package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, Widget}
import io.worxbend.tui.terminal.BackendError

import scala.concurrent.duration.FiniteDuration

/** Runner configuration: an optional tick rate (synthetic [[Event.Tick]]s for animation) and whether to capture mouse
  * events.
  *
  * `tickRate` is a [[FiniteDuration]] rather than a `Duration` on purpose: "no ticks" is spelled `None`, and every
  * infinite `Duration` is a runtime failure waiting to happen — `Duration.Inf.toNanos` throws, so
  * `RunnerConfig(tickRate = Some(Duration.Inf))`, a plausible spelling of "never tick", used to type-check and then
  * kill the render loop on its first iteration.
  *
  * `onTaskError` decides what happens when a body queued onto the render thread (an [[Async]] continuation, a timer
  * body) throws. `None` — the default — accumulates them and returns them from [[Runner.run]] as
  * [[RunnerError.QueuedTask]] once the app exits: the first throwable, the total count, and the later throwables
  * attached to the first as suppressed exceptions. Installing a handler takes reporting over instead: it is called as
  * each failure happens, and `run` then returns `Right` unless the backend itself failed. Either way the loop survives
  * the failing body and the bodies queued behind it still run — but only as long as the reporting itself does not
  * throw. A handler that throws is not isolated: it unwinds out of the drain, abandoning the bodies queued behind it,
  * and out of [[Runner.run]]. Keep a handler total.
  *
  * `onFrame`, when set, is called on the render thread immediately after each frame has been flushed, with a snapshot
  * of what was flushed — see [[CompletedFrame]]. It is what gives a *production* app the frame capture that until now
  * only tests could get through `HeadlessBackend.lastDrawn`: an "export the screen" command, a frame attached to a bug
  * report, a periodic sample. `None`, the default, costs nothing at all — no snapshot is taken. A body that throws is
  * not absorbed: like the render function it ends the loop as [[RunnerError.Handler]], because a second isolation
  * policy for frame observers would be one policy too many to reason about.
  *
  * `keyEventTypes` asks the terminal to report key *releases* as well as presses, which reaches the app as
  * [[Event.KeyRelease]]. It is off by default because it doubles the input volume for every keystroke and an app that
  * ignores releases gains nothing for the traffic, and it is best-effort even when on: only a terminal speaking the
  * kitty keyboard protocol can report a release at all, so an app must treat releases as an enrichment rather than as
  * the event a feature depends on.
  *
  * `viewport` decides how much of the terminal the run owns — the whole alternate screen (the default), or a strip of
  * rows at the bottom of the primary screen that leaves the shell's own output visible above it. See [[Viewport]].
  */
final case class RunnerConfig(
    tickRate: Option[FiniteDuration] = None,
    mouseCapture: Boolean = false,
    onTaskError: Option[RenderTaskErrorHandler] = None,
    onFrame: Option[CompletedFrame => Unit] = None,
    viewport: Viewport = Viewport.Fullscreen,
    keyEventTypes: Boolean = false,
)

/** An event handler's answer to one question: does this event change what is on screen?
  *
  * [[Redraw]] asks the loop to compose and flush a frame once the handler returns; [[Ignored]] says the handler left
  * the UI exactly as it was, so the loop goes straight back to waiting for input. Answering [[Ignored]] after changing
  * state the render function reads is what leaves a stale frame on screen; answering [[Redraw]] when nothing changed
  * costs one composed frame that the backend's diff then flushes as nothing.
  *
  * One event reads more into the answer than "repaint or not". [[Event.Interrupt]] (Ctrl+C) is offered to the handler
  * first, and a handler that answers [[Ignored]] is taken to have declined it: the loop then quits through its normal
  * teardown, which is what makes Ctrl+C work in an app that never wrote a handler for it. A handler that wants to stay
  * running through an interrupt — to ask "really quit?", say — answers [[Redraw]], which it was going to have to do
  * anyway to put that question on screen.
  */
