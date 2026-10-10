package io.worxbend.tui.widgets

import java.util.Locale

import io.worxbend.tui.core.{
  Alignment,
  Buffer,
  CharWidth,
  Constraint,
  Direction,
  Flex,
  Layout,
  Line,
  Rect,
  Span,
  StatefulWidget,
  Style,
}

/** Which way a [[DataTable]] column is sorted. */
enum SortDirection:
  case Ascending, Descending

  /** The other direction — what sorting an already-sorted column again does. */
  def flipped: SortDirection = this match
    case Ascending  => Descending
    case Descending => Ascending

/** A column index paired with the direction it is sorted in.
  *
  * The two travel together because neither means anything alone: a direction with no column says nothing at all, and
  * before this pairing existed an unsorted table still carried an `ascending` flag that could be flipped — changing the
  * memoization key, and so recomputing an identical view.
  */
final case class ColumnSort(column: Int, direction: SortDirection)

/** A page window over a [[DataTable]]: how many rows a page holds, and which page is showing.
  *
  * Also a pair for a reason: a page number with no page size describes nothing the widget can render, which is what a
  * separate `pageSize: Option[Int]` plus `page: Int` allowed anyone to write.
  */
final case class Paging(size: Int, page: Int)

/** Caller-owned [[DataTable]] state: the sort (if any), a substring filter, selection, scroll, and paging (if any).
  *
  * Selection indexes into the *view* (the filtered, sorted rows) — use [[DataTable.visibleRows]] to map it back to
  * data.
  *
  * Every render also records the selected row's key, and the *next* render re-anchors the selection to wherever that
  * key landed after a re-sort or a data refresh — the highlight follows the record rather than the row number. Every
  * selection mutator here, and every direct `selected = …` assignment, clears the recorded key; [[DataTable.selectKey]]
  * sets both at once.
  *
  * The state is typed on the table's key type so the recorded key and the memoized view never leave it: one state
  * instance belongs to one [[DataTable]] shape, which is how it was already used — sharing a state between tables with
  * different row data never meant anything.
  *
  * Render-thread-only, and mutating it does not by itself schedule a frame. This is a plain mutable object, invisible
  * to the reactive layer: a background result written straight into it stays off screen until something unrelated
  * happens to repaint. Pair the mutation with a `Signal` write, or call `TuiApp.requestRedraw()` from the same
  * render-thread callback that made it.
  *
  * @tparam K
  *   the key type of the table's [[KeyedRow]]s; `Int` — the row's original position, which is what
  *   [[DataTable.fromStrings]] keys by — for a table built from plain text rows, whose state is still spelled
  *   `DataTableState()` (see the companion).
  */
