package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Buffer, Event, Position, Widget}
import io.worxbend.tui.terminal.{Backend, BackendError}

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.util.control.NonFatal

/** The production [[Runner]]: raw mode + alternate screen setup, diff-driven redraws, tick emission, resize handling,
  * and render-thread registration around a [[Backend]].
  *
  * `nanoTime` is injectable so tick scheduling is testable; production code uses the system clock.
  *
  * `redrawRequested` is the host's own "is a frame owed?" question, asked once per iteration — a `TuiApp` answers it
  * from its signal-invalidation flag. It is read alongside, not instead of, [[RunnerHandle.requestRedraw]]: the handle
  * serves code that has no reactive state to invalidate.
  *
  * `onStop` is the registered-loop teardown seam: it runs once on the owner thread after a bounded final queued-work
  * drain (at most 256 callbacks per queue), before the queue is closed, the owner is unregistered and the backend is
  * closed. Remaining owned callbacks are discarded; running callbacks are never interrupted. It also runs after partial
  * setup failure. Use it to dispose thread-confined subscriptions and services. Every release is attempted even if an
  * earlier one throws; a prior run failure stays primary, with secondary failures exposed by
  * [[RunnerError.cleanupFailures]] (and suppressed on its throwable when it has one). A first non-fatal teardown
  * throwable is returned as [[RunnerError.Handler]]; fatal throwables escape only after cleanup completes.
  */
