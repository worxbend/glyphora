package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Color, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class GroupedBarChartSpec extends AnyFunSuite:

  test("centred labels preserve styled cells, odd padding and whole graphemes at a nonzero origin"):
    val sentinel   = Cell(".", Style.Default.withBg(Color.Blue))
    val labelStyle = Style.Default.withFg(Color.Red).withBg(Color.Green).bold
    for (label, width, fitted, offset) <- Seq(
        ("ab", 5, "ab", 1),
        ("你好世", 5, "你好", 0),
        ("e\u0301", 4, "e\u0301", 1),
        ("👩‍💻x", 2, "👩‍💻", 0),
        ("👩‍💻", 1, "", 0),
      )
    do
      val area     = Rect(3, 2, width, 3)
      val actual   = Buffer.filled(Rect(0, 0, 12, 7), sentinel)
      val expected = actual.snapshot
      val _        = expected.setString(3 + offset, 4, fitted, labelStyle)
      GroupedBarChart(Seq(BarGroup.of(label, "a" -> 0L)), barWidth = width, labelStyle = labelStyle)
        .render(area, actual)
      for y <- 0 until 7; x <- 0 until 12 do assert(actual.get(x, y) == expected.get(x, y), s"$label at ($x,$y)")

  private def chart(groups: Seq[BarGroup]): GroupedBarChart =
    GroupedBarChart(groups, barWidth = 1, barGap = 0, groupGap = 1, max = Some(8))

  test("bars cluster into groups with the group label centred underneath"):
    val groups = Seq(BarGroup.of("q1", "a" -> 8, "b" -> 4), BarGroup.of("q2", "a" -> 8, "b" -> 8))
    val buffer = rendered(chart(groups), 5, 3)
    // two bars per group, one blank column between the groups, and each label centred under its own pair
    assert(trimmedLines(buffer) == Seq("█  ██", "██ ██", "q1 q2"))

  test("the scale is shared by every group rather than computed per group"):
    // Without a shared ceiling the lone bar in each group would fill its own column, and the second group would look
    // the same height as the first despite being a quarter of it.
    val groups = Seq(BarGroup.of("", "a" -> 8), BarGroup.of("", "a" -> 2))
    val buffer = rendered(GroupedBarChart(groups, barWidth = 1, barGap = 0, groupGap = 1), 3, 2)
    assert(trimmedLines(buffer) == Seq("█", "█ ▄"))

  test("a bar's own style layers over the chart's"):
    val groups = Seq(BarGroup("g", Seq(GroupedBar("a", 8, Style.Default.withFg(Color.Red)), GroupedBar("b", 8))))
    val buffer = rendered(chart(groups).copy(barStyle = Style.Default.bold), 2, 1)
    assert(buffer.get(0, 0).style.fg.contains(Color.Red))
    assert(buffer.get(0, 0).style.modifiers.hasAny(io.worxbend.tui.core.Modifiers.Bold))
    assert(buffer.get(1, 0).style.fg.isEmpty) // the bar with no style of its own keeps the chart's
    assert(buffer.get(1, 0).style.modifiers.hasAny(io.worxbend.tui.core.Modifiers.Bold))

  test("a group that does not fit wholly in the area is dropped, not half-drawn"):
    val groups = Seq(BarGroup.of("", "a" -> 8, "b" -> 8), BarGroup.of("", "a" -> 8, "b" -> 8))
    // Four columns hold the first group and one column of the second; the second is dropped entirely.
    val buffer = rendered(chart(groups), 4, 1)
    assert(trimmedLines(buffer) == Seq("██"))

  test("groups with different numbers of bars each start after the one before"):
    val groups = Seq(BarGroup.of("", "a" -> 8, "b" -> 8), BarGroup.of("", "a" -> 8))
    val buffer = rendered(chart(groups), 5, 1)
    assert(trimmedLines(buffer) == Seq("██ █"))

  test("a wide label is truncated in columns, not characters, and stays inside its group"):
    val groups = Seq(BarGroup.of("你好世", "a" -> 8, "b" -> 8), BarGroup.of("x", "a" -> 8))
    val buffer = rendered(chart(groups), 5, 2)
    // the group is two columns wide, so exactly one wide character fits; the next group's label is untouched
    assert(trimmedLines(buffer) == Seq("██ █", "你 x"))

  test("empty and degenerate inputs render nothing and do not throw"):
    assert(trimmedLines(rendered(chart(Seq.empty), 5, 3)).forall(_.isEmpty))
    assert(trimmedLines(rendered(chart(Seq(BarGroup("g", Seq.empty))), 5, 3)).forall(_.isEmpty))
    assert(trimmedLines(rendered(chart(Seq(BarGroup.of("g", "a" -> 8))), 0, 0)).isEmpty)
    val zeroWidth = GroupedBarChart(Seq(BarGroup.of("g", "a" -> 8)), barWidth = 0)
    assert(trimmedLines(rendered(zeroWidth, 4, 2)).forall(_.isEmpty))

  test("an all-zero chart draws no fill and does not divide by zero"):
    val groups = Seq(BarGroup.of("", "a" -> 0, "b" -> 0))
    assert(trimmedLines(rendered(GroupedBarChart(groups, barWidth = 1, barGap = 0), 4, 2)).forall(_.isEmpty))

  test("a negative gap cannot walk a group left of the area"):
    val groups = Seq(BarGroup.of("", "a" -> 8), BarGroup.of("", "a" -> 8))
    val buffer = rendered(GroupedBarChart(groups, barWidth = 1, barGap = -5, groupGap = -5, max = Some(8)), 3, 1)
    assert(trimmedLines(buffer) == Seq("██"))

  test("a negative gap tight enough to overlap bars still keeps the group inside its area"):
    // barWidth 3 with a gap of -1 gives a stride of 2, so two bars occupy columns 0..4 — five columns, not the four
    // that `groups * stride - gap` used to compute. With the group believed to be four wide it passed the "does it
    // fit" test against a four-column area and then painted a fifth column outside it. `Buffer.set` clips to the
    // buffer and not to the area, so in a real layout that column lands on the neighbouring widget.
    val buffer = io.worxbend.tui.core.Buffer(io.worxbend.tui.core.Rect(0, 0, 8, 2))
    val chart  = GroupedBarChart(Seq(BarGroup.of("g", "a" -> 8, "b" -> 8)), barWidth = 3, barGap = -1, max = Some(8))
    chart.render(io.worxbend.tui.core.Rect(0, 0, 4, 1), buffer)
    val row    = (0 until 8).map(x => buffer.get(x, 0).symbol).mkString
    assert(row.drop(4).forall(_ == ' '), s"the chart painted past its area: '$row'")
