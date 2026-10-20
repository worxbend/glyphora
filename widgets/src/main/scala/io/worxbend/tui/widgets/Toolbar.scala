package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Measured, Rect, Style, VersionedState, Widget}

/** Caller-owned state of a toolbar's overflow menu: whether the popup listing the controls that did not fit is open.
  *
  * The [[Toolbar]] widget itself only draws the `»` marker; the DSL's `toolbar(state)(buttons*)` opens this popup when
  * the marker is clicked and floats the hidden controls in it.
  *
  * Render-thread-only. Every mutation raises `version` (see [[io.worxbend.tui.core.Versioned]]), so a write made
  * outside the event path is painted too, with no `Signal` write.
  */
final class ToolbarState extends VersionedState:

  private var openValue: Boolean = false

  /** Whether the overflow menu shows. */
  def open: Boolean = openValue

  /** Opens the overflow menu when it is closed and closes it when it is open. */
  def toggle(): Unit =
    openValue = !openValue
    touch()

  /** Closes the overflow menu. */
  def close(): Unit =
    openValue = false
    touch()

  /** The indices of the controls the toolbar left out the last time it was drawn — what the overflow menu lists. Empty
    * until the first draw, and whenever everything fit.
    */
  def hidden: Seq[Int] = hiddenValue

  private var hiddenValue: Seq[Int] = Seq.empty

  /** Records what the toolbar left out while drawing. An observation of the frame rather than a change to the state, so
    * it does not raise `version` — a redraw that records the same thing must not schedule another redraw.
    */
  private[tui] def recordHidden(indices: Seq[Int]): Unit = hiddenValue = indices

object Toolbar:

  /** The half-open range of drawn item indices. */
  final case class Window(start: Int, end: Int)

  /** An item index and its drawn rectangle in the caller's coordinate space. */
  final case class Placement(index: Int, rect: Rect)

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
  def window(width: Int): Toolbar.Window =
    val sizes = widths
    if span(sizes) <= width then Toolbar.Window(0, sizes.size)
    else
      val fromStart = fitForward(sizes, 0, width - CharWidth.of(overflowAfter))
      focus.filter(index => index >= fromStart && index < sizes.size) match
        case Some(index) =>
          val end   = index + 1
          val room  =
            width - CharWidth.of(overflowBefore) - (if end < sizes.size then CharWidth.of(overflowAfter) else 0)
          var start = index
          while start > 0 && span(sizes.slice(start - 1, end)) <= room do start -= 1
          Toolbar.Window(start, end)
        case None        => Toolbar.Window(0, math.max(1, fromStart).min(sizes.size))

  /** The items [[window]] leaves out at `width` columns, in order — those before the window, then those after it. */
  def hidden(width: Int): Seq[Int] =
    val Toolbar.Window(start, end) = window(width)
    (0 until start) ++ (end until items.size)

  /** Each drawn item with the rectangle it is drawn into, inside `area`. */
  def placements(area: Rect): Seq[Toolbar.Placement] =
    if area.isEmpty then Seq.empty
    else
      val sizes                      = widths
      val Toolbar.Window(start, end) = window(area.width)
      var x                          = area.x + (if start > 0 then CharWidth.of(overflowBefore) else 0)
      (start until end).map { index =>
        if index > start then x += between
        val placed = Rect(x, area.y, math.max(0, math.min(sizes(index), area.right - x)), 1)
        x += sizes(index)
        Toolbar.Placement(index, placed)
      }

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      val Toolbar.Window(start, end) = window(area.width)
      if start > 0 then buffer.setString(area.x, area.y, overflowBefore, style.patch(dividerStyle), area.width)
      val placed                     = placements(area)
      placed.foreach { case Toolbar.Placement(index, rect) =>
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