final class DataTableState[K]:
  var sort: Option[ColumnSort] = None
  var offset: Int              = 0
  var paging: Option[Paging]   = None

  /** The selected view row, or `None` for no selection.
    *
    * A custom setter rather than a bare `var` so that moving the selection — here or by direct assignment — drops the
    * recorded row key: the key answers "which record was selected", and a selection the caller just moved must not be
    * snapped back to the old record on the next frame.
    */
  def selected: Option[Int] = selectedValue

  def selected_=(index: Option[Int]): Unit =
    selectedValue = index
    selectedRowKey = None

  private var selectedValue: Option[Int] = None

  /** The key of the record the selection is anchored to, written by [[DataTable.render]] on every frame. */
  private[widgets] var selectedRowKey: Option[K] = None

  /** The selected column, or `None` for no column cursor — the horizontal half of a spreadsheet-style cursor.
    *
    * Independent of [[selected]] on purpose. A column selection with no row selection highlights a whole column, a row
    * selection with no column selection highlights a whole row (which is all a `DataTable` could do before), and the
    * two together identify one cell. Nothing is drawn for it unless the widget was given a `columnHighlightStyle` or a
    * `cellHighlightStyle`, so a table that does not want a column cursor is unaffected by its existence.
    */
  var selectedColumn: Option[Int] = None

  /** The substring rows are filtered by, or `""` for no filter. Read-only: change it through [[setFilter]], which is
    * the only place that also resets the selection and the scroll to match the new result set.
    */
  def filter: String = filterText

  private var filterText: String = ""

  /** Moves to the next/previous page (no-ops while `paging` is unset, the one state where no-op is the honest answer);
    * `totalFiltered` bounds the last page.
    */
  def nextPage(totalFiltered: Int): Unit =
    paging.foreach { window =>
      val lastPage = math.max(0, (totalFiltered - 1) / math.max(1, window.size))
      paging = Some(window.copy(page = math.min(window.page + 1, lastPage)))
      selected = None
      offset = 0
    }

  def previousPage(): Unit =
    paging.foreach { window =>
      paging = Some(window.copy(page = math.max(0, window.page - 1)))
      selected = None
      offset = 0
    }

  /** Sorts by `column`; sorting the same column again flips the direction. */
  def sortBy(column: Int): Unit =
    sort = sort match
      case Some(current) if current.column == column => Some(current.copy(direction = current.direction.flipped))
      case _                                         => Some(ColumnSort(column, SortDirection.Ascending))

  /** Drops the memoized filtered/sorted view.
    *
    * Replacing the immutable row sequence automatically refreshes the view, even at the same length. This is an
    * explicit escape hatch for caller-defined sequences with externally changing contents; it preserves selection.
    */
  def invalidate(): Unit = view = None

  private var view: Option[(DataTableState.ViewKey, Seq[KeyedRow[K]])] = None

  /** Returns the cached view when `key` still matches, otherwise recomputes and stores it. */
  private[widgets] def cachedView(key: DataTableState.ViewKey)(compute: => Seq[KeyedRow[K]]): Seq[KeyedRow[K]] =
    view match
      case Some((cached, rows)) if cached.matches(key) => rows
      case _                                           =>
        val fresh = compute
        view = Some((key, fresh))
        fresh

  /** Filters the rows to those with `text` in any cell, and clears the selection and the scroll offset.
    *
    * Clearing both is the point of routing every filter change through here. A selection is an index into the *view*,
    * so keeping it across a filter change lands the highlight on whatever unrelated row now happens to sit at that
    * index, and keeping the offset scrolls a short result set to a position that no longer exists.
    */
  def setFilter(text: String): Unit =
    filterText = text
    selected = None
    offset = 0

  def selectNext(visibleCount: Int): Unit =
    if visibleCount > 0 then selected = Selection.next(selected, visibleCount)

  def selectPrevious(visibleCount: Int): Unit =
    if visibleCount > 0 then selected = Selection.previous(selected, visibleCount)

  /** Moves the column cursor right/left, clamping at the ends the same way row selection does — see [[Selection]] for
    * why these clamp rather than wrap. With no column selected yet, the first move lands on the first column.
    */
  def selectNextColumn(columnCount: Int): Unit =
    if columnCount > 0 then selectedColumn = Selection.next(selectedColumn, columnCount)

  def selectPreviousColumn(columnCount: Int): Unit =
    if columnCount > 0 then selectedColumn = Selection.previous(selectedColumn, columnCount)

  /** Puts the cursor on one cell: row `row` of the current view, column `column`.
    *
    * The pair is set together because a caller placing a cell cursor — a mouse click, a "jump to this field" command —
    * wants both halves to move at once, and setting them one at a time draws an intermediate frame with the cursor on a
    * cell nobody asked for. Negative indices clamp to zero; an index past the end is left for [[DataTable.render]] to
    * clamp, because only the render knows how many rows survived the filter.
    */
  def selectCell(row: Int, column: Int): Unit =
    selected = Some(math.max(0, row))
    selectedColumn = Some(math.max(0, column))

  /** Selects the first visible row — the Home key's move. A no-op when the filter has left nothing to select.
    *
    * `visibleCount` is the number of rows *after* filtering and sorting, not `rows.size`: the selection is an index
    * into the view the reader is looking at.
    */
  def selectFirst(visibleCount: Int): Unit =
    if visibleCount > 0 then selected = Selection.first(visibleCount)

  /** Selects the last visible row — the End key's move, with the same `visibleCount` contract as [[selectFirst]].
    *
    * Like the other selection moves this leaves `offset` alone; the table re-derives it during render to scroll the
    * chosen row into view.
    */
  def selectLast(visibleCount: Int): Unit =
    if visibleCount > 0 then selected = Selection.last(visibleCount)

  /** Moves the selection `delta` visible rows, clamped at both ends — the screenful jump PageUp and PageDown make. */
  def selectBy(visibleCount: Int, delta: Int): Unit =
    if visibleCount > 0 then selected = Selection.by(selected, visibleCount, delta)

