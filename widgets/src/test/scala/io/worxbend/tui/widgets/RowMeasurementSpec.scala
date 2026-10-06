package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Constraint, Flex, Layout, Direction, Measured, Rect, Text, Widget}

import org.scalatest.funsuite.AnyFunSuite

final class RowMeasurementSpec extends AnyFunSuite:

  private def height(row: Row, width: Int): Option[Int] = (row: Widget) match
    case measured: Measured => measured.heightAt(width)
    case _                  => None

  test("a row measures wrapped children at the widths it actually renders") {
    val child  = Paragraph(Text.raw("abcdefghijklmnopqrst"), overflow = Overflow.Wrap)
    val row    = Row(Seq.fill(2)(LayoutItem(Constraint.Fill(1), child)))
    assert(height(row, 20).contains(2))
    val buffer = Buffer(Rect(0, 0, 20, 2))
    row.render(buffer.area, buffer)
    assert(buffer.get(0, 1).symbol == "k")
    assert(buffer.get(10, 1).symbol == "k")
  }

  test("measurement shares rendering constraints spacing flex and rounding") {
    Seq(Flex.Start, Flex.End, Flex.Center, Flex.SpaceBetween).foreach { flex =>
      val widths   = scala.collection.mutable.ArrayBuffer.empty[Int]
      val child    = new Widget with Measured:
        def render(area: Rect, buffer: Buffer): Unit   = ()
        override def heightAt(width: Int): Option[Int] =
          widths += width
          Some(width)
      val items    = Seq(
        LayoutItem(Constraint.Length(3), child),
        LayoutItem(Constraint.Fill(2), child),
        LayoutItem(Constraint.Fill(1), child),
      )
      val row      = Row(items, spacing = 2, flex = flex)
      val expected = Layout(Direction.Horizontal, items.map(_.constraint), 2, flex)
        .split(Rect(0, 0, 18, 1))
        .map(_.width)
        .filter(_ > 0)
      assert(height(row, 18).contains(expected.max))
      assert(widths.toSeq == expected)
    }
  }

  test("zero-width children contribute zero without being measured") {
    val unknown = new Widget:
      def render(area: Rect, buffer: Buffer): Unit = ()
    val row     =
      Row(Seq(LayoutItem(Constraint.Length(5), Paragraph(Text.raw("x"))), LayoutItem(Constraint.Fill(1), unknown)))
    assert(height(row, 5).contains(1))
    assert(height(row, 0).contains(0))
    assert(height(row, -1).contains(0))
    assert(height(Row(Seq.empty), 10).contains(0))
    assert(height(row, 6).isEmpty)
  }
