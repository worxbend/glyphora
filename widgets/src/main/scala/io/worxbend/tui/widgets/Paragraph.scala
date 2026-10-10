package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Buffer, Line, LineBreaks, Measured, Rect, Span, Style, Text, Widget}

/** Multi-line styled text with alignment and optional wrapping.
  *
  * `alignment` places every line, and it is the last word rather than the first: a [[Line]] that carries an alignment
  * of its own overrides it for that one row, and failing that the [[Text]]'s own alignment overrides it for every row.
  * A wrapped line keeps its alignment on every row it spills onto.
  *
  * `style` is likewise the outermost style layer, not the only one. Every character is drawn with this style, then the
  * text's own [[io.worxbend.tui.core.Text.style]] on top of it, then the line's, then the span's — each step a
  * [[io.worxbend.tui.core.Style.patch]], so the more specific layer wins wherever it speaks. A `Paragraph` given a
  * `style` therefore no longer erases a style the `Text` was built with; it sits underneath it.
  *
  * With [[Overflow.Wrap]] the text breaks at word boundaries: a word moves to the next row whole rather than being cut
  * in half, and the blanks that sat at the break are dropped instead of becoming a stray indent on the next row. A word
  * longer than the whole width has nowhere to go, so that one is broken between grapheme clusters — never inside one,
  * so a wide character or emoji is never split. With [[Overflow.Clip]], the default, long lines are cut at the area
  * edge. [[Overflow.WrapTrimmed]] and [[Overflow.WrapPreserved]] wrap the same way and differ only in what becomes of
  * the blanks at the head of a row — see [[Overflow]] for the three renderings side by side.
  *
  * A line that is still wider than the area — one that is clipped, or a single unbreakable word — loses the side away
  * from its `alignment`: a right-aligned line keeps its end, a centred one loses as much from each side, and a
  * left-aligned one keeps its beginning. Keeping the beginning of a right-aligned line would hide exactly the part the
  * alignment was chosen to show, which for a path or a timestamp is the part that identifies it.
  *
  * `scrollY` and `scrollX` make the area a window into a larger document rather than the whole of it. `scrollY` skips
  * that many *composed* rows before drawing: with [[Overflow.Wrap]] those are wrapped rows, not source lines, so
  * scrolling a reflowed document by one moves the view by exactly one screen row whatever the width — which is the case
  * [[ScrollView]] cannot express, because it scrolls a rendered widget and has to be told its content height. `scrollX`
  * skips that many columns from the start of every row it draws, which only has anything to skip under
  * [[Overflow.Clip]]: wrapped rows are never wider than the area. Both are clamped at zero, and scrolling past the end
  * of the text leaves the area blank rather than failing.
  *
  * `style` is the paragraph's own style, and it is painted over the whole area — not only the cells that carry a
  * character. A paragraph given a background colour therefore reads as a block of colour with text in it, which is what
  * a caller sets a background for; each span's style is then layered on top of it for the text itself.
  *
  * [[heightAt]] reports the rows the paragraph needs from the same `overflow` field `render` draws with, so the two
  * cannot disagree about whether the text wraps.
  */
