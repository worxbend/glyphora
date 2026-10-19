package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, Widget}
import io.worxbend.tui.terminal.{BackendError, MouseCaptureMode, PointerShape, TaskbarState}

import java.nio.file.Path
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
  * policy for frame observers would be one policy too many to reason about. For bounded off-thread sampling, pass a
  * caller-owned [[LatestFrameObserver]]; snapshot creation and admission still happen synchronously.
  *
  * `onFrameTiming` opts into elapsed phase measurements — see [[FrameTiming]]. It runs synchronously after successful
  * composition, draw, observation and cursor placement, on the render thread. Keep it short: it is not itself timed,
  * and a throwable ends the loop as [[RunnerError.Handler]]. `None` adds no profiling clock reads or timing records.
  * Timing alone takes no snapshots; only `onFrame` enables those.
  *
  * `keyEventTypes` asks the terminal to report key *releases* as well as presses, which reaches the app as
  * [[Event.KeyRelease]]. It is off by default because it doubles the input volume for every keystroke and an app that
  * ignores releases gains nothing for the traffic, and it is best-effort even when on: only a terminal speaking the
  * kitty keyboard protocol can report a release at all, so an app must treat releases as an enrichment rather than as
  * the event a feature depends on.
  *
  * `viewport` decides how much of the terminal the run owns — the whole alternate screen (the default), or a strip of
  * rows at the bottom of the primary screen that leaves the shell's own output visible above it. See [[Viewport]].
  *
  * `captureStdio` decides what happens to `System.out`/`System.err` while the run owns the terminal — see
  * [[CaptureStdio]]. `None`, the default, leaves the decision to whoever owns the runner: a bare [[TerminalRunner]]
  * captures nothing, while `TuiApp` captures into `printAbove`. Set it explicitly —
  * `withCaptureStdio(CaptureStdio.Off)` — to overrule that host default.
  *
  * `recording`, when set, writes every flushed frame of the run to that file as an asciicast v2 recording — see
  * [[AsciicastRecorder]]. The runner opens the file before it touches the terminal (a path that cannot be opened ends
  * `run` with [[RunnerError.Handler]] and the terminal untouched), records synchronously after each flush alongside any
  * `onFrame` observer, and closes it during teardown. `None`, the default, records nothing — unless the JVM was started
  * with `-Dglyphora.record=<file>`, which names the file for a run whose configuration did not; the configuration wins
  * when both are set.
  *
  * `nonInteractive` says what a run does when the process has no terminal to be interactive on — standard output is not
  * a TTY, or `CI` is set. See [[NonInteractivePolicy]]; the default, [[NonInteractivePolicy.Fail]], is the behaviour
  * every release before 0.20.0 had, and the one the CI smoke test of every example relies on.
  *
  * An immutable value with a private constructor: build one from [[RunnerConfig.Default]] (or the companion `apply`)
  * and the `with*` methods, each of which returns a new configuration. The two Booleans and every `Option` come as a
  * pair — `withMouseCapture`/`withoutMouseCapture`, `withTickRate(d)`/`withoutTickRate` — so a call site reads as a
  * sentence rather than a `true`. Equality and `hashCode` are structural over every field, with one caveat the type
  * system forces: `onTaskError`, `onFrame` and `onFrameTiming` hold functions, which compare by reference — two
  * configurations built from the same handler instance are equal, two built from separately written lambdas with the
  * same body are not — exactly as they did when this was a `case class`. `toString` lists only the fields that differ
  * from the default, so the common configuration prints as one short line.
  */
