package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Modifiers, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{line, rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class SliderSpec extends AnyFunSuite:

  test("the track runs between brackets with the knob positioned by value"):
    assert(trimmedLines(rendered(Slider(50, SliderRange.of(0, 100)), 11, 1)) == Seq("├────●────┤"))

  test("the minimum puts the knob on the first track column and the maximum on the last"):
    assert(trimmedLines(rendered(Slider(0, SliderRange.of(0, 100)), 11, 1)) == Seq("├●────────┤"))
    assert(trimmedLines(rendered(Slider(100, SliderRange.of(0, 100)), 11, 1)) == Seq("├────────●┤"))

  test("a value outside the range is clamped rather than drawn off the track"):
    assert(trimmedLines(rendered(Slider(-50, SliderRange.of(0, 100)), 11, 1)) == Seq("├●────────┤"))
    assert(trimmedLines(rendered(Slider(500, SliderRange.of(0, 100)), 11, 1)) == Seq("├────────●┤"))

  for (minimum, maximum, value, trackColumn) <- Seq(
      (-1500000000, 1500000000, -1499999998, 0),
      (-1500000000, 1500000000, 0, 1),
      (-1500000000, 1500000000, 1500000000, 2),
      (Int.MinValue, Int.MaxValue, Int.MinValue, 0),
      (Int.MinValue, Int.MaxValue, 0, 1),
      (Int.MinValue, Int.MaxValue, Int.MaxValue, 2),
      (-1500000000, 1500000000, Int.MinValue, 0),
      (-1500000000, 1500000000, Int.MaxValue, 2),
      (Int.MinValue, Int.MinValue, Int.MaxValue, 0),
      (Int.MaxValue, Int.MaxValue, Int.MinValue, 0),
    )
  do
    test(s"range $minimum..$maximum places $value on the track without overwriting neighboring cells"):
      val area     = Rect(2, 1, 5, 1)
      val buffer   = Buffer(Rect(0, 0, 10, 3))
      val sentinel = Cell("~", Style.Default.reverse)
      buffer.fill(buffer.area, sentinel)
      Slider(value, SliderRange.of(minimum, maximum)).render(area, buffer)
      for
        y <- 0 until buffer.area.height
        x <- 0 until buffer.area.width
        if !area.contains(x, y)
      do assert(buffer.get(x, y) == sentinel, s"outside write at ($x, $y)")
      assert(buffer.get(area.x, area.y).symbol == "├")
      assert(buffer.get(area.right - 1, area.y).symbol == "┤")
      assert(buffer.get(area.x + 1 + trackColumn, area.y).symbol == "●")
      assert((area.x until area.right).count(x => buffer.get(x, area.y).symbol == "●") == 1)

  test("the full Int range stays contained when the track has one column or no room to paint"):
    for
      width  <- 0 to 3
      height <- 0 to 1
      value  <- Seq(Int.MinValue, 0, Int.MaxValue)
    do
      val area     = Rect(2, 1, width, height)
      val buffer   = Buffer(Rect(0, 0, 8, 3))
      val sentinel = Cell("~", Style.Default.reverse)
      buffer.fill(buffer.area, sentinel)
      Slider(value, SliderRange.of(Int.MinValue, Int.MaxValue)).render(area, buffer)
      for
        y <- 0 until buffer.area.height
        x <- 0 until buffer.area.width
      do
        val expected =
          if width == 3 && height == 1 && area.contains(x, y) then Seq("├", "●", "┤")(x - area.x)
          else sentinel.symbol
        assert(buffer.get(x, y).symbol == expected)

  test("an empty range puts the knob at the start instead of dividing by zero"):
    assert(trimmedLines(rendered(Slider(7, SliderRange.of(7, 7)), 11, 1)) == Seq("├●────────┤"))

  test("SliderRange orders the bounds it is given and refuses a step that cannot move"):
    // a caller computing bounds from data cannot know which end came out larger, so the range takes either order
    assert(SliderRange.of(100, 0) == SliderRange.of(0, 100))
    // a zero step would make the DSL slider swallow Left/Right and change nothing; a negative one would reverse them
    assert(intercept[IllegalArgumentException](SliderRange.of(0, 100, 0)).getMessage.contains("at least 1"))
    assert(intercept[IllegalArgumentException](SliderRange.of(0, 100, -5)).getMessage.contains("at least 1"))

  test("the default range is a percentage in steps of five"):
    assert(SliderRange.Percent == SliderRange.of(0, 100, 5))

  test("nothing is drawn below the three columns a slider needs"):
    assert(line(rendered(Slider(50), 0, 1), 0) == "")
    assert(line(rendered(Slider(50), 1, 1), 0) == " ")
    assert(line(rendered(Slider(50), 2, 1), 0) == "  ")

  test("at exactly three columns the one-column track leaves the knob against the left bracket"):
    // `trackWidth - 1` is 0, so every value maps to the same position: the single track column, which at this width is
    // both the first and the last one
    assert(trimmedLines(rendered(Slider(0, SliderRange.of(0, 100)), 3, 1)) == Seq("├●┤"))
    assert(trimmedLines(rendered(Slider(100, SliderRange.of(0, 100)), 3, 1)) == Seq("├●┤"))

  test("the knob carries the knob style and the rest of the row the base style"):
    val buffer = rendered(Slider(0, SliderRange.of(0, 100)), 11, 1)
    assert(buffer.get(1, 0).style.modifiers.hasAny(Modifiers.Bold))  // the knob
    assert(!buffer.get(2, 0).style.modifiers.hasAny(Modifiers.Bold)) // the track beside it
    assert(!buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Bold)) // the bracket
