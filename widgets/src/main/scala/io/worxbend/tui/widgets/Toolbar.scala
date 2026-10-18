package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Measured, Rect, Style, Widget}

/** A one-row strip of controls, drawn on the first row of its area — a toolbar, a segmented button group — laid left to
  * right at each item's natural width ([[Measured.widthAt]]; an item that cannot say takes one column), `gap` blank
  * columns apart, with `divider` drawn in the middle of every gap when given (`gap = 0, divider = Some("│")` joins the
  * items into one segmented control).
  *
  * When the items do not all fit, the strip shows the window of them that does and marks what is cut off: `»` in the
  * last column when items follow, `« ` at the start when items precede. The window starts at the first item, unless the
  * `focus` item would fall outside it — then it ends at the focus item, so the control the keyboard is on is always on
  * screen. [[window]] and [[placements]] replay the same arithmetic for a caller doing its own hit testing.
  *
  * Stateless and immutable; render-thread use only, like every widget.
  */
final case class Toolbar(
    items: Seq[Widget],
    focus: Option[Int] = None,
    gap: Int = 1,
    divider: Option[String] = None,
    style: Style = Style.Default,
    dividerStyle: Style = Style.Default.dim,
    overflowBefore: String = "« ",
    overflowAfter: String = "»",
) extends Widget
    with Measured:

  /** Columns between two neighbours: the gap, or a gap either side of the divider. */
  private val between: Int = divider.fold(math.max(0, gap))(glyph => 2 * math.max(0, gap) + CharWidth.of(glyph))

  private def widths: Seq[Int] = items.map(item => LayoutItem.widthOf(item, 1).fold(1)(math.max(0, _)))

  override def widthAt(height: Int): Option[Int] =
    val _ = height
    Some(span(widths))

  override def heightAt(width: Int): Option[Int] =
    val _ = width
    Some(1)

  /** The half-open range of items drawn at `width` columns. */
  def window(width: Int): (Int, Int) =
    val sizes = widths
    if span(sizes) <= width then (0, sizes.size)
    else
      val fromStart = fitForward(sizes, 0, width - CharWidth.of(overflowAfter))
      focus.filter(index => index >= fromStart && index < sizes.size) match
        case Some(index) =>
          val end   = index + 1
          val room  =
            width - CharWidth.of(overflowBefore) - (if end < sizes.size then CharWidth.of(overflowAfter) else 0)
          var start = index
          while start > 0 && span(sizes.slice(start - 1, end)) <= room do start -= 1
          (start, end)
        case None        => (0, math.max(1, fromStart).min(sizes.size))

  /** Each drawn item with the rectangle it is drawn into, inside `area`. */
  def placements(area: Rect): Seq[(Int, Rect)] =
    if area.isEmpty then Seq.empty
    else
      val sizes        = widths
      val (start, end) = window(area.width)
      var x            = area.x + (if start > 0 then CharWidth.of(overflowBefore) else 0)
      (start until end).map { index =>
        if index > start then x += between
        val placed = Rect(x, area.y, math.max(0, math.min(sizes(index), area.right - x)), 1)
        x += sizes(index)
        index -> placed
      }

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      val (start, end) = window(area.width)
      if start > 0 then buffer.setString(area.x, area.y, overflowBefore, style.patch(dividerStyle), area.width)
      val placed       = placements(area)
      placed.foreach { (index, rect) =>
        divider.filter(_ => index > start).foreach { glyph =>
          buffer.setString(
            rect.x - math.max(0, gap) - CharWidth.of(glyph),
            area.y,
            glyph,
            style.patch(dividerStyle),
            area.right - rect.x,
          )
        }
        if !rect.isEmpty then items(index).render(rect, buffer)
      }
      if end < items.size then
        val markerX = area.right - CharWidth.of(overflowAfter)
        buffer.setString(markerX, area.y, overflowAfter, style.patch(dividerStyle), area.right - markerX)

  /** Columns `sizes` take laid out with the gaps between them. */
  private def span(sizes: Seq[Int]): Int = sizes.sum + between * math.max(0, sizes.size - 1)

  /** How many items from `start` fit in `room` columns. */
  private def fitForward(sizes: Seq[Int], start: Int, room: Int): Int =
    var end = start
    while end < sizes.size && span(sizes.slice(start, end + 1)) <= room do end += 1
    end - start
