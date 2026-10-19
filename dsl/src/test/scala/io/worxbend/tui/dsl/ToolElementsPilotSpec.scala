package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import java.time.{LocalDate, LocalTime}

/** The interactive nodes in `ChoiceElements`, `DisplayElements` and `CollectionElements` (formerly `ToolElements`),
  * driven through a running app.
  */
final class ToolElementsPilotSpec extends AnyFunSuite with PilotFixture:

  private def startApp(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(Size(40, 20))
    val testApp = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("datePicker: Right at month end shows the next month, Enter chooses the date"):
    val state  = DatePickerState(LocalDate.of(2026, 7, 31), () => LocalDate.of(2026, 10, 19))
    var chosen = Option.empty[LocalDate]
    val pilot  = startApp(datePicker(state, date => chosen = Some(date)))
    assert(pilot.screenText.contains("July 2026"))
    pilot.pressKey(KeyCode.Right).waitForIdle()
    assert(state.cursor == LocalDate.of(2026, 8, 1))
    assert(pilot.screenText.contains("August 2026"))
    pilot.pressKey(KeyCode.PageDown).pressKey(KeyCode.Down).waitForIdle()
    assert(state.cursor == LocalDate.of(2026, 9, 8))
    pilot.pressKey(KeyCode.Char('t')).pressKey(KeyCode.Enter).waitForIdle()
    assert(chosen.contains(LocalDate.of(2026, 10, 19)))
    assert(pilot.screenText.contains("October 2026"))
    quitApp(pilot)

  test("datePicker: min/max keep keys and clicks inside the range"):
    val state  = DatePickerState(
      LocalDate.of(2026, 7, 14),
      () => LocalDate.of(2026, 10, 19),
      min = Some(LocalDate.of(2026, 7, 10)),
      max = Some(LocalDate.of(2026, 7, 20)),
    )
    var chosen = Option.empty[LocalDate]
    val pilot  = startApp(datePicker(state, date => chosen = Some(date)))
    pilot.pressKey(KeyCode.Up).pressKey(KeyCode.Up).waitForIdle()
    assert(state.cursor == LocalDate.of(2026, 7, 10))
    pilot.pressKey(KeyCode.PageDown).waitForIdle()
    assert(state.cursor == LocalDate.of(2026, 7, 20))
    assert(pilot.screenText.contains("July 2026"))
    // the 21st (second slot, fourth week row: x = 3, y = 5) is out of range — a click on it does nothing
    pilot.click(3, 5).waitForIdle()
    assert(chosen.isEmpty)
    assert(state.cursor == LocalDate.of(2026, 7, 20))
    // the 15th (third slot, third week row: x = 6, y = 4) is in range — a click chooses it
    pilot.click(6, 4).waitForIdle()
    assert(state.cursor == LocalDate.of(2026, 7, 15))
    assert(chosen.contains(LocalDate.of(2026, 7, 15)))
    quitApp(pilot)

  test("timePicker: 23:59 plus a minute wraps to 00:00"):
    val state = TimePickerState(LocalTime.of(23, 59), withSeconds = false)
    val pilot = startApp(timePicker(state))
    assert(pilot.screenText.contains("23:59"))
    pilot.pressKey(KeyCode.Right).pressKey(KeyCode.Up).waitForIdle()
    assert(state.time == LocalTime.MIDNIGHT)
    assert(pilot.screenText.contains("00:00"))
    quitApp(pilot)

  test("timePicker: typed digits fill the focused field and move on"):
    val state = TimePickerState(LocalTime.of(0, 0), withSeconds = false)
    val pilot = startApp(timePicker(state))
    pilot.typeText("0930").waitForIdle()
    assert(state.time == LocalTime.of(9, 30))
    assert(pilot.screenText.contains("09:30"))
    quitApp(pilot)

  test("rangeSlider: Tab moves to the upper thumb, the thumbs cannot cross, Tab from the top leaves"):
    val state = RangeSliderState(SliderRange.of(0, 100, 10), 40, 60)
    var heard = Option.empty[(Int, Int)]
    val other = TextInputState()
    val pilot = startApp(column(rangeSlider(state, (lo, hi) => heard = Some((lo, hi))), input(other)))
    pilot.pressKey(KeyCode.Right).pressKey(KeyCode.Right).pressKey(KeyCode.Right).waitForIdle()
    assert((state.low, state.high) == (60, 60))
    assert(heard.contains((60, 60)))
    pilot.pressKey(KeyCode.Tab).pressKey(KeyCode.Right).waitForIdle()
    assert(state.thumb == RangeThumb.High)
    assert(state.high == 70)
    pilot.pressKey(KeyCode.Tab).typeText("x").waitForIdle()
    assert(other.value == "x")
    quitApp(pilot)

  test("colorPicker: walking to the bright-red swatch and pressing Enter emits Color.Indexed(196)"):
    val state  = ColorPickerState()
    var chosen = Option.empty[Color]
    val pilot  = startApp(colorPicker(state, color => chosen = Some(color)))
    (1 to 12).foreach(_ => pilot.pressKey(KeyCode.Down))
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(chosen.contains(Color.Indexed(196)))
    assert(pilot.screenText.contains("196  #ff0000"))
    quitApp(pilot)

  test("hexViewer: Down moves the selection a row and the view scrolls with it"):
    val data  = IndexedSeq.tabulate(400)(_.toByte)
    val state = HexViewerState()
    val pilot = startApp(hexViewer(data, state))
    assert(pilot.screenText.contains("00000000  00 01 02"))
    pilot.pressKey(KeyCode.Right).pressKey(KeyCode.End).pressKey(KeyCode.Up).waitForIdle()
    assert(state.selected.contains(383))
    assert(pilot.screenText.contains("00000180"))
    assert(!pilot.screenText.contains("00000000"))
    quitApp(pilot)

  test("treeTable: Enter expands a branch and its children appear with their columns"):
    val nodes = Seq(TreeNode("src", Seq(TreeNode("Main.scala"))), TreeNode("README.md"))
    val size  = Map("src" -> "-", "Main.scala" -> "1200", "README.md" -> "80")
    val state = TreeState.keyed(_.label)
    state.selected = Some("src")
    val table = TreeTable[String](
      nodes,
      Seq("Name", "Bytes"),
      key => Seq(size(key)),
      Seq(Constraint.Length(16), Constraint.Length(6)),
    )
    val pilot = startApp(treeTable(table, state))
    assert(!pilot.screenText.contains("Main.scala"))
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(pilot.screenText.contains("Main.scala"))
    assert(pilot.screenText.contains("1200"))
    pilot.pressKey(KeyCode.Left).waitForIdle()
    assert(!pilot.screenText.contains("Main.scala"))
    pilot.pressKey(KeyCode.Right).pressKey(KeyCode.Down).waitForIdle()
    assert(state.selected.contains("Main.scala"))
    quitApp(pilot)
