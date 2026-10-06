package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect, Text}
import io.worxbend.tui.widgets.{Paragraph, ScrollViewState}

import org.scalatest.funsuite.AnyFunSuite

final class RowAllocationSpec extends AnyFunSuite:

  private def wrapped: Element = widget(Paragraph(Text.raw("abcdefghijklmnopqrst"), overflow = Overflow.Wrap))

  test("a row measures wrapping at the widths it actually allocates"):
    val node   = row(wrapped, wrapped)
    assert(node.intrinsicHeight(20).contains(2))
    val buffer = Buffer(Rect(0, 0, 20, 2))
    node.widget.render(buffer.area, buffer)
    assert(buffer.get(0, 1).symbol == "k")
    assert(buffer.get(10, 1).symbol == "k")

  test("gaps and horizontal constraints affect width, not measured child height"):
    assert(row(wrapped, wrapped).gap(2).intrinsicHeight(20).contains(3))
    assert(row(wrapped.length(4), wrapped.fill).gap(2).intrinsicHeight(20).contains(5))
    assert(row(wrapped.percent(25), wrapped.fill).intrinsicHeight(20).contains(4))
    assert(row(wrapped.length(4), wrapped.length(4)).spaceBetween.intrinsicHeight(20).contains(5))
    val nested = column(text("a").length(3), text("b").length(2)).length(5)
    assert(row(nested, wrapped.fill).intrinsicHeight(20).contains(5))

  test("zero-width children contribute zero and visible unmeasurable children remain unknown"):
    assert(row(spacer.length(0), wrapped).intrinsicHeight(20).contains(1))
    assert(row(spacer, wrapped).intrinsicHeight(20).isEmpty)
    assert(row(wrapped, wrapped).intrinsicHeight(0).contains(0))
    assert(row(spacer, wrapped).intrinsicHeight(0).contains(0))

  test("auto measured scrolling reaches the final wrapped row"):
    val state  = ScrollViewState()
    val node   = scrollView(row(wrapped, wrapped), state)
    val buffer = Buffer(Rect(0, 0, 21, 1))
    node.widget.render(buffer.area, buffer)
    state.last()
    val last   = Buffer(buffer.area)
    node.widget.render(last.area, last)
    assert(state.offset == 1)
    assert(last.get(0, 0).symbol == "k")
    assert(last.get(10, 0).symbol == "k")
