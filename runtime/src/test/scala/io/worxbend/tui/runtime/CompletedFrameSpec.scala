package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, Rect, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.{BufferAssertions, Pilot}

import org.scalatest.funsuite.AnyFunSuite

/** Covers [[RunnerConfig.onFrame]] and [[CompletedFrame]]: what a production app is handed after each flushed frame. */
final class CompletedFrameSpec extends AnyFunSuite:

  private def quitOnQ(event: Event, handle: RunnerHandle): EventOutcome =
    event match
      case Event.Key(KeyEvent(KeyCode.Char('q'), _)) =>
        handle.quit()
        EventOutcome.Ignored
      case _                                         => EventOutcome.Redraw

  /** Draws `text` at the top-left of the frame. */
  private def drawing(text: () => String)(frame: Frame): Unit =
    frame.renderWidget((area, buffer) => buffer.setString(area.x, area.y, text(), Style.Default), frame.area)

  test("the observer sees one frame per flush, numbered from zero"):
    val backend  = HeadlessBackend(Size(20, 3))
    val observed = scala.collection.mutable.ArrayBuffer.empty[Long]
    val pilot    = Pilot.start(backend) {
      TerminalRunner(
        backend,
        RunnerConfig(onFrame = Some(frame => observed.synchronized { val _ = observed += frame.count })),
      ).run(_ => (), quitOnQ, drawing(() => "hi"))
    }
    try
      pilot.waitForIdle()
      pilot.pressKey(KeyCode.Char('a')).pressKey(KeyCode.Char('b')).waitForIdle()
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
      val counts = observed.synchronized(observed.toSeq)
      assert(counts.length >= 3)
      assert(counts == counts.indices.map(_.toLong))
      assert(counts.length == backend.drawCount)
    finally pilot.close()

  test("the reported area follows the terminal, including across a resize"):
    val backend                                = HeadlessBackend(Size(20, 3))
    @volatile var last: Option[CompletedFrame] = None
    val pilot                                  = Pilot.start(backend) {
      TerminalRunner(backend, RunnerConfig(onFrame = Some(frame => last = Some(frame))))
        .run(_ => (), quitOnQ, drawing(() => "hi"))
    }
    try
      pilot.waitForIdle()
      assert(last.map(_.area).contains(Rect(0, 0, 20, 3)))
      pilot.resize(30, 6).waitForIdle()
      assert(last.map(_.area).contains(Rect(0, 0, 30, 6)))
      assert(last.map(_.buffer.area) == last.map(_.area))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("the text of a completed frame is what the widgets drew"):
    val backend                                = HeadlessBackend(Size(12, 2))
    @volatile var last: Option[CompletedFrame] = None
    val pilot                                  = Pilot.start(backend) {
      TerminalRunner(backend, RunnerConfig(onFrame = Some(frame => last = Some(frame))))
        .run(_ => (), quitOnQ, drawing(() => "héllo"))
    }
    try
      pilot.waitForIdle()
      val frame = last.getOrElse(fail("no frame was observed"))
      assert(frame.lines.head == "héllo")
      // Pinned against the assertion helper tests already trust, so the two spellings of "a buffer as text" cannot drift.
      assert(frame.lines == BufferAssertions.trimmedLines(frame.buffer))
      assert(frame.text == BufferAssertions.text(frame.buffer))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("wide graphemes occupy one entry of the text, not one per column"):
    val backend                                = HeadlessBackend(Size(12, 1))
    @volatile var last: Option[CompletedFrame] = None
    val pilot                                  = Pilot.start(backend) {
      // A CJK ideograph is one character across two columns; the family emoji is many code points joined by
      // zero-width joiners, also across two columns. Neither may be repeated per column in the text.
      TerminalRunner(backend, RunnerConfig(onFrame = Some(frame => last = Some(frame))))
        .run(_ => (), quitOnQ, drawing(() => "世界👨‍👩‍👧"))
    }
    try
      pilot.waitForIdle()
      assert(last.map(_.lines.head).contains("世界👨‍👩‍👧"))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("a retained frame keeps its own content when a later frame draws something else"):
    // This is the regression test for handing out the composer's reused live buffer instead of a snapshot.
    val backend                                 = HeadlessBackend(Size(12, 1))
    @volatile var first: Option[CompletedFrame] = None
    @volatile var shown                         = "one"
    val pilot                                   = Pilot.start(backend) {
      TerminalRunner(
        backend,
        RunnerConfig(onFrame = Some(frame => if first.isEmpty then first = Some(frame))),
      ).run(_ => (), quitOnQ, drawing(() => shown))
    }
    try
      pilot.waitForIdle()
      shown = "two"
      pilot.pressKey(KeyCode.Char('x')).waitForIdle()
      assert(pilot.screenLines.head == "two")
      assert(first.map(_.lines.head).contains("one"))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("a run with no observer behaves exactly as before"):
    val backend = HeadlessBackend(Size(12, 1))
    val pilot   = Pilot.start(backend)(TerminalRunner(backend).run(_ => (), quitOnQ, drawing(() => "plain")))
    try
      pilot.waitForIdle()
      assert(pilot.screenLines.head == "plain")
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
    finally pilot.close()

  test("a throwing observer ends the run as a handler failure with the terminal restored"):
    val backend = HeadlessBackend(Size(12, 1))
    val result  = TerminalRunner(
      backend,
      RunnerConfig(onFrame = Some(_ => throw IllegalStateException("boom"))),
    ).run(_ => (), quitOnQ, drawing(() => "hi"))
    assert(result match
      case Left(RunnerError.Handler(error)) => error.getMessage == "boom"
      case _                                => false)
    assert(!backend.isRawMode)
    assert(!backend.isAlternateScreen)
