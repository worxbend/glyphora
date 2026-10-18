package io.worxbend.tui.widgets

import io.worxbend.tui.core.Modifiers
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class TextAreaSpec extends AnyFunSuite:

  private val area = TextArea()

  test("inserting text with newlines splits into lines"):
    val state = TextAreaState()
    state.insert("one\ntwo")
    assert(state.value == "one\ntwo")
    assert(state.lineCount == 2)
    assert(state.cursor == (1, 3))

  test("newline splits the current line at the cursor"):
    val state = TextAreaState("abcd")
    state.moveHome()
    state.moveRight()
    state.moveRight()
    state.newline()
    assert(state.value == "ab\ncd")
    assert(state.cursor == (1, 0))

  test("backspace at column zero joins with the previous line"):
    val state = TextAreaState("ab\ncd")
    state.moveHome()
    state.backspace()
    assert(state.value == "abcd")
    assert(state.cursor == (0, 2))

  test("delete at line end joins with the next line"):
    val state = TextAreaState("ab\ncd")
    state.moveUp()
    state.moveEnd()
    state.delete()
    assert(state.value == "abcd")

  test("vertical movement clamps the column to the target line length"):
    val state = TextAreaState("long line\nab")
    assert(state.cursor == (1, 2))
    state.moveUp()
    assert(state.cursor == (0, 2))
    state.moveEnd()
    state.moveDown()
    assert(state.cursor == (1, 2))

  test("left and right wrap across line boundaries"):
    val state = TextAreaState("ab\ncd")
    state.moveUp()
    state.moveEnd()
    state.moveRight()
    assert(state.cursor == (1, 0))
    state.moveLeft()
    assert(state.cursor == (0, 2))

  test("editing is cluster-safe for emoji"):
    val state = TextAreaState("a👍🏽b")
    state.moveEnd()
    state.backspace()
    state.backspace()
    assert(state.value == "a")

  test("undo restores text and cursor across several edits"):
    val state = TextAreaState("start")
    state.insert("!")
    state.newline()
    state.insert("more")
    assert(state.value == "start!\nmore")
    state.undo()
    assert(state.value == "start!\n")
    state.undo()
    assert(state.value == "start!")
    state.undo()
    assert(state.value == "start")
    assert(state.cursor == (0, 5))
    state.undo() // empty history: no-op
    assert(state.value == "start")

  test("renders lines with the cursor highlighted on its cell"):
    val state = TextAreaState("ab\ncd")
    state.moveUp() // cursor to (0, 2): end of first line
    val buffer = rendered(area, state, 10, 4)
    assert(trimmedLines(buffer).take(2) == Seq("ab", "cd"))
    assert(buffer.get(2, 0).style.modifiers.hasAny(Modifiers.Reverse))

  test("vertical scroll follows the cursor"):
    val state  = TextAreaState("1\n2\n3\n4\n5\n6")
    val buffer = rendered(area, state, 10, 3) // cursor on line 5 (index 5)
    assert(state.scrollRow == 3)
    assert(trimmedLines(buffer).head == "4")

  test("horizontal scroll follows the cursor on long lines"):
    val state  = TextAreaState("abcdefghij")
    val buffer = rendered(area, state, 5, 1)
    assert(state.scrollColumn > 0)
    assert(buffer.get(4, 0).style.modifiers.hasAny(Modifiers.Reverse))

  test("redo re-applies undone edits and a fresh edit clears the redo history"):
    val state = TextAreaState("a")
    state.insert("bb") // multi-grapheme inserts are steps of their own
    state.insert("cc")
    assert(state.value == "abbcc")
    state.undo()
    state.undo()
    assert(state.value == "a")
    state.redo()
    assert(state.value == "abb")
    state.redo()
    assert(state.value == "abbcc")
    state.undo()
    state.insert("X")  // new edit invalidates the redo branch
    state.redo()
    assert(state.value == "abbX")

  test("controls are dropped on insert but the newline still splits"):
    // the filter has to run per segment, after the split: filtering the whole string first would eat the "\n" that
    // newline() inserts
    val state = TextAreaState()
    state.insert("a\tb\nc" + Escape + "d")
    assert(state.value == "ab\ncd")
    assert(state.lineCount == 2)
    assert(state.cursor == (1, 2))

  test("the constructor seed is filtered too"):
    val state = TextAreaState("x" + Null + "y\r\nz")
    assert(state.value == "xy\nz")
    assert(!state.value.exists(c => c != '\n' && Character.isISOControl(c)))

  test("no control character reaches a cell"):
    val buffer  = rendered(area, TextAreaState("a\tb"), 10, 4)
    val symbols = for y <- 0 until 4; x <- 0 until 10 yield buffer.get(x, y).symbol
    assert(symbols.forall(symbol => !symbol.exists(c => Character.isISOControl(c))))
    assert(trimmedLines(buffer).head == "ab")

  test("a zero-width cluster is drawn as a blank cell"):
    val state  = TextAreaState(CombiningAcute + "a")
    state.moveHome()
    val buffer = rendered(area, state, 10, 4)
    assert(buffer.get(0, 0).symbol == " ")
    assert(buffer.get(1, 0).symbol == "a")

  test("the cursor on a wide grapheme stays visible at an odd inner width"):
    val state = TextAreaState("你好")
    state.moveLeft() // cursor at (0, 1), on the second cluster
    val buffer = rendered(area, state, 3, 1)
    assert(state.scrollColumn == 2, "the offset counts columns, and the cluster scrolled past is two columns wide")
    assert(buffer.get(0, 0).symbol == "好")
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Reverse))

  test("a wide-grapheme cursor renders unscrolled when the line fits"):
    val state  = TextAreaState("你好")
    state.moveLeft()
    val buffer = rendered(area, state, 4, 1)
    assert(state.scrollColumn == 0)
    assert(trimmedLines(buffer).head == "你好")
    assert(buffer.get(2, 0).style.modifiers.hasAny(Modifiers.Reverse))

  test("a cluster wider than the whole area terminates the horizontal solver"):
    val state  = TextAreaState("你")
    state.moveHome()
    val buffer = rendered(area, state, 1, 1)
    assert(state.scrollColumn == 0)
    assert(buffer.get(0, 0).symbol == " ")

  test("lines of differing cluster widths scroll by the same number of columns"):
    // The horizontal offset is shared by every line, so it has to be counted in columns. Counted in clusters, the
    // seven-cluster offset the ASCII line needs scrolled the six-cluster wide line clean off the screen.
    val state = TextAreaState("\u6f22\u5b57\u6f22\u5b57\u6f22\u5b57\nabcdefghijkl")
    state.moveDown()
    state.moveEnd()
    val lines = trimmedLines(rendered(area, state, 6, 2))
    assert(lines(1) == "hijkl")
    assert(lines.head.nonEmpty, "the wide line still shows the part of itself in the visible columns")
    assert("\u6f22\u5b57\u6f22\u5b57\u6f22\u5b57".contains(lines.head.trim))

  /** Controls and marks spelled by codepoint: a literal one in a source file is invisible. */
  private val Escape: String         = 0x1b.toChar.toString
  private val Null: String           = 0x00.toChar.toString
  private val CombiningAcute: String = 0x0301.toChar.toString
