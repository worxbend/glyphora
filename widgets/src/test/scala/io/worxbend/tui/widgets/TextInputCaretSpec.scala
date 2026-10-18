package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Modifiers, Position, Rect}

import org.scalatest.funsuite.AnyFunSuite

/** [[TextInput.caretAt]]: the buffer cell the hardware caret belongs on — the one the painted cursor lands in.
  *
  * The two answers come from the same scroll and width arithmetic, so every test here renders first and then checks
  * that the reported cell is the one carrying the reverse-video cursor. A caret that disagrees with the paint is the
  * bug this contract exists to rule out.
  */
final class TextInputCaretSpec extends AnyFunSuite:

  private val input = TextInput()

  private def paintedCursor(buffer: Buffer, area: Rect): Option[Position] =
    val cells = for
      y <- area.y until area.bottom
      x <- area.x until area.right
      if buffer.get(x, y).style.modifiers.hasAny(Modifiers.Reverse)
    yield Position(x, y)
    cells.headOption

  private def caretAgreesWithPaint(state: TextInputState, area: Rect): Option[Position] =
    val buffer = Buffer(Rect(0, 0, 40, 5))
    input.render(area, buffer, state)
    val caret  = input.caretAt(area, state)
    assert(caret == paintedCursor(buffer, area), "the reported caret and the painted cursor disagree")
    caret

  test("the caret sits after the text, counted in display columns rather than code points"):
    // "héllo" with a combining acute: six code points, five clusters, five columns
    val state = TextInputState("héllo")
    assert(caretAgreesWithPaint(state, Rect(2, 1, 20, 1)) == Some(Position(7, 1)))

  test("wide characters move the caret two columns each"):
    val state = TextInputState("東京")
    assert(caretAgreesWithPaint(state, Rect(0, 0, 20, 1)) == Some(Position(4, 0)))

  test("an empty field puts the caret on its first cell"):
    assert(caretAgreesWithPaint(TextInputState(), Rect(3, 2, 10, 1)) == Some(Position(3, 2)))

  test("a cursor inside the text maps to that cluster's cell"):
    val state = TextInputState("abc")
    state.moveHome()
    state.moveRight()
    assert(caretAgreesWithPaint(state, Rect(0, 0, 10, 1)) == Some(Position(1, 0)))

  test("a scrolled field reports the caret where the scrolled row paints it"):
    val state = TextInputState("abcdefgh") // cursor at the end, eight columns in a four-column field
    assert(caretAgreesWithPaint(state, Rect(0, 0, 4, 1)) == Some(Position(3, 0)))

  test("an empty area has no caret cell"):
    assert(input.caretAt(Rect(0, 0, 0, 1), TextInputState("ab")).isEmpty)

  test("a field that hides its cursor still knows where the caret is"):
    // the host decides whether the painted cursor shows; the caret cell is geometry and does not depend on it
    val hidden = TextInput(showCursor = false)
    assert(hidden.caretAt(Rect(0, 0, 10, 1), TextInputState("ab")) == Some(Position(2, 0)))
