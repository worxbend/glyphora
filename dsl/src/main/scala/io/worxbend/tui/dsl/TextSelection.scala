package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Position, Rect, Style}
import io.worxbend.tui.widgets.Clipboard

import java.text.BreakIterator

/** Mouse text selection over the text-showing nodes — `text`, `markdown`, `log`, `logConsole`, the tables — composed as
  * an overlay on the frame rather than as a feature of any widget.
  *
  * The selection belongs to a node — held by its key or its path from the root, see [[TextSelection.anchorFor]] — and
  * to cells of that node's own area, not to screen positions: when the tree around the node changes shape it stays on
  * the node, and when a scroll view moves the node the highlight moves with it.
  *
  * A mouse-capturing app takes the terminal's own selection away, which users notice immediately; this gives it back
  * inside the app. A left press that no element consumed, on a node that is selectable, anchors a selection there; a
  * drag extends it (the head is clamped to that node, so a selection never wanders into the next panel); the release
  * copies the selected text through the app clipboard — OSC 52 plus the in-app paste buffer, see
  * [[AppServices.clipboard]]. A double click selects the word under the pointer, by `java.text.BreakIterator`; a triple
  * click the whole row. The next press anywhere clears it.
  *
  * '''Composition.''' Nothing about the widget changes. The node's recorded [[BoundsElement]] wrapper calls [[painted]]
  * straight after the node paints: that copies the node's visible cells into a grid (the source of the copied text and
  * of word boundaries — the frame buffer itself is reused by the runner, so it cannot be read after the frame) and
  * layers the theme's focus cue over the selected cells. Selection works in grapheme cells: a wide character is one
  * symbol over two cells, and its trailing continuation cell contributes nothing to the text.
  *
  * '''Ownership and threads.''' One belongs to one [[ElementHost]] and is touched only on its render thread, from the
  * mouse dispatch and the paint. Unsynchronised for that reason.
  */
