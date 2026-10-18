package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Modifiers, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}

import org.scalatest.funsuite.AnyFunSuite

final class RangeSliderSpec extends AnyFunSuite:

  test("two thumbs bracket a filled stretch of the track"):
    val state = RangeSliderState(SliderRange.of(0, 100, 10), 20, 80)
    assert(lines(rendered(RangeSlider(), state, 12, 1)) == Seq("├──●━━━━●──┤"))

  test("the focused thumb wears the focus style and Tab-style switching moves it"):
    val state  = RangeSliderState(SliderRange.of(0, 100, 10), 20, 80)
    val buffer = rendered(RangeSlider(), state, 12, 1)
    assert(buffer.get(3, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(8, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(state.focusNext())
    assert(!state.focusNext())
    assert(state.thumb == RangeThumb.High)
    assert(rendered(RangeSlider(), state, 12, 1).get(8, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(state.focusPrevious())
    assert(!state.focusPrevious())

  test("the thumbs cannot cross: each stops where the other one is"):
    val state = RangeSliderState(SliderRange.of(0, 100, 10), 40, 60)
    state.step(5)
    assert((state.low, state.high) == (60, 60))
    state.focusNext()
    state.step(-3)
    assert((state.low, state.high) == (60, 60))

  test("a minimum gap keeps the thumbs that far apart"):
    val state = RangeSliderState(SliderRange.of(0, 100, 10), 40, 60, minGap = 15)
    state.step(1)
    assert((state.low, state.high) == (45, 60))
    state.toMin()
    assert(state.low == 0)
    state.focusNext()
    state.toMin()
    assert(state.high == 15)

  test("the constructor clamps an out-of-range or inverted pair"):
    val state   = RangeSliderState(SliderRange.of(0, 100), 90, -10, minGap = 1000)
    assert(state.low == 0)
    assert(state.high == 100)
    val ordered = RangeSliderState(SliderRange.of(0, 100), 70, 30)
    assert((ordered.low, ordered.high) == (30, 70))

  test("a narrow or empty area writes nothing outside itself"):
    for width <- 0 to 4 do
      val buffer   = Buffer(Rect(0, 0, 8, 3))
      val sentinel = Cell("~", Style.Default)
      buffer.fill(buffer.area, sentinel)
      val area     = Rect(1, 1, width, 1)
      RangeSlider().render(area, buffer, RangeSliderState(SliderRange.Percent, 10, 90))
      for
        y <- 0 until 3
        x <- 0 until 8
        if !area.contains(x, y)
      do assert(buffer.get(x, y) == sentinel)
