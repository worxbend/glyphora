package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** The application-chrome nodes in `ChromeElements`, driven through a running app. */
final class ChromeElementsPilotSpec extends AnyFunSuite with PilotFixture:

  private def startApp(size: Size)(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(size)
    val testApp = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("breadcrumb: a click on a visible item selects it; the fold and the separators select nothing"):
    var chosen = Vector.empty[Int]
    val trail  = Seq("Home", "Projects", "glyphora", "widgets", "Breadcrumb")
    val pilot  = startApp(Size(30, 3))(breadcrumb(trail, index => chosen :+= index))
    assert(pilot.screenLines.head.trim == "Home › … › Breadcrumb")
    pilot.click(1, 0).click(7, 0).click(12, 0).waitForIdle()
    assert(chosen == Vector(0, 4))
    quitApp(pilot)

  test("accordion: opening one section closes the other — a single-open group of collapsibles"):
    val state = AccordionState()
    val pilot = startApp(Size(30, 8)) {
      accordion(state)("General" -> text("name, email"), "Network" -> text("proxy"), "About" -> text("v1"))
    }
    assert(pilot.screenLines.take(3).map(_.trim) == Seq("▸ General", "▸ Network", "▸ About"))
    pilot.press("enter").waitForIdle()
    assert(state.open.contains(0))
    assert(pilot.screenText.contains("name, email"))
    pilot.press("tab", "enter").waitForIdle()
    assert(state.open.contains(1))
    assert(!pilot.screenText.contains("name, email"))
    assert(pilot.screenText.contains("proxy"))
    pilot.press("enter").waitForIdle()
    assert(state.open.isEmpty)
    quitApp(pilot)

  test("wizard: Next is blocked while the step fails validation, then walks on and Finish runs onFinish"):
    val state    = WizardState()
    var accepted = false
    var finished = 0
    val pilot    = startApp(Size(40, 8)) {
      wizard(state)(
        WizardStep("Account", text("account form"), () => Option.when(!accepted)("email is required")),
        WizardStep("Confirm", text("all good")),
      )(finished += 1)
    }
    assert(pilot.screenLines.head.stripTrailing == "● Account ─ ○ Confirm")
    pilot.press("enter").waitForIdle()
    assert(state.current == 0)
    assert(pilot.screenText.contains("email is required"))
    pilot.readOnRenderThread { accepted = true }
    pilot.press("enter").waitForIdle()
    assert(state.current == 1)
    assert(pilot.screenText.contains("all good") && !pilot.screenText.contains("email is required"))
    assert(pilot.screenText.contains("[ Finish ]"))
    pilot.press("enter").waitForIdle()
    assert(finished == 1)
    quitApp(pilot)
