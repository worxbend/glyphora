package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Color, Direction, Modifiers, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{cellDifferences, rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

/** One bar, or one sparkline column, drawn differently from the rest — the threshold breach in an otherwise plain
  * series. The override is patched over the widget's own style, so these tests assert both that the marked bar changed
  * and that everything the override did not mention survived.
  */
final class BarStylingSpec extends AnyFunSuite:

  private val Base = Style.Default.withBg(Color.Blue).bold
  private val Red  = Style.Default.withFg(Color.Red)

  private def overLimit(limit: Long): (Int, Long) => Option[Style] =
    (_, value) => Option.when(value > limit)(Red)

  test("a bar chart restyles only the bars the override names"):
    val chart  = BarChart(
      Seq(("a", 2), ("b", 8), ("c", 2)),
      BarChartOptions(
        barWidth = 1,
        barGap = 1,
        max = Some(8),
        barStyle = Base,
        barStyleFor = overLimit(4),
      ),
    )
    val buffer = rendered(chart, 5, 3)

    assert(buffer.get(2, 0).style.fg.contains(Color.Red), "the bar over the limit was not restyled")
    assert(buffer.get(0, 1).style == Base, "a bar under the limit was restyled")
    assert(buffer.get(4, 1).style == Base, "a bar under the limit was restyled")

  test("an override that sets only a colour keeps the rest of the bar's style"):
    val chart  =
      BarChart(Seq(("a", 8)), BarChartOptions(barWidth = 1, max = Some(8), barStyle = Base, barStyleFor = overLimit(0)))
    val buffer = rendered(chart, 1, 2)

    val style = buffer.get(0, 0).style
    assert(style.fg.contains(Color.Red), "the override's colour is missing")
    assert(style.bg.contains(Color.Blue), "the chart's own background was replaced instead of patched")
    assert(style.modifiers.hasAny(Modifiers.Bold), "the chart's own text attributes were lost")

  test("restyling changes no glyph and moves no bar"):
    val data    = Seq[(String, Long)](("a", 2), ("b", 8), ("c", 5))
    val plain   = rendered(BarChart(data, BarChartOptions(barWidth = 1, barGap = 1, max = Some(8))), 5, 3)
    val painted = rendered(
      BarChart(data, BarChartOptions(barWidth = 1, barGap = 1, max = Some(8), barStyleFor = overLimit(4))),
      5,
      3,
    )

    assert(trimmedLines(painted) == trimmedLines(plain), "the override moved or changed the drawing")
    assert(cellDifferences(painted, plain).nonEmpty, "nothing was restyled, so this test proves nothing")

  test("an override that answers None everywhere draws the identical chart"):
    val data    = Seq[(String, Long)](("a", 2), ("b", 8))
    val plain   = rendered(BarChart(data, BarChartOptions(barWidth = 1, barGap = 1, barStyle = Base)), 3, 3)
    val painted = rendered(
      BarChart(data, BarChartOptions(barWidth = 1, barGap = 1, barStyle = Base, barStyleFor = (_, _) => None)),
      3,
      3,
    )

    assert(cellDifferences(painted, plain).isEmpty)

  test("only the bars actually painted are asked about"):
    val asked = ArrayBuffer.empty[Int]
    // three bars two columns wide with a gap need eight columns; five columns hold two of them
    val chart = BarChart(
      Seq(("a", 8), ("b", 8), ("c", 8)),
      BarChartOptions(
        barWidth = 2,
        barGap = 1,
        max = Some(8),
        barStyleFor = (index, _) =>
          asked += index
          None,
      ),
    )
    val _     = rendered(chart, 5, 2)

    assert(asked.toSet == Set(0, 1), s"a clipped bar was still queried: $asked")

  test("a horizontal chart restyles the bar and leaves the gutter label alone"):
    val chart  = BarChart(
      Seq(("cpu", 8)),
      BarChartOptions(
        barWidth = 1,
        max = Some(8),
        barStyle = Base,
        labelStyle = Style.Default.dim,
        direction = Direction.Horizontal,
        barHeight = 1,
        barStyleFor = overLimit(0),
      ),
    )
    val buffer = rendered(chart, 8, 1)

    assert(buffer.get(0, 0).style == Style.Default.dim, "the label took the bar's override")
    assert(buffer.get(4, 0).style.fg.contains(Color.Red), "the bar was not restyled")

  test("a sparkline restyles a single column"):
    val line   = Sparkline(Seq(1L, 9L, 1L), max = Some(9), style = Base, styleFor = overLimit(4))
    val buffer = rendered(line, 3, 2)

    assert(buffer.get(1, 0).style.fg.contains(Color.Red), "the column over the limit was not restyled")
    assert(buffer.get(0, 1).style == Base, "a column under the limit was restyled")

  test("a right-to-left sparkline reports the index in the data, not the screen column"):
    val asked = ArrayBuffer.empty[Int]
    // six points into three columns: the oldest three scroll off the left, so the visible points are 3, 4 and 5
    val line  = Sparkline(
      Seq(1L, 2L, 3L, 4L, 5L, 6L),
      direction = SparkDirection.RightToLeft,
      styleFor = (index, _) =>
        asked += index
        None,
    )
    val _     = rendered(line, 3, 2)

    assert(asked.toSeq == Seq(3, 4, 5), s"the wrong indices were reported: $asked")

  test("an empty sparkline asks about nothing"):
    val asked = ArrayBuffer.empty[Int]
    val line  = Sparkline(
      Seq.empty,
      styleFor = (index, _) =>
        asked += index
        None,
    )
    val _     = rendered(line, 4, 2)

    assert(asked.isEmpty)

  test("two default-built charts still compare equal"):
    // the no-op override is a single shared value, not a fresh lambda per chart, so equality survives
    assert(BarChart(Seq(("a", 1))) == BarChart(Seq(("a", 1))))
    assert(Sparkline(Seq(1L)) == Sparkline(Seq(1L)))
