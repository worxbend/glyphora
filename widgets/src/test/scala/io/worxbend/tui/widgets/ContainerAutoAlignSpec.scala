package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Constraint, CrossAlign, Measured, Rect, Style, Text, Widget}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}

import org.scalatest.funsuite.AnyFunSuite

/** `Row` and `Column` in 0.20.0: `Constraint.Auto` asks the child's own `Measured`, the cross axis follows a
  * [[CrossAlign]], and `Column` measures itself the way `Row` already did.
  */
final class ContainerAutoAlignSpec extends AnyFunSuite:

  /** A block of `#` that says how big it is on both axes. */
  private def box(width: Int, height: Int): Widget =
    new Widget with Measured:
      def render(area: Rect, buffer: Buffer): Unit   =
        for y <- area.y until area.bottom; x <- area.x until area.right do buffer.setString(x, y, "#", Style.Default)
      override def widthAt(height: Int): Option[Int] = Some(width)
      override def heightAt(width: Int): Option[Int] = Some(height)

  private val unmeasured: Widget = (area, buffer) =>
    for y <- area.y until area.bottom; x <- area.x until area.right do buffer.setString(x, y, "?", Style.Default)

  private def measuredHeight(widget: Widget, width: Int): Option[Int] =
    widget match
      case measured: Measured => measured.heightAt(width)
      case _                  => None

  private def measuredWidth(widget: Widget, height: Int): Option[Int] =
    widget match
      case measured: Measured => measured.widthAt(height)
      case _                  => None

  test("an Auto child of a column takes the rows its widget measures"):
    val column = Column(Seq(LayoutItem(Constraint.Auto, box(3, 2)), LayoutItem(Constraint.fill, unmeasured)))
    assert(lines(rendered(column, 3, 5)) == Seq("###", "###", "???", "???", "???"))

  test("an Auto child of a row takes the columns its widget measures; unmeasured Auto is a fill"):
    val row = Row(Seq(LayoutItem(Constraint.Auto, box(2, 1)), LayoutItem(Constraint.Auto, unmeasured)))
    assert(lines(rendered(row, 6, 1)) == Seq("##????"))

  test("a centred column paints a 10-wide child at x=10 in a 30-wide area"):
    val column = Column(Seq(LayoutItem(Constraint.Length(1), box(10, 1))), crossAlign = CrossAlign.Center)
    assert(lines(rendered(column, 30, 1)) == Seq(" " * 10 + "#" * 10 + " " * 10))

  test("an item's own alignment overrides the container's, and an unmeasured child still stretches"):
    val column = Column(
      Seq(
        LayoutItem(Constraint.Length(1), box(2, 1), align = Some(CrossAlign.End)),
        LayoutItem(Constraint.Length(1), box(2, 1)),
        LayoutItem(Constraint.Length(1), unmeasured, align = Some(CrossAlign.Center)),
      ),
      crossAlign = CrossAlign.Start,
    )
    assert(lines(rendered(column, 6, 3)) == Seq("    ##", "##    ", "??????"))

  test("a row aligns its children vertically"):
    val row = Row(
      Seq(LayoutItem(Constraint.Length(1), box(1, 1)), LayoutItem(Constraint.fill, box(1, 3))),
      crossAlign = CrossAlign.End,
    )
    assert(lines(rendered(row, 2, 3)) == Seq(" #", " #", "##"))

  test("a column measures the sum of its children, with a length as its own answer"):
    val column = Column(
      Seq(
        LayoutItem(Constraint.Length(2), unmeasured),
        LayoutItem(Constraint.Auto, box(4, 3)),
        LayoutItem(Constraint.fill, Paragraph(Text.raw("a\nb"))),
      ),
      spacing = 1,
    )
    assert(measuredHeight(column, 10).contains(2 + 3 + 2 + 2))
    assert(measuredHeight(Column(Seq(LayoutItem(Constraint.fill, unmeasured))), 10).isEmpty)
    assert(measuredHeight(Column(Seq.empty), 10).contains(0))

  test("widths: a column is as wide as its widest child, a row the sum of its children and gaps"):
    val column = Column(Seq(LayoutItem(Constraint.Length(1), box(4, 1)), LayoutItem(Constraint.Length(1), box(7, 1))))
    assert(measuredWidth(column, 2).contains(7))
    val row    =
      Row(Seq(LayoutItem(Constraint.Length(5), unmeasured), LayoutItem(Constraint.Auto, box(3, 1))), spacing = 2)
    assert(measuredWidth(row, 1).contains(10))
    assert(measuredWidth(Row(Seq(LayoutItem(Constraint.fill, unmeasured))), 1).isEmpty)
