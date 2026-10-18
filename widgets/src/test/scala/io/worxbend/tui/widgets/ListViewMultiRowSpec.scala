package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Color, Line, Modifiers, Style, Text}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** Items that occupy more than one row: how they are drawn, where the marker goes, and how the scroll offset — which
  * counts items, not rows — keeps a tall selection on screen.
  */
final class ListViewMultiRowSpec extends AnyFunSuite:

  private def text(lines: String*): Text = Text(lines.map(Line.raw))

  private val mixed = ListView(Seq("one", text("two", "two-sub"), "three"))

  test("a multi-line item takes one row per line and pushes the next item down"):
    val buffer = rendered(mixed, ListState(), 12, 4)
    assert(trimmedLines(buffer) == Seq("  one", "  two", "  two-sub", "  three"))

  test("an empty text still takes one row, so the item stays selectable"):
    val widget = ListView(Seq("one", Text.Empty, "three"))
    val buffer = rendered(widget, ListState(selected = Some(1)), 12, 3)
    assert(trimmedLines(buffer) == Seq("  one", ">", "  three"))

  test("the marker is drawn once per selected item by default"):
    val buffer = rendered(mixed, ListState(selected = Some(1)), 12, 4)
    assert(trimmedLines(buffer) == Seq("  one", "> two", "  two-sub", "  three"))

  test("repeatHighlightSymbol draws the marker on every row of the selected item"):
    val widget = mixed.copy(repeatHighlightSymbol = true)
    val buffer = rendered(widget, ListState(selected = Some(1)), 12, 4)
    assert(trimmedLines(buffer) == Seq("  one", "> two", "> two-sub", "  three"))

  test("the highlight style covers every row of a selected multi-row item"):
    val widget = ListView(Seq("one", text("two", "two-sub")), highlightStyle = Style.fg(Color.Red))
    val buffer = rendered(widget, ListState(selected = Some(1)), 12, 3)
    assert(buffer.get(2, 1).style.fg.contains(Color.Red))
    assert(buffer.get(2, 2).style.fg.contains(Color.Red))
    assert(!buffer.get(2, 0).style.fg.contains(Color.Red))

  test("an item taller than the viewport renders from its top rather than disappearing"):
    val widget = ListView(Seq("head", text("a", "b", "c", "d", "e")))
    val state  = ListState(selected = Some(1))
    val buffer = rendered(widget, state, 12, 3)
    assert(state.offset == 1)
    assert(trimmedLines(buffer) == Seq("> a", "  b", "  c"))

  test("selecting a later item scrolls until its last row is visible"):
    val widget = ListView(Seq(text("a", "a2"), text("b", "b2"), "c"))
    val state  = ListState(selected = Some(2))
    val buffer = rendered(widget, state, 12, 3)
    assert(state.offset == 1)
    assert(trimmedLines(buffer) == Seq("  b", "  b2", "> c"))

  test("a wide-character item is clipped by display width, not by character count"):
    val widget = ListView(Seq(text("你好世界")))
    val buffer = rendered(widget, ListState(), 6, 1)
    assert(trimmedLines(buffer) == Seq("  你好"))

  test("a one-row-per-item list is unaffected by the multi-row arithmetic"):
    val widget = ListView(Seq("alpha", "beta", "gamma", "delta"))
    val state  = ListState(selected = Some(3))
    val buffer = rendered(widget, state, 10, 3)
    assert(state.offset == 1)
    assert(trimmedLines(buffer) == Seq("  beta", "  gamma", "> delta"))

  test("a zero-height area draws nothing and does not throw"):
    val buffer = rendered(mixed, ListState(selected = Some(1)), 12, 0)
    assert(trimmedLines(buffer).isEmpty)

  test("heightAt reports the summed item heights"):
    assert(mixed.heightAt(20).contains(4))

  test("a Text item's own base style reaches its cells, exactly as a Line item's does"):
    // Text.style is the outermost of the three text layers. `linesOf` used to hand the Text's lines back untouched, so
    // that layer never reached a cell and a styled multi-line item drew plain — while the same base style on a Line
    // item drew bold. The two spellings of "this item is bold" now agree.
    val bold     = Style.Default.bold
    val fromText = rendered(ListView(Seq(Text.raw("hi").withStyle(bold))), ListState(), 10, 3)
    val fromLine = rendered(ListView(Seq(Line.raw("hi").withStyle(bold))), ListState(), 10, 3)
    assert(fromText.get(2, 0).style == fromLine.get(2, 0).style)
    assert(fromText.get(2, 0).style.modifiers.hasAny(Modifiers.Bold))

  test("a line inside a Text item overrules the Text's base style"):
    val item   = Text(Seq(Line.raw("a").withStyle(Style.Default.withFg(Color.Blue)))).withStyle(
      Style.Default.withFg(Color.Red)
    )
    val buffer = rendered(ListView(Seq(item)), ListState(), 10, 3)
    assert(buffer.get(2, 0).style.fg.contains(Color.Blue))

  test("an offset past a tall last item still renders content rather than a blank area"):
    val tall   = ListView(Seq("a", text("b", "c", "d", "e")))
    val buffer = rendered(tall, ListState(selected = None, offset = 2), 10, 3)
    assert(trimmedLines(buffer) == Seq("  b", "  c", "  d"))

  test("a bottom-to-top list keeps a multi-row item's lines in reading order"):
    // The first item is anchored to the floor and the next climbs above it, but *inside* the item the title still
    // reads above its subtitle: the direction moves blocks, never the lines within one. The marker belongs to the
    // item's first line, which is its top row whichever edge the list grows from.
    val widget = ListView(Seq(text("title", "sub"), "next"), direction = ListDirection.BottomToTop)
    val buffer = rendered(widget, ListState(selected = Some(0)), 10, 4)
    assert(trimmedLines(buffer) == Seq("", "  next", "> title", "  sub"))

  test("the topmost bottom-to-top item that does not fit loses its top lines"):
    // Two rows are left above "first" for a three-line item, and in reading order those two rows are its last two
    // lines — the cut falls on the lines past the far edge, which bottom-to-top is the top of the block.
    val widget = ListView(Seq("first", text("a", "b", "c")), direction = ListDirection.BottomToTop)
    val buffer = rendered(widget, ListState(), 10, 3)
    assert(trimmedLines(buffer) == Seq("  b", "  c", "  first"))

  test("a bottom-to-top item taller than the viewport shows its bottom rather than disappearing"):
    val widget = ListView(Seq(text("a", "b", "c", "d", "e")), direction = ListDirection.BottomToTop)
    val buffer = rendered(widget, ListState(), 10, 3)
    assert(trimmedLines(buffer) == Seq("  c", "  d", "  e"))

  test("a selected bottom-to-top item that does not fit still shows its marker on its first visible line"):
    // Scrolling keeps a selected item on screen, so only an item taller than the viewport is ever cut while selected.
    // The cut takes the item's first line with it, and the marker moves to the topmost row the item still owns
    // instead of vanishing along with the line it would normally sit on.
    val widget = ListView(Seq(text("a", "b", "c", "d", "e")), direction = ListDirection.BottomToTop)
    val buffer = rendered(widget, ListState(selected = Some(0)), 10, 3)
    assert(trimmedLines(buffer) == Seq("> c", "  d", "  e"))

