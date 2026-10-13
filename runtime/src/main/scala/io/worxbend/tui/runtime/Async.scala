package io.worxbend.tui.runtime

import java.util.concurrent.{
  ArrayBlockingQueue,
  ScheduledExecutorService,
  ScheduledThreadPoolExecutor,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit,
}

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/** A handle to cancel a scheduled or repeating task started through [[Async]]. */
trait Cancelable:
  def cancel(): Unit

/** Structured background work for a signals-driven app — glyphora's answer to bubbletea's `Cmd`/`Msg`.
  *
  * The problem it solves: [[Signal]]s may only be mutated on the render thread ([[RenderThread]]), so a naive
  * `new Thread { data.set(fetch()) }` throws. `Async` runs the work off-thread, then **marshals the continuation back
  * onto the render thread** (via [[RenderThread.runLater]]), where it may safely update signals. The runner drains that
  * queue at the top of each loop iteration and repaints if a signal changed — so the result appears as an ordinary
  * reactive update, no `Msg` plumbing.
  *
  * All executor threads are daemons, so pending work never keeps the JVM alive after the app quits. Callbacks scheduled
  * while no runner is active simply wait on the queue until one drains it (or are dropped when the process exits).
  *
  * **Lifetime.** Every entry point here captures its target runner at the moment it is called, and that runner's queue
  * stops accepting work once the runner exits. Concretely:
  *
  *   - A bounded final batch of queued work runs before the runner retires; any remaining callbacks are discarded.
  *   - Work queued *after* that is silently dropped — there is no longer a render thread to run it on, and keeping it
  *     would only grow memory.
  *   - A [[Cancelable]] from [[after]] or [[every]] is **not** cancelled by the app quitting. The scheduler is a
  *     process-lifetime daemon singleton, so an uncancelled `every` keeps firing (into a queue that now discards its
  *     bodies) until the JVM exits. Cancel it when the app quits — from `TuiApp.onStop`, which runs on every exit path
  *     including Ctrl+C, or wherever a bare [[Runner]] app finishes — which matters most in the embedded and
  *     multiple-runner cases this module is built for.
  *
  * The mirror-image rule applies to *starting* repeating work. Every entry point here captures the render loop of the
  * thread that calls it, and a thread that belongs to no runner — a constructor running before any runner is registered
  * — captures the shared unattributed queue instead. Those bodies wait for a runner, but repeating ticks coalesce to
  * one pending delivery per timer. Detached admission throws at 256 outstanding callbacks/reservations; an active owner
  * allows 1024. Start repeating work from `TuiApp.onStart` (or a bare runner's `onStart`).
  *
  * **Admission.** Submission never waits for capacity and never runs background work on the submitting thread.
  * `RejectedExecutionException` escapes synchronously if either the worker backlog or captured owner is full; it is not
  * a work failure delivered to an error handler or `Left`. Accepted work reserves its continuation slot before
  * starting, including the default error report, so completion cannot recursively overload the owner's queue. Timers
  * reserve one slot until one-shot delivery or cancellation (repeating timers retain it until cancellation). These are
  * per-owner bounds, not a global limit on owners or scheduler registrations. Unscoped timers still require explicit
  * cancellation on owner exit; cancelled scheduler entries are removed immediately.
  */
