package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Color, Constraint, Line, Rect, Span, Style, Text}
import io.worxbend.tui.testsupport.BufferAssertions.trimmedLines

import org.scalatest.funsuite.AnyFunSuite

/** Rendering must cost what the *viewport* costs, not what the dataset costs.
  *
  * These assert observable work rather than wall-clock time, so they are stable on a loaded CI box: a `Seq` that counts
  * how often it was traversed catches an accidental `foreach` over ten thousand rows far more reliably than a timing
  * threshold.
  */
final class ViewportCostSpec extends AnyFunSuite:

  private val widths = Seq(Constraint.Length(6), Constraint.Fill(1))

  /** A view over `rows` that records how many elements were actually pulled. */
  final class CountingSeq[A](underlying: Seq[A]) extends Seq[A]:
    var touched: Int                       = 0
    var mapped: Int                        = 0
    def apply(i: Int): A                   = { touched += 1; underlying(i) }
    def length: Int                        = underlying.length
    override def map[B](f: A => B): Seq[B] =
      mapped += 1
      underlying.map(f)
    def iterator: Iterator[A]              =
      underlying.iterator.map { element =>
        touched += 1
        element
      }

  test("Table only touches the rows it can draw"):
    val data   = Vector.tabulate(10000)(i => Seq(Line.raw(i.toString), Line.raw(s"row $i")))
    val rows   = CountingSeq(data)
    val buffer = Buffer(Rect(0, 0, 40, 20))
    Table(rows, widths).render(Rect(0, 0, 40, 20), buffer)
    assert(rows.touched <= 20, s"walked ${rows.touched} rows to draw 20")
    assert(buffer.get(0, 0).symbol == "0", "the first row still rendered")

  test("deriving equal columns from the data still only touches the rows it can draw"):
    val data   = Vector.tabulate(10000)(i => Seq(Line.raw(i.toString), Line.raw(s"row $i")))
    val rows   = CountingSeq(data)
    val buffer = Buffer(Rect(0, 0, 40, 20))
    Table(rows, Seq.empty).render(Rect(0, 0, 40, 20), buffer)
    assert(rows.touched <= 20, s"walked ${rows.touched} rows to derive a column count for 20")
    assert(buffer.get(0, 0).symbol == "0", "the first row still rendered")

  test("rows taller than one line shorten the walk rather than lengthening it"):
    val data   = Vector.tabulate(10000)(i => TableRow(Seq(Line.raw(i.toString)), height = 4))
    val rows   = CountingSeq(data)
    val buffer = Buffer(Rect(0, 0, 40, 20))
    Table(rows, widths).render(Rect(0, 0, 40, 20), buffer)
    assert(rows.touched <= 6, s"walked ${rows.touched} four-line rows to fill 20 lines")
    assert(buffer.get(0, 0).symbol == "0", "the first row still rendered")

  test("Table still renders every row that fits, header included"):
    val data   = Vector.tabulate(3)(i => Seq(Line.raw(i.toString), Line.raw(s"row $i")))
    val buffer = Buffer(Rect(0, 0, 40, 10))
    Table(data, widths, header = Some(Seq(Line.raw("id"), Line.raw("name")))).render(Rect(0, 0, 40, 10), buffer)
    assert(buffer.get(0, 0).symbol == "i") // header
    assert(buffer.get(0, 1).symbol == "0")
    assert(buffer.get(0, 3).symbol == "2")

  test("Table clips rather than overflowing when there are more rows than lines"):
    val data   = Vector.tabulate(50)(i => Seq(Line.raw(i.toString), Line.raw("x")))
    val buffer = Buffer(Rect(0, 0, 40, 3))
    Table(data, widths).render(Rect(0, 0, 40, 3), buffer)
    assert(buffer.get(0, 2).symbol == "2")

  test("a wrapping Paragraph only wraps as far as the viewport needs"):
    val lines  = Vector.tabulate(10000)(i => Line(Seq(Span(s"line $i " + "word " * 40, Style.Default))))
    val text   = CountingSeq(lines)
    val buffer = Buffer(Rect(0, 0, 30, 10))
    Paragraph(Text(text), overflow = Overflow.Wrap).render(Rect(0, 0, 30, 10), buffer)
    assert(text.touched <= 20, s"wrapped ${text.touched} source lines to fill 10 rows")
    assert(buffer.get(0, 0).symbol == "l")

  test("a tiny wrapped viewport does not traverse an invisible long source line"):
    for overflow <- Seq(Overflow.Wrap, Overflow.WrapTrimmed, Overflow.WrapPreserved) do
      val spans  = CountingSeq(Vector.fill(10000)(Span.raw("a")))
      val area   = Rect(0, 0, 4, 2)
      val buffer = Buffer(area)
      Paragraph(Text(Seq(Line(spans))), overflow = overflow).render(area, buffer)
      assert(trimmedLines(buffer) == Seq("aaaa", "aaaa"))
      assert(spans.touched <= 40, s"$overflow read ${spans.touched} spans for eight visible columns")

  test("a completed wrapped row is yielded before an invisible long whitespace gap"):
    for overflow <- Seq(Overflow.Wrap, Overflow.WrapTrimmed, Overflow.WrapPreserved) do
      val spans  = CountingSeq(Vector(Span.raw("aaaa")) ++ Vector.fill(10000)(Span.raw(" ")) :+ Span.raw("b"))
      val area   = Rect(0, 0, 4, 1)
      val buffer = Buffer(area)
      Paragraph(Text(Seq(Line(spans))), overflow = overflow).render(area, buffer)
      assert(trimmedLines(buffer) == Seq("aaaa"))
      assert(spans.touched <= 40, s"$overflow read ${spans.touched} spans before yielding a full row")

  test("bounded wrapped prefixes preserve split graphemes and their leading span style"):
    val red  = Style.Default.withFg(Color.Red)
    val blue = Style.Default.withFg(Color.Blue)
    for overflow <- Seq(Overflow.Wrap, Overflow.WrapTrimmed, Overflow.WrapPreserved) do
      val spans    = CountingSeq(
        Vector(
          Span("e", red),
          Span("\u0301", blue),
          Span.raw("你"),
          Span("👩", blue),
          Span("\u200d", red),
          Span("💻", red),
        ) ++ Vector.fill(10000)(Span.raw("a"))
      )
      val area     = Rect(0, 0, 4, 2)
      val buffer   = Buffer(area)
      val expected = Buffer(area)
      expected.setString(0, 0, "e\u0301", red)
      expected.setString(1, 0, "你", Style.Default)
      expected.setString(0, 1, "👩‍💻", blue)
      expected.setString(2, 1, "aa", Style.Default)
      Paragraph(Text(Seq(Line(spans))), overflow = overflow).render(area, buffer)
      for y <- 0 until 2; x <- 0 until 4 do assert(buffer.get(x, y) == expected.get(x, y), s"$overflow at ($x,$y)")
      assert(spans.touched <= 40, s"$overflow read ${spans.touched} spans for two Unicode rows")

  test("a fitting styled gap survives incremental wrapping before a discarded long gap"):
    val red  = Style.Default.withFg(Color.Red)
    val blue = Style.Default.withFg(Color.Blue)
    for overflow <- Seq(Overflow.Wrap, Overflow.WrapTrimmed, Overflow.WrapPreserved) do
      val spans  = CountingSeq(
        Vector(Span.raw("aa"), Span(" ", red), Span(" ", blue), Span.raw("b")) ++
          Vector.fill(10000)(Span.raw(" ")) :+ Span.raw("c")
      )
      val area   = Rect(0, 0, 5, 1)
      val buffer = Buffer(area)
      Paragraph(Text(Seq(Line(spans))), overflow = overflow).render(area, buffer)
      assert(trimmedLines(buffer) == Seq("aa  b"))
      assert(buffer.get(2, 0).style == red)
      assert(buffer.get(3, 0).style == blue)
      assert(spans.touched <= 40, s"$overflow read ${spans.touched} spans before yielding a styled row")

  test("stable Paragraph height measurement does not walk its content again"):
    val spans         = CountingSeq(Vector.fill(1000)(Span.raw("a ")))
    val paragraph     = Paragraph(Text(Seq(Line(spans))), overflow = Overflow.Wrap)
    val first         = paragraph.heightAt(4)
    val measuredReads = spans.touched
    assert(first.contains(500))
    assert(measuredReads > 1000)
    (1 to 10).foreach { _ => assert(paragraph.heightAt(4) == first) }
    assert(spans.touched == measuredReads, s"stable measurement added ${spans.touched - measuredReads} span reads")

  test("stable Paragraph natural width measurement does not walk its content again"):
    val spans         = CountingSeq(Vector.fill(1000)(Span.raw("你")))
    val paragraph     = Paragraph(Text(Seq(Line(spans))))
    assert(paragraph.widthAt(1).contains(2000))
    val measuredReads = spans.touched
    (1 to 10).foreach { height => assert(paragraph.widthAt(height).contains(2000)) }
    assert(spans.touched == measuredReads, s"stable width added ${spans.touched - measuredReads} span reads")

  test("Paragraph keeps only its latest measured width and copies measure fresh content"):
    val spans       = CountingSeq(Vector.fill(1000)(Span.raw("a ")))
    val paragraph   = Paragraph(Text(Seq(Line(spans))), overflow = Overflow.Wrap)
    assert(paragraph.heightAt(4).contains(500))
    val firstReads  = spans.touched
    assert(paragraph.heightAt(8).contains(250))
    assert(spans.touched > firstReads)
    val secondReads = spans.touched
    assert(paragraph.heightAt(8).contains(250))
    assert(spans.touched == secondReads)
    assert(paragraph.heightAt(4).contains(500))
    assert(spans.touched > secondReads, "old widths must not accumulate in an unbounded cache")
    val copied      = paragraph.copy(text = Text.raw("abcde"))
    assert(copied.heightAt(4).contains(2))
    assert(copied.widthAt(1).contains(5))
    assert(paragraph.copy(overflow = Overflow.Clip).heightAt(4).contains(1))
    assert(paragraph.heightAt(4).contains(500), "copies must not alter their source value's cache")

  test("wrapped viewport work scales with skipped rows, not the rest of a source line"):
    val spans  = CountingSeq(Vector.fill(10000)(Span.raw("你")))
    val area   = Rect(0, 0, 4, 2)
    val buffer = Buffer(area)
    Paragraph(Text(Seq(Line(spans))), overflow = Overflow.Wrap, scrollY = 3).render(area, buffer)
    assert(trimmedLines(buffer) == Seq("你你", "你你"))
    assert(spans.touched <= 40, s"read ${spans.touched} spans to skip three and draw two rows")

  test("DataTable does not re-sort while only the scroll offset moves"):
    val data   = Vector.tabulate(500)(i => Seq(i.toString, s"item ${(i * 37) % 500}"))
    val table  = DataTable.fromStrings(Seq("id", "name"), data, widths)
    val state  = DataTableState()
    state.sort = Some(ColumnSort(1, SortDirection.Ascending))
    val first  = table.filteredRows(state)
    state.offset = 42
    val second = table.filteredRows(state)
    assert(first eq second, "scrolling recomputed the sorted view")

  test("DataTable recomputes when the sort, direction or filter changes"):
    val data   = Vector.tabulate(50)(i => Seq(i.toString, s"item ${(i * 7) % 50}"))
    val table  = DataTable.fromStrings(Seq("id", "name"), data, widths)
    val state  = DataTableState()
    val plain  = table.filteredRows(state)
    state.sortBy(1)
    val sorted = table.filteredRows(state)
    assert(sorted ne plain)
    assert(sorted.map(_.cells(1)) == sorted.map(_.cells(1)).sorted)
    state.sortBy(1) // same column again flips direction
    val reversed = table.filteredRows(state)
    assert(reversed.map(_.cells(1)) == sorted.map(_.cells(1)).reverse)
    state.setFilter("item 1")
    val filtered = table.filteredRows(state)
    assert(filtered.forall(_.cells.exists(_.contains("item 1"))))
    assert(filtered.size < data.size)

  test("DataTable refreshes automatically after swapping same-length data"):
    val first  = Vector(Seq("1", "b"), Seq("2", "a"))
    val second = Vector(Seq("1", "z"), Seq("2", "y"))
    val state  = DataTableState()
    state.sort = Some(ColumnSort(1, SortDirection.Ascending))
    assert(DataTable.fromStrings(Seq("id", "name"), first, widths).filteredRows(state).map(_.cells(1)) == Seq("a", "b"))
    assert(
      DataTable.fromStrings(Seq("id", "name"), second, widths).filteredRows(state).map(_.cells(1)) == Seq("y", "z")
    )

  test("ListView derives a uniform list's scroll offset without materializing per-item heights"):
    // the scroll arithmetic must cost what the viewport costs, not what the dataset costs: a uniform 10 000-item
    // list used to build a boxed height for every item on every frame just to discover they were all one
    val data   = Vector.tabulate(10000)(i => s"item $i")
    val items  = CountingSeq(data)
    val buffer = Buffer(Rect(0, 0, 20, 10))
    val state  = ListState(selected = Some(9999), scrollPadding = 2)
    ListView(items).render(Rect(0, 0, 20, 10), buffer, state)
    assert(items.mapped == 0, "a uniform list materialized per-item heights to compute its offset")
    assert(state.offset == 9990, "the window still lands where ScrollWindow.offsetFor puts it")
    assert(trimmedLines(buffer).last == "> item 9999", "the same window renders, selection included")

  test("ListView still materializes per-item heights when a multi-row item needs them"):
    val data  = Vector.tabulate(100)(i =>
      if i == 50 then Text(Seq(Line.raw("tall"), Line.raw("tall2"))) else Line.raw(s"item $i")
    )
    val items = CountingSeq(data)
    val state = ListState(selected = Some(50))
    ListView(items).render(Rect(0, 0, 20, 3), Buffer(Rect(0, 0, 20, 3)), state)
    assert(items.mapped == 1, "the row-counting branch must build the heights it scrolls by")
    assert(state.offset == 49, "the tall item's last row is what the window scrolls to show")
