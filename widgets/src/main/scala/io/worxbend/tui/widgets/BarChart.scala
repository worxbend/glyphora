package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Buffer, CharWidth, Direction, Rect, Style, Widget}

/** Everything about a [[BarChart]] that is not the data: the layout, the scale, the numbers beside the bars, and how
  * all of it is styled and drawn.
  *
  * Bundled rather than listed on [[BarChart]] itself because the list had grown to twelve optional knobs. Every field
  * keeps the default it had as a `BarChart` constructor parameter, so `BarChartOptions()` is exactly the chart the bare
  * constructor drew.
  *
  * @param direction
  *   picks the layout — `Vertical` (the default) draws upright bars with their labels on the row underneath,
  *   `Horizontal` draws bars rightwards with the labels in a gutter down the left edge
  * @param barHeight
  *   rows per bar; read by the horizontal layout only
  * @param barWidth
  *   columns per bar; read by the vertical layout only, where it is also all the room a label gets
  * @param barGap
  *   blank columns (rows, sideways) between neighbouring bars
  * @param max
  *   the top of the scale; `None` — the default — scales against the largest value in the data, so the tallest bar
  *   always reaches the edge of the area
  * @param showValues
  *   writes each bar's number beside the bar: above an upright bar, to the right of a sideways one. Written next to the
  *   bar rather than inside it because a [[io.worxbend.tui.core.Buffer]] cell holds one style, so a number on top of a
  *   filled bar would take the bar's colours and could vanish into them. A number with no room — a bar already at the
  *   top of the area, or wider than the space left beside it — is left out rather than truncated, because half a number
  *   reads as a different number
  * @param valueFormat
  *   turns a value into the text `showValues` writes, which is where a unit or a thousands separator goes
  * @param barStyle
  *   the style every bar is drawn in unless `barStyleFor` says otherwise
  * @param labelStyle
  *   the style of the category labels
  * @param valueStyle
  *   the style of the numbers `showValues` writes
  * @param barStyleFor
  *   restyles individual bars — `(_, value) => Option.when(value > limit)(Style.Default.withFg(Color.Red))` paints the
  *   bars over a limit red and leaves the rest alone. It is handed the bar's index in `data` and its value, and what it
  *   returns is patched over `barStyle`, so an override setting only a colour keeps the rest. It colours the bar and
  *   nothing else: the label under it and the number beside it keep `labelStyle` and `valueStyle`, because those are
  *   text a reader has to be able to read whatever the bar is doing. See [[BarStyling]]
  * @param barSet
  *   the glyphs the bars are drawn from — [[BarSet.Ascii]] for a terminal with no block elements, [[BarSet.Solid]] or
  *   [[BarSet.Halves]] for blunter bars, or a set of your own. Its `empty` glyph, if it has one, also fills the cells
  *   above each bar, which is how the chart gets a visible track behind every bar
  */
final case class BarChartOptions(
    direction: Direction = Direction.Vertical,
    barHeight: Int = 1,
    barWidth: Int = 3,
    barGap: Int = 1,
    max: Option[Long] = None,
    showValues: Boolean = false,
    valueFormat: Long => String = _.toString,
    barStyle: Style = Style.Default,
    labelStyle: Style = Style.Default,
    valueStyle: Style = Style.Default,
    barStyleFor: (Int, Long) => Option[Style] = BarStyling.NoOverride,
    barSet: BarSet = BarSet.Eighths,
)

/** Bars with labels, one per `(label, value)`, scaled against `options.max` (defaulting to the data's maximum) and
  * topped with a partial block glyph for sub-cell precision.
  *
  * `options.direction` picks the layout. `Vertical` — the default, and what this widget has always done — draws upright
  * bars `barWidth` columns wide with their labels on the row underneath, so each name has only as many columns as its
  * bar. `Horizontal` draws the bars rightwards, `barHeight` rows tall, and reserves a gutter down the left edge for the
  * labels: that is the layout to reach for when the category names are long, because a name in the gutter has a whole
  * strip of columns to itself instead of being truncated to a bar's width.
  *
  * Everything past the data — the layout, the scale, the numbers beside the bars, the styles and the glyph set — rides
  * in `options`; see [[BarChartOptions]] for what each knob does.
  */
