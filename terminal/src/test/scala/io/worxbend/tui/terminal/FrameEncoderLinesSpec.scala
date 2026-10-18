package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, Color, Rect, Style}

import org.scalatest.funsuite.AnyFunSuite

/** Covers [[FrameEncoder.encodeLines]] and [[FrameEncoder.plainLines]]: the two whole-frame encodings print mode is
  * built on. A printed frame is not a screen update — the terminal, not the encoder, decides which line it lands on —
  * so these are pinned apart from the diff encoder: no cursor movement, one row per line, and nothing at all written
  * for a style that renders identically to the default at the given depth.
  */
final class FrameEncoderLinesSpec extends AnyFunSuite:

  private val Esc = "\u001b["

  private def frame(width: Int, height: Int)(write: Buffer => Unit): Buffer =
    val buffer = Buffer(Rect(0, 0, width, height))
    write(buffer)
    buffer

  test("rows are newline-separated and carry no cursor movement"):
    val buffer = frame(6, 2) { b =>
      b.setString(0, 0, "ab", Style.Default)
      b.setString(0, 1, "cd", Style.Default)
    }
    assert(FrameEncoder.encodeLines(buffer, ColorDepth.TrueColor) == "ab\ncd")

  test("a styled run opens its SGR and the row ends with a reset"):
    val cyan   = Style.Default.withFg(Color.Cyan)
    val buffer = frame(6, 1)(_.setString(0, 0, "ok", cyan))
    val text   = FrameEncoder.encodeLines(buffer, ColorDepth.TrueColor)
    assert(text.startsWith(Esc), text)
    assert(text.contains("ok"))
    assert(text.endsWith(AnsiSequences.ResetStyle), text)
    // one SGR to open the run, one reset to close the row — nothing per cell
    assert(text.sliding(2).count(_ == Esc) == 2, text)

  test("under NoColor a frame whose styles are colour-only contains no escape sequence at all"):
    val buffer = frame(6, 1)(_.setString(0, 0, "ok", Style.Default.withFg(Color.Cyan).withBg(Color.Indexed(236))))
    val text   = FrameEncoder.encodeLines(buffer, ColorDepth.NoColor)
    assert(text == "ok", text)

  test("under NoColor text attributes are still written"):
    val buffer = frame(6, 1)(_.setString(0, 0, "ok", Style.Default.bold))
    val text   = FrameEncoder.encodeLines(buffer, ColorDepth.NoColor)
    assert(text.contains(Esc), text)
    assert(text.endsWith(AnsiSequences.ResetStyle))

  test("trailing blank cells in the default style are dropped, a coloured background is kept"):
    val buffer = frame(6, 2) { b =>
      b.setString(0, 0, "a", Style.Default)
      b.setString(0, 1, "b  ", Style.Default.withBg(Color.Red))
    }
    val rows   = FrameEncoder.encodeLines(buffer, ColorDepth.TrueColor).split("\n", -1).toSeq
    assert(rows.head == "a")
    // the three background-painted cells survive, the three default blanks after them do not
    assert(rows(1).count(_ == ' ') == 2, rows(1))

  test("a wide grapheme is written once, not once per column"):
    val buffer = frame(6, 1)(_.setString(0, 0, "漢x", Style.Default))
    assert(FrameEncoder.encodeLines(buffer, ColorDepth.TrueColor) == "漢x")

  test("plainLines carries glyphs only, trailing blanks trimmed"):
    val buffer = frame(8, 2) { b =>
      b.setString(0, 0, "ab", Style.Default.withFg(Color.Cyan).bold)
      b.setString(0, 1, "漢 c", Style.Default)
    }
    assert(FrameEncoder.plainLines(buffer) == Seq("ab", "漢 c"))
