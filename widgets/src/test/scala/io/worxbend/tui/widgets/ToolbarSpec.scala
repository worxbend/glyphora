package io.worxbend.tui.widgets

import io.worxbend.tui.core.Rect
import io.worxbend.tui.testsupport.BufferAssertions.{line, rendered}

import org.scalatest.funsuite.AnyFunSuite

final class ToolbarSpec extends AnyFunSuite:

  private val buttons = Seq("New", "Open", "Save", "Quit").map(Button(_))

  test("items sit left to right at their natural widths, a gap apart"):
    assert(line(rendered(Toolbar(buttons), 40, 1), 0).stripTrailing == "[ New ] [ Open ] [ Save ] [ Quit ]")

  test("a divider is drawn in every gap"):
    val group = Toolbar(buttons.take(3), gap = 0, divider = Some("│"))
    assert(line(rendered(group, 40, 1), 0).stripTrailing == "[ New ]│[ Open ]│[ Save ]")
    assert(group.widthAt(1).contains(7 + 1 + 8 + 1 + 8))

  test("items that do not fit are hidden behind » and the window follows the focused item"):
    val bar           = Toolbar(buttons)
    assert(line(rendered(bar, 20, 1), 0) == "[ New ] [ Open ]   »")
    assert(bar.window(20) == (0, 2))
    val focusedLast   = Toolbar(buttons, focus = Some(3))
    assert(line(rendered(focusedLast, 20, 1), 0) == "« [ Save ] [ Quit ] ")
    assert(focusedLast.window(20) == (2, 4))
    val focusedMiddle = Toolbar(buttons, focus = Some(2))
    assert(line(rendered(focusedMiddle, 20, 1), 0) == "« [ Open ] [ Save ]»")

  test("placements report where each visible item went, for hit testing"):
    val bar = Toolbar(buttons, focus = Some(3))
    assert(
      bar.placements(Rect(5, 2, 20, 1)).map((index, rect) => (index, rect.x, rect.width)) == Seq((2, 7, 8), (3, 16, 8))
    )

  test("an empty area draws nothing"):
    val buffer = rendered(Toolbar(buttons), 4, 1)
    Toolbar(buttons).render(Rect(0, 0, 0, 1), buffer)

  test("hidden lists the items the window leaves out, before and after it"):
    assert(Toolbar(buttons).hidden(20) == Seq(2, 3))
    assert(Toolbar(buttons, focus = Some(3)).hidden(20) == Seq(0, 1))
    assert(Toolbar(buttons, focus = Some(2)).hidden(20) == Seq(0, 3))
    assert(Toolbar(buttons).hidden(40).isEmpty)

  test("ToolbarState opens, closes and toggles its overflow menu"):
    val state = ToolbarState()
    assert(!state.open)
    state.toggle()
    assert(state.open)
    state.close()
    assert(!state.open)
