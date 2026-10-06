package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Covers [[Frame.count]]: the sequence number every composed frame carries.
  *
  * The interesting property is not that a counter counts, it is *where* the counter lives. It belongs to the frame
  * composer's whole lifetime, not to the buffer the composer happens to be reusing — so a resize, which throws the
  * buffer away and allocates a new one, must not restart the numbering.
  */
final class FrameCountSpec extends AnyFunSuite:

  private def quitOnQ(event: Event, handle: RunnerHandle): EventOutcome =
    event match
      case Event.Key(KeyEvent(KeyCode.Char('q'), _)) =>
        handle.quit()
        EventOutcome.Ignored
      case _                                         => EventOutcome.Redraw

  /** Runs an app that records the `count` of every frame it composes, and hands the recorded numbers back. */
  private def recordCounts(backend: HeadlessBackend)(drive: Pilot => Unit): Seq[Long] =
    val counts = scala.collection.mutable.ArrayBuffer.empty[Long]
    val pilot  = Pilot.start(backend) {
      TerminalRunner(backend).run(
        _ => (),
        quitOnQ,
        frame =>
          counts.synchronized { val _ = counts += frame.count }
          frame.renderWidget((_, _) => (), frame.area),
      )
    }
    try
      pilot.waitForIdle()
      drive(pilot)
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
      counts.synchronized(counts.toSeq)
    finally pilot.close()

  test("the first composed frame of a run is numbered zero"):
    val counts = recordCounts(HeadlessBackend(Size(20, 3)))(_ => ())
    assert(counts.headOption.contains(0L))

  test("each composed frame gets the next number"):
    val counts = recordCounts(HeadlessBackend(Size(20, 3))) { pilot =>
      pilot.pressKey(KeyCode.Char('a')).pressKey(KeyCode.Char('b')).waitForIdle()
    }
    assert(counts.length >= 3)
    assert(counts == counts.indices.map(_.toLong))

  test("a resize does not restart the numbering"):
    // A resize makes the composer drop its cached buffer and allocate a new one. If the counter ever moved next to the
    // buffer's lifetime, the numbers would jump back to 0 here.
    val counts = recordCounts(HeadlessBackend(Size(20, 3))) { pilot =>
      pilot.resize(30, 6).waitForIdle()
    }
    assert(counts.length >= 2)
    assert(counts == counts.indices.map(_.toLong))

  test("the last frame's number is one below the backend's draw count"):
    val backend = HeadlessBackend(Size(20, 3))
    val counts  = recordCounts(backend)(pilot => pilot.pressKey(KeyCode.Char('a')).waitForIdle())
    assert(counts.lastOption.contains(backend.drawCount - 1))
