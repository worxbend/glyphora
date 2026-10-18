package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, CharWidth, Color, Line, Modifiers, Span, Style, Text}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class ParagraphSpec extends AnyFunSuite:

  test("plain text renders line by line"):
    val buffer = rendered(Paragraph(Text.raw("one\ntwo")), 5, 3)
    assert(trimmedLines(buffer) == Seq("one", "two", ""))

  test("lines are clipped at the area width without wrapping"):
    val buffer = rendered(Paragraph(Text.raw("abcdefgh")), 4, 1)
    assert(trimmedLines(buffer) == Seq("abcd"))

  test("wrapping breaks long lines at the area width"):
    val buffer = rendered(Paragraph(Text.raw("abcdef"), overflow = Overflow.Wrap), 4, 2)
    assert(trimmedLines(buffer) == Seq("abcd", "ef"))

  test("wrapping never splits a wide character"):
    val buffer = rendered(Paragraph(Text.raw("ab你cd"), overflow = Overflow.Wrap), 3, 2)
    assert(trimmedLines(buffer) == Seq("ab", "你c"))

  test("wrapping breaks between words rather than inside one"):
    val buffer = rendered(Paragraph(Text.raw("hello world"), overflow = Overflow.Wrap), 8, 2)
    assert(trimmedLines(buffer) == Seq("hello", "world"))

  test("the blanks at a break are dropped, not carried to the next row"):
    val buffer = rendered(Paragraph(Text.raw("aa   bb"), overflow = Overflow.Wrap), 4, 2)
    assert(buffer.get(0, 1).symbol == "b")

  test("indentation the caller wrote survives, because it is not a break point"):
    val buffer = rendered(Paragraph(Text.raw("  aa bb"), overflow = Overflow.Wrap), 5, 2)
    assert(trimmedLines(buffer) == Seq("  aa", "bb"))

  test("a word longer than the whole width is broken between grapheme clusters"):
    val buffer = rendered(Paragraph(Text.raw("ab superlong"), overflow = Overflow.Wrap), 4, 4)
    assert(trimmedLines(buffer) == Seq("ab", "supe", "rlon", "g"))

  test("no-break space holds two words together and zero width space offers a break"):
    val heldTogether = rendered(Paragraph(Text.raw("10 kg x"), overflow = Overflow.Wrap), 5, 2)
    assert(trimmedLines(heldTogether) == Seq("10 kg", "x"))
    // The zero width space draws nothing, so it stays inside the cluster it was absorbed into rather than costing a
    // column; what matters is that the row was broken there instead of after the fourth column.
    val zwsp         = "​"
    val breakable    = rendered(Paragraph(Text.raw(s"aaa${zwsp}bbb"), overflow = Overflow.Wrap), 4, 2)
    assert(trimmedLines(breakable) == Seq(s"aaa$zwsp", "bbb"))

  test("wrapping keeps a wide word whole when it fits on a row of its own"):
    val buffer = rendered(Paragraph(Text.raw("ab 你好"), overflow = Overflow.Wrap), 4, 2)
    assert(trimmedLines(buffer) == Seq("ab", "你好"))

  test("long discarded whitespace gaps preserve subsequent text and style"):
    val line = Line(Seq(Span.raw("aaaa"), Span.raw(" " * 10000), Span("你b", Style.fg(Color.Red))))
    for overflow <- Seq(Overflow.Wrap, Overflow.WrapTrimmed, Overflow.WrapPreserved) do
      val paragraph = Paragraph(Text(Seq(line)), overflow = overflow)
      val buffer    = rendered(paragraph, 4, 3)
      assert(trimmedLines(buffer) == Seq("aaaa", "你b", ""))
      assert(buffer.get(0, 1).style.fg.contains(Color.Red))
      assert(paragraph.heightAt(4).contains(2))

  test("early row emission preserves fitting gap styles in every wrapping mode"):
    val line = Line(Seq(Span.raw("a"), Span(" ", Style.bg(Color.Blue)), Span.raw("b c")))
    for overflow <- Seq(Overflow.Wrap, Overflow.WrapTrimmed, Overflow.WrapPreserved) do
      val buffer = rendered(Paragraph(Text(Seq(line)), overflow = overflow), 4, 2)
      assert(trimmedLines(buffer) == Seq("a b", if overflow == Overflow.WrapPreserved then " c" else "c"))
      assert(buffer.get(1, 0).style.bg.contains(Color.Blue))

  test("span styles survive a wrap onto the next row"):
    val line   = Line(Seq(Span.raw("aaa "), Span("bbb", Style.Default.withFg(Color.Red))))
    val buffer = rendered(Paragraph(Text(Seq(line)), overflow = Overflow.Wrap), 4, 2)
    assert(trimmedLines(buffer) == Seq("aaa", "bbb"))
    assert(buffer.get(0, 1).style.fg.contains(Color.Red))

  test("a blank source line still occupies one row when wrapping"):
    val text   = Text.raw("aaaa bbbb\n\ncc")
    assert(Paragraph(text, overflow = Overflow.Wrap).heightAt(4).contains(4))
    val buffer = rendered(Paragraph(text, overflow = Overflow.Wrap), 4, 4)
    assert(trimmedLines(buffer) == Seq("aaaa", "bbbb", "", "cc"))

  test("measurement counts exactly the rows the wrapped render draws"):
    val text      = Text.raw("the quick brown fox jumps over the lazy dog")
    val paragraph = Paragraph(text, overflow = Overflow.Wrap)
    (1 to 20).foreach { width =>
      val drawn = text.lines.flatMap(Paragraph.wrapLine(_, width)).size
      assert(paragraph.heightAt(width).contains(drawn), s"width $width")
    }

  test("counting rows and building them agree on every kind of awkward line"):
    // The counting walk keeps no text, so this is what stops it drifting away from the walk that builds the rows.
    val zwsp  = "​"
    val lines = Seq(
      Line.raw(""),
      Line.raw("     "),
      Line.raw("  indented prose that has to wrap somewhere"),
      Line.raw("supercalifragilisticexpialidocious"),
      Line.raw("10 kg of 你好 and 👍🏽 mixed together"),
      Line.raw(s"aaa${zwsp}bbb${zwsp}ccc"),
      Line(Seq(Span.raw("bold "), Span("red words here", Style.Default.withFg(Color.Red)))),
    )
    for line <- lines; width <- 1 to 12 do
      assert(
        Paragraph.wrappedRowCount(line, width) == Paragraph.wrapLine(line, width).size,
        s"'${line.spans.map(_.content).mkString}' at width $width",
      )

  test("an indented line wraps within the width instead of overflowing its first row"):
    // The indent is part of the first row, so it has to be counted against the width like any other column: with the
    // indent ignored the row came out one column too wide and the renderer clipped the last cluster away entirely.
    assert(Paragraph.wrapLine(Line.raw(" abcde"), 5).map(_.width) == Seq(5, 1))
    assert(Paragraph(Text.raw(" abcde"), overflow = Overflow.Wrap).heightAt(5).contains(2))
    val buffer = rendered(Paragraph(Text.raw(" abcde"), overflow = Overflow.Wrap), 5, 2)
    assert(trimmedLines(buffer) == Seq(" abcd", "e"))

  test("no wrapped row is ever wider than the width it was wrapped to"):
    // Losing text is invisible at the call site: the renderer clips an over-wide row, so an off-by-one here reads as a
    // missing character rather than as a broken wrap. This is the invariant that makes that impossible.
    val zwsp  = "\u200b"
    val lines = Seq(
      Line.raw(" abcde"),
      Line.raw("    deeply indented"),
      Line.raw("  你好 mixed 宽度 text"),
      Line.raw("        supercalifragilistic"),
      Line.raw(s"  aaa${zwsp}bbbbbbbb"),
    )
    val modes = Seq(WrapBlanks.KeepIndent, WrapBlanks.DropAll, WrapBlanks.KeepAll)
    for line <- lines; mode <- modes; width <- 1 to 12 do
      Paragraph.wrapLine(line, width, mode).foreach { row =>
        // The one documented exception: a single cluster wider than the whole width gets a row of its own and is
        // clipped by the renderer, because dropping it would shift every following row up by one.
        val clusters = CharWidth.graphemeClusters(row.spans.map(_.content).mkString).toVector
        val clipped  = clusters.sizeIs == 1 && CharWidth.of(clusters.head) > width
        assert(
          row.width <= width || clipped,
          s"'${line.spans.map(_.content).mkString}' at width $width in $mode: '$row'",
        )
      }

  test("widthAt reports the longest line, in columns rather than characters"):
    val paragraph = Paragraph(Text.raw("ab\n你好世界\nc"))
    assert(paragraph.widthAt(1).contains(8))  // four wide characters, two columns each
    assert(paragraph.widthAt(99).contains(8)) // the height is not consulted
    assert(Paragraph(Text.raw("")).widthAt(1).contains(0))
    assert(Paragraph(Text(Seq.empty)).widthAt(1).contains(0))

  test("the width widthAt reports is one at which nothing is clipped or wrapped"):
    val text      = Text.raw("hello wide 你好 world")
    val natural   = Paragraph(text).widthAt(1).getOrElse(0)
    val paragraph = Paragraph(text, overflow = Overflow.Wrap)
    assert(paragraph.heightAt(natural).contains(text.lines.size))
    assert(trimmedLines(rendered(paragraph, natural, 1)) == Seq("hello wide 你好 world"))

  test("center alignment offsets each line by its own width"):
    val buffer = rendered(Paragraph(Text.raw("ab\nabcd"), alignment = Alignment.Center), 6, 2)
    assert(trimmedLines(buffer) == Seq("  ab", " abcd"))

  test("right alignment pushes lines to the right edge"):
    val buffer = rendered(Paragraph(Text.raw("ab"), alignment = Alignment.Right), 5, 1)
    assert(trimmedLines(buffer) == Seq("   ab"))

  test("an over-wide right-aligned line keeps its end, not its beginning"):
    // A right-aligned path or timestamp is aligned that way because the end is the part that identifies it.
    val buffer = rendered(Paragraph(Text.raw("/var/log/app.log"), alignment = Alignment.Right), 7, 1)
    assert(trimmedLines(buffer) == Seq("app.log"))

  test("an over-wide centered line loses as much from each side"):
    val buffer = rendered(Paragraph(Text.raw("abcdefgh"), alignment = Alignment.Center), 4, 1)
    assert(trimmedLines(buffer) == Seq("cdef"))

  test("an over-wide left-aligned line still keeps its beginning"):
    val buffer = rendered(Paragraph(Text.raw("abcdefgh"), alignment = Alignment.Left), 4, 1)
    assert(trimmedLines(buffer) == Seq("abcd"))

  test("truncating an over-wide line never splits a wide character"):
    // Two columns must go from the front of "你好世", but the cut lands inside 你, so the whole character goes and the
    // row starts one column in from the right edge rather than showing half a glyph.
    val buffer = rendered(Paragraph(Text.raw("你好世"), alignment = Alignment.Right), 4, 1)
    assert(trimmedLines(buffer) == Seq("好世"))

  test("truncation away from the alignment keeps each span's style"):
    val line   = Line(Seq(Span.raw("dropped "), Span("kept", Style.Default.withFg(Color.Red))))
    val buffer = rendered(Paragraph(Text(Seq(line)), alignment = Alignment.Right), 4, 1)
    assert(trimmedLines(buffer) == Seq("kept"))
    assert(buffer.get(0, 0).style.fg.contains(Color.Red))

  test("span styles survive rendering and layer over the paragraph style"):
    val line   = Line(Seq(Span.raw("a"), Span("b", Style.Default.withFg(Color.Red))))
    val buffer = rendered(Paragraph(Text(Seq(line)), style = Style.Default.bold), 3, 1)
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Bold))
    assert(buffer.get(1, 0).style.fg.contains(Color.Red))
    assert(buffer.get(1, 0).style.modifiers.hasAny(Modifiers.Bold))

  test("excess lines are clipped at the area height"):
    val buffer = rendered(Paragraph(Text.raw("1\n2\n3")), 3, 2)
    assert(trimmedLines(buffer) == Seq("1", "2"))

  test("a line's own alignment overrides the paragraph's for that row only"):
    val text   = Text(Seq(Line.raw("ab"), Line.raw("ab").centered, Line.raw("ab").rightAligned))
    val buffer = rendered(Paragraph(text), 6, 3)
    assert(trimmedLines(buffer) == Seq("ab", "  ab", "    ab"))

  test("a line marked left-aligned escapes a right-aligned paragraph"):
    val text   = Text(Seq(Line.raw("ab").leftAligned, Line.raw("ab")))
    val buffer = rendered(Paragraph(text, alignment = Alignment.Right), 6, 2)
    assert(trimmedLines(buffer) == Seq("ab", "    ab"))

  test("a wrapped line keeps its own alignment on every row it spills onto"):
    val paragraph = Paragraph(Text(Seq(Line.raw("abcdef").rightAligned)), overflow = Overflow.Wrap)
    val buffer    = rendered(paragraph, 4, 2)
    assert(trimmedLines(buffer) == Seq("abcd", "  ef"))
    assert(paragraph.heightAt(4).contains(2))

  test("a line's alignment is measured in display columns, not characters"):
    // "你好" is two characters but four terminal columns, so centring it in nine columns starts it at column 2.
    val buffer = rendered(Paragraph(Text(Seq(Line.raw("你好").centered))), 9, 1)
    assert(trimmedLines(buffer) == Seq("  你好"))

  test("a line wider than the area starts at the left edge and keeps the end it asked for"):
    // There is nowhere to place a line that is already wider than the area, so it always starts at column 0. What it
    // keeps still follows its alignment: a right-aligned line loses its beginning rather than its end.
    val buffer = rendered(Paragraph(Text(Seq(Line.raw("abcdef").rightAligned))), 4, 1)
    assert(trimmedLines(buffer) == Seq("cdef"))

  test("a one-column area clips a centred line instead of starting it off-screen"):
    val buffer = rendered(Paragraph(Text(Seq(Line.raw("ab").centered))), 1, 1)
    assert(trimmedLines(buffer) == Seq("a"))

  test("a Text restyled with styled reaches the rendered cells"):
    val buffer = rendered(Paragraph(Text.raw("hi").styled(_.withFg(Color.Green).bold)), 4, 1)
    assert(buffer.get(0, 0).style.fg.contains(Color.Green))
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Bold))

  test("restyling a Text leaves its column widths alone"):
    val buffer = rendered(Paragraph(Text.raw("漢字").styled(_.withFg(Color.Red))), 6, 1)
    assert(trimmedLines(buffer) == Seq("漢字"))
    assert(buffer.get(2, 0).symbol == "字") // still two columns for the first character

  test("patchStyle survives the render path and layers over the span's own style"):
    val text   = Text(Seq(Line(Seq(Span("hi", Style.Default.withFg(Color.Green))))))
    val buffer = rendered(Paragraph(text.patchStyle(Style.Default.italic)), 4, 1)
    assert(buffer.get(0, 0).style.fg.contains(Color.Green))
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Italic))

  test("a Text accumulated span by span renders like the same text written out"):
    val built   = Text.Empty.appendedToLast(Span.raw("ab")).appended(Line.raw("cd"))
    val literal = Text(Seq(Line(Seq(Span.raw("ab"))), Line(Seq(Span.raw("cd")))))
    assert(trimmedLines(rendered(Paragraph(built), 4, 2)) == trimmedLines(rendered(Paragraph(literal), 4, 2)))
    assert(trimmedLines(rendered(Paragraph(built), 4, 2)) == Seq("ab", "cd"))

  test("scrollY skips whole source lines under Overflow.Clip"):
    val text   = Text.raw("one\ntwo\nthree\nfour")
    val buffer = rendered(Paragraph(text, scrollY = 2), 6, 2)
    assert(trimmedLines(buffer) == Seq("three", "four"))

  test("scrollY counts wrapped rows, not source lines"):
    // One source line wrapping into three rows: an offset of one shows the *second* wrapped row at the top of the
    // area, which is the case a widget-level scroller cannot express.
    val text   = Text.raw("aa bb cc")
    val buffer = rendered(Paragraph(text, overflow = Overflow.Wrap, scrollY = 1), 3, 2)
    assert(trimmedLines(buffer) == Seq("bb", "cc"))

  test("scrolling past the end of the text leaves the area blank"):
    val buffer = rendered(Paragraph(Text.raw("one\ntwo"), scrollY = 9), 5, 2)
    assert(trimmedLines(buffer) == Seq("", ""))

  test("a negative scroll offset is clamped to the top-left corner"):
    val text = Text.raw("one\ntwo")
    assert(trimmedLines(rendered(Paragraph(text, scrollY = -3, scrollX = -3), 5, 2)) == Seq("one", "two"))

  test("scrollY does not change the height the paragraph reports"):
    val text     = Text.raw("aa bb cc dd")
    val scrolled = Paragraph(text, overflow = Overflow.Wrap, scrollY = 2)
    assert(scrolled.heightAt(3) == Paragraph(text, overflow = Overflow.Wrap).heightAt(3))

  test("scrollX skips columns from the start of every clipped row"):
    val buffer = rendered(Paragraph(Text.raw("abcdefgh\n12345678"), scrollX = 3), 4, 2)
    assert(trimmedLines(buffer) == Seq("defg", "4567"))

  test("scrollX never cuts a wide grapheme in half"):
    // Skipping one column would land inside "你", so the whole character is dropped and the column of it that lay past
    // the edge is left blank: "好" is written where the source puts it, one column in, not pulled left into the gap.
    val buffer = rendered(Paragraph(Text.raw("你好"), scrollX = 1), 4, 1)
    assert(buffer.get(0, 0).symbol == " ")
    assert(buffer.get(1, 0).symbol == "好")

  test("scrollX keeps source columns aligned across rows when a wide glyph straddles the edge"):
    // Source column 4 holds `a` on both rows. One column scrolled off drops `漢` (columns 0–1) whole, but the column it
    // would have occupied past the edge stays blank rather than closing up, so `字abc` on the first row sits under
    // `zabc` on the second exactly as it does in the source — the rule `TextArea` has always followed for the same
    // scroll. Closing the gap used to draw `字` one column early and leave a stray blank inside the row.
    val buffer = rendered(Paragraph(Text.raw("漢字abc\nxyzabc"), scrollX = 1), 6, 2)
    assert(lines(buffer) == Seq(" 字abc", "yzabc "))

  test("scrollX moves a right-aligned line further towards its beginning"):
    // Without an offset a right-aligned over-wide line keeps its end, "cdef". Two columns of scroll throw two more
    // columns away from the left, leaving "ef" — and what is left is still right-aligned in the four-column area, so
    // it is placed at the right edge rather than at column zero.
    val line = Text(Seq(Line.raw("abcdef").rightAligned))
    assert(trimmedLines(rendered(Paragraph(line, scrollX = 2), 4, 1)) == Seq("  ef"))

  test("a short line scrolled past its own end draws nothing and does not misplace the rest"):
    val buffer = rendered(Paragraph(Text.raw("ab\nlonger"), scrollX = 4), 6, 2)
    assert(trimmedLines(buffer) == Seq("", "er"))

  test("the paragraph style paints the whole area, not only the cells with text in them"):
    val buffer = rendered(Paragraph(Text.raw("hi"), style = Style.Default.withBg(Color.Blue)), 4, 2)
    assert(buffer.get(0, 0).style.bg.contains(Color.Blue)) // under the text
    assert(buffer.get(3, 0).style.bg.contains(Color.Blue)) // the blank tail of a short line
    assert(buffer.get(1, 1).style.bg.contains(Color.Blue)) // a row past the end of the text
    assert(buffer.get(3, 0).symbol == " ") // the fill changes styles, never glyphs

  test("a span's own style layers over the paragraph fill rather than replacing it"):
    val text   = Text(Seq(Line(Seq(Span("x", Style.Default.withFg(Color.Red))))))
    val buffer = rendered(Paragraph(text, style = Style.Default.withBg(Color.Blue)), 3, 1)
    assert(buffer.get(0, 0).style.fg.contains(Color.Red))
    assert(buffer.get(0, 0).style.bg.contains(Color.Blue))

  test("a paragraph left at the default style touches no cell it did not write to"):
    val buffer = rendered(Paragraph(Text.raw("hi")), 4, 2)
    assert(buffer.get(3, 1).style == Style.Default)
