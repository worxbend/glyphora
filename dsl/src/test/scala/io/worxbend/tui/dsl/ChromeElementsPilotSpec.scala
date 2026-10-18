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
