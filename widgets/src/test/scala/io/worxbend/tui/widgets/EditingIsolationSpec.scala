package io.worxbend.tui.widgets

import org.scalatest.funsuite.AnyFunSuite

final class EditingIsolationSpec extends AnyFunSuite:
  test("hidden input never paints source clusters or reveals caret position"):
    val state  = TextInputState("fictitious secret")
    val area   = io.worxbend.tui.core.Rect(0, 0, 30, 1)
    val buffer = io.worxbend.tui.core.Buffer(area)
    val input  = TextInput(style = io.worxbend.tui.core.Style.Default.hidden)
    input.render(area, buffer, state)
    assert((0 until 30).forall(x => buffer.get(x, 0).symbol == " "))
    assert(input.caretAt(area, state).isEmpty)

  test("kills in different fields never coalesce across the shared ring"):
    val ring = KillRing()
    val a    = TextInputState("foo bar", ring)
    val b    = TextInputState("baz", ring)
    a.deleteWordBackward()
    b.deleteWordBackward()
    a.deleteWordBackward()
    assert(ring.size == 3)
    assert(ring.newest.contains("foo "))
    a.yank()
    assert(a.value == "foo ")

  test("an intervening command in another field breaks a shared kill chain"):
    val ring = KillRing()
    val a    = TextInputState("foo bar", ring)
    val b    = TextInputState("baz", ring)
    a.deleteWordBackward()
    b.moveHome()
    a.deleteWordBackward()
    assert(ring.size == 2)

  test("undo restores the direction and anchor of a replaced selection"):
    val state = TextInputState("hello world")
    state.moveTo(TextPosition(0, 5))
    state.moveTo(TextPosition(0, 0), select = true)
    state.insert("X")
    state.undo()
    assert(state.value == "hello world")
    assert(state.selectedText == "hello")
    assert(state.caret == TextPosition(0, 0))
    state.moveRight(select = true)
    assert(state.selectedText == "ello")
    state.redo()
    assert(state.value == "X world")
    assert(state.selection.isEmpty)
