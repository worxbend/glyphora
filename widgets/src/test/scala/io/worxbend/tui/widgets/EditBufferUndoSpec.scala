package io.worxbend.tui.widgets

import org.scalatest.funsuite.AnyFunSuite

/** Coalesced, diff-based undo shared by [[TextInputState]] and [[TextAreaState]] (bug B5). */
final class EditBufferUndoSpec extends AnyFunSuite:

  private def typeText(state: EditBuffer, text: String): Unit =
    text.foreach(c => state.insert(c.toString))

  test("five typed graphemes undo as one word"):
    val state = TextAreaState("say ")
    typeText(state, "hello")
    assert(state.value == "say hello")
    state.undo()
    assert(state.value == "say ")
    assert(state.cursor == (0, 4))
    state.redo()
    assert(state.value == "say hello")
    assert(state.cursor == (0, 9))

  test("a space after a word starts a new undo group"):
    val state = TextInputState()
    typeText(state, "hello world")
    state.undo()
    assert(state.value == "hello")
    state.undo()
    assert(state.value == "")

  test("a cursor move between keystrokes breaks the group"):
    val state = TextInputState()
    typeText(state, "ab")
    state.moveLeft()
    state.moveRight()
    typeText(state, "cd")
    state.undo()
    assert(state.value == "ab")

  test("consecutive backspaces coalesce, and undo restores the cursor"):
    val state = TextInputState("abcdef")
    state.backspace()
    state.backspace()
    state.backspace()
    assert(state.value == "abc")
    state.undo()
    assert(state.value == "abcdef")
    assert(state.cursor == 6)

  test("undo history is bounded by entries, oldest dropped first"):
    val state = TextAreaState()
    (1 to 150).foreach { _ =>
      state.insert("xy") // multi-grapheme inserts never coalesce
    }
    (1 to 200).foreach(_ => state.undo())
    assert(state.value == "xy" * 50)

  test("the undo log stores diffs: memory grows with keystrokes, not with document size times keystrokes"):
    val document = Vector.fill(100)("x" * 100).mkString("\n") // 10 000 clusters
    val state    = TextAreaState(document)
    (1 to 100).foreach { _ =>
      state.insert("k")
      state.moveLeft() // defeat coalescing: every keystroke becomes its own entry
      state.moveRight()
    }
    assert(state.undoFootprint <= 200, s"undo retained ${state.undoFootprint} clusters")
    (1 to 100).foreach(_ => state.undo())
    assert(state.value == document)
    assert(state.undoFootprint <= 200)
