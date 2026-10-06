package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.widgets.TextInputState

import org.scalatest.funsuite.AnyFunSuite

/** Where a key release goes, and — more importantly — where it does not. Getting the second half wrong is how one
  * keystroke runs an action twice on a kitty-protocol terminal and once everywhere else.
  */
final class KeyReleaseRoutingSpec extends AnyFunSuite with PilotFixture:

  test("a release reaches onKeyRelease and never the press handler"):
    val backend  = HeadlessBackend(Size(30, 6))
    val state    = TextInputState()
    val observed = scala.collection.mutable.ArrayBuffer.empty[String]
    val app      = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        input(state).autofocus
          .onKeyEvent { key =>
            observed += s"press ${key.code}"; false
          }
          .onKeyRelease { key =>
            observed += s"release ${key.code}"; true
          }
    val pilot    = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()

    pilot.release("a").waitForIdle()
    assert(observed.toSeq == Seq("release Char(97)"))
    // and the release must not have been typed into the focused field
    assert(state.value == "")

    observed.clear()
    pilot.press("b").waitForIdle()
    assert(observed.toSeq == Seq("press Char(98)"))

    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** A binding names a press. Firing it again on the way up would run every chord twice — the exact double-fire the
    * separate `Event.KeyRelease` case exists to prevent.
    */
  test("a release does not fire an application binding"):
    val backend = HeadlessBackend(Size(30, 6))
    val state   = TextInputState()
    var saves   = 0
    val app     = new TuiApp:
      override def bindings: KeyBindings            =
        KeyBindings(binding("ctrl+s", "save")(saves += 1), binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = input(state).autofocus
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()

    pilot.release("ctrl+s").waitForIdle()
    assert(saves == 0)
    pilot.press("ctrl+s").waitForIdle()
    assert(saves == 1)

    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** A built-in is written against a press, so a release must not run one: a text input that inserted on the way up
    * would double every character the user typed.
    */
  test("a release runs no built-in behaviour on the focused element"):
    val backend = HeadlessBackend(Size(30, 6))
    val state   = TextInputState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = input(state).autofocus
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()

    pilot.press("x").waitForIdle()
    pilot.release("x").waitForIdle()
    assert(state.value == "x")

    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a release bubbles to an ancestor that asked for it"):
    val backend  = HeadlessBackend(Size(30, 8))
    val state    = TextInputState()
    val observed = scala.collection.mutable.ArrayBuffer.empty[String]
    val app      = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        column(input(state).autofocus).onKeyRelease { key =>
          observed += key.code.toString; true
        }
    val pilot    = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()

    pilot.release("a").waitForIdle()
    assert(observed.toSeq == Seq("Char(97)"))

    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** Handlers layer instead of overwriting, the same rule `onKeyEvent` follows: an element that already carried one
    * still gets it after a second is added.
    */
  test("onKeyRelease handlers compose rather than replacing each other"):
    val seen    = scala.collection.mutable.ArrayBuffer.empty[Int]
    val element = text("x")
      .onKeyRelease { _ =>
        seen += 1; false
      }
      .onKeyRelease { _ =>
        seen += 2; false
      }
    assert(!element.props.onKeyUp.exists(_(KeyEvent.char('a'))))
    assert(seen.toSeq == Seq(2, 1))
