package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Color, Constraint, Flex, Modifiers, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class DataTableSpec extends AnyFunSuite:

  private val table = DataTable.fromStrings(
    columns = Seq("name", "size"),
    rows = Seq(Seq("beta", "20"), Seq("alpha", "100"), Seq("gamma", "3")),
    widths = Seq(Constraint.Length(8), Constraint.Length(6)),
  )

  /** The `copy` a table's options need now that they travel as a [[DataTableOptions]]. */
  private def withOptions(table: DataTable[Int])(change: DataTableOptions => DataTableOptions): DataTable[Int] =
    table.copy(options = change(table.options))

  test("renders the header row then the data rows"):
    val lines = trimmedLines(rendered(table, DataTableState(), 15, 4))
    assert(lines == Seq("name     size", "beta     20", "alpha    100", "gamma    3"))

  test("sorting by a text column orders rows and marks the header"):
    val state = DataTableState()
    state.sortBy(0)
    val lines = trimmedLines(rendered(table, state, 15, 4))
    assert(lines.head.startsWith("name ▲"))
    assert(lines.drop(1) == Seq("alpha    100", "beta     20", "gamma    3"))

  test("sorting the same column again flips the direction"):
    val state = DataTableState()
    state.sortBy(0)
    state.sortBy(0)
    val lines = trimmedLines(rendered(table, state, 15, 4))
    assert(lines.head.startsWith("name ▼"))
    assert(lines(1).startsWith("gamma"))

  test("numeric columns sort as numbers, not text"):
    val state = DataTableState()
    state.sortBy(1)
    val lines = trimmedLines(rendered(table, state, 15, 4))
    assert(lines.drop(1) == Seq("gamma    3", "beta     20", "alpha    100"))

  test("the filter keeps rows where any cell matches, case-insensitively"):
    val state   = DataTableState()
    state.setFilter("A")
    val visible = table.visibleRows(state)
    assert(visible.map(_.cells.head) == Seq("beta", "alpha", "gamma")) // all contain 'a'
    state.setFilter("alph")
    assert(table.visibleRows(state).map(_.cells.head) == Seq("alpha"))

  test("filter and sort compose"):
    val state = DataTableState()
    state.setFilter("a")
    state.sortBy(1)
    assert(table.visibleRows(state).map(_.cells.head) == Seq("gamma", "beta", "alpha"))

  test("the selected view row is highlighted and selection scrolls the body"):
    val state = DataTableState()
    state.selectNext(3)
    state.selectNext(3)
    state.selectNext(3) // clamped at the last row
    val buffer = rendered(table, state, 15, 3) // header + 2 body rows
    assert(state.selected.contains(2))
    assert(state.offset == 1)
    assert(trimmedLines(buffer)(2) == "gamma    3")
    assert(buffer.get(0, 2).style.modifiers.hasAny(Modifiers.Reverse))

  test("an empty filter result renders only the header"):
    val state = DataTableState()
    state.setFilter("zzz")
    val lines = trimmedLines(rendered(table, state, 15, 4))
    assert(lines == Seq("name     size", "", "", ""))

  for (width, height) <- Seq((12, 4), (12, 1), (12, 2), (0, 4), (12, 0))
  do
    test(s"an empty refresh clears the row selection, key and offset in a $width x $height area"):
      val populated = DataTable(
        Seq("name"),
        Seq(KeyedRow("first", Seq("alpha")), KeyedRow("last", Seq("omega"))),
        Seq(Constraint.Fill(1)),
        DataTableOptions(footer = Some(Seq("total"))),
      )
      val state     = new DataTableState[String]
      assert(populated.selectKey(state, "last"))
      val _         = rendered(populated, state, 12, 3)
      assert(state.selected.contains(1))
      assert(populated.selectedKey(state).contains("last"))
      assert(state.offset == 1)

      val empty = populated.copy(rows = Seq.empty[KeyedRow[String]])
      val _     = rendered(empty, state, width, height)
      assert(state.selected.isEmpty)
      assert(empty.selectedKey(state).isEmpty)
      assert(state.offset == 0)

  for (width, height) <- Seq((12, 1), (12, 2), (0, 4), (12, 0))
  do
    test(s"a $width x $height area reconciles keys on refresh without painting body rows"):
      val original = DataTable(
        Seq("name"),
        Seq(KeyedRow("first", Seq("alpha")), KeyedRow("last", Seq("omega"))),
        Seq(Constraint.Fill(1)),
        DataTableOptions(footer = Some(Seq("total"))),
      )
      val state    = new DataTableState[String]
      assert(original.selectKey(state, "last"))
      val _        = rendered(original, state, 12, 3)

      state.invalidate()
      val reordered = original.copy(rows = original.rows.reverse)
      val _         = rendered(reordered, state, width, height)
      assert(state.selected.contains(0))
      assert(reordered.selectedKey(state).contains("last"))
      assert(state.offset == 0)

      val removed = original.copy(rows = Seq(original.rows.head))
      val _       = rendered(removed, state, width, height)
      assert(state.selected.contains(0))
      assert(removed.selectedKey(state).contains("first"))
      assert(state.offset == 0)

  test("a page size windows the visible rows and paging clamps at the ends"):
    val state = DataTableState()
    state.paging = Some(Paging(size = 2, page = 0))
    assert(table.visibleRows(state).map(_.cells.head) == Seq("beta", "alpha"))
    state.nextPage(table.filteredRows(state).size)
    assert(state.paging.map(_.page).contains(1))
    assert(table.visibleRows(state).map(_.cells.head) == Seq("gamma"))
    state.nextPage(table.filteredRows(state).size) // clamped: already the last page
    assert(state.paging.map(_.page).contains(1))
    state.previousPage()
    assert(state.paging.map(_.page).contains(0))

  test("paging is a no-op while no page size is set"):
    val state = DataTableState()
    state.nextPage(3)
    state.previousPage()
    assert(state.paging.isEmpty)
    assert(table.visibleRows(state).sizeIs == 3)

  test("filtering shrinks the page domain and the visible page snaps back into range"):
    val state = DataTableState()
    state.paging = Some(Paging(size = 2, page = 1))
    state.setFilter("alph")
    assert(table.visibleRows(state).map(_.cells.head) == Seq("alpha")) // page snapped back into range

  test("reading the visible rows leaves the state alone; rendering repairs the out-of-range page"):
    val state = DataTableState()
    state.paging = Some(Paging(size = 2, page = 4))
    val _     = table.visibleRows(state)
    assert(state.paging.map(_.page).contains(4)) // a read is only a read
    val _ = rendered(table, state, 15, 4)
    assert(state.paging.map(_.page).contains(1)) // three rows over two-row pages: page 1 is the last

  test("no highlight symbol reserves no gutter, so the columns start at the area's left edge"):
    val state = DataTableState()
    state.selected = Some(1)
    val lines = trimmedLines(rendered(table, state, 15, 4))
    assert(lines == Seq("name     size", "beta     20", "alpha    100", "gamma    3"))

  test("a highlight symbol marks the selected row and pads every other one"):
    val state    = DataTableState()
    state.selected = Some(1)
    val withMark = withOptions(table)(_.copy(highlightSymbol = "> "))
    val buffer   = rendered(withMark, state, 17, 4)
    // the gutter is two columns wide on every row, so the header and the body stay in one grid
    assert(trimmedLines(buffer) == Seq("  name     size", "  beta     20", "> alpha    100", "  gamma    3"))

  test("a wide highlight symbol reserves its display width, not its character count"):
    // "選" is a single character but occupies two terminal columns, which is what a naive `length` gets wrong
    val narrow = withOptions(table)(_.copy(highlightSymbol = "→"))
    val wide   = withOptions(table)(_.copy(highlightSymbol = "選"))
    assert(trimmedLines(rendered(narrow, DataTableState(), 17, 2)).head == " name     size")
    assert(trimmedLines(rendered(wide, DataTableState(), 17, 2)).head == "  name     size")

  test("the highlight symbol is styled with the selected row's style"):
    val state  = DataTableState()
    state.selected = Some(0)
    val buffer = rendered(withOptions(table)(_.copy(highlightSymbol = "> ")), state, 17, 4)
    assert(buffer.get(0, 1).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(0, 2).style.modifiers.hasAny(Modifiers.Reverse))

  test("a highlight symbol wider than the whole area leaves no room for cells and draws no garbage"):
    val state  = DataTableState()
    state.selected = Some(0)
    val buffer = rendered(withOptions(table)(_.copy(highlightSymbol = ">>>>>>")), state, 3, 2)
    assert(trimmedLines(buffer) == Seq("", ">>>"))

  test("no widths at all gives every named column an equal share of the area"):
    val equal  = DataTable.fromStrings(
      columns = Seq("a", "b"),
      rows = Seq(Seq("1", "2")),
      widths = Seq.empty,
      options = DataTableOptions(columnSpacing = 0),
    )
    val buffer = rendered(equal, DataTableState(), 8, 2)
    assert(buffer.get(0, 0).symbol == "a")
    assert(buffer.get(4, 0).symbol == "b") // two four-column halves of an eight-column area
    assert(buffer.get(4, 1).symbol == "2")

  test("flex places the leftover width when the columns are all fixed"):
    val centred = withOptions(table)(_.copy(flex = Flex.Center))
    // eight plus six plus one cell of spacing is fifteen columns of content in a twenty-one column area
    assert(rendered(centred, DataTableState(), 21, 2).get(3, 0).symbol == "n")
    assert(rendered(table, DataTableState(), 21, 2).get(0, 0).symbol == "n")

  test("the highlight gutter is taken off before the flex distributes what is left"):
    val centred = withOptions(table)(_.copy(flex = Flex.Center, highlightSymbol = "> "))
    // the gutter takes two columns, so the fifteen columns of content centre in the remaining nineteen
    assert(rendered(centred, DataTableState(), 21, 2).get(4, 0).symbol == "n")

  test("a footer is pinned to the bottom and shortens the scrollable body"):
    val totals = withOptions(table)(_.copy(footer = Some(Seq("total", "123"))))
    val lines  = trimmedLines(rendered(totals, DataTableState(), 15, 6))
    assert(lines == Seq("name     size", "beta     20", "alpha    100", "gamma    3", "", "total    123"))

  test("the body scrolls within the height the footer leaves it"):
    val totals = withOptions(table)(_.copy(footer = Some(Seq("total", "123"))))
    val state  = DataTableState()
    state.selected = Some(2)
    // four rows of area, one header and one footer: the body has two lines, so the third row scrolls into view
    val lines  = trimmedLines(rendered(totals, state, 15, 4))
    assert(lines == Seq("name     size", "alpha    100", "gamma    3", "total    123"))

  test("a one-row area drops the footer rather than overwriting the header"):
    val totals = withOptions(table)(_.copy(footer = Some(Seq("total", "123"))))
    assert(trimmedLines(rendered(totals, DataTableState(), 15, 1)) == Seq("name     size"))

  test("a column cursor draws nothing until the table is given a column highlight style"):
    val state = DataTableState()
    state.selectedColumn = Some(1)
    val plain = rendered(table, state, 15, 4)
    assert(!plain.get(9, 1).style.modifiers.hasAny(Modifiers.Underline))
    assert(trimmedLines(plain) == Seq("name     size", "beta     20", "alpha    100", "gamma    3"))

  test("the column highlight style paints every body cell in the selected column"):
    val state  = DataTableState()
    state.selectedColumn = Some(1)
    val cursor = withOptions(table)(_.copy(columnHighlightStyle = Some(Style.Default.underline)))
    val buffer = rendered(cursor, state, 15, 4)
    assert(buffer.get(9, 1).style.modifiers.hasAny(Modifiers.Underline))
    assert(!buffer.get(0, 1).style.modifiers.hasAny(Modifiers.Underline)) // the unselected column is untouched
    assert(!buffer.get(9, 0).style.modifiers.hasAny(Modifiers.Underline)) // the header keeps its own style

  test("the cell highlight style is layered last, over the crossing of the selected row and column"):
    val state  = DataTableState()
    state.selectCell(1, 1)
    val cursor = withOptions(table)(
      _.copy(
        columnHighlightStyle = Some(Style.Default.underline),
        cellHighlightStyle = Some(Style.Default.bold),
      )
    )
    val buffer = rendered(cursor, state, 15, 4)
    assert(buffer.get(9, 2).style.modifiers.hasAny(Modifiers.Bold))      // the crossing cell
    assert(buffer.get(9, 1).style.modifiers.hasAny(Modifiers.Underline)) // same column, different row
    assert(!buffer.get(9, 1).style.modifiers.hasAny(Modifiers.Bold))
    assert(buffer.get(0, 2).style.modifiers.hasAny(Modifiers.Reverse))   // same row, different column
    assert(!buffer.get(0, 2).style.modifiers.hasAny(Modifiers.Bold))

  test("the column cursor clamps to the last column rather than pointing past it"):
    val state = DataTableState()
    state.selectedColumn = Some(9)
    val _     = rendered(withOptions(table)(_.copy(columnHighlightStyle = Some(Style.Default.underline))), state, 15, 4)
    assert(state.selectedColumn.contains(1))

  test("column navigation clamps at both ends the way row navigation does"):
    val state = DataTableState()
    state.selectPreviousColumn(2)
    assert(state.selectedColumn.contains(0)) // nothing selected yet: the first move lands on the first column
    state.selectNextColumn(2)
    state.selectNextColumn(2)
    assert(state.selectedColumn.contains(1)) // clamped at the last column
    state.selectPreviousColumn(2)
    state.selectPreviousColumn(2)
    assert(state.selectedColumn.contains(0))

  test("column navigation with no columns leaves the cursor alone"):
    val state = DataTableState()
    state.selectNextColumn(0)
    assert(state.selectedColumn.isEmpty)

  test("selectCell moves both halves of the cursor at once and clamps negatives"):
    val state = DataTableState()
    state.selectCell(-1, -3)
    assert(state.selected.contains(0))
    assert(state.selectedColumn.contains(0))

  test("cellStyle colours one column and leaves the rest of the row alone"):
    val flagged = withOptions(table)(
      _.copy(cellStyle =
        (row, column) => if column == 1 && row(1) == "3" then Style.Default.withFg(Color.Red) else Style.Default
      )
    )
    val buffer  = rendered(flagged, DataTableState(), 15, 4)
    // "gamma" sits on row 3 with size "3"; only its size cell is red, and its name cell is untouched
    assert(buffer.get(9, 3).style.fg.contains(Color.Red))
    assert(buffer.get(0, 3).style.fg.isEmpty)
    assert(buffer.get(9, 1).style.fg.isEmpty)

  test("cellStyle follows the record and not the row position when the sort reverses"):
    // this is the whole reason it is asked about the row's contents. Sorting by name puts "gamma" last, sorting again
    // puts it first, and the red cell has to travel with it.
    val flagged   = withOptions(table)(
      _.copy(cellStyle =
        (row, column) => if column == 1 && row(0) == "gamma" then Style.Default.withFg(Color.Red) else Style.Default
      )
    )
    val ascending = DataTableState()
    ascending.sortBy(0)
    assert(rendered(flagged, ascending, 15, 4).get(9, 3).style.fg.contains(Color.Red))
    ascending.sortBy(0) // the same column again flips the direction
    assert(rendered(flagged, ascending, 15, 4).get(9, 1).style.fg.contains(Color.Red))

  test("cellStyle is layered over the selection highlight rather than replacing it"):
    val flagged =
      withOptions(table)(
        _.copy(cellStyle = (_, column) => if column == 1 then Style.Default.withFg(Color.Red) else Style.Default)
      )
    val state   = DataTableState()
    state.selected = Some(0)
    val cell    = rendered(flagged, state, 15, 4).get(9, 1)
    assert(cell.style.fg.contains(Color.Red))
    assert(cell.style.modifiers.hasAny(Modifiers.Reverse))

  test("cellStyle is never asked about the header or the footer"):
    val withFooter =
      withOptions(table)(
        _.copy(footer = Some(Seq("total", "123")), cellStyle = (_, _) => Style.Default.withFg(Color.Red))
      )
    val buffer     = rendered(withFooter, DataTableState(), 15, 5)
    assert(buffer.get(0, 0).style.fg.isEmpty) // the header caption
    assert(buffer.get(0, 4).style.fg.isEmpty) // the footer, pinned to the bottom row
    assert(buffer.get(0, 1).style.fg.contains(Color.Red))

  test("the default cellStyle changes nothing about how a table renders"):
    val plain   = rendered(table, DataTableState(), 15, 4)
    val spelled = rendered(withOptions(table)(_.copy(cellStyle = (_, _) => Style.Default)), DataTableState(), 15, 4)
    assert(plain.diff(spelled).isEmpty)
