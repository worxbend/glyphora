package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CrossAlign, Direction, Flex, Measured, Rect, Widget}

/** Lays its items out top-to-bottom using the core constraint solver and renders each into its segment.
  *
  * An item constrained `Constraint.Auto` gets the rows its widget measures through
  * [[io.worxbend.tui.core.Measured#heightAt Measured.heightAt]], and each item is placed across the column by its own
  * `align`, or by [[crossAlign]] when it sets none — see [[Row]], which this mirrors one axis over.
  *
  * Measurement: a column is as tall as its items plus the gaps between them, where an item's height is its `Length`
  * when it has one and its widget's own measurement otherwise; any item that can say neither makes the column
  * unmeasurable. It is as wide as its widest measurable item.
  */
final case class Column(
    items: Seq[LayoutItem],
    spacing: Int = 0,
    flex: Flex = Flex.Start,
    crossAlign: CrossAlign = CrossAlign.Stretch,
) extends Widget
    with Measured:
  def render(area: Rect, buffer: Buffer): Unit =
    LayoutItem.renderSplit(Direction.Vertical, items, spacing, flex, crossAlign, area, buffer)

  override def heightAt(width: Int): Option[Int] =
    LayoutItem
      .allOrNone(
        items.map(item => LayoutItem.lengthOf(item.constraint).orElse(LayoutItem.heightOf(item.widget, width)))
      )
      .map(_.sum + math.max(0, spacing) * math.max(0, items.size - 1))

  override def widthAt(height: Int): Option[Int] =
    LayoutItem.allOrNone(items.map(item => LayoutItem.widthOf(item.widget, height))).map(_.maxOption.getOrElse(0))