final case class Paragraph(
    text: Text,
    alignment: Alignment = Alignment.Left,
    overflow: Overflow = Overflow.Clip,
    scrollY: Int = 0,
    scrollX: Int = 0,
    style: Style = Style.Default,
) extends Widget
    with Measured:

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      // The paragraph's own style is painted across the *whole* area before any text is drawn, so a background colour
      // covers the blank columns after a short line and the rows below the last one, instead of showing up as ragged
      // coloured text on whatever was behind it. `mapStyle` with `patch`, not `setStyle`: it layers onto what is
      // already there, keeping each cell's glyph and foreground, the same rule `Block` follows for its panel fill.
      // The `Style.Default` guard matters: without it every paragraph would touch every cell of its area on every
      // frame, and the diff-based flush would lose its cheap path for the overwhelmingly common unstyled case.
      if style != Style.Default then buffer.mapStyle(area)(_.patch(style))
      // lazily: wrapping the whole document to draw one screenful makes render cost scale with the text, not the area
      val lines     = overflow.wrapBlanks match
        case Some(blanks) => text.lines.iterator.flatMap(Paragraph.wrapLine(_, area.width, blanks))
        case None         => text.lines.iterator
      // resolved once for the whole paragraph rather than per row: the widget's style is the floor, the text's own
      // style is laid over it, and each line then lays its own over that inside `LineRenderer`
      val baseStyle = style.patch(text.style)
      // Skipping happens on the lazy iterator, so scrolling past a long document costs the rows skipped rather than a
      // full layout of the text. A negative offset is clamped to zero: `drop(-n)` drops nothing, but reading that as
      // "scrolled to the top" is a decision worth making here rather than inheriting from the collections library.
      lines.drop(math.max(0, scrollY)).take(area.height).zipWithIndex.foreach { (line, row) =>
        // the line's own alignment wins over the text's, which wins over the paragraph's argument; `None` at both
        // inner levels means "use the paragraph's", which is what every line said before either could carry one
        val placement  = line.alignment.orElse(text.alignment).getOrElse(alignment)
        // measured once here and handed to the renderer as `lineWidth`, so the line — and, through the skip path, its
        // spans — is not measured a second time on the same draw
        val lineWidth  = line.width
        // A line wider than the area loses the side *away* from the alignment: a right-aligned line keeps its end, a
        // centred one loses as much from each side. Left-aligned text keeps its beginning, as before.
        val tooWideBy  = math.max(0, lineWidth - area.width)
        val alignSkip  = placement match
          case Alignment.Left   => 0
          case Alignment.Center => tooWideBy / 2
          case Alignment.Right  => tooWideBy
        // The horizontal offset is thrown away on top of whatever the alignment already threw away, so a right-aligned
        // line scrolled by two columns shows two columns further left than the end it would otherwise keep.
        val skipWidth  = alignSkip + math.max(0, scrollX)
        // What is left of the line once those columns are gone, capped at the area: the number the alignment places.
        // With no scroll offset and no over-wide line this is the plain `min(line.width, area.width)` it always was.
        val drawnWidth = math.max(0, math.min(lineWidth - skipWidth, area.width))
        val startX     = placement.originAt(area.x, area.width, drawnWidth)
        // the paragraph has already placed the line itself, by choosing `startX`, so the renderer is told to draw from
        // there and not to align a second time
        val _          = LineRenderer.render(
          buffer,
          startX,
          area.y + row,
          line,
          area.right - startX,
          baseStyle,
          Alignment.Left,
          skipWidth,
          lineWidth,
        )
      }

  /** The rows this text occupies at `width` — the measurement counterpart of [[render]], and always an answer: a
    * paragraph always knows its own height. A clipping paragraph is one row per line; a wrapping one counts the rows
    * `wrapLine` would produce, which is why the two share that function rather than each doing the arithmetic.
    *
    * A wrapping paragraph given no columns at all (`width <= 0`) measures zero rows, because zero rows is what it
    * draws: there is nothing to wrap into, `wrapLine` returns no rows and `render` returns immediately on an empty
    * area. It used to answer one row per source line there, which is the one width at which measurement and rendering
    * could disagree. Zero is an answer, not a refusal — [[io.worxbend.tui.core.Measured]] reserves `None` for "cannot
    * say", and a paragraph with no columns can say. A clipping paragraph is still one row per line at any width,
    * because clipping never consults the width to decide how many rows there are.
    *
    * The scroll offsets are deliberately not subtracted. This is the height of the whole text, which is what a caller
    * sizing a viewport or driving a [[Scrollbar]] beside it needs: the scrollbar's `contentLength` is the document, and
    * its `position` is the same `scrollY` the paragraph was given. A scrolled paragraph still occupies whatever rows
    * its area gives it.
    */
  override def heightAt(width: Int): Option[Int] =
    val rows = overflow.wrapBlanks match
      case None                  => text.lines.size
      case Some(_) if width <= 0 => 0
      case Some(blanks)          =>
        text.lines.map(line => math.max(1, Paragraph.wrappedRowCount(line, width, blanks))).sum
    Some(rows)

  /** The columns this text needs so that no line is clipped and no line has to wrap — the width counterpart of
    * [[heightAt]], and always an answer, because the longest line is a property of the text itself and needs no layout
    * to work out.
    *
    * The `height` argument is ignored: a paragraph flows top to bottom, so how many rows it is given does not change
    * how wide it would like to be. The answer is the same for both [[Overflow]] modes on purpose — `Overflow.Clip`
    * needs this width to avoid cutting a line off, `Overflow.Wrap` needs it to avoid reflowing one. It is the longest
    * *line*, not the longest word: at the longest-word width the text still fits, but only after wrapping, which is the
    * thing this number exists to avoid.
    */
  override def widthAt(height: Int): Option[Int] =
    Some(text.width)

