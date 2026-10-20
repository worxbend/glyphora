package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

final class DialogGeometryRegressionSpec extends AnyFunSuite with PilotFixture:
  test("BUGS-2 Screen.confirm ignores corner, message and border presses; buttons use pointer identity"):
    final class App extends TuiApp:
      var confirmed     = 0
      var canceled      = 0
      private val modal = Screen.confirm("Quit", "Discard?", "OK", "Cancel")(confirmed += 1, canceled += 1)
      override def bindings: KeyBindings            = KeyBindings(binding("o", "open")(pushScreen(modal)))
      def view(using ReactiveScope, Theme): Element = text("PAGE")
    val backend = HeadlessBackend(Size(40, 12))
    val app   = App()
    val pilot = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle().press("o").waitForIdle()
    pilot.click(1, 1).waitForIdle()
    assert(app.confirmed == 0)
    assert(app.canceled == 0)
    val top   = pilot.screenLines.indexWhere(_.contains("Quit"))
    val left  = pilot.screenLines(top).indexOf('╔')
    pilot.click(left, top).waitForIdle()
    val body  = pilot.screenLines.indexWhere(_.contains("Discard?"))
    pilot.click(pilot.screenLines(body).indexOf("Discard?"), body).waitForIdle()
    assert(app.confirmed == 0)
    val row   = pilot.screenLines.indexWhere(_.contains("[ OK ]"))
    pilot.click(pilot.screenLines(row).indexOf("Cancel"), row).waitForIdle()
    assert(app.canceled == 1)
    assert(app.confirmed == 0)
    pilot.click(pilot.screenLines(row).indexOf("OK"), row).waitForIdle()
    assert(app.confirmed == 1)

  test("BUGS-3 helpOverlay uses its centered painted box for outside dismissal"):
    val app     = new TuiApp:
      private val modal = Screen(helpOverlay(KeyBindings.empty, title = "HELP"), dismissal = Dismissal.ClickOutside)
      override def bindings: KeyBindings            = KeyBindings(binding("o", "open")(pushScreen(modal)))
      def view(using ReactiveScope, Theme): Element = text("PAGE")
    val backend = HeadlessBackend(Size(40, 12))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle().press("o").waitForIdle()
    assert(pilot.screenLines.exists(_.contains("HELP")))
    pilot.click(1, 1).waitForIdle()
    assert(!pilot.screenLines.exists(_.contains("HELP")))

  for controller <- Seq(false, true) do
    test(s"BUGS-2 BUGS-3 centered dialog outside dismissal, controller=$controller"):
      final class App extends TuiApp:
        var presses                                   = 0
        private def content: View                     =
          if controller then confirmDialog("DIALOG", "Message", Seq("OK"), 0)(_ => (), _ => presses += 1, () => ())
          else dialog("DIALOG", "Message")
        private val modal                             = Screen(content, dismissal = Dismissal.ClickOutside)
        override def bindings: KeyBindings            = KeyBindings(binding("o", "open")(pushScreen(modal)))
        def view(using ReactiveScope, Theme): Element = text("PAGE")
      val backend = HeadlessBackend(Size(40, 12))
      val app   = App()
      val pilot = startPilot(backend)(app.runWith(backend))
      pilot.waitForIdle().press("o").waitForIdle()
      val top   = pilot.screenLines.indexWhere(_.contains("DIALOG"))
      val left  = pilot.screenLines(top).indexOf('╔')
      assert(left >= 0)
      pilot.click(left, top).waitForIdle()
      assert(pilot.screenLines.exists(_.contains("DIALOG")))
      assert(app.presses == 0)
      pilot.click(left - 1, top).waitForIdle()
      assert(!pilot.screenLines.exists(_.contains("DIALOG")))
      assert(app.presses == 0)
