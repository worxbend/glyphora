package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, Style, Widget}

/** One bar inside a [[BarGroup]]: what it measures, how tall it is, and the colour that identifies the series it
  * belongs to.
  *
  * `style` is per bar rather than per group because in a grouped chart the colour is what tells the reader *which
  * series* a bar is — "this quarter's revenue" against "last quarter's" — and that repeats identically in every group.
  * A palette such as [[SeriesPalette]] gives one style per series, and the same style is then used for that series' bar
  * in each group. `Style.Default` falls back to the chart's own `barStyle`.
  */
final case class GroupedBar(label: String, value: Long, style: Style = Style.Default)

/** A cluster of bars drawn side by side under one shared label — one month, one region, one test run, with a bar per
  * series measured inside it.
  */
final case class BarGroup(label: String, bars: Seq[GroupedBar])

object BarGroup:

  /** A group whose bars carry no styles of their own, so they all take the chart's `barStyle`. Convenient when the
    * grouping itself is the point and the series are told apart by position rather than by colour.
    */
  def of(label: String, values: (String, Long)*): BarGroup =
    BarGroup(label, values.map((barLabel, value) => GroupedBar(barLabel, value)))

/** Upright bars clustered into labelled groups — the chart for comparing the same handful of series across several
  * categories, where [[BarChart]] compares one series across them.
  *
  * Every group is laid out identically: its bars sit `barGap` columns apart, and `groupGap` columns separate one group
  * from the next. A group's label is written centred underneath it on the area's last row, so it has the whole group's
  * width to fit in rather than one bar's — which is the practical reason to reach for this widget even for a single
  * series with long category names.
  *
  * The scale is shared by every bar in the chart, not computed per group. A bar's height is only readable against the
  * bars beside it if they are all measured against the same ceiling; `max` sets that ceiling explicitly, and otherwise
  * it is the largest value anywhere in the data.
  *
  * A group that does not fit wholly inside the area is dropped rather than half-drawn, the same rule [[BarChart]]
  * follows for a bar: a clipped group would show bars whose heights cannot be compared with their neighbours'.
  *
  * Renders into the [[io.worxbend.tui.core.Buffer]] it is handed and nothing else, so it carries the usual widget
  * thread constraint: call it from the render thread that owns the buffer.
  *
  * @param groups
  *   the clusters, drawn left to right in the order given
  * @param barWidth
  *   columns per bar
  * @param barGap
  *   columns between two bars of the same group
  * @param groupGap
  *   columns between one group and the next, on top of `barGap`'s own spacing
  * @param max
  *   the top of the shared scale; `None` means the largest value in the data
  * @param barStyle
  *   the style of a bar whose own `style` is `Style.Default`
  * @param labelStyle
  *   the style of the group labels on the bottom row
  * @param barSet
  *   the glyphs the bars are drawn from — [[BarSet.Ascii]] for a terminal with no block elements, or a set of your own
  */
final case class GroupedBarChart(
    groups: Seq[BarGroup],
    barWidth: Int = 3,
    barGap: Int = 1,
    groupGap: Int = 2,
    max: Option[Long] = None,
    barStyle: Style = Style.Default,
    labelStyle: Style = Style.Default,
    barSet: BarSet = BarSet.Eighths,
) extends Widget:

  def render(area: Rect, buffer: Buffer): Unit =
    val showLabels  = ColumnChart.showLabels(area, groups.map(_.label))
    val chartHeight = ColumnChart.chartHeight(area, showLabels)
    if area.isEmpty || groups.isEmpty || chartHeight <= 0 || barWidth <= 0 then ()
    else
      val ceiling   = scaleCeiling
      val stride    = ColumnChart.stride(barWidth, barGap)
      // A group starts where the one before it ended, so the left edges are accumulated rather than computed from the
      // index: groups may hold different numbers of bars, and multiplying an index by a fixed width would then place
      // every group after the first in the wrong column.
      var groupLeft = area.x
      groups.foreach { group =>
        val groupWidth = widthOf(group, stride)
        if groupWidth > 0 && groupLeft + groupWidth <= area.right then
          group.bars.zipWithIndex.foreach { (bar, index) =>
            BlockLadder.fillColumn(
              buffer,
              x = groupLeft + index * stride,
              columns = barWidth,
              bottom = area.y + chartHeight - 1,
              top = area.y,
              value = bar.value,
              ceiling = ceiling,
              // The bar's own style layers over the chart's, so a bar that names only a foreground colour still gets
              // whatever background the chart was given.
              style = barStyle.patch(bar.style),
              set = barSet,
            )
          }
          if showLabels then ColumnChart.drawCentredLabel(buffer, area, groupLeft, groupWidth, group.label, labelStyle)
        // The gap is added even for a group that was dropped, so dropping one does not shuffle the rest leftwards
        // into columns the reader has already learned to associate with another group.
        groupLeft += groupWidth + math.max(0, groupGap)
      }

  /** Columns from a group's left edge to just past its last bar. A group with no bars occupies nothing at all.
    *
    * Measured from where the bars actually go rather than from the gap that was asked for: the last bar starts at
    * `(n - 1) * stride` and is `barWidth` columns wide, so that sum is the group's width by construction. For a
    * non-negative gap it is the same number as the old `n * stride - barGap`, but a *negative* gap made those two
    * disagree — `stride` clamps at one column while the subtraction used `max(0, barGap)`, so a group of two
    * three-column bars a column apart measured four and painted five. It then passed the "does this group fit" test
    * against an area one column too small and drew that column outside it, over whatever the layout put next door.
    */
  private def widthOf(group: BarGroup, stride: Int): Int =
    if group.bars.isEmpty then 0 else (group.bars.size - 1) * stride + barWidth

  /** The top of the shared scale, at least one so an all-zero chart cannot divide by zero. */
  private def scaleCeiling: Long =
    val values = groups.flatMap(_.bars).map(_.value)
    math.max(1L, max.getOrElse(if values.isEmpty then 0L else values.max))