private[dsl] final class TextSelection:

  /** Whether selection is on at all — `TuiApp` turns it on exactly when it runs with mouse capture. */
  var enabled: Boolean = false

  // Where the selection lives: the node, by the identity [[TextSelection.anchorOf]] gives it, and anchor and head as
  // cells of that node's own area (0, 0 is its top-left corner). The screen is only where the node was last seen:
  // `origin` is the screen position of the node's (0, 0) and `shown` the part of the node on screen, both as of the last
  // paint — so the selection follows its node through a reshaped tree and travels with it when a scroll view moves.
  private var regionKey: Option[String]    = None
  private var origin: Position             = Position(0, 0)
  private var shown: Rect                  = Rect(0, 0, 0, 0)
  private var size: Rect                   = Rect(0, 0, 0, 0)
  private var anchor: Position             = Position(0, 0)
  private var head: Position               = Position(0, 0)
  private var pressAt: Position            = Position(0, 0)
  private var granularity: Int             = 1
  private var pendingSnap: Boolean         = false
  private var moved: Boolean               = false
  private var dragging: Boolean            = false
  private var grid: Vector[Vector[String]] = Vector.empty
  private var copyTo: Option[Clipboard]    = None
  private var paintedThisFrame: Boolean    = false

  /** Whether a selection is anchored, visible or not. */
  def isActive: Boolean = regionKey.isDefined

  /** Whether the pointer is still down on a selection being dragged out. */
  def isDragging: Boolean = dragging

  /** Whether the selection covers at least one cell the reader can see as selected. */
  def isVisible: Boolean = regionKey.isDefined && (moved || granularity > 1)

  /** Anchors a selection at screen position `at` inside the node recorded as `entry`. `clicks` is the press's click
    * count: 2 selects the word under `at`, 3 or more the whole row; the boundaries are worked out on the next paint,
    * from the cells the node actually drew.
    */
  def begin(entry: NodeBoundsEntry, at: Position, clicks: Int): Unit =
    regionKey = Some(TextSelection.anchorOf(entry.id, entry.anchor))
    origin = entry.layout.position
    size = Rect(0, 0, entry.layout.width, entry.layout.height)
    shown = local(entry.visible)
    anchor = toLocal(at)
    head = anchor
    pressAt = at
    granularity = math.min(math.max(clicks, 1), 3)
    pendingSnap = granularity > 1
    moved = false
    dragging = true
    copyTo = None

  /** Moves the head to screen position `to`, clamped into the part of the anchored node on screen. A pointer still on
    * the cell it pressed moves nothing, so the release of a double click keeps the word it selected.
    */
  def extend(to: Position): Unit =
    val clamped = clamp(toLocal(to))
    if (moved || to != pressAt) && clamped != head then
      head = clamped
      moved = true

  /** Ends the drag at `to` and, when anything is selected, copies it into `clipboard` once the next paint has seen the
    * final extent.
    */
  def release(to: Position, clipboard: Clipboard): Unit =
    extend(to)
    dragging = false
    if isVisible then copyTo = Some(clipboard)

  /** Forgets the selection; `true` when one was showing, so the frame that drops the highlight is owed. */
  def clear(): Boolean =
    val showing = isVisible
    regionKey = None
    dragging = false
    copyTo = None
    grid = Vector.empty
    showing

  /** The selected text as of the last paint, `None` when nothing is selected. Only the rows of the node that were on
    * screen are known — a scroll view draws nothing else — so rows of the selection scrolled out of view are left out.
    */
  def text: Option[String] =
    if !isVisible || grid.isEmpty then None
    else
      val (lo, hi) = ordered
      val rows     = (math.max(lo.y, shown.y) to math.min(hi.y, shown.bottom - 1)).map { y =>
        val from = if y == lo.y then lo.x else 0
        val to   = if y == hi.y then hi.x else size.right - 1
        val row  = grid.lift(y - shown.y).getOrElse(Vector.empty)
        (from to to).iterator.map(x => row.lift(x - shown.x).getOrElse("")).mkString.stripTrailing()
      }
      Some(rows.mkString("\n"))

  /** Called by the bounds wrapper of the node identified as `key` right after it painted into `area` of `buffer`: notes
    * where the node now is on screen, snapshots its visible cells, resolves a pending word or row selection, layers
    * `highlight` over the selected cells and makes a copy the release asked for.
    */
  def painted(key: String, area: Rect, buffer: Buffer, highlight: Style): Unit =
    if regionKey.contains(key) then
      paintedThisFrame = true
      val onFrame = FrameCoordinates.translate(area)
      origin = onFrame.position
      size = Rect(0, 0, area.width, area.height)
      shown = local(FrameCoordinates.visible(area))
      // a cell of the node at local (x, y) sits at (area.x + x, area.y + y) of the buffer it was painted into
      grid = Vector.tabulate(shown.height, shown.width) { (row, column) =>
        val x = area.x + shown.x + column
        val y = area.y + shown.y + row
        if !buffer.area.contains(x, y) || buffer.isContinuation(x, y) then "" else buffer.get(x, y).symbol
      }
      if pendingSnap then
        pendingSnap = false
        snapToGranularity()
      if isVisible then
        val (lo, hi) = ordered
        (lo.y to hi.y).foreach { y =>
          val from = if y == lo.y then lo.x else 0
          val to   = if y == hi.y then hi.x else size.right - 1
          (from to to).foreach { x =>
            val cellAt = Position(area.x + x, area.y + y)
            if buffer.area.contains(cellAt) then
              val cell = buffer.get(cellAt)
              buffer.set(cellAt, cell.copy(style = cell.style.patch(highlight)))
          }
        }
      copyTo.foreach { clipboard =>
        copyTo = None
        text.foreach(clipboard.copy)
      }

  /** Called once the frame is painted: a selection whose node was not on it is dropped. */
  def afterPaint(): Unit =
    if regionKey.isDefined && !paintedThisFrame then
      val _ = clear()
    paintedThisFrame = false

  private def toLocal(at: Position): Position = Position(at.x - origin.x, at.y - origin.y)

  private def local(rect: Rect): Rect = Rect(rect.x - origin.x, rect.y - origin.y, rect.width, rect.height)

  /** Anchor and head in reading order, widened to whole graphemes: an end on the trailing half of a wide character
    * takes the character, and so does an end on its leading half.
    */
  private def ordered: (Position, Position) =
    val (lo, hi)                             =
      if anchor.y < head.y || (anchor.y == head.y && anchor.x <= head.x) then (anchor, head) else (head, anchor)
    def cell(x: Int, y: Int): Option[String] = grid.lift(y - shown.y).flatMap(_.lift(x - shown.x))
    val start = Iterator.iterate(lo.x)(_ - 1).find(x => x <= 0 || !cell(x, lo.y).contains("")).getOrElse(lo.x)
    val end   = Iterator.iterate(hi.x)(_ + 1).find(x => !cell(x + 1, hi.y).contains("")).getOrElse(hi.x)
    (Position(start, lo.y), Position(end, hi.y))

  private def clamp(to: Position): Position =
    if shown.isEmpty then to
    else if to.y < shown.y then Position(shown.x, shown.y)
    else if to.y >= shown.bottom then Position(shown.right - 1, shown.bottom - 1)
    else Position(math.min(math.max(to.x, shown.x), shown.right - 1), to.y)

  /** Widens anchor..head to the word (granularity 2) or the row (3) under the anchor, from the snapshot. */
  private def snapToGranularity(): Unit =
    val row = grid.lift(anchor.y - shown.y).getOrElse(Vector.empty)
    if row.nonEmpty then
      if granularity >= 3 then
        anchor = Position(shown.x, anchor.y)
        head = Position(shown.right - 1, anchor.y)
      else
        TextSelection.wordColumns(row, anchor.x - shown.x).foreach { (from, to) =>
          anchor = Position(shown.x + from, anchor.y)
          head = Position(shown.x + to, anchor.y)
        }

