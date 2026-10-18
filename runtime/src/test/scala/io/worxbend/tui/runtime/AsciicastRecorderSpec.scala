package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Buffer, Rect, Style}

import org.scalatest.funsuite.AnyFunSuite

import java.io.StringWriter

/** The asciicast v2 writer on its own, fed frames by hand with a hand-turned clock: a header, then one output event per
  * frame that changed something, timestamps counted from the first frame.
  */
final class AsciicastRecorderSpec extends AnyFunSuite:

  private val Event = """^\[(\d+\.\d{6}),"o","(.*)"\]$""".r

  private def frame(count: Long, text: String): CompletedFrame =
    val area   = Rect(0, 0, 6, 2)
    val buffer = Buffer(area)
    buffer.setString(0, 0, text, Style.Default)
    CompletedFrame(buffer, area, count)

  test("three changing frames give a v2 header and three events with non-decreasing timestamps"):
    val out      = StringWriter()
    var now      = 1_000_000_000L
    val recorder = AsciicastRecorder(out, () => now, () => 1_700_000_000L)
    recorder(frame(0, "one"))
    now += 500_000_000L
    recorder(frame(1, "two"))
    now += 250_000_000L
    recorder(frame(2, "three"))
    recorder.close()
    val lines    = out.toString.split("\n").toSeq
    assert(lines.length == 4)
    assert(lines.head.startsWith("""{"version":2,"width":6,"height":2,"timestamp":1700000000"""))
    val events   = lines.tail.map { case Event(time, data) => (time.toDouble, data) }
    assert(events.map(_._1) == Seq(0.0, 0.5, 0.75))
    assert(events.head._2.startsWith("\\u001b[H\\u001b[2J\\u001b[?25l"), "the first frame clears and hides the cursor")
    assert(events.head._2.contains("one"))
    assert(events(1)._2.contains("two") && !events(1)._2.contains("\\u001b[2J"), "later frames are diffs")
    assert(!out.toString.contains("\u001b"), "control characters are JSON-escaped, never written raw")

  test("a frame that changes nothing writes no event, and nothing is written after close"):
    val out      = StringWriter()
    val recorder = AsciicastRecorder(out, () => 0L, () => 0L)
    recorder(frame(0, "same"))
    recorder(frame(1, "same"))
    recorder.close()
    recorder(frame(2, "after"))
    assert(out.toString.split("\n").length == 2)

  test("a resize restarts from a full repaint under a new size, and the data escapes quotes and backslashes"):
    val out      = StringWriter()
    val recorder = AsciicastRecorder(out, () => 0L, () => 0L)
    recorder(frame(0, "a\"b\\c"))
    val wider    = Buffer(Rect(0, 0, 8, 2))
    wider.setString(0, 0, "resized", Style.Default)
    recorder(CompletedFrame(wider, wider.area, 1))
    recorder.close()
    val lines    = out.toString.split("\n").toSeq
    assert(lines(1).contains("""a\"b\\c"""))
    assert(lines(2).contains("\\u001b[2J"), "a changed area is a full repaint")