final class TerminalRunner(
    backend: Backend,
    config: RunnerConfig = RunnerConfig(),
    nanoTime: () => Long = () => System.nanoTime(),
    redrawRequested: () => Boolean = () => false,
    onStop: () => Unit = () => (),
) extends Runner:

  def run(
      onStart: RunnerHandle => Unit,
      handleEvent: (Event, RunnerHandle) => EventOutcome,
      render: Frame => Unit,
  ): Either[RunnerError, Unit] =
    // Installed *before* the terminal is dressed up. Between `setup()` and this line the app runs in raw mode on the
    // alternate screen with no protection at all, and a signal that terminates the JVM directly (SIGTERM, SIGHUP)
    // skips straight to shutdown hooks — so a kill landing in that window used to hand back an unusable terminal.
    // Installing it early costs nothing: every sequence the hook writes is an idempotent DEC private-mode reset, so it
    // is harmless against a terminal on which nothing was ever enabled.
    val restoreOnShutdown = installRestoreHook()
    try runRegistered(onStart, handleEvent, render)
    finally removeRestoreHook(restoreOnShutdown)

  /** Runs the loop with this thread registered as the render thread, and tears that registration down around it.
    *
    * Split out of [[run]] so the teardown that must happen (unregister, close the work queue, restore the terminal) is
    * not tangled with the shutdown hook's own lifetime, which spans the setup that happens before any of it exists.
    */
  private def runRegistered(
      onStart: RunnerHandle => Unit,
      handleEvent: (Event, RunnerHandle) => EventOutcome,
      render: Frame => Unit,
  ): Either[RunnerError, Unit] =
    val recorder = new QueuedTaskFailureRecorder
    val loop     = RenderThread.register(
      Thread.currentThread(),
      () => backend.wake(),
      config.onTaskError.getOrElse(recorder),
    )

    var result: Either[RunnerError, Unit] = Right(())
    var thrown: Option[Throwable]         = None
    var setupIncomplete                   = true

    def recordCleanup(error: Throwable): Unit =
      thrown match
        case Some(primary) => Cleanup.suppress(primary, error)
        case None          =>
          result match
            case Left(primary) =>
              primary.addCleanupFailure(error)
              error.getSuppressed.foreach(primary.addCleanupFailure)
            case Right(())     =>
              if NonFatal(error) then
                val primary = RunnerError.Handler(error)
                error.getSuppressed.foreach(primary.addCleanupFailure)
                result = Left(primary)
              else thrown = Some(error)

    def release(body: => Unit): Unit =
      val cleanup = new Cleanup
      cleanup.attempt(body)
      cleanup.collected.foreach(recordCleanup)

    try
      setup() match
        case Left(error) =>
          result = Left(RunnerError.Backend(error))
        case Right(())   =>
          setupIncomplete = false
          val outcome = runLoop(onStart, handleEvent, render, loop)
          result = report(outcome, recorder.collected)
          // One final bounded batch per queue, not a drain-until-empty: self-requeueing producers cannot postpone
          // onStop and backend restoration forever. Remaining owned work is discarded by close after onStop.
          RenderThread.drainPending(loop, RenderThread.FinalDrainBudget)
          result = report(outcome, recorder.collected)
    catch
      case error: Throwable =>
        result match
          case Left(primary) => primary.addCleanupFailure(error)
          case Right(())     => thrown = Some(error)
    finally
      // Confined resources are disposed while their owner remains registered. Each release is attempted independently.
      release(onStop())
      release(loop.close())
      release(RenderThread.unregister())
      release {
        backend.close() match
          case Left(error) =>
            if result.isRight && thrown.isEmpty then
              val primary = RunnerError.Backend(error)
              error match
                case BackendError.Io(cause) => cause.getSuppressed.foreach(primary.addCleanupFailure)
                case _                      => ()
              result = Left(primary)
            else
              val cause = error match
                case BackendError.Io(primary) => primary
                case _                        => IllegalStateException(error.message)
              recordCleanup(cause)
          case Right(())   => ()
      }
      if setupIncomplete then release(backend.emergencyRestore())
    thrown.foreach(error => throw error)
    result

  /** Combines the loop's own outcome with the queued-task failures it absorbed into what [[run]] returns.
    *
    * A terminal backend failure ends the loop but says nothing about the tasks that already failed, so it carries them
    * rather than replacing them. A handler failure reports on its own: the throwable that stopped the app is the whole
    * story, and pinning absorbed background failures to it would bury it. Called from `run` on the render thread, once
    * the loop has exited.
    */
  private def report(
      outcome: Option[LoopFailure],
      tasks: Option[QueuedTaskFailures],
  ): Either[RunnerError, Unit] =
    outcome match
      case Some(LoopFailure.Backend(error)) => Left(RunnerError.Backend(error, tasks))
      case Some(LoopFailure.Handler(error)) => Left(RunnerError.Handler(error))
      case None                             => tasks.map(RunnerError.QueuedTask(_)).toLeft(())

  /** Registers a JVM shutdown hook that restores the terminal, and hands the hook back so `run` can remove it again.
    *
    * A `try/finally` alone only protects against exceptions unwinding this thread; a signal that terminates the JVM
    * directly (SIGTERM, SIGHUP) skips straight to shutdown hooks. `close()` is the tidy path, but by the time a hook
    * runs the backend's own resources may already have been torn down underneath it — so follow up with
    * `emergencyRestore()`, which takes the shortest path to a usable terminal and cannot fail.
    *
    * Installed before `setup()` dresses the terminal up, so there is no window in which the app is on the alternate
    * screen with no hook behind it; the hook writes only idempotent mode resets, which are harmless against a terminal
    * that was never dressed.
    *
    * The returned thread is owned by `run`, which is the only caller and the only thing that unregisters it.
    */
  private def installRestoreHook(): Thread =
    val hook = new Thread(
      () =>
        try
          val _ = backend.close()
        finally backend.emergencyRestore(),
      "glyphora-terminal-restore",
    )
    Runtime.getRuntime.addShutdownHook(hook)
    hook

  /** Unregisters the hook [[installRestoreHook]] returned, on the normal teardown path where `run` has already closed
    * the backend itself. Tolerates a JVM that is already shutting down: the hook is then running (or about to), there
    * is nothing left to remove, and its work is harmless anyway.
    */
  private def removeRestoreHook(hook: Thread): Unit =
    try
      val _ = Runtime.getRuntime.removeShutdownHook(hook)
    catch case _: IllegalStateException => ()

  /** Dresses the terminal for the configured viewport.
    *
    * A full-screen run switches to the alternate screen; an inline run stays on the primary screen and instead asks the
    * backend to scroll its rows free, so the shell's output above the app survives the run and the app's last frame
    * survives its exit.
    */
  private def setup(): Either[BackendError, Unit] =
    for
      _ <- backend.enableRawMode()
      _ <- config.viewport match
        case Viewport.Fullscreen     => backend.enterAlternateScreen()
        case inline: Viewport.Inline => backend.reserveInlineRows(inline.reservedRows)
      _ <- backend.hideCursor()
      _ <- if config.mouseCapture then backend.enableMouseCapture() else Right(())
      _ <- if config.keyEventTypes then backend.enableKeyEventTypes() else Right(())
    yield ()

  private def runLoop(
      onStart: RunnerHandle => Unit,
      handleEvent: (Event, RunnerHandle) => EventOutcome,
      render: Frame => Unit,
      loop: RenderThread.RenderLoop,
  ): Option[LoopFailure] =
    LoopBody(backend, config, nanoTime, redrawRequested, onStart, handleEvent, render, loop).run()

