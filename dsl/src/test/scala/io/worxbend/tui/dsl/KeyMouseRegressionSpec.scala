package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.ManualClock

import scala.concurrent.duration.DurationInt

import org.scalatest.funsuite.AnyFunSuite

/** Regressions for key/mouse policy at the running application's public boundary. */
final class KeyMouseRegressionSpec extends AnyFunSuite with PilotFixture:

  test("copy without a selection is consumed, while plain Ctrl+C still quits"):
    val backend = HeadlessBackend(Size(20, 4))
    val app     = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig.Default.withMouseCapture
      def view(using ReactiveScope, Theme): Element = text("hello")
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl | KeyModifiers.Shift).waitForIdle()
    assert(pilot.readOnRenderThread(42) == 42, "copy must leave the event loop alive")
    pilot.press("ctrl+c")
    assert(pilot.awaitTermination())

  test("an expired prefix is discarded at the next key, without a tick"):
    val backend = HeadlessBackend(Size(20, 4))
    val clock   = ManualClock()
    var tops    = 0
    val app     = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(tickRate = Some(5.seconds))
      override def bindings: KeyBindings            = KeyBindings(binding("g g", "top")(tops += 1))
      def view(using ReactiveScope, Theme): Element = text("hello")
    val pilot   = startPilot(backend)(app.runWith(backend, clock.reading)).waitForIdle()
    pilot.press("g").waitForIdle()
    val _       = clock.advance(1500.millis)
    pilot.press("g").waitForIdle()
    assert(pilot.readOnRenderThread(tops) == 0)
    pilot.press("g").waitForIdle()
    assert(pilot.readOnRenderThread(tops) == 1)

  test("app arrow bindings win over focus-group navigation and Shift+arrow selection"):
    val backend = HeadlessBackend(Size(20, 4))
    var nexts   = 0
    var selects = 0
    val app     = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig.Default.withMouseCapture
      override def bindings: KeyBindings            = KeyBindings(
        binding(Seq("down", "j"), "next")(nexts += 1),
        binding("shift+right", "extend")(selects += 1),
      )
      def view(using ReactiveScope, Theme): Element =
        focusGroup(column(text("first").focusable.key("a").autofocus, text("second").focusable.key("b")))
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    pilot.press("down", "j", "shift+right").waitForIdle()
    assert(pilot.readOnRenderThread(nexts) == 2)
    assert(pilot.readOnRenderThread(selects) == 1)

  test("click counts use the run clock and restart after 400 ms, independent of dispatch speed"):
    val backend = HeadlessBackend(Size(20, 4))
    val clock   = ManualClock()
    var counts  = Vector.empty[Int]
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element = text("target").onMouse { ctx =>
        if ctx.event.kind == MouseEventKind.Down then counts :+= ctx.event.clickCount
        true
      }
    val pilot   = startPilot(backend)(app.runWith(backend, clock.reading)).waitForIdle()
    pilot.click(1, 0).waitForIdle()
    val _       = clock.advance(500.millis)
    pilot.click(1, 0).waitForIdle()
    pilot.click(1, 0).waitForIdle()
    val _       = clock.advance(400.millis)
    pilot.click(1, 0).waitForIdle()
    val _       = clock.advance(401.millis)
    pilot.click(1, 0).waitForIdle()
    assert(pilot.readOnRenderThread(counts) == Vector(1, 1, 2, 3, 1))
