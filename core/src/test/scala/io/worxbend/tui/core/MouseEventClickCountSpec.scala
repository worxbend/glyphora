package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

/** `clickCount` is synthesised by whoever routes mouse events; the decoder and every hand-built event carry a single
  * click, so the four-argument construction written before the field existed still means what it meant.
  */
final class MouseEventClickCountSpec extends AnyFunSuite:

  test("a mouse event built without a click count is a single click"):
    val event = MouseEvent(Position(1, 2), MouseEventKind.Down, KeyModifiers.None)
    assert(event.clickCount == 1)
    assert(MouseEvent(Position(1, 2), MouseEventKind.Down, KeyModifiers.None, MouseButton.Right).clickCount == 1)

  test("a double click is the same event with its count raised"):
    val single = MouseEvent(Position(1, 2), MouseEventKind.Down, KeyModifiers.None)
    val double = single.copy(clickCount = 2)
    assert(double.clickCount == 2)
    assert(double.position == single.position && double.kind == single.kind && double.button == single.button)
