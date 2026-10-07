package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.TextInputState

import org.scalatest.funsuite.AnyFunSuite

final class NumericInputEditingSpec extends AnyFunSuite:
  private def withElement(element: => Element)(check: Pilot => Unit): Unit =
    val app = new TuiApp:
      def view(using ReactiveScope, Theme): Element = element
    Pilot.using(Size(40, 8))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      check(pilot)
    }

  test("numeric paste inserts the whole value at the cursor"):
    val state = TextInputState("14")
    withElement(numberInput(state)) { pilot =>
      pilot.press("left").paste("23").waitForIdle()
      assert(state.value == "1234")
      assert(state.cursor == 3)
    }

  test("shift-modified digits insert while command chords still bubble"):
    val state = TextInputState()
    var saves = 0
    withElement(column(numberInput(state)).onKey("ctrl+s") { saves += 1 }) { pilot =>
      pilot.pressKey(KeyCode.Char('1'), KeyModifiers.Shift).press("ctrl+s").waitForIdle()
      assert(state.value == "1")
      assert(saves == 1)
    }

  test("insertion cannot move an existing minus away from the leading position"):
    val state = TextInputState("-12")
    withElement(numberInput(state)) { pilot =>
      pilot.press("home").typeText("3").waitForIdle()
      assert(state.value == "-12")
      assert(state.cursor == 0)
      pilot.press("right").typeText("3").waitForIdle()
      assert(state.value == "-312")
    }

  test("an invalid numeric paste is rejected atomically without invoking application keys"):
    val state    = TextInputState("12")
    var commands = 0
    withElement(numberInput(state).onKey("q") { commands += 1 }) { pilot =>
      pilot.paste("q34").waitForIdle()
      assert(state.value == "12")
      assert(commands == 0)
      pilot.paste("3\n4").waitForIdle()
      assert(state.value == "12")
    }

  test("decimal paste preserves partial numbers and rejects a second dot"):
    val state = TextInputState()
    withElement(numberInput(state).decimal) { pilot =>
      pilot.paste("-.").waitForIdle()
      assert(state.value == "-.")
      pilot.paste("25").waitForIdle()
      assert(state.value == "-.25")
      pilot.paste(".5").waitForIdle()
      assert(state.value == "-.25")
    }
