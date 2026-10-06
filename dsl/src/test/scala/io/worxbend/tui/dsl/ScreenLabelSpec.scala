package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** `Screen.label` and `TuiApp.screenLabels`: the names an application builds a breadcrumb or a title bar from.
  *
  * The library draws none of this itself, so what has to be pinned down is the shape of the list — outermost first,
  * unnamed screens skipped, the app's own view absent — and that reading it from a `view` subscribes that view to
  * navigation, so the trail repaints when a screen is pushed or popped.
  */
final class ScreenLabelSpec extends AnyFunSuite with PilotFixture:

  private final class BreadcrumbApp extends TuiApp:
    /** Every screen here is a full-screen page that draws the breadcrumb itself, so the trail is visible on screen at
      * every depth. A modal would paint over the app's own view and hide the very thing under test.
      */
    private def named(name: String): Screen = Screen.full(trail, label = name)

    private val anonymous: Screen = Screen.full(trail)

    /** The breadcrumb, as a view: `screenLabels` is a reactive read, so whichever view evaluates this subscribes to
      * navigation and recomputes on the next push or pop.
      */
    private def trail: View = text(("Home" +: screenLabels).mkString(" > "))

    override def bindings: KeyBindings = KeyBindings(
      binding("1", "settings")(pushScreen(named("Settings"))),
      binding("2", "theme")(pushScreen(named("Theme"))),
      binding("d", "a nameless dialog")(pushScreen(anonymous)),
      binding("b", "back")(popScreen()),
      binding("ctrl+q", "quit")(quit()),
    )

    def view(using ReactiveScope, Theme): Element = trail

  private def start(): Pilot =
    val backend = HeadlessBackend(Size(60, 3))
    startPilot(backend) { BreadcrumbApp().runWith(backend) }.waitForIdle()

  private def press(pilot: Pilot, key: Char): Unit =
    val _ = pilot.pressKey(KeyCode.Char(key)).waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("the labels read outermost first, and every push and pop repaints the view that reads them"):
    val pilot = start()
    assert(pilot.screenLines.head.trim == "Home")
    press(pilot, '1')
    assert(pilot.screenLines.head.trim == "Home > Settings")
    press(pilot, '2')
    // outermost first: the screen pushed first is nearest "Home", even though the stack holds the newest one at its head
    assert(pilot.screenLines.head.trim == "Home > Settings > Theme")
    press(pilot, 'd')
    // the nameless dialog adds a level of depth but no step in the trail
    assert(pilot.screenLines.head.trim == "Home > Settings > Theme")
    press(pilot, 'b')
    press(pilot, 'b')
    assert(pilot.screenLines.head.trim == "Home > Settings")
    press(pilot, 'b')
    assert(pilot.screenLines.head.trim == "Home")
    quitApp(pilot)

  test("an unnamed screen contributes nothing rather than a blank step"):
    val screens = Seq(Screen(text("x"), label = "Theme"), Screen(text("y")), Screen(text("z"), label = "Settings"))
    // the same flatMap the app performs, over a stack held newest-first
    assert(screens.reverse.flatMap(_.label) == Seq("Settings", "Theme"))

  test("a screen built without a label has none, and an empty label counts as none"):
    assert(Screen(text("x")).label.isEmpty)
    assert(Screen(text("x"), label = "").label.isEmpty)
    assert(Screen.full(text("x")).label.isEmpty)

  test("both factories carry the label they were given, alongside their presentation"):
    val modal = Screen(text("x"), label = "Confirm delete")
    val page  = Screen.full(text("y"), label = "Dashboard")
    assert(modal.label.contains("Confirm delete"))
    assert(modal.presentation == Presentation.Modal)
    assert(page.label.contains("Dashboard"))
    assert(page.presentation == Presentation.Full)

  test("a label may be any text the terminal can draw, including CJK and emoji"):
    // The label is passed through untouched — no width arithmetic happens to it here, so what the app writes is what a
    // `text` node is asked to draw, and the node does the column math.
    assert(Screen(text("x"), label = "設定").label.contains("設定"))
    assert(Screen(text("x"), label = "👩‍💻 profile").label.contains("👩‍💻 profile"))

  test("a labelled screen still carries its hooks and its own keys"):
    // The label is one more defaulted parameter beside them, so it must not displace what was already there.
    var entered = 0
    val screen  = Screen(
      text("x"),
      onEnter = () => entered += 1,
      keys = KeyBindings(binding("esc", "close")(())),
      label = "Editor",
    )
    screen.onEnter()
    assert(entered == 1)
    assert(screen.bindings.hints == Seq(("esc", "close")))
    assert(screen.label.contains("Editor"))
