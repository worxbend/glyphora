package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Constraint, GridLayout, GridPlacement, Measured, Style, Text, Widget}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}

import org.scalatest.funsuite.AnyFunSuite

final class GridFlowSpec extends AnyFunSuite:

  private def fill(glyph: String): Widget = (area, buffer) =>
    for y <- area.y until area.bottom; x <- area.x until area.right do buffer.setString(x, y, glyph, Style.Default)

  private def chip(label: String): Widget = Paragraph(Text.raw(label))

  private def heightOf(widget: Widget, width: Int): Option[Int] =
    widget match
      case measured: Measured => measured.heightAt(width)
      case _                  => None

  // ---- Grid ----

  private val twoByTwo =
    GridLayout(Seq(Constraint.fill, Constraint.fill), Seq(Constraint.Length(1), Constraint.fill)).withGap(1, 0)

  test("a grid paints each cell into its placement, spans included"):
    val grid = Grid(
      twoByTwo,
      Seq(
        GridEntry(GridPlacement(0, 0, colSpan = 2), fill("T")),
        GridEntry(GridPlacement(1, 0), fill("a")),
        GridEntry(GridPlacement(1, 1), fill("b")),
      ),
    )
    assert(lines(rendered(grid, 7, 3)) == Seq("TTTTTTT", "aaa bbb", "aaa bbb"))

  test("Auto tracks size to the cells that sit in exactly one of them"):
    val layout = GridLayout(Seq(Constraint.Auto, Constraint.fill), Seq(Constraint.Auto, Constraint.fill))
    val grid   = Grid(
      layout,
      Seq(
        GridEntry(GridPlacement(0, 0), chip("ab")),
        GridEntry(GridPlacement(0, 1), fill("x")),
        GridEntry(GridPlacement(1, 0, colSpan = 2), fill("y")),
      ),
    )
    assert(lines(rendered(grid, 5, 3)) == Seq("abxxx", "yyyyy", "yyyyy"))

  test("a grid measures the rows it can: lengths, Auto rows from their cells, gaps between"):
    val layout = GridLayout(Seq(Constraint.fill), Seq(Constraint.Length(2), Constraint.Auto)).withGap(0, 1)
    val grid   = Grid(layout, Seq(GridEntry(GridPlacement(1, 0), chip("a\nb\nc"))))
    assert(heightOf(grid, 10).contains(2 + 1 + 3))
    assert(heightOf(Grid(twoByTwo, Seq.empty), 10).isEmpty) // a fill row cannot say
    assert(heightOf(Grid(GridLayout(Seq(Constraint.fill), Seq(Constraint.Auto)), Seq.empty), 10).isEmpty)

  // ---- Flow ----

  test("four 8-wide chips wrap onto two rows in a 20-wide area"):
    val flow = Flow(Seq.fill(4)(chip("[chip-1]")), spacing = 1)
    assert(lines(rendered(flow, 20, 2)) == Seq("[chip-1] [chip-1]   ", "[chip-1] [chip-1]   "))
    assert(heightOf(flow, 20).contains(2))
    assert(heightOf(flow, 40).contains(1))

  test("a wide-character chip counts its display width, not its length"):
    // "漢字漢字" is four characters and eight columns: two of them and a 6-column chip do not fit in 20
    val flow = Flow(Seq(chip("漢字漢字"), chip("漢字漢字"), chip("[abc]")), spacing = 1)
    assert(heightOf(flow, 20).contains(2))
    assert(lines(rendered(flow, 20, 2))(1).startsWith("[abc]"))

  test("row spacing separates the wrapped lines; a line is as tall as its tallest item"):
    val flow = Flow(Seq(chip("aa\naa"), chip("bb"), chip("cc")), spacing = 1, rowSpacing = 1)
    assert(heightOf(flow, 6).contains(2 + 1 + 1))
    assert(lines(rendered(flow, 6, 4)) == Seq("aa bb ", "aa    ", "      ", "cc    "))

  test("an item that cannot say its width takes a line of its own"):
    val flow = Flow(Seq(chip("a"), fill("?"), chip("b")), spacing = 1)
    assert(lines(rendered(flow, 4, 3)) == Seq("a   ", "????", "b   "))

  test("an item wider than the area is clipped on a line of its own"):
    val flow = Flow(Seq(chip("abcdefgh"), chip("z")), spacing = 1)
    assert(lines(rendered(flow, 4, 2)) == Seq("abcd", "z   "))
    assert(rendered(Flow(Seq(chip("a"))), 0, 0).area.isEmpty)

  test("a flow is as wide as its items laid on one line"):
    val flow = Flow(Seq(chip("abc"), chip("de")), spacing = 2)
    assert(flow.widthAt(1).contains(7))
    assert(Flow(Seq(fill("?"))).widthAt(1).isEmpty)
