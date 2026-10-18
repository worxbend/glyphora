package io.worxbend.tui.dsl

import io.worxbend.tui.core.Effect
import io.worxbend.tui.widgets.NoticeLevel

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

  /** Pops the topmost screen, if there is one. */
  def popScreen(): Unit

  /** Opens the fuzzy command palette over the app's declared bindings. */
  def openPalette(): Unit

  /** Closes the command palette. */
  def closePalette(): Unit

  /** Starts a post-render [[Effect]] over the frames that follow. */
  def runEffect(effect: Effect): Unit

  /** Schedules one frame — for state the reactive layer cannot see, such as a mutable widget state. */
  def requestRedraw(): Unit

  /** Copies `text` to the system clipboard via OSC 52, best effort. */
  def copyToClipboard(text: String): Unit

  /** Asks the app to exit cleanly. */
  def quit(): Unit

  /** Opens the user's editor (`VISUAL`, then `EDITOR`, then `vi`) on a temporary file holding `initial`, with the
    * terminal handed back for the duration, and answers the file's new content — `None` when the editor left it
    * unchanged or could not be started. `suffix` is the file's extension, which is what tells the editor which syntax
    * to highlight. Call from an event handler, like `suspend`.
    */
  def editExternally(initial: String, suffix: String = ".md"): Option[String]

  /** Opens `path` in the user's editor, at `line` when given, with the terminal handed back for the duration. */
  def openInEditor(path: Path, line: Option[Int] = None): Unit

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
    def notify(message: String, level: NoticeLevel, duration: FiniteDuration): Unit = ()
    def dismissToasts(): Unit                                                       = ()
    def pushScreen(screen: Screen): Unit                                            = ()
    def popScreen(): Unit                                                           = ()
    def openPalette(): Unit                                                         = ()
    def closePalette(): Unit                                                        = ()
    def runEffect(effect: Effect): Unit                                             = ()
    def requestRedraw(): Unit                                                       = ()
    def copyToClipboard(text: String): Unit                                         = ()
    def quit(): Unit                                                                = ()
    def editExternally(initial: String, suffix: String): Option[String]             = None
    def openInEditor(path: Path, line: Option[Int]): Unit                           = ()
