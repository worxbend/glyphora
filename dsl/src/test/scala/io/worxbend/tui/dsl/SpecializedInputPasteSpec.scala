package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.TextInputState

import org.scalatest.funsuite.AnyFunSuite

final class SpecializedInputPasteSpec extends AnyFunSuite:
  private def withElement(element: => Element)(check: Pilot => Unit): Unit =
    val app = new TuiApp:
      def view(using ReactiveScope, Theme): Element = element
    Pilot.using(Size(40, 8))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      check(pilot)
    }

  test("autocomplete paste folds newlines and resets the suggestion highlight"):
    val state = AutocompleteState()
    state.highlighted = 2
    withElement(autocomplete(Seq("Ada Lovelace"), state)) { pilot =>
      pilot.paste("Ada\nLovelace").waitForIdle()
      assert(state.input.value == "Ada Lovelace")
      assert(state.highlighted == 0)
    }

  test("template paste uses the same slot and literal rules as typing"):
    val state     = TextInputState()
    var submitted = 0
    withElement(templateInput(state, "##/##").onKey("enter") { submitted += 1 }) { pilot =>
      pilot.paste("12/34\n").waitForIdle()
      assert(state.value == "12/34")
      assert(submitted == 0)
    }

  test("template input accepts shift-modified letters"):
    val state = TextInputState()
    withElement(templateInput(state, "AA")) { pilot =>
      pilot.pressKey(KeyCode.Char('A'), KeyModifiers.Shift).waitForIdle()
      assert(state.value == "A")
    }