enum EventOutcome:
  /** Compose and flush a frame before waiting for the next event. */
  case Redraw

  /** Nothing on screen changed. (On [[Event.Interrupt]] this also declines the interrupt, and the loop quits.) */
  case Ignored

/** The mid-level API tier: owns the event/render loop over a `Backend`.
  *
  * `onStart` runs once, on the render thread, after the terminal has been prepared and before the first frame is
  * composed. It is the earliest moment at which the app is certainly on the render thread, which makes it the correct
  * place to start background work: [[Async]] captures its target loop from the calling thread, so a repeating task
  * armed from a constructor attaches to no loop at all. Calling [[RunnerHandle.quit]] from `onStart` exits without ever
  * rendering a frame. `onStart` has no default: an app that needs nothing there passes `_ => ()`, which costs one
  * lambda and keeps the hook visible at every call site.
  *
  * `handleEvent` answers, per event, whether the UI must be repainted — see [[EventOutcome]]. `render` fills the frame
  * on each redraw. `run` blocks until the app quits (via [[RunnerHandle.quit]]) or the backend fails, and always
  * restores the terminal on the way out — including when `handleEvent` throws, which ends the loop as
  * [[RunnerError.Handler]] rather than unwinding out of `run` past the terminal restore. The calling thread becomes the
  * render thread for the duration of `run`.
  */
trait Runner:
  def run(
      onStart: RunnerHandle => Unit,
      handleEvent: (Event, RunnerHandle) => EventOutcome,
      render: Frame => Unit,
  ): Either[RunnerError, Unit]

/** Handed to `onStart` and to event handlers: request loop exit, ask for a frame, marshal work onto the render thread,
  * or reach the backend for out-of-band terminal operations like clipboard access.
  *
  * Every method is called on the render thread. None of them has a default implementation, deliberately: a handle that
  * silently did nothing for [[suspend]] or [[printAbove]] would let a backend forget to implement them and fail with no
  * symptom other than a `$EDITOR` that never opens.
  *
  * That policy has a cost worth stating, because it is paid by anyone outside this repository who implements the trait:
  * **adding a method here is a source-breaking change.** An existing implementation stops compiling until it supplies
  * the new method — which is the intended outcome, since the alternative is an implementation that compiles and then
  * does nothing — but it means a release that adds one is not a drop-in replacement for the release before it.
  * [[insertBefore]] was added on those terms in 0.13.0. The trait is written to be *called*, not implemented, so the
  * population this affects is test doubles; a double that wants the old behaviour can implement the new method as `()`
  * in one line.
  */
trait RunnerHandle:
  def quit(): Unit
  def runOnRenderThread(body: => Unit): Unit

  /** Schedules one frame.
    *
    * The escape hatch for state the reactive layer cannot see. A `Signal` write already schedules its own redraw, but
    * caller-owned widget state — a `ListState`, a `TextInputState`, the rows of a `DataTableState` — is a plain mutable
    * object, so mutating it from outside the event path (an [[Async]] continuation, a timer body) changes what the next
    * frame would draw without anything noticing that a next frame is owed. Call this after such a mutation.
    *
    * Redundant from inside an event handler, which answers [[EventOutcome.Redraw]] instead. Callable only from the
    * render thread, like the rest of this handle.
    */
  def requestRedraw(): Unit

  /** Copies `text` to the system clipboard (OSC 52). Best-effort; unsupported terminals ignore it. */
  def copyToClipboard(text: String): Unit

  /** Hands the terminal to `body` (leaving the app's screen) and restores afterward — e.g. launch `$EDITOR`. Call it
    * from an event handler (already on the render thread).
    */
  def suspend(body: => Unit): Unit

  /** Prints `lines` into the terminal scrollback above the live UI (durable log output). */
  def printAbove(lines: Seq[String]): Unit

  /** [[printAbove]] with styling: renders `widget` into a block `height` rows tall and inserts *that* into the
    * scrollback above the live UI, so an inserted line can carry colour, bold and hyperlinks.
    *
    * `printAbove` takes text, and the backend strips control sequences out of it before it reaches the terminal, so
    * plain text is all it can ever emit. Here the caller draws: the widget is handed a buffer as wide as the terminal
    * and `height` rows tall, and whatever it paints becomes durable output. A `height` of zero or less inserts nothing.
    */
  def insertBefore(height: Int, widget: Widget): Unit

