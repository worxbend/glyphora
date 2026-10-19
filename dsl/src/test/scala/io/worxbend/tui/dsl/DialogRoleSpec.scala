package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Cell, Color, KeyCode, KeyModifiers, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** The `dialog` component role: `dialog` and `confirmDialog` take their body and selected-button styles from the
  * theme's `"dialog"` / `"dialog.selected"` components, and draw as they always did when the theme has neither.
  */
final class DialogRoleSpec extends AnyFunSuite with PilotFixture:

  private val Body     = Style.Default.withFg(Color.White).withBg(Color.Blue)
  private val Selected = Style.Default.withFg(Color.Black).withBg(Color.Yellow)

  private val themed: Theme = Theme.Dark
    .withComponent(DialogRole, StateStyles(base = Body))
    .withComponent(DialogRole.Selected, StateStyles(base = Selected, focused = Style.Default.withBg(Color.Green)))

  private def driving(appTheme: Theme)(content: View)(body: Pilot => Unit): Unit =
    val app     = new TuiApp:
      override def theme: Theme                     = appTheme
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = content
    val backend = HeadlessBackend(Size(30, 7))
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    body(pilot)
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** The first cell, row by row, showing `symbol`. */
  private def find(pilot: Pilot, symbol: String): Cell =
    (0 until 7).iterator
      .flatMap(y => (0 until 30).iterator.map(x => pilot.cellAt(x, y)))
      .find(_.symbol == symbol)
      .getOrElse(fail(s"no '$symbol' on screen:\n${pilot.screenText}"))

  test("dialog draws its body and its selected button from the theme's dialog components"):
    driving(themed)(dialog("Saved", "Written.", Seq("Fine", "Undo"))) { pilot =>
      val body   = find(pilot, "W")
      assert(body.style.bg.contains(Color.Blue), body.toString)
      assert(body.style.fg.contains(Color.White), body.toString)
      val button = find(pilot, "F")
      assert(button.style.bg.contains(Color.Yellow), button.toString)
      assert(find(pilot, "U").style.bg.contains(Color.Blue), "the other button is drawn in the body style")
    }

  test("a theme with no dialog components draws dialog as before: primary body, focus-reversed selection"):
    driving(Theme.Dark)(dialog("Saved", "Written.", Seq("Fine", "Undo"))) { pilot =>
      assert(find(pilot, "W").style == Style.Default)
      assert(find(pilot, "F").style.modifiers.hasAny(io.worxbend.tui.core.Modifiers.Reverse))
    }

  test("confirmDialog takes the body style and, while focused, the selected button's focused style"):
    driving(themed)(confirmDialog("Quit", "Really?", Seq("Yes", "No"), 0)(_ => (), _ => (), () => ()).autofocus) {
      pilot =>
        val body   = find(pilot, "R")
        assert(body.style.bg.contains(Color.Blue), body.toString)
        val button = find(pilot, "Y")
        assert(button.style.bg.contains(Color.Green), button.toString)
    }