object TerminalRunner:
  /** Releases an already-owned backend when application preparation failed before a runner existed. No terminal modes
    * are entered. Cleanup callbacks still have a registered owner, and their failures cannot replace `primary`.
    */
  private[tui] def cleanupBeforeStart(backend: Backend, primary: Throwable, onStop: () => Unit): Unit =
    val cleanup = new Cleanup
    cleanup.attempt(throw primary)
    val loop    = RenderThread.register(Thread.currentThread(), () => backend.wake())
    cleanup.attempt(onStop())
    cleanup.attempt(loop.close())
    cleanup.attempt(RenderThread.unregister())
    cleanup.attempt {
      backend.close() match
        case Left(BackendError.Io(error)) => throw error
        case Left(error)                  => throw IllegalStateException(error.message)
        case Right(())                    => ()
    }
    cleanup.rethrow()

/** The event loop of one [[TerminalRunner.run]]: the per-iteration phases (redraw, guarded callback, dispatch,
  * frame-owed check) and the loop itself, gathered out of `runLoop` so each phase is one named method on the state it
  * closes over rather than one of four nested closures sharing locals.
  *
  * Everything here runs on the render thread, like the [[LoopState]] it owns.
  */
private final class LoopBody(
    backend: Backend,
    config: RunnerConfig,
    nanoTime: () => Long,
    redrawRequested: () => Boolean,
    onStart: RunnerHandle => Unit,
    handleEvent: (Event, RunnerHandle) => EventOutcome,
    render: Frame => Unit,
    loop: RenderThread.RenderLoop,
):

  private val state    = LoopState()
  private val ticks    = TickSchedule(config.tickRate, nanoTime)
  private val handle   = BackendHandle(backend, state)
  private val composer = FrameComposer(backend, render, config.onFrame, config.viewport)

  private def isLive: Boolean = state.isLive && !loop.stopRequested

  /** Composes and flushes one frame.
    *
    * The render function is the app's code too — in the DSL it is the user's `view` — so a throwable out of it is
    * caught here rather than being allowed to unwind past the terminal restore. `compose` returns
    * `Either[BackendError, Unit]`, which has no room for a handler failure, hence the guard at the call site: a backend
    * failure still arrives as a `Left` and is recorded unchanged.
    */
  private def redraw(): Unit =
    try state.record(composer.compose())
    catch case NonFatal(error) => state.failHandler(error)

  /** Runs `body` on the app's behalf, recording a throwable as the loop's failure instead of letting it unwind.
    *
    * Unwinding would leave `run`'s callers with the throwable and the user with a terminal still in raw mode on the
    * alternate screen: the restore lives further down this call stack, not above it.
    */
  private def guarded(body: => EventOutcome): EventOutcome =
    try body
    catch
      case NonFatal(error) =>
        state.failHandler(error)
        EventOutcome.Ignored

  /** Dispatches one event; answers whether the frame should be repainted afterward. */
  private def dispatch(event: Event): DispatchOutcome =
    event match
      case Event.Interrupt  =>
        // an app that does not consume Ctrl+C quits cleanly, so teardown runs on the normal path
        if guarded(handleEvent(event, handle)) == EventOutcome.Redraw then DispatchOutcome.Repaint
        else
          handle.quit()
          DispatchOutcome.NoRepaint
      case Event.EndOfInput =>
        // Not a request the app may decline, unlike the interrupt above: the stream is gone, so there is nothing
        // left to read and a loop that kept polling it would spin at 100% CPU. The handler still runs, and runs
        // first, so an app can save its work; whatever it answers, the loop quits. No repaint either — the frame
        // that would be drawn is one nobody will look at, and drawing it only delays the terminal restore.
        val _ = guarded(handleEvent(event, handle))
        handle.quit()
        DispatchOutcome.NoRepaint
      case _: Event.Resize  =>
        // a resize always repaints, whatever the handler answers, because the composed frame no longer fits the
        // terminal. The handler still runs, and runs first, so an app that tracks its own dimensions sees the event.
        val _ = guarded(handleEvent(event, handle))
        DispatchOutcome.Repaint
      case _                =>
        if guarded(handleEvent(event, handle)) == EventOutcome.Redraw then DispatchOutcome.Repaint
        else DispatchOutcome.NoRepaint

  /** Whether a frame is owed for a reason no event asked for: caller-owned state mutated through
    * [[RunnerHandle.requestRedraw]], or whatever the host's own `redrawRequested` tracks (a DSL app's signals).
    *
    * Both are read every iteration — neither short-circuits the other — because the handle's request is consumed by
    * reading it and dropping it would owe a frame nobody ever pays.
    */
  private def frameOwed(): Boolean =
    val byHandle = state.takeRedrawRequest()
    val byHost   = redrawRequested()
    byHandle || byHost

  /** Runs the loop until the app quits or something fails; what to report, or `None` on a clean exit. */
  def run(): Option[LoopFailure] =
    // Before the first frame and after the terminal is dressed: the earliest point at which this is certainly the
    // render thread, so background work armed here captures this loop. A `quit()` from it exits without rendering.
    val _ = guarded { onStart(handle); EventOutcome.Ignored }
    if isLive then redraw()
    while isLive do
      RenderThread.drainPending(loop)
      // queued work (runLater/runOnRenderThread) may have invalidated state between events
      if frameOwed() && isLive then redraw()
      if isLive then
        // Backends require a positive timeout. Keep input polling fair without sleeping a full tick/default interval
        // while callbacks remain runnable; queued wake notifications may already have been consumed or coalesced.
        val timeout = if RenderThread.hasPending(loop) then 1.millis else ticks.pollTimeout
        backend.readEvent(timeout) match
          case Left(error)                  => state.fail(error)
          case Right(Some(event)) if isLive =>
            // deliberately one event per redraw: the element tree that routes focus and hit-testing is published *by*
            // rendering, so folding several key events into one frame would dispatch the later ones against a stale
            // tree — Tab would move focus and the next keystroke would still go to the previous element. Floods are
            // bounded anyway: a paste arrives as a single `Event.Paste`, and resizes coalesce inside the backend.
            if dispatch(event) == DispatchOutcome.Repaint && isLive then redraw()
          case Right(_)                     => ()
      // the loop may have stopped inside dispatch above — a quit(), the end of input, a failed backend call — and a
      // tick that falls due now is not owed to an app that has already exited: only a live loop delivers one
      if isLive then
        // when no tick is due the handler is not invoked at all
        val tickDue     = ticks.takeDue()
        val tickOutcome = if tickDue then guarded(handleEvent(Event.Tick, handle)) else EventOutcome.Ignored
        if tickDue && tickOutcome == EventOutcome.Redraw && isLive then redraw()
    state.outcome

