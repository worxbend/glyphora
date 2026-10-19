package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend

import jdk.jfr.Recording
import jdk.jfr.consumer.{RecordedEvent, RecordingFile}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters.*

/** The Flight Recorder events a run emits, read back from a real `jdk.jfr.Recording`. */
final class JfrEventsSpec extends AnyFunSuite:

  private def recorded(names: String*)(body: => Unit): Vector[RecordedEvent] =
    val recording = Recording()
    names.foreach(name => recording.enable(name).withThreshold(java.time.Duration.ZERO))
    recording.start()
    try body
    finally recording.stop()
    val file      = Files.createTempFile("glyphora-jfr", ".jfr")
    try
      recording.dump(file)
      RecordingFile.readAllEvents(file).asScala.toVector.filter(event => names.contains(event.getEventType.getName))
    finally
      recording.close()
      Files.deleteIfExists(file)

  private def runFrames(backend: HeadlessBackend, draw: Long => Unit): Unit =
    val result = TerminalRunner(backend, RunnerConfig()).run(
      _ => (),
      (event, handle) =>
        event match
          case Event.EndOfInput => { handle.quit(); EventOutcome.Ignored }
          case _                => EventOutcome.Redraw
      ,
      frame => draw(frame.count),
    )
    assert(result == Right(()))

  test("a recorded run emits FrameDrawn events with a duration and one per composed frame"):
    val backend = HeadlessBackend(Size(6, 2))
    backend.postEvent(Event.Key(KeyEvent.of(KeyCode.Char('a'))))
    backend.postEvent(Event.EndOfInput)
    val events  = recorded(JfrEvents.FrameDrawnName) {
      runFrames(backend, _ => ())
    }
    val frames  = events.filter(_.getEventType.getName == JfrEvents.FrameDrawnName)
    assert(frames.nonEmpty)
    assert(frames.forall(!_.getDuration.isNegative))
    assert(frames.exists(_.getDuration.toNanos > 0L))
    assert(frames.map(_.getLong("frame")).sorted == Vector(0L, 1L))

  test("dirty cells count what changed since the previous frame"):
    val backend = HeadlessBackend(Size(6, 1))
    backend.postEvent(Event.Tick)
    backend.postEvent(Event.EndOfInput)
    val events  = recorded(JfrEvents.FrameDrawnName) {
      val result = TerminalRunner(backend, RunnerConfig()).run(
        _ => (),
        (event, handle) =>
          event match
            case Event.EndOfInput => { handle.quit(); EventOutcome.Ignored }
            case _                => EventOutcome.Redraw
        ,
        frame =>
          val text = if frame.count == 0L then "abc" else "abd"
          text.zipWithIndex.foreach((c, x) => frame.buffer.setString(x, 0, c.toString, Style.Default)),
      )
      assert(result == Right(()))
    }
    val dirty   = events.sortBy(_.getLong("frame")).map(_.getInt("dirtyCells"))
    assert(dirty == Vector(3, 1))

  test("every dispatched event is recorded as EventRouted with its kind and whether it repainted"):
    val backend = HeadlessBackend(Size(4, 1))
    backend.postEvent(Event.Key(KeyEvent.of(KeyCode.Char('x'))))
    backend.postEvent(Event.EndOfInput)
    val events  = recorded(JfrEvents.EventRoutedName)(runFrames(backend, _ => ()))
    val routed  = events.map(event => (event.getString("kind"), event.getBoolean("repaint")))
    assert(routed.contains(("Key", true)))
    assert(routed.contains(("EndOfInput", false)))

  test("async work records its admission"):
    val events = recorded(JfrEvents.AsyncAdmittedName) {
      val done = CountDownLatch(1)
      Async.runCatching(())(_ => ())
      Async.onWorker(done.countDown())
      assert(done.await(5, TimeUnit.SECONDS))
    }
    assert(events.exists(_.getString("queue") == "worker"))
