package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Position, Rect, StatefulWidget, Style}

/** Caller-owned multi-line editing state for [[TextArea]]: an [[EditBuffer]] of many lines.
  *
  * The cursor is `(line, column)` in cluster coordinates and can never split a combining sequence or emoji. Edits and
  * line joins re-segment affected lines; a splice inside a newly merged grapheme maps the cursor to that grapheme's end
  * (right affinity), identically for insertion, deletion and either join direction. Undo stores coalesced diffs — see
  * [[EditBuffer]].
  *
  * Control characters other than the `\n` that separates lines are dropped on the way in — by the constructor as well
  * as by [[insert]] — because a control is zero columns wide but still fills a whole `Cell`, so storing one
  * desynchronises the backend's cursor model from the terminal's.
  *
  * Render-thread-only. Every mutation raises `version` (see [[io.worxbend.tui.core.Versioned]]), so a DSL node that
  * paints this state notices a write made outside the event path — a background result applied on the render thread —
  * and schedules the frame that shows it, with no `Signal` write and no `requestRedraw()`.
  *
  * Incremental search lives here too: [[openSearch]], [[updateSearch]] as the query is typed, then [[nextMatch]] and
  * [[previousMatch]]; the widget draws the open prompt and underlines [[matches]].
  */
final class TextAreaState(initial: String = "", killRing: KillRing = KillRing())
    extends EditBuffer(initial, multiline = true, killRing):
  import TextAreaState.Match

  private[widgets] var scrollRow: Int = 0

  /** Horizontal scroll offset in *columns*, shared by every line — see [[TextArea.scrolledHorizontally]]. */
  private[widgets] var scrollColumn: Int = 0

  /** Cursor as `(line, column)` in cluster coordinates. */
  def cursor: (Int, Int) = (caret.line, caret.column)

  // --- soft wrap -------------------------------------------------------------------------------------------------

  /** The text width the last render wrapped at; `None` when it did not wrap (or nothing has rendered yet), which is
    * what makes Up and Down move by logical line. Written by [[TextArea.render]].
    */
  private[widgets] var wrapWidth: Option[Int] = None

  // Each line's wrap spans, keyed by the identity of the line's cluster vector: an edit replaces exactly the lines it
  // touches with new vectors and keeps every other one, so a line the edit did not touch — even one it shifted down —
  // finds its spans again, and only edited lines are wrapped anew. Dropped wholesale when the width changes.
  private var wrapCache      = java.util.IdentityHashMap[Vector[String], Vector[(Int, Int)]]()
  private var wrapCacheWidth = -1

  /** How many lines have been wrapped from scratch — an instrumentation hook for the cache above. */
  private[widgets] var wrapComputations: Long = 0L

  /** The wrap spans of line `index` at `width`, from the cache when the line is unchanged. */
  private[widgets] def spansOf(index: Int, width: Int): Vector[(Int, Int)] =
    if width != wrapCacheWidth then
      wrapCache = java.util.IdentityHashMap()
      wrapCacheWidth = width
    val line   = clusterLines(index)
    val cached = wrapCache.get(line)
    if cached != null then cached // scalafix:ok DisableSyntax; `IdentityHashMap.get` answers null for a missing key
    else
      wrapComputations += 1
      val spans = TextArea.wrapSpans(line, width)
      val _     = wrapCache.put(line, spans)
      spans

  /** Every line's wrap spans at `width`, keeping in the cache only the lines the text still holds. */
  private[widgets] def wrapLayout(width: Int): Vector[Vector[(Int, Int)]] =
    val spans = clusterLines.indices.map(spansOf(_, width)).toVector
    // an edit leaves the replaced line's entry behind; sweep once the stale entries outnumber the live ones
    if wrapCache.size > 2 * clusterLines.size then
      val kept = java.util.IdentityHashMap[Vector[String], Vector[(Int, Int)]]()
      clusterLines.indices.foreach(index => kept.put(clusterLines(index), spans(index)))
      wrapCache = kept
    spans

  /** Up one row: under soft wrap the visual row above (the line's previous wrapped row, or the last row of the line
    * above), at the same display column; otherwise the line above. A no-op on the first row.
    */
  override def moveUp(select: Boolean = false): Unit =
    wrapWidth.fold(super.moveUp(select))(width => moveByRow(-1, width, select))

  /** Down one row — the mirror of [[moveUp]]. */
  override def moveDown(select: Boolean = false): Unit =
    wrapWidth.fold(super.moveDown(select))(width => moveByRow(1, width, select))

  /** One visual row up (`delta` -1) or down (1) at `width`, keeping the caret's display column within its row. */
  private def moveByRow(delta: Int, width: Int, select: Boolean): Unit =
    val clusters = clusterLines(caret.line)
    val spans    = spansOf(caret.line, width)
    val index    = spans.indexWhere((start, end) => caret.column >= start && caret.column < end) match
      case -1    => spans.size - 1
      case found => found
    val (s, e)   = spans(index)
    // the caret past the end of a full last row stands on a phantom row of its own below it (see `TextArea`)
    val phantom  = caret.column == clusters.size && e > s && ClusterRow.visibleWidth(clusters, s, e) >= width
    val x        = if phantom then 0 else ClusterRow.visibleWidth(clusters, s, caret.column)
    val target   =
      if phantom && delta < 0 then Some((caret.line, index))
      else if index + delta >= 0 && index + delta < spans.size then Some((caret.line, index + delta))
      else if delta < 0 && caret.line > 0 then Some((caret.line - 1, spansOf(caret.line - 1, width).size - 1))
      else if delta > 0 && caret.line < lineCount - 1 then Some((caret.line + 1, 0))
      else None
    target.foreach { (line, row) =>
      val rows          = spansOf(line, width)
      val (from, until) = rows(row)
      moveTo(
        TextPosition(line, TextAreaState.columnAt(clusterLines(line), from, until, x, row == rows.size - 1)),
        select,
      )
    }

  def lineCount: Int = clusterLines.size

  def newline(): Unit = insert("\n")

  // --- incremental search ----------------------------------------------------------------------------------------

  private var searchOrigin: Option[TextPosition]                  = None
  private var openQuery: Option[String]                           = None
  private var lastQuery: String                                   = ""
  private var matchCache: Option[(AnyRef, String, Vector[Match])] = None

  /** The query being typed while the search prompt is open (`None` once it closed) — what [[TextArea]] draws on its
    * bottom row as `/query`.
    */
  def search: Option[String] = openQuery

  /** Opens the search prompt with an empty query, remembering the caret so [[cancelSearch]] can return to it. */
  def openSearch(): Unit =
    searchOrigin = Some(caret)
    openQuery = Some("")
    touch()

  /** Sets the open prompt's query and jumps to the first match at or after where the search started, wrapping round;
    * the caret stays at the origin when nothing matches. Case-insensitive unless the query holds a capital letter;
    * matched grapheme by grapheme after NFC normalisation, like every searched view (see [[SearchState]]).
    */
  def updateSearch(query: String): Unit =
    if openQuery.isDefined then
      openQuery = Some(query)
      lastQuery = query
      touch()
      val origin = searchOrigin.getOrElse(caret)
      val found  = matches
      found.find(_.start >= origin).orElse(found.headOption) match
        case Some(hit) => moveTo(hit.start)
        case None      => moveTo(origin)

  /** Closes the prompt, leaving the caret on the match; [[nextMatch]] and [[previousMatch]] keep using the query. */
  def closeSearch(): Unit =
    openQuery = None
    searchOrigin = None
    touch()

  /** Closes the prompt and puts the caret back where the search started. */
  def cancelSearch(): Unit =
    searchOrigin.foreach(origin => moveTo(origin))
    lastQuery = ""
    closeSearch()

  /** Moves to the next match of the last query after the caret, wrapping round; answers whether there was one. */
  def nextMatch(): Boolean =
    val found = matches
    found.find(_.start > caret).orElse(found.headOption).map(hit => moveTo(hit.start)).isDefined

  /** Moves to the previous match of the last query before the caret, wrapping round. */
  def previousMatch(): Boolean =
    val found = matches
    found.findLast(_.start < caret).orElse(found.lastOption).map(hit => moveTo(hit.start)).isDefined

  /** Every match of the last query, in document order — what [[TextArea]] underlines. Recomputed only when the text or
    * the query changed since the last call.
    */
  def matches: Vector[Match] =
    val lines = clusterLines
    matchCache match
      case Some((seen, query, found)) if (seen eq lines) && query == lastQuery => found
      case _                                                                   =>
        val found = TextAreaState.find(lines, lastQuery)
        matchCache = Some((lines, lastQuery, found))
        found

