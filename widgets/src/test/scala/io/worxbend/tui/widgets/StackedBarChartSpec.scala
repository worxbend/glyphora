package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Color, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class StackedBarChartSpec extends AnyFunSuite:

  test("extreme pinned segments cannot narrow into a negative height or paint below the area"):
    val sentinel = Cell(".", Style.Default.withBg(Color.Red))
    val buffer   = Buffer(Rect(0, 0, 1, 7))
    (0 until 7).foreach(y => buffer.set(0, y, sentinel))
    val chart    = StackedBarChart(Seq(("", Seq(Long.MaxValue, 1L))), barWidth = 1, max = Some(1L))
    chart.render(Rect(0, 2, 1, 2), buffer)
    assert(buffer.get(0, 4) == sentinel)
    assert((0 until 7).filterNot(y => y == 2 || y == 3).forall(y => buffer.get(0, y) == sentinel))
    assert((2 to 3).forall(y => buffer.get(0, y) == Cell("█", SeriesPalette.Default.head)))

  test("automatic scale uses positive totals beyond Long without wrapping or saturating"):
    val chart  = StackedBarChart(
      Seq(("", Seq(Long.MaxValue, Long.MaxValue)), ("", Seq(Long.MaxValue, Long.MinValue))),
      barWidth = 1,
      barGap = 0,
    )
    val buffer = rendered(chart, 2, 4)
    assert(
      (0 until 4).map(y => buffer.get(0, y)) ==
        Seq.fill(2)(Cell("█", SeriesPalette.Default(1))) ++ Seq.fill(2)(Cell("█", SeriesPalette.Default.head))
    )
    assert((0 until 4).map(y => buffer.get(1, y).symbol) == Seq(" ", " ", "█", "█"))

  test("ordinary independent segment rounding retains full-cell frame parity"):
    // Fixed ordinary frame: 1/4 of six rows rounds to two per segment (not cumulative rounding).
    val buffer   = rendered(StackedBarChart(Seq(("", Seq(1L, 1L, 2L))), barWidth = 1), 1, 6)
    val expected = Seq(2, 2, 1, 1, 0, 0).map(series => Cell("█", SeriesPalette.Default(series)))
    assert((0 until 6).map(y => buffer.get(0, y)) == expected)

  test("empty, negative and tiny extreme stacks keep sentinel cells outside their rectangles"):
    val sentinel = Cell(".", Style.Default.withBg(Color.Red))
    for
      values  <- Seq(Seq.empty[Long], Seq(Long.MinValue, -1L, 0L), Seq(Long.MaxValue, Long.MaxValue))
      area    <- Seq(Rect(2, 2, 0, 2), Rect(2, 2, 1, 0), Rect(2, 2, 1, 1), Rect(2, 2, 1, 2))
      ceiling <- Seq(None, Some(0L), Some(-1L), Some(Long.MaxValue))
    do
      val buffer = Buffer.filled(Rect(0, 0, 5, 6), sentinel)
      StackedBarChart(Seq(("", values)), barWidth = 1, max = ceiling).render(area, buffer)
      for y <- 0 until 6; x <- 0 until 5 do
        if x < area.x || x >= area.right || y < area.y || y >= area.bottom ||
          !values.exists(_ > 0) || ceiling.exists(_ <= 0)
        then assert(buffer.get(x, y) == sentinel)
    val empty    = Buffer.filled(Rect(0, 0, 2, 2), sentinel)
    StackedBarChart(Seq.empty).render(empty.area, empty)
    assert((0 until 2).forall(y => (0 until 2).forall(x => empty.get(x, y) == sentinel)))

  test("stacked bars stack series segments bottom-up with palette styles"):
    val chart  = StackedBarChart(Seq(("x", Seq(1L, 1L)), ("y", Seq(2L, 2L))), barWidth = 1, barGap = 0)
    val buffer = rendered(chart, 2, 5) // 4 chart rows + label
    assert(buffer.get(0, 4).symbol == "x")
    assert(buffer.get(0, 3).style.fg.contains(Color.Cyan)) // series 0 at the bottom
    assert(buffer.get(0, 2).style.fg.contains(Color.Green)) // series 1 above
    assert(buffer.get(0, 1).symbol == " ") // the shorter bar stops here
    assert(buffer.get(1, 0).style.fg.contains(Color.Green)) // the max bar fills to the top

  test("stacked segments never spill above the chart when their rounding overshoots"):
    // six equal segments each round up to 2 of the 10 chart rows, asking for 12 rows in total
    val chart  = StackedBarChart(Seq(("", Seq.fill(6)(15L))), barWidth = 1, barGap = 0)
    val buffer = Buffer(Rect(0, 0, 1, 13))
    chart.render(Rect(0, 3, 1, 10), buffer)
    assert(trimmedLines(buffer).take(3) == Seq("", "", ""))

  test("a pinned max keeps two charts on the same scale"):
    // the same stack drawn beside a taller one: without a pinned ceiling it would shrink to half the height
    val small  = StackedBarChart(Seq(("", Seq(4L))), barWidth = 1, barGap = 0, max = Some(8))
    val both   = StackedBarChart(Seq(("", Seq(4L)), ("", Seq(8L))), barWidth = 1, barGap = 0, max = Some(8))
    assert(trimmedLines(rendered(small, 1, 4)) == Seq("", "", "█", "█"))
    val beside = rendered(both, 2, 4)
    assert((0 to 3).map(row => beside.get(0, row).symbol) == Seq(" ", " ", "█", "█"))

  test("without a max the scale still follows the tallest stack"):
    val chart  = StackedBarChart(Seq(("", Seq(4L)), ("", Seq(8L))), barWidth = 1, barGap = 0)
    val buffer = rendered(chart, 2, 4)
    assert((0 to 3).map(row => buffer.get(0, row).symbol) == Seq(" ", " ", "█", "█"))

  test("a stack taller than a pinned max is clamped to the top of the chart"):
    val chart  = StackedBarChart(Seq(("", Seq(100L))), barWidth = 1, barGap = 0, max = Some(8))
    val buffer = Buffer(Rect(0, 0, 1, 6))
    chart.render(Rect(0, 2, 1, 4), buffer)
    // the two rows above the chart's own area stay untouched, whatever the data asks for
    assert(trimmedLines(buffer) == Seq("", "", "█", "█", "█", "█"))

  test("a max of zero draws nothing rather than dividing by zero"):
    val chart = StackedBarChart(Seq(("", Seq(4L))), barWidth = 1, barGap = 0, max = Some(0))
    assert(trimmedLines(rendered(chart, 1, 4)).forall(_.isEmpty))
