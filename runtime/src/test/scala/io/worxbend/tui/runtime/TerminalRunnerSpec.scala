package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Color, Event, KeyCode, KeyEvent, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

final class TerminalRunnerSpec extends AnyFunSuite:

  /** The immediate-mode hello-world render function — the same drawing the real example performs, asserted here on
    * `Buffer` contents instead of a live terminal.
    */
  private def helloWorldRender(frame: Frame): Unit =
    frame.renderWidget(
      (area, buffer) =>
        buffer.setString(area.x + 2, area.y + 1, "Hello from glyphora!", Style.Default.bold.withFg(Color.Cyan))
        buffer.setString(area.x + 2, area.y + 3, "Press 'q' to quit", Style.Default.dim)
      ,
      frame.area,
    )

  private def quitOnQ(event: Event, handle: RunnerHandle): EventOutcome =
    event match
      case Event.Key(KeyEvent(KeyCode.Char('q'), _)) =>
        handle.quit()
        EventOutcome.Ignored
      case _                                         => EventOutcome.Redraw

  test("the runner renders an initial frame before any event arrives"):
    val backend = HeadlessBackend(Size(30, 5))
    val pilot   = Pilot.start(backend)(TerminalRunner(backend).run(_ => (), quitOnQ, helloWorldRender))
    try
      pilot.waitForIdle()
      assert(pilot.screenLines(1) == "  Hello from glyphora!")
      assert(pilot.screenLines(3) == "  Press 'q' to quit")
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("the runner sets up and tears down the terminal around the loop"):
    val backend = HeadlessBackend(Size(30, 5))
    val pilot   = Pilot.start(backend)(TerminalRunner(backend).run(_ => (), quitOnQ, helloWorldRender))
    try
      pilot.waitForIdle()
      assert(backend.isRawMode)
      assert(backend.isAlternateScreen)
      assert(!backend.isCursorVisible)
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("an event handler answering Redraw triggers a repaint with updated state"):
    val backend = HeadlessBackend(Size(20, 3))
    var count   = 0
    val pilot   = Pilot.start(backend) {
      TerminalRunner(backend).run(
        _ => (),
        (event, handle) =>
          event match
            case Event.Key(KeyEvent(KeyCode.Char('q'), _)) =>
              handle.quit()
              EventOutcome.Ignored
            case Event.Key(KeyEvent(KeyCode.Char('+'), _)) =>
              count += 1
              EventOutcome.Redraw
            case _                                         => EventOutcome.Ignored
        ,
        frame =>
          frame.renderWidget(
            (area, buffer) => buffer.setString(area.x, area.y, s"count=$count", Style.Default),
            frame.area,
          ),
      )
    }
    try
      pilot.waitForIdle()
      assert(pilot.screenLines.head == "count=0")
      pilot.pressKey(KeyCode.Char('+')).pressKey(KeyCode.Char('+')).waitForIdle()
      assert(pilot.screenLines.head == "count=2")
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("a resize event recreates the frame at the new size"):
    val backend  = HeadlessBackend(Size(20, 3))
    var lastArea = Size(0, 0)
    val pilot    = Pilot.start(backend) {
      TerminalRunner(backend).run(
        _ => (),
        quitOnQ,
        frame =>
          lastArea = Size(frame.area.width, frame.area.height)
          frame.renderWidget((_, _) => (), frame.area),
      )
    }
    try
      pilot.waitForIdle()
      assert(lastArea == Size(20, 3))
      pilot.resize(40, 10).waitForIdle()
      assert(lastArea == Size(40, 10))
      assert(pilot.backend.lastDrawn.exists(_.area.width == 40))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("a configured tick rate delivers Tick events without input"):
    val backend         = HeadlessBackend(Size(10, 2))
    @volatile var ticks = 0
    val pilot           = Pilot.start(backend) {
      TerminalRunner(backend, RunnerConfig(tickRate = Some(10.millis))).run(
        _ => (),
        (event, handle) =>
          event match
            case Event.Tick =>
              ticks += 1
              if ticks >= 3 then handle.quit()
              EventOutcome.Redraw
            case _          => EventOutcome.Ignored
        ,
        frame => frame.renderWidget((_, _) => (), frame.area),
      )
    }
    try
      assert(pilot.awaitTermination(2.seconds))
      assert(ticks >= 3)
    finally pilot.close()

  test("the render thread is registered for the duration of the loop"):
    val backend                   = HeadlessBackend(Size(10, 2))
    @volatile var wasRenderThread = false
    val pilot                     = Pilot.start(backend) {
      TerminalRunner(backend).run(
        _ => (),
        (event, handle) =>
          wasRenderThread = RenderThread.isRenderThread
          quitOnQ(event, handle)
        ,
        frame => frame.renderWidget((_, _) => (), frame.area),
      )
    }
    try
      pilot.pressKey(KeyCode.Char('x')).waitForIdle()
      assert(wasRenderThread)
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("work queued via runLater triggers a redraw without any input event"):
    val backend           = HeadlessBackend(Size(20, 2))
    @volatile var message = "before"
    @volatile var dirty   = false
    val pilot             = Pilot.start(backend) {
      TerminalRunner(backend, redrawRequested = () => dirty).run(
        _ => (),
        quitOnQ,
        frame =>
          dirty = false
          frame.renderWidget(
            (area, buffer) => buffer.setString(area.x, area.y, message, io.worxbend.tui.core.Style.Default),
            frame.area,
          ),
      )
    }
    try
      pilot.waitForIdle()
      assert(pilot.screenLines.head.startsWith("before"))
      RenderThread.runLater {
        message = "after"
        dirty = true
      }
      pilot.waitUntil("the redrawn frame to land")(pilot.screenLines.headOption.exists(_.startsWith("after")))
      assert(pilot.screenLines.head.startsWith("after"))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()
