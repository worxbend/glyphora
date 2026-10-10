package io.worxbend.tui.core

/** A mutable grid of [[Cell]]s covering `area`, the in-memory render target for one frame.
  *
  * `x`/`y` arguments are absolute terminal coordinates (the same space as `area`'s offset), not area-relative — widgets
  * receive a `Rect` positioned in absolute space and write to the buffer at those coordinates. Writes outside `area`
  * are silently clipped, never errors; reads outside `area` return [[Cell.Empty]].
  *
  * Mutability is an implementation detail of the render loop — it never escapes through `Widget.render`'s contract.
  *
  * '''Not thread-safe.''' The grid is a plain array with no synchronization, so a `Buffer` is owned by whichever thread
  * renders into it — in a running application that is the runner's render thread, the same thread `Signal` writes are
  * pinned to. Staging content on a background thread is safe only if the buffer is fully written before the reference
  * is published to the render thread (`RenderThread.capture` in `tui-runtime` is how background work hands its
  * continuation back) and is never touched again afterwards. Concurrent writes, or reads racing a write, produce a torn
  * frame or a dropped diff rather than an error.
  */
final class Buffer(val area: Rect):

  // Validated before the first allocation below, because every plane of this buffer is indexed by `area.area` and that
  // product is 32-bit: a rectangle of more than `Int.MaxValue` cells wraps around to a small — possibly zero — length,
  // and the mismatch only shows up later as an ArrayIndexOutOfBoundsException from inside an unrelated write, while
  // `area.contains` keeps answering true for the very coordinate that threw. Failing here instead names the offending
  // rectangle at the moment somebody asked for it. It is an IllegalArgumentException rather than an `Either` because
  // no terminal can produce such a rectangle: reaching this is a bug in the calling code, not a condition to recover
  // from, and a constructor has nowhere to return a failure value anyway.
  require(
    area.cellCount <= Buffer.MaxCells,
    s"Buffer area $area covers ${area.cellCount} cells, more than the ${Buffer.MaxCells} a buffer can address",
  )

  // `private[core]` rather than `private` on the three planes: the frame-diff engine ([[FrameDiff]]) walks them by
  // index from outside the class, which is the same trade the diff loop already made for `next`'s arrays
  private[core] val cells: Array[Cell] = Array.fill(area.area)(Cell.Empty)

  /** Which cells are the second column of a two-column grapheme, recorded by [[set]] rather than measured.
    *
    * Two invariants the rest of this class relies on: a flagged cell always holds [[Cell.Empty]], so releasing a flag
    * can never destroy content; and the cell to the left of a flagged cell is always two columns wide.
    *
    * The relationship used to be inferred at diff time by measuring the left neighbour's width, which misclassified
    * real content as a continuation as soon as a second, independent write landed on that column — and [[diff]] then
    * silently refused to flush it.
    */
  private[core] val continuations: Array[Boolean] = Array.fill(area.area)(false)

  /** The per-position [[DiffDirective]] of each cell, held as the enum's ordinal.
    *
    * The second plane of per-position render metadata, alongside [[continuations]], and here for the same reason: the
    * fact is about a *position* in a frame rather than about a glyph value, so it cannot live on [[Cell]] — the same
    * `Cell` is skipped in one column and flushed in the next. Keeping it off `Cell` also keeps the library's hottest
    * value two fields wide.
    *
    * Ordinals in an `Array[Byte]` rather than an `Array[DiffDirective]`: a reference array would cost eight bytes per
    * cell — 80 kB on a 200x50 frame — to store one of three constants. The encoding never escapes `tui-core`;
    * [[diffDirective]] hands out the enum value.
    */
  private[core] val directives: Array[Byte] = new Array[Byte](area.area)

  /** The cell at `(x, y)`, or [[Cell.Empty]] when the coordinates fall outside `area`. */
  def get(x: Int, y: Int): Cell = cellAt(x, y)

  /** The cell at `position`, or [[Cell.Empty]] when it falls outside `area`.
    *
    * The same read as `get(x, y)`, spelled for callers that already hold a [[Position]] — a mouse event's coordinate, a
    * [[Rect]]'s `position`, an entry from [[diff]] — so they need not unpack it into two arguments.
    */
  def get(position: Position): Cell = cellAt(position.x, position.y)

  /** Writes `cell` at `position`, with exactly the semantics of `set(x, y, cell)` including the clipping and the
    * wide-grapheme bookkeeping described there.
    */
  def set(position: Position, cell: Cell): Unit = set(position.x, position.y, cell)

  /** Writes `cell` at `(x, y)`; writes outside `area` are silently clipped.
    *
    * A write can also mutate the neighbouring cell, because a two-column grapheme owns two cells and a terminal cannot
    * draw half of one: writing a wide cell reserves the cell to its right as its continuation; overwriting a wide cell
    * releases the continuation it held; and writing over a continuation blanks the wide grapheme that owned it, since
    * that grapheme can no longer draw across a column somebody else has claimed.
    *
    * That last rule holds for every cell written, [[Cell.Empty]] included. A blank used to be exempt, on the theory
    * that a caller writing one onto a reserved column was re-spelling the continuation the buffer had just created for
    * it. Nothing in the toolkit spells a continuation that way — [[blit]] is the only caller that ever copies one, and
    * it skips those columns because the wide cell it copied a moment earlier already reserved them — while the
    * exemption made the commonest blank of all unable to erase. `fill(region, Cell.Empty)` and a default-styled overlay
    * composited over CJK text both left the character on screen, one column inside the rectangle they had declared
    * blank.
    *
    * A wide cell aimed at the last column of a row is stored as a blank in `cell`'s style rather than as itself: there
    * is no column left to reserve, and a terminal handed a two-column glyph in a one-column slot wraps the row. The
    * style is kept so a background fill stays continuous to the edge.
    */
  def set(x: Int, y: Int, cell: Cell): Unit =
    setMeasured(x, y, cell, CharWidth.ofCluster(cell.symbol))

  /** [[set]] for a caller that already knows `cell`'s display width, so the cluster is measured once per written cell
    * rather than once by the caller and again here. `width` must be `CharWidth.ofCluster(cell.symbol)`.
    */
  private def setMeasured(x: Int, y: Int, cell: Cell, width: Int): Unit =
    if area.contains(x, y) then
      val index = indexOf(x, y)
      // the column was the right half of a wide grapheme, which can no longer draw across a column this write claims
      if continuations(index) then cells(index - 1) = Cell.Empty
      write(index, x, cell, width)

  /** [[set]]'s tail, once the addressed cell is known not to be a wide grapheme's continuation: stores `cell` and
    * re-derives the continuation to its right. `x` is passed only to test the row's right edge — `index + 1` would
    * otherwise wrap onto the next row.
    */
  private def write(index: Int, x: Int, cell: Cell, width: Int): Unit =
    val hasRight = x + 1 < area.right
    val isWide   = width == 2
    // the cell being replaced may itself be a wide grapheme, in which case it owns the column to its right
    if hasRight && continuations(index + 1) then releaseContinuation(index + 1)
    // a wide grapheme with no column left to claim would render across a column this buffer does not own
    cells(index) = if isWide && !hasRight then Cell(" ", cell.style) else cell
    continuations(index) = false
    if isWide && hasRight then
      // the column being claimed may itself be a wide grapheme's left half, in which case it owns the column beyond
      // it; that continuation is orphaned the moment we blank its owner, so release it in the same breath
      if x + 2 < area.right && continuations(index + 2) then releaseContinuation(index + 2)
      claimContinuation(index + 1)

  /** Gives up the continuation flag at `index`, blanking the cell with it.
    *
    * A flagged cell only ever holds [[Cell.Empty]], so this can never destroy content. Leaving a stale flag set would
    * make [[diff]] suppress a column the frame has already blanked.
    */
  private def releaseContinuation(index: Int): Unit =
    cells(index) = Cell.Empty
    continuations(index) = false

  /** Reserves `index` as the second column of the two-column grapheme to its left.
    *
    * The cell is blanked as well as flagged, which is the invariant [[continuations]] documents: a terminal draws the
    * wide grapheme across both columns, so the second one must carry no symbol of its own.
    */
  private def claimContinuation(index: Int): Unit =
    cells(index) = Cell.Empty
    continuations(index) = true

  /** Writes `text` starting at `(x, y)`, one grapheme cluster per cell, clipping at the area's right edge.
    *
    * A wide (two-column) cluster occupies its cell plus a continuation cell to the right, which [[set]] reserves; a
    * wide cluster that would only half-fit at the right edge is dropped entirely. Grapheme clusters that begin with a
    * combining mark (no base character before them in `text`) are skipped.
    *
    * A row outside `area` returns at once. That is the same clipping [[set]] already applies cell by cell, moved ahead
    * of the work: without it a line drawn above or below the buffer still split itself into grapheme clusters and built
    * one [[Cell]] per character, only for every one of them to be rejected. Scrolled content, where most rows are
    * off-window by design, is what makes that the common case rather than a corner one.
    */
  def setString(x: Int, y: Int, text: String, style: Style): Unit =
    writeString(x, y, text, style, area.right)

  /** Writes `text` at `(x, y)` stopping after at most `maxWidth` columns, and answers how many columns it wrote.
    *
    * Two things this adds over the four-argument [[setString]]. The bound: the write stops at whichever comes first,
    * the area's right edge or `x + maxWidth`, so a caller with a column budget no longer has to cut the string to size
    * beforehand. And the answer: the returned column count is exactly how far the cursor advanced, so a caller laying a
    * row out as a run of segments adds it to `x` instead of measuring the text a second time.
    *
    * The count can be smaller than `maxWidth` even with text left over. A two-column cluster that would only half-fit
    * inside the bound is dropped whole rather than split — a terminal handed half a wide glyph draws it across the
    * column beyond the budget — so a budget of 3 filled with `漢字` writes 2 columns and reports 2. A negative `maxWidth`
    * is treated as zero: nothing is written and the answer is 0. An arbitrarily large one is treated as "no limit of my
    * own, stop at the area's edge": the bound `x + maxWidth` is added in `Long` and then clamped, because in `Int` that
    * sum overflows for a budget near `Int.MaxValue` and the wrapped-negative bound stopped the write before it began.
    * `Int.MaxValue` is the natural spelling for an uncapped budget — `Constraint.Max` already uses it for one — so it
    * is the value that must not misbehave.
    */
  def setString(x: Int, y: Int, text: String, style: Style, maxWidth: Int): Int =
    val bound = x.toLong + math.max(0, maxWidth)
    writeString(x, y, text, style, math.min(area.right.toLong, bound).toInt)

  /** Writes `span`'s content at `(x, y)` in at most `maxWidth` columns, and answers how many columns it wrote.
    *
    * The span's own [[Style]] is layered over `baseStyle` — the argument supplies whatever the span says nothing about,
    * which is how a theme colour reaches text that already chose to be bold. Clipping is [[setString]]'s: the write
    * stops at the area's right edge or at `x + maxWidth`, whichever comes first, and a two-column cluster that would
    * only half-fit inside that bound is dropped whole rather than split, so the answer can be smaller than the budget
    * with content left over.
    *
    * A [[Span]] is a `tui-core` value, and so is the buffer it is drawn into; before this existed the code that put one
    * into the other lived inside `tui-widgets`, out of reach of every other module. Nothing about it needs a widget.
    */
  def setSpan(x: Int, y: Int, span: Span, maxWidth: Int, baseStyle: Style = Style.Default): Int =
    setString(x, y, span.content, baseStyle.patch(span.style), maxWidth)

  /** Writes `line`'s spans left to right from `(x, y)`, sharing one budget of `maxWidth` columns between them, and
    * answers how many columns were written in total.
    *
    * Three style layers resolve here, in the order [[Line.styledGraphemes]] defines: `baseStyle` underneath, then the
    * line's own [[Line.style]], then each span's. That is the cascade a `Line` means everywhere else in the toolkit, so
    * a line that carries a style of its own paints with it here too rather than only when a widget happens to patch it
    * in by hand. The spans are written end to end with nothing inserted between them, and the budget is spent as it
    * goes, so a line wider than its budget stops part way through whichever span reaches the edge instead of dropping
    * that span and every one after it. Writing stops as soon as the budget is gone, so the tail of a very long line is
    * never even measured.
    *
    * Writing also stops at the first span the budget could not draw whole, even when columns are left over. A
    * two-column cluster is dropped rather than split, so a span can end one column short of its budget — and letting
    * the next span start in the column that cluster gave up would draw a character from later in the line as though it
    * came first. A budget of 1 filled with `漢` then `x` writes nothing and reports 0; it does not draw `x` at column 0.
    *
    * This places the line where it is told and nowhere else. Centring or right-aligning a row against a wider area is a
    * decision about layout rather than about writing cells, and belongs to the widget making it — see
    * `io.worxbend.tui.core.Alignment` for the arithmetic the widgets share.
    */
  def setLine(x: Int, y: Int, line: Line, maxWidth: Int, baseStyle: Style = Style.Default): Int =
    if line.spans.lengthIs == 1 then setSpan(x, y, line.spans.head, maxWidth, baseStyle.patch(line.style))
    else
      val limit     = math.min(area.right.toLong, x.toLong + math.max(0, maxWidth)).toInt
      var column    = x
      var truncated = false
      val clusters  = line.styledGraphemes(baseStyle)
      while y >= area.y && y < area.bottom && column < limit && clusters.hasNext && !truncated do
        val grapheme = clusters.next()
        val width    = CharWidth.ofCluster(grapheme.cluster)
        if width > 0 then
          if column.toLong + width <= limit then
            setMeasured(column, y, Cell(grapheme.cluster, grapheme.style), width)
            column += width
          else truncated = true
      column - x

  /** The shared body of both [[setString]] overloads: writes clusters left to right while the write head stays below
    * `limit` (an exclusive column bound already clipped to the area), and answers the columns written.
    */
  private def writeString(x: Int, y: Int, text: String, style: Style, limit: Int): Int =
    if y < area.y || y >= area.bottom || limit <= x then 0
    else if CharWidth.isPrintableAscii(text) then writeAsciiString(x, y, text, style, limit)
    else
      var column   = x
      // set by the branch below, so that "dropped a half-fitting cluster" is a distinct exit from "ran out of columns"
      var stopped  = false
      val clusters = CharWidth.graphemeClusters(text)
      while clusters.hasNext && !stopped && column < limit do
        val cluster = clusters.next()
        // `ofCluster`, not `of`: the iterator has already established this is exactly one cluster, and `of` would
        // build a second cluster iterator over it. The width is then handed to `setMeasured` rather than re-derived.
        val width   = CharWidth.ofCluster(cluster)
        // a zero-width cluster (a combining mark with no base character before it) claims no cell at all
        if width > 0 then
          if column + width <= limit then
            setMeasured(column, y, Cell(cluster, style), width)
            column += width
          else stopped = true // a wide cluster that only half-fits at the edge
      end while
      column - x

  /** Allocation-free [[writeString]] for printable ASCII: one column per char, symbols taken from a shared table. */
  private def writeAsciiString(x: Int, y: Int, text: String, style: Style, limit: Int): Int =
    var index  = 0
    var column = x
    while index < text.length && column < limit do
      // printable ASCII is one column by definition, so the width needs no measuring at all
      setMeasured(column, y, Cell(CharWidth.asciiSymbol(text.charAt(index)), style), 1)
      column += 1
      index += 1
    column - x

  /** Writes `cell` into every column of `region` that falls inside `area`; anything outside is clipped away, exactly as
    * [[set]] clips a single write.
    *
    * This is the one owner of "blank a box". An overlay — a dialog, a popup, a menu — has to paint its whole rectangle
    * in its own style so that whatever was drawn underneath cannot show through, and before this method every such
    * widget wrote its own pair of `while` loops to do it, which is how one ends up a column short on one edge.
    *
    * The cell's display width is measured once for the whole region instead of once per column, and a two-column symbol
    * advances two columns at a time so that the continuation cell [[set]] reserves is not immediately painted over. If
    * the region's width is not a whole multiple of that symbol's width, the leftover column at the right edge is stored
    * as a blank in `cell`'s style — that is [[set]]'s existing rule for a wide symbol with no room to its right, and it
    * keeps a background fill continuous to the edge.
    */
  def fill(region: Rect, cell: Cell): Unit =
    val clipped = region.intersection(area)
    // a zero-width symbol (a lone combining mark, say) claims no column, and stepping by zero would never terminate
    val step    = math.max(1, CharWidth.ofCluster(cell.symbol))
    var y       = clipped.y
    while y < clipped.bottom do
      var x = clipped.x
      while x < clipped.right do
        // A two-column grapheme starting in the last column of an odd-width region would paint its second half
        // *outside* the region: `setMeasured` only refuses to leave the buffer, and the column past the region is
        // usually still inside it. Blank that column in the fill's own style instead, the same way `blit` blanks a
        // grapheme the window edge cuts in half. The region keeps its background and the neighbour keeps its glyph.
        if x + step > clipped.right then setMeasured(x, y, Cell(" ", cell.style), 1)
        else setMeasured(x, y, cell, step)
        x += step
      y += 1

  /** Replaces the style of every cell of `region` that falls inside `area`, leaving the symbols exactly as they are.
    *
    * This is the "patch over the top" write. A widget draws its content first, and then a caller tints the rectangle
    * for a selection highlight, a disabled overlay or a focus ring without needing to know which symbols happen to be
    * there. Neither [[set]] nor [[setString]] can do that: both take the symbol as an argument, so restyling through
    * them means overwriting the content.
    *
    * Blank cells are restyled too. That is deliberate — it is what makes a background tint reach the whole rectangle
    * rather than only the columns that carry a glyph.
    *
    * Continuation cells (the second column of a two-column grapheme) are left alone: the terminal paints the wide
    * grapheme across both columns from the *left* cell's style, so restyling the right one changes nothing on screen
    * while breaking the invariant that a flagged cell holds [[Cell.Empty]] — the test [[set]] uses to tell a caller's
    * own continuation cell apart from real content landing on a reserved column.
    */
  def setStyle(region: Rect, style: Style): Unit =
    mapStyle(region)(_ => style)

  /** [[setStyle]] with the replacement derived from each cell's current style — a dim, a tint, a background swap that
    * keeps the foreground it finds.
    *
    * `transform` must be a pure function of the style it is handed, because it is not called once per cell: the pass
    * runs through a [[MemoizedStyleTransform]], so the transform re-runs only where the style actually changes. Without
    * that, a full-frame pass would build a fresh `Style` for each of ten thousand cells to arrive at the same answer
    * ten thousand times.
    */
  def mapStyle(region: Rect)(transform: Style => Style): Unit =
    val clipped = region.intersection(area)
    if !clipped.isEmpty then
      val memoized = MemoizedStyleTransform(transform)
      foreachIndex(clipped): (_, _, index) =>
        if !continuations(index) then
          val cell   = cells(index)
          val styled = memoized(cell.style)
          if styled != cell.style then cells(index) = cell.copy(style = styled)

  /** Copies `region` of `source` into this buffer with the region's top-left landing at `at`.
    *
    * Writes outside this buffer's area are clipped like any other write — this is how offscreen-rendered content
    * (scroll views, overlays) lands on the frame. A `region` reaching past `source`'s own area is trimmed to it, and
    * the landing point moves by however much the trim shifted the region's origin, so the surviving cells keep their
    * position relative to `at` instead of sliding onto the ones that were dropped.
    *
    * A cell's [[DiffDirective]] travels with it, so a fragment that reserved columns for an out-of-band painter keeps
    * that reservation once it is composited into the frame.
    *
    * Blitting a buffer onto itself works on a snapshot: without it, overlapping rows would read cells this call has
    * already overwritten and smear them across the region.
    *
    * Both buffers must belong to the calling thread for the duration of the call: `source` is read cell by cell with no
    * synchronization, so another thread writing into it mid-blit copies half of one frame and half of the next.
    */
  def blit(source: Buffer, at: Position, region: Rect): Unit =
    if source eq this then blit(source.snapshot, at, region)
    else
      val sourceWindow = region.intersection(source.area)
      val landingX     = at.x + (sourceWindow.x - region.x)
      val landingY     = at.y + (sourceWindow.y - region.y)
      val destination  = Rect(landingX, landingY, sourceWindow.width, sourceWindow.height).intersection(area)
      // Treat destination clipping as a source window too. Its first visible continuation has no copied owner and
      // must be blanked by the same seam rules as an explicitly cropped source region.
      val clipped      = Rect(
        sourceWindow.x + (destination.x - landingX),
        sourceWindow.y + (destination.y - landingY),
        destination.width,
        destination.height,
      )
      val originX      = destination.x
      val originY      = destination.y
      var dy           = 0
      while dy < clipped.height do
        val y  = clipped.y + dy
        var dx = 0
        while dx < clipped.width do
          if !ownedByPreviousColumn(source, clipped, dx, y) then
            set(originX + dx, originY + dy, cellToCopy(source, clipped, dx, y))
          // the directive travels with the cell: a sub-buffer that reserved columns for an image protocol must keep
          // that reservation once it is composited into the frame, or the frame flushes over the picture
          copyDirective(originX + dx, originY + dy, source, clipped.x + dx, y)
          dx += 1
        dy += 1

  /** Whether the source column is reserved for the wide grapheme in the column before it, which the previous iteration
    * of [[blit]] already copied — writing it reserved the destination column in the same breath. Such a column needs no
    * write of its own: writing the source's blank over the reservation would instead read as content landing on a
    * reserved column and blank the grapheme just copied. `dx == 0` is not such a column — the grapheme that owned it is
    * outside the region being copied, so the half is blanked by [[cellToCopy]] rather than skipped.
    */
  private def ownedByPreviousColumn(source: Buffer, clipped: Rect, dx: Int, y: Int): Boolean =
    dx > 0 && source.isContinuation(clipped.x + dx, y)

  /** The cell [[blit]] writes for one source position, with the two window-edge cases blanked.
    *
    * A wide grapheme cut in half by the *window* edge would render torn — the half is blanked instead. This is a
    * different edge from the destination's own: [[set]] blanks a wide cell that does not fit the destination row, but a
    * window narrower than the destination would otherwise let the glyph spill one column past the region the caller
    * asked to copy.
    */
  private def cellToCopy(source: Buffer, clipped: Rect, dx: Int, y: Int): Cell =
    val cell = source.get(clipped.x + dx, y)
    if cutByWindowStart(source, clipped, dx, y) || cutByWindowEnd(clipped, dx, cell) then Cell.Empty
    else cell

  /** Whether the window's first column lands on the second half of a wide grapheme whose left half lies outside the
    * region being copied.
    */
  private def cutByWindowStart(source: Buffer, clipped: Rect, dx: Int, y: Int): Boolean =
    dx == 0 && source.isContinuation(clipped.x, y)

  /** Whether the window's last column holds a wide grapheme whose second half the window cuts off. */
  private def cutByWindowEnd(clipped: Rect, dx: Int, cell: Cell): Boolean =
    dx == clipped.width - 1 && CharWidth.ofCluster(cell.symbol) == 2

  /** Carries one position's [[DiffDirective]] from `source` to this buffer, allocating nothing.
    *
    * [[blit]]'s per-cell helper. Going through [[setDiffDirective]] would build a one-cell `Rect` for every column of
    * every composited fragment, which is the hottest loop the composition path has.
    */
  private def copyDirective(x: Int, y: Int, source: Buffer, sourceX: Int, sourceY: Int): Unit =
    if area.contains(x, y) && source.area.contains(sourceX, sourceY) then
      directives(indexOf(x, y)) = source.directives(source.indexOf(sourceX, sourceY))

  /** Copies all of `source` into this buffer at `at`. */
  def blit(source: Buffer, at: Position): Unit =
    blit(source, at, source.area)

  /** A new buffer covering the union of this buffer's area and `other`'s, holding this buffer's content with `other`'s
    * composited on top.
    *
    * The difference from [[blit]] is who decides the size. `blit` writes into a destination whose bounds the caller
    * already fixed and throws away whatever falls outside them; this grows the result to the smallest rectangle
    * covering both inputs, so nothing is lost. That is the operation for composing offscreen fragments whose extent is
    * only known once they have been produced — a tooltip measured from its own text, say, laid over a panel.
    *
    * Neither input is modified, and neither is resized: a `Buffer`'s `area` is fixed for its lifetime because
    * [[snapshot]], [[diff]] and a backend's last-flushed frame all assume the grid they hold cannot change size
    * underneath them. The result is a third buffer, owned by the caller.
    *
    * Coordinates are absolute (see the class documentation), so each input lands at the position it already claims.
    * Cells of the union that neither input covers stay [[Cell.Empty]]. Where the two overlap, `other` wins, and it wins
    * as a whole grapheme: the copy goes through [[blit]], so a two-column cluster cut by a seam is blanked rather than
    * drawn torn.
    *
    * Both buffers must belong to the calling thread for the duration of the call, for the same reason [[blit]] does.
    */
  def merged(other: Buffer): Buffer =
    val combined = Buffer(area.union(other.area))
    combined.blit(this, area.position)
    combined.blit(other, other.area.position)
    combined

  /** Applies `visit(x, y, cell)` to every cell of `region` that this buffer covers, in row-major order.
    *
    * The traversal every reader of a finished frame — a golden-frame writer, a test assertion, an alternative encoder —
    * would otherwise write for itself as a nested loop over [[get]]. Two reasons to have it here rather than there: the
    * bounds check happens once, on `region`, instead of once per cell; and nothing is allocated per cell, no `Position`
    * and no tuple, which matters because a 200x50 frame is 10 000 cells.
    *
    * `visit` sees the raw grid, continuation cells included — they arrive as the [[Cell.Empty]] they hold. A caller
    * rebuilding what a terminal displays must skip them with [[isContinuation]] rather than print their blank, or a row
    * containing a two-column grapheme comes out one column too wide.
    *
    * Writing into this buffer from inside `visit` is not supported: a write can move a neighbouring cell (see [[set]])
    * and the traversal has already decided which positions it will read.
    */
  def foreachIn(region: Rect)(visit: (Int, Int, Cell) => Unit): Unit =
    foreachIndex(region)((x, y, index) => visit(x, y, cells(index)))

  /** The index-based counterpart of [[foreachIn]]: the same clipped row-major walk, under the same contract — the
    * bounds check happens once on `region` rather than once per cell, and nothing is allocated per position.
    *
    * What it yields is the difference. `foreachIn` hands out the [[Cell]], which is all a reader of a finished frame
    * needs; the walks over this grid mostly want the backing array index instead, because they read or write
    * [[continuations]] and [[directives]] at that position too and would otherwise recompute [[indexOf]] per plane.
    *
    * `inline`, with an `inline` function parameter, so each use is expanded into its caller as the plain nested `while`
    * pair it was written as: no closure is allocated and no call is made per cell.
    */
  private[core] inline def foreachIndex(region: Rect)(inline visit: (Int, Int, Int) => Unit): Unit =
    val clipped = region.intersection(area)
    var y       = clipped.y
    while y < clipped.bottom do
      var index = (y - area.y) * area.width + (clipped.x - area.x)
      var x     = clipped.x
      while x < clipped.right do
        visit(x, y, index)
        index += 1
        x += 1
      y += 1

  /** [[foreachIn]] over the whole buffer. */
  def foreach(visit: (Int, Int, Cell) => Unit): Unit =
    foreachIn(area)(visit)

  /** Whether `(x, y)` is the second column of a two-column grapheme — the half a terminal paints when it draws the
    * cluster in the cell to the left, which therefore must not be drawn a second time.
    *
    * The state is *recorded* by [[set]], never re-measured here; see [[continuations]] for why measuring it was a bug.
    * Coordinates outside `area` are never continuations.
    */
  def isContinuation(x: Int, y: Int): Boolean =
    area.contains(x, y) && continuations(indexOf(x, y))

  /** How [[diff]] must treat `(x, y)`. Coordinates outside `area` are always [[DiffDirective.Default]]. */
  def diffDirective(x: Int, y: Int): DiffDirective =
    if area.contains(x, y) then DiffDirective.fromOrdinal(directives(indexOf(x, y))) else DiffDirective.Default

  /** Declares how [[diff]] must treat every position of `region` that falls inside `area`; the rest is clipped away,
    * exactly as [[set]] clips a single write. A single cell is `Rect(x, y, 1, 1)`.
    *
    * This changes what gets *flushed*, never what the grid holds: the cells keep whatever [[set]] put in them. See
    * [[DiffDirective]] for what each value means and why the two non-default ones are mutually exclusive.
    *
    * Directives do not survive [[reset]], so a widget that owns a region re-declares it on every render. That is the
    * behaviour a frame loop wants: the frame that stops drawing an image is the frame whose cells must be flushed
    * again, and a directive that outlived the widget that asked for it would freeze the region for the rest of the run.
    * They *are* carried by [[snapshot]] and [[copyFrom]], because those produce a record of the frame that was flushed,
    * and the diff of the next frame against that record is exactly where the directives are read.
    */
  def setDiffDirective(region: Rect, directive: DiffDirective): Unit =
    val code = directive.ordinal.toByte
    foreachIndex(region)((_, _, index) => directives(index) = code)

  /** An independent copy of this buffer. Backends snapshot the frame they just flushed so later mutation of the
    * caller's buffer cannot corrupt the next diff.
    *
    * The copy is unsynchronized, so it must be taken on the thread that owns this buffer; the result is a fresh buffer
    * owned by the caller, which is what makes it safe to hand to another thread once no further writes follow.
    */
  def snapshot: Buffer =
    val copied = Buffer(area)
    Array.copy(cells, 0, copied.cells, 0, cells.length)
    Array.copy(continuations, 0, copied.continuations, 0, continuations.length)
    Array.copy(directives, 0, copied.directives, 0, directives.length)
    copied

  /** Overwrites this buffer's contents with `source`'s, allocating nothing.
    *
    * The in-place counterpart of [[snapshot]]. A backend that keeps a baseline of the frame it last flushed needs a
    * private copy of it, and taking that copy with `snapshot` allocates a fresh `Buffer` plus two grid-sized arrays on
    * every frame — 10 000 cells on a 200x50 screen, at the tick rate. Recycling one buffer with this method gives the
    * same private copy and allocates nothing at all.
    *
    * Both buffers must cover the same `area` and be owned by the calling thread, for the same reason [[snapshot]] must
    * be taken on the owning thread: the copy is unsynchronized, so another thread writing into `source` part-way
    * through leaves this buffer holding half of one frame and half of the next. Copying a buffer onto itself is a no-op
    * rather than an error.
    *
    * @throws IllegalArgumentException
    *   if `source.area != area`. That is a programmer error, not a condition to recover from: the grids have different
    *   shapes, so there is no correct thing to copy. A caller that can be handed a differently sized buffer — anything
    *   that survives a terminal resize — must allocate a new buffer for the new area instead.
    */
  def copyFrom(source: Buffer): Unit =
    require(source.area == area, s"cannot copy a buffer covering ${source.area} into one covering $area")
    if source ne this then
      Array.copy(source.cells, 0, cells, 0, cells.length)
      Array.copy(source.continuations, 0, continuations, 0, continuations.length)
      Array.copy(source.directives, 0, directives, 0, directives.length)

  /** Resets every cell to [[Cell.Empty]], recycling the buffer for the next frame. */
  def reset(): Unit =
    var index = 0
    while index < cells.length do
      cells(index) = Cell.Empty
      continuations(index) = false
      directives(index) = Buffer.DefaultCode
      index += 1

  /** The cells that changed going from this buffer (the previous frame) to `next` (the frame to display).
    *
    * This is what a terminal backend flushes each frame instead of redrawing everything. Positions covered by the
    * continuation cell of a wide grapheme in `next` are never emitted — flushing the wide cell itself repaints both
    * columns. The two buffers must start at the same origin and be the same width; see [[emitAll]] for the frame after
    * a resize, where the previous frame is unusable and there is nothing to compare against.
    *
    * One column is emitted even when its content did not change: a column that was the right half of a wide grapheme in
    * this (previous) frame, is not one in `next`, and whose grapheme painted a style that shows through a blank — see
    * [[visibleOnBlank]]. Both frames hold [[Cell.Empty]] there, so a plain content compare calls it unchanged, yet the
    * terminal is still painting the old glyph's background across that half-cell.
    *
    * `next`'s [[DiffDirective]]s override the content comparison, and are read from `next` alone — the request belongs
    * to the frame being drawn, not to the one being replaced. A [[DiffDirective.Skip]] position is never emitted; a
    * [[DiffDirective.AlwaysUpdate]] one always is; and a position `next` no longer skips is emitted even if its content
    * matches, because while it was skipped this buffer's memory of it was never flushed to the terminal at all.
    */
  def diff(next: Buffer): Iterator[(Position, Cell)] =
    val changes = Iterator.newBuilder[(Position, Cell)]
    diff(next, (x, y, cell) => changes += ((Position(x, y), cell)))
    changes.result()

  /** [[diff]] without the intermediate objects: calls `emit(x, y, cell)` for each changed cell, in row-major order.
    *
    * This is what backends use on the hot path — it allocates nothing per cell (no `Position`, no tuple, no iterator
    * state), which matters because a 200x50 frame is 10 000 cells and runs at the tick rate. The walk itself is
    * [[FrameDiff]]'s.
    */
  def diff(next: Buffer, emit: (Int, Int, Cell) => Unit): Unit =
    diff(next, emit, TrailingCellPolicy.Keep)

  /** [[diff]] with a workaround for terminals that draw an emoji presentation sequence in one column.
    *
    * A cluster containing U+FE0F — the variation selector that asks for a character's colourful emoji form — is two
    * columns wide by the Unicode rules this toolkit measures with, so the buffer reserves the column to its right as a
    * continuation and never emits that column: painting the glyph paints both halves. Several terminals draw such a
    * sequence in a single column anyway, and then the second column is never repainted at all, so whatever stood there
    * in an earlier frame stays on screen next to the emoji.
    *
    * With [[TrailingCellPolicy.Clear]], a changed cell holding such a cluster is followed by a blank emitted into its
    * reserved column, carrying the owning cell's style so a background fill stays continuous across the pair.
    *
    * [[TrailingCellPolicy.Keep]] is the default, and picking between the two is a backend's decision rather than the
    * buffer's, because the opposite artifact is just as real: on a terminal that does draw both columns, that blank
    * lands on the right half of the glyph and clips it. Only the code that knows which terminal it is talking to can
    * pick the lesser of the two.
    *
    * The extra emission costs one enum test per changed cell — the reserved-column flag is an array read and is checked
    * before the cluster is scanned for the selector — so a frame with no emoji in it pays nothing measurable.
    */
  def diff(next: Buffer, emit: (Int, Int, Cell) => Unit, trailingCellPolicy: TrailingCellPolicy): Unit =
    require(
      area.x == next.area.x && area.y == next.area.y && area.width == next.area.width,
      s"diff expects two buffers with the same origin and width, got $area and ${next.area}; " +
        "call emitAll on the new frame when the previous one is unusable (a resize, a resume from suspend)",
    )
    new FrameDiff(this, next, emit, trailingCellPolicy).run()

  /** Emits every cell of this buffer, in the same row-major order and with the same continuation rule as [[diff]] — the
    * full repaint the first frame after a resize or a resume from suspend needs.
    *
    * This is the honest way to spell "there is no usable previous frame". [[diff]] used to answer that case itself, by
    * quietly emitting everything whenever the two areas disagreed, which meant a caller who really had made a mistake —
    * diffing an off-screen staging buffer against the frame, say — got a silent full repaint every frame instead of an
    * error. Splitting the two makes the resize path something a test can name, and turns the mistake back into one.
    *
    * The columns holding the right half of a two-column grapheme are skipped here as well: emitting the grapheme itself
    * repaints both of its columns, and a terminal handed the continuation cell would draw a stray blank over the half
    * already painted. So are the positions marked [[DiffDirective.Skip]] — a resize is exactly the moment a blind full
    * repaint would erase an image somebody else painted, so the owner of such a region redraws it rather than this
    * buffer.
    */
  def emitAll(emit: (Int, Int, Cell) => Unit): Unit =
    FrameDiff.emitAll(this, emit)

  /** Content equality: the same `area`, the same [[Cell]] at every position, and the same continuation flags.
    *
    * The continuation flags are part of the comparison because they are part of what gets drawn: two buffers whose
    * cells all match but whose flags differ disagree about which columns a terminal is allowed to paint, and they
    * produce different diffs.
    *
    * '''A `Buffer` is mutable''', so this answer — and [[hashCode]] with it — changes as a frame is rendered into the
    * buffer. Compare or hash one only while nothing is writing to it, and never use one as a key in a hash map: the key
    * would move out from under the map on the next `set`.
    */
  override def equals(other: Any): Boolean = other match
    case that: Buffer =>
      (this eq that) || (area == that.area && sameCells(that) && sameContinuations(that))
    case _            => false

  private def sameCells(that: Buffer): Boolean =
    var index = 0
    var same  = true
    while same && index < cells.length do
      same = sameCell(cells(index), that.cells(index))
      index += 1
    same

  private def sameContinuations(that: Buffer): Boolean =
    java.util.Arrays.equals(continuations, that.continuations)

  /** Hashes the area and every cell. See [[equals]] for why a mutable buffer must not be used as a hash-map key. */
  override def hashCode(): Int =
    var hash  = area.hashCode()
    var index = 0
    while index < cells.length do
      hash = hash * 31 + cells(index).hashCode()
      hash = hash * 31 + (if continuations(index) then 1 else 0)
      index += 1
    hash

  /** A dump of the whole grid, for the one place a buffer is printed: the message of a failed test.
    *
    * The default `toString` of a class is its name and an object hash, which tells a reader nothing about why two
    * frames differ. This prints three sections instead, following ratatui's `Debug for Buffer`:
    *
    *   - `content` — one quoted string per row, every column included. Continuation cells hold [[Cell.Empty]] by
    *     construction, so they print as a space and the rows stay aligned in the dump even though the wide glyph before
    *     them takes two columns on a real terminal.
    *   - `styles` — one line per *change* of style in row-major order, not one line per cell. A frame is usually a few
    *     long runs of one style, so listing the runs is what makes a wrong colour visible instead of drowned.
    *   - `hidden` — the positions holding the second column of a two-column grapheme, which the `content` section
    *     cannot show because they carry no symbol of their own.
    *
    * Building the string walks every cell, so this is a debugging aid and not something to call per frame.
    */
  override def toString: String =
    val out = StringBuilder()
    out ++= s"Buffer(area=$area, content=[\n"
    var y   = area.y
    while y < area.bottom do
      out ++= "  \""
      var x = area.x
      while x < area.right do
        out ++= cells(indexOf(x, y)).symbol
        x += 1
      out ++= "\",\n"
      y += 1
    out ++= "], styles=[\n"
    appendStyleRuns(out)
    out ++= "], hidden=[\n"
    appendHiddenPositions(out)
    out ++= "])"
    out.result()

  /** Appends one line per style run to `out`: the first cell of the buffer, then every cell whose style differs from
    * the cell before it in row-major order.
    */
  private def appendStyleRuns(out: StringBuilder): Unit =
    // `None` is "no run has started yet", so the very first cell always opens one
    var previous: Option[Style] = None
    foreachIndex(area): (x, y, index) =>
      val style = cells(index).style
      if !previous.contains(style) then
        out ++= s"  x: $x, y: $y, $style\n"
        previous = Some(style)

  /** Appends one line per continuation cell to `out` — the columns a wide grapheme draws over from the left. */
  private def appendHiddenPositions(out: StringBuilder): Unit =
    foreachIndex(area): (x, y, index) =>
      if continuations(index) then out ++= s"  x: $x, y: $y, hidden by a two-column grapheme\n"

  /** Whether two cells would render identically: a reference-equality fast path before the structural compare, because
    * `Cell.equals` walks a `String`.
    */
  private[core] def sameCell(a: Cell, b: Cell): Boolean =
    (a eq b) || a == b

  /** The single bounds-check-and-index site behind [[get]]. Kept separate from [[get]] so the hot paths read a
    * `private` method the compiler can inline freely.
    */
  private def cellAt(x: Int, y: Int): Cell =
    if area.contains(x, y) then cells(indexOf(x, y)) else Cell.Empty

  private def indexOf(x: Int, y: Int): Int =
    (y - area.y) * area.width + (x - area.x)