object Async:

  /** Creates a closeable task group bound to this thread's current runner. Create in `onStart` / `onEnter`, not a
    * constructor. Close in `onStop` / `onLeave` for early cancellation; owner retirement also closes it.
    */
  def scope(): TaskScope =
    val owner = RenderThread.currentOwner.getOrElse(
      throw IllegalStateException("Async.scope requires a registered render owner")
    )
    val scope = new TaskScope(owner)
    owner.attach(scope)
    scope

  /** Runs `work` on a background thread; when it finishes, `onResult` runs on the render thread with the value. Use for
    * one-shot IO (HTTP, disk) whose result feeds a signal: `Async.run(api.fetch())(rows.set)`. See [[runCatching]] for
    * an `Either` result the app handles itself.
    *
    * `work` failing is the ordinary case, not a defect — a fetch times out, a file is missing — so the default
    * `onError` hands the throwable back to the render loop that armed the call ([[AsyncErrorHandler.toRenderThread]]),
    * where the runner reports it as `RunnerError.QueuedTask` with the others. Throwing it on the worker thread instead
    * (still available as [[AsyncErrorHandler.rethrow]]) prints a stack trace over the alternate screen the app is
    * drawing on, which the frame diff never repaints. Install a `given AsyncErrorHandler` to take reporting over.
    *
    * The default is evaluated at the call site, on the calling thread, which is what lets it capture the right loop.
    *
    * The worker pool has `max(2, availableProcessors)` threads and 256 waiting submissions. Admission throws
    * `RejectedExecutionException` synchronously if that backlog or the captured owner's callback capacity is full.
    * Rejected work never starts; accepted work reserves space for its result or built-in render-thread error report.
    * Only failures from `work` go to `onError`. Dispatch failures (for example, a throwing owner wakeup) escape to the
    * worker's uncaught-exception handler instead; a callback already queued before a wakeup fails remains accepted.
    */
  def run[A](work: => A)(onResult: A => Unit)(using
      onError: AsyncErrorHandler = AsyncErrorHandler.toRenderThread()
  ): Unit =
    val target   = captureTarget()
    val delivery = target.reserve()
    val errors   = onError.forInvocation(target, delivery.enqueue)
    try
      onWorker {
        try
          val outcome: Either[Throwable, A] =
            try Right(work)
            catch case NonFatal(error) => Left(error)
          outcome match
            case Right(value) => delivery.enqueue(() => onResult(value))
            case Left(error)  => errors.handle(error)
        finally delivery.releaseIfUnused()
      }
    catch
      case NonFatal(error) =>
        delivery.cancel()
        throw error

  /** Like [[run]] but delivers `Right(value)` or `Left(throwable)` to `onDone` on the render thread — no ambient error
    * handler needed. The idiomatic way to drive a load into `Signal[Either[Throwable, A]]` (or a loading/error state).
    *
    * Same admission contract as [[run]]: overload throws synchronously, rather than becoming a `Left` callback.
    * Dispatch failures escape to the worker's uncaught-exception handler, not to `onDone` as a work failure.
    */
  def runCatching[A](work: => A)(onDone: Either[Throwable, A] => Unit): Unit =
    val delivery = captureTarget().reserve()
    try
      onWorker {
        try
          val outcome: Either[Throwable, A] =
            try Right(work)
            catch case NonFatal(error) => Left(error)
          delivery.enqueue(() => onDone(outcome))
        finally delivery.releaseIfUnused()
      }
    catch
      case NonFatal(error) =>
        delivery.cancel()
        throw error

  /** Runs `body` on the render thread once, after `delay`. Cancellation also discards queued callbacks that have not
    * started, without interrupting a body already running. Reserves a callback slot now; throws
    * `RejectedExecutionException` synchronously on owner overload.
    */
  def after(delay: FiniteDuration)(body: => Unit): Cancelable =
    scheduleCancelable(body, repeat = false)(task => scheduler.schedule(task, delay.toMillis, TimeUnit.MILLISECONDS))

  /** The floor for a repeating interval: `scheduleAtFixedRate` rejects a non-positive period. */
  private val MinIntervalMillis: Long = 1L

  /** Runs `body` on the render thread every `interval` (first tick after one `interval`). Returns a handle to stop it.
    * The place to drive animation or polling without a global `config.tickRate`. Ticks while a callback is queued or
    * running are coalesced, not replayed. One callback slot is reserved until cancellation; overload throws at arming.
    */
  def every(interval: FiniteDuration)(body: => Unit): Cancelable =
    val millis = math.max(MinIntervalMillis, interval.toMillis)
    scheduleCancelable(body, repeat = true)(task =>
      scheduler.scheduleAtFixedRate(task, millis, millis, TimeUnit.MILLISECONDS)
    )

  /** Execute rather than submit: escaping failures (including a throwing error policy) must reach the worker's
    * uncaught-exception handler, not disappear inside an unobserved Future.
    */
  private[runtime] def onWorker(body: => Unit): Unit =
    worker.execute(() => body)

  /** The render loop of the calling thread, resolved *now* — the one capture every entry point here must do on the
    * caller's thread, before any work is handed to an executor. Resolving it later, from the worker, can deliver the
    * continuation to the wrong runner.
    */
  private[runtime] def captureTarget(): RenderThread.RenderLoop = RenderThread.capture()

  /** Reserve on the caller's owner before scheduling, and gate cancellation at delivery, not just in the scheduler. A
    * repeating timer holds exactly one slot even while the owner is stalled.
    */
  private def scheduleCancelable(body: => Unit, repeat: Boolean)(
      schedule: Runnable => java.util.concurrent.Future[?]
  ): Cancelable =
    val delivery = captureTarget().reserve()
    try
      val future = schedule(() =>
        if repeat then delivery.enqueueRepeating(() => body)
        else delivery.enqueue(() => body)
      )
      () =>
        delivery.cancel()
        val _ = future.cancel(false)
    catch
      case NonFatal(error) =>
        delivery.cancel()
        throw error

  /** Process-lifetime daemon workers with 256 waiting submissions and nonblocking rejection, never CallerRuns on UI.
    */
  private val WorkerThreads = math.max(2, Runtime.getRuntime.availableProcessors())
  private val worker        = new ThreadPoolExecutor(
    WorkerThreads,
    WorkerThreads,
    0L,
    TimeUnit.MILLISECONDS,
    new ArrayBlockingQueue[Runnable](256),
    daemonFactory("glyphora-async"),
    new ThreadPoolExecutor.AbortPolicy(),
  )

  private[runtime] val scheduler: ScheduledExecutorService =
    val executor = new ScheduledThreadPoolExecutor(1, daemonFactory("glyphora-timer"))
    executor.setRemoveOnCancelPolicy(true)
    executor

  private def daemonFactory(prefix: String): ThreadFactory =
    new ThreadFactory:
      private val counter                       = new java.util.concurrent.atomic.AtomicInteger(0)
      def newThread(runnable: Runnable): Thread =
        val thread = new Thread(runnable, s"$prefix-${counter.getAndIncrement()}")
        thread.setDaemon(true)
        thread