final case class BarChart(
    data: Seq[(String, Long)],
    options: BarChartOptions = BarChartOptions(),
) extends Widget:

  import options.*

  def render(area: Rect, buffer: Buffer): Unit = direction match
    case Direction.Vertical   => renderVertical(area, buffer)
    case Direction.Horizontal => renderHorizontal(area, buffer)

  /** Upright bars across the area, labels on the last row. */
  private def renderVertical(area: Rect, buffer: Buffer): Unit =
    val showLabels  = ColumnChart.showLabels(area, data.map((label, _) => label))
    val chartHeight = ColumnChart.chartHeight(area, showLabels)
    if area.isEmpty || data.isEmpty || chartHeight <= 0 || barWidth <= 0 then ()
    else
      val ceiling = scaleCeiling
      val stride  = ColumnChart.stride(barWidth, barGap)
      data.zipWithIndex.foreach { case ((label, value), index) =>
        val barLeft = ColumnChart.barLeftAt(area, index, stride)
        if ColumnChart.fits(area, barLeft, barWidth) then
          BlockLadder.fillColumn(
            buffer,
            x = barLeft,
            columns = barWidth,
            bottom = area.y + chartHeight - 1,
            top = area.y,
            value = value,
            ceiling = ceiling,
            style = BarStyling.styleAt(barStyle, barStyleFor, index, value),
            set = barSet,
          )
          if showLabels then ColumnChart.drawCentredLabel(buffer, area, barLeft, barWidth, label, labelStyle)
          if showValues then drawValueAbove(buffer, area, barLeft, chartHeight, value, ceiling)
      }

  /** Bars growing rightwards down the area, labels right-aligned in a gutter on the left. */
  private def renderHorizontal(area: Rect, buffer: Buffer): Unit =
    val labels     = data.map((label, _) => label)
    val showLabels = RowChart.showLabels(area, labels)
    val gutter     = RowChart.labelGutter(area, labels, showLabels)
    val plotLeft   = area.x + gutter
    val plotWidth  = area.width - gutter
    if area.isEmpty || data.isEmpty || plotWidth <= 0 || barHeight <= 0 then ()
    else
      val ceiling = scaleCeiling
      val stride  = RowChart.stride(barHeight, barGap)
      data.zipWithIndex.foreach { case ((label, value), index) =>
        val barTop = RowChart.barTopAt(area, index, stride)
        if RowChart.fits(area, barTop, barHeight) then
          BlockLadder.fillRow(
            buffer,
            y = barTop,
            rows = barHeight,
            left = plotLeft,
            right = plotLeft + plotWidth - 1,
            value = value,
            ceiling = ceiling,
            style = BarStyling.styleAt(barStyle, barStyleFor, index, value),
            set = barSet,
          )
          if gutter > 0 then RowChart.drawGutterLabel(buffer, area, barTop, gutter, label, labelStyle)
          if showValues then drawValueBeside(buffer, barTop, plotLeft, plotWidth, value, ceiling)
      }

  /** Writes an upright bar's value on the row above its top, centred over the bar and dropped when it does not fit. */
  private def drawValueAbove(
      buffer: Buffer,
      area: Rect,
      barLeft: Int,
      chartHeight: Int,
      value: Long,
      ceiling: Long,
  ): Unit =
    val text    = valueFormat(value)
    val filled  = BlockLadder.filledCells(value, ceiling, chartHeight)
    val labelY  = area.y + chartHeight - filled - 1
    val fitsRow = labelY >= area.y
    if fitsRow && CharWidth.of(text) <= barWidth then
      buffer.setString(Alignment.Center.originAt(barLeft, barWidth, CharWidth.of(text)), labelY, text, valueStyle)

  /** Writes a sideways bar's value in the track just right of the bar's end, dropped when it does not fit. */
  private def drawValueBeside(
      buffer: Buffer,
      barTop: Int,
      plotLeft: Int,
      plotWidth: Int,
      value: Long,
      ceiling: Long,
  ): Unit =
    val text   = valueFormat(value)
    val filled = BlockLadder.filledCells(value, ceiling, plotWidth)
    val room   = plotWidth - filled - 1
    if CharWidth.of(text) <= room then buffer.setString(plotLeft + filled + 1, barTop, text, valueStyle)

  /** The top of the scale, at least one so an all-zero series cannot divide by zero. */
  private def scaleCeiling: Long =
    math.max(1L, max.getOrElse(data.map((_, value) => value).max))
