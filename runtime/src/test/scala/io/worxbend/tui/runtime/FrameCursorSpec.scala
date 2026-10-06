package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Buffer, Event, KeyCode, KeyEvent, KeyModifiers, Position, Size}
import io.worxbend.tui.terminal.{Backend, BackendError, HeadlessBackend}
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.Duration

/** Covers the hardware caret: a frame declares where the terminal's own cursor belongs and the runner honours it after
  * the flush.
  *
  * The two properties worth pinning are that the declaration is per frame — stop declaring it and the cursor goes back
  * into hiding — and that each cursor-moving draw restores position while visibility is deduplicated.
  */
final class FrameCursorSpec extends AnyFunSuite:

  private def quitOnQ(event: Event, handle: RunnerHandle): EventOutcome =
    event match
      case Event.Key(KeyEvent(KeyCode.Char('q'), _)) =>
        handle.quit()
        EventOutcome.Ignored
      case _                                         => EventOutcome.Redraw

  test("a frame that declares a cursor position leaves the terminal cursor there and visible"):
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = Pilot.start(backend) {
      TerminalRunner(backend).run(
        _ => (),
        quitOnQ,
        frame =>
          frame.renderWidget((_, _) => (), frame.area)
          frame.setCursorPosition(Position(4, 2)),
      )
    }
    try
      pilot.waitForIdle()
      assert(backend.cursorPosition.contains(Position(4, 2)))
      assert(backend.isCursorVisible)
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("a frame that declares nothing leaves the cursor hidden"):
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = Pilot.start(backend) {
      TerminalRunner(backend).run(_ => (), quitOnQ, frame => frame.renderWidget((_, _) => (), frame.area))
    }
    try
      pilot.waitForIdle()
      assert(backend.cursorPosition.isEmpty)
      assert(!backend.isCursorVisible)
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("withdrawing the declaration hides the cursor again on the next frame"):
    val backend       = HeadlessBackend(Size(20, 3))
    @volatile var own = true
    val pilot         = Pilot.start(backend) {
      TerminalRunner(backend).run(
        _ => (),
        quitOnQ,
        frame =>
          frame.renderWidget((_, _) => (), frame.area)
          if own then frame.setCursorPosition(Position(1, 1)),
      )
    }
    try
      pilot.waitForIdle()
      assert(backend.isCursorVisible)
      own = false
      pilot.pressKey(KeyCode.Char('x')).waitForIdle()
      assert(!backend.isCursorVisible)
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("the last declaration in a frame wins"):
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = Pilot.start(backend) {
      TerminalRunner(backend).run(
        _ => (),
        quitOnQ,
        frame =>
          frame.setCursorPosition(Position(1, 1))
          frame.setCursorPosition(Position(7, 0)),
      )
    }
    try
      pilot.waitForIdle()
      assert(backend.cursorPosition.contains(Position(7, 0)))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("clearCursorPosition withdraws a position declared earlier in the same frame"):
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = Pilot.start(backend) {
      TerminalRunner(backend).run(
        _ => (),
        quitOnQ,
        frame =>
          frame.setCursorPosition(Position(1, 1))
          frame.clearCursorPosition(),
      )
    }
    try
      pilot.waitForIdle()
      assert(backend.cursorPosition.isEmpty)
      assert(!backend.isCursorVisible)
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("an unchanged declaration restores draw-moved position without repeating visibility"):
    // Driven synchronously rather than through `Pilot`: the events are queued before the loop starts and the last of
    // them quits, so the run is deterministic and the counted writes belong to a known number of frames.
    val inner   = HeadlessBackend(Size(20, 3))
    val backend = CountingBackend(inner)
    inner.postEvent(Event.Key(KeyEvent(KeyCode.Char('x'), KeyModifiers.None)))
    inner.postEvent(Event.Key(KeyEvent(KeyCode.Char('y'), KeyModifiers.None)))
    inner.postEvent(Event.Key(KeyEvent(KeyCode.Char('q'), KeyModifiers.None)))
    val result  = TerminalRunner(backend).run(
      _ => (),
      quitOnQ,
      frame =>
        frame.renderWidget((_, _) => (), frame.area)
        frame.setCursorPosition(Position(2, 1)),
    )
    assert(result == Right(()))
    assert(inner.drawCount >= 3, "an initial frame plus one per redrawing key press")
    assert(backend.cursorWrites == 3, "each draw can move the cursor and must restore the declared caret")
    assert(backend.positionsAtRead == List.fill(3)(Some(Position(2, 1))))
    assert(backend.cursorShows == 1, "visibility is deduplicated independently of position")

/** A [[Backend]] that forwards everything to a [[HeadlessBackend]] and counts the cursor moves on the way through.
  *
  * `HeadlessBackend` records the latest cursor position but not how often it was written, and "how often" is exactly
  * what the de-duplication in the frame composer is about. It is `final`, so this counts by delegation rather than by
  * subclassing; nothing outside this suite needs the number.
  */
private final class CountingBackend(inner: HeadlessBackend) extends Backend:

  private var moves                             = 0
  private var shows                             = 0
  private var positions: List[Option[Position]] = Nil

  def positionsAtRead: List[Option[Position]] = positions.reverse
  def cursorShows: Int                        = shows

  /** How many times a frame moved the physical cursor since this backend was created. */
  def cursorWrites: Int = moves

  override def setCursorPosition(position: Position): Either[BackendError, Unit] =
    moves += 1
    inner.setCursorPosition(position)

  def size: Either[BackendError, Size]                                  = inner.size
  def draw(buffer: Buffer): Either[BackendError, Unit]                  =
    inner.draw(buffer).flatMap(_ => inner.setCursorPosition(Position(19, 2)))
  def enableRawMode(): Either[BackendError, Unit]                       = inner.enableRawMode()
  def disableRawMode(): Either[BackendError, Unit]                      = inner.disableRawMode()
  def enterAlternateScreen(): Either[BackendError, Unit]                = inner.enterAlternateScreen()
  def leaveAlternateScreen(): Either[BackendError, Unit]                = inner.leaveAlternateScreen()
  def enableMouseCapture(): Either[BackendError, Unit]                  = inner.enableMouseCapture()
  def disableMouseCapture(): Either[BackendError, Unit]                 = inner.disableMouseCapture()
  def hideCursor(): Either[BackendError, Unit]                          = inner.hideCursor()
  def showCursor(): Either[BackendError, Unit]                          =
    shows += 1
    inner.showCursor()
  def readEvent(timeout: Duration): Either[BackendError, Option[Event]] =
    positions = inner.cursorPosition :: positions
    inner.readEvent(timeout)
  def close(): Either[BackendError, Unit]                               = inner.close()
