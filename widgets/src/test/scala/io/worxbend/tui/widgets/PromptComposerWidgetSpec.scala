package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Modifiers, Position, Rect}
import io.worxbend.tui.testsupport.BufferAssertions.*
import org.scalatest.funsuite.AnyFunSuite

final class PromptComposerWidgetSpec extends AnyFunSuite:
  test("composer painting and history ghosts need no runtime or backend"):
    val state    = PromptComposerState(initialHistory = Seq("日本 hello"))
    state.editor.insert("日本")
    val composer = PromptComposer(promptGlyph = "> ")
    val area     = Rect(0, 0, 20, 3)
    val buffer   = Buffer(area)
    composer.render(area, buffer, state)
    assert(trimmedLines(buffer).head == "> 日本 hello")
    assert(composer.caretAt(area, state).contains(Position(6, 0)))
    assert(buffer.get(7, 0).style.modifiers.hasAny(Modifiers.Dim))

  test("reverse search paints safely in tiny and empty areas and suppresses the draft caret"):
    val state    = PromptComposerState(initialHistory = Seq("deploy"))
    state.reverseSearch()
    state.updateReverse("dep")
    val composer = PromptComposer()
    for area <- Seq(Rect(0, 0, 0, 0), Rect(0, 0, 1, 1), Rect(0, 0, 8, 2)) do
      val buffer = Buffer(Rect(0, 0, 8, 2))
      composer.render(area, buffer, state)
      assert(composer.caretAt(area, state).isEmpty)

  test("completion list painting is reusable and its placement respects a nonzero frame origin"):
    val state    = PromptComposerState()
    state.editor.insert("@a")
    state.popup = Some(Popup('@', TextPosition(0, 0), "a", Vector("alice", "日本"), 1))
    val composer = PromptComposer()
    val frame    = Rect(3, 4, 12, 5)
    val area     = Rect(3, 7, 12, 1)
    val buffer   = Buffer(Rect(0, 0, 20, 12))
    composer.render(area, buffer, state)
    val target   = composer.popupAt(area, state, frame).get
    assert(target.y < area.y)
    assert(target.intersection(frame) == target)
    composer.popupWidget(state).render(target, buffer)
    assert(buffer.get(target.x, target.y + 1).style.modifiers.hasAny(Modifiers.Reverse))

  test("placeholder expansion scans only the draft, never replacement payloads"):
    val state = PromptComposerState(pasteLines = 2)
    state.paste("first\nline", _ => "(paste)")
    state.editor.insert("|")
    state.paste("(paste)\nsecond", _ => "(paste)")
    assert(state.expandedValue == "first\nline|(paste)\nsecond")
