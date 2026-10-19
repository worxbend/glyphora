package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect}

import org.scalatest.funsuite.AnyFunSuite

/** Under soft wrap, Up and Down move by the rows the reader sees, and the wrap layout is recomputed only for lines an
  * edit touched.
  */
final class TextAreaWrapMotionSpec extends AnyFunSuite:

  private val wrapping = TextArea(options = TextAreaOptions(wrap = true))

  private def paint(state: TextAreaState, width: Int = 10, height: Int = 6): Unit =
    val buffer = Buffer(Rect(0, 0, width, height))
    wrapping.render(buffer.area, buffer, state)

  test("Down moves to the next wrapped row of the same line, keeping the display column"):
    // "aaaa bbbb " | "cccc dddd " | "eeee" at width 10
    val state = TextAreaState("aaaa bbbb cccc dddd eeee")
    state.moveTo(TextPosition(0, 2))
    paint(state)
    state.moveDown()
    assert(state.cursor == (0, 12))
    state.moveDown()
    assert(state.cursor == (0, 22))
    state.moveUp()
    assert(state.cursor == (0, 12))

  test("Down from a line's last row goes to the next line's first row, Up comes back to the last row"):
    val state = TextAreaState("aaaa bbbb cccc\nxy")
    state.moveTo(TextPosition(0, 11))
    paint(state)
    state.moveDown()
    assert(state.cursor == (1, 1))
    state.moveUp()
    assert(state.cursor == (0, 11))
    state.moveUp()
    assert(state.cursor == (0, 1))
    state.moveUp() // already on the first row: nowhere to go
    assert(state.cursor == (0, 1))

  test("a column past a shorter row's end clamps onto that row, not onto the row after it"):
    // "aaaa " | "bbbbbbbbbb" | "c" at width 10
    val state = TextAreaState("aaaa bbbbbbbbbbc")
    state.moveTo(TextPosition(0, 13))
    paint(state)
    state.moveUp()
    assert(
      state.cursor == (0, 4),
      "the last cluster of the short first row, not its end, which is the next row's start",
    )

  test("wide graphemes: the column is display columns, never half a CJK character"):
    // "你好世界你" | "好世界" at width 10
    val state = TextAreaState("你好世界你好世界")
    state.moveTo(TextPosition(0, 1))
    paint(state)
    state.moveDown()
    assert(state.cursor == (0, 6))

  test("without wrap, or before the first render, Up and Down still move by logical line"):
    val unwrapped = TextAreaState("aaaa bbbb cccc dddd eeee\nxy")
    unwrapped.moveTo(TextPosition(0, 2))
    val buffer    = Buffer(Rect(0, 0, 10, 4))
    TextArea().render(buffer.area, buffer, unwrapped)
    unwrapped.moveDown()
    assert(unwrapped.cursor == (1, 2))
    val fresh     = TextAreaState("aaaa bbbb cccc dddd eeee\nxy")
    fresh.moveTo(TextPosition(0, 2))
    fresh.moveDown()
    assert(fresh.cursor == (1, 2))

  test("Shift+Down under wrap selects to the row below"):
    val state = TextAreaState("aaaa bbbb cccc dddd eeee")
    state.moveTo(TextPosition(0, 0))
    paint(state)
    state.moveDown(select = true)
    assert(state.selection.contains((TextPosition(0, 0), TextPosition(0, 10))))

  test("an edit re-wraps only the line it touched"):
    val state = TextAreaState(Vector.fill(200)("lorem ipsum dolor sit amet consectetur").mkString("\n"))
    paint(state, height = 4)
    val first = state.wrapComputations
    paint(state, height = 4)
    assert(state.wrapComputations == first, "a repaint with nothing changed wraps nothing")
    state.moveTo(TextPosition(100, 3))
    state.insert("x")
    paint(state, height = 4)
    assert(state.wrapComputations == first + 1)
    state.newline()
    paint(state, height = 4)
    assert(state.wrapComputations == first + 3, "a split line is two new lines; the 99 shifted below it are not")
    paint(state, width = 12, height = 4)
    assert(state.wrapComputations > first + 200, "a new width re-wraps everything")
