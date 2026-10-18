package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.{GoldenFrames, Pilot}
import io.worxbend.tui.widgets.DropdownState

import org.scalatest.funsuite.AnyFunSuite

/** `dropdown(...)` driven the way a user drives it: through `Pilot`, reading the frame back.
  *
  * The two things worth pinning down are that the highlight is not the chosen value until it is committed — so Escape
  * really does leave the value alone — and that opening the list floats it over the frame through a portal rather than
  * reflowing the siblings around it: whatever sits beside or below the row keeps its place, the list flips upward when
  * the bottom edge leaves no room, and a click anywhere outside the list closes it.
  */
final class DropdownElementSpec extends AnyFunSuite with PilotFixture:

  private val regions = Seq("eu-west", "us-east", "ap-south")

  private final class PickerApp extends TuiApp:
    val state: DropdownState = DropdownState()
    val chosen: Signal[Int]  = Signal(0)
    var commits: Int         = 0

    def view(using ReactiveScope, Theme): Element =
      column(
        dropdown(regions, chosen.get, state) { index =>
          chosen.set(index)
          commits += 1
        },
        text("BELOW"),
      )

  private def start(): (PickerApp, Pilot) =
    val backend = HeadlessBackend(Size(24, 10))
    val app     = PickerApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    (app, pilot)

  private def frame(pilot: Pilot): String = pilot.screenLines.mkString("\n")

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a closed dropdown is one row, and Enter opens the list"):
    val (app, pilot) = start()
    assert(pilot.screenLines.head.trim == "▸ eu-west")
    assert(pilot.screenLines(1).trim == "BELOW") // the row below sits directly underneath
    assert(!frame(pilot).contains("us-east"))
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(app.state.open)
    assert(frame(pilot).contains("us-east"))
    assert(frame(pilot).contains("ap-south"))
    quitApp(pilot)

  test("opening the list floats it over the content below, which keeps its row instead of moving down"):
    val (_, pilot) = start()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    // the popup — a top border, three options, a bottom border — covers rows 1 to 5; nothing was pushed to row 6,
    // which is where "BELOW" used to land when the node grew to make room
    assert(pilot.screenLines(1).trim.startsWith("╭"))
    assert(pilot.screenLines(5).trim.startsWith("╰"))
    assert(pilot.screenLines(6).trim.isEmpty)
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(pilot.screenLines(1).trim == "BELOW") // back where it always was, not where it was reflowed to
    quitApp(pilot)

  /** A two-pane scene: the dropdown and a label in the left pane, a numbered column in the right. The right pane is
    * what shows the siblings did not move — with the old in-layout popup, opening the list grew the left pane's column
    * and nothing else, so the two panes went out of step.
    */
  private final class TwoPanes extends TuiApp:
    val state: DropdownState                      = DropdownState()
    def view(using ReactiveScope, Theme): Element =
      row(
        column(dropdown(regions, 0, state)(_ => ()), text("BELOW")).length(12),
        column((0 until 8).map(index => text(s"R$index"))*).fill,
      )

  test("golden before and after: the right pane keeps every row while the list is open"):
    val backend   = HeadlessBackend(Size(24, 8))
    val app       = TwoPanes()
    val pilot     = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    GoldenFrames.assertMatches("dropdown-closed", pilot.lastFrame)
    val rightPane = pilot.screenLines.map(_.drop(12).trim)
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    GoldenFrames.assertMatches("dropdown-open", pilot.lastFrame)
    assert(pilot.screenLines.map(_.drop(12).trim) == rightPane, "the right pane moved when the list opened")
    assert(pilot.screenLines(2).take(12).contains("eu-west")) // the list is drawn in the left pane, under the row
    quitApp(pilot)

  test("near the bottom edge the list opens upward instead of being clipped"):
    final class Bottom extends TuiApp:
      val state: DropdownState                      = DropdownState()
      val chosen: Signal[Int]                       = Signal(0)
      def view(using ReactiveScope, Theme): Element =
        column(spacer, dropdown(regions, chosen.get, state)(chosen.set))

    val backend = HeadlessBackend(Size(24, 8))
    val app     = Bottom()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenLines(7).trim == "▸ eu-west")
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    // no room below row 7, so the five popup rows sit on rows 2 to 6, bottom border touching the closed row
    assert(pilot.screenLines(2).trim.startsWith("╭"))
    assert(pilot.screenLines(3).contains("eu-west"))
    assert(pilot.screenLines(5).contains("ap-south"))
    assert(pilot.screenLines(6).trim.startsWith("╰"))
    assert(pilot.screenLines(7).trim == "▾ eu-west")
    // and the options are clickable where they are drawn
    pilot.click(4, 4).waitForIdle()
    assert(app.chosen.peek == 1)
    assert(!app.state.open)
    quitApp(pilot)

  test("a click anywhere outside the list closes it without committing, and goes no further"):
    final class WithButton extends TuiApp:
      val state: DropdownState                      = DropdownState()
      val pressed: Signal[Int]                      = Signal(0)
      var commits: Int                              = 0
      def view(using ReactiveScope, Theme): Element =
        column(dropdown(regions, 0, state)(_ => commits += 1), spacer, button("GO")(pressed.set(pressed.peek + 1)))

    val backend = HeadlessBackend(Size(24, 10))
    val app     = WithButton()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    pilot.pressKey(KeyCode.Down).waitForIdle()
    pilot.click(20, 7).waitForIdle() // empty space
    assert(!app.state.open)
    assert(app.commits == 0)
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    pilot.click(3, 9).waitForIdle()  // the button: the click closes the list and is spent on that
    assert(!app.state.open)
    assert(app.pressed.peek == 0)
    assert(app.commits == 0)
    quitApp(pilot)

  test("Tab while the list is open closes it and moves focus on"):
    final class TwoControls extends TuiApp:
      val state: DropdownState                      = DropdownState()
      val pressed: Signal[Int]                      = Signal(0)
      def view(using ReactiveScope, Theme): Element =
        column(dropdown(regions, 0, state)(_ => ()), spacer, button("GO")(pressed.set(pressed.peek + 1)))

    val backend = HeadlessBackend(Size(24, 8))
    val app     = TwoControls()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(app.state.open)
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    assert(!app.state.open)
    assert(!frame(pilot).contains("us-east"))
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(app.pressed.peek == 1)
    quitApp(pilot)

  test("the wheel over the floating list moves the highlight"):
    val (app, pilot) = start()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    pilot.scrollDown(4, 3).waitForIdle()
    assert(app.state.highlighted.contains(1))
    pilot.scrollUp(4, 3).waitForIdle()
    assert(app.state.highlighted.contains(0))
    quitApp(pilot)

  test("inside a bordered panel the list spills past the border instead of being clipped by it"):
    final class Framed extends TuiApp:
      val state: DropdownState                      = DropdownState()
      def view(using ReactiveScope, Theme): Element =
        column(panel("Pick")(dropdown(regions, 0, state)(_ => ())).length(3), text("BELOW"))

    val backend = HeadlessBackend(Size(24, 10))
    val app     = Framed()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenLines(3).trim == "BELOW")
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    // the row is at row 1 inside the panel; the popup starts at row 2 and runs over the panel's bottom border and the
    // text under it
    assert(pilot.screenLines(3).contains("eu-west"))
    assert(pilot.screenLines(5).contains("ap-south"))
    quitApp(pilot)

  test("Down moves the highlight and Enter commits it"):
    val (app, pilot) = start()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    pilot.pressKey(KeyCode.Down).waitForIdle()
    assert(app.chosen.peek == 0) // moving the highlight commits nothing
    assert(app.commits == 0)
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(app.chosen.peek == 1)
    assert(app.commits == 1)
    assert(!app.state.open)
    assert(pilot.screenLines.head.trim == "▸ us-east")
    quitApp(pilot)

  test("Escape closes the list and leaves the chosen value alone"):
    val (app, pilot) = start()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    pilot.pressKey(KeyCode.Down).waitForIdle()
    pilot.pressKey(KeyCode.Down).waitForIdle()
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(!app.state.open)
    assert(app.chosen.peek == 0)
    assert(app.commits == 0)
    assert(pilot.screenLines.head.trim == "▸ eu-west")
    quitApp(pilot)

  test("the highlight opens on the option in force, so opening and committing changes nothing"):
    val (app, pilot) = start()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    pilot.pressKey(KeyCode.Down).waitForIdle()
    pilot.pressKey(KeyCode.Enter).waitForIdle() // now on us-east
    pilot.pressKey(KeyCode.Enter).waitForIdle() // reopen
    assert(app.state.menu.selected.contains(1))
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(app.chosen.peek == 1)
    quitApp(pilot)

  test("a click on the row opens the list, and a click on an option commits it"):
    val (app, pilot) = start()
    pilot.click(2, 0).waitForIdle()
    assert(app.state.open)
    // row 0 is the closed row, row 1 the popup's top border, so the options start at row 2
    pilot.click(4, 4).waitForIdle()
    assert(app.chosen.peek == 2)
    assert(!app.state.open)
    quitApp(pilot)

  test("a click on the row while open closes it again without committing"):
    val (app, pilot) = start()
    pilot.click(2, 0).waitForIdle()
    pilot.click(2, 0).waitForIdle()
    assert(!app.state.open)
    assert(app.commits == 0)
    quitApp(pilot)

  test("a closed dropdown leaves keys alone so Tab still moves focus"):
    // A closed dropdown is a label. If it swallowed keys it did not use, the control after it could never be reached.
    final class TwoControls extends TuiApp:
      val state: DropdownState                      = DropdownState()
      val pressed: Signal[Int]                      = Signal(0)
      def view(using ReactiveScope, Theme): Element =
        column(
          dropdown(regions, 0, state)(_ => ()),
          button("GO")(pressed.set(pressed.peek + 1)),
        )

    val backend = HeadlessBackend(Size(24, 6))
    val app     = TwoControls()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(app.pressed.peek == 1)
    assert(!app.state.open)
    pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("an empty dropdown opens nothing and stays one row"):
    final class EmptyApp extends TuiApp:
      val state: DropdownState                      = DropdownState()
      def view(using ReactiveScope, Theme): Element =
        column(dropdown(Seq.empty, 0, state)(_ => ()), text("BELOW"))

    val backend = HeadlessBackend(Size(24, 6))
    val app     = EmptyApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(!app.state.open)
    assert(pilot.screenLines(1).trim == "BELOW")
    pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("the node claims one row whether the list is open or closed"):
    // A construction test rather than a rendered one: the claim is what a container reads to hand out rows, and it
    // must not change with the state any more — the list floats, so the layout around the row never makes room for it.
    val state = DropdownState()
    val node  = DropdownElement(regions, 0, state, _ => ())
    assert(node.claim == SizeClaim.OneRow)
    state.openAt(0)
    assert(node.claim == SizeClaim.OneRow)