object DataTableState:

  /** The state of a plain-text table, keyed by original row position — the spelling `DataTableState()` keeps for the
    * tables [[DataTable.fromStrings]] builds. A table with its own key type writes the type out:
    * `DataTableState[String]()`.
    */
  def apply(): DataTableState[Int] = new DataTableState[Int]

  /** Everything that can change the filtered/sorted view, used as the memoization key. */
  private[widgets] final case class ViewKey(
      sort: Option[ColumnSort],
      filter: String,
      source: AnyRef,
  ):
    def matches(other: ViewKey): Boolean =
      (source eq other.source) && sort == other.sort && filter == other.filter

/** One [[DataTable]] row: a stable identity plus the text cells the table sorts, filters and draws.
  *
  * The key is what lets a selection survive everything the widget does to the row order: [[DataTable.render]] records
  * the selected row's key on every frame and re-anchors the highlight to the same key after a re-sort or a data
  * refresh, so a table of processes keeps its highlight on the same PID instead of on whatever row number it happened
  * to occupy. Keys are compared with `==`; give each row a key that is unique within the table — when two rows share
  * one, the first in view order is the one the selection anchors to.
  *
  * `cells` is a projection of the record into display text: the table sorts and filters on these strings exactly as it
  * always has, and the record itself never has to be parsed back out of its own formatting.
  */
final case class KeyedRow[K](key: K, cells: Seq[String])

/** Everything about a [[DataTable]] that is not its columns, rows or widths: layout, looks, and the per-cell styling
  * hook.
  *
  * Bundled rather than listed on [[DataTable]] itself because the list had grown to twelve optional knobs. Every field
  * keeps the default it had as a `DataTable` constructor parameter, so `DataTableOptions()` is the table 0.14.0 drew by
  * default.
  *
  * @param footer
  *   an optional summary row — totals, a record count — pinned to the *bottom* of the area rather than following the
  *   last data row, and laid out on the same solved columns as the body. It costs one row of the scrollable body. A
  *   `DataTable` always draws its header, so the footer is dropped on an area only one row tall, where the header has
  *   already taken the only row there is.
  * @param columnSpacing
  *   columns of padding between two solved column widths
  * @param flex
  *   where the columns sit when they do not fill the area — see [[Table]] for the full explanation. The reserved
  *   `highlightSymbol` gutter is taken off the left first, so the flex distributes only what is left over after it.
  * @param alignments
  *   where each column's text sits inside its own column, by position: `alignments(0)` places column 0, `alignments(1)`
  *   column 1, and so on. This is how a numeric column lines up on its last digit instead of on its first. It applies
  *   to the header caption, the body cells and the footer alike, so a right-aligned column's title stays over its
  *   figures.
  *
  * The sequence may be shorter than the column list, or empty — the default — and every column it does not reach is
  * left-aligned, which is what every column did before this parameter existed. A short sequence is allowed on purpose:
  * a table gains a column far more often than it changes an alignment, and a length check that threw from inside the
  * render loop would be the worst way to find that out. Entries past the last column are ignored.
  *
  * A [[Table]] takes no such parameter because its cells are [[io.worxbend.tui.core.Line]]s, which carry their own
  * alignment (`Line.raw("42").rightAligned`). A `DataTable` cell is a bare `String` — it has to be, because the widget
  * sorts and filters on the text — so the placement has nowhere to live except here.
  * @param style
  *   the style of the whole table
  * @param headerStyle
  *   the style of the header row, bold by default
  * @param footerStyle
  *   the style the footer row is drawn in, bold by default, matching the header
  * @param highlightStyle
  *   layered over the selected row's style
  * @param columnHighlightStyle
  *   layered over the row's style for every body cell in the selected column, or `None` — the default — to draw no
  *   column cursor at all. Together with `cellHighlightStyle` this is what turns a row-selecting table into a
  *   spreadsheet-style grid cursor; a table that sets neither renders exactly as it did before they existed.
  * @param cellHighlightStyle
  *   layered on last, over the cell where the selected row and the selected column meet. Painting it after both means
  *   the intersection can be told apart from the row and the column that cross there, which is the whole point of
  *   having three styles rather than one.
  * @param cellStyle
  *   the style of one body cell, given the row's own cells and the column index, layered over everything the widget
  *   itself decided: the table style, the selection highlight if that row is selected, and the column and cell cursors
  *   if they are on it. That ordering means a red `FAILED` cell stays red under the selection bar instead of the two
  *   fighting over the same cell, and it is why this returns a patch rather than a whole style.
  *
  * It is asked about the row's *contents* rather than about the row's position because filtering, sorting and paging
  * all move rows around between frames: an index identifies a different record after every sort, and a caller reading
  * one would colour whatever record happened to land there. The header and footer never consult it — `headerStyle` and
  * `footerStyle` own those rows — and neither does the reserved `highlightSymbol` gutter.
  *
  * The default returns an empty style, which patches nothing, so a table that does not set it renders exactly as it did
  * before this parameter existed.
  * @param highlightSymbol
  *   text drawn to the left of the selected row, in a gutter reserved for it on *every* row so the columns do not jump
  *   as the selection moves. `highlightStyle` alone marks the selection by reversing the row's colours, which two kinds
  *   of terminal do not show: one that ignores reverse video, and one where the row already carries a background colour
  *   of its own that the reversal blends into. A symbol survives both. The default is `""` — an empty symbol reserves a
  *   zero-width gutter, so a table written before this parameter existed draws exactly the same cells in exactly the
  *   same columns as before. [[ListView]] defaults to `"> "` instead, because a list has no column grid to keep still.
  */
