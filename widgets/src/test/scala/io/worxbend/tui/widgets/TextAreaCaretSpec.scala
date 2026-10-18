package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Modifiers, Position, Rect}

import org.scalatest.funsuite.AnyFunSuite

/** [[TextArea.caretAt]]: the frame cell of a multi-line editor's caret, through both of its scroll offsets. As in
  * `TextInputCaretSpec`, every case renders first and checks the reported cell is the one the painted cursor landed in.
  */
final class TextAreaCaretSpec extends AnyFunSuite:

  private val editor = TextArea()

  private def paintedCursor(buffer: Buffer, area: Rect): Option[Position] =
    val cells = for
      y <- area.y until area.bottom
      x <- area.x until area.right
      if buffer.get(x, y).style.modifiers.hasAny(Modifiers.Reverse)
    yield Position(x, y)
    cells.headOption

  private def caretAgreesWithPaint(state: TextAreaState, area: Rect): Option[Position] =
    val buffer = Buffer(Rect(0, 0, 40, 10))
    editor.render(area, buffer, state)
    val caret  = editor.caretAt(area, state)
    assert(caret == paintedCursor(buffer, area), "the reported caret and the painted cursor disagree")
    caret

  test("line 3, column 2 maps to the third row and third column of the area"):
    val state = TextAreaState("one\ntwo\nthree")
    state.moveHome()
    state.moveRight()
    state.moveRight()
    assert(state.cursor == (2, 2))
    assert(caretAgreesWithPaint(state, Rect(4, 1, 20, 5)) == Some(Position(6, 3)))

  test("a cursor past the visible rows is reported where the scrolled editor paints it"):
    val state = TextAreaState("a\nb\nc\nd\ne") // the constructor leaves the cursor on the last line
    assert(caretAgreesWithPaint(state, Rect(0, 0, 10, 2)) == Some(Position(1, 1)))

  test("a long line scrolls horizontally and the caret follows"):
    val state = TextAreaState("abcdefgh")
    assert(caretAgreesWithPaint(state, Rect(0, 0, 4, 3)) == Some(Position(3, 0)))

  test("wide characters before the cursor count two columns each"):
    val state = TextAreaState("東京x")
    state.moveLeft()
    assert(caretAgreesWithPaint(state, Rect(0, 0, 10, 1)) == Some(Position(4, 0)))

  test("an empty area has no caret cell"):
    assert(editor.caretAt(Rect(0, 0, 10, 0), TextAreaState("ab")).isEmpty)