/** What [[LoopBody.dispatch]] settled about the frame that follows the event it handled — named so the loop reads
  * "repaint" rather than `true`.
  */
private enum DispatchOutcome:

  /** Draw a fresh frame: the handler asked for it, or the event (a resize) invalidated the current one. */
  case Repaint

  /** Keep the current frame: the handler ignored the event, or the loop is exiting and nobody will see another. */
  case NoRepaint

/** When the next tick is due, and how long the loop may block on input before then.
  *
  * The loop used to carry this as a `lastTick` local threaded through a `pollTimeout` helper: two places that had to
  * agree about the same instant. Here they are one object, so "a tick is owed" and "how long may I wait" are answered
  * from the same field.
  *
  * `rate` is the app's configured tick rate, `None` when it asked for no ticks at all. Confined to the render thread,
  * like the loop that owns it.
  */
private final class TickSchedule(rate: Option[FiniteDuration], nanoTime: () => Long):

  /** How long to block on input when the app asked for no tick rate at all. */
  private val DefaultPollTimeout: FiniteDuration = 100.millis

  /** The floor for any poll: short enough to be indistinguishable from "check now", never zero. */
  private val MinPollNanos: Long = 1_000_000L

  private var lastTick: Long = nanoTime()

  /** Answers whether a tick is owed now, consuming it when one is: the schedule is re-stamped, so one iteration of the
    * loop must ask exactly once — asking twice would swallow the second tick.
    */
  def takeDue(): Boolean =
    rate.exists { r =>
      if nanoTime() - lastTick >= r.toNanos then
        lastTick = nanoTime()
        true
      else false
    }

  /** Block on input at most until the next tick is due (or a coarse default poll when there is no tick rate), so ticks
    * stay on schedule while input stays responsive.
    *
    * Never returns zero: [[Backend.readEvent]] treats a non-positive timeout as "block until an event arrives", so an
    * overrunning frame would otherwise wedge the loop until the user happened to press a key.
    */
  def pollTimeout: FiniteDuration =
    rate match
      case None       => DefaultPollTimeout
      case Some(rate) =>
        val remainingNanos = rate.toNanos - (nanoTime() - lastTick)
        Duration.fromNanos(math.max(MinPollNanos, math.min(remainingNanos, rate.toNanos)))