final case class DataTableOptions(
    footer: Option[Seq[String]] = None,
    columnSpacing: Int = 1,
    flex: Flex = Flex.Start,
    alignments: Seq[Alignment] = Seq.empty,
    style: Style = Style.Default,
    headerStyle: Style = Style.Default.bold,
    footerStyle: Style = Style.Default.bold,
    highlightStyle: Style = Style.Default.reverse,
    columnHighlightStyle: Option[Style] = None,
    cellHighlightStyle: Option[Style] = None,
    cellStyle: (Seq[String], Int) => Style = (_, _) => Style.Default,
    highlightSymbol: String = "",
)

/** A sortable, filterable table with a selectable, scrollable body — [[Table]] plus the interaction a data grid needs.
  *
  * The header shows a `▲`/`▼` indicator on the sorted column; the filter keeps rows where *any* cell contains the text
  * (case-insensitive); sorting compares numerically when both cells parse as numbers, else as text.
  *
  * Each row is a [[KeyedRow]]: `cells` is what the table sorts, filters and draws, and `key` is the row's stable
  * identity. The selection follows the key across re-sorts and data refreshes — see [[DataTableState]] — and
  * [[selectedKey]] reads it back, so an app never has to parse its own formatted cells to recover which record is
  * selected. [[DataTable.fromStrings]] builds a table from plain text rows, keying each by its original position, for
  * callers that have no record identity of their own.
  *
  * @tparam K
  *   the row key type; `Int` (the row's original position) for a table built by [[DataTable.fromStrings]]
  * @param columns
  *   the column titles; the header settles how many columns the table has
  * @param rows
  *   the body rows — see [[KeyedRow]] for the key's contract
  * @param widths
  *   one [[Constraint]] per column. An empty sequence means "equal columns": each of the `columns` titles gets an equal
  *   share of the area. Before that fallback existed an empty sequence drew a blank rectangle instead.
  */
