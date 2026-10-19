package io.worxbend.tui.dsl

import io.worxbend.tui.core.Direction
import io.worxbend.tui.testsupport.BufferAssertions.{line as bufferLine, rendered}

import org.scalatest.funsuite.AnyFunSuite

/** Before `scrollbar(...)` existed, showing a scroll position for content the DSL does not scroll itself meant
  * `widget(Scrollbar(...))` and a `io.worxbend.tui.widgets` import in the view. These tests pin the factory, the
  * builders, and the one-cell claim that keeps the bar beside the content rather than over it.
  */
final class ScrollbarElementSpec extends AnyFunSuite:

  private def column(element: Element, width: Int, height: Int): String =
    val buffer = rendered(element.widget, width, height)
    (0 until height).map(row => bufferLine(buffer, row).charAt(width - 1)).mkString

  test("a vertical bar draws down the right edge with the thumb at the top for position zero"):
    assert(column(scrollbar(8), 3, 4) == "██││")

  test("at moves the thumb down the track"):
    assert(column(scrollbar(8).thumbAt(4), 3, 4) == "││██")

  test("a position past the end pins the thumb rather than drawing it off the track"):
    assert(column(scrollbar(8).thumbAt(1000), 3, 4) == "││██")

  test("content that already fits draws only the track"):
    assert(column(scrollbar(3), 3, 4) == "││││")

  test("a horizontal bar draws along the bottom edge instead"):
    val buffer = rendered(scrollbar(8).horizontal.widget, 4, 3)
    assert(bufferLine(buffer, 2) == "██││")
    assert(bufferLine(buffer, 0).trim.isEmpty)

  test("vertical undoes a horizontal on an element built elsewhere"):
    assert(scrollbar(8).horizontal.vertical.options.orientation == Direction.Vertical)

  test("symbols replaces the two glyphs the bar is drawn from"):
    assert(column(scrollbar(8).symbols(".", "#"), 3, 4) == "##..")

  test("the claim is one cell across the short axis and fills the long one"):
    assert(scrollbar(8).claim == SizeClaim(Constraint.Fill(1), Constraint.Length(1)))
    assert(scrollbar(8).horizontal.claim == SizeClaim(Constraint.Length(1), Constraint.Fill(1)))

  test("an empty area paints nothing and does not throw"):
    assert(rendered(scrollbar(8).widget, 0, 0).area.isEmpty)