/** Composes one frame into a [[Buffer]] and hands it to the backend to flush, for the lifetime of one
  * [[TerminalRunner]] loop.
  *
  * This exists to own the buffer-reuse policy in one place. The backend diffs each frame against the one it last
  * flushed, so nothing is gained by allocating a fresh buffer per frame — the previous one is cleared and reused for as
  * long as the terminal keeps the same size, and only a resize forces a new allocation.
  *
  * Confined to the render thread, which is where the loop calls it; the cached buffer needs no synchronisation.
  */
private final class FrameComposer(
    backend: Backend,
    render: Frame => Unit,
    onFrame: Option[CompletedFrame => Unit],
    viewport: Viewport,
):

  private var frameBuffer: Option[Buffer] = None
  private var composed: Long              = 0L

  /** Where the cursor was left after the previous frame; `None` means hidden, which is how `setup()` leaves it. */
  private var shownCursor: Option[Position] = None

  /** Asks the backend for the current size, composes the frame into the (reused or freshly sized) buffer and flushes
    * it. A failure at either backend call is returned rather than thrown, for the loop to record.
    */
  def compose(): Either[BackendError, Unit] =
    backend.size.flatMap { size =>
      val area   = viewport.areaIn(size)
      val buffer = frameBuffer.filter(_.area == area).getOrElse(Buffer(area))
      buffer.reset()
      frameBuffer = Some(buffer)
      val frame  = Frame(area, buffer, composed)
      render(frame)
      // The number is spent here, before the flush: the first frame an app sees is 0, and a frame whose `draw` fails
      // still used its number. Rolling it back on a failed draw would hand two different frames the same number, which
      // is worse for a debug label than a gap in the sequence.
      composed = if composed == Long.MaxValue then 0L else composed + 1L
      backend.draw(buffer).flatMap { _ =>
        // Only a flushed frame is a completed one, so the observer runs after a successful draw and never before it.
        onFrame.foreach(observe => observe(CompletedFrame(buffer.snapshot, area, frame.count)))
        placeCursor(frame.declaredCursor)
      }
    }

  /** Puts the physical cursor where the frame asked for it, or hides it when the frame asked for nothing.
    *
    * Runs after the flush, never before: the diff leaves the cursor wherever the last changed cell was, so placing it
    * first would place it and then move it away again. Position first and *then* show, so the cursor is never briefly
    * visible at its stale spot.
    *
    * Every draw may move the physical cursor, even when the declaration is unchanged. Restore its position after every
    * draw; deduplicate visibility separately so a steady caret is not repeatedly shown.
    */
  private def placeCursor(requested: Option[Position]): Either[BackendError, Unit] =
    val visibilityChanged = requested.isDefined != shownCursor.isDefined
    requested match
      case Some(position) =>
        backend.setCursorPosition(position).flatMap { _ =>
          val result = if visibilityChanged then backend.showCursor() else Right(())
          result.map { _ => shownCursor = requested }
        }
      case None           =>
        val result = if visibilityChanged then backend.hideCursor() else Right(())
        result.map { _ => shownCursor = None }

/** Why a [[TerminalRunner]] loop stopped early, when it did.
  *
  * Two failures end the loop and they are not the same kind of thing: the terminal broke, or the app did. Naming both
  * in one type is what lets [[LoopState]] answer "may I run another iteration?" once, rather than once per kind.
  */
private enum LoopFailure:
  /** A call into the [[Backend]] failed — reading input, drawing, an out-of-band terminal operation. */
  case Backend(error: BackendError)

  /** One of the callbacks the app supplied threw: the event handler, `onStart`, or the render function. */
  case Handler(error: Throwable)

/** The reasons a [[TerminalRunner]] loop stops and the frames it still owes, held in one place so every site asks the
  * same question.
  *
  * The loop asks "should I keep going?" at four points and can be stopped from five more, so both questions get one
  * name each — [[isLive]] and [[record]] — instead of being spelled out at each site. A third reason to stop is then a
  * change to [[isLive]] alone; spelled out, one missed site would draw an extra frame after the failure.
  *
  * Confined to the render thread: the loop, the [[BackendHandle]] it hands to event handlers, and the redraw/dispatch
  * helpers all run there, so no field here needs to be volatile.
  */
