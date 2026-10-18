package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** [[Dropdown]] renders in two shapes — one row when closed, that row plus a bordered list when open — and the tests
  * are grouped that way. Everything is read back out of the buffer, because the closed row's truncation and the popup's
  * placement are the two things a reader cannot check by eye from the code.
  */
final class DropdownSpec extends AnyFunSuite:

  private val fruit    = Seq("apple", "banana", "cherry")
  private val dropdown = Dropdown(fruit, selected = 1)

  test("a closed dropdown draws one row: the closed glyph and the option in force"):
    val lines = trimmedLines(rendered(dropdown, DropdownState(), 20, 4))
    assert(lines.head == "▸ banana")
    assert(lines.tail.forall(_.isEmpty)) // nothing at all below it

  test("an open dropdown keeps its row and draws the whole list underneath, in a border"):
    val state = DropdownState()
    state.openAt(1)
    val lines = trimmedLines(rendered(dropdown, state, 20, 8))
    assert(lines.head == "▾ banana") // the glyph flips to the open one
    assert(lines(1).startsWith("╭") && lines(1).endsWith("╮"))
    assert(lines(2).contains("apple"))
    assert(lines(3).contains("banana"))
    assert(lines(4).contains("cherry"))
    assert(lines(5).startsWith("╰"))

  test("the highlight starts on the chosen option, so opening and committing changes nothing"):
    val state = DropdownState()
    state.openAt(2)
    assert(state.open)
    assert(state.menu.selected.contains(2))
    assert(state.menu.offset == 0)
    state.close()
    assert(!state.open)
    assert(state.menu.selected.contains(2)) // closing leaves the highlight where it was

  test("maxVisibleRows caps the popup and the rest of the list scrolls past it"):
    val many = Dropdown((1 to 40).map(index => s"option $index"), selected = 0, maxVisibleRows = 3)
    assert(many.popupHeight == 5) // three rows plus two borders
    assert(many.openHeight == 6)
    val state = DropdownState()
    state.openAt(0)
    val lines = trimmedLines(rendered(many, state, 20, 10))
    assert(lines(2).contains("option 1"))
    assert(lines(4).contains("option 3"))
    assert(!lines.exists(_.contains("option 4"))) // capped, not merely clipped by the area

  test("a dropdown given only one row draws its closed row and no list at all"):
    val state = DropdownState()
    state.openAt(0)
    val lines = trimmedLines(rendered(dropdown, state, 20, 1))
    assert(lines.head == "▾ banana")
    assert(lines.size == 1)

  test("a popup taller than the room left is clipped rather than drawn outside the area"):
    // Two rows means the closed row and a single popup row. A one-row border has no inside, so no option is drawn and
    // nothing is written past `area` — the failure this guards against is a popup that runs off the bottom.
    val state = DropdownState()
    state.openAt(0)
    val lines = trimmedLines(rendered(dropdown, state, 20, 2))
    assert(lines.size == 2)
    assert(!lines.exists(_.contains("apple")))

  test("an empty dropdown draws its glyph, claims no popup, and does not throw"):
    val empty = Dropdown(Seq.empty, selected = 0)
    assert(empty.popupHeight == 0)
    assert(empty.openHeight == 1)
    val state = DropdownState()
    state.openAt(0)
    val lines = trimmedLines(rendered(empty, state, 10, 6))
    assert(lines.head == "▾")
    assert(lines.tail.forall(_.isEmpty))

  test("a selection past the end of the list falls back to the nearest real option"):
    val stale = Dropdown(fruit, selected = 99)
    assert(trimmedLines(rendered(stale, DropdownState(), 20, 1)).head == "▸ cherry")
    val short = Dropdown(fruit, selected = -3)
    assert(trimmedLines(rendered(short, DropdownState(), 20, 1)).head == "▸ apple")

  test("a zero-area and a one-column render write nothing out of bounds"):
    val state = DropdownState()
    state.openAt(0)
    val empty = Buffer(Rect(0, 0, 0, 0))
    dropdown.render(Rect(0, 0, 0, 0), empty, state)
    assert(empty.area.isEmpty)
    val thin  = trimmedLines(rendered(dropdown, state, 1, 4))
    assert(thin.head == "▾")

  test("a wide-glyph option is truncated by display columns, not by character count"):
    // Each of these CJK characters is two columns wide, so six characters is twelve columns. In eight columns the row
    // fits the glyph, a space, and three of them.
    val cjk   = Dropdown(Seq("設定設定設定"), selected = 0)
    val lines = trimmedLines(rendered(cjk, DropdownState(), 8, 1))
    assert(lines.head == "▸ 設定設")

  test("an emoji option with a zero-width joiner counts as one grapheme of two columns"):
    // The glyph and its space take two columns, and the joined emoji takes two more, so four columns hold exactly it
    // and nothing of the word after it — a naive count of `char`s would have taken five and cut the emoji in half.
    val emoji = Dropdown(Seq("👩‍💻 dev"), selected = 0)
    val lines = trimmedLines(rendered(emoji, DropdownState(), 4, 1))
    assert(lines.head == "▸ 👩‍💻")

  test("the natural width covers the widest option, and the natural height is the closed row"):
    // `heightAt` reports the closed height even for an open dropdown, because `Measured` is handed no state and would
    // otherwise be guessing. `openHeight` is the accessor for the other answer.
    assert(dropdown.widthAt(1).exists(_ >= 10)) // "banana" is six columns, plus the glyph, plus the popup's border
    assert(dropdown.heightAt(20).contains(1))
    val state = DropdownState()
    state.openAt(0)
    assert(dropdown.heightAt(20).contains(1))
    assert(dropdown.openHeight == 6) // three options, two borders, and the closed row

  test("the popup alone can be drawn at any rectangle, for a host that floats it over the frame"):
    // the DSL node floats the list through a portal rather than stacking it under the row, so the list has to be
    // drawable on its own — here three rows below where the closed row would be, with a blank row in between
    val state  = DropdownState()
    state.openAt(2)
    val buffer = Buffer(Rect(0, 0, 20, 8))
    dropdown.renderPopup(Rect(0, 2, 20, 5), buffer, state)
    val lines  = trimmedLines(buffer)
    assert(lines.head.isEmpty && lines(1).isEmpty) // nothing drawn above the popup: no closed row, no border
    assert(lines(2).startsWith("╭") && lines(2).endsWith("╮"))
    assert(lines(3).contains("apple"))
    assert(lines(5).contains("cherry"))
    assert(lines(6).startsWith("╰"))
    assert(buffer.get(2, 5).style.modifiers.hasAny(io.worxbend.tui.core.Modifiers.Reverse)) // the highlight on cherry

  test("the popup draws nothing for an empty option list or an empty area"):
    val state  = DropdownState()
    state.openAt(0)
    val buffer = Buffer(Rect(0, 0, 20, 8))
    Dropdown(Seq.empty, 0).renderPopup(Rect(0, 1, 20, 5), buffer, state)
    dropdown.renderPopup(Rect(0, 1, 0, 5), buffer, state)
    assert(trimmedLines(buffer).forall(_.isEmpty))
