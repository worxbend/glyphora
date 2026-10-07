package io.worxbend.tui.dsl

import io.worxbend.tui.core.Effect
import io.worxbend.tui.runtime.{ReactiveScope, Signal}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** How a pushed [[Screen]] relates to the view underneath it. */
enum Presentation:

  /** Renders layered over what is beneath it, with everything below removed from the tab order — a dialog. */
  case Modal

  /** Replaces the view beneath it entirely — a page. */
  case Full

/** How a modal [[Screen]] may close itself, without the application writing a handler for it.
  *
  * A sealed set of cases rather than two booleans, so a call site reads as one decision — `Dismissal.Escape` — instead
  * of a pair of unlabelled `true`/`false` arguments whose order nobody remembers.
  *
  * Every case is honoured only for [[Presentation.Modal]]. A full screen replaces what is beneath it, so there is no
  * whitespace around it that counts as "outside", and closing it on `Esc` would take the user somewhere the screen
  * never said it was willing to go.
  */
enum Dismissal:

  /** Only the application's own `popScreen()` closes this screen. The default, and what every screen did before this
    * existed.
    */
  case Never

  /** `Esc` closes it — but only once no element and no key binding claimed that key, so a text field that uses `Esc` to
    * leave its editing mode still wins.
    */
  case Escape

  /** A mouse press in the whitespace around the dialog closes it. A press anywhere on the dialog itself does not,
    * including on its border.
    */
  case ClickOutside

  /** Both [[Escape]] and [[ClickOutside]]. */
  case EscapeOrClickOutside

  private[dsl] def byEscape: Boolean = this == Dismissal.Escape || this == Dismissal.EscapeOrClickOutside

  private[dsl] def byClickOutside: Boolean =
    this == Dismissal.ClickOutside || this == Dismissal.EscapeOrClickOutside

/** One entry of the app's screen stack. See [[Presentation]] for how it sits over the view beneath; push and pop via
  * `TuiApp.pushScreen`/`popScreen`.
  */
trait Screen:

  /** This screen's element tree. Carries the same two contexts as `TuiApp.view` — see [[View]] — so a screen written as
    * its own class still gets the running app's [[Theme]] rather than the library default.
    */
  def view(using ReactiveScope, Theme): Element
  def presentation: Presentation = Presentation.Modal

  /** The keys this screen declares for as long as it is the top of the app's stack, in the same form as
    * `TuiApp.bindings` — see [[binding]].
    *
    * Before this existed, a screen that wanted its own shortcut had two unhappy options. Putting it on a root element
    * handler made it fire from wherever the tree was showing, including screens it had nothing to do with; putting it
    * in `TuiApp.bindings` made it a permanent app key that had to test the navigation depth itself before deciding
    * whether it meant anything. Declaring it here scopes it: `TuiApp` merges these over the app's own bindings while
    * this screen is on top, and they are gone the moment it is popped.
    *
    * They are merged *first*, so a screen key shadows an app key that answers to the same spec — `KeyBindings.handle`
    * runs the first binding that matches. They feed the status-bar hints, the help overlay and the command palette too,
    * through `TuiApp.activeBindings`, so the chrome advertises exactly the keys that will actually fire.
    */
  def bindings: KeyBindings = KeyBindings.empty

  /** A short human-readable name for this screen — "Settings", "Confirm delete" — or `None` when it has none.
    *
    * The library never draws this by itself. It exists so an application can build its own chrome from the stack:
    * `TuiApp.screenLabels` collects the named screens outermost-first, which is exactly the sequence a breadcrumb or a
    * title bar wants. Deriving the name from the class instead is not an option here — that would mean runtime
    * reflection, which this library refuses to depend on so that native images need no reflection configuration.
    */
  def label: Option[String] = None

  /** How this screen closes itself when the user tries to leave it — see [[Dismissal]]. `Never` by default, which is
    * what every screen did before this existed: only the application's own `popScreen()` closes it.
    *
    * Honoured only for [[Presentation.Modal]]. It is wired once by `TuiApp` rather than per dialog, which is the point:
    * before this, "clicking the darkened area around the dialog closes it" had to be written by hand for every dialog,
    * and in fact could not be written at all, because the layer underneath a modal is deliberately inert and a press
    * out there reached nothing.
    */
  def dismissal: Dismissal = Dismissal.Never

  /** Runs on the render thread when this stack entry becomes active, before the frame that first shows it. A push
    * during a run enters immediately, including from `TuiApp.onStart`. Retained inactive entries enter outermost first
    * after `onStart` returns successfully on the next run; entries removed during startup are not entered.
    *
    * This is a screen's own "now I am running", the counterpart of `TuiApp.onStart` for a subtree that comes and goes.
    * Without it, a screen that polls had to arm its poller in the app's `onStart` and cancel it in the app's `onStop`,
    * even though the screen might be popped long before the app exits — so the polling carried on against a screen
    * nobody could see.
    *
    * {{{
    * private var poller: Option[Cancelable] = None
    * override def onEnter(): Unit = poller = Some(Async.every(5.seconds)(refresh()))
    * override def onLeave(): Unit = poller.foreach(_.cancel())
    * }}}
    *
    * A screen pushed twice is two independent entries, each with one `onEnter` and matching [[onLeave]] per active
    * lifetime. Navigation outside a run or from cleanup retains inactive entries until a later startup.
    */
  def onEnter(): Unit = ()

  /** Runs on the render thread when an active entry is popped, replaced, reset away, or still on the stack when the run
    * ends — in which case it runs before `TuiApp.onStop`, so the app's own resources are still alive. Teardown leaves
    * entries innermost first without clearing navigation; a later run re-enters retained entries.
    *
    * Always paired with exactly one attempted [[onEnter]], including when acquisition itself threw part-way through. An
    * entry becomes inactive before this callback runs: a throwing cleanup or navigation from a hook cannot leave it
    * twice. Removing an inactive retained entry calls neither hook. Cancel here whatever `onEnter` started; nothing
    * else cancels a repeating `Async.every` for you.
    */
  def onLeave(): Unit = ()