final class RunnerConfig private (
    val tickRate: Option[FiniteDuration],
    val mouse: MouseCaptureMode,
    val onTaskError: Option[RenderTaskErrorHandler],
    val onFrame: Option[CompletedFrame => Unit],
    val viewport: Viewport,
    val keyEventTypes: Boolean,
    val onFrameTiming: Option[FrameTiming => Unit],
    val captureStdio: Option[CaptureStdio],
    val recording: Option[Path],
    val nonInteractive: NonInteractivePolicy,
):

  /** Synthetic [[Event.Tick]]s every `rate` — the whole-app clock for animation; see the class notes on why the type is
    * `FiniteDuration`.
    */
  def withTickRate(rate: FiniteDuration): RunnerConfig = updated(tickRate = Some(rate))

  /** No synthetic ticks at all: the loop blocks on input for its default poll between events. The default. */
  def withoutTickRate: RunnerConfig = updated(tickRate = None)

  /** How much mouse traffic the terminal is asked to report — see [[MouseCaptureMode]]. Reports reach the app as
    * [[Event.Mouse]]; [[MouseCaptureMode.AllMotion]] is what delivers [[io.worxbend.tui.core.MouseEventKind.Moved]] and
    * so hover. A DSL `TuiApp` running with [[MouseCaptureMode.Buttons]] upgrades itself to `AllMotion` the first time a
    * frame asks for a hover cue, so asking for `AllMotion` here is for an app that wants hover from the very first
    * frame, or drives its own loop.
    */
  def withMouse(mode: MouseCaptureMode): RunnerConfig = updated(mouse = mode)

  /** Asks the terminal to report mouse buttons, the wheel and drags — `withMouse(MouseCaptureMode.Buttons)`. */
  def withMouseCapture: RunnerConfig = updated(mouse = MouseCaptureMode.Buttons)

  /** Leaves mouse reporting off — `withMouse(MouseCaptureMode.Off)`. The default. */
  def withoutMouseCapture: RunnerConfig = updated(mouse = MouseCaptureMode.Off)

  /** Whether the terminal is asked to report the mouse at all: [[mouse]] is anything but [[MouseCaptureMode.Off]]. */
  def mouseCapture: Boolean = mouse != MouseCaptureMode.Off

  /** Takes over the reporting of queued-body failures as they happen — see the class notes for what that changes about
    * what `run` returns, and keep the handler total.
    */
  def withOnTaskError(handler: RenderTaskErrorHandler): RunnerConfig = updated(onTaskError = Some(handler))

  /** Back to accumulating queued-body failures and returning them as [[RunnerError.QueuedTask]]. The default. */
  def withoutOnTaskError: RunnerConfig = updated(onTaskError = None)

  /** Observes every flushed frame on the render thread — see [[CompletedFrame]] and the class notes on cost and on a
    * throwing observer.
    */
  def withOnFrame(observer: CompletedFrame => Unit): RunnerConfig = updated(onFrame = Some(observer))

  /** No frame observation, and no snapshot taken. The default. */
  def withoutOnFrame: RunnerConfig = updated(onFrame = None)

  /** How much of the terminal the run owns — see [[Viewport]]. */
  def withViewport(viewport: Viewport): RunnerConfig = updated(viewport = viewport)

  /** Asks the terminal to report key releases as well as presses, as [[Event.KeyRelease]] — best-effort, see the class
    * notes.
    */
  def withKeyEventTypes: RunnerConfig = updated(keyEventTypes = true)

  /** Key presses only. The default. */
  def withoutKeyEventTypes: RunnerConfig = updated(keyEventTypes = false)

  /** Opts into elapsed phase measurements per frame — see [[FrameTiming]]. */
  def withOnFrameTiming(observer: FrameTiming => Unit): RunnerConfig = updated(onFrameTiming = Some(observer))

  /** No phase timing, and no profiling clock reads. The default. */
  def withoutOnFrameTiming: RunnerConfig = updated(onFrameTiming = None)

  /** What to do with `System.out`/`System.err` during the run — see [[CaptureStdio]] and the class notes. */
  def withCaptureStdio(mode: CaptureStdio): RunnerConfig = updated(captureStdio = Some(mode))

  /** Back to the host's default: nothing for a bare runner, `printAbove` under `TuiApp`. The default. */
  def withoutCaptureStdio: RunnerConfig = updated(captureStdio = None)

  /** Records every flushed frame of the run to `path` as asciicast v2 — see the class notes and [[AsciicastRecorder]].
    */
  def withRecording(path: Path): RunnerConfig = updated(recording = Some(path))

  /** No recording from the configuration (`-Dglyphora.record` may still name one). The default. */
  def withoutRecording: RunnerConfig = updated(recording = None)

  /** What a run does without a terminal to be interactive on — see [[NonInteractivePolicy]]. */
  def withNonInteractive(policy: NonInteractivePolicy): RunnerConfig = updated(nonInteractive = policy)

  private def updated(
      tickRate: Option[FiniteDuration] = tickRate,
      mouse: MouseCaptureMode = mouse,
      onTaskError: Option[RenderTaskErrorHandler] = onTaskError,
      onFrame: Option[CompletedFrame => Unit] = onFrame,
      viewport: Viewport = viewport,
      keyEventTypes: Boolean = keyEventTypes,
      onFrameTiming: Option[FrameTiming => Unit] = onFrameTiming,
      captureStdio: Option[CaptureStdio] = captureStdio,
      recording: Option[Path] = recording,
      nonInteractive: NonInteractivePolicy = nonInteractive,
  ): RunnerConfig =
    new RunnerConfig(
      tickRate,
      mouse,
      onTaskError,
      onFrame,
      viewport,
      keyEventTypes,
      onFrameTiming,
      captureStdio,
      recording,
      nonInteractive,
    )

  /** Structural over every field; the three function-valued fields compare by reference (see the class notes). */
  override def equals(other: Any): Boolean =
    other match
      case that: RunnerConfig =>
        (this eq that) ||
        (mouse == that.mouse && keyEventTypes == that.keyEventTypes && tickRate == that.tickRate &&
          viewport == that.viewport && onTaskError == that.onTaskError && onFrame == that.onFrame &&
          onFrameTiming == that.onFrameTiming && captureStdio == that.captureStdio && recording == that.recording &&
          nonInteractive == that.nonInteractive)
      case _                  => false

  override def hashCode(): Int =
    var hash = tickRate.hashCode()
    hash = 31 * hash + mouse.hashCode()
    hash = 31 * hash + onTaskError.hashCode()
    hash = 31 * hash + onFrame.hashCode()
    hash = 31 * hash + viewport.hashCode()
    hash = 31 * hash + keyEventTypes.hashCode()
    hash = 31 * hash + onFrameTiming.hashCode()
    hash = 31 * hash + captureStdio.hashCode()
    hash = 31 * hash + recording.hashCode()
    31 * hash + nonInteractive.hashCode()

  /** Only the fields that differ from [[RunnerConfig.Default]], so the common configuration prints as one short line; a
    * function field prints as `<installed>`, a lambda having no readable text of its own.
    */
  override def toString: String =
    val fields = List(
      Option.when(tickRate.nonEmpty)(s"tickRate=$tickRate"),
      Option.when(mouseCapture)(s"mouse=$mouse"),
      Option.when(onTaskError.nonEmpty)("onTaskError=<installed>"),
      Option.when(onFrame.nonEmpty)("onFrame=<installed>"),
      Option.when(viewport != Viewport.Fullscreen)(s"viewport=$viewport"),
      Option.when(keyEventTypes)("keyEventTypes=true"),
      Option.when(onFrameTiming.nonEmpty)("onFrameTiming=<installed>"),
      captureStdio.map(mode => s"captureStdio=$mode"),
      recording.map(path => s"recording=$path"),
      Option.when(nonInteractive != NonInteractivePolicy.Fail)(s"nonInteractive=$nonInteractive"),
    ).flatten
    if fields.isEmpty then "RunnerConfig.Default" else fields.mkString("RunnerConfig(", ", ", ")")

