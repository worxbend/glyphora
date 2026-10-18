package io.worxbend.tui.widgets

import io.worxbend.tui.core.Constraint
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** A [[DataTable]] over a [[TableRows]] provider must cost the body it paints, not the million rows behind it. Work is
  * counted — how many rows the provider was asked for — rather than timed.
  */
final class DataTableLazyRowsSpec extends AnyFunSuite:

  /** A million rows that exist only when asked for, counting every one that was. */
  private final class Million extends TableRows[Int]:
    var touched: Int                     = 0
    def count: Int                       = 1_000_000
    def apply(index: Int): KeyedRow[Int] =
      touched += 1
      KeyedRow(index, Seq(index.toString, s"row $index"))

  private val columns = Seq("id", "name")
  private val widths  = Seq(Constraint.Length(8), Constraint.Fill(1))
  private val height  = 12 // one header row and eleven body rows
  private val body    = height - 1

  test("rendering a million-row provider asks for the visible rows and nothing else"):
    val rows   = Million()
    val state  = DataTableState()
    val buffer = rendered(DataTable(columns, rows, widths), state, 40, height)
    assert(trimmedLines(buffer).take(3) == Seq("id       name", "0        row 0", "1        row 1"))
    assert(rows.touched <= body + 1, s"drawing $body body rows asked the provider for ${rows.touched}")

  test("a selection deep in the data is drawn and re-anchored without a scan"):
    val rows  = Million()
    val state = DataTableState()
    state.selected = Some(999_990)
    val first = rendered(DataTable(columns, rows, widths), state, 40, height)
    assert(trimmedLines(first).last == "999990   row 999990", "the selection is nudged onto the bottom row")
    assert(rows.touched <= body + 2, s"the first frame asked for ${rows.touched} rows")
    rows.touched = 0
    val again = rendered(DataTable(columns, rows, widths), state, 40, height)
    assert(trimmedLines(again) == trimmedLines(first))
    assert(state.selected.contains(999_990))
    assert(rows.touched <= body + 2, s"re-anchoring the selection asked the provider for ${rows.touched} rows")

  test("the provider's count is the row count every state repair sees"):
    val rows  = Million()
    val table = DataTable(columns, rows, widths)
    val state = DataTableState()
    assert(table.visibleRows(state).size == 1_000_000)
    state.selectLast(table.visibleRows(state).size)
    val _     = rendered(table, state, 40, height)
    assert(state.selected.contains(999_999))
    assert(table.selectedKey(state).contains(999_999))
    assert(rows.touched <= body + 2)

  test("paging windows the provider without reading past the page"):
    val rows   = Million()
    val state  = DataTableState()
    state.paging = Some(Paging(size = 5, page = 3))
    val buffer = rendered(DataTable(columns, rows, widths), state, 40, height)
    assert(
      trimmedLines(buffer).slice(1, 6) == Seq(
        "15       row 15",
        "16       row 16",
        "17       row 17",
        "18       row 18",
        "19       row 19",
      )
    )
    assert(trimmedLines(buffer).drop(6).forall(_.isEmpty))
    assert(rows.touched <= 5)

  test("printing a provider does not walk it"):
    val rows = Million()
    assert(rows.toString == "TableRows(count = 1000000)")
    assert(rows.touched == 0)

  test("a plain row sequence is a provider already: the table takes either"):
    val plain        = Vector(KeyedRow(1, Seq("1", "one")), KeyedRow(2, Seq("2", "two")))
    val fromSeq      = DataTable(columns, plain, widths)
    val fromProvider = DataTable(columns, TableRows.from(plain), widths)
    val state        = DataTableState()
    assert(trimmedLines(rendered(fromSeq, state, 40, 4)) == trimmedLines(rendered(fromProvider, state, 40, 4)))
