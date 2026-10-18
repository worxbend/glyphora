package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Modifiers, Position, Rect}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

/** Soft wrap, the line-number gutter, selection highlight and incremental search on [[TextArea]]. */
final class TextAreaViewSpec extends AnyFunSuite:

  private val wrapping = TextArea(showCursor = false, options = TextAreaOptions(wrap = true))
  private val numbered = TextArea(showCursor = false, options = TextAreaOptions(lineNumbers = true))

  test("soft wrap at width 10 breaks after spaces and between CJK characters, never inside one"):
    val state = TextAreaState("the quick 你好世界 fox\n你好世界你好世界")
    GoldenFrames.assertMatches("textarea-softwrap-cjk", rendered(wrapping, state, 10, 6))

  test("soft wrap moves the caret onto the wrapped row it sits in"):
    val state  = TextAreaState("abcdefghijkl")
    val editor = TextArea(options = TextAreaOptions(wrap = true))
    assert(editor.caretAt(Rect(0, 0, 10, 3), state) == Some(Position(2, 1)))
    state.moveHome()
    assert(editor.caretAt(Rect(0, 0, 10, 3), state) == Some(Position(0, 0)))

  test("a caret at the end of a full wrapped row lands on the row below"):
    val state  = TextAreaState("abcdefghij")
    val editor = TextArea(options = TextAreaOptions(wrap = true))
    assert(editor.caretAt(Rect(0, 0, 10, 3), state) == Some(Position(0, 1)))

  test("soft wrap scrolls by visual rows to keep the caret in view"):
    val state  = TextAreaState("aaaaaaaaaabbbbbbbbbbcccccccccc")
    val buffer = rendered(TextArea(showCursor = false, options = TextAreaOptions(wrap = true)), state, 10, 2)
    assert(trimmedLines(buffer).take(2) == Seq("cccccccccc", "")) // the caret is on the phantom fourth row

  test("the gutter grows a column at line 10 and again at line 100"):
    def firstRow(lines: Int): String =
      trimmedLines(rendered(numbered, TextAreaState(Vector.fill(lines)("x").mkString("\n")), 12, lines min 200)).head
    assert(firstRow(9) == "1 x")
    assert(firstRow(10) == " 1 x")
    assert(firstRow(99) == " 1 x")
    assert(firstRow(100) == "  1 x")

  test("wrapped continuation rows leave the gutter blank"):
    val editor = TextArea(showCursor = false, options = TextAreaOptions(wrap = true, lineNumbers = true))
    val state  = TextAreaState("abcdefgh\nz")
    assert(trimmedLines(rendered(editor, state, 7, 4)).take(3) == Seq("1 abcde", "  fgh", "2 z"))

  test("the selection paints in selectionStyle"):
    val state  = TextAreaState("abcd")
    state.moveHome()
    state.moveRight(select = true)
    state.moveRight(select = true)
    val buffer = rendered(TextArea(showCursor = false), state, 10, 1)
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(buffer.get(1, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(2, 0).style.modifiers.hasAny(Modifiers.Reverse))

  test("incremental search: /foo jumps to the first match, n to the second, N back"):
    val state = TextAreaState("foo bar\nbaz foo\nfoo")
    state.openSearch()
    state.updateSearch("f")
    assert(state.cursor == (0, 0))
    state.updateSearch("foo")
    assert(state.search == Some("foo"))
    state.closeSearch()
    assert(state.search.isEmpty)
    state.nextMatch()
    assert(state.cursor == (1, 4))
    state.nextMatch()
    assert(state.cursor == (2, 0))
    state.nextMatch() // wraps round
    assert(state.cursor == (0, 0))
    state.previousMatch()
    assert(state.cursor == (2, 0))

  test("cancelling a search puts the caret back where it started"):
    val state = TextAreaState("foo bar")
    state.openSearch()
    state.updateSearch("bar")
    assert(state.cursor == (0, 4))
    state.cancelSearch()
    assert(state.cursor == (0, 7))

  test("search is case-insensitive unless the query has a capital"):
    val state = TextAreaState("Foo foo")
    state.moveHome()
    state.openSearch()
    state.updateSearch("foo")
    assert(state.cursor == (0, 0))
    state.updateSearch("Foo")
    assert(state.cursor == (0, 0))
    state.closeSearch()
    state.nextMatch() // only one case-sensitive match: wraps to itself
    assert(state.cursor == (0, 0))

  test("an open search draws its prompt on the bottom row with the caret after the query, and underlines matches"):
    val state  = TextAreaState("a foo\nb foo")
    val editor = TextArea()
    state.openSearch()
    state.updateSearch("foo")
    val buffer = rendered(editor, state, 10, 3)
    assert(trimmedLines(buffer) == Seq("a foo", "b foo", "/foo"))
    assert(buffer.get(2, 1).style.modifiers.hasAny(Modifiers.Underline))
    assert(!buffer.get(0, 1).style.modifiers.hasAny(Modifiers.Underline))
    assert(editor.caretAt(Rect(0, 0, 10, 3), state) == Some(Position(4, 2)))