/** How [[Async.run]] reports a failure of its background work. Provided as a `using` value so apps can install their
  * own (log, toast, set an error signal) without changing call sites.
  */
trait AsyncErrorHandler:
  def handle(error: Throwable): Unit

  /** Binds a reporting policy to the runner arming this invocation. Worker-side policies keep their behavior. */
  private[runtime] def forInvocation(target: RenderThread.RenderLoop): AsyncErrorHandler =
    val _ = target
    this

  private[runtime] def forInvocation(
      target: RenderThread.RenderLoop,
      enqueue: (() => Unit) => Unit,
  ): AsyncErrorHandler =
    val _ = enqueue
    forInvocation(target)

object AsyncErrorHandler:
  /** Rethrows on the worker thread, where the JVM's default uncaught-exception handler prints the stack trace to
    * standard error.
    *
    * For a terminal app that is the *same* tty the UI is drawn on, so the trace lands on top of the alternate screen
    * and stays there: the backend flushes only cells that differ from the frame it last flushed, and that frame still
    * describes what the app believes is on screen. Nothing repaints until a resize. Use this only where standard error
    * is not the UI — a bare [[Runner]] app writing elsewhere, or a process with no runner at all. [[toRenderThread]] is
    * the default for [[Async.run]] for exactly this reason.
    */
  val rethrow: AsyncErrorHandler = error => throw error

  /** Swallows the error silently. */
  val ignore: AsyncErrorHandler = _ => ()

  /** Rethrows on the render loop of the calling thread: the throwable is queued back to that runner, which absorbs it
    * through its `RenderTaskErrorHandler` and reports it as `RunnerError.QueuedTask` — count, first throwable and
    * suppressed stack traces — once the app exits. The default for [[Async.run]].
    *
    * `Async.run` binds the destination per invocation, so this policy is also safe as a long-lived `given` shared
    * between runners. Direct calls to `handle` retain the loop captured when the policy was constructed.
    */
  def toRenderThread(): AsyncErrorHandler =
    bound(Async.captureTarget(), error => throw error)

  /** Reports on the runner arming each `Async.run`, not the worker's globally resolved destination. Safe to share as a
    * long-lived given. A direct `handle` outside `Async.run` uses ordinary render-thread routing.
    */
  def onRenderThread(report: Throwable => Unit): AsyncErrorHandler =
    new AsyncErrorHandler:
      def handle(error: Throwable): Unit = RenderThread.runLater(report(error))
      override private[runtime] def forInvocation(target: RenderThread.RenderLoop): AsyncErrorHandler =
        bound(target, report)
      override private[runtime] def forInvocation(
          target: RenderThread.RenderLoop,
          enqueue: (() => Unit) => Unit,
      ): AsyncErrorHandler                                                                            =
        val _ = target
        error => enqueue(() => report(error))

  private def bound(target: RenderThread.RenderLoop, report: Throwable => Unit): AsyncErrorHandler =
    new AsyncErrorHandler:
      def handle(error: Throwable): Unit = target.enqueue(() => report(error))
      override private[runtime] def forInvocation(owner: RenderThread.RenderLoop): AsyncErrorHandler =
        bound(owner, report)
      override private[runtime] def forInvocation(
          owner: RenderThread.RenderLoop,
          enqueue: (() => Unit) => Unit,
      ): AsyncErrorHandler                                                                           =
        val _ = owner
        error => enqueue(() => report(error))