/** The row-counting scroll rule on its own. */

final class ScrollWindowItemsSpec extends AnyFunSuite:

  test("uniform heights agree with the index-based rule"):
    val heights = Seq.fill(10)(1)
    (0 until 10).foreach { selected =>
      assert(
        ScrollWindow.offsetForItems(0, Some(selected), heights, 4) ==
          ScrollWindow.offsetFor(0, Some(selected), heights.size, 4)
      )
    }

  test("no selection leaves a clamped offset alone"):
    assert(ScrollWindow.offsetForItems(1, None, Seq(2, 2, 2), 4) == 1)
    assert(ScrollWindow.offsetForItems(9, None, Seq(2, 2, 2), 4) == 1)

  test("an empty list and a zero-height viewport answer zero"):
    assert(ScrollWindow.offsetForItems(3, Some(1), Seq.empty, 5) == 0)
    assert(ScrollWindow.offsetForItems(3, Some(1), Seq(1, 1), 0) == 0)

  test("a selection above the window pulls the window up to it"):
    assert(ScrollWindow.offsetForItems(2, Some(0), Seq(1, 1, 1, 1), 2) == 0)

  test("a selection taller than the viewport stops at its own top"):
    assert(ScrollWindow.offsetForItems(0, Some(1), Seq(1, 5), 3) == 1)

  test("an out-of-range selection is clamped rather than trusted"):
    assert(ScrollWindow.offsetForItems(0, Some(99), Seq(1, 1, 1), 2) == 1)
    assert(ScrollWindow.offsetForItems(0, Some(-4), Seq(1, 1, 1), 2) == 0)

  test("a height of zero is treated as one row so an item is never skipped"):
    assert(ScrollWindow.offsetForItems(0, Some(2), Seq(0, 0, 0), 2) == 1)

  test("an item taller than the viewport still caps the offset inside the list"):
    // Seq(1, 4) in a 3-row viewport: the last item alone overflows, so no offset makes the remaining items "fit".
    // The answer must still be an item index the caller can slice with — 1, the tall item's own top — because an
    // offset of 2 would drop every item and paint the whole area blank.
    assert(ScrollWindow.offsetForItems(2, None, Seq(1, 4), 3) == 1)
