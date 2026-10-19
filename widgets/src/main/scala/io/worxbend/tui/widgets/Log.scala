package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Line, Rect, StatefulWidget, Style, VersionedState}

import scala.collection.mutable

/** Caller-owned [[Log]] state: a bounded ring of lines plus follow-tail scrolling.
  *
  * While `follow` is on (the default), the view pins to the newest lines; scrolling up detaches it and scrolling back
  * to the bottom re-attaches.
  *
  * Render-thread-only. Every mutation raises `version` (see [[io.worxbend.tui.core.Versioned]]), so a DSL node that
  * paints this state notices a write made outside the event path — a background result applied on the render thread —
  * and schedules the frame that shows it, with no `Signal` write and no `requestRedraw()`.
  */
final class LogState(maxLines: Int = 1000) extends VersionedState:

  require(maxLines > 0, s"a log needs a positive maxLines, got $maxLines")

  private val ring    = mutable.ArrayDeque[Line]()
  def follow: Boolean = followValue

  def follow_=(value: Boolean): Unit =
    followValue = value
    touch()

  private var followValue: Boolean = true
  def offset: Int                  = offsetValue

  def offset_=(value: Int): Unit =
    offsetValue = value
    touch()

  private var offsetValue: Int                 = 0
  private[widgets] var lastViewportHeight: Int = 1

  /** Moves on every change to the lines the log holds — an append (and the eviction it may cause) or a clear — and on
    * nothing else, so a search keyed on it re-matches when the text changes and not when the reader scrolls.
    */
  private[widgets] var revision: Long = 0L

  /** Appends `text` as one or more lines, split on `\n` (a lone `\r` before it is trimmed, so CRLF input splits clean).
    * A caller piping raw chunks from a process or a socket — the ordinary way a [[Log]] gets its content — routinely
    * hands this a chunk spanning several lines; treating the whole chunk as one [[Line]] would not wrap it, it would
    * render it as one row with every embedded `\n` silently gone, because `Character.isISOControl` (see
    * [[io.worxbend.tui.core.CharWidth]]) gives a control character zero display width and [[Buffer.setString]] never
    * writes a zero-width cluster at all. Splitting here is what makes `append` behave the way appending to a log looks
    * like it should.
    */
  def append(text: String): Unit = text.split("\n", -1).foreach(raw => append(Line.raw(raw.stripSuffix("\r"))))

  def append(line: Line): Unit =
    ring.append(line)
    revision += 1
    touch()
    while ring.size > maxLines do
      val _ = ring.removeHead()
      if !follow then offset = math.max(0, offset - 1)

  def size: Int = ring.size

  def clear(): Unit =
    ring.clear()
    revision += 1
    touch()
    offset = 0
    follow = true

  def scrollUp(count: Int = 1): Unit =
    follow = false
    offset = math.max(0, offset - count)

  /** Scrolls `count` lines towards the newest ones, against the viewport of the last render; scrolling down past the
    * end re-enables follow. The mirror of [[scrollUp]].
    *
    * There is no viewport parameter: by the time any key or wheel handler runs, the widget has already rendered at
    * least once and `lastViewportHeight` is the height the user is looking at. A caller-supplied height could only
    * disagree with what is on screen. Render thread only, like every other method here.
    */
  def scrollDown(count: Int = 1): Unit =
    val maxOffset = math.max(0, ring.size - lastViewportHeight)
    offset = math.min(maxOffset, offset + count)
    if offset >= maxOffset then follow = true

  /** Scrolls as little as possible to show line `row`, detaching follow when that moves the window off the tail — how a
    * search brings its current match on screen. Uses the viewport of the last render, like [[scrollDown]].
    */
  def reveal(row: Int): Unit =
    val height = math.max(1, lastViewportHeight)
    val top    = if follow then math.max(0, ring.size - height) else offset
    if row < top then
      follow = false
      offset = math.max(0, row)
    else if row >= top + height then
      follow = false
      offset = row - height + 1

  /** The content row the window starts at, as the next render will draw it. */
  private[widgets] def windowTop(height: Int): Int =
    if follow then math.max(0, ring.size - height) else math.max(0, math.min(offset, math.max(0, ring.size - height)))

  private[widgets] def lines: collection.IndexedSeq[Line] = ring

  private[widgets] def visibleSlice(height: Int): Seq[Line] =
    lastViewportHeight = height
    // `offset` is a public var and the viewport can grow between frames, so clamp on the way out as well as on the
    // way in — an offset past the last useful row renders the tail of the log followed by blank rows, or nothing.
    offset = math.max(0, math.min(offset, math.max(0, ring.size - height)))
    if follow then offset = math.max(0, ring.size - height)
    ring.slice(offset, offset + height).toSeq

/** An append-only scrolling text panel (build/log output, chat transcripts).
  *
  * Rendered with a [[SearchState]] (the four-argument `render`), the log searches every line it holds — not only the
  * ones on screen — highlights the matches in `matchStyle`, the current one in `currentMatchStyle`, and scrolls so the
  * current match's line is visible whenever the query changes or the search steps with `next`/`previous`.
  */
final case class Log(
    style: Style = Style.Default,
    matchStyle: Style = SearchHighlight.DefaultMatchStyle,
    currentMatchStyle: Style = SearchHighlight.DefaultCurrentStyle,
) extends StatefulWidget[LogState]:

  def render(area: Rect, buffer: Buffer, state: LogState): Unit =
    if !area.isEmpty then
      state.visibleSlice(area.height).zipWithIndex.foreach { (line, row) =>
        val _ = LineRenderer.render(buffer, area.x, area.y + row, line, area.width, style)
      }

  /** Draws the log searched for `search`'s query; see the class notes. Render thread only. */
  def render(area: Rect, buffer: Buffer, state: LogState, search: SearchState): Unit =
    if !area.isEmpty then
      state.lastViewportHeight = area.height
      val found =
        if search.isActive then
          // keyed on the log's revision, so a frame that only scrolls or steps reuses the matches rather than walking
          // every line the ring holds again
          search.matchesFor((state, state.revision)) {
            SearchHighlight.find(state.lines.toIndexedSeq.map(l => SearchHighlight.glyphsOf(l.plainText)), search.query)
          }
        else Vector.empty
      search.record(found, state.windowTop(area.height))
      search.takeReveal().foreach(hit => state.reveal(hit.row))
      render(area, buffer, state)
      val top   = state.offset
      SearchHighlight.paint(buffer, area, area.x, area.y - top, search, matchStyle, currentMatchStyle)
