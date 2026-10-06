package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, Rect, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.{BufferAssertions, Pilot}

import org.scalatest.funsuite.AnyFunSuite

/** Covers an inline run end to end: the terminal is dressed differently, and the frame is composed into the bottom rows
  * instead of the whole screen.
  */
final class TerminalRunnerInlineSpec extends AnyFunSuite:

  private def quitOnQ(event: Event, handle: RunnerHandle): EventOutcome =
    event match
      case Event.Key(KeyEvent(KeyCode.Char('q'), _)) =>
        handle.quit()
        EventOutcome.Ignored
      case _                                         => EventOutcome.Redraw

  /** Fills every row of the frame's own area with `mark`, so a composed area that quietly grew back to the whole screen
    * shows up as content on rows the strip does not own.
    */
  private def fillFrame(mark: String)(frame: Frame): Unit =
    frame.renderWidget(
      (area, buffer) =>
        for y <- area.y until area.bottom do buffer.setString(area.x, y, mark * area.width, Style.Default),
      frame.area,
    )

  private def inlineRunner(backend: HeadlessBackend, rows: Int)(render: Frame => Unit): Pilot =
    Pilot.start(backend) {
      TerminalRunner(backend, RunnerConfig(viewport = Viewport.Inline(rows))).run(_ => (), quitOnQ, render)
    }

  test("an inline run stays on the primary screen and reserves its rows there"):
    val backend = HeadlessBackend(Size(10, 6))
    val pilot   = inlineRunner(backend, 2)(fillFrame("#"))
    try
      pilot.waitForIdle()
      assert(!backend.isAlternateScreen, "an inline app must not take the alternate screen")
      assert(backend.reservedInlineRows == 2)
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("the frame is composed into the bottom rows and nothing above them is touched"):
    val backend = HeadlessBackend(Size(4, 5))
    val pilot   = inlineRunner(backend, 2)(fillFrame("#"))
    try
      pilot.waitForIdle()
      val frame = pilot.lastFrame
      assert(frame.area.height == 2)
      assert(frame.area.y == 3)
      assert(BufferAssertions.line(frame, 3) == "####")
      assert(BufferAssertions.line(frame, 4) == "####")
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("the strip re-anchors to the bottom when the terminal is resized"):
    val backend                         = HeadlessBackend(Size(4, 5))
    @volatile var composed: Option[Int] = None
    val pilot                           = inlineRunner(backend, 2) { frame =>
      composed = Some(frame.area.y)
      fillFrame("#")(frame)
    }
    try
      pilot.waitForIdle()
      assert(composed.contains(3))
      pilot.resize(4, 9).waitForIdle()
      assert(composed.contains(7))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("a terminal shorter than the strip shrinks the composed area instead of failing"):
    val backend                         = HeadlessBackend(Size(4, 6))
    @volatile var composed: Option[Int] = None
    val pilot                           = inlineRunner(backend, 4) { frame =>
      composed = Some(frame.area.height)
      fillFrame("#")(frame)
    }
    try
      pilot.waitForIdle()
      assert(composed.contains(4))
      pilot.resize(4, 2).waitForIdle()
      assert(composed.contains(2))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("a full-screen run is unchanged: the alternate screen and the whole area"):
    // The regression guard for the default path, which is what every existing app uses.
    val backend = HeadlessBackend(Size(4, 3))
    val pilot   = Pilot.start(backend)(TerminalRunner(backend).run(_ => (), quitOnQ, fillFrame("#")))
    try
      pilot.waitForIdle()
      assert(backend.isAlternateScreen)
      assert(backend.reservedInlineRows == 0)
      assert(pilot.lastFrame.area == Rect(0, 0, 4, 3))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()
