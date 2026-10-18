package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import java.time.{LocalDate, LocalTime}

/** The interactive nodes in `ToolElements`, driven through a running app. */
final class ToolElementsPilotSpec extends AnyFunSuite with PilotFixture:

  private def startApp(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(Size(40, 10))
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

  test("timePicker: 23:59 plus a minute wraps to 00:00"):
    val state = TimePickerState(LocalTime.of(23, 59), withSeconds = false)
    val pilot = startApp(timePicker(state))
    assert(pilot.screenText.contains("23:59"))
    pilot.pressKey(KeyCode.Right).pressKey(KeyCode.Up).waitForIdle()
    assert(state.time == LocalTime.MIDNIGHT)
    assert(pilot.screenText.contains("00:00"))
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
