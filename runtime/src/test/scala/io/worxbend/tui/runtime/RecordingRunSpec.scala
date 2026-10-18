package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}

/** `RunnerConfig.withRecording` and `-Dglyphora.record` through a whole headless run: every flushed frame reaches the
  * `.cast` file, the user's own `onFrame` still runs, and the file is complete once `run` has returned.
  */
final class RecordingRunSpec extends AnyFunSuite:

  /** Runs an app that paints the last typed letter, types `a` and `b` (three frames), quits, and returns the text of
    * the cast file `configure` is handed together with the frame numbers the user's own `onFrame` saw.
    */
  private def record(configure: Path => RunnerConfig): (String, Seq[Long]) =
    val cast     = Files.createTempFile("glyphora-record", ".cast")
    val observed = scala.collection.mutable.ArrayBuffer.empty[Long]
    try
      val backend = HeadlessBackend(Size(10, 2))
      var letter  = "-"
      val config  = configure(cast).withOnFrame(frame => observed.synchronized { val _ = observed += frame.count })
      val pilot   = Pilot.start(backend) {
        TerminalRunner(backend, config)
          .run(
            _ => (),
            {
              case (Event.Key(KeyEvent(KeyCode.Char('q'), _)), handle) =>
                handle.quit()
                EventOutcome.Ignored
              case (Event.Key(KeyEvent(KeyCode.Char(c), _)), _)        =>
                letter = Character.toString(c)
                EventOutcome.Redraw
              case _                                                   => EventOutcome.Ignored
            },
            frame =>
              frame.renderWidget((area, buffer) => buffer.setString(area.x, area.y, letter, Style.Default), frame.area),
          )
      }
      try
        pilot.waitForIdle()
        pilot.press("a").waitForIdle().press("b").waitForIdle().press("q")
        assert(pilot.awaitTermination())
      finally pilot.close()
      (Files.readString(cast), observed.synchronized(observed.toSeq))
    finally
      val _ = Files.deleteIfExists(cast)

  test("withRecording writes a header and one event per flushed frame while the user's onFrame still runs"):
    val (cast, observed) = record(path => RunnerConfig.Default.withRecording(path))
    val lines            = cast.split("\n").toSeq
    assert(lines.head.startsWith("""{"version":2,"width":10,"height":2,"""))
    assert(lines.tail.length == 3, s"expected three output events, got:\n$cast")
    assert(lines.tail.forall(_.startsWith("[")))
    assert(observed == Seq(0L, 1L, 2L))

  test("-Dglyphora.record names the file when the configuration does not"):
    val cast = Files.createTempFile("glyphora-record-property", ".cast")
    System.setProperty(AsciicastRecorder.SystemProperty, cast.toString)
    try
      val (unconfigured, _) = record(_ => RunnerConfig.Default)
      assert(unconfigured.isEmpty, "the file the configuration did not name stays untouched")
      val lines             = Files.readString(cast).split("\n").toSeq
      assert(lines.length == 4, s"expected a header and three events in the property-named file, got:\n$lines")
    finally
      System.clearProperty(AsciicastRecorder.SystemProperty)
      val _ = Files.deleteIfExists(cast)

  test("the configuration wins over -Dglyphora.record when both name a file"):
    val ignored = Files.createTempFile("glyphora-record-ignored", ".cast")
    System.setProperty(AsciicastRecorder.SystemProperty, ignored.toString)
    try
      val (cast, _) = record(path => RunnerConfig.Default.withRecording(path))
      assert(cast.split("\n").length == 4)
      assert(Files.readString(ignored).isEmpty)
    finally
      System.clearProperty(AsciicastRecorder.SystemProperty)
      val _ = Files.deleteIfExists(ignored)

  test("a recording path that cannot be opened fails the run before the terminal is touched"):
    val backend = HeadlessBackend(Size(10, 2))
    val result  = TerminalRunner(backend, RunnerConfig.Default.withRecording(Path.of("/nonexistent/dir/x.cast")))
      .run(_.quit(), (_, _) => EventOutcome.Ignored, _ => ())
    result match
      case Left(RunnerError.Handler(_: java.io.IOException)) => ()
      case other => fail(s"expected a handler failure carrying the I/O error, got $other")
    assert(!backend.isRawMode && !backend.isAlternateScreen)
    assert(backend.drawCount == 0L)