object Paragraph:

  /** Breaks `line` into the rows it occupies at `width`, at word boundaries where the row has one.
    *
    * This is the single owner of the wrapping decision: `render` draws the rows it returns and `heightAt` counts them,
    * so the two can never disagree. The rules, in the order the loop applies them:
    *
    *   - A word is committed to the current row only if the whole word fits. Otherwise the row is flushed and the word
    *     starts the next one.
    *   - The blanks at the point of the break are dropped, so a wrapped row never begins with the space that caused the
    *     break. Blanks the caller wrote at the *start* of the source line are content, not a break point, and are kept
    *     — indentation survives wrapping.
    *   - What counts as a blank is [[io.worxbend.tui.core.LineBreaks.isBreakingSpace]], so U+00A0 NO-BREAK SPACE holds
    *     two words together and U+200B ZERO WIDTH SPACE offers a break that costs no column.
    *   - A single word longer than the whole width cannot be placed whole anywhere, so it is broken between grapheme
    *     clusters, and a single cluster wider than the whole width gets a row of its own and is clipped by the
    *     renderer. Dropping it would be worse than clipping it: `heightAt` counts these rows, so a dropped cluster
    *     would shift every following line up by a row.
    *   - A source line that produces no rows at all (it was empty, or nothing but blanks) still yields one empty row,
    *     so a blank line in the source stays a blank line on screen.
    *
    * Span styles survive the reflow: each output row carries the spans it was built from, so bold or coloured runs from
    * [[Markdown]] keep their styling across a break.
    */
  private[widgets] def wrapLine(line: Line, width: Int, blanks: WrapBlanks = WrapBlanks.KeepIndent): Seq[Line] =
    if width <= 0 then Seq.empty
    else if line.width <= width && blanks != WrapBlanks.DropAll then Seq(line)
    else
      val sink = LineSink(line.alignment, line.style)
      WrapWalker(width, blanks, sink).walk(line)
      sink.rows

  /** How many rows [[wrapLine]] would return for `line` at `width`, without building any of them.
    *
    * `heightAt` used to call `wrapLine(...).size`, which built every row, every span and every fitted string only to
    * keep the integer and throw the rest away — measuring a long document allocated as much as drawing it, and a layout
    * pass measures far more text than it ever draws. This runs the same walk with a sink that counts instead of
    * collecting, so the number is still produced by the one algorithm `render` draws with; there is no second
    * implementation that could drift away from it.
    */
  private[widgets] def wrappedRowCount(line: Line, width: Int, blanks: WrapBlanks = WrapBlanks.KeepIndent): Int =
    if width <= 0 then 0
    else if line.width <= width then 1
    else
      val sink = CountingSink()
      WrapWalker(width, blanks, sink).walk(line)
      sink.rows

  /** Reads a line cluster by cluster and tells `sink` where each row of at most `width` columns ends.
    *
    * The wrapping decisions all live here, so the rows `render` draws and the rows `heightAt` counts can never come
    * from two different algorithms; the sink only decides whether the text of a row is kept or discarded. The rules are
    * the ones documented on [[wrapLine]].
    */
  private final class WrapWalker(width: Int, blanks: WrapBlanks, sink: RowSink):
    private var rows           = 0
    // Widths of the row being filled, the run of blanks seen since the last word, and the word being read. A word is
    // only moved onto the row once the whole of it is known to fit, which is what makes the break land between words.
    private var rowWidth       = 0
    private var gapWidth       = 0
    private var wordWidth      = 0
    // Columns of the word's first cluster: the narrowest a row holding any of this word can be, and so what decides
    // whether an indent still leaves the word somewhere to go.
    private var wordFirstWidth = 0
    private var rowHasContent  = false
    private var wordHasContent = false
    // True until the first row ends: while it holds, the pending blanks are the caller's own indentation rather than
    // a break point, and are kept.
    private var atLineStart    = true

    /** The whole walk: read every cluster of `line`, then flush the pending word and the row it lands on. */
    def walk(line: Line): Unit =
      line.spanGraphemes.foreach { grapheme =>
        val cluster = grapheme.cluster
        if LineBreaks.isBreakingSpace(cluster) then
          commitWord()
          sink.addGap(cluster, grapheme.style)
          gapWidth += grapheme.width
        else if LineBreaks.isZeroWidthBreak(cluster) then
          // A break opportunity with no glyph, standing on its own at the very start of the text: end the word here
          // and drop the character, which draws nothing whether the break is taken or not.
          commitWord()
        else
          val clusterWidth = grapheme.width
          // The blanks that will be written in front of this word are part of the row it lands on, so they are counted
          // against the width here too. Left out, a line whose first row is indented was broken one column too late
          // and the renderer clipped that column away: " abcde" at width 5 came out as the single row " abcde".
          val firstWidth   = if wordHasContent then wordFirstWidth else clusterWidth
          if gapOnFreshRow(firstWidth, wordWidth + clusterWidth) + wordWidth + clusterWidth > width then
            // The word alone is wider than any row can be, so it has to be broken. Put what has been read onto a row
            // of its own and carry on reading the rest of the word into the next one.
            commitWord()
            if rowHasContent then endRow()
          if !wordHasContent then wordFirstWidth = clusterWidth
          sink.addWord(cluster, grapheme.style)
          wordWidth += clusterWidth
          wordHasContent = true
          // A zero width space rides along inside the cluster before it, and says a break is allowed after that
          // cluster: end the word here so the next one may start on a new row.
          if LineBreaks.endsWithZeroWidthBreak(cluster) then commitWord()
      }
      commitWord()
      if rowHasContent || rows == 0 then endRow()

    private def endRow(): Unit =
      sink.endRow()
      rows += 1
      rowWidth = 0
      rowHasContent = false
      atLineStart = false

    /** Whether the pending blanks would survive onto a row with nothing on it yet.
      *
      * This is the one thing the three wrapping modes disagree about, so it is read off `blanks` rather than decided at
      * each call site: keep only the line's own indentation, keep nothing, or keep everything.
      *
      * Both keeping modes stop short of a row wider than `width`, which is not a visible wrap bug — the renderer clips
      * such a row, so it reads as a character that has gone missing — but they stop differently, because they promise
      * different things. An indent is part of the line's content, so it is kept and the word beside it is broken to
      * fit; it is dropped only when it would leave no room for even the word's first cluster, which is the case where
      * that cluster gets a row of its own. Blanks carried onto a later row by `KeepAll` are a courtesy, so they are
      * dropped as soon as the whole word no longer fits after them.
      *
      * @param firstCluster
      *   columns taken by the first cluster of the word being placed — the smallest a broken word can be
      * @param word
      *   columns taken by the whole word being placed
      */
    private def keepsGap(firstCluster: Int, word: Int): Boolean =
      blanks match
        case WrapBlanks.KeepIndent => atLineStart && !rowHasContent && gapWidth + firstCluster <= width
        case WrapBlanks.DropAll    => false
        case WrapBlanks.KeepAll    => gapWidth + word <= width

    /** The columns the pending blanks would take on a fresh row — zero when they would be dropped there. */
    private def gapOnFreshRow(firstCluster: Int, word: Int): Int = if keepsGap(firstCluster, word) then gapWidth else 0

    private def commitWord(): Unit =
      if wordHasContent then
        if !rowHasContent then
          val keepGap = keepsGap(wordFirstWidth, wordWidth)
          sink.takeWord(keepGap)
          rowWidth = wordWidth + (if keepGap then gapWidth else 0)
        else if rowWidth + gapWidth + wordWidth <= width then
          sink.takeWord(keepGap = true)
          rowWidth = rowWidth + gapWidth + wordWidth
        else
          // The word does not fit after the pending blanks, so the row ends here. `KeepAll` carries those blanks onto
          // the new row — that is the whole of what it means — but only while they still leave the word its columns.
          endRow()
          val keepGap = keepsGap(wordFirstWidth, wordWidth)
          sink.takeWord(keepGap)
          rowWidth = wordWidth + (if keepGap then gapWidth else 0)
        rowHasContent = true
        wordHasContent = false
        wordWidth = 0
        wordFirstWidth = 0
        // The blanks have now either been written into the row or dropped at a break; either way they are spent.
        // They are only cleared here, with a word: a run of blanks with no word after it yet is still growing.
        sink.clearGap()
        gapWidth = 0

  /** Where [[WrapWalker]] puts the text it reads. `LineSink` keeps it and hands back rows; `CountingSink` throws it
    * away and only counts them. The walker owns every decision about *where* a row ends, so the two cannot disagree.
    */
  private sealed trait RowSink:
    /** Adds a blank to the run of blanks pending between the last word and the next. */
    def addGap(cluster: String, style: Style): Unit

    /** Adds a cluster to the word being read. */
    def addWord(cluster: String, style: Style): Unit

    /** Moves the pending word onto the current row, preceded by the pending blanks when `keepGap` is true. */
    def takeWord(keepGap: Boolean): Unit

    /** Discards the pending blanks: they were spent, either written into a row or dropped at a break. */
    def clearGap(): Unit

    /** Ends the current row. */
    def endRow(): Unit

  /** The sink [[wrapLine]] uses: collects the spans of each row and merges neighbouring clusters of the same style back
    * into one span, so a row read one cluster at a time comes out as the few spans it was written as.
    */
  private final class LineSink(alignment: Option[Alignment], style: Style) extends RowSink:
    private val wrapped = List.newBuilder[Line]
    private var row     = Vector.empty[Span]
    private var gap     = Vector.empty[Span]
    private var word    = Vector.empty[Span]

    def addGap(cluster: String, style: Style): Unit  = gap = appended(gap, cluster, style)
    def addWord(cluster: String, style: Style): Unit = word = appended(word, cluster, style)

    def takeWord(keepGap: Boolean): Unit =
      row = if keepGap then row ++ gap ++ word else row ++ word
      word = Vector.empty

    def clearGap(): Unit = gap = Vector.empty

    def endRow(): Unit =
      wrapped += Line(row, alignment, style)
      row = Vector.empty

    /** The rows read so far, in order. */
    def rows: Seq[Line] = wrapped.result()

  /** The sink [[wrappedRowCount]] uses: keeps nothing at all, so measuring costs a walk and an integer. */
  private final class CountingSink extends RowSink:
    private var count = 0

    def addGap(cluster: String, style: Style): Unit  = ()
    def addWord(cluster: String, style: Style): Unit = ()
    def takeWord(keepGap: Boolean): Unit             = ()
    def clearGap(): Unit                             = ()
    def endRow(): Unit                               = count += 1

    /** How many rows the walk ended. */
    def rows: Int = count

  /** `spans` with `text` on the end, merged into the last span when it carries the same style, so a run of clusters
    * read one at a time comes back out as one span rather than one span per character.
    */
  private def appended(spans: Vector[Span], text: String, style: Style): Vector[Span] =
    if spans.nonEmpty && spans.last.style == style then spans.init :+ Span(spans.last.content + text, style)
    else spans :+ Span(text, style)
