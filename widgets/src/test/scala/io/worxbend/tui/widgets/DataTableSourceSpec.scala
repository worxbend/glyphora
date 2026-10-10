package io.worxbend.tui.widgets

import io.worxbend.tui.testsupport.BufferAssertions.rendered
import org.scalatest.funsuite.AnyFunSuite

final class DataTableSourceSpec extends AnyFunSuite:
  test("same-count refresh reapplies filters and reanchors keyed selection without invalidation"):
    val state   = new DataTableState[String]
    state.setFilter("keep")
    state.sortBy(0)
    val first   = DataTable(Seq("name"), Vector(KeyedRow("a", Seq("keep 1")), KeyedRow("b", Seq("keep 2"))), Seq.empty)
    assert(first.selectKey(state, "a"))
    val _       = rendered(first, state, 20, 4)
    val fresh   = first.copy(rows = Vector(KeyedRow("a", Seq("keep 9")), KeyedRow("b", Seq("keep 0"))))
    val _       = rendered(fresh, state, 20, 4)
    assert(state.selected.contains(1))
    assert(fresh.selectedKey(state).contains("a"))
    val removed = fresh.copy(rows = Vector(KeyedRow("a", Seq("gone")), KeyedRow("b", Seq("gone"))))
    val _       = rendered(removed, state, 20, 4)
    assert(state.selected.isEmpty)
    assert(removed.selectedKey(state).isEmpty)

  test("rebuilding widget metadata with the same immutable source reuses the view"):
    val state  = DataTableState()
    state.sortBy(0)
    val table  = DataTable(Seq("name"), Vector(KeyedRow(1, Seq("z")), KeyedRow(2, Seq("a"))), Seq.empty)
    val cached = table.filteredRows(state)
    state.offset = 1
    state.paging = Some(Paging(1, 1))
    assert(table.copy(columns = Seq("new heading")).filteredRows(state) eq cached)
    assert(table.selectKey(state, 1))
    state.invalidate()
    assert(table.filteredRows(state) ne cached)
    assert(table.selectedKey(state).contains(1))

  test("a cache hit neither traverses nor compares nor hashes the source"):
    final class Source extends Seq[KeyedRow[Int]]:
      var touched                              = 0
      def length: Int                          = { touched += 1; 2 }
      def apply(index: Int): KeyedRow[Int]     = { touched += 1; KeyedRow(index, Seq(index.toString)) }
      def iterator: Iterator[KeyedRow[Int]]    = Iterator(apply(0), apply(1))
      override def equals(other: Any): Boolean = { touched += 1; super.equals(other) }
      override def hashCode(): Int             = { touched += 1; super.hashCode() }
    val source = new Source
    val table  = DataTable(Seq("n"), source, Seq.empty)
    val state  = DataTableState()
    state.sortBy(0)
    val cached = table.filteredRows(state)
    source.touched = 0
    assert(table.copy().filteredRows(state) eq cached)
    assert(source.touched == 0)
