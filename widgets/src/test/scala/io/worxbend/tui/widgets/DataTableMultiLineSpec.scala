package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Constraint, Modifiers, Rect}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** `DataTableOptions(multiLine = true)`: a cell's `\n` starts another row of the same record, and the record is as tall
  * as its tallest cell — scrolling, selection and `rowAt` all count those rows.
  */
final class DataTableMultiLineSpec extends AnyFunSuite:

  private val columns = Seq("c1", "c2")
  private val widths  = Seq(Constraint.Length(3), Constraint.Length(3))
  private val rows    = Seq(KeyedRow(1, Seq("a", "x\ny")), KeyedRow(2, Seq("b", "z")), KeyedRow(3, Seq("c\nd\ne", "w")))
  private val table   = DataTable(columns, rows, widths, DataTableOptions(multiLine = true))

  test("a record is as tall as its tallest cell, the shorter cells blank below"):
    assert(
      trimmedLines(rendered(table, DataTableState(), 7, 7)) ==
        Seq("c1  c2", "a   x", "    y", "b   z", "c   w", "d", "e")
    )

  test("without multiLine a newline does not add rows"):
    val flat = DataTable(columns, rows, widths)
    assert(trimmedLines(rendered(flat, DataTableState(), 7, 4)).size == 4)
    assert(trimmedLines(rendered(flat, DataTableState(), 7, 4))(2) == "b   z")

  test("the selection highlights every row of its record and scrolls the record's rows into view"):
    val state  = DataTableState()
    state.selected = Some(2)
    val buffer = rendered(table, state, 7, 4) // header plus three body rows: exactly the three-row record
    assert(trimmedLines(buffer) == Seq("c1  c2", "c   w", "d", "e"))
    assert(state.offset == 2)
    assert((1 to 3).forall(y => buffer.get(0, y).style.modifiers.hasAny(Modifiers.Reverse)))

  test("rowAt counts the rows of every record above the pointer"):
    val state = DataTableState()
    val area  = Rect(0, 0, 7, 7)
    val _     = rendered(table, state, 7, 7)
    assert(
      (0 to 6).map(y => table.rowAt(area, y, state)) == Seq(None, Some(0), Some(0), Some(1), Some(2), Some(2), Some(2))
    )
