package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.widgets.{TextAreaState, TextInputState}

import org.scalatest.funsuite.AnyFunSuite

/** The shared editing chords of `input` and `textArea`, driven through a whole app: selection, word kills, the kill
  * ring and the app clipboard.
  */
final class EditingChordsSpec extends AnyFunSuite with PilotFixture:

  private val Ctrl  = KeyModifiers.Ctrl
  private val Shift = KeyModifiers.Shift
  private val Alt   = KeyModifiers.Alt

  test("Ctrl+W kills a word, Shift+Left selects, Ctrl+X and Ctrl+V move it to another field through the clipboard"):
    val backend = HeadlessBackend(Size(30, 6))
    val first   = TextInputState()
    val second  = TextInputState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = column(input(first), input(second))
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("hello brave world").pressKey(KeyCode.Char('w'), Ctrl).waitForIdle()
    assert(first.value == "hello brave ")
    pilot.pressKey(KeyCode.Backspace)
    (1 to 5).foreach(_ => pilot.pressKey(KeyCode.Left, Shift))
    pilot.pressKey(KeyCode.Char('x'), Ctrl).waitForIdle()
    assert(first.value == "hello ")
    assert(backend.clipboardContents == Some("brave")) // OSC 52 went out too
    pilot.pressKey(KeyCode.Tab).pressKey(KeyCode.Char('v'), Ctrl).waitForIdle()
    assert(second.value == "brave")
    pilot.pressKey(KeyCode.Char('q'), Ctrl)
    assert(pilot.awaitTermination())

  test("Ctrl+K kills to the end of the line, Ctrl+Y yanks it back, Alt+Y cycles the ring"):
    val backend = HeadlessBackend(Size(30, 6))
    val state   = TextAreaState("alpha beta")
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = column(textArea(state))
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('w'), Ctrl) // kills "beta"
    pilot.pressKey(KeyCode.Home).pressKey(KeyCode.Right).pressKey(KeyCode.Char('k'), Ctrl).waitForIdle()
    assert(state.value == "a")
    pilot.pressKey(KeyCode.Char('y'), Ctrl).waitForIdle()
    assert(state.value == "alpha ")
    pilot.pressKey(KeyCode.Char('y'), Alt).waitForIdle()
    assert(state.value == "abeta")
    pilot.pressKey(KeyCode.Char('z'), Ctrl).waitForIdle()
    assert(state.value == "alpha ")
    pilot.pressKey(KeyCode.Char('z'), Ctrl | Shift).waitForIdle()
    assert(state.value == "abeta")
    pilot.pressKey(KeyCode.Char('q'), Ctrl)
    assert(pilot.awaitTermination())

  test("Ctrl+C with nothing selected keeps bubbling to the app"):
    val backend = HeadlessBackend(Size(30, 6))
    val state   = TextInputState("text")
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element = column(input(state))
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('c'), Ctrl)
    assert(pilot.awaitTermination())