/** Every queued-body failure one run absorbed, collapsed into a single report.
  *
  * `first` is the throwable that started it and `count` how many failures there were in total; the later throwables are
  * attached to `first` as suppressed exceptions, up to a cap — so a continuation that fails on every tick for a week
  * neither reports as one incident nor accumulates a week of stack traces.
  */
final case class QueuedTaskFailures(first: Throwable, count: Int)

enum RunnerError:
  private var cleanupErrors: Vector[Throwable] = Vector.empty

  /** Secondary teardown failures, in release order. The error case and its primary failure are unchanged. Populated by
    * the owning runner before returning; read only after `run` finishes.
    */
  def cleanupFailures: Vector[Throwable] = cleanupErrors

  private[tui] def addCleanupFailure(error: Throwable): Unit =
    val primary = this match
      case Handler(cause)                     => Some(cause)
      case QueuedTask(failures)               => Some(failures.first)
      case Backend(BackendError.Io(cause), _) => Some(cause)
      case _                                  => None
    if !primary.exists(_ eq error) && !cleanupErrors.exists(_ eq error) then
      cleanupErrors = cleanupErrors :+ error
      primary.foreach(Cleanup.suppress(_, error))

  /** The backend failed, which ends the loop. `queuedTasks` carries whatever queued-body failures the loop had already
    * absorbed, so they are still reported when a terminal failure happens to follow them.
    */
  case Backend(error: BackendError, queuedTasks: Option[QueuedTaskFailures] = None)

  /** Bodies queued onto the render thread threw. The loop absorbed them and kept running; this reports them once the
    * app has exited. Install [[RunnerConfig.onTaskError]] to handle them as they happen instead.
    */
  case QueuedTask(failures: QueuedTaskFailures)

  /** A callback the app supplied threw, which ends the loop: the event handler, `onStart`, or the render function — in
    * the DSL, the user's `view`.
    *
    * Unlike a queued body, such a failure is not absorbed: these callbacks *are* the app, and an app that cannot
    * process an event or draw a frame has no defined next frame. What the loop does guarantee is that the throwable
    * comes back *here* rather than unwinding out of `run` — that path would skip the terminal restore and leave the
    * shell in raw mode on the alternate screen.
    */
  case Handler(error: Throwable)

  /** One human-readable line describing the failure, for an app that reports it to the user before exiting.
    *
    * The generated `toString` of an enum case is a constructor call — `Backend(Io(java.io.IOException: ...),None)` —
    * which is a fine debugging string and a poor thing to print at someone. This is the sentence to print instead;
    * [[RunnerError.Backend]] delegates to [[BackendError.message]] for its half.
    */
  def message: String =
    this match
      case Backend(error, queuedTasks) =>
        val tasks = queuedTasks.map(failures => s" (${describe(failures)} beforehand)").getOrElse("")
        s"${error.message}$tasks"
      case QueuedTask(failures)        => describe(failures)
      case Handler(error)              =>
        val detail = Option(error.getMessage).getOrElse(error.getClass.getName)
        s"an app callback threw on the render thread: $detail"

  /** How many render-thread bodies failed and what the first one said. */
  private def describe(failures: QueuedTaskFailures): String =
    val detail = Option(failures.first.getMessage).getOrElse(failures.first.getClass.getName)
    val plural = if failures.count == 1 then "task" else "tasks"
    s"${failures.count} background $plural failed on the render thread; the first was: $detail"
