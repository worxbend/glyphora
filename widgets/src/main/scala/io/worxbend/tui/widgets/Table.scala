package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Buffer, Constraint, Direction, Flex, Layout, Line, Rect, Style, Widget}

/** Rows of cells laid out in columns sized by the core constraint solver.
  *
  * Cells are never wrapped, and rows past the area's bottom edge are clipped, matching the library-wide silent-clipping
  * philosophy.
  *
  * A cell carrying an alignment of its own — `Line.raw("42").rightAligned`, and likewise `centered` — is placed that
  * way inside its own column instead of being written flush against the column's left edge. That is how a column of
  * numbers lines up on its last digit. A cell that says nothing about alignment is left-aligned, which is what every
  * cell did before. The placement is per cell rather than per column because a `Line` already had somewhere to record
  * it, and a header caption often wants a different placement from the figures underneath it.
  *
  * @param rows
  *   each row is either a bare sequence of cells — one terminal line tall — or a [[TableRow]], which adds a height, top
  *   and bottom margins, and a per-row style. A cell in either shape is a [[io.worxbend.tui.core.Line Line]] covering
  *   one column, or a [[TableCell]] covering several. All the shapes may appear in the same table; see [[TableRow]] and
  *   [[TableCell]] for what the extra room and the spans are for.
  *
  * @param widths
  *   one [[io.worxbend.tui.core.Constraint Constraint]] per column. An empty sequence means "equal columns": the table
  *   counts the cells in the header and in the rows it is about to draw, and gives each column an equal share of the
  *   area. That fallback exists because an empty sequence used to render a blank rectangle, and the DSL's `table(rows)`
  *   — whose widths are varargs, so writing none of them is a legal call — landed straight in it.
  * @param footer
  *   an optional summary row — a totals line, a "last updated" note — pinned to the *bottom* of the area rather than
  *   following the last data row. Pinning it is the point: a table whose rows do not fill the area would otherwise
  *   leave the totals floating in the middle of the pane. It is laid out on the same solved columns as the body, which
  *   is what a separate `Table` stacked underneath could never guarantee — the moment the widths changed, the two
  *   drifted apart. It costs one row of body height. On an area one row tall that already has a header there is no
  *   bottom row left to pin it to, so the header wins and the footer is dropped.
  * @param footerStyle
  *   the style the footer row is drawn in, bold by default, matching the header.
  * @param flex
  *   where the columns sit when they do not fill the area. Constraints like `Length(8)` can leave the table narrower
  *   than the space it was given; before this parameter the leftover always trailed off the right-hand side, because
  *   the widget passed no flex to the layout and took the [[io.worxbend.tui.core.Flex.Start Flex.Start]] default. Now a
  *   table of fixed-width columns can be centred or right-aligned in its area, the same way a `row` can. Has no effect
  *   when a `Fill` or `Min` column is already absorbing the leftover, because then there is none.
  */
final case class Table(
    rows: Seq[TableRow.Source],
    widths: Seq[Constraint],
    header: Option[Seq[TableCell.Source]] = None,
    footer: Option[Seq[TableCell.Source]] = None,
    columnSpacing: Int = 1,
    flex: Flex = Flex.Start,
    style: Style = Style.Default,
    headerStyle: Style = Style.Default.bold,
    footerStyle: Style = Style.Default.bold,
) extends Widget:

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      val headerRows  = if header.isDefined then 1 else 0
      // the footer is pinned to the bottom, so it is only drawn when there is a row down there that the header is not
      // already using: on a one-row area with a header, the header wins and the footer is dropped
      val footerRows  = if footer.isDefined && area.height > headerRows then 1 else 0
      // bounded by the area, not the data: drawing 50 visible rows must not walk a 10 000-row Seq
      val body        = TableRow.fitting(rows.iterator, math.max(0, area.height - headerRows - footerRows))
      // the fallback only walks the rows that are about to be drawn, which is why `body` is taken first
      val cellCounts  = (header.iterator ++ body.iterator.map(_.cells) ++ footer.iterator).map(TableCell.columnCount)
      val constraints = TableColumns.resolve(widths, cellCounts, area.width)
      val columns     = Layout(Direction.Horizontal, constraints, columnSpacing, flex).split(area)
      var y           = area.y
      header.foreach { cells =>
        if y < area.bottom then
          renderRow(buffer, columns, cells, y, headerStyle)
          y += 1
      }
      body.foreach { row =>
        // the cells sit on the first line of the row's height; the rest of it, and both margins, stay blank
        val line = y + row.contentOffset
        if line < area.bottom then renderRow(buffer, columns, row.cells, line, row.style.fold(style)(style.patch))
        y += row.totalHeight
      }
      if footerRows == 1 then footer.foreach(cells => renderRow(buffer, columns, cells, area.bottom - 1, footerStyle))

  /** Draws one row's cells left to right, giving each the merged rectangle of the columns it spans.
    *
    * The column pointer advances by the cell's span rather than by one, which is what makes a spanning cell push the
    * cells after it along instead of overwriting them.
    */
  private def renderRow(
      buffer: Buffer,
      columns: Seq[Rect],
      cells: Seq[TableCell.Source],
      y: Int,
      rowStyle: Style,
  ): Unit =
    var column = 0
    cells.foreach { source =>
      if column < columns.size then
        val cell = TableCell.of(source)
        val span = math.max(1, math.min(cell.columnSpan, columns.size - column))
        val area = TableCell.merge(columns, column, span)
        if !area.isEmpty then
          // a cell that says where it wants to sit gets its way; a cell that says nothing sits on the left, which is
          // where every cell sat before a `Line` could carry an alignment
          val placement = cell.content.alignment.getOrElse(Alignment.Left)
          val _         = LineRenderer.render(buffer, area.x, y, cell.content, area.width, rowStyle, placement)
        column += span
    }

object Table:

  /** A table of plain unstyled text.
    *
    * The cells of a table are [[io.worxbend.tui.core.Line Line]]s so that any one of them can carry several styles, but
    * the common case is a grid of `String`s and every such caller was writing the same `rows.map(_.map(Line.raw))`
    * incantation. This does that once. A `Seq[Seq[String | Line]]` union would have removed the need for a second
    * factory altogether, but a union two collections deep is unpleasant to read and to infer, so the two shapes get two
    * names instead.
    *
    * The parameter list mirrors [[Table]]'s, and that mirror is a maintenance contract: a new [[Table]] parameter
    * belongs here too. The forwarding call below passes every argument by name, so a parameter added to one and not the
    * other, or two neighbouring parameters of the same type swapped, is a compile error rather than a table that
    * quietly renders wrong.
    */
  def ofStrings(
      rows: Seq[Seq[String]],
      widths: Seq[Constraint],
      header: Option[Seq[String]] = None,
      footer: Option[Seq[String]] = None,
      columnSpacing: Int = 1,
      flex: Flex = Flex.Start,
      style: Style = Style.Default,
      headerStyle: Style = Style.Default.bold,
      footerStyle: Style = Style.Default.bold,
  ): Table =
    Table(
      rows = rows.map(_.map(Line.raw)),
      widths = widths,
      header = header.map(_.map(Line.raw)),
      footer = footer.map(_.map(Line.raw)),
      columnSpacing = columnSpacing,
      flex = flex,
      style = style,
      headerStyle = headerStyle,
      footerStyle = footerStyle,
    )
