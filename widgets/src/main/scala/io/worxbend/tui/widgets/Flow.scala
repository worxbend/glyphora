package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Measured, Rect, Widget}

/** Lays items left to right at their natural widths and wraps onto a new line when the next one does not fit — tags,
  * chips, a bar of buttons.
  *
  * An item's width is its [[io.worxbend.tui.core.Measured#widthAt Measured.widthAt]] answer, which for text is its
  * display width through `CharWidth`, so a CJK label takes two columns per character; its height is its `heightAt` at
  * that width, or one row when it cannot say. An item that cannot say how wide it is takes a whole line of its own, and
  * an item wider than the area starts a line of its own and is clipped at the right edge. Each line is as tall as its
  * tallest item, `spacing` blank columns separate neighbours on a line and `rowSpacing` blank rows separate lines.
  *
  * Measurement replays exactly the placement render uses, so `heightAt(width)` is the rows `render` fills at that
  * width. Stateless and immutable; render-thread use only, like every widget.
  */
final case class Flow(items: Seq[Widget], spacing: Int = 1, rowSpacing: Int = 0) extends Widget with Measured:

  private val gap    = math.max(0, spacing)
  private val rowGap = math.max(0, rowSpacing)

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      placements(area.width).foreach { (item, at) =>
        val target = Rect(area.x + at.x, area.y + at.y, at.width, at.height).intersection(area)
        if !target.isEmpty then item.render(target, buffer)
      }

  override def heightAt(width: Int): Option[Int] =
    val placed = placements(math.max(0, width))
    Some(placed.map((_, at) => at.bottom).maxOption.getOrElse(0))

  override def widthAt(height: Int): Option[Int] =
    LayoutItem
      .allOrNone(items.map(LayoutItem.widthOf(_, 1)))
      .map(widths => widths.sum + gap * math.max(0, widths.size - 1))

  /** Each item with the rectangle it is drawn into, relative to the area's top-left corner, at `width` columns. */
  private[widgets] def placements(width: Int): Seq[(Widget, Rect)] =
    val placed     = Vector.newBuilder[(Widget, Rect)]
    var x          = 0
    var y          = 0
    var lineHeight = 0
    items.foreach { item =>
      val natural = LayoutItem.widthOf(item, 1).map(math.max(0, _))
      val itemW   = natural.fold(width)(math.min(_, width))
      val ownLine = natural.isEmpty || natural.exists(_ > width)
      if x > 0 && (ownLine || x + itemW > width) then
        y += lineHeight + rowGap
        x = 0
        lineHeight = 0
      val itemH   = math.max(1, LayoutItem.heightOf(item, itemW).getOrElse(1))
      placed += item -> Rect(x, y, itemW, itemH)
      lineHeight = math.max(lineHeight, itemH)
      x += itemW + gap
      if ownLine then
        y += lineHeight + rowGap
        x = 0
        lineHeight = 0
    }
    placed.result()
