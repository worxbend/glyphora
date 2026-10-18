package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

final class CrossAlignSpec extends AnyFunSuite:

  private val columnSegment = Rect(0, 4, 30, 1)

  test("Stretch keeps the whole cross axis whatever the content measures"):
    assert(CrossAlign.Stretch.place(columnSegment, Direction.Vertical, Some(10)) == columnSegment)

  test("an unmeasured child stretches under every mode, never collapses to nothing"):
    CrossAlign.values.foreach(mode => assert(mode.place(columnSegment, Direction.Vertical, None) == columnSegment))

  test("Center puts a 10-wide child in a 30-wide column at x=10"):
    assert(CrossAlign.Center.place(columnSegment, Direction.Vertical, Some(10)) == Rect(10, 4, 10, 1))

  test("Start and End hug the near and far edges"):
    assert(CrossAlign.Start.place(columnSegment, Direction.Vertical, Some(10)) == Rect(0, 4, 10, 1))
    assert(CrossAlign.End.place(columnSegment, Direction.Vertical, Some(10)) == Rect(20, 4, 10, 1))

  test("in a row the cross axis is the height"):
    val rowSegment = Rect(5, 0, 8, 6)
    assert(CrossAlign.Center.place(rowSegment, Direction.Horizontal, Some(2)) == Rect(5, 2, 8, 2))
    assert(CrossAlign.End.place(rowSegment, Direction.Horizontal, Some(2)) == Rect(5, 4, 8, 2))

  test("a child measuring wider than the segment is clipped to it"):
    assert(CrossAlign.Center.place(columnSegment, Direction.Vertical, Some(50)) == columnSegment)
    assert(CrossAlign.End.place(columnSegment, Direction.Vertical, Some(-3)) == Rect(30, 4, 0, 1))

  test("a layout carries a default cross alignment and applies it in the aligned split"):
    val layout             = Layout.vertical(1, 1).withCrossAlign(CrossAlign.End)
    assert(layout.crossAlign == CrossAlign.End)
    assert(Layout.vertical(1).crossAlign == CrossAlign.Stretch)
    val Seq(first, second) =
      layout.split(Rect(0, 0, 30, 2), _ => None, index => Option.when(index == 0)(10)): @unchecked
    assert(first == Rect(20, 0, 10, 1))
    assert(second == Rect(0, 1, 30, 1))
    assert(layout.toString.endsWith("crossAlign=End)"))