object Screen:

  /** A modal screen from a view function (`Screen { dialogElement }`).
    *
    * `onEnter`/`onLeave` are the same hooks the trait declares, for a screen small enough not to want a class of its
    * own: `Screen(detailView, onEnter = () => startPolling(), onLeave = () => stopPolling())`.
    *
    * `dismissal` says whether `Esc` or a click outside closes it — see [[Dismissal]]; it applies to a modal screen
    * only, so `Screen.full` leaving it at `Never` is not an oversight.
    *
    * `label` names the screen for the application's own breadcrumbs and title bars — see [[Screen.label]]; leaving it
    * empty means the screen is unnamed.
    *
    * `keys` declares the shortcuts that exist only while this screen is on top — see [[Screen.bindings]] — so a dialog
    * can own its `Esc` without the app having to test the navigation depth:
    * `Screen(body, keys = KeyBindings(binding("esc", "close")(popScreen())))`.
    */
  def apply(
      element: View,
      onEnter: () => Unit = () => (),
      onLeave: () => Unit = () => (),
      keys: KeyBindings = KeyBindings.empty,
      label: String = "",
      dismissal: Dismissal = Dismissal.Never,
  ): Screen =
    build(element, Presentation.Modal, onEnter, onLeave, keys, label, dismissal)

  /** A ready-made modal "are you sure?": Left/Right (and Tab) move between the two buttons, Space or Enter presses the
    * selected one, Esc cancels.
    *
    * The screen owns the selection state, which is the last thing an application had to write by hand for this. Before
    * it, a confirmation meant a `Signal` for the selected index, the Left/Right/Enter/Esc wiring, and a `Screen` to
    * hold them, repeated per confirmation.
    *
    * Neither callback pops the screen: what should happen after a confirmation is the application's business, and a
    * screen that popped itself would take away the choice. A confirmation that closes reads
    * `pushScreen(Screen.confirm("Quit", "Discard unsaved changes?")({ popScreen(); quit() }, popScreen()))`. Both
    * callbacks are by-name, so nothing runs when the screen is built.
    *
    * @param confirmLabel
    *   the first button, and the one selected when the screen opens.
    */
  def confirm(
      title: String,
      message: String,
      confirmLabel: String = "OK",
      cancelLabel: String = "Cancel",
  )(onConfirm: => Unit, onCancel: => Unit): Screen =
    new Screen:
      private val selected                                        = Signal(0)
      private val labels                                          = Seq(confirmLabel, cancelLabel)
      def view(using scope: ReactiveScope, theme: Theme): Element =
        Element.confirmDialog(title, message, labels, selected.get)(
          index => selected.set(index),
          index => if index == 0 then onConfirm else onCancel,
          () => onCancel,
        )

  /** A screen that fully replaces the view beneath it. Takes the same `onEnter`/`onLeave` hooks and screen-scoped
    * `keys` as the modal form above.
    */
  def full(
      element: View,
      onEnter: () => Unit = () => (),
      onLeave: () => Unit = () => (),
      keys: KeyBindings = KeyBindings.empty,
      label: String = "",
      dismissal: Dismissal = Dismissal.Never,
  ): Screen =
    build(element, Presentation.Full, onEnter, onLeave, keys, label, dismissal)

  private def build(
      element: View,
      how: Presentation,
      entering: () => Unit,
      leaving: () => Unit,
      keys: KeyBindings,
      name: String,
      closes: Dismissal,
  ): Screen =
    new Screen:
      def view(using ReactiveScope, Theme): Element = element
      override def presentation: Presentation       = how
      override def onEnter(): Unit                  = entering()
      override def onLeave(): Unit                  = leaving()
      override def bindings: KeyBindings            = keys
      // an empty string is the "no name given" spelling: the parameter has to have a default, and `Option[String]` as
      // a parameter type would make every labelled call site write `Some(...)` for nothing
      override def label: Option[String]            = Option(name).filter(_.nonEmpty)
      override def dismissal: Dismissal             = closes

/** An intro shown before the first view render: `content` (typically a `bigText` logo composition) plays `effect` and
  * holds for at least `minimumDuration`; any key skips it. Wire via `TuiApp.splash`.
  */
final case class SplashScreen(
    content: Element,
    effect: Effect,
    minimumDuration: FiniteDuration = 1500.millis,
)
