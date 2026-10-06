package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, CharWidth, Style}

/** One row of editable text: how wide each grapheme cluster is, how far the row is scrolled, and how it is drawn.
  *
  * [[TextInput]] is a single row of this and [[TextArea]] is a stack of them, and both have to answer the same
  * questions the same way, because the answers depend on each other. The measurement rule below ("every cluster gets at
  * least one cell") is what the scroll solver counts in and what the draw loop advances by; if the two ever disagreed —
  * which is what happens when the same arithmetic is maintained in two files — the cursor drifts a column further
  * off-screen with every wide or zero-width cluster in the row. Keeping the rule in one place is the point.
  *
  * A cursor position here is a cluster index in `[0, clusters.size]`, where the top value means "one past the end",
  * which renders as a trailing blank cell.
  *
  * These are pure functions over the values passed in; the scroll offset they compute is caller-owned state, written on
  * the render thread by the widget that owns it.
  */
private[widgets] object ClusterRow:

  /** The columns `cluster` occupies on screen — at least one.
    *
    * Measurement has to agree with [[draw]], which gives every cluster a whole cell. A zero-width cluster (a bare
    * combining mark) measured as 0 but drawn in 1 makes the scroll arithmetic drift until the cursor is pushed
    * off-screen.
    */
  def renderedWidth(cluster: String): Int = math.max(1, CharWidth.of(cluster))

  /** The symbol actually drawn for `cluster`. A zero-width cluster — a bare combining mark, or a control character that
    * predates the editing state's filter — is drawn as a blank, because [[renderedWidth]] gives every cluster a whole
    * cell and a symbol whose width disagrees with the cell it occupies desynchronises the backend's cursor model from
    * the terminal's. The cell is still written, so a cursor sitting on it stays visible.
    */
  def drawnSymbol(cluster: String): String = if CharWidth.of(cluster) == 0 then " " else cluster

  /** Writes one grapheme cluster at `(x, y)` and returns the column the next one starts at.
    *
    * The single-cell counterpart of [[RowCursor.write]], and the one place the whole rule is stated: a cluster measures
    * [[renderedWidth]] (at least one column); it is refused outright — not clipped — when it would straddle `right`,
    * because half of a wide glyph is not a character; a zero-width cluster is substituted by [[drawnSymbol]] so the
    * cell drawn is as wide as the cell measured; and the continuation cell of a two-column glyph is blanked by
    * `Buffer.set`, which owns that bookkeeping.
    *
    * The head advances by the cluster's width whether or not it was drawn, so a caller looping to `right` always
    * terminates.
    *
    * @param right
    *   the first column that must not be written — an area's `right`, i.e. exclusive
    */
  def put(buffer: Buffer, x: Int, y: Int, cluster: String, style: Style, right: Int): Int =
    val width = renderedWidth(cluster)
    if x + width <= right then buffer.set(x, y, Cell(drawnSymbol(cluster), style))
    x + width

  /** The columns clusters `[from, until)` occupy together. */
  def visibleWidth(clusters: IndexedSeq[String], from: Int, until: Int): Int =
    clusters.slice(from, until).map(renderedWidth).sum

  /** The furthest-right offset worth scrolling to: the one that just fits the end of the row, cursor column included.
    * Scrolling past it only pushes text off the left edge to leave blanks on the right.
    */
  def rightmostUsefulScroll(clusters: IndexedSeq[String], width: Int): Int =
    var index = clusters.size
    var used  = 1 // the end-of-row cursor always occupies one column
    while index > 0 && used + renderedWidth(clusters(index - 1)) <= width do
      used += renderedWidth(clusters(index - 1))
      index -= 1
    index

  /** The offset to render at: the smallest change to `scroll` that keeps the cursor visible in `width` columns.
    *
    * What is reserved for the cursor is the display width of the cluster it sits on — one column when the cursor is
    * past the end of the row, which renders a trailing space. Reserving a flat column instead would let this call a
    * two-column cluster visible that [[draw]] then refuses to paint, and the cursor would vanish off the right edge.
    *
    * The offset is caller-owned state that outlives any one frame, so it has to be pulled *back* as well as pushed
    * forward: deleting text or widening the terminal both leave an offset further right than the row now needs, and the
    * row would render blank with its content off the left edge.
    */
  def scrolledTo(clusters: IndexedSeq[String], scrollFrom: Int, cursor: Int, width: Int): Int =
    val cursorWidth = if cursor < clusters.size then renderedWidth(clusters(cursor)) else 1
    val earliest    = math.min(math.min(scrollFrom, cursor), rightmostUsefulScroll(clusters, width))
    var scroll      = cursor
    var used        = cursorWidth.toLong
    var stopped     = false
    // Walk backwards through the width budget once, rather than re-measuring every successively shorter suffix.
    // Stop at the old (clamped) offset: when it already fits, preserving it is the smallest possible change.
    while scroll > earliest && !stopped do
      val nextWidth = renderedWidth(clusters(scroll - 1))
      if used + nextWidth <= width.toLong then
        used += nextWidth
        scroll -= 1
      else stopped = true
    scroll

  /** Where a column offset falls in a row: the first cluster that starts at or after `column`, and the column it
    * actually starts at.
    *
    * A row is a sequence of clusters of differing widths, so a column offset shared between rows — [[TextArea]] scrolls
    * every line by one — does not land on a cluster boundary in all of them. A cluster straddling the offset is left
    * whole and skipped: the returned column is then one past the requested one, and the caller starts drawing that far
    * to the right so the columns of every row still line up.
    *
    * The returned column is never less than `column`, and the returned index is `clusters.size` when the row ends
    * before the offset (that row is scrolled entirely off the left edge).
    */
  def clusterAtColumn(clusters: IndexedSeq[String], column: Int): (Int, Int) =
    var index = 0
    var used  = 0
    while index < clusters.size && used < column do
      used += renderedWidth(clusters(index))
      index += 1
    (index, used)

  /** The column cluster `index` starts at — the inverse of [[clusterAtColumn]] on a cluster boundary. */
  def columnOfCluster(clusters: IndexedSeq[String], index: Int): Int = visibleWidth(clusters, 0, index)

  /** Paints one row of clusters starting at `scroll`, left to right from `x` up to (but not including) `right`.
    *
    * A cluster that would straddle `right` is skipped rather than clipped, because half of a wide glyph is not a
    * character. Wide glyphs reserve their continuation cell through `Buffer.set`; see [[put]].
    *
    * `cursorAt` is highlighted with `style` patched by `cursorStyle`, and only when `showCursor` is set — an unfocused
    * field passes `false` so exactly one field on screen shows a cursor.
    *
    * `paintEndCell` decides what happens to the blank one past the end of the row. A single-line input paints it, so
    * the field's own background runs to the end of its text; a multi-line editor does not, leaving the cell untouched
    * for whatever is behind it, and paints it only when the cursor is there.
    */
  def draw(
      buffer: Buffer,
      x0: Int,
      y: Int,
      right: Int,
      clusters: IndexedSeq[String],
      scroll: Int,
      cursorAt: Int,
      showCursor: Boolean,
      style: Style,
      cursorStyle: Style,
      paintEndCell: Boolean,
  ): Unit =
    var x     = x0
    var index = scroll
    while index <= clusters.size && x < right do
      val atEnd     = index == clusters.size
      val symbol    = if atEnd then " " else clusters(index)
      val isCursor  = showCursor && index == cursorAt
      val cellStyle = if isCursor then style.patch(cursorStyle) else style
      // the blank past the end is skipped rather than painted when the caller wants whatever is behind it to show
      x =
        if !atEnd || isCursor || paintEndCell then put(buffer, x, y, symbol, cellStyle, right)
        else x + renderedWidth(symbol)
      index += 1
