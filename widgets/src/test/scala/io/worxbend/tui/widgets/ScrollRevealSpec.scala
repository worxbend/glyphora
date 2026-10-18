package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Style, Widget}
import io.worxbend.tui.testsupport.BufferAssertions.rendered

import org.scalatest.funsuite.AnyFunSuite

final class ScrollRevealSpec extends AnyFunSuite:

  private val rows: Widget =
    (area, buffer) => (0 until area.height).foreach(y => buffer.setString(area.x, area.y + y, s"r$y", Style.Default))

  /** A state that has rendered once: a 3-row viewport over 10 rows of content. */
  private def measured(): ScrollViewState =
    val state = ScrollViewState()
    val _     = rendered(ScrollView(rows, contentHeight = 10), state, 6, 3)
    state

  test("a range below the window scrolls just far enough to show its last row"):
    val state = measured()
    state.reveal(5, 1)
    assert(state.offset == 3)

  test("a range above the window scrolls so its first row is the top one"):
    val state = measured()
    state.scrollTo(6)
    state.reveal(2, 2)
    assert(state.offset == 2)

  test("a range already in view leaves the offset alone"):
    val state = measured()
    state.scrollTo(2)
    state.reveal(3, 2)
    assert(state.offset == 2)

  test("a range taller than the viewport shows its top, and the result is clamped to the content"):
    val state = measured()
    state.reveal(4, 5)
    assert(state.offset == 4)
    state.reveal(9, 4)
    assert(state.offset == 7)

  test("a range taller than the viewport that is already partly in view stays where it is"):
    val state = measured()
    state.scrollTo(3)
    state.reveal(0, 10)
    assert(state.offset == 3)
