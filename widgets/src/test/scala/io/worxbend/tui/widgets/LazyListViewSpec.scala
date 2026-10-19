package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Modifiers, Rect, Style, Widget}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable

final class LazyListViewSpec extends AnyFunSuite:

  /** A list whose items say their index, recording every index it was asked to draw. */
  private final class Probe(count: Int, itemHeight: Int = 1, available: Int => Boolean = _ => true):
    val asked: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty
    val widget: LazyListView            =
      LazyListView(
        count,
        itemHeight,
        i =>
          asked += i
          Option.when(available(i))(Paragraph(io.worxbend.tui.core.Text.raw(s"item $i")): Widget),
      )

  test("only the items the viewport shows are drawn"):
    val probe  = Probe(100000)
    val buffer = rendered(probe.widget, LazyListState(), 10, 3)
    assert(trimmedLines(buffer) == Seq("item 0", "item 1", "item 2"))
    assert(probe.asked == Seq(0, 1, 2))

  test("the offset scrolls whole items and is clamped to the last page"):
    val state = LazyListState(offset = 99)
    val probe = Probe(5)
    assert(trimmedLines(rendered(probe.widget, state, 10, 3)) == Seq("item 2", "item 3", "item 4"))
    assert(state.offset == 2)

  test("the selection is kept on screen and drawn in the highlight style"):
    val state  = LazyListState(selected = Some(7))
    val probe  = Probe(20)
    val buffer = rendered(probe.widget, state, 10, 3)
    assert(trimmedLines(buffer) == Seq("item 5", "item 6", "item 7"))
    assert(buffer.get(0, 2).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(0, 1).style.modifiers.hasAny(Modifiers.Reverse))

  test("a multi-row item takes itemHeight rows and a partial last item is clipped to the area"):
    val probe  = Probe(10, itemHeight = 2)
    val buffer = rendered(probe.widget, LazyListState(), 10, 3)
    assert(trimmedLines(buffer).head == "item 0")
    assert(trimmedLines(buffer)(2) == "item 1")
    assert(probe.asked == Seq(0, 1))

  test("inside a taller area clipped by its buffer only the clipped window is drawn"):
    // a scroll view hands its content the whole content rect but a buffer covering only the window
    val probe  = Probe(1000)
    val window = Buffer(Rect(0, 500, 10, 3))
    probe.widget.render(Rect(0, 0, 10, 1000), window, LazyListState())
    assert(probe.asked == Seq(500, 501, 502))

  test("an item that was not built is left blank and recorded as missing"):
    val state  = LazyListState()
    val probe  = Probe(10, available = _ < 2)
    val buffer = rendered(probe.widget, state, 10, 3)
    assert(trimmedLines(buffer) == Seq("item 0", "item 1", ""))
    assert(state.missedItems)
    val _      = rendered(Probe(10).widget, state, 10, 3)
    assert(!state.missedItems)

  test("the build window covers the viewport plus one item of overscan either side"):
    val state = LazyListState(offset = 10)
    val _     = rendered(Probe(100).widget, state, 10, 4)
    assert(state.buildWindow(count = 100, fallbackRows = 24, itemHeight = 1) == (9 until 15))

  test("before the first render the build window falls back to the given rows"):
    assert(LazyListState().buildWindow(count = 100000, fallbackRows = 24, itemHeight = 1) == (0 until 25))
    assert(LazyListState().buildWindow(count = 3, fallbackRows = 24, itemHeight = 1) == (0 until 3))

  test("the build window follows a selection moved past the last rendered page"):
    val state = LazyListState()
    val _     = rendered(Probe(100).widget, state, 10, 4)
    state.selectBy(100, 10)
    assert(state.buildWindow(count = 100, fallbackRows = 24, itemHeight = 1) == (6 until 12))

  test("selection moves are clamped to the item count and raise the version"):
    val state  = LazyListState()
    val before = state.version
    state.selectNext(3)
    assert(state.selected.contains(0))
    state.selectLast(3)
    state.selectNext(3)
    assert(state.selected.contains(2))
    state.selectFirst(3)
    state.selectPrevious(3)
    assert(state.selected.contains(0))
    assert(state.version > before)

  test("measures as count times item height, and an empty area draws nothing"):
    val widget = Probe(7, itemHeight = 3).widget
    assert(widget.heightAt(10).contains(21))
    val buffer = Buffer(Rect(0, 0, 5, 1))
    widget.render(Rect(0, 0, 0, 0), buffer, LazyListState())
    assert(trimmedLines(buffer) == Seq(""))

  test("a highlight style override replaces the reversed default"):
    val state  = LazyListState(selected = Some(0))
    val widget = Probe(3).widget.copy(highlightStyle = Style.Default.bold)
    val buffer = rendered(widget, state, 10, 2)
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Bold))
