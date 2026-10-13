package io.worxbend.tui.runtime

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, RejectedExecutionException}
import scala.util.control.NonFatal

/** The single-render-thread model.
  *
  * All UI state mutation must happen on a render thread — the thread running a [[Runner]] loop. The guard is
  * deliberately a no-op while no render thread is registered, so unit tests of widgets and signals need no running
  * runtime.
  *
  * Each runner owns its own work queue rather than sharing one process-wide, so two runners in the same JVM (parallel
  * test suites, an embedded app) never execute each other's queued work on the wrong thread.
  */
object RenderThread:

  /** Maximum callbacks per queue per turn; owned and detached queues each get a share so neither starves. */
  private[runtime] val TurnBudget: Int = 256

  /** Teardown's independent callback allowance per queue, before confined cleanup and owned-queue retirement. */
  private[runtime] val FinalDrainBudget: Int = 256

  /** Active-owner capacity, including queued callbacks and reserved async/timer deliveries. */
  private[runtime] val OwnedQueueLimit: Int = 1024

  /** One runner's queue of work waiting to run on its render thread.
    *
    * `onError` is supplied by the runner that owns this loop and receives throwables escaping queued bodies.
    *
    * Admission is nonblocking: active owners allow 1024 outstanding callbacks, including reservations made before async
    * work or timers start. The detached queue allows 256. At capacity submission throws `RejectedExecutionException`;
    * it never evicts an accepted continuation or runs queued work on the producer. Repeating timers retain one
    * reservation until cancelled and coalesce ticks while delivery is pending/running.
    */
  final class RenderLoop private[runtime] (
      wake: () => Unit,
      private[runtime] val onError: RenderTaskErrorHandler,
      limit: Option[Int] = None,
  ):

    private val pending    = ConcurrentLinkedQueue[() => Unit]()
    private val taskScopes = ConcurrentHashMap.newKeySet[TaskScope]()

    private[runtime] def isClosed: Boolean = closed

    private[runtime] def attach(scope: TaskScope): Unit =
      val _ = taskScopes.add(scope)
      if closed then scope.close()

    private[runtime] def detach(scope: TaskScope): Unit =
      val _ = taskScopes.remove(scope)

    // `ConcurrentLinkedQueue.size` walks the whole queue, so the depth is counted alongside it instead: `enqueue` is on
    // the hot path of every Async continuation and timer tick
    private val queued   = AtomicInteger(0)
    private var admitted = 0

    /** Reserves one continuation slot before background work starts. Reservations count against active-owner capacity
      * even before delivery; cancellation frees the slot and removes an already queued callback.
      */
    private[runtime] def reserve(): Delivery = synchronized {
      if !closed then acquire()
      new Delivery(!closed)
    }

    private def acquire(): Unit =
      val capacity = limit.getOrElse(OwnedQueueLimit)
      if admitted >= capacity then throw RejectedExecutionException(s"render callback capacity ($capacity) exhausted")
      admitted += 1

    private[runtime] final class Delivery private[RenderLoop] (initiallyActive: Boolean) extends Cancelable:
      private var active   = initiallyActive
      private var callback = Option.empty[() => Unit]

      def enqueue(body: () => Unit): Unit = enqueue(body, repeat = false)

      def enqueueRepeating(body: () => Unit): Unit = enqueue(body, repeat = true)

      private def enqueue(body: () => Unit, repeat: Boolean): Unit =
        val enqueued = RenderLoop.this.synchronized {
          if active && !closed && callback.isEmpty then
            val task = () => {
              val accepted = RenderLoop.this.synchronized {
                val wasActive = active && !closed
                if !repeat then
                  callback = None
                  cancel()
                wasActive
              }
              try if accepted then body()
              finally if repeat then RenderLoop.this.synchronized { callback = None }
            }
            callback = Some(task)
            val _    = pending.add(task)
            val _    = queued.incrementAndGet()
            true
          else false
        }
        if enqueued then wake()

      def releaseIfUnused(): Unit = RenderLoop.this.synchronized {
        if callback.isEmpty then cancel()
      }

      def cancel(): Unit = RenderLoop.this.synchronized {
        if active then
          active = false
          admitted -= 1
          callback.foreach { task =>
            if pending.remove(task) then { val _ = queued.decrementAndGet() }
          }
          callback = None
      }

    // read by every thread that queues work, written by the runner thread that owned this loop
    @volatile private var closed   = false
    @volatile private var stopping = false

    /** Non-consumable cancellation: runner state, never an application input event. Safe from any thread. */
    private[tui] def requestStop(): Unit =
      stopping = true
      if !closed then wake()

    private[runtime] def stopRequested: Boolean = stopping

    /** Dispatches to this captured owner: inline on its current render thread, queued otherwise. Returns false when
      * retired; off-thread overload throws `RejectedExecutionException` without running or evicting any callback.
      * Acceptance is not a completion guarantee: retirement can discard queued work. Capture on the owning thread
      * before registering an external callback, rather than resolving an ambiguous runner on delivery.
      */
    def execute(body: => Unit): Boolean =
      if closed then false
      else
        val own = loops.get(Thread.currentThread())
        if own != null && (own.head eq this) then // scalafix:ok DisableSyntax; java.util.concurrent interop
          body
          true
        else offer(() => body)

    /** Queues a callback without blocking. Throws `RejectedExecutionException` at capacity; accepted callbacks are
      * never evicted to make room. A retired owner still discards late work, as it has no thread left to drain it.
      */
    private[runtime] def enqueue(body: () => Unit): Unit =
      val _ = offer(body)

    private def offer(body: () => Unit): Boolean =
      val accepted = synchronized {
        if closed then false
        else
          acquire()
          val task = () => {
            synchronized { admitted -= 1 }
            body()
          }
          val _    = pending.add(task)
          val _    = queued.incrementAndGet()
          true
      }
      if accepted then wake()
      accepted

    /** Retires this loop: whatever is still pending is discarded and nothing new is accepted.
      *
      * Called by the runner that owns this loop, once, as it exits — after a bounded final [[drain]] and confined
      * cleanup. Work exceeding that final allowance is deliberately discarded. [[Async]]'s scheduler is a daemon
      * singleton: an `Async.every(16.millis)` that was never cancelled holds a reference to this loop and, without
      * this, would keep appending sixty closures a second to a queue nothing drains, for the life of the JVM.
      */
    private[runtime] def close(): Unit =
      synchronized {
        closed = true
        pending.clear()
        queued.set(0)
      }
      taskScopes.forEach(scope => scope.close())
      taskScopes.clear()

    /** Compatibility observation: overload now rejects instead of evicting, so this is always zero. */
    private[runtime] def dropCount: Long = 0L

    /** Runs at most `budget` queued bodies in FIFO order, including successors queued during this turn. Remaining work
      * stays queued. A `NonFatal` throwable goes to `handler` and consumes one slot just like successful work; fatal
      * errors and handler failures propagate. This bounds callback count, not the duration of a running body.
      *
      * Runs on the render thread that owns this loop.
      */
    private[runtime] def drain(handler: RenderTaskErrorHandler = onError, budget: Int = TurnBudget): Unit =
      var remaining = budget
      var draining  = true
      while remaining > 0 && draining do
        val task = take()
        if task == null then draining = false // scalafix:ok DisableSyntax; java.util.concurrent interop
        else
          remaining -= 1
          try task()
          catch case NonFatal(error) => handler.handle(error)

    /** A queue observation, not the approximate depth counter: concurrent enqueue also wakes the backend. */
    private[runtime] def hasPending: Boolean = !pending.isEmpty

    /** Takes the next waiting body, or `null` when there is none, keeping the depth count in step. */
    private def take(): () => Unit = synchronized {
      val task = pending.poll()
      val _    = if task != null then queued.decrementAndGet() else 0 // scalafix:ok DisableSyntax; queue interop
      task
    }

  // Several runners may live in one JVM and must not race on a single registration slot. Each thread maps to a stack
  // (innermost registration first) so a runner started from inside another runner's loop restores its host on exit
  // instead of deregistering the thread outright.
  private val loops = ConcurrentHashMap[Thread, List[RenderLoop]]()

  // Test-support observes startup on the app thread, rather than resolving a destination on its calling thread.
  private val registrationObserver = new ThreadLocal[RenderLoop => Unit]()

  private[tui] def observingRegistration[A](observe: RenderLoop => Unit)(body: => A): A =
    val enclosing = registrationObserver.get()
    registrationObserver.set(observe)
    try body
    finally
      if enclosing == null then registrationObserver.remove() // scalafix:ok DisableSyntax; java interop
      else registrationObserver.set(enclosing)

  /** Detached callback/reservation capacity; unlike a runner queue, it has no guaranteed consumer. */
  private[runtime] val DetachedQueueLimit: Int = 256

  /** Work queued when the caller belongs to no runner and more than one is running — genuinely ambiguous, so it is
    * drained by whichever render thread gets there first. With zero or one runner the routing is exact.
    *
    * This loop is never closed and has no guaranteed consumer. Admission throws at [[DetachedQueueLimit]], rather than
    * silently replacing required continuations. Start repeating work on the owning runner, not in constructors.
    */
  private val detached = RenderLoop(() => (), RenderTaskErrorHandler.rethrow, Some(DetachedQueueLimit))

  /** Compatibility observation: detached overload rejects; accepted bodies are not evicted. */
  private[runtime] def detachedDrops: Long = detached.dropCount

  /** Whether the calling thread may mutate UI state.
    *
    * `true` when this thread has a registered [[RenderLoop]] — and, deliberately, also when *no* loop is registered
    * anywhere: with no runtime running there is no render thread to be off, so plain unit tests of widgets and signals
    * need no runner. A caller reading this as "am I on the render thread?" gets `true` in that zero-runner case.
    */
  def isRenderThread: Boolean =
    loops.isEmpty || loops.containsKey(Thread.currentThread())

  /** Defect-detection assertion: throws `IllegalStateException` when called off the render thread while one is
    * registered. A programming error, not a recoverable condition — hence throw, not `Either`.
    *
    * The message names the remedy as well as the fault, because "wrong thread" on its own leaves the reader to guess
    * which of the two fixes applies: almost always the write belongs in an `Async` continuation (which already resumes
    * here), and only work owned by a third-party library's own callback thread needs the explicit hop.
    */
  def checkRenderThread(): Unit =
    if !isRenderThread then
      throw IllegalStateException(
        s"UI state must be mutated on the render thread, not '${Thread.currentThread().getName}'. " +
          "Set it from an Async.run/runCatching callback (which already resumes on the render thread), " +
          "or wrap the write in RenderThread.runOnRenderThread { ... }."
      )

  /** Runs `body` inline when already on the render thread, otherwise queues it for the next loop iteration. */
  def runOnRenderThread(body: => Unit): Unit =
    if isRenderThread then body else runLater(body)

  /** Always queues `body`; throws `RejectedExecutionException` on overload, never blocking for capacity. The runner
    * executes accepted work in finite batches. Late work on a retired owner is discarded.
    */
  def runLater(body: => Unit): Unit =
    capture().enqueue(() => body)

  /** The calling thread's innermost owner only; never guesses another runner's destination. */
  private[runtime] def currentOwner: Option[RenderLoop] =
    Option(loops.get(Thread.currentThread())).map(_.head)

  /** The loop that should receive work queued from *this* thread, resolved in three steps.
    *
    *   1. The calling thread's own innermost registration, when it has one — a runner's own continuations always come
    *      back to that runner.
    *   1. Otherwise the sole registered loop, when exactly one runner exists. A background worker that never registered
    *      itself still has an unambiguous destination.
    *   1. Otherwise the shared `detached` queue: with no runner there is nobody to deliver to yet, and with several the
    *      destination is genuinely ambiguous, so the work goes to whichever render thread drains it first.
    *
    * Background workers must call this while still on the render thread (before they go async) so their continuation
    * comes back to the runner that started it — see [[Async]].
    */
  def capture(): RenderLoop =
    val own = loops.get(Thread.currentThread())
    if own != null then own.head // scalafix:ok DisableSyntax; java.util.concurrent interop
    else soleRegisteredLoop.getOrElse(detached)

  /** The one registered loop when exactly one runner is running, `None` when there are zero or several.
    *
    * Reads the registry through a single iterator rather than `loops.size`: the map is concurrent, so asking for a size
    * and then for the entry can straddle a registration, while one traversal answers "is there exactly one" and "which
    * one" from the same view.
    */
  private def soleRegisteredLoop: Option[RenderLoop] =
    val all = loops.values.iterator
    if !all.hasNext then None
    else
      val first = all.next()
      if all.hasNext then None else Some(first.head)

  /** Registers `thread` as a render thread and returns its queue. `wake` nudges a blocked runner when work is queued
    * (drivers that poll instead of blocking can leave it at the no-op default); `onError` receives throwables escaping
    * queued bodies drained by this loop, and defaults to rethrowing so an unowned loop never swallows a failure.
    */
  private[tui] def register(
      thread: Thread,
      wake: () => Unit = () => (),
      onError: RenderTaskErrorHandler = RenderTaskErrorHandler.rethrow,
  ): RenderLoop =
    val loop = RenderLoop(wake, onError)
    val _    = loops.compute(
      thread,
      (_, enclosing) => loop :: (if enclosing == null then Nil else enclosing),
    ) // scalafix:ok DisableSyntax; java.util.concurrent interop
    Option(registrationObserver.get()).foreach(observe => observe(loop))
    loop

  /** Retires the calling thread's innermost registration, restoring an enclosing runner's if there is one. */
  private[tui] def unregister(): Unit =
    currentOwner.foreach(_.close())
    val _        = loops.compute(
      Thread.currentThread(),
      (_, registered) =>
        val enclosing =
          if registered == null then Nil
          else registered.drop(1) // scalafix:ok DisableSyntax; java.util.concurrent interop
        if enclosing.isEmpty then null else enclosing, // scalafix:ok DisableSyntax; java.util.concurrent interop
    )
    // Returning from a nested runner restores its enclosing owner for integrations observing this app thread.
    val restored = loops.get(Thread.currentThread())
    if restored != null then // scalafix:ok DisableSyntax; java.util.concurrent interop
      Option(registrationObserver.get()).foreach(observe => observe(restored.head))

  /** Whether a bounded turn left runnable work. Each queue still gets its own finite share on the next turn. */
  private[runtime] def hasPending(loop: RenderLoop): Boolean = loop.hasPending || detached.hasPending

  /** Runs one bounded FIFO batch per queue, including work unattributed to a specific runner. Unattributed failures are
    * reported through `loop`'s handler: the detached queue has no owner of its own, and `loop` is the one whose runner
    * would otherwise be taken down by them.
    */
  private[tui] def drainPending(loop: RenderLoop, budgetPerQueue: Int = TurnBudget): Unit =
    loop.drain(budget = budgetPerQueue)
    detached.drain(loop.onError, budgetPerQueue)

  /** Drains whatever is queued for the calling thread, plus unattributed work. With no runner registered the detached
    * queue keeps its own rethrowing handler, so a failing body still surfaces on the caller's thread.
    */
  private[tui] def drainPending(): Unit =
    val own = loops.get(Thread.currentThread())
    if own != null then drainPending(own.head) // scalafix:ok DisableSyntax; java.util.concurrent interop
    else detached.drain()

/** How a render loop reports a `NonFatal` throwable escaping a body queued with [[RenderThread.runLater]] — typically
  * the continuation of some background work (see [[Async]]).
  *
  * Installed per loop rather than passed at the call site the way [[AsyncErrorHandler]] is: each runner owns its own
  * queue, and the queue is drained long after the caller that filled it is gone. It runs on the render thread, inside
  * the drain; anything it throws propagates and stops that drain, which is exactly how
  * [[RenderTaskErrorHandler.rethrow]] surfaces a failure on a loop no runner owns.
  */
trait RenderTaskErrorHandler:
  def handle(error: Throwable): Unit

object RenderTaskErrorHandler:
  /** Rethrows on the render thread. The default for an unowned loop, so a failing continuation in a test that drives
    * [[RenderThread]] directly still fails that test instead of vanishing.
    */
  val rethrow: RenderTaskErrorHandler = error => throw error
