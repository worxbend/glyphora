package io.worxbend.tui.widgets

import org.scalatest.funsuite.AnyFunSuite

/** Selection, word motion, clipboard and the kill ring on [[EditBuffer]]. */
final class EditBufferSelectionSpec extends AnyFunSuite:

  private val Family = "👨‍👩‍👧" // man ZWJ woman ZWJ girl: one grapheme

  test("Shift+Right three times selects three graphemes, a ZWJ emoji counting as one"):
    val state = TextInputState(s"a${Family}bc")
    state.moveHome()
    (1 to 3).foreach(_ => state.moveRight(select = true))
    assert(state.selectedText == s"a${Family}b")
    assert(state.selection == Some((TextPosition(0, 0), TextPosition(0, 3))))

  test("typing replaces the selection and a plain move collapses it"):
    val state = TextInputState("hello world")
    state.moveHome()
    (1 to 5).foreach(_ => state.moveRight(select = true))
    state.insert("J")
    assert(state.value == "J world")
    state.moveEnd(select = true)
    state.moveLeft()
    assert(state.selection.isEmpty)
    assert(state.cursor == 1) // collapsed to the selection's start

  test("backspace deletes the selection across lines, and undo brings it back"):
    val state = TextAreaState("one\ntwo\nthree")
    state.moveTo(TextPosition(0, 1))
    state.moveTo(TextPosition(2, 2), select = true)
    assert(state.selectedText == "ne\ntwo\nth")
    state.backspace()
    assert(state.value == "oree")
    state.undo()
    assert(state.value == "one\ntwo\nthree")

  test("select-all, copy, cut and paste go through the caller's clipboard"):
    val clipboard = Clipboard.inMemory()
    val state     = TextAreaState("ab\ncd")
    state.selectAll()
    state.copy(clipboard)
    assert(clipboard.paste() == Some("ab\ncd"))
    state.cut(clipboard)
    assert(state.value == "")
    state.paste(clipboard)
    state.paste(clipboard)
    assert(state.value == "ab\ncdab\ncd")

  test("word motion follows BreakIterator word boundaries and skips punctuation"):
    val state = TextInputState("hello, brave world")
    state.moveHome()
    state.moveWordRight()
    assert(state.cursor == 5)
    state.moveWordRight()
    assert(state.cursor == 12)
    state.moveWordLeft()
    assert(state.cursor == 7)
    state.moveWordLeft(select = true)
    assert(state.selectedText == "hello, ")

  test("deleteWordBackward (Ctrl+W) removes one word per call"):
    val state = TextInputState("hello brave world")
    state.deleteWordBackward()
    assert(state.value == "hello brave ")
    state.deleteWordBackward()
    assert(state.value == "hello ")

  test("paragraph motion jumps between blank lines"):
    val state = TextAreaState("a\nb\n\nc\nd\n\ne")
    state.moveTo(TextPosition(0, 0))
    state.moveParagraphDown()
    assert(state.cursor == (2, 0))
    state.moveParagraphDown()
    assert(state.cursor == (5, 0))
    state.moveParagraphUp()
    assert(state.cursor == (2, 0))

  test("Ctrl+K kills to end of line, Ctrl+Y yanks, Alt+Y cycles the ring"):
    val state = TextAreaState("first line\nsecond line")
    state.moveTo(TextPosition(0, 5))
    state.killToEndOfLine()
    assert(state.value == "first\nsecond line")
    state.moveTo(TextPosition(1, 6))
    state.killToEndOfLine()
    assert(state.value == "first\nsecond")
    state.moveTo(TextPosition(0, 0))
    state.yank()
    assert(state.value == " linefirst\nsecond")
    state.undo()
    assert(state.value == "first\nsecond")

  test("Alt+Y replaces the yank with the previous kill"):
    val state = TextInputState("alpha beta")
    state.deleteWordBackward() // kills "beta"
    state.moveHome()
    state.moveRight()
    state.killToEndOfLine()    // kills "lpha "
    state.yank()
    assert(state.value == "alpha ")
    state.yankPop()
    assert(state.value == "abeta")
    state.yankPop()
    assert(state.value == "alpha ")

  test("Alt+Y does nothing unless the previous command was a yank"):
    val state = TextInputState("xy")
    state.killToStartOfLine()
    state.insert("z")
    state.yankPop()
    assert(state.value == "z")

  test("consecutive kills accumulate into one ring entry"):
    val state = TextAreaState("ab\ncd")
    state.moveTo(TextPosition(0, 0))
    state.killToEndOfLine() // "ab"
    state.killToEndOfLine() // the line break
    state.killToEndOfLine() // "cd"
    assert(state.value == "")
    state.yank()
    assert(state.value == "ab\ncd")
