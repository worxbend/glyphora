package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Color, Constraint, Rect, Style}

import org.scalatest.funsuite.AnyFunSuite

final class DataTableGutterSpec extends AnyFunSuite:

  for multiLine <- Seq(false, true) do
    test(s"gutter preserves source callbacks, style order and first-line prefix with multiLine=$multiLine"):
      val sentinel     = Cell(".", Style.Default.withBg(Color.Magenta))
      val buffer       = Buffer.filled(Rect(0, 0, 14, 8), sentinel)
      val expected     = buffer.snapshot
      val base         = Style.Default.withFg(Color.White).withBg(Color.Blue)
      val highlight    = Style.Default.withFg(Color.Yellow).bold
      val column       = Style.Default.withFg(Color.Green).underline
      val intersection = Style.Default.withBg(Color.Red)
      val custom       = Style.Default.withFg(Color.Cyan)
      val header       = Style.Default.withFg(Color.Red)
      val footer       = Style.Default.withFg(Color.Green).bold
      val selected     = base.patch(highlight)
      val gamma        = Seq(if multiLine then "g\nG" else "g", "3")
      val beta         = Seq(if multiLine then "b\nB" else "b", "2")
      val calls        = scala.collection.mutable.ArrayBuffer.empty[(Seq[String], Int, Cell, Cell)]
      val table        = DataTable.fromStrings(
        Seq("A", "B"),
        Seq(beta, gamma),
        Seq(Constraint.Length(3), Constraint.Length(3)),
        DataTableOptions(
          footer = Some(Seq("sum", "5")),
          multiLine = multiLine,
          style = base,
          headerStyle = header,
          footerStyle = footer,
          highlightStyle = highlight,
          columnHighlightStyle = Some(column),
          cellHighlightStyle = Some(intersection),
          cellStyle = (source, index) =>
            val physicalLine = calls.size / 2
            calls += ((source, index, buffer.get(2, 2 + physicalLine), buffer.get(4, 5)))
            custom
          ,
          highlightSymbol = "界",
        ),
      )
      val state        = DataTableState()
      state.sort = Some(ColumnSort(0, SortDirection.Descending))
      state.selectCell(0, 1)
      table.render(Rect(2, 1, 9, 5), buffer, state)
      val _            = expected.setString(4, 1, "A ▼", header)
      val _            = expected.setString(8, 1, "B", header)
      val _            = expected.setString(4, 5, "sum", footer)
      val _            = expected.setString(8, 5, "5", footer)
      val body         =
        if multiLine then
          Seq((gamma, "g", "3", true, true), (gamma, "G", "", true, false), (beta, "b", "2", false, false))
        else Seq((gamma, "g", "3", true, true), (beta, "b", "2", false, false))
      body.zipWithIndex.foreach { case ((_, left, right, isSelected, prefix), line) =>
        val rowStyle      = if isSelected then selected else base
        val rightStyle    = rowStyle.patch(column)
        val resolvedRight = if isSelected then rightStyle.patch(intersection) else rightStyle
        val _             = expected.setString(2, 2 + line, if prefix then "界" else "  ", rowStyle)
        val _             = expected.setString(4, 2 + line, left, rowStyle.patch(custom))
        val _             = expected.setString(8, 2 + line, right, resolvedRight.patch(custom))
      }
      assert(calls.toSeq == body.zipWithIndex.flatMap { case ((source, _, _, _, _), line) =>
        (0 to 1).map(index => (source, index, expected.get(2, 2 + line), expected.get(4, 5)))
      })
      for y <- 0 until 8; x <- 0 until 14 do assert(buffer.get(x, y) == expected.get(x, y), s"cell ($x,$y)")

  test("a clipped wide gutter cannot overrun a one-column table or consult cellStyle"):
    val sentinel = Cell(".", Style.Default.withBg(Color.Blue))
    val buffer   = Buffer.filled(Rect(0, 0, 6, 5), sentinel)
    var calls    = 0
    val table    = DataTable.fromStrings(
      Seq("head"),
      Seq(Seq("body")),
      Seq(Constraint.Fill(1)),
      DataTableOptions(
        highlightSymbol = "界",
        cellStyle = (_, _) =>
          calls += 1
          Style.Default,
      ),
    )
    val state    = DataTableState()
    state.selected = Some(0)
    table.render(Rect(2, 1, 1, 2), buffer, state)
    assert(calls == 0)
    for y <- 0 until 5; x <- 0 until 6 do assert(buffer.get(x, y) == sentinel)
