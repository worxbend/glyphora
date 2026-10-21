package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Buffer, Cell, Line, Position, Rect, StatefulWidget, Style, Widget}

import java.util.regex.Pattern

/** Renders buffers to plain strings for test assertions — the lightweight equivalent of TamboUI's buffer-assertion
  * module.
  */
object BufferAssertions:

  /** Whitespace at the end of a row, compiled once instead of per row of every frame. */
  private val TrailingSpaces: Pattern = Pattern.compile("\\s+$")

  /** Each row of the buffer as a string. Continuation cells of wide graphemes are skipped, so a row's string content
    * matches what a terminal shows, and trailing blanks are kept (every row spans the full width).
    */
  def lines(buffer: Buffer): Seq[String] =
    for y <- buffer.area.y until buffer.area.bottom
    yield rowText(buffer, y)

  /** Like [[lines]] but with trailing whitespace stripped — the usual shape for readable expected values. This is the
    * row-level half of the normalisation golden-frame comparison relies on; `GoldenFrames.normalise` does the
    * frame-level half by dropping trailing blank rows.
    */
  def trimmedLines(buffer: Buffer): Seq[String] =
    lines(buffer).map(TrailingSpaces.matcher(_).replaceFirst(""))

  /** The whole buffer as one newline-joined string (trailing blanks stripped per row). */
  def text(buffer: Buffer): String =
    trimmedLines(buffer).mkString("\n")

  /** One row of `buffer` as a string, with trailing blanks kept — [[lines]] for a single row.
    *
    * For the common assertion that names one row of a frame, so a test does not index into [[lines]] and does not
    * re-derive the wide-grapheme stepping rule for itself.
    */
  def line(buffer: Buffer, y: Int): String = rowText(buffer, y)

  /** Renders `widget` into a fresh `width` x `height` buffer and returns the buffer for assertions. */
  def rendered(widget: Widget, width: Int, height: Int): Buffer =
    renderInto(width, height)(buffer => widget.render(buffer.area, buffer))

  /** Renders `widget` against caller-owned `state` into a fresh `width` x `height` buffer.
    *
    * The stateful counterpart of [[rendered]]. Interactive widgets in this toolkit are `StatefulWidget[S]` by design —
    * the renderer adjusts the caller's scroll offset to keep the selection visible — so a harness that only accepted
    * plain `Widget`s made every interactive widget's suite write its own three-line renderer, which is exactly the
    * duplication the harness exists to absorb.
    *
    * `state` is mutated by the render, as it would be in an app, and stays the caller's to inspect afterwards.
    */
  def rendered[S](widget: StatefulWidget[S], state: S, width: Int, height: Int): Buffer =
    renderInto(width, height)(buffer => widget.render(buffer.area, buffer, state))

  /** Renders `widget` into `area` of a fresh `width` x `height` buffer.
    *
    * For the clipping tests: a widget given a sub-rect must stay inside it, and the only way to see that it did is to
    * leave room around the rect and assert the surrounding cells are untouched. `area` may sit anywhere in the buffer,
    * including partly outside it — the buffer's own bounds checking is what is under test in that case.
    */
  def renderedInto(widget: Widget, area: Rect, width: Int, height: Int): Buffer =
    renderInto(width, height)(widget.render(area, _))

  /** A fresh `width` x `height` buffer at the origin — the canvas every `rendered*` overload starts from. */
  private def freshBuffer(width: Int, height: Int): Buffer = Buffer(Rect(0, 0, width, height))

  /** Runs `draw` against a [[freshBuffer]] of `width` x `height` and returns the buffer for assertions. */
  private def renderInto(width: Int, height: Int)(draw: Buffer => Unit): Buffer =
    val buffer = freshBuffer(width, height)
    draw(buffer)
    buffer

  /** An expected frame written out as plain rows: `rows.length` high, and as wide as the widest row's *display* width.
    *
    * Display width, not `String.length`: a row of three ideographs is six columns wide, and sizing the buffer by
    * character count would leave the last one half off the edge, where
    * [[io.worxbend.tui.core.Buffer.setString Buffer.setString]] drops it entirely rather than draw a two-column glyph
    * across a boundary. Every cell carries [[io.worxbend.tui.core.Style.Default]] — see the
    * [[io.worxbend.tui.core.Line Line]] overload for an expected frame that carries styling.
    */
  def buffered(rows: String*): Buffer =
    buffered(rows.map(Line.raw), Style.Default)

  /** An expected frame built from styled [[io.worxbend.tui.core.Line Line]]s: each row is written with
    * [[io.worxbend.tui.core.Buffer.setLine Buffer.setLine]], so the style cascade and the column advance are exactly
    * the ones a widget drawing that `Line` would get.
    *
    * This is the shape [[assertEquals]] is for — an expected frame that carries colour and modifiers, which a golden
    * fixture deliberately does not. `base` is the style the whole frame starts from (a theme's background, say); the
    * line's own style goes over it and each span's over that.
    */
  def buffered(rows: Seq[Line], base: Style): Buffer =
    val width  = rows.map(_.width).maxOption.getOrElse(0)
    val buffer = Buffer(Rect(0, 0, width, rows.size))
    rows.zipWithIndex.foreach((line, y) => buffer.setLine(0, y, line, width, base))
    buffer

  /** Fails unless `actual` renders exactly like `expected`: the same area first, then the same symbol *and* the same
    * [[io.worxbend.tui.core.Style]] in every cell.
    *
    * This is the style-aware counterpart of [[text]]. `text` answers "does the frame read the way it should"; this
    * answers "is the frame the way it should be", which is the question a colour, a bold run or a reversed selection
    * row regresses on while every glyph stays exactly where it was. Before this existed, a test that cared about style
    * had to name each interesting cell by hand and compare `buffer.get(x, y).style` one cell at a time — which catches
    * only the cells somebody thought to name.
    *
    * The continuation cells of wide graphemes (the second column an ideograph or an emoji occupies) are compared like
    * any other cell. Two frames showing the same graphemes in different columns are therefore *not* equal here, even
    * though [[trimmedLines]] reads them identically; a test that wants the looser, glyph-only reading should compare
    * [[text]] instead.
    *
    * The failure is an `AssertionError`, the way [[GoldenFrames]] reports a mismatched fixture. It names the differing
    * positions with both cells (at most [[MaxReportedDifferences]] of them) and then prints both frames as text, so a
    * reader who cannot picture a coordinate can still see what moved.
    */
  def assertEquals(actual: Buffer, expected: Buffer): Unit =
    assertEquals(actual, expected, "")

  /** [[assertEquals]] with `label` written in front of the failure message, for a test that compares several frames in
    * a row and would otherwise have to work out which of them failed.
    */
  def assertEquals(actual: Buffer, expected: Buffer, label: String): Unit =
    val prefix      = if label.isEmpty then "" else s"$label: "
    if actual.area != expected.area then
      CallSite.fail(s"${prefix}buffer area ${actual.area} does not match expected ${expected.area}")
    val differences = cellDifferences(actual, expected)
    if differences.nonEmpty then CallSite.fail(differenceReport(prefix, actual, expected, differences))

  /** Every position where `actual` and `expected` hold a different [[io.worxbend.tui.core.Cell Cell]], in row-major
    * order, each paired with the two cells as `(position, actualCell, expectedCell)`.
    *
    * Public rather than private so a suite can assert on the *shape* of a difference — "only the selected row changed
    * style" — without reading a failure message back apart.
    *
    * @throws IllegalArgumentException
    *   if the two buffers cover different areas. "Which cells differ" has no answer across two different shapes, and
    *   quietly comparing the overlap would let a resize regression pass.
    */
  def cellDifferences(actual: Buffer, expected: Buffer): Seq[(Position, Cell, Cell)] =
    require(
      actual.area == expected.area,
      s"cellDifferences needs two buffers of the same area, got ${actual.area} and ${expected.area}",
    )
    val differences = Seq.newBuilder[(Position, Cell, Cell)]
    var y           = expected.area.y
    while y < expected.area.bottom do
      var x = expected.area.x
      // One column at a time, deliberately. Unlike `rowText` this must *not* step over the continuation cell of a wide
      // grapheme, because a grapheme sitting one column off is exactly the defect this assertion exists to catch.
      // `Buffer.diff` is not reused for the same reason: it suppresses continuation cells, collapses a mismatched area
      // into "every cell changed", and reports only the new cell rather than both sides of the pair.
      while x < expected.area.right do
        val actualCell   = actual.get(x, y)
        val expectedCell = expected.get(x, y)
        if actualCell != expectedCell then differences += ((Position(x, y), actualCell, expectedCell))
        x += 1
      y += 1
    differences.result()

  /** How many differing cells a failure message lists before summarising the rest. One wrong cell in an 80x24 frame is
    * worth reading; 1 920 of them are not, and the two frames printed underneath say more at that point.
    */
  private val MaxReportedDifferences: Int = 20

  /** Builds the message [[assertEquals]] fails with: a count, the first differing cells, then both frames as text. */
  private def differenceReport(
      prefix: String,
      actual: Buffer,
      expected: Buffer,
      differences: Seq[(Position, Cell, Cell)],
  ): String =
    val message = StringBuilder()
    message ++= s"$prefix${differences.size} of ${expected.area.area} cells differ"
    differences.take(MaxReportedDifferences).zipWithIndex.foreach {
      case ((position, actualCell, expectedCell), index) =>
        message ++= s"\n  ${index + 1}. (${position.x},${position.y}) actual $actualCell expected $expectedCell"
    }
    if differences.size > MaxReportedDifferences then
      message ++= s"\n  … and ${differences.size - MaxReportedDifferences} more"
    message ++= s"\nactual:\n${text(actual)}\nexpected:\n${text(expected)}"
    message.result()

  /** One row of the buffer as a string, stepping over the continuation cells of wide graphemes.
    *
    * Continuation columns are skipped by asking the buffer which columns it reserved for a wide grapheme, not by
    * re-measuring the glyph just read — `Buffer.continuations` records why measuring misclassifies content, and
    * `FrameEncoder.advanceOf` steps the real terminal by the same rule. Stepping one column at a time also removes any
    * need for a zero-width guard.
    */
  private def rowText(buffer: Buffer, y: Int): String =
    val row = StringBuilder()
    var x   = buffer.area.x
    while x < buffer.area.right do
      if !buffer.isContinuation(x, y) then row ++= buffer.get(x, y).symbol
      x += 1
    row.result()
