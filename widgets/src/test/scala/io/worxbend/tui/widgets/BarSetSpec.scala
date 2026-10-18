package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, CharWidth, Color, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** Covers the substitutable bar ladder: the built-in sets, the empty-cell glyph, and the charts that take one. */
final class BarSetSpec extends AnyFunSuite:

  test("the default set is unchanged, and it is the one the charts use"):
    assert(BarSet.Eighths.eighths == Vector("▁", "▂", "▃", "▄", "▅", "▆", "▇", "█"))
    assert(BarSet.Eighths.empty.isEmpty)
    assert(BlockLadder.Eighths == BarSet.Eighths.eighths)
    assert(Sparkline(Seq(1L)).barSet == BarSet.Eighths)
    assert(BarChart(Seq(("a", 1L))).options.barSet == BarSet.Eighths)

  test("a set must have exactly one glyph per eighth"):
    assertThrows[IllegalArgumentException](BarSet(Vector("#")))
    assertThrows[IllegalArgumentException](BarSet(Vector.fill(9)("#")))

  test("every built-in set is one column wide, so a bar never spills into its neighbour"):
    for set <- Seq(BarSet.Eighths, BarSet.Halves, BarSet.Solid, BarSet.Ascii) do
      for glyph <- set.eighths ++ set.empty do assert(CharWidth.of(glyph) == 1, s"'$glyph' is not one column wide")

  test("the ASCII set draws a sparkline with no block elements at all"):
    val rows = trimmedLines(rendered(Sparkline(Seq(4L, 2L, 0L), max = Some(4L), barSet = BarSet.Ascii), 3, 2))
    assert(rows == Seq("#", "##"))
    assert(rows.mkString.forall(_ < 128))

  test("the solid set fills whole cells with no partial glyphs"):
    val rows = trimmedLines(rendered(Sparkline(Seq(4L, 1L), max = Some(4L), barSet = BarSet.Solid), 2, 2))
    assert(rows == Seq("█", "██"))

  test("an empty glyph paints a track above the bar"):
    val buffer =
      rendered(Sparkline(Seq(4L, 2L), max = Some(4L), style = Style.Default.bold, barSet = BarSet.Solid), 2, 2)
    // the second column's bar reaches only the bottom row, so the cell above it is the track, not untouched space
    assert(buffer.get(1, 1) == Cell("█", Style.Default.bold))
    assert(buffer.get(1, 0) == Cell(" ", Style.Default.bold))

  test("a set with no empty glyph leaves the cells above the bar alone"):
    val area   = Rect(0, 0, 2, 2)
    val buffer = Buffer(area)
    buffer.setString(0, 0, "ab", Style.Default)
    Sparkline(Seq(2L, 2L), max = Some(4L)).render(area, buffer)
    // the bars reach the bottom row only, so the top row keeps the text that was already there
    assert(trimmedLines(buffer) == Seq("ab", "██"))

  test("a set with an empty glyph overwrites what was behind the chart"):
    val area   = Rect(0, 0, 2, 2)
    val buffer = Buffer(area)
    buffer.setString(0, 0, "ab", Style.Default)
    Sparkline(Seq(2L, 2L), max = Some(4L), barSet = BarSet.Solid).render(area, buffer)
    assert(trimmedLines(buffer) == Seq("", "██"))

  test("the track is drawn in the bar's own style"):
    val area   = Rect(0, 0, 1, 2)
    val buffer = Buffer(area)
    Sparkline(Seq(1L), max = Some(4L), style = Style.Default.withFg(Color.Red), barSet = BarSet.Solid)
      .render(area, buffer)
    assert(buffer.get(0, 0) == Cell(" ", Style.Default.withFg(Color.Red)))

  test("a bar chart honours the set across the full width of a wide bar"):
    val rows = trimmedLines(
      rendered(BarChart(Seq(("a", 4L)), BarChartOptions(barWidth = 3, max = Some(4L), barSet = BarSet.Ascii)), 4, 2)
    )
    assert(rows == Seq("###", " a"))

  test("the halves set collapses the ladder to three levels"):
    def columnOf(value: Long): Seq[String] =
      trimmedLines(rendered(Sparkline(Seq(value), max = Some(8L), barSet = BarSet.Halves), 1, 1))
    assert(columnOf(8L) == Seq("█"))
    assert(columnOf(4L) == Seq("▄"))
    assert(columnOf(0L) == Seq(""))

  test("uniform builds a one-glyph set"):
    assert(BarSet.uniform("#", Some(" ")) == BarSet.Ascii)
    assert(BarSet.uniform("*").empty.isEmpty)

  test("an empty area draws nothing even with a track-painting set"):
    val buffer = Buffer(Rect(0, 0, 3, 3))
    Sparkline(Seq(1L), barSet = BarSet.Solid).render(Rect(0, 0, 0, 0), buffer)
    assert(buffer.diff(Buffer(Rect(0, 0, 3, 3))).isEmpty)