object RunnerConfig:

  /** A configuration from its fields, every one defaulting to off — `RunnerConfig(tickRate = Some(d))` is
    * `RunnerConfig.Default.withTickRate(d)`.
    *
    * This is a construction convenience, not the compatibility surface. Adding a field changes its descriptor and
    * renumbers its default-argument getters, so a consumer compiled against `RunnerConfig(tickRate = …)` still fails
    * with `NoSuchMethodError` on the next field until it recompiles; at that growth the previous arity is kept as a
    * plain default-free overload so compiled callers keep working. Code that must survive a new field without
    * recompiling starts from [[Default]] and builds with the `with*` methods.
    */
  def apply(
      tickRate: Option[FiniteDuration] = None,
      mouse: MouseCaptureMode = MouseCaptureMode.Off,
      onTaskError: Option[RenderTaskErrorHandler] = None,
      onFrame: Option[CompletedFrame => Unit] = None,
      viewport: Viewport = Viewport.Fullscreen,
      keyEventTypes: Boolean = false,
      onFrameTiming: Option[FrameTiming => Unit] = None,
      captureStdio: Option[CaptureStdio] = None,
      recording: Option[Path] = None,
      nonInteractive: NonInteractivePolicy = NonInteractivePolicy.Fail,
  ): RunnerConfig =
    new RunnerConfig(
      tickRate,
      mouse,
      onTaskError,
      onFrame,
      viewport,
      keyEventTypes,
      onFrameTiming,
      captureStdio,
      recording,
      nonInteractive,
    )

  /** The ten-field arity `apply` had while mouse capture was a Boolean, kept default-free so code compiled against it
    * keeps working — the policy the `apply` above documents. `mouseCapture = true` is [[MouseCaptureMode.Buttons]].
    */
  def apply(
      tickRate: Option[FiniteDuration],
      mouseCapture: Boolean,
      onTaskError: Option[RenderTaskErrorHandler],
      onFrame: Option[CompletedFrame => Unit],
      viewport: Viewport,
      keyEventTypes: Boolean,
      onFrameTiming: Option[FrameTiming => Unit],
      captureStdio: Option[CaptureStdio],
      recording: Option[Path],
      nonInteractive: NonInteractivePolicy,
  ): RunnerConfig =
    new RunnerConfig(
      tickRate,
      buttonsWhen(mouseCapture),
      onTaskError,
      onFrame,
      viewport,
      keyEventTypes,
      onFrameTiming,
      captureStdio,
      recording,
      nonInteractive,
    )

  /** The nine-field arity `apply` had before `nonInteractive` was added, kept default-free so code compiled against it
    * keeps working — the policy the `apply` above documents.
    */
  def apply(
      tickRate: Option[FiniteDuration],
      mouseCapture: Boolean,
      onTaskError: Option[RenderTaskErrorHandler],
      onFrame: Option[CompletedFrame => Unit],
      viewport: Viewport,
      keyEventTypes: Boolean,
      onFrameTiming: Option[FrameTiming => Unit],
      captureStdio: Option[CaptureStdio],
      recording: Option[Path],
  ): RunnerConfig =
    new RunnerConfig(
      tickRate,
      buttonsWhen(mouseCapture),
      onTaskError,
      onFrame,
      viewport,
      keyEventTypes,
      onFrameTiming,
      captureStdio,
      recording,
      NonInteractivePolicy.Fail,
    )

  /** The eight-field arity `apply` had before `recording` and `nonInteractive` were added, kept default-free so code
    * compiled against it keeps working — the policy the `apply` above documents.
    */
  def apply(
      tickRate: Option[FiniteDuration],
      mouseCapture: Boolean,
      onTaskError: Option[RenderTaskErrorHandler],
      onFrame: Option[CompletedFrame => Unit],
      viewport: Viewport,
      keyEventTypes: Boolean,
      onFrameTiming: Option[FrameTiming => Unit],
      captureStdio: Option[CaptureStdio],
  ): RunnerConfig =
    new RunnerConfig(
      tickRate,
      buttonsWhen(mouseCapture),
      onTaskError,
      onFrame,
      viewport,
      keyEventTypes,
      onFrameTiming,
      captureStdio,
      None,
      NonInteractivePolicy.Fail,
    )

  /** The seven-field arity `apply` had before `captureStdio`, `recording` and `nonInteractive` were added, kept
    * default-free so code compiled against it keeps working — the policy the `apply` above documents.
    */
  def apply(
      tickRate: Option[FiniteDuration],
      mouseCapture: Boolean,
      onTaskError: Option[RenderTaskErrorHandler],
      onFrame: Option[CompletedFrame => Unit],
      viewport: Viewport,
      keyEventTypes: Boolean,
      onFrameTiming: Option[FrameTiming => Unit],
  ): RunnerConfig =
    new RunnerConfig(
      tickRate,
      buttonsWhen(mouseCapture),
      onTaskError,
      onFrame,
      viewport,
      keyEventTypes,
      onFrameTiming,
      None,
      None,
      NonInteractivePolicy.Fail,
    )

  /** What a kept Boolean `mouseCapture` meant: button reporting when `true`, nothing when `false`. */
  private def buttonsWhen(mouseCapture: Boolean): MouseCaptureMode =
    if mouseCapture then MouseCaptureMode.Buttons else MouseCaptureMode.Off

  /** Full screen, no ticks, no mouse or key-release reporting, no observers: what `RunnerConfig()` builds and where
    * every `with*` chain starts.
    */
  val Default: RunnerConfig = RunnerConfig()

