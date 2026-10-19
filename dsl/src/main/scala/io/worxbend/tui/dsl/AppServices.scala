package io.worxbend.tui.dsl

import io.worxbend.tui.core.{ColorScheme, Effect}
import io.worxbend.tui.runtime.{Reactive, ReactiveScope, Signal}
import io.worxbend.tui.widgets.{Clipboard, NoticeLevel}
import io.worxbend.tui.terminal.TaskbarState

import java.nio.file.Path
import scala.concurrent.duration.FiniteDuration

/** The running application's cross-cutting services, as a value a helper written outside the app object can be handed.
  *
  * Everything an application can *do* to itself — raise a toast, push a screen, open the command palette, run a
  * post-render effect, ask for a repaint, copy to the clipboard, quit — is a `protected` method on `TuiApp`, reachable
  * only from inside the app's own body. A view helper in another file therefore either takes one callback per action or
  * gives up and moves back into the app.
  *
  * This is that whole set as one value. It extends [[Notifications]], so a helper that only needs to notify can ask for
  * the narrower type and a helper that navigates can ask for this one:
  *
  * {{{
  * def statusFooter(using ReactiveScope, Theme, AppServices): Element =
  *   row(
  *     button("Settings")(summon[AppServices].pushScreen(settingsScreen)),
  *     button("Quit")(summon[AppServices].quit()),
  *   )
  * }}}
  *
  * `TuiApp` publishes its own as a `given`, so a call made from any method of the app resolves it with nothing to write
  * at the call site.
  *
  * Ownership and threads: an instance belongs to one `TuiApp` and every method on it must be called on that app's
  * render thread — which every event handler, timer body and `Async` continuation already is. Background work must call
  * `RenderThread.capture()` before going async, exactly as it must to touch a `Signal`.
  */
trait AppServices extends Notifications:

  /** Pushes a screen: a modal layers over the current view, a full screen replaces it. */
  def pushScreen(screen: Screen): Unit

  /** The names of the screens on the stack, outermost first, as a reactive read — the app's `screenLabels`, for a
    * helper written outside the app: `breadcrumb("Home" +: services.screenLabels, …)`. Unnamed screens contribute
    * nothing. Concrete, answering no screens, so an implementation written before it keeps compiling; a running
    * `TuiApp`'s services answer its stack.
    */
  def screenLabels(using @scala.annotation.unused scope: ReactiveScope): Seq[String] = Seq.empty

  /** Pops the topmost screen, if there is one. */
  def popScreen(): Unit

  /** Opens the fuzzy command palette over the app's declared bindings. */
  def openPalette(): Unit

  /** Closes the command palette. */
  def closePalette(): Unit

  /** Starts a post-render [[Effect]] over the frames that follow. */
  def runEffect(effect: Effect): Unit

  /** Starts a post-render [[Effect]] under `key`, replacing the effect already running under that key, if any — so a
    * binding pressed twenty times plays one flash instead of stacking twenty. The replacement is timed from now.
    */
  def runEffect(key: String, effect: Effect): Unit

  /** [[runEffect(key:String,effect:io\.worxbend\.tui\.core\.Effect)*]] confined to the element declared with
    * `.key(scope)`: the effect covers only the rect that node was painted into, resolved on every frame, and paints
    * nothing on a frame the node is not on screen. Services that cannot resolve an element — this default, kept so an
    * existing implementation of the trait still compiles — run it under `key` over the whole frame instead.
    */
  def runEffect(key: String, effect: Effect, scope: String): Unit =
    val _ = scope
    runEffect(key, effect)

  /** Schedules one frame — for state the reactive layer cannot see, such as a plain field the view reads. */
  def requestRedraw(): Unit

  /** Copies `text` to the system clipboard via OSC 52, best effort. */
  def copyToClipboard(text: String): Unit

  /** Asks the app to exit cleanly. */
  def quit(): Unit

  /** The app's clipboard as text-entry nodes see it: a copy goes out through OSC 52 *and* into an in-app paste buffer,
    * and a paste answers that buffer — a terminal never lets an app read the system clipboard, so `Ctrl+V` in a text
    * node pastes what this app last copied or cut, while the terminal's own paste (bracketed paste) still lands as a
    * paste event. [[Clipboard.Discard]] unless the services belong to a running app.
    */
  def clipboard: Clipboard = Clipboard.Discard

  /** Opens the user's editor (`VISUAL`, then `EDITOR`, then `vi`) on a temporary file holding `initial`, with the
    * terminal handed back for the duration, and answers the file's new content — `None` when the editor left it
    * unchanged or could not be started. `suffix` is the file's extension, which is what tells the editor which syntax
    * to highlight. Call from an event handler, like `suspend`.
    */
  def editExternally(initial: String, suffix: String = ".md"): Option[String]

  /** Opens `path` in the user's editor, at `line` when given, with the terminal handed back for the duration. */
  def openInEditor(path: Path, line: Option[Int] = None): Unit

  /** Whether the terminal is light or dark, as a reactive value: what the capability probe established at start-up (OSC
    * 11) and whatever mode 2031 reports afterwards. `Unknown` when the terminal could not say — read that as dark.
    * `Theme.auto` and `lightDark` are built on this read.
    */
  def colorScheme: Reactive[ColorScheme]

  /** Mirrors `progress` on the terminal's tab or the taskbar (OSC 9;4) for as long as it is bound: `Some(fraction)` is
    * a determinate bar, `None` clears it. The value is read on every frame, so a change schedules the frame that pushes
    * it, and the indicator is cleared when the app exits. Binding a second source replaces the first;
    * [[clearTaskbarProgress]] unbinds. Best-effort — a terminal without the indicator ignores it.
    */
  def taskbarProgress(progress: Reactive[Option[Double]]): Unit

  /** [[taskbarProgress]] with the indicator's other states — `TaskbarState.Error(fraction)`, `Indeterminate`,
    * `Paused(fraction)` — under the same rules: read every frame, pushed on change, cleared on exit, replaced by a
    * second binding of either kind and unbound by [[clearTaskbarProgress]].
    */
  def taskbarState(state: Reactive[TaskbarState]): Unit

  /** Stops mirroring a progress source and clears whatever the taskbar was showing. */
  def clearTaskbarProgress(): Unit

  /** The key mode the app's bindings dispatch in (see `KeyBindings.modes`): set it from a binding to switch modes —
    * `keyMode.set(ModeId("insert"))` — and read it from a view to show it. Holds `ModeId.Default` in an app whose
    * bindings declare no modes. Render-thread only, like every `Signal`.
    */
  def keyMode: Signal[ModeId]

