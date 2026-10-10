package io.worxbend.tui.runtime

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
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

  /** One runner's queue of work waiting to run on its render thread.
    *
    * `onError` is supplied by the runner that owns this loop and receives throwables escaping queued bodies.
    *
    * `limit`, when set, caps how many bodies may wait here: past the cap the *oldest* is dropped, and [[dropCount]]
    * counts how many were. A runner's own loop is unlimited — its bodies are owed to a thread that is draining them
    * every iteration, and dropping one would lose a continuation the app is waiting for. The shared `detached` queue
    * sets a limit because it has no such thread: see [[RenderThread.detached]].
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
    private val queued  = AtomicInteger(0)
    private val dropped = AtomicLong(0)

    // read by every thread that queues work, written by the runner thread that owned this loop
    @volatile private var closed   = false
    @volatile private var stopping = false

    /** Non-consumable cancellation: runner state, never an application input event. Safe from any thread. */
    private[tui] def requestStop(): Unit =
      stopping = true
      if !closed then wake()

    private[runtime] def stopRequested: Boolean = stopping

    /** Dispatches to this captured owner: inline on its current render thread, queued otherwise. Returns false when
      * already retired. Acceptance is not a completion guarantee: retirement can discard queued work. Capture on the
      * owning thread before registering an external callback, rather than resolving an ambiguous runner on delivery.
      */
    def execute(body: => Unit): Boolean =
      if closed then false
      else
        val own = loops.get(Thread.currentThread())
        if own != null && (own.head eq this) then body // scalafix:ok DisableSyntax; java.util.concurrent interop
        else enqueue(() => body)
        true

    /** Queues `body` and nudges the runner, so it is picked up on the next iteration rather than at the next poll.
      *
      * A body queued after [[close]] is **dropped**: its runner has exited and nothing will ever drain this queue
      * again, so keeping it would only grow memory. So is the oldest body still waiting, once a `limit` is set and
      * reached. See [[Async]] for what that means for scheduled work.
      */
    private[runtime] def enqueue(body: () => Unit): Unit =
      if !closed then
        val _ = pending.add(body)
        val _ = queued.incrementAndGet()
        // re-check after the add: close() retires this queue concurrently, and a body that lands after its clear would
        // sit in `pending` for the life of the JVM with `queued` one higher than anything will ever drain back down
        if closed && pending.remove(body) then
          val _ = queued.decrementAndGet()
        else
          limit.foreach(trimTo)
          wake()

    /** Drops the oldest waiting bodies until at most `cap` are left.
      *
      * The oldest rather than the newest, because this queue's bodies are continuations of work that has already
      * happened: if only some of them can be kept, the ones describing the most recent state are the ones worth
      * keeping. Stops early when the queue turns out to be empty — `queued` is incremented just after the body lands
      * and decremented just after one is taken, so it can momentarily exceed what is actually in the queue, and this
      * loop must not spin waiting for a body that a concurrent drain already took.
      */
    private def trimTo(cap: Int): Unit =
      var trimming = queued.get() > cap
      while trimming do
        val evicted = pending.poll()
        if evicted == null then trimming = false // scalafix:ok DisableSyntax; java.util.concurrent interop
        else
          val _ = queued.decrementAndGet()
          val _ = dropped.incrementAndGet()
          trimming = queued.get() > cap

    /** Retires this loop: whatever is still pending is discarded and nothing new is accepted.
      *
      * Called by the runner that owns this loop, once, as it exits — after a bounded final [[drain]] and confined
      * cleanup. Work exceeding that final allowance is deliberately discarded. [[Async]]'s scheduler is a daemon
      * singleton: an `Async.every(16.millis)` that was never cancelled holds a reference to this loop and, without
      * this, would keep appending sixty closures a second to a queue nothing drains, for the life of the JVM.
      */
    private[runtime] def close(): Unit =
      closed = true
      taskScopes.forEach(scope => scope.close())
      taskScopes.clear()
      pending.clear()
      queued.set(0)

    /** How many bodies this loop dropped because its `limit` was reached. Always `0` for an unlimited loop. */
    private[runtime] def dropCount: Long = dropped.get()

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
    private def take(): () => Unit =
      val task = pending.poll()
      val _    = if task != null then queued.decrementAndGet() else 0 // scalafix:ok DisableSyntax; queue interop
      task

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

  /** How many bodies may wait on the unattributed queue before the oldest start being dropped.
    *
    * A frame is 16 ms, so this is roughly four seconds of a 60 Hz timer firing into a queue nobody drains: past that,
    * work armed for a runner that never started is not a backlog anyone is owed. Declared before `detached` because it
    * is read while that field is initialised.
    */
  private[runtime] val DetachedQueueLimit: Int = 256

  /** Work queued when the caller belongs to no runner and more than one is running — genuinely ambiguous, so it is
    * drained by whichever render thread gets there first. With zero or one runner the routing is exact.
    *
    * This is the one loop nobody owns: it is never closed, and nothing is guaranteed ever to drain it. An
    * `Async.every(16.millis)` armed before any runner starts lands here and, unbounded, would append sixty closures a
    * second for the life of the JVM — the same leak [[RenderLoop.close]] exists to prevent for an owned loop — and then
    * run every one of them at once the moment some runner drained it. Hence [[DetachedQueueLimit]]: enough depth that a
    * burst of continuations arriving just before a runner starts is delivered intact, and a ceiling past which the
    * oldest are dropped rather than accumulated. [[detachedDrops]] makes that observable.
    */
  private val detached = RenderLoop(() => (), RenderTaskErrorHandler.rethrow, Some(DetachedQueueLimit))

  /** How many unattributed bodies have been dropped for exceeding [[DetachedQueueLimit]] since the process started.
    *
    * Package-private, for the test that pins the bound: a queue that silently drops work needs one number that says it
    * did.
    */
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

  /** Always queues `body`; the runner executes queued work at the start of each loop iteration. */
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