private[dsl] object TextSelection:

  /** The identity a selection holds a node by across frames: its key when it has one, else its path of child positions
    * from the root (`trail`, innermost first, as the focus pass keeps it). Neither moves when nodes are added elsewhere
    * in the tree, which the node's pre-order id does.
    */
  def anchorFor(key: Option[String], trail: List[Int]): String =
    key.fold(trail.reverseIterator.mkString("path:", "/", ""))("key:" + _)

  /** The selection identity of node `id`, given the anchor its bounds wrapper carries. */
  def anchorOf(id: Int, anchor: Option[String]): String = anchor.getOrElse(s"node:$id")

  /** The first and last cell (inclusive) of the word `java.text.BreakIterator` finds around `column` in `row`, where
    * `row` holds one grapheme per cell and `""` for the trailing half of a wide one. `None` past the row's end.
    */
  def wordColumns(row: Vector[String], column: Int): Option[(Int, Int)] =
    if column < 0 || column >= row.length then None
    else
      val starts = row.scanLeft(0)(_ + _.length)
      val joined = row.mkString
      // a click on the trailing half of a wide character belongs to the character
      val owner  = Iterator.iterate(column)(_ - 1).find(c => c <= 0 || row(c).nonEmpty).getOrElse(0)
      val offset = starts(owner)
      if offset >= joined.length then None
      else
        val words = BreakIterator.getWordInstance
        words.setText(joined)
        val end   = words.following(offset)
        val start = words.preceding(end)
        val first = row.indices.find(c => row(c).nonEmpty && starts(c) >= start).getOrElse(owner)
        val last  = row.indices.reverse.find(c => row(c).nonEmpty && starts(c) < end).getOrElse(owner)
        // the last character's continuation cells belong to it
        val tail  = Iterator.iterate(last + 1)(_ + 1).find(c => c >= row.length || row(c).nonEmpty).getOrElse(last + 1)
        Some((first, tail - 1))

  /** Whether `element` is one of the text-showing nodes that are selectable unless they opt out. */
  def selectableByDefault(element: Element): Boolean =
    element match
      case _: TextElement | _: RichTextElement | _: LineElement | _: TableElement | _: StyledTableElement |
          _: LogElement | _: LogConsoleElement | _: MarkdownStreamElement | _: DataTableElement[?] =>
        true
      case _ => false

/** Text selection — see [[TextSelection]]. */
extension [E <: Element](element: E)

  /** Whether a mouse drag over this element selects its text. The text-showing nodes (`text`, `markdown`, `log`,
    * `logConsole`, `table`, `styledTable`, `dataTable`) are selectable by default; `.selectable(false)` opts one out,
    * together with everything inside it, and `.selectable(true)` opts any other node in. Selection only exists in an
    * app that captures the mouse — without capture the terminal selects natively.
    */
  def selectable(enabled: Boolean): element.Self =
    element.withProps(element.props.copy(selectable = Some(enabled)))
