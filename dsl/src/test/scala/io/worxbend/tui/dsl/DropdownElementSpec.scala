package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.DropdownState

import org.scalatest.funsuite.AnyFunSuite

/** `dropdown(...)` driven the way a user drives it: through `Pilot`, reading the frame back.
  *
  * The two things worth pinning down are that the highlight is not the chosen value until it is committed — so Escape
  * really does leave the value alone — and that opening the list makes room for it in the layout rather than painting
  * over whatever is below.
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

  test("opening the list pushes the content below it down instead of painting over it"):
    val (_, pilot) = start()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    // one closed row, a top border, three options, a bottom border: "BELOW" has moved from row 1 to row 6
    assert(pilot.screenLines(6).trim == "BELOW")
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

  test("the node claims one row closed and the full popup open"):
    // A construction test rather than a rendered one: the claim is what a container reads to hand out rows, and it has
    // to change with the state or the list would be drawn over whatever comes next.
    val state = DropdownState()
    val node  = DropdownElement(regions, 0, state, _ => ())
    assert(node.claim == SizeClaim.OneRow)
    state.openAt(0)
    assert(node.claim == SizeClaim.rows(6))
