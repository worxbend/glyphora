package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Constraint, Line, Rect, Span, Style, Text}
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
