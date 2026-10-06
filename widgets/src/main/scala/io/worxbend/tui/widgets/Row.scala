package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Constraint, Direction, Flex, Layout, Measured, Rect, Widget}

/** One slot of a [[Row]] or [[Column]]: how much space the slot claims and what renders inside it. */
final case class LayoutItem(constraint: Constraint, widget: Widget)

object LayoutItem:

  /** Splits `area` along `direction` by the items' constraints and renders each item into its own segment.
    *
    * The half of [[Row]] and [[Column]] that is the same in both: only the axis differs, so it is stated once here
    * rather than twice in two files that would then have to be kept in step. Segments that come out empty are skipped,
    * because a widget handed a zero-width rect can only guess at what its caller meant.
    */
  private[widgets] def renderSplit(
      direction: Direction,
      items: Seq[LayoutItem],
      spacing: Int,
      flex: Flex,
      area: Rect,
      buffer: Buffer,
  ): Unit =
    val segments = Layout(direction, items.map(_.constraint), spacing, flex).split(area)
    items.zip(segments).foreach { (item, segment) =>
      if !segment.isEmpty then item.widget.render(segment, buffer)
    }

/** Lays its items out left-to-right using the core constraint solver and renders each into its segment. Measurement
  * uses the identical horizontal allocation. A zero-width child contributes zero (render skips it); any visible
  * unmeasurable child makes the row unmeasurable.
  */
final case class Row(items: Seq[LayoutItem], spacing: Int = 0, flex: Flex = Flex.Start) extends Widget with Measured:
  def render(area: Rect, buffer: Buffer): Unit =
    LayoutItem.renderSplit(Direction.Horizontal, items, spacing, flex, area, buffer)

  override def heightAt(width: Int): Option[Int] =
    val segments = Layout(Direction.Horizontal, items.map(_.constraint), spacing, flex)
      .split(Rect(0, 0, math.max(0, width), 1))
    items.zip(segments).foldLeft(Option(0)) { case (height, (item, segment)) =>
      val childHeight =
        if segment.width == 0 then Some(0)
        else
          item.widget match
            case measured: Measured => measured.heightAt(segment.width)
            case _                  => None
      for current <- height; child <- childHeight yield math.max(current, child)
    }
