package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, Rect, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

final class SuspendSpec extends AnyFunSuite with PilotFixture:

  test("suspend hands the terminal back during the body and restores the app screen afterward"):
    val backend          = HeadlessBackend(Size(20, 4))
    var screenDuringBody = true
    val app              = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("e", "edit")(suspend { screenDuringBody = backend.isAlternateScreen }),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("editor host")
    val pilot            = startPilot(backend) { app.runWith(backend) }.waitForIdle()
    assert(backend.isAlternateScreen) // the app runs on the alternate screen
    pilot.typeText("e").waitForIdle()
    assert(backend.suspendCount == 1)
    assert(!screenDuringBody)         // during the body the terminal was handed back (primary screen)
    assert(backend.isAlternateScreen) // and the app screen was restored afterward
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("printAbove records durable lines above the app"):
    val backend = HeadlessBackend(Size(20, 4))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("l", "log")(printAbove("build ok", "deployed ✓")),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("app")
    val pilot   = startPilot(backend) { app.runWith(backend) }.waitForIdle()
    pilot.typeText("l").waitForIdle()
    assert(backend.printedAbove == Seq("build ok", "deployed ✓"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("insertBefore puts a styled block above the app, and its text among the printed lines"):
    // the point of the styled form: `printAbove` would have emitted the word "ERROR" and nothing else, because the
    // backend strips control sequences out of the text it is handed. Here the app draws, so the colour survives.
    val backend = HeadlessBackend(Size(20, 4))
    val level   = Style.Default.withFg(Color.Red).bold
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("l", "log")(insertBefore(1) { (area: Rect, buffer) =>
          buffer.setString(area.x, area.y, "ERROR", level)
          buffer.setString(area.x + 6, area.y, "disk full", Style.Default)
        }),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("app")
    val pilot   = startPilot(backend) { app.runWith(backend) }.waitForIdle()
    pilot.typeText("l").waitForIdle()

    val block = backend.insertedAbove.head
    assert(block.area == Rect(0, 0, 20, 1))
    assert(block.get(0, 0).style == level)         // the styling reached the scrollback
    assert(block.get(6, 0).style == Style.Default) // and stopped where the widget stopped applying it
    assert(backend.printedAbove == Seq("ERROR disk full"))
    assert(backend.suspendCount == 0)              // headless has no screen to leave; the live UI was not disturbed

    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a block of zero rows inserts nothing"):
    val backend = HeadlessBackend(Size(20, 4))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("l", "log")(insertBefore(0) { (area: Rect, buffer) =>
          buffer.setString(area.x, area.y, "never", Style.Default)
        }),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("app")
    val pilot   = startPilot(backend) { app.runWith(backend) }.waitForIdle()
    pilot.typeText("l").waitForIdle()
    assert(backend.insertedAbove.isEmpty)
    assert(backend.printedAbove.isEmpty)
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
