package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Color, Constraint, Line, Modifiers, Rect, Style, Text, Widget}
import io.worxbend.tui.testsupport.BufferAssertions.{line, renderedInto, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** Widgets under the inputs a real app reaches by ordinary means: state that outlived the data it indexes, a viewport
  * that changed size between frames, a caller-supplied collection that turned out to be empty, a count that came from
  * `area.height - 2` on a short terminal.
  *
  * Every case here either threw out of `render` (which kills the render thread and the app with it), wrote outside the
  * `Rect` it was given (silently corrupting the neighbouring widget), or rendered blank while holding real content.
  */
final class DegenerateInputSpec extends AnyFunSuite:

  private def buffer(width: Int, height: Int): Buffer = Buffer(Rect(0, 0, width, height))

  // ---------------------------------------------------------------- TextInput / TextArea scroll offsets

  /** The scroll offset is caller-owned and survives across frames, so it has to be pulled back as well as pushed
    * forward. Typing past the edge and then deleting back down used to leave the whole value off the left edge: the
    * field looked frozen and empty while still holding text.
    */
  test("a text input still shows its value after the text is deleted back down"):
    val state = TextInputState()
    state.insert("the quick brown fox jumps")
    val input = TextInput()
    input.render(Rect(0, 0, 12, 1), buffer(12, 1), state)
    assert(state.scrollCluster > 0, "the long value should have scrolled")
    (1 to 20).foreach(_ => state.backspace())
    assert(state.value == "the q")
    val buf   = buffer(12, 1)
    input.render(Rect(0, 0, 12, 1), buf, state)
    assert(line(buf, 0).startsWith("the q"), s"rendered '${line(buf, 0)}'")

  test("a text input re-flows when the viewport grows"):
    val state  = TextInputState()
    state.insert("hello world, this is a long value")
    val input  = TextInput()
    input.render(Rect(0, 0, 6, 1), buffer(6, 1), state)
    val narrow = state.scrollCluster
    assert(narrow > 0)
    val buf    = buffer(40, 1)
    input.render(Rect(0, 0, 40, 1), buf, state)
    assert(state.scrollCluster == 0, "the whole value fits at width 40, so nothing should be scrolled away")
    assert(line(buf, 0).startsWith("hello world"))

  test("a text area re-flows both axes when the viewport grows"):
    val state = TextAreaState((1 to 8).map(n => s"line$n").mkString("\n"))
    val area  = TextArea()
    area.render(Rect(0, 0, 6, 2), buffer(6, 2), state)
    assert(state.scrollRow > 0)
    val buf   = buffer(20, 8)
    area.render(Rect(0, 0, 20, 8), buf, state)
    assert(state.scrollRow == 0, "all 8 lines fit in 8 rows, so nothing should be scrolled away")
    assert(line(buf, 0).startsWith("line1"))

  // ---------------------------------------------------------------- writes outside the given Rect

  /** `Buffer.set` clips to the buffer, not to the widget's `Rect`, so anything a widget draws without clipping lands on
    * its neighbour. Here the highlight symbol is wider than the list it is drawn in.
    */
  test("a list view narrower than its highlight symbol stays inside its rect"):
    val buf = buffer(10, 2)
    (0 until 10).foreach(x => buf.set(x, 0, Cell("#", Style.Default)))
    ListView(Seq(Line.raw("a")), highlightSymbol = "==> ")
      .render(Rect(0, 0, 2, 1), buf, ListState(selected = Some(0)))
    assert(line(buf, 0).drop(2) == "########", s"row 0 is '${line(buf, 0)}'")

  test("a bar chart with a negative gap stays inside its rect"):
    val buf = buffer(20, 4)
    (0 until 10).foreach(x => buf.set(x, 1, Cell("#", Style.Default)))
    BarChart(Seq("" -> 5L, "" -> 5L, "" -> 5L), BarChartOptions(barWidth = 3, barGap = -5))
      .render(Rect(10, 0, 10, 4), buf)
    // only the ten columns to the *left* of the chart's rect are under test; the chart owns everything from 10 on
    assert(line(buf, 1).take(10) == "##########", s"columns left of the area were overwritten: '${line(buf, 1)}'")

  // ---------------------------------------------------------------- exceptions out of render

  /** A menu whose entries are all disabled has nothing to highlight, and `MenuState.selected` says so with `None`.
    *
    * While the selection was a bare `Int`, "nothing" was spelled `-1` — a value the widget's own API could never
    * produce, because `MenuState()` opened on index 0 and `step` never assigned it. Every menu an application actually
    * built therefore painted `highlightStyle` over its first disabled entry, and this assertion could not be written at
    * all: the only way to reach the documented behaviour was to hand-seed the sentinel from a test.
    */
  test("a menu with nothing selectable highlights nothing"):
    val menu   = Menu(Seq(MenuEntry.Item("cut", enabled = false), MenuEntry.Item("copy", enabled = false)))
    val state  = MenuState()
    val target = buffer(10, 4)
    menu.render(Rect(0, 0, 10, 4), target, state)
    assert(state.selected.isEmpty)
    // row 1 is the first entry, inside the border; dimmed as disabled rather than reversed as highlighted
    assert(target.get(1, 1).style.modifiers.hasAny(Modifiers.Dim))
    assert(!target.get(1, 1).style.modifiers.hasAny(Modifiers.Reverse))

  test("a calendar clamps an out-of-range month into the year instead of throwing or blanking"):
    // stepping a month at a time through prev/next navigation naturally produces 0 and 13, so both are clamped into
    // 1..12 — 13 is December, 0 is January. Not throwing is only half the contract: the pane must still show a month.
    val overflow  = buffer(20, 10)
    Calendar(2026, 13).render(Rect(0, 0, 20, 10), overflow)
    assert(line(overflow, 0).contains("December 2026"), s"title row is '${line(overflow, 0)}'")
    assert(line(overflow, 1).contains("Mo Tu We"), s"weekday header is '${line(overflow, 1)}'")
    val underflow = buffer(20, 10)
    Calendar(2026, 0).render(Rect(0, 0, 20, 10), underflow)
    assert(line(underflow, 0).contains("January 2026"), s"title row is '${line(underflow, 0)}'")
    // `Int.MaxValue` is past `java.time.Year.MAX_VALUE`, so the year clamps too and the grid is still drawn
    val distant   = buffer(20, 10)
    Calendar(Int.MaxValue, 6).render(Rect(0, 0, 20, 10), distant)
    assert(line(distant, 0).contains("June"), s"title row is '${line(distant, 0)}'")
    assert(trimmedLines(distant).count(_.nonEmpty) > 2, "the day grid was not drawn at all")

  test("charts with an empty palette render instead of dividing by zero"):
    // the palette is indexed by slice, so an empty one is the divide-by-zero. Surviving it is worth nothing if the
    // chart then paints nothing: these two assertions are what make "render" in the test name mean something.
    val pie  = buffer(30, 10)
    PieChart(Seq("a" -> 1.0, "b" -> 2.0), styles = Seq.empty).render(Rect(0, 0, 30, 10), pie)
    assert(trimmedLines(pie).exists(_.nonEmpty), "the pie chart drew nothing while holding two slices")
    val bars = buffer(20, 6)
    StackedBarChart(Seq("a" -> Seq(1L, 2L)), styles = Seq.empty).render(Rect(0, 0, 20, 6), bars)
    assert(trimmedLines(bars).exists(_.nonEmpty), "the stacked bar chart drew nothing while holding two segments")

  test("an image with a short row repeats that row's last pixel instead of indexing past it"):
    // `Image` takes its source width from the *first* row, so a ragged `pixels` is where an index would run off the
    // end. The answer is the same nearest-neighbour clamp the widget already uses for scaling: the last pixel of the
    // short row fills the columns it does not reach.
    val top: Color.Rgb    = Color.Rgb(10, 20, 30)
    val bottom: Color.Rgb = Color.Rgb(200, 100, 50)
    val ragged            = Image(Vector(Vector.fill(4)(top), Vector(bottom)))
    val buf               = buffer(20, 2)
    ragged.render(Rect(0, 0, 4, 2), buf)
    // two pixel rows over two terminal rows: row 0 samples the full row, row 1 samples the one-pixel row four times
    assert((0 until 4).forall(x => buf.get(x, 0).style.fg.contains(top)), "the full row did not paint")
    assert((0 until 4).forall(x => buf.get(x, 1).style.fg.contains(bottom)), "the short row did not fill its width")
    assert(spillToTheRight(ragged, 4, 2) == Seq("", ""))

  test("an image with an entirely empty row samples black rather than throwing"):
    val rgb: Color.Rgb = Color.Rgb(10, 20, 30)
    val empty          = Image(Vector(Vector.fill(4)(rgb), Vector.empty))
    val buf            = buffer(20, 2)
    empty.render(Rect(0, 0, 4, 2), buf)
    assert((0 until 4).forall(x => buf.get(x, 1).style.fg.contains(Color.Rgb(0, 0, 0))))
    assert(spillToTheRight(empty, 4, 2) == Seq("", ""))

  /** A column mixing numbers with dates is ordinary data. Deciding numeric-vs-text per comparison is not a valid
    * ordering — `"9" < "10"` numerically, `"10" < "2020-01-01"` and `"2020-01-01" < "9"` textually is a cycle — and
    * TimSort raises `IllegalArgumentException: Comparison method violates its general contract!` when it notices.
    */
  test("sorting a column that mixes numbers and text is a total order"):
    val cells  = Vector("9", "10", "11", "12", "3", "41", "7", "55", "2", "88", "30", "5x", "2018-01-01", "NaN")
    val rows   = (0 until 8).flatMap(_ => cells).map(Seq(_))
    val table  = DataTable.fromStrings(Seq("value"), rows, Seq(Constraint.Fill(1)))
    val state  = DataTableState()
    state.sort = Some(ColumnSort(0, SortDirection.Ascending))
    val sorted = table.filteredRows(state)
    assert(sorted.size == rows.size)

  test("an all-numeric column still sorts numerically"):
    val table = DataTable.fromStrings(Seq("value"), Seq(Seq("9"), Seq("10"), Seq("2")), Seq(Constraint.Fill(1)))
    val state = DataTableState()
    state.sort = Some(ColumnSort(0, SortDirection.Ascending))
    assert(table.filteredRows(state).map(_.cells.head) == Seq("2", "9", "10"))

  // ---------------------------------------------------------------- measurement that disagrees with rendering

  /** `Paragraph.heightAt` counts the rows `wrapLine` returns, so the two have to agree. A cluster wider than the column
    * budget used to be deleted from the document instead of clipped: `ScrollView` then reserved rows nothing rendered
    * into, sized its scrollbar thumb wrong, and every following line moved up one row.
    */
  test("a cluster wider than the wrap width is clipped, not deleted"):
    assert(Paragraph.wrapLine(Line.raw("日本語"), 1).size == 3)
    assert(Paragraph.wrapLine(Line.raw("ab日cd"), 1).size == 5)
    assert(Paragraph.wrapLine(Line.raw("ab👍cd"), 1).map(_.plainText).mkString == "ab👍cd")

  test("heightAt agrees with the rows a paragraph renders"):
    val cases = Seq(
      Text.raw("日本語テキスト")     -> 1,
      Text.raw("你好\nok")      -> 1,
      Text.raw("hello world") -> 5,
      Text.raw("")            -> 3,
      Text.raw("hello world") -> 0,
      Text.raw("a\nb\nc")     -> 0,
      Text.raw("hello")       -> -1,
    )
    cases.foreach: (text, width) =>
      val measured = Paragraph(text, overflow = Overflow.Wrap).heightAt(width).getOrElse(0)
      val rendered = text.lines.flatMap(line => Paragraph.wrapLine(line, width)).size
      assert(measured == rendered, s"heightAt said $measured, wrapping produced $rendered for width $width")

  /** A wrapping paragraph with no columns draws nothing, so it has to measure nothing. Reporting one row per source
    * line there made a layout pass reserve rows that stayed blank.
    */
  test("a wrapping paragraph measures no rows in no columns"):
    val wrapping = Paragraph(Text.raw("a\nb\nc"), overflow = Overflow.Wrap)
    assert(wrapping.heightAt(0).contains(0))
    assert(wrapping.heightAt(-1).contains(0))
    assert(Paragraph(Text.raw("a\nb\nc")).heightAt(0).contains(3)) // clipping never consults the width
    val drawn = renderedInto(wrapping, Rect(0, 0, 0, 3), 4, 3)
    assert(trimmedLines(drawn) == Seq("", "", ""))

  // ---------------------------------------------------------------- degenerate counts

  /** `Paging(area.height - 2, 0)` is the obvious idiom and yields a page size of zero on a two-row terminal. Paging by
    * zero showed no rows at all, on every page, forever.
    */
  test("a page size of zero still shows rows"):
    val table = DataTable.fromStrings(Seq("value"), (1 to 5).map(n => Seq(n.toString)), Seq(Constraint.Fill(1)))
    val state = DataTableState()
    state.paging = Some(Paging(size = 0, page = 0))
    assert(table.visibleRows(state).nonEmpty)
    state.paging = Some(Paging(size = -3, page = 0))
    assert(table.visibleRows(state).nonEmpty)

  /** `LogState.offset` is a public var and the viewport can grow between frames; an offset past the last useful row
    * rendered the tail of the log followed by blank rows, or nothing at all.
    */
  test("a log clamps a stale scroll offset to the content it has"):
    val state = LogState()
    (1 to 30).foreach(n => state.append(Line.raw(s"line$n")))
    state.scrollUp(20)
    assert(state.visibleSlice(20).size == 20, "a 20-row viewport over 30 lines must be full")
    val small = LogState()
    (1 to 3).foreach(n => small.append(Line.raw(s"line$n")))
    small.offset = 10
    assert(small.visibleSlice(5).nonEmpty, "an offset past the end must not blank the panel")

  // ---------------------------------------------------------------- single-line controls versus a narrow area

  /** Renders `widget` into the left `width` columns of a 20-column buffer and returns what landed to the right of it,
    * which must stay blank: a widget clips at the `Rect` it was handed, never at the buffer's own edge.
    */
  private def spillToTheRight(widget: Widget, width: Int, height: Int): Seq[String] =
    val buf = buffer(20, height)
    widget.render(Rect(0, 0, width, height), buf)
    trimmedLines(buf).map(_.drop(width))

  test("single-line controls truncate at their own right edge, not the buffer's"):
    assert(spillToTheRight(Checkbox("ship it now please", checked = true), 6, 1) == Seq(""))
    assert(spillToTheRight(Toggle("dark mode please", on = true), 6, 1) == Seq(""))
    assert(spillToTheRight(Select(Seq("a-long-option"), selected = 0), 6, 1) == Seq(""))
    assert(spillToTheRight(Spinner(0.millis, "loading the world"), 6, 1) == Seq(""))
    assert(spillToTheRight(Link("a-long-label", "https://example.com"), 6, 1) == Seq(""))
    assert(spillToTheRight(RadioGroup(Seq("first option", "second option"), selected = 0), 6, 2) == Seq("", ""))
