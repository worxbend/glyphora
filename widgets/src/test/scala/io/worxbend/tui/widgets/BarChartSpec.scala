package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Direction, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class BarChartSpec extends AnyFunSuite:

  test("bars scale against the maximum with labels underneath"):
    val chart  = BarChart(Seq(("a", 8), ("b", 4)), BarChartOptions(barWidth = 1, barGap = 1, max = Some(8)))
    val buffer = rendered(chart, 3, 3)
    assert(trimmedLines(buffer) == Seq("█", "█ █", "a b"))

  test("partial values top out with a fractional block"):
    val chart  = BarChart(Seq(("", 3)), BarChartOptions(barWidth = 1, max = Some(8), barGap = 0))
    val buffer = rendered(chart, 1, 1)
    assert(trimmedLines(buffer) == Seq("▃"))

  test("bars that do not fit the width are clipped"):
    val chart  = BarChart(Seq(("a", 8), ("b", 8), ("c", 8)), BarChartOptions(barWidth = 2, barGap = 1, max = Some(8)))
    val buffer = rendered(chart, 5, 2)
    assert(trimmedLines(buffer).head == "██ ██")

  test("empty data renders nothing"):
    val buffer = rendered(BarChart(Seq.empty), 5, 3)
    assert(trimmedLines(buffer).forall(_.isEmpty))

  test("a horizontal chart draws bars rightwards with labels in a left gutter"):
    val chart  = BarChart(
      Seq(("api", 8), ("db", 4)),
      BarChartOptions(
        barGap = 0,
        max = Some(8),
        direction = Direction.Horizontal,
      ),
    )
    val buffer = rendered(chart, 8, 2)
    // the gutter is "api" plus a blank column; the remaining four columns carry the bars
    assert(trimmedLines(buffer) == Seq("api ████", " db ██"))

  test("a horizontal bar can be several rows thick"):
    val chart  = BarChart(
      Seq(("", 8)),
      BarChartOptions(barGap = 0, max = Some(8), direction = Direction.Horizontal, barHeight = 2),
    )
    val buffer = rendered(chart, 4, 2)
    assert(trimmedLines(buffer) == Seq("████", "████"))

  test("a horizontal bar tops out with a fractional block at its leading edge"):
    val chart = BarChart(Seq(("", 1)), BarChartOptions(barGap = 0, max = Some(8), direction = Direction.Horizontal))
    // one eighth of a single column is the narrowest left-growing glyph
    assert(trimmedLines(rendered(chart, 1, 1)) == Seq("▏"))

  test("a horizontal bar that does not fit the height is dropped, not clipped"):
    val chart  = BarChart(
      Seq(("", 8), ("", 8), ("", 8)),
      BarChartOptions(
        barGap = 0,
        max = Some(8),
        direction = Direction.Horizontal,
        barHeight = 2,
      ),
    )
    val buffer = rendered(chart, 2, 3)
    // the third bar would need rows 4 and 5 of a three-row area, so it is not drawn at all
    assert(trimmedLines(buffer) == Seq("██", "██", ""))

  test("the label gutter never takes more than half the width"):
    val chart  = BarChart(
      Seq(("a very long category name", 8)),
      BarChartOptions(
        barGap = 0,
        max = Some(8),
        direction = Direction.Horizontal,
      ),
    )
    val buffer = rendered(chart, 10, 1)
    // five columns of gutter (four of label plus a blank) and five of bar, whatever the name's length; the
    // label keeps its beginning, which is the part that identifies the category
    assert(trimmedLines(buffer) == Seq("a ve █████"))

  test("a horizontal gutter measures a wide-character label in columns"):
    val chart  = BarChart(Seq(("負荷", 8)), BarChartOptions(barGap = 0, max = Some(8), direction = Direction.Horizontal))
    val buffer = rendered(chart, 10, 1)
    // 負荷 is two ideographs occupying four columns, so the gutter is five columns wide, not three
    assert(trimmedLines(buffer) == Seq("負荷 █████"))

  test("a horizontal chart with no labels gives the whole width to the bars"):
    val chart = BarChart(Seq(("", 8)), BarChartOptions(barGap = 0, max = Some(8), direction = Direction.Horizontal))
    assert(trimmedLines(rendered(chart, 4, 1)) == Seq("████"))

  test("empty data renders nothing in either direction"):
    Direction.values.foreach { direction =>
      val buffer = rendered(BarChart(Seq.empty, BarChartOptions(direction = direction)), 5, 3)
      assert(trimmedLines(buffer).forall(_.isEmpty))
    }

  test("an upright bar's value is written on the row above its top"):
    val chart  = BarChart(Seq(("", 4)), BarChartOptions(barWidth = 1, barGap = 0, max = Some(8), showValues = true))
    val buffer = rendered(chart, 1, 4)
    // 4 of 8 over four rows is two full cells, so the number lands on the row above them
    assert(trimmedLines(buffer) == Seq("", "4", "█", "█"))

  test("a value with no room above the bar is left out rather than truncated"):
    val chart = BarChart(Seq(("", 8)), BarChartOptions(barWidth = 1, barGap = 0, max = Some(8), showValues = true))
    assert(trimmedLines(rendered(chart, 1, 2)) == Seq("█", "█"))

  test("a value wider than its bar is left out"):
    val chart  =
      BarChart(Seq(("", 1000)), BarChartOptions(barWidth = 1, barGap = 0, max = Some(8000), showValues = true))
    val buffer = rendered(chart, 1, 4)
    assert(!trimmedLines(buffer).mkString.contains("1"))

  test("valueFormat decides the text, so a unit or a separator can be added"):
    val chart  =
      BarChart(
        Seq(("", 4)),
        BarChartOptions(barWidth = 4, barGap = 0, max = Some(8), showValues = true, valueFormat = v => s"${v}ms"),
      )
    val buffer = rendered(chart, 4, 4)
    // centred over the four-column bar: three columns of text leave one spare, which goes to the right
    assert(trimmedLines(buffer)(1) == "4ms")

  test("a sideways bar's value is written in the track past the bar's end"):
    val chart = BarChart(
      Seq(("", 4)),
      BarChartOptions(
        barGap = 0,
        max = Some(8),
        direction = Direction.Horizontal,
        showValues = true,
      ),
    )
    // four of eight over eight columns is four filled cells, one blank, then the number
    assert(trimmedLines(rendered(chart, 8, 1)) == Seq("████ 4"))

  test("a sideways value with no room left in the track is left out"):
    val chart = BarChart(
      Seq(("", 8)),
      BarChartOptions(
        barGap = 0,
        max = Some(8),
        direction = Direction.Horizontal,
        showValues = true,
      ),
    )
    assert(trimmedLines(rendered(chart, 4, 1)) == Seq("████"))

  test("values are off unless they are asked for"):
    val plain = rendered(BarChart(Seq(("", 4)), BarChartOptions(barWidth = 1, barGap = 0, max = Some(8))), 1, 4)
    val asked = rendered(
      BarChart(Seq(("", 4)), BarChartOptions(barWidth = 1, barGap = 0, max = Some(8), showValues = false)),
      1,
      4,
    )
    assert(trimmedLines(plain) == trimmedLines(asked))
    assert(!trimmedLines(plain).mkString.contains("4"))

  test("a horizontal bar honours a substituted glyph set"):
    val chart  =
      BarChart(Seq(("a", 8L)), BarChartOptions(max = Some(8L), barSet = BarSet.Ascii, direction = Direction.Horizontal))
    val buffer = rendered(chart, 10, 1)
    assert(trimmedLines(buffer) == Seq("a ########"))

  test("a horizontal bar paints the empty track past its end"):
    val buffer = Buffer(Rect(0, 0, 10, 1))
    (2 until 10).foreach(x => buffer.set(x, 0, Cell("x", Style.Default)))
    BarChart(Seq(("a", 4L)), BarChartOptions(max = Some(8L), barSet = BarSet.Ascii, direction = Direction.Horizontal))
      .render(Rect(0, 0, 10, 1), buffer)
    assert(trimmedLines(buffer) == Seq("a ####"))

  test("a horizontal eighths bar grows rightward rather than upward"):
    val chart  = BarChart(Seq(("a", 1L)), BarChartOptions(max = Some(64L), direction = Direction.Horizontal))
    val buffer = rendered(chart, 10, 1)
    assert(trimmedLines(buffer) == Seq("a ▏"))

  test("BarChartOptions() draws exactly what the bare constructor drew"):
    // every field of the bundle keeps the default it had as a constructor parameter, so a chart built with no options
    // and one built with an explicit empty bundle are the same chart, cell for cell
    val data = Seq(("cpu", 3L), ("mem", 7L), ("io", 5L))
    assert(BarChart(data) == BarChart(data, BarChartOptions()))
    assert(rendered(BarChart(data), 12, 5) == rendered(BarChart(data, BarChartOptions()), 12, 5))
