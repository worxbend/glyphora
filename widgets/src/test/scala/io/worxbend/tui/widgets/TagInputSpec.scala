package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Modifiers, Position, Rect}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}

import org.scalatest.funsuite.AnyFunSuite

final class TagInputSpec extends AnyFunSuite:

  test("commit adds the trimmed input as a chip and clears it; blanks and duplicates are refused"):
    val state = TagInputState()
    state.input.setValue("  scala ")
    assert(state.commit())
    assert(state.tags == Vector("scala") && state.input.value.isEmpty)
    state.input.setValue("scala")
    assert(!state.commit())
    state.input.setValue("   ")
    assert(!state.commit())
    assert(state.tags == Vector("scala"))

  test("removeLast drops the newest chip, and remove(i) any one"):
    val state = TagInputState(Seq("a", "b", "c"))
    state.removeLast()
    assert(state.tags == Vector("a", "b"))
    state.remove(0)
    assert(state.tags == Vector("b"))
    state.remove(5)
    assert(state.tags == Vector("b"))

  test("chips flow onto a second row when the first is full, the input after the last chip"):
    val state  = TagInputState(Seq("scala", "jvm", "native", "tui"))
    val buffer = rendered(TagInput(), state, 20, 3)
    assert(lines(buffer).map(_.stripTrailing) == Seq(" scala   jvm", " native   tui", ""))
    assert(buffer.get(1, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(TagInput().heightAt(20, state) == 2)
    assert(TagInput().caretAt(Rect(0, 0, 20, 3), state).contains(Position(15, 1)))

  test("the first suggestion that extends the input shows dim after the caret, and is accepted on request"):
    val state  = TagInputState(suggestions = Seq("scala", "scalafix", "rust"))
    state.input.setValue("sc")
    assert(state.suggestion.contains("scala"))
    val buffer = rendered(TagInput(showCursor = false), state, 20, 1)
    assert(lines(buffer).head.stripTrailing == "scala")
    assert(buffer.get(3, 0).style.modifiers.hasAny(Modifiers.Dim))
    assert(state.acceptSuggestion())
    assert(state.input.value == "scala")
    state.input.setValue("x")
    assert(state.suggestion.isEmpty && !state.acceptSuggestion())

  test("a tag already present is never suggested, and an empty field shows its placeholder"):
    val state = TagInputState(Seq("scala"), suggestions = Seq("scala", "scalafix"))
    state.input.setValue("sca")
    assert(state.suggestion.contains("scalafix"))
    val empty = rendered(TagInput(placeholder = "add a tag", showCursor = false), TagInputState(), 20, 1)
    assert(lines(empty).head.stripTrailing == "add a tag")

  test("every mutation, including one made through the input, moves the version"):
    val state  = TagInputState()
    val before = state.version
    state.input.insert("x")
    assert(state.version > before)
