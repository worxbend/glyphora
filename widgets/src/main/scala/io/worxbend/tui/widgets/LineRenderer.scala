package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Buffer, CharWidth, Line, Style}

/** Shared span-aware single-row text rendering: writes a [[Line]]'s spans in order, clipping at a column budget,
  * layering each span's style over a base style.
  */
private[widgets] object LineRenderer:

  /** Renders `line` starting at `(x, y)`, using at most `maxWidth` columns. Returns the columns written.
    *
    * The clipping rule itself belongs to [[RowCursor]]; this only knows that a line is spans laid end to end and how
    * the three style layers stack. Every character is drawn with `baseStyle`, then the line's own
    * [[io.worxbend.tui.core.Line.style]] layered on top of it, then the span's style on top of that — each step is a
    * [[io.worxbend.tui.core.Style.patch]], so the more specific layer wins wherever it speaks and the outer one shows
    * through wherever it says nothing.
    *
    * `skipWidth` throws the first `skipWidth` columns of the line away before drawing, which is how a caller shows the
    * *end* of a line too wide for the space it has instead of its beginning. A span that falls entirely inside the
    * skipped columns is not drawn at all, the span straddling the edge is cut from its left through
    * [[io.worxbend.tui.core.CharWidth.dropByWidth]], and because that never splits a grapheme cluster the drawing can
    * start one column later than asked for rather than half-way through a wide character.
    *
    * `alignment` places the line inside the `maxWidth` columns it was given. `Left` — the default, and what every
    * caller got before this parameter existed — starts drawing at `x`; `Center` and `Right` start further right by the
    * columns the line does not use. A line at least as wide as the budget starts at `x` whatever the alignment says,
    * because [[io.worxbend.tui.core.Alignment.originAt]] clamps the leftover at zero, so over-wide content still clips
    * from the right exactly as before.
    *
    * The return value is always measured from `x`, so it counts the blank columns a non-`Left` alignment left in front
    * of the line as well as the line itself. A caller laying widgets end to end (see [[Tabs]]) therefore keeps working
    * unchanged: it uses `Left`, where there is no leading gap to count.
    */
  def render(
      buffer: Buffer,
      x: Int,
      y: Int,
      line: Line,
      maxWidth: Int,
      baseStyle: Style = Style.Default,
      alignment: Alignment = Alignment.Left,
      skipWidth: Int = 0,
  ): Int = render(buffer, x, y, line, maxWidth, baseStyle, alignment, skipWidth, line.width)

  /** Renders `line` as [[render]] does, for a caller that has already measured it: `lineWidth` is the line's width in
    * terminal columns, so the line — and, through the skip path below, its spans — is not measured again. Every in-repo
    * caller measures with the same [[io.worxbend.tui.core.CharWidth]] the line itself uses, so the handed-in width and
    * the line's own agree; a width that disagrees is a defect of the caller, not something this renderer re-checks.
    */
  def render(
      buffer: Buffer,
      x: Int,
      y: Int,
      line: Line,
      maxWidth: Int,
      baseStyle: Style,
      alignment: Alignment,
      skipWidth: Int,
      lineWidth: Int,
  ): Int =
    // what is left of the line once the skipped columns are gone is what has to be placed, not the whole line
    val drawnWidth = math.max(0, lineWidth - math.max(0, skipWidth))
    val start      = alignment.originAt(x, maxWidth, drawnWidth)
    val cursor     = RowCursor(buffer, y, start, x + maxWidth)
    val lineStyle  = baseStyle.patch(line.style)
    var remaining  = math.max(0, skipWidth)
    var truncated  = false
    val spans      = line.spans.iterator
    while spans.hasNext && cursor.remaining > 0 && !truncated do
      val span                  = spans.next()
      val text                  =
        if remaining <= 0 then span.content
        else
          val spanWidth = CharWidth.of(span.content)
          if spanWidth <= remaining then
            remaining -= spanWidth
            ""
          else
            val tail = CharWidth.dropByWidth(span.content, remaining)
            remaining = 0
            tail
      // Retain the first half-fitting cluster as evidence of truncation, but never scan the invisible tail.
      val budget                = math.min(cursor.remaining, math.max(0, buffer.area.right - cursor.at))
      val (prefix, prefixWidth) = boundedPrefix(CharWidth.graphemeClusters(text), budget)
      val before                = cursor.at
      cursor.write(prefix, lineStyle.patch(span.style))
      // A half-fitting wide cluster leaves one column unused; it still ends the entire line, not just this span.
      truncated = budget <= 0 || cursor.at - before < prefixWidth
    cursor.at - x

  /** Takes only the clusters needed for a write, including the first cluster that would half-fit. Its measured width
    * lets the caller distinguish that stop from an exhausted span without measuring the rest of the source. The
    * iterator is single-use and owned by this call; zero-width clusters are consumed but spend no column budget.
    */
  private[widgets] def boundedPrefix(clusters: Iterator[String], maxWidth: Int): (String, Int) =
    val prefix = StringBuilder()
    var used   = 0
    while used < maxWidth && clusters.hasNext do
      val cluster = clusters.next()
      prefix.append(cluster)
      used += CharWidth.of(cluster)
    (prefix.result(), used)
