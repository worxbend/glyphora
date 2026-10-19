package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Position, Rect, StatefulWidget, Style}

/** Caller-owned single-line editing state: an [[EditBuffer]] holding one line.
  *
  * The text is stored as grapheme clusters, so the cursor can never land inside a combining sequence or split an emoji;
  * the cursor is a cluster index in `[0, length]` (the top value meaning "append here"). Edits re-segment the line and
  * map the cursor with right affinity: if the splice falls inside a newly merged grapheme, the cursor lands after that
  * grapheme. Selection, word motion, kill ring, clipboard and coalesced undo all come from [[EditBuffer]].
  *
  * Control characters — line breaks included — are dropped on the way in, by the constructor as well as by [[insert]],
  * because a field is just as often seeded from a file or an HTTP response as it is typed into. A control is zero
  * columns wide but still fills a whole `Cell`, so storing one desynchronises the backend's cursor model from the
  * terminal's.
  *
  * Render-thread-only. Every mutation raises `version` (see [[io.worxbend.tui.core.Versioned]]), so a DSL node that
  * paints this state notices a write made outside the event path — a background result applied on the render thread —
  * and schedules the frame that shows it, with no `Signal` write and no `requestRedraw()`.
  */
final class TextInputState(initial: String = "", killRing: KillRing = KillRing())
    extends EditBuffer(initial, multiline = false, killRing):

  private[widgets] var scrollCluster: Int = 0

  /** Cursor position as a cluster index. */
  def cursor: Int = caret.column

  /** Empties the field, forgets its undo history and scrolls back to the start. */
  override def clear(): Unit =
    super.clear()
    scrollCluster = 0

  private[widgets] def clusterSeq: Vector[String] = clusterLines.head

/** A single-line text input with horizontal scrolling and an optional visible cursor.
  *
  * The cursor is drawn by styling the cluster under it (or a trailing space) with `cursorStyle` — pass
  * `showCursor = false` for unfocused inputs so only the focused field shows a cursor. A selection paints in
  * `selectionStyle`.
  *
  * `mask` draws every grapheme cluster of the value as that one glyph — `Some("•")` for a password field — so neither
  * the text nor its display width shows; scrolling, the cursor and [[caretAt]] all follow the masked row. The value in
  * the state is untouched, and the placeholder of an empty field is drawn as is.
  */
final case class TextInput(
    placeholder: String = "",
    showCursor: Boolean = true,
    style: Style = Style.Default,
    cursorStyle: Style = Style.Default.reverse,
    placeholderStyle: Style = Style.Default.dim,
    selectionStyle: Style = Style.Default.reverse,
    mask: Option[String] = None,
) extends StatefulWidget[TextInputState]:

  /** The clusters as drawn: the value's own, or one mask glyph per cluster. */
  private def shown(state: TextInputState): Vector[String] =
    mask.fold(state.clusterSeq)(glyph => Vector.fill(state.clusterSeq.size)(glyph))

  def render(area: Rect, buffer: Buffer, state: TextInputState): Unit =
    if !area.isEmpty then
      val clusters = shown(state)
      if clusters.isEmpty then renderEmpty(area, buffer)
      else
        state.scrollCluster = ClusterRow.scrolledTo(clusters, state.scrollCluster, state.cursor, area.width)
        renderClusters(area, buffer, state, clusters)

  /** The buffer cell the caret occupies when this widget is drawn into `area` for `state` — where a host parks the
    * terminal's own cursor so that a screen reader and an input method follow the insertion point the painted cursor
    * shows.
    *
    * Computed from the same scroll and width arithmetic [[render]] uses, so the two can never disagree: the scroll
    * offset is re-solved from the state's current one, which is a no-op after a render and the same answer before one.
    * The state is not written. `None` when `area` is empty or the caret's cell would fall past the area's right edge,
    * which is when the painter draws no cursor either. Independent of `showCursor` — that flag decides whether the
    * painted cursor shows, and a host hiding the painted cell still needs the geometry.
    */
  def caretAt(area: Rect, state: TextInputState): Option[Position] =
    if area.isEmpty then None
    else
      val clusters = shown(state)
      val column   =
        if clusters.isEmpty then area.x
        else
          val scroll = ClusterRow.scrolledTo(clusters, state.scrollCluster, state.cursor, area.width)
          area.x + ClusterRow.visibleWidth(clusters, scroll, state.cursor)
      Option.when(column < area.right)(Position(column, area.y))

  private def renderEmpty(area: Rect, buffer: Buffer): Unit =
    buffer.setString(area.x, area.y, CharWidth.substringByWidth(placeholder, area.width), placeholderStyle)
    // The cursor goes through the same gate as every other cluster: a two-column glyph in a one-column field is
    // refused, not clipped. Written straight into the buffer it claimed a continuation cell outside `area` and painted
    // over whatever the widget next door had put there.
    if showCursor then
      val _ = ClusterRow.put(buffer, area.x, area.y, placeholderCursorSymbol, cursorStyle, area.right)

  private def placeholderCursorSymbol: String =
    val clusters = CharWidth.graphemeClusters(placeholder)
    if clusters.hasNext then clusters.next() else " "

  /** Paints the row, ending with a blank cell past the last cluster so the field's own styling runs to the end of the
    * text rather than stopping mid-line — see [[ClusterRow.draw]] for the wide-glyph and cursor rules.
    */
  private def renderClusters(area: Rect, buffer: Buffer, state: TextInputState, clusters: Vector[String]): Unit =
    ClusterRow.draw(
      buffer,
      x0 = area.x,
      y = area.y,
      right = area.right,
      clusters = clusters,
      scroll = state.scrollCluster,
      cursorAt = state.cursor,
      showCursor = showCursor,
      style = style,
      cursorStyle = cursorStyle,
      paintEndCell = true,
      highlights =
        state.selection.toList.map((from, to) => ClusterRow.Highlight(from.column, to.column, selectionStyle)),
    )