private final class LoopState:

  private var running                      = true
  private var failure: Option[LoopFailure] = None
  private var redrawPending                = false

  /** Whether the loop should run another iteration: nobody has asked to quit and nothing has failed. */
  def isLive: Boolean = running && failure.isEmpty

  /** Asks for a clean exit once the current iteration finishes — what [[RunnerHandle.quit]] does. */
  def stop(): Unit = running = false

  /** Records the backend failure that ends the loop. The most recent one is what gets reported. */
  def fail(error: BackendError): Unit = failure = Some(LoopFailure.Backend(error))

  /** Records the throwable an event handler let escape, which also ends the loop. */
  def failHandler(error: Throwable): Unit = failure = Some(LoopFailure.Handler(error))

  /** Records `result`'s failure if it has one; a successful value is deliberately discarded. */
  def record[A](result: Either[BackendError, A]): Unit = result.left.foreach(fail)

  /** Notes that a frame is owed — what [[RunnerHandle.requestRedraw]] does. Idempotent: two requests before the next
    * iteration are one frame, because a frame is a snapshot of current state and not a queue of edits.
    */
  def requestRedraw(): Unit = redrawPending = true

  /** Answers whether a frame was requested and clears the request, so the next iteration does not draw it twice. */
  def takeRedrawRequest(): Boolean =
    val pending = redrawPending
    redrawPending = false
    pending

  /** What the loop returns to `run`: the recorded failure, or `None` when it exited cleanly. */
  def outcome: Option[LoopFailure] = failure

/** The [[RunnerHandle]] handed to event handlers for the lifetime of one [[TerminalRunner]] loop.
  *
  * Every method here runs on the render thread, because that is where handlers are invoked. A backend call that fails
  * is not thrown: it is recorded on the shared [[LoopState]], which ends the loop after the current iteration rather
  * than unwinding it mid-frame.
  */
private final class BackendHandle(backend: Backend, state: LoopState) extends RunnerHandle:

  def quit(): Unit                           = state.stop()
  def runOnRenderThread(body: => Unit): Unit = RenderThread.runOnRenderThread(body)
  def requestRedraw(): Unit                  = state.requestRedraw()
  def copyToClipboard(text: String): Unit    = state.record(backend.copyToClipboard(text))

  // the backend forces a full repaint once `body` hands the terminal back
  def suspend(body: => Unit): Unit         = state.record(backend.suspend(body))
  def printAbove(lines: Seq[String]): Unit = state.record(backend.printAbove(lines))

  def insertBefore(height: Int, widget: Widget): Unit = state.record(backend.insertBefore(height, widget))

/** Collects the failures of bodies queued onto the render thread during a single [[TerminalRunner.run]].
  *
  * Recording rather than rethrowing is the whole point: absorbing a throwing continuation keeps the loop running, and
  * the failures are reported once the app has exited rather than tearing it down mid-frame. Every failure is counted,
  * and every one up to [[MaxSuppressedTaskFailures]] keeps its own stack trace — a continuation that throws on every
  * tick must not report as a single incident, which is the silent-failure mode this default exists to avoid in the
  * first place.
  *
  * An instance is owned by the `run` that created it and is touched only from the render thread, so its state needs no
  * volatile and no lock.
  */
private final class QueuedTaskFailureRecorder extends RenderTaskErrorHandler:

  /** How many queued-task throwables past the first keep their own stack trace on the reported failure. Bounded because
    * a long-lived app can fail on every tick; the reported count stays exact regardless.
    */
  private val MaxSuppressedTaskFailures: Int = 16

  private var firstFailure: Option[Throwable] = None
  private var failureCount                    = 0

  def handle(error: Throwable): Unit =
    failureCount += 1
    firstFailure match
      case None        => firstFailure = Some(error)
      case Some(first) =>
        // `addSuppressed(self)` throws, and a cached throwable rethrown every tick is exactly the shape that hits it
        if !(first eq error) && first.getSuppressed.length < MaxSuppressedTaskFailures then first.addSuppressed(error)

  /** What the run reports: the throwable that started it plus the exact total, or `None` when nothing failed. */
  def collected: Option[QueuedTaskFailures] = firstFailure.map(QueuedTaskFailures(_, failureCount))
