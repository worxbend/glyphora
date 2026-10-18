package io.worxbend.tui.dsl

import io.worxbend.tui.core.{ColorScheme, Effect}
import io.worxbend.tui.runtime.{Reactive, Signal}
import io.worxbend.tui.widgets.NoticeLevel

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

  /** Stops mirroring a progress source and clears whatever the taskbar was showing. */
  def clearTaskbarProgress(): Unit

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
    def notify(message: String, level: NoticeLevel, duration: FiniteDuration): Unit = ()
    def dismissToasts(): Unit                                                       = ()
    def desktop(title: String, body: String): Unit                                  = ()
    def colorScheme: Reactive[ColorScheme]                                          = unknownScheme
    def taskbarProgress(progress: Reactive[Option[Double]]): Unit                   = ()
    def clearTaskbarProgress(): Unit                                                = ()
    def pushScreen(screen: Screen): Unit                                            = ()
    def popScreen(): Unit                                                           = ()
    def openPalette(): Unit                                                         = ()
    def closePalette(): Unit                                                        = ()
    def runEffect(effect: Effect): Unit                                             = ()
    def requestRedraw(): Unit                                                       = ()
    def copyToClipboard(text: String): Unit                                         = ()
    def quit(): Unit                                                                = ()