object AppServices:

  /** Services that do nothing, for building elements outside a running application.
    *
    * A helper written against `AppServices` still has to be constructible in a plain unit test, or in a tool that
    * renders one element and prints it, where there is no app to delegate to. This fills that gap the way
    * `Theme.default` fills it for styling: every method is a no-op, so the element builds and renders and none of its
    * handlers can do anything. It is deliberately silent rather than failing — construction is not the moment to find
    * out that an action would have had nowhere to go.
    */
  val NoOp: AppServices = new AppServices:
    private val unknownScheme: Signal[ColorScheme]                                  = Signal(ColorScheme.Unknown)
    private val defaultMode: Signal[ModeId]                                         = Signal(ModeId.Default)
    def keyMode: Signal[ModeId]                                                     = defaultMode
    def notify(message: String, level: NoticeLevel, duration: FiniteDuration): Unit = ()
    def dismissToasts(): Unit                                                       = ()
    def desktop(title: String, body: String): Unit                                  = ()
    def colorScheme: Reactive[ColorScheme]                                          = unknownScheme
    def taskbarProgress(progress: Reactive[Option[Double]]): Unit                   = ()
    def taskbarState(state: Reactive[TaskbarState]): Unit                           = ()
    def clearTaskbarProgress(): Unit                                                = ()
    def pushScreen(screen: Screen): Unit                                            = ()
    def popScreen(): Unit                                                           = ()
    def openPalette(): Unit                                                         = ()
    def closePalette(): Unit                                                        = ()
    def runEffect(effect: Effect): Unit                                             = ()
    def runEffect(key: String, effect: Effect): Unit                                = ()
    def requestRedraw(): Unit                                                       = ()
    def copyToClipboard(text: String): Unit                                         = ()
    def quit(): Unit                                                                = ()
    def editExternally(initial: String, suffix: String): Option[String]             = None
    def openInEditor(path: Path, line: Option[Int]): Unit                           = ()

/** The clipboard the focused text node's `Ctrl+C`/`Ctrl+X`/`Ctrl+V` reach: the running app's [[AppServices.clipboard]]
  * while that app dispatches an event, and outside any app an in-memory one per thread, so an element driven by hand in
  * a test still copies and pastes between its own fields.
  *
  * Thread-scoped like [[CaretRegister]], so two runners in one JVM never share a paste buffer.
  */
private[dsl] object AppClipboard:

  def current: Clipboard =
    val context = FrameContext.current
    context.clipboard.getOrElse(context.threadClipboard)

  /** Runs `body` with `clipboard` as [[current]], restoring the previous one afterwards. */
  def during[A](clipboard: Clipboard)(body: => A): A =
    val context  = FrameContext.current
    val previous = context.clipboard
    context.clipboard = Some(clipboard)
    try body
    finally context.clipboard = previous