object TextAreaState:

  /** The cluster of row `[from, until)` of `clusters` that display column `x` (from the row's start) falls in — the
    * cluster to its left when `x` lands inside a wide one. A row that is not its line's last cannot hold the caret at
    * `until` (that is the next row's start), so the column clamps onto its last cluster.
    */
  private[widgets] def columnAt(clusters: Vector[String], from: Int, until: Int, x: Int, lastRow: Boolean): Int =
    var column = from
    var used   = 0
    while column < until && used + ClusterRow.renderedWidth(clusters(column)) <= x do
      used += ClusterRow.renderedWidth(clusters(column))
      column += 1
    if !lastRow && column == until && until > from then until - 1 else column

  /** One occurrence of a search query: `[start, end)` on a single line. */
  final case class Match(start: TextPosition, end: TextPosition)

  /** Every match of `query` over `lines`, through the matcher every searched view shares — see [[SearchHighlight]]. */
  private[widgets] def find(lines: Vector[Vector[String]], query: String): Vector[Match] =
    lines.indices.iterator.flatMap { line =>
      SearchHighlight.clusterMatches(lines(line), query).iterator.map { (start, end) =>
        Match(TextPosition(line, start), TextPosition(line, end))
      }
    }.toVector

/** Optional knobs for [[TextArea]]; `TextAreaOptions()` is the plain editor.
  *
  * @param lexer
  *   highlights each visible line through this [[Lexer]], its token styles patched over the editor's `style`; `None`
  *   draws every cell in `style`
  * @param wrap
  *   soft-wraps long lines at the editor's width — after a space where there is one, between graphemes where there is
  *   not — instead of scrolling horizontally. Up/Down then move by wrapped row, keeping the display column.
  * @param lineNumbers
  *   draws a gutter of right-aligned 1-based line numbers, as wide as the largest number plus one column; a wrapped
  *   line's continuation rows leave it blank
  * @param syntax
  *   the token colours the lexer paints with
  * @param selectionStyle
  *   patched over the selected clusters
  * @param matchStyle
  *   patched over every match of the state's search query
  * @param gutterStyle
  *   the line numbers' style
  */
