package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

final class MenuSpec extends AnyFunSuite with PilotFixture:

  private val items = Seq(
    MenuEntry.Item("Open"),
    MenuEntry.Item("Save"),
    MenuEntry.Separator,
    MenuEntry.Item("Reload"),
  )

  private def startApp(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(Size(24, 8))
    val testApp = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("arrow keys move the highlight (skipping the separator) and Enter fires onSelect"):
    var chosen = -1
    val state  = MenuState()
    val pilot  = startApp(menu(items, state)(chosen = _))
    pilot.pressKey(KeyCode.Down).waitForIdle() // Open -> Save
    pilot.pressKey(KeyCode.Down).waitForIdle() // Save -> Reload (separator skipped)
    assert(state.selected.contains(3))
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(chosen == 3)
    quitApp(pilot)

  test("clicking a menu row selects and fires it"):
    var chosen = -1
    val state  = MenuState()
    val pilot  = startApp(menu(items, state)(chosen = _))
    // rows render inside the border: y=0 border, y=1 Open, y=2 Save
    pilot.click(3, 2).waitForIdle()
    assert(state.selected.contains(1))
    assert(chosen == 1)
    quitApp(pilot)

  test("clicking a separator row is inert"):
    var chosen = -1
    val state  = MenuState()
    val pilot  = startApp(menu(items, state)(chosen = _))
    pilot.click(3, 3).waitForIdle() // y=3 is the separator row
    assert(chosen == -1)
    quitApp(pilot)
