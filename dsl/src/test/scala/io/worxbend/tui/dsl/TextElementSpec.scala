package io.worxbend.tui.dsl

import io.worxbend.tui.testsupport.BufferAssertions.{line as bufferLine, rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** `text(...)` used to build its paragraph with the alignment and overflow defaults and offer no way to change either,
  * so nothing written in the DSL could wrap or be centred. These tests pin both knobs and the layout claim that has to
  * move with them.
  */
final class TextElementSpec extends AnyFunSuite:

  private def lines(element: Element, width: Int, height: Int): Seq[String] =
    trimmedLines(rendered(element.widget, width, height))

  test("a plain text element still clips at the right edge"):
    assert(lines(text("abcdefgh"), 4, 2) == Seq("abcd", ""))

  test("wrapped text breaks onto further rows"):
    assert(lines(text("abcdefgh").wrapped, 4, 3) == Seq("abcd", "efgh", ""))

  test("clipped undoes a wrap"):
    assert(lines(text("abcdefgh").wrapped.clipped, 4, 2) == Seq("abcd", ""))

  test("alignment positions a short line inside the area"):
    assert(bufferLine(rendered(text("ab").centered.widget, 6, 1), 0) == "  ab  ")
    assert(bufferLine(rendered(text("ab").rightAligned.widget, 6, 1), 0) == "    ab")
    assert(bufferLine(rendered(text("ab").aligned(Alignment.Left).widget, 6, 1), 0) == "ab    ")

  test("wrapping never splits a wide character across two rows"):
    // Each ideograph is two columns wide, so three of them do not fit a five-column row.
    assert(lines(text("漢字語").wrapped, 5, 3) == Seq("漢字", "語", ""))

  test("wrapping keeps a combining mark attached to the letter it modifies"):
    // "e" followed by U+0301 COMBINING ACUTE ACCENT is one grapheme cluster occupying one column.
    val accented = "aéb"
    assert(lines(text(accented).wrapped, 2, 2) == Seq("aé", "b"))

  test("a clipping text claims the exact box its longest line measures"):
    val element = text("ab\ncdef")
    assert(element.claim == SizeClaim.Measured)
    assert(element.intrinsicWidth(2).contains(4) && element.intrinsicHeight(4).contains(2))

  test("a wrapping text claims the container's width rather than its unwrapped one"):
    val claim = text("a very long single line of prose").wrapped.claim
    assert(claim == SizeClaim.Fill)

  test("a wrapping text measures the rows it will actually occupy at a given width"):
    assert(text("abcdefgh").wrapped.intrinsicHeight(4) == Some(2))
    assert(text("abcdefgh").intrinsicHeight(4) == Some(1))

  test("an explicit length constraint still wins over the measured height"):
    assert(text("abcdefgh").wrapped.length(5).intrinsicHeight(4) == Some(5))

  test("empty and zero-sized areas paint nothing and do not throw"):
    assert(lines(text("abc").wrapped.centered, 0, 0).isEmpty)
    assert(lines(text("").wrapped, 4, 1) == Seq(""))
