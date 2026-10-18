package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Color, Modifiers}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class TextAreaHighlightSpec extends AnyFunSuite:

  private val syntax = SyntaxTheme()

  test("TextAreaOptions.lexer highlights the editor's lines"):
    val editor = TextArea(showCursor = false, options = TextAreaOptions(lexer = Some(Lexer.Python)))
    val buffer = rendered(editor, TextAreaState("def f():\n    return 1"), 20, 2)
    assert(trimmedLines(buffer) == Seq("def f():", "    return 1"))
    assert(buffer.get(0, 0).style.fg == syntax.keyword.fg)  // def
    assert(buffer.get(4, 0).style.fg == syntax.function.fg) // f(
    assert(buffer.get(4, 1).style.fg == syntax.keyword.fg)  // return
    assert(buffer.get(11, 1).style.fg.contains(Color.Yellow)) // 1

  test("the cursor still shows over a highlighted token"):
    val state  = TextAreaState("def")
    state.moveHome()
    val buffer = rendered(TextArea(options = TextAreaOptions(lexer = Some(Lexer.Python))), state, 10, 1)
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(buffer.get(0, 0).style.fg == syntax.keyword.fg)

  test("highlighting follows horizontal scroll and wide clusters"):
    val state  = TextAreaState("x = '你好' + 1")
    val buffer =
      rendered(TextArea(showCursor = false, options = TextAreaOptions(lexer = Some(Lexer.Python))), state, 20, 1)
    assert(buffer.get(4, 0).style.fg == syntax.string.fg)
    assert(buffer.get(13, 0).style.fg == syntax.number.fg)

  test("without a lexer the editor draws in its own style, as before"):
    val buffer = rendered(TextArea(showCursor = false), TextAreaState("def f"), 10, 1)
    assert(buffer.get(0, 0).style.fg.isEmpty)