final case class TextAreaOptions(
    lexer: Option[Lexer] = None,
    wrap: Boolean = false,
    lineNumbers: Boolean = false,
    syntax: SyntaxTheme = SyntaxTheme(),
    selectionStyle: Style = Style.Default.reverse,
    matchStyle: Style = Style.Default.underline,
    gutterStyle: Style = Style.Default.dim,
)

/** A multi-line text editor view: vertical and horizontal scroll follow the cursor, which renders as a highlighted cell
  * (`showCursor = false` for unfocused areas). Syntax highlighting is opt-in through [[TextAreaOptions.lexer]], soft
  * wrap through [[TextAreaOptions.wrap]] and a line-number gutter through [[TextAreaOptions.lineNumbers]]. An open
  * search ([[TextAreaState.openSearch]]) takes the bottom row for its `/query` prompt.
  *
  * Without wrap only the rows on screen are visited, each frame, so the cost follows the viewport rather than the
  * document. With wrap every line's row count is needed to place the viewport, but each line's wrap is cached on the
  * state and recomputed only when an edit changed the line (or the width changed), so a frame re-wraps only what was
  * edited.
  */
final case class TextArea(
    showCursor: Boolean = true,
    style: Style = Style.Default,
    cursorStyle: Style = Style.Default.reverse,
    options: TextAreaOptions = TextAreaOptions(),
) extends StatefulWidget[TextAreaState]:

  def render(area: Rect, buffer: Buffer, state: TextAreaState): Unit =
    if !area.isEmpty then
      val layout = layoutFor(area, state)
      state.wrapWidth = Option.when(options.wrap)(layout.text.width)
      state.scrollRow = layout.scrollRow
      state.scrollColumn = layout.scrollColumn
      (layout.scrollRow until math.min(layout.rowCount, layout.scrollRow + layout.text.height)).foreach { index =>
        renderRow(buffer, layout, layout.row(index), layout.text.y + index - layout.scrollRow, state)
      }
      for prompt <- layout.prompt; query <- state.search do
        buffer.setString(prompt.x, prompt.y, CharWidth.substringByWidth("/" + query, prompt.width), style)

  /** The buffer cell the caret occupies when this editor is drawn into `area` for `state` — the counterpart of
    * `TextInput.caretAt`, for a host that parks the terminal's own cursor on the insertion point. While a search is
    * open, that is the end of the prompt's query.
    *
    * Both scroll offsets are re-solved from the state's current ones with the same rules [[render]] applies, so the
    * answer is the painted cursor's cell whether this is asked before or after the frame was drawn; the state is not
    * written. `None` when `area` is empty or the cell would fall outside it, which is when the painter draws no cursor
    * either. Independent of `showCursor`, for the reason given on `TextInput.caretAt`.
    */
  def caretAt(area: Rect, state: TextAreaState): Option[Position] =
    if area.isEmpty then None
    else
      val layout = layoutFor(area, state)
      (layout.prompt, state.search) match
        case (Some(prompt), Some(query)) =>
          val x = prompt.x + 1 + ClusterRow.visibleWidth(GraphemeEdit.clustersOf(query), 0, Int.MaxValue)
          Option.when(x < prompt.right)(Position(x, prompt.y))
        case _                           =>
          val text                       = layout.text
          val (cursorLine, cursorColumn) = state.cursor
          val clusters                   = state.clusterLines(cursorLine)
          val x                          =
            if options.wrap then
              text.x + ClusterRow.visibleWidth(clusters, layout.row(layout.cursorRow).start, cursorColumn)
            else
              val (firstCluster, from) = ClusterRow.clusterAtColumn(clusters, layout.scrollColumn)
              text.x + (from - layout.scrollColumn) + ClusterRow.visibleWidth(clusters, firstCluster, cursorColumn)
          val y                          = text.y + layout.cursorRow - layout.scrollRow
          Option.when(x >= text.x && x < text.right && y >= text.y && y < text.bottom)(Position(x, y))

  /** One screen row of text: clusters `[start, end)` of logical line `line`; `last` marks the line's final row, the one
    * that may show the caret past the end.
    */
  private final case class VisualRow(line: Int, start: Int, end: Int, last: Boolean)

  /** Where everything goes this frame: the text rectangle right of the gutter, the prompt row, the rows and scroll. */
  private final case class Layout(
      text: Rect,
      gutterWidth: Int,
      prompt: Option[Rect],
      rowCount: Int,
      row: Int => VisualRow,
      cursorRow: Int,
      scrollRow: Int,
      scrollColumn: Int,
  )

  private def layoutFor(area: Rect, state: TextAreaState): Layout =
    val lines                      = state.clusterLines
    val (cursorLine, cursorColumn) = state.cursor
    val prompt                     = Option.when(state.search.isDefined)(Rect(area.x, area.bottom - 1, area.width, 1))
    val digits                     = lines.size.toString.length
    val gutterWidth                = if options.lineNumbers && digits + 1 < area.width then digits + 1 else 0
    val text                       =
      Rect(area.x + gutterWidth, area.y, area.width - gutterWidth, math.max(0, area.height - prompt.size))
    if options.wrap then
      val rows      = wrappedRows(lines, state.wrapLayout(text.width), text.width, cursorLine, cursorColumn)
      val cursorRow = math.max(0, rows.indexWhere(row => row.line == cursorLine && covers(row, cursorColumn)))
      val scrollRow = ScrollWindow.offsetFor(state.scrollRow, Some(cursorRow), rows.size, text.height)
      Layout(text, gutterWidth, prompt, rows.size, rows, cursorRow, scrollRow, 0)
    else
      val scrollRow = ScrollWindow.offsetFor(state.scrollRow, Some(cursorLine), lines.size, text.height)
      val row       = (index: Int) => VisualRow(index, 0, lines(index).size, last = true)
      Layout(text, gutterWidth, prompt, lines.size, row, cursorLine, scrollRow, scrolledHorizontally(state, text.width))

  private def covers(row: VisualRow, column: Int): Boolean =
    (column >= row.start && column < row.end) || (row.last && column == row.end)

  /** Every line broken into rows of at most `width` columns. The caret's line gets one extra empty row when the caret
    * sits past the end of a row that is already full, so the caret always has a cell to stand on.
    */
  private def wrappedRows(
      lines: Vector[Vector[String]],
      layout: Vector[Vector[(Int, Int)]],
      width: Int,
      cursorLine: Int,
      cursorColumn: Int,
  ) =
    val rows = Vector.newBuilder[VisualRow]
    lines.indices.foreach { line =>
      val clusters = lines(line)
      val spans    = layout(line)
      spans.zipWithIndex.foreach { case ((start, end), index) =>
        val last = index == spans.size - 1
        // measured only for the one row it can matter to, so laying out the rows costs nothing per cluster
        if last && line == cursorLine && cursorColumn == clusters.size &&
          ClusterRow.visibleWidth(clusters, start, end) >= width
        then
          rows += VisualRow(line, start, end, last = false)
          rows += VisualRow(line, end, end, last = true)
        else rows += VisualRow(line, start, end, last)
      }
    }
    rows.result()

  /** Paints one row and its gutter cell. The blank one past the last cluster is left untouched unless the cursor sits
    * on it, so an editor does not paint its own background over whatever is behind the end of a short line — see
    * [[ClusterRow.draw]] for the wide-glyph and cursor rules.
    */
  private def renderRow(buffer: Buffer, layout: Layout, row: VisualRow, y: Int, state: TextAreaState): Unit =
    val (cursorLine, cursorColumn) = state.cursor
    val clusters                   = state.clusterLines(row.line)
    if layout.gutterWidth > 0 then
      val number = if row.start == 0 then (row.line + 1).toString else ""
      buffer.setString(
        layout.text.x - layout.gutterWidth,
        y,
        " " * (layout.gutterWidth - 1 - number.length) + number,
        options.gutterStyle,
      )
    // Without wrap the offset is a column count shared by every line, and this line's clusters may not have a boundary
    // there: a cluster straddling the offset is skipped and the row starts that much further right, so the columns of
    // all the lines still line up. Counting the offset in clusters instead scrolled a line of wide characters roughly
    // twice as far as the ASCII lines around it, and often clean off the screen.
    val (firstCluster, x0)         =
      if options.wrap then (row.start, layout.text.x)
      else
        val (first, from) = ClusterRow.clusterAtColumn(clusters, layout.scrollColumn)
        (first, layout.text.x + (from - layout.scrollColumn))
    ClusterRow.draw(
      buffer,
      x0 = x0,
      y = y,
      right = layout.text.right,
      clusters = clusters,
      scroll = firstCluster,
      cursorAt = cursorColumn,
      showCursor = showCursor && row.line == cursorLine && covers(row, cursorColumn),
      style = style,
      cursorStyle = cursorStyle,
      paintEndCell = false,
      tokenStyles = options.lexer.fold(IndexedSeq.empty)(lexer => tokenStyles(clusters, lexer)),
      highlights = highlightsOn(row.line, clusters.size, state),
      until = if row.last then clusters.size + 1 else row.end,
    )

  /** Search matches, then the selection over them, as cluster ranges of `line`. */
  private def highlightsOn(line: Int, size: Int, state: TextAreaState): Seq[ClusterRow.Highlight] =
    val found     = state.matches.iterator
      .filter(_.start.line == line)
      .map(hit => ClusterRow.Highlight(hit.start.column, hit.end.column, options.matchStyle))
      .toList
    val selection = state.selection.toList.collect {
      case (from, to) if from.line <= line && to.line >= line =>
        val start = if from.line == line then from.column else 0
        val end   = if to.line == line then to.column else size
        ClusterRow.Highlight(start, end, options.selectionStyle)
    }
    found ++ selection

  /** One style per cluster from `lexer`, or none when its spans do not segment back into the same clusters — a lexer
    * that rewrote the text has its styling ignored rather than painted onto the wrong cells.
    */
  private def tokenStyles(clusters: Vector[String], lexer: Lexer): IndexedSeq[Style] =
    val graphemes = lexer.highlightLine(clusters.mkString, options.syntax).styledGraphemes(Style.Default).toVector
    if graphemes.map(_.cluster) == clusters then graphemes.map(_.style) else IndexedSeq.empty

  /** Scrolls all lines left just enough that the cursor's column, measured on the cursor's own line, stays visible. The
    * rule and the reason it reserves the cursor cluster's display width rather than a flat column both live in
    * [[ClusterRow.scrolledTo]]; every line is drawn at this one offset, so the cursor's line is the one that decides
    * it.
    *
    * The offset is carried between frames as a number of *columns*, because that is the only unit the lines have in
    * common: a cluster index means a different distance on a line of wide characters than on a line of ASCII. The
    * solver works in cluster indices on the cursor's own line, so the offset is converted into that line's clusters on
    * the way in and back into columns on the way out.
    */
  private def scrolledHorizontally(state: TextAreaState, width: Int): Int =
    val (cursorLine, cursorColumn) = state.cursor
    val clusters                   = state.clusterLines(cursorLine)
    val (scrollFrom, _)            = ClusterRow.clusterAtColumn(clusters, state.scrollColumn)
    val scrolledTo                 = ClusterRow.scrolledTo(clusters, scrollFrom, cursorColumn, width)
    ClusterRow.columnOfCluster(clusters, scrolledTo)

object TextArea:

  /** Greedy soft wrap of one line into `[start, end)` cluster spans of at most `width` columns: a span that would
    * overflow ends after its last whitespace cluster when it has one, otherwise between graphemes. A cluster wider than
    * `width` gets a span of its own (which the painter then refuses). An empty line is one empty span.
    */
  private[widgets] def wrapSpans(clusters: Vector[String], width: Int): Vector[(Int, Int)] =
    if clusters.isEmpty || width <= 0 then Vector((0, clusters.size))
    else
      val spans = Vector.newBuilder[(Int, Int)]
      var start = 0
      while start < clusters.size do
        var end  = start
        var used = 0
        while end < clusters.size && used + ClusterRow.renderedWidth(clusters(end)) <= width do
          used += ClusterRow.renderedWidth(clusters(end))
          end += 1
        if end < clusters.size then
          val space = (end until start by -1).find(k => clusters(k - 1).codePoints().allMatch(Character.isWhitespace))
          end = space.getOrElse(end)
        if end == start then end = start + 1
        spans += ((start, end))
        start = end
      spans.result()