/** Ways of building a buffer from content that is already laid out, rather than from an empty rectangle.
  *
  * `Buffer(rect)` — the class's own constructor — remains how a render target is allocated. These factories are for the
  * other direction: a test that wants to write the frame it expects as a literal, instead of allocating a `Rect` of the
  * right size by hand and calling `setString` once per row.
  */
object Buffer:

  /** The largest number of cells one buffer can hold.
    *
    * Every plane of a buffer is a single flat array indexed by `(row * width) + column`, and a JVM array is indexed by
    * an `Int`, so `Int.MaxValue` cells is the hard ceiling of that representation. A rectangle above it is rejected by
    * the constructor. For scale, a 1000x1000 terminal is one million cells, four thousand times under this limit.
    */
  val MaxCells: Long = Int.MaxValue.toLong

  /** The [[DiffDirective]] ordinals the diff loop compares against, resolved once rather than per cell. */
  private val DefaultCode: Byte            = DiffDirective.Default.ordinal.toByte
  private[core] val SkipCode: Byte         = DiffDirective.Skip.ordinal.toByte
  private[core] val AlwaysUpdateCode: Byte = DiffDirective.AlwaysUpdate.ordinal.toByte

  /** A fresh buffer covering `area` with every cell already set to `cell` — the starting point for a layer that is
    * opaque rather than transparent. The plain constructor `Buffer(area)` still starts out as [[Cell.Empty]]
    * everywhere, which is what a frame that composes onto other content wants.
    */
  def filled(area: Rect, cell: Cell): Buffer =
    val buffer = Buffer(area)
    buffer.fill(area, cell)
    buffer

  /** A buffer as tall as `lines` and as wide as the widest of them, with each line's spans painted left to right at
    * their own styles, starting at the origin.
    *
    * Each row goes through [[Buffer.setLine]], so the style cascade and the column advance are the ones every other
    * writer of a `Line` gets: the advance is the columns actually written rather than characters, so a two-column
    * grapheme takes two columns here exactly as it does when a widget draws it — an expectation written with CJK or
    * emoji in it lines up with the frame under test instead of drifting one column per wide cluster. Cells no line
    * reaches keep [[Cell.Empty]].
    *
    * Example — the expected frame for a two-row widget whose second row is highlighted:
    * {{{
    * val expected = Buffer.withLines(Line.raw("Total"), Line.styled("  42", Style.Default.bold))
    * }}}
    *
    * A caller holding a `Seq[Line]` splats it: `Buffer.withLines(rows*)`.
    */
  def withLines(lines: Line*): Buffer =
    val height = lines.size
    val width  = if lines.isEmpty then 0 else lines.map(_.width).max
    val buffer = Buffer(Rect(0, 0, width, height))
    lines.zipWithIndex.foreach((line, y) => buffer.setLine(0, y, line, width))
    buffer

  /** [[withLines]] for a whole [[Text]] value, for a caller that already has one.
    *
    * The text's own [[Text.style]] is laid under each line's, exactly as [[io.worxbend.tui.core.Text]] is rendered:
    * `Paragraph` folds the text style in and `withLines` folds the line style in, so a helper that dropped the outer
    * layer built an expected frame that could never equal the real one. `text.alignment` is not applied — this builds a
    * buffer only as wide as the widest line, so there is nothing for a line to be aligned inside of.
    */
  def withText(text: Text): Buffer =
    withLines(text.lines.map(line => line.copy(style = text.style.patch(line.style)))*)