final case class DataTable[K](
    columns: Seq[String],
    rows: Seq[KeyedRow[K]],
    widths: Seq[Constraint],
    options: DataTableOptions = DataTableOptions(),
) extends StatefulWidget[DataTableState[K]]:

  import options.*

  /** Every row surviving the filter, in sort order — the domain paging windows over.
    *
    * Memoized on `state`: scrolling changes only the offset, and re-sorting ten thousand rows on every frame is what
    * pushes a redraw past the tick budget. Row-source identity, sort and filter form the key: retain the immutable
    * sequence to reuse work across widget rebuilds; replace it to refresh. No content hashing or traversal is needed.
    */
  def filteredRows(state: DataTableState[K]): Seq[KeyedRow[K]] =
    val key = DataTableState.ViewKey(state.sort, state.filter, rows)
    state.cachedView(key) {
      val filtered =
        if state.filter.isEmpty then rows
        else
          // ROOT, not the default locale: in a Turkish locale "ID".toLowerCase is "ıd", which matches nothing.
          val needle = state.filter.toLowerCase(Locale.ROOT)
          rows.filter(_.cells.exists(_.toLowerCase(Locale.ROOT).contains(needle)))
      state.sort match
        case None                                => filtered
        case Some(ColumnSort(column, direction)) =>
          val cells   = filtered.map(_.cells.lift(column).getOrElse(""))
          val ordered = ordering(cells)
          val sorted  =
            filtered.sortWith((a, b) =>
              ordered.lt(a.cells.lift(column).getOrElse(""), b.cells.lift(column).getOrElse(""))
            )
          direction match
            case SortDirection.Ascending  => sorted
            case SortDirection.Descending => sorted.reverse
    }

  /** The rows the widget is currently showing: filtered, sorted, and windowed to the current page — what a selection
    * indexes.
    */
  def visibleRows(state: DataTableState[K]): Seq[KeyedRow[K]] =
    val all = filteredRows(state)
    state.paging match
      case None         => all
      case Some(window) =>
        val size = pageSizeOf(window)
        val page = pageOf(window, all.size)
        all.slice(page * size, (page + 1) * size)

  /** The key of the selected row, or `None` when nothing is selected.
    *
    * This is the answer a caller used to recover by parsing its own formatted cells. The recorded anchor is answered
    * when there is one, so a sort between frames does not leave the key pointing at a stale index — the anchor is what
    * the next frame re-resolves.
    */
  def selectedKey(state: DataTableState[K]): Option[K] =
    state.selectedRowKey.orElse(state.selected.flatMap(visibleRows(state).lift).map(_.key))

  /** Moves the selection to the row keyed `key` in the current view, returning `true` when there is one.
    *
    * `false` — and no state touched — when no visible row carries the key: a record that left the data, or one sitting
    * on another page, since a selection indexes the windowed view. On success both halves of the selection are set
    * together (the index for this frame, the key for the frames after a re-sort), which a direct `state.selected = …`
    * assignment cannot do.
    */
  def selectKey(state: DataTableState[K], key: K): Boolean =
    val view  = visibleRows(state)
    val index = view.indexWhere(_.key == key)
    if index < 0 then false
    else
      state.selected = Some(index)
      state.selectedRowKey = Some(key)
      true

  /** Writes back the page [[visibleRows]] would show, so a page left past the end of a shrunken result set does not
    * stay there once the user turns it.
    *
    * The one write [[visibleRows]] used to make itself, moved out so that reading the rows stays a read. [[render]]
    * calls it on every frame alongside the selection and offset clamps, which is the moment all three state repairs
    * belong at.
    */
  private[widgets] def clampPage(state: DataTableState[K]): Unit =
    val total = filteredRows(state).size
    state.paging = state.paging.map(window => window.copy(page = pageOf(window, total)))

  /** One page size for every use: `Paging(0, …)` arises naturally from `area.height - 2` on a short terminal, and
    * paging by 0 shows no rows at all on every page.
    */
  private def pageSizeOf(window: Paging): Int = math.max(1, window.size)

  private def pageOf(window: Paging, total: Int): Int =
    val lastPage = math.max(0, (total - 1) / pageSizeOf(window))
    math.max(0, math.min(window.page, lastPage))

  def render(area: Rect, buffer: Buffer, state: DataTableState[K]): Unit =
    clampPage(state)
    val view       = visibleRows(state)
    val footerRows = if footer.isDefined && area.height > 1 then 1 else 0
    val bodyHeight = math.max(0, area.height - 1 - footerRows)
    reconcileSelection(state, view, bodyHeight)
    if !area.isEmpty then
      // the gutter is carved off the left of the whole table, header included, so every column keeps one x position
      val symbolWidth = math.min(CharWidth.of(highlightSymbol), area.width)
      val grid        = area.copy(x = area.x + symbolWidth, width = area.width - symbolWidth)
      // an empty `widths` means equal columns; a DataTable always names its columns, so the header settles the count
      val constraints = TableColumns.resolve(widths, Iterator(columns.size), grid.width)
      val segments    = Layout(Direction.Horizontal, constraints, columnSpacing, flex).split(grid)
      // a column index past the last column would highlight nothing and hide the fact that it was set wrong
      state.selectedColumn = Selection.clamped(state.selectedColumn, segments.size)
      renderHeader(buffer, segments, state)
      if footerRows == 1 then
        footer.foreach(cells => renderRow(buffer, segments, cells, area.bottom - 1, _ => footerStyle))
      if bodyHeight > 0 && view.nonEmpty then renderBody(area, buffer, state, view, segments, symbolWidth, bodyHeight)

  /** Reconciles caller-owned state on every render, including an empty view or an area with no body to paint.
    *
    * A key recorded last frame is re-resolved to its index in this frame's view. A key that no longer resolves falls
    * back to the clamped index; an empty view clears both. Even with no body rows, a one-row scroll window keeps the
    * offset anchored to an existing row rather than one past the selection.
    */
  private def reconcileSelection(state: DataTableState[K], view: Seq[KeyedRow[K]], bodyHeight: Int): Unit =
    val anchored = state.selectedRowKey.flatMap { key =>
      val index = view.indexWhere(_.key == key)
      Option.when(index >= 0)(index)
    }
    val selected = Selection.clamped(anchored.orElse(state.selected), view.size)
    state.selected = selected
    // assigning `selected` clears the recorded key, so the anchor is written after it, from the resolved row
    state.selectedRowKey = selected.map(index => view(index).key)
    state.offset = ScrollWindow.offsetFor(state.offset, selected, view.size, math.max(1, bodyHeight))

  /** Paints the reconciled window of body rows that fits; layout and state repair belong to [[render]]. */
  private def renderBody(
      area: Rect,
      buffer: Buffer,
      state: DataTableState[K],
      view: Seq[KeyedRow[K]],
      segments: Seq[Rect],
      symbolWidth: Int,
      bodyHeight: Int,
  ): Unit =
    val selected = state.selected
    val padding  = " ".repeat(symbolWidth)
    val cursor   = state.selectedColumn
    view.slice(state.offset, state.offset + bodyHeight).zipWithIndex.foreach { (keyed, row) =>
      val cells      = keyed.cells
      val index      = state.offset + row
      val isSelected = selected.contains(index)
      val rowStyle   = if isSelected then style.patch(highlightStyle) else style
      val y          = area.y + 1 + row
      if symbolWidth > 0 then
        val prefix = if isSelected then highlightSymbol else padding
        buffer.setString(area.x, y, CharWidth.substringByWidth(prefix, symbolWidth), rowStyle)
      renderRow(buffer, segments, cells, y, cursorStyle(rowStyle, isSelected, cursor, _), Some(cells))
    }

  private def renderHeader(buffer: Buffer, segments: Seq[Rect], state: DataTableState[K]): Unit =
    columns.zipWithIndex.foreach { (title, index) =>
      segments.lift(index).filterNot(_.isEmpty).foreach { segment =>
        // the sort indicator is part of the caption before it is placed, so a right-aligned column's arrow sits at the
        // column's right edge rather than floating away from the title it belongs to. It is appended only when this
        // column is the sorted one; an unsorted header is the title alone and pays no concatenation for an empty
        // indicator
        val line = sortIndicator(state.sort, index)
          .fold(Line(Seq(Span(title, headerStyle))))(indicator => Line(Seq(Span(title + indicator, headerStyle))))
        val _    =
          LineRenderer.render(buffer, segment.x, segment.y, line, segment.width, Style.Default, alignmentOf(index))
      }
    }

  /** The sort-indicator glyph for column `index`, or `None` when `sort` is not on that column. */
  private def sortIndicator(sort: Option[ColumnSort], index: Int): Option[String] = sort match
    case Some(ColumnSort(`index`, SortDirection.Ascending))  => Some(DataTable.AscendingIndicator)
    case Some(ColumnSort(`index`, SortDirection.Descending)) => Some(DataTable.DescendingIndicator)
    case _                                                   => None

  /** The style one body cell is drawn in before the caller's own `cellStyle` has a say: the row's style, then the
    * column cursor over it, then the cell cursor over both. Layering in that order is what lets the intersection of the
    * selected row and the selected column look like neither of them.
    */
  private def cursorStyle(rowStyle: Style, isSelectedRow: Boolean, cursor: Option[Int], column: Int): Style =
    if !cursor.contains(column) then rowStyle
    else
      val withColumn = columnHighlightStyle.fold(rowStyle)(rowStyle.patch)
      if isSelectedRow then cellHighlightStyle.fold(withColumn)(withColumn.patch) else withColumn

  /** Draws one row's cells, asking `styleAt` for the style of each column so that a per-cell cursor can differ from the
    * row it sits on. Rows with no cursor pass a function that ignores the column.
    *
    * `source` is the row the caller's `cellStyle` is asked about, or `None` for a row it is never asked about at all —
    * the header and the footer, whose styles are settled by `headerStyle` and `footerStyle`.
    */
  private def renderRow(
      buffer: Buffer,
      segments: Seq[Rect],
      cells: Seq[String],
      y: Int,
      styleAt: Int => Style,
      source: Option[Seq[String]] = None,
  ): Unit =
    segments.zip(cells).zipWithIndex.foreach { case ((segment, cell), column) =>
      if !segment.isEmpty then
        // the caller's patch goes on last, over the selection and the cursors, so a cell it colours keeps that colour
        // wherever the selection happens to be
        val resolved = source.fold(styleAt(column))(row => styleAt(column).patch(cellStyle(row, column)))
        alignmentOf(column) match
          // the common case — a left-aligned cell — writes straight into the buffer: no Line/Seq/Span wrapper is
          // built just to carry one string and one style through the renderer, and the budgeted `setString` clips a
          // too-wide cell no differently than the renderer's RowCursor does
          case Alignment.Left => val _ = buffer.setString(segment.x, y, cell, resolved, segment.width)
          case alignment      =>
            val line = Line.styled(cell, resolved)
            val _    = LineRenderer.render(buffer, segment.x, y, line, segment.width, Style.Default, alignment)
    }

  /** Where column `column`'s text sits, defaulting to the left edge for every column `alignments` does not reach —
    * including every column when it is empty, which is the default.
    */
  private def alignmentOf(column: Int): Alignment = alignments.lift(column).getOrElse(Alignment.Left)

  /** Numeric-aware ordering, chosen once for the whole column rather than per comparison.
    *
    * Deciding per pair is not a valid ordering and is not merely untidy: in a column mixing `"9"`, `"10"` and
    * `"2020-01-01"`, `"9" < "10"` numerically while `"10" < "2020-01-01"` and `"2020-01-01" < "9"` textually — a cycle,
    * which makes `sortWith` throw `IllegalArgumentException: Comparison method violates its general contract!` out of
    * the render loop, and silently mis-order the rows when it does not. `NaN` is excluded from the numeric case for the
    * same reason: it compares false against everything, breaking transitivity just as badly.
    */
  private def ordering(cells: Seq[String]): Ordering[String] =
    val numeric = cells.forall(cell => cell.toDoubleOption.exists(value => !value.isNaN))
    if numeric then Ordering.by[String, Double](_.toDoubleOption.getOrElse(0.0))
    else (left, right) => left.compareToIgnoreCase(right)

object DataTable:

  /** The caption suffix a sorted column carries, hoisted out of [[DataTable.renderHeader]] so the glyphs are not
    * re-allocated per column per frame.
    */
  private val AscendingIndicator  = " ▲"
  private val DescendingIndicator = " ▼"

  /** A table over plain text cells — the shape every `DataTable` row took before rows carried keys.
    *
    * Each row is keyed by its original position in `rows`, so the key-anchored selection behaves sensibly here too —
    * the highlight follows the record across a re-sort — without the caller having an identity of its own to supply.
    * Callers that do have one (a PID, a path, an id) should build [[KeyedRow]]s themselves instead.
    */
  def fromStrings(
      columns: Seq[String],
      rows: Seq[Seq[String]],
      widths: Seq[Constraint],
      options: DataTableOptions = DataTableOptions(),
  ): DataTable[Int] =
    DataTable(columns, rows.zipWithIndex.map((cells, index) => KeyedRow(index, cells)), widths, options)
