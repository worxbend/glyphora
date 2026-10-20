package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Constraint, CrossAlign, Direction, Flex, Layout, Measured, Rect, Widget}

/** One slot of a [[Row]] or [[Column]]: how much space the slot claims, what renders inside it, and — when set — where
  * it sits across the container's axis, overriding the container's own [[CrossAlign]].
  */
final case class LayoutItem(constraint: Constraint, widget: Widget, align: Option[CrossAlign] = None)

object LayoutItem:

  /** Splits `area` along `direction` by the items' constraints and renders each item into its own segment.
    *
    * The half of [[Row]] and [[Column]] that is the same in both: only the axis differs, so it is stated once here
    * rather than twice in two files that would then have to be kept in step. An `Auto` item is sized by its widget's
    * measurement along the axis; each segment is then placed across the axis by the item's alignment (the container's
    * when the item sets none), using the widget's cross-axis measurement and stretching when it has none. Segments that
    * come out empty are skipped, because a widget handed a zero-width rect can only guess at what its caller meant.
    */
  private[widgets] def renderSplit(
      direction: Direction,
      items: Seq[LayoutItem],
      spacing: Int,
      flex: Flex,
      crossAlign: CrossAlign,
      area: Rect,
      buffer: Buffer,
  ): Unit =
    val segments = split(direction, items, spacing, flex, area)
    items.zip(segments).foreach { (item, segment) =>
      val align  = item.align.getOrElse(crossAlign)
      val placed =
        if align == CrossAlign.Stretch then segment
        else align.place(segment, direction, crossSizeOf(item.widget, direction, segment))
      if !placed.isEmpty then item.widget.render(placed, buffer)
    }

  /** The segments `items` get along `direction` in `area`, with `Auto` items measured — shared by render and the
    * measurement [[Row.heightAt]] does, so the two allocate identically.
    */
  private[widgets] def split(
      direction: Direction,
      items: Seq[LayoutItem],
      spacing: Int,
      flex: Flex,
      area: Rect,
  ): Seq[Rect] =
    val along: Int => Option[Int] = index =>
      direction match
        case Direction.Vertical   => heightOf(items(index).widget, area.width)
        case Direction.Horizontal => widthOf(items(index).widget, area.height)
    Layout(direction, items.map(_.constraint), spacing, flex).split(area, along)

  /** What `widget` needs across `direction`, given the segment it was allotted along it. */
  private def crossSizeOf(widget: Widget, direction: Direction, segment: Rect): Option[Int] =
    direction match
      case Direction.Vertical   => widthOf(widget, segment.height)
      case Direction.Horizontal => heightOf(widget, segment.width)

  private[widgets] def heightOf(widget: Widget, width: Int): Option[Int] =
    widget match
      case measured: Measured => measured.heightAt(width)
      case _                  => None

  private[widgets] def widthOf(widget: Widget, height: Int): Option[Int] =
    widget match
      case measured: Measured => measured.widthAt(height)
      case _                  => None

  /** The cells a `Length` fixes, which is an item's own answer to "how big along the axis" whatever its widget says. */
  private[widgets] def lengthOf(constraint: Constraint): Option[Int] =
    constraint match
      case Constraint.Length(cells) => Some(math.max(0, cells))
      case _                        => None

  /** Every answer, or `None` when any one of them is missing — `Measured`'s "cannot say" is contagious. */
  private[widgets] def allOrNone(answers: Seq[Option[Int]]): Option[Seq[Int]] =
    Option.when(answers.forall(_.nonEmpty))(answers.flatten)

/** Lays its items out left-to-right using the core constraint solver and renders each into its segment.
  *
  * An item constrained `Constraint.Auto` gets the columns its widget measures through [[Measured.widthAt]]; each item
  * is placed vertically by its own `align` or by [[crossAlign]] (the default, `Stretch`, gives it the row's full
  * height).
  *
  * Measurement uses the identical horizontal allocation. A zero-width child contributes zero (render skips it); any
  * visible unmeasurable child makes the row unmeasurable. Its width is the sum of its items' — each a `Length` or its
  * widget's own measurement — plus the gaps.
  */
final case class Row(
    items: Seq[LayoutItem],
    spacing: Int = 0,
    flex: Flex = Flex.Start,
    crossAlign: CrossAlign = CrossAlign.Stretch,
) extends Widget
    with Measured:
  def render(area: Rect, buffer: Buffer): Unit =
    LayoutItem.renderSplit(Direction.Horizontal, items, spacing, flex, crossAlign, area, buffer)

  override def heightAt(width: Int): Option[Int] =
    def measuredAt(height: Int): Option[Int]     =
      val segments =
        LayoutItem.split(Direction.Horizontal, items, spacing, flex, Rect(0, 0, math.max(0, width), height))
      items.zip(segments).foldLeft(Option(0)) { case (currentHeight, (item, segment)) =>
        val childHeight = if segment.width == 0 then Some(0) else LayoutItem.heightOf(item.widget, segment.width)
        for current <- currentHeight; child <- childHeight yield math.max(current, child)
      }
    def hasAuto(constraint: Constraint): Boolean = constraint match
      case Constraint.Auto                => true
      case Constraint.Bounded(_, _, base) => hasAuto(base)
      case _                              => false
    val refine                                   = items.exists(item => hasAuto(item.constraint))
    // Only Auto widths depend on height. Refine those to a fixed point; cycles are unmeasurable.
    var candidate                                = 1
    var result                                   = measuredAt(candidate)
    var seen                                     = Set(candidate)
    var rounds                                   = 0
    while refine && result.isDefined && result.get != candidate && !seen.contains(result.get) && rounds < 16 do
      candidate = result.get
      seen += candidate
      result = measuredAt(candidate)
      rounds += 1
    if refine then result.filter(_ == candidate) else result

  override def widthAt(height: Int): Option[Int] =
    LayoutItem
      .allOrNone(
        items.map(item => LayoutItem.lengthOf(item.constraint).orElse(LayoutItem.widthOf(item.widget, height)))
      )
      .map(_.sum + math.max(0, spacing) * math.max(0, items.size - 1))