/** What a run does when the process has no terminal to be interactive on.
  *
  * "No terminal" is decided by the host of the run — in the DSL, `TuiApp.printTarget`: standard output is not a TTY
  * (redirected into a file, piped into another program, an IDE's output panel), or the `CI` environment variable is
  * set. Every policy but [[Fail]] drives the app without a terminal: `onStart` runs, the first frame is composed at the
  * detected size (`COLUMNS`×`LINES`, else 80×24), and the run ends the first time the loop would wait for input — no
  * keystroke is ever going to arrive — so an app that only exits on `q` still exits, and exits `0`. Background work and
  * ticks are not waited for: the frame printed is the one the app composes from its initial state and whatever
  * `onStart` set synchronously.
  *
  *   - [[Fail]] — the behaviour every release before 0.20.0 had: the backend reports `terminal not supported` and the
  *     app exits non-zero. The default, because it is what a user who piped a full-screen app by mistake needs to see,
  *     and what the CI smoke test of every example asserts.
  *   - [[PrintFinalFrame]] — the last frame the run composed is written to standard output as styled text (colour per
  *     the detected depth, `NO_COLOR`/`FORCE_COLOR` honoured, no escape sequence at all when colour is off), one row
  *     per line.
  *   - [[PrintEveryFrame]] — every frame the run composed, in order, each in the same encoding.
  *   - [[Plain]] — the text rows of every frame with no styling whatsoever, consecutive identical frames written once:
  *     the form for a screen reader, a log file, or a `grep`.
  *   - [[AwaitingIdle]] — any of the three above, made to wait for the app to settle: built with
  *     `PrintFinalFrame.awaitIdle(5.seconds)`. Instead of ending at the first wait for input, the run goes on — ticks
  *     fire, `Async` results land and repaint — until the loop is idle (no queued render-thread work, no outstanding
  *     `Async` work or timer, and no frame composed since it last waited) or `timeout` has passed since the run
  *     started, whichever comes first. An app with a repeating `Async.every` or an animation that repaints on every
  *     tick is never idle, so it prints the frame it had reached at the timeout.
  */
