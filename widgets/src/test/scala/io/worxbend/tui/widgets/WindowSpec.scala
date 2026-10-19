package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Position, Rect}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}

import org.scalatest.funsuite.AnyFunSuite

final class WindowSpec extends AnyFunSuite:

  test("the frame carries the title and a resize grip in its bottom-right corner"):
    val buffer = rendered(Window("Notes"), WindowStack().window(0, 0, 12, 4), 12, 4)
    assert(
      lines(buffer) == Seq(
        "┌ Notes ───┐",
        "│          │",
        "│          │",
        "└──────────◢",
      )
    )

  test("arrange mode is announced in the title"):
    val state = WindowStack().window(0, 0, 20, 3)
    state.mode = WindowMode.Moving
    assert(lines(rendered(Window("Notes"), state, 20, 3)).head.startsWith("┌ Notes · move "))
    state.mode = WindowMode.Resizing
    assert(lines(rendered(Window("Notes"), state, 20, 3)).head.startsWith("┌ Notes · resize "))

  test("moving and resizing change the rect; a resize never shrinks below the minimum"):
    val state = WindowStack().window(2, 3, 20, 6)
    state.moveBy(5, -1)
    assert(state.rect == Rect(7, 2, 20, 6))
    state.resizeBy(-30, -30)
    assert(state.rect == Rect(7, 2, WindowState.MinWidth, WindowState.MinHeight))

  test("a drag moves (or resizes) by how far the pointer travelled since the press"):
    val state = WindowStack().window(2, 3, 20, 6)
    state.startDrag(Position(10, 3), resizing = false)
    state.dragTo(Position(15, 5))
    assert(state.rect == Rect(7, 5, 20, 6))
    state.dragTo(Position(12, 3))
    assert(state.rect == Rect(4, 3, 20, 6))
    state.endDrag()
    state.dragTo(Position(40, 40))
    assert(state.rect == Rect(4, 3, 20, 6))
    state.startDrag(Position(23, 8), resizing = true)
    state.dragTo(Position(25, 10))
    assert(state.rect == Rect(4, 3, 22, 8))

  test("raise puts a window above every window of its stack raised before it"):
    val desk = WindowStack()
    val a    = desk.window(0, 0, 10, 4)
    val b    = desk.window(0, 0, 10, 4)
    b.raise()
    a.raise()
    assert(a.z > b.z)

  test("each stack keeps its own order, so one stack's raises never reach another's"):
    val first  = WindowStack()
    val second = WindowStack()
    first.window(0, 0, 10, 4).raise()
    first.window(0, 0, 10, 4).raise()
    val lone   = second.window(0, 0, 10, 4)
    lone.raise()
    assert(lone.z == 1L)

  test("inner is the area inside the frame"):
    assert(Window("x").inner(Rect(3, 2, 10, 5)) == Rect(4, 3, 8, 3))
