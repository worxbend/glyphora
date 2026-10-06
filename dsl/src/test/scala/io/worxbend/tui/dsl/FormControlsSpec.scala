package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.macros.deriveForm
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.{ListState, TextInputState}

import org.scalatest.funsuite.AnyFunSuite

/** A top-level case class so `deriveForm` can summon its `Mirror`. */
final case class Reservation(name: String, guests: Int)

final class FormControlsSpec extends AnyFunSuite with PilotFixture:

  private def startApp(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(Size(40, 8))
    val testApp = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("radioGroup renders markers and moves the selection with arrows"):
    val selected = Signal(0)
    val pilot    = startApp(radioGroup(Seq("small", "medium", "large"), selected))
    assert(pilot.screenLines.head.startsWith("(•) small"))
    pilot.pressKey(KeyCode.Down).pressKey(KeyCode.Down).waitForIdle()
    assert(selected.peek == 2)
    assert(pilot.screenLines(2).startsWith("(•) large"))
    quitApp(pilot)

  test("slider adjusts by step and clamps at the bounds"):
    val value = Signal(50)
    val pilot = startApp(slider(value, SliderRange.of(0, 100, step = 10)))
    pilot.pressKey(KeyCode.Right).waitForIdle()
    assert(value.peek == 60)
    pilot.pressKey(KeyCode.End).waitForIdle()
    assert(value.peek == 100)
    pilot.pressKey(KeyCode.Right).waitForIdle()
    assert(value.peek == 100)
    pilot.pressKey(KeyCode.Home).waitForIdle()
    assert(value.peek == 0)
    quitApp(pilot)

  test("selectionList toggles membership with space"):
    val selected = Signal(Set.empty[Int])
    val state    = ListState()
    val pilot    = startApp(selectionList(Seq("read", "write", "exec"), selected, state))
    pilot.pressKey(KeyCode.Down).pressKey(KeyCode.Char(' ')).waitForIdle()
    assert(selected.peek == Set(0))
    pilot.pressKey(KeyCode.Down).pressKey(KeyCode.Char(' ')).waitForIdle()
    assert(selected.peek == Set(0, 1))
    pilot.pressKey(KeyCode.Char(' ')).waitForIdle()
    assert(selected.peek == Set(0))
    assert(pilot.screenText.contains("[x] read"))
    assert(pilot.screenText.contains("[ ] write"))
    quitApp(pilot)

  test("numberInput accepts digits, one leading minus, and rejects letters"):
    val state = TextInputState()
    val pilot = startApp(numberInput(state))
    pilot.typeText("-12a3-").waitForIdle()
    assert(state.value == "-123")
    quitApp(pilot)

  test("numberInput allows a single dot only when decimals are enabled"):
    val state = TextInputState()
    val pilot = startApp(numberInput(state).decimal)
    pilot.typeText("3.1.4").waitForIdle()
    assert(state.value == "3.14")
    quitApp(pilot)

  /** A derived `Int` field used to render as a plain text input, so letters typed into it went in and stayed there
    * looking accepted until submit answered `'12ab3' is not a whole number`. The rejection now happens at the
    * keystroke.
    */
  test("a derived Int field refuses letters as they are typed, while the String field beside it takes them"):
    val state = FormState.of(deriveForm[Reservation])
    val pilot = startApp(Form(state))
    val text  = state.bindings.collect { case FieldBinding.TextLike(_, input, _) => input }
    pilot.typeText("ab").waitForIdle()
    assert(text.head.value == "ab", "the String field still accepts letters")
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    pilot.typeText("12ab3").waitForIdle()
    assert(text(1).value == "123", "letters must never reach a derived Int field's state")
    quitApp(pilot)

  test("templateInput inserts literals automatically and erases whole slots"):
    val state = TextInputState()
    val pilot = startApp(templateInput(state, "##/##"))
    pilot.typeText("12x34").waitForIdle()
    assert(state.value == "12/34")
    pilot.pressKey(KeyCode.Backspace).waitForIdle()
    assert(state.value == "12/3")
    pilot.pressKey(KeyCode.Backspace).waitForIdle()
    assert(state.value == "12") // the literal '/' went with its slot
    quitApp(pilot)

  test("paginator renders dots and pages with arrows"):
    val page    = Signal(0)
    val element = paginator(page, 4)(using ReactiveScope.untracked)
    assert(trimmedLines(rendered(element.widget, 10, 1)).head == "● ○ ○ ○")
    val pilot   = startApp(paginator(page, 4))
    pilot.pressKey(KeyCode.Right).pressKey(KeyCode.Right).waitForIdle()
    assert(page.peek == 2)
    quitApp(pilot)