enum NonInteractivePolicy:
  case Fail, PrintFinalFrame, PrintEveryFrame, Plain

  /** `printing`'s output, once the run is idle or `timeout` has passed — see the enum's notes. */
  case AwaitingIdle(printing: NonInteractivePolicy, timeout: FiniteDuration)

  /** This policy, waiting for the app to settle (queued `Async` work, timers, ticks) for at most `timeout` before it
    * prints. `Fail` stays `Fail`; a policy already waiting takes the new timeout.
    */
  def awaitIdle(timeout: FiniteDuration): NonInteractivePolicy =
    this match
      case Fail                   => Fail
      case AwaitingIdle(inner, _) => AwaitingIdle(inner, timeout)
      case printing               => AwaitingIdle(printing, timeout)

  /** What is printed: this policy itself, or the one an [[AwaitingIdle]] wraps. */
  def output: NonInteractivePolicy =
    this match
      case AwaitingIdle(inner, _) => inner.output
      case other                  => other

  /** How long the run may wait for the app to settle, when it waits at all. */
  def idleTimeout: Option[FiniteDuration] =
    this match
      case AwaitingIdle(_, timeout) => Some(timeout)
      case _                        => None

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
  * [[insertBefore]] was added on those terms in 0.13.0, and [[setPointerShape]], [[notifyDesktop]] and
  * [[setTaskbarProgress]] in 0.20.0. The trait is written to be *called*, not implemented, so the population this
  * affects is test doubles; a double that wants the old behaviour can implement the new method as `()` in one line.
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

  /** Asks the terminal to draw the mouse pointer as `shape` (OSC 22) — see
    * [[io.worxbend.tui.terminal.Backend.setPointerShape]]. Best-effort: a terminal without the protocol, or one the
    * backend knows will swallow it, changes nothing. Terminal state, so call it when what is under the pointer changes,
    * not on every frame.
    */
  def setPointerShape(shape: PointerShape): Unit

  /** Raises a desktop notification (OSC 9, 777 or 99, whichever the terminal speaks) — see
    * [[io.worxbend.tui.terminal.Backend.notifyDesktop]]. Best-effort; for telling a user who has looked away.
    */
  def notifyDesktop(title: String, body: String): Unit

  /** Shows `progress` on the terminal's tab or the taskbar (OSC 9;4), or clears it with `None` — see
    * [[io.worxbend.tui.terminal.Backend.setTaskbarProgress]]. Best-effort, and terminal state: emit it when the
    * fraction changes.
    */
  def setTaskbarProgress(progress: Option[Double]): Unit

  /** [[setTaskbarProgress]] with the protocol's error, indeterminate and paused states — see
    * [[io.worxbend.tui.terminal.Backend.setTaskbarState]]. The default shows the state by its fraction, so a handle
    * written before the method existed keeps compiling.
    */
  def setTaskbarState(state: TaskbarState): Unit = setTaskbarProgress(state.progress)

  /** Switches how much mouse traffic the terminal sends, mid-run — `MouseCaptureMode.AllMotion` for hover. Only
    * meaningful while mouse capture is on; the DSL calls it the first time a frame asks for a hover cue. The default
    * does nothing, so a handle written before the method existed keeps compiling.
    */
  def setMouseCaptureMode(mode: MouseCaptureMode): Unit =
    val _ = mode

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
    *
    * Identity-bound: the record lives on the instance the runner returned, not in the case's value. Equality,
    * `hashCode`, `toString` and pattern matching see only the case's own fields, so two errors that differ only here
    * compare equal, and a value rebuilt from an extracted payload — `RunnerError.Backend(error, tasks)` — starts with
    * none. The durable record is the suppressed chain on the primary throwable, where every failure is attached as
    * well; [[message]] reports how many there were.
    */
  def cleanupFailures: Vector[Throwable] = cleanupErrors

  /** The throwable behind this error, when its case carries one: the thing cleanup failures are suppressed onto. */
  private[runtime] def primaryThrowable: Option[Throwable] =
    this match
      case Handler(cause)                     => Some(cause)
      case QueuedTask(failures)               => Some(failures.first)
      case Backend(BackendError.Io(cause), _) => Some(cause)
      case _                                  => None

  private[tui] def addCleanupFailure(error: Throwable): Unit =
    val primary = primaryThrowable
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
    * [[RunnerError.Backend]] delegates to [[BackendError.message]] for its half. When teardown failed as well, the line
    * ends with how many [[cleanupFailures]] there were — ` (+2 cleanup failures)` — so the one line an app prints
    * admits that `onStop` or the terminal restore went wrong too, rather than leaving that to a field nobody reads.
    */
  def message: String =
    val primary = this match
      case Backend(error, queuedTasks) =>
        val tasks = queuedTasks.map(failures => s" (${describe(failures)} beforehand)").getOrElse("")
        s"${error.message}$tasks"
      case QueuedTask(failures)        => describe(failures)
      case Handler(error)              =>
        val detail = Option(error.getMessage).getOrElse(error.getClass.getName)
        s"an app callback threw on the render thread: $detail"
    val cleanup = cleanupErrors.size match
      case 0 => ""
      case 1 => " (+1 cleanup failure)"
      case n => s" (+$n cleanup failures)"
    s"$primary$cleanup"

  /** How many render-thread bodies failed and what the first one said. */
  private def describe(failures: QueuedTaskFailures): String =
    val detail = Option(failures.first.getMessage).getOrElse(failures.first.getClass.getName)
    val plural = if failures.count == 1 then "task" else "tasks"
    s"${failures.count} background $plural failed on the render thread; the first was: $detail"
