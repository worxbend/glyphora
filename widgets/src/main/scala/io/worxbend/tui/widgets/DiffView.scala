package io.worxbend.tui.widgets

import io.worxbend.tui.core.{
  Alignment,
  Buffer,
  CharWidth,
  Color,
  Line,
  Measured,
  Rect,
  Span,
  StatefulWidget,
  Style,
  Versioned,
}

/** How a [[DiffView]] lays a hunk out: one column with `-` lines above their `+` replacements (`Unified`, what `git
  * diff` prints), or the old file on the left and the new one on the right with a replaced line beside its replacement
  * (`Split`).
  */
enum DiffLayout:
  case Unified, Split

/** Everything about a [[DiffView]] past the patch it draws; `DiffViewOptions()` is the plain unified view.
  *
  * `contextLines = Some(n)` keeps `n` unchanged lines next to each change and folds every longer run of context into a
  * single `… 12 unchanged lines` row — the way to show a [[Patch.of]] computed with a large context without the reader
  * scrolling past the whole file; `None` shows every context line the patch holds. `wordHighlight` marks, on a removed
  * line and the added line that replaced it, the words that actually changed, in the `*Emphasis` styles. This optional
  * emphasis is skipped above 16384 UTF-16 units per line, 2048 tokens per line or 256 word edits; the ordinary line
  * tint remains, and a generated one-line replacement cannot exhaust the render thread's word-diff budget. `lexer`
  * highlights the text of every line through a [[Lexer]] in `syntaxTheme`, with the line's inserted or deleted style
  * underneath, so a theme whose `insertedStyle` is a background keeps the syntax colours on top of it. With no `lexer`,
  * `lexerFromPath` picks one per file from the extension of the path in its header (`.scala`, `.ts`, `.py`, `.yaml`, …,
  * through [[Lexer.forName]]); a file with no path or an extension no built-in lexer claims is drawn unhighlighted.
  * Each line is lexed on its own — a block comment spanning lines is not followed across them.
  *
  * Styles: `style` is the base of the whole view; `insertedStyle`/`deletedStyle` colour added and removed lines, marker
  * included; the emphasis styles are laid over those for changed words; the rest colour the file and hunk headers, the
  * line-number gutter (and the split view's divider) and the folded-context rows. `separator` is the split view's
  * divider and `collapsedGlyph` leads a folded-context row; `approximateNotice` is the first row of a patch marked
  * [[Patch.approximate]].
  */
final case class DiffViewOptions(
    layout: DiffLayout = DiffLayout.Unified,
    lineNumbers: Boolean = true,
    contextLines: Option[Int] = None,
    wordHighlight: Boolean = true,
    lexer: Option[Lexer] = None,
    lexerFromPath: Boolean = true,
    style: Style = Style.Default,
    insertedStyle: Style = Style.Default.withFg(Color.Green),
    deletedStyle: Style = Style.Default.withFg(Color.Red),
    insertedEmphasis: Style = Style.Default.bold.reverse,
    deletedEmphasis: Style = Style.Default.bold.reverse,
    fileHeaderStyle: Style = Style.Default.bold,
    hunkHeaderStyle: Style = Style.Default.withFg(Color.Cyan),
    lineNumberStyle: Style = Style.Default.dim,
    collapsedStyle: Style = Style.Default.dim.italic,
    syntaxTheme: SyntaxTheme = SyntaxTheme(),
    separator: String = "│",
    collapsedGlyph: String = "…",
    approximateNotice: String = "… too many changes to compare line by line",
):
  contextLines.foreach(n => require(n >= 0, s"contextLines must not be negative, was $n"))

/** Caller-owned [[DiffView]] state: the scroll offset (the first visible row), hunk navigation, which folded context
  * runs the reader expanded, and the horizontal scroll of the line text.
  *
  * [[nextHunk]] and [[previousHunk]] put a hunk's header on the top row, even when that leaves blank rows below the
  * last hunk — a reader stepping through hunks wants each one at the same place. [[expandAt]] opens the folded run on a
  * laid-out row (what a click on it does), [[expandFirstVisible]] the first one on screen (what Enter does), and
  * [[collapseAll]] folds them again. [[scrollRight]] and [[scrollLeft]] move every line's text sideways by columns —
  * the gutter, the markers and the headers stay put — up to the widest line. The rows of the hunks and the size of the
  * viewport are recorded by the most recent render, so every move is a no-op before the first frame. The state also
  * keeps the laid-out rows of the last patch it drew, so a view redrawn every frame does not diff its changed lines
  * again until the patch (by reference), the options or the expanded runs change.
  *
  * Render-thread-only. Every mutation raises `version` (see [[io.worxbend.tui.core.Versioned]]), so a DSL node that
  * paints this state notices a write made outside the event path and schedules the frame that shows it. The scrolling
  * itself — offset, clamp, page moves — is a [[ScrollViewState]] inside, so a diff and a scroll view page alike.
  */
final class DiffViewState extends Versioned:

  /** The scrolling half: the offset, the recorded viewport and content heights, and the clamped moves. */
  private val scroll: ScrollViewState = ScrollViewState()

  private var mutations: Long = 0L

  def version: Long = scroll.version + mutations

  def offset: Int = scroll.offset

  def offset_=(value: Int): Unit = scroll.offset = value

  private[widgets] var hunkRows: IndexedSeq[Int]      = IndexedSeq.empty
  private[widgets] var cache: Option[DiffLayoutCache] = None
  private[widgets] var expanded: Set[DiffFold]        = Set.empty
  private var columnValue: Int                        = 0

  /** How many columns every line's text is scrolled to the left. */
  def column: Int = columnValue

  /** Scrolls the line text `count` columns further right, stopping with the widest line's last column on screen. */
  def scrollRight(count: Int = 4): Unit = moveColumn(columnValue + math.max(0, count))

  /** Scrolls the line text `count` columns back towards its start. */
  def scrollLeft(count: Int = 4): Unit = moveColumn(columnValue - math.max(0, count))

  private def moveColumn(target: Int): Unit =
    val widest  = cache.fold(0)(_.widestText)
    val clamped = math.max(0, math.min(target, widest - 1))
    if clamped != columnValue then
      columnValue = clamped
      mutations += 1

  /** Expands the folded context run on laid-out row `row` (the row index the view's `offset` counts in); answers
    * whether that row was a fold.
    */
  def expandAt(row: Int): Boolean =
    cache.flatMap(_.rows.lift(row)) match
      case Some(DiffRow.Collapsed(_, fold)) =>
        expanded += fold
        mutations += 1
        true
      case _                                => false

  /** Expands the first folded run on screen — on the rows the last frame showed; answers whether there was one. */
  def expandFirstVisible(): Boolean =
    val rows = cache.fold(IndexedSeq.empty[DiffRow])(_.rows)
    (offset until math.min(rows.size, offset + scroll.viewportRows))
      .find { row =>
        rows(row) match
          case DiffRow.Collapsed(_, _) => true
          case _                       => false
      }
      .exists(expandAt)

  /** Folds every run the reader expanded back up. */
  def collapseAll(): Unit =
    if expanded.nonEmpty then
      expanded = Set.empty
      mutations += 1

  /** Records the size of the frame just drawn, which every clamped move is measured against. */
  private[widgets] def recordFrame(contentHeight: Int, viewportHeight: Int): Unit =
    scroll.lastContentHeight = contentHeight
    scroll.lastViewportHeight = viewportHeight

  /** The index of the hunk whose header is at or above the top row, or `None` above the first hunk. */
  def currentHunk: Option[Int] =
    val index = hunkRows.lastIndexWhere(_ <= offset)
    Option.when(index >= 0)(index)

  /** Scrolls the next hunk's header to the top row; past the last hunk, nothing moves. */
  def nextHunk(): Unit = hunkRows.find(_ > offset).foreach(row => offset = row)

  /** Scrolls the previous hunk's header — the one above the top row — to the top row. */
  def previousHunk(): Unit = hunkRows.findLast(_ < offset).foreach(row => offset = row)

  def scrollUp(count: Int = 1): Unit = scroll.scrollUp(count)

  /** Scrolls `count` rows towards the end, stopping once the last row is at the bottom of the viewport. An offset a
    * hunk jump left further down than that stays where it is.
    */
  def scrollDown(count: Int = 1): Unit = if offset < scroll.maxOffset then scroll.scrollDown(count)

  /** Scrolls up by one viewport — the PageUp move. */
  def pageUp(): Unit = scroll.pageUp()

  /** Scrolls down by one viewport — the PageDown move; like [[scrollDown]], it never pulls a hunk jump back up. */
  def pageDown(): Unit = if offset < scroll.maxOffset then scroll.pageDown()

  def first(): Unit = scroll.first()

  def last(): Unit = scroll.last()

  private[widgets] def rowsFor(patch: Patch, options: DiffViewOptions): IndexedSeq[DiffRow] =
    cache match
      case Some(laid) if (laid.patch eq patch) && laid.options == options && laid.expanded == expanded => laid.rows
      case _                                                                                           =>
        val rows = DiffRow.layout(patch, options, expanded)
        cache = Some(DiffLayoutCache(patch, options, expanded, rows, DiffRow.widestText(rows)))
        rows

/** The rows a [[DiffViewState]] laid out last, with what they were laid out for and the widest line text among them. */
private[widgets] final case class DiffLayoutCache(
    patch: Patch,
    options: DiffViewOptions,
    expanded: Set[DiffFold],
    rows: IndexedSeq[DiffRow],
    widestText: Int,
)

/** Names one run of context in a patch — the `segment`th part of hunk `hunk` of file `file` — so an expanded run stays
  * expanded while the same patch is redrawn.
  */
private[widgets] final case class DiffFold(file: Int, hunk: Int, segment: Int)

/** A unified diff, drawn as `git diff` prints it or side by side — the review pane of a coding agent, a commit browser,
  * a "what changed" panel. `patch` comes from [[Patch.parse]] (git's own output) or [[Patch.of]] (two texts).
  *
  * Each file with a path is headed by it (`old → new` for a rename), each hunk by its `@@` line, and each line carries
  * its old and new line numbers. Unified mode lists a change's removed lines above its added ones; split mode puts the
  * old side left of the [[DiffViewOptions.separator]] and the new side right of it, a replaced line beside its
  * replacement and the longer side of an uneven change against blank rows. Columns are measured with
  * [[io.worxbend.tui.core.CharWidth]], so CJK and emoji keep both halves aligned; a line too long for its column is
  * clipped. See [[DiffViewOptions]] for folding context, word highlighting and syntax highlighting.
  *
  * Scrolling and hunk navigation live in the caller-owned [[DiffViewState]]; the widget value is immutable. It measures
  * its height — one row per header, line and folded run — through [[io.worxbend.tui.core.Measured Measured]].
  */
final case class DiffView(patch: Patch, options: DiffViewOptions = DiffViewOptions())
    extends StatefulWidget[DiffViewState]
    with Measured:

  private lazy val rows: IndexedSeq[DiffRow] = DiffRow.layout(patch, options, Set.empty)

  /** The rows with every context run folded as `options` folds it — see [[heightAt(width:Int,state*]] for the rows a
    * state with expanded runs draws.
    */
  override def heightAt(width: Int): Option[Int] = Some(rows.size)

  /** The rows this view draws for `state`, the runs its reader expanded included. */
  def heightAt(width: Int, state: DiffViewState): Int =
    val _ = width
    state.rowsFor(patch, options).size

  def render(area: Rect, buffer: Buffer, state: DiffViewState): Unit =
    val laidOut = state.rowsFor(patch, options)
    state.hunkRows = laidOut.indices.filter { index =>
      laidOut(index) match
        case DiffRow.HunkHeader(_) => true
        case _                     => false
    }
    state.recordFrame(laidOut.size, math.max(1, area.height))
    state.offset = math.max(0, math.min(state.offset, laidOut.size - 1))
    if !area.isEmpty then
      buffer.setStyle(area, options.style)
      val gutter = DiffRow.gutterWidth(patch)
      laidOut.slice(state.offset, state.offset + area.height).zipWithIndex.foreach { (row, index) =>
        drawRow(buffer, area, area.y + index, row, gutter, state.column)
      }

  private def drawRow(buffer: Buffer, area: Rect, y: Int, row: DiffRow, gutter: Int, column: Int): Unit =
    def full(line: Line): Unit =
      val _ = LineRenderer.render(buffer, area.x, y, line, area.width, options.style)
    row match
      case DiffRow.Notice(text)        => full(Line.styled(text, options.collapsedStyle))
      case DiffRow.FileHeader(title)   => full(Line.styled(title, options.fileHeaderStyle))
      case DiffRow.HunkHeader(text)    => full(Line.styled(text, options.hunkHeaderStyle))
      case DiffRow.Collapsed(count, _) =>
        val noun = if count == 1 then "line" else "lines"
        full(Line.styled(s"${options.collapsedGlyph} $count unchanged $noun", options.collapsedStyle))
      case DiffRow.Body(side, numbers) =>
        val textAt = drawGutter(buffer, area.x, y, area.width, numbers, gutter)
        drawSide(buffer, textAt, y, area.x + area.width - textAt, side, column)
      case DiffRow.Pair(left, right)   =>
        val separatorWidth = CharWidth.of(options.separator)
        val leftWidth      = math.max(0, area.width - separatorWidth) / 2
        val rightX         = area.x + leftWidth + separatorWidth
        val rightWidth     = math.max(0, area.x + area.width - rightX)
        left.foreach(side => drawNumbered(buffer, area.x, y, leftWidth, side, gutter, column))
        val _              = LineRenderer.render(
          buffer,
          area.x + leftWidth,
          y,
          Line.styled(options.separator, options.lineNumberStyle),
          math.min(separatorWidth, area.width - leftWidth),
          options.style,
        )
        right.foreach(side => drawNumbered(buffer, rightX, y, rightWidth, side, gutter, column))

  private def drawNumbered(buffer: Buffer, x: Int, y: Int, width: Int, side: DiffSide, gutter: Int, column: Int): Unit =
    val textAt = drawGutter(buffer, x, y, width, Seq(side.number), gutter)
    drawSide(buffer, textAt, y, x + width - textAt, side, column)

  /** Writes the right-aligned line numbers (`None` is a blank column) and returns the column the marker starts at. */
  private def drawGutter(buffer: Buffer, x: Int, y: Int, width: Int, numbers: Seq[Option[Int]], gutter: Int): Int =
    if !options.lineNumbers || width <= 0 then x
    else
      val text    = numbers.map(number => padLeft(number.fold("")(_.toString), gutter)).mkString("", " ", " ")
      val written = LineRenderer.render(buffer, x, y, Line.styled(text, options.lineNumberStyle), width, options.style)
      x + written

  /** The marker, then the text scrolled `column` columns to the left; the marker never scrolls. */
  private def drawSide(buffer: Buffer, x: Int, y: Int, width: Int, side: DiffSide, column: Int): Unit =
    if width > 0 then
      val (marker, tint) = side.kind match
        case DiffKind.Context => (" ", Style.Default)
        case DiffKind.Added   => ("+", options.insertedStyle)
        case DiffKind.Removed => ("-", options.deletedStyle)
      if side.kind != DiffKind.Context then buffer.setStyle(Rect(x, y, width, 1), options.style.patch(tint))
      val markerWidth    = LineRenderer.render(buffer, x, y, Line(Seq(Span(marker, tint))), width, options.style)
      if width > markerWidth then
        val _ = LineRenderer.render(
          buffer,
          x + markerWidth,
          y,
          side.text,
          width - markerWidth,
          options.style,
          Alignment.Left,
          column,
        )

  private def padLeft(text: String, width: Int): String = " " * math.max(0, width - CharWidth.of(text)) + text

/** One kind of diff line, for the renderer. */
private[widgets] enum DiffKind:
  case Context, Added, Removed

/** One side of a body row: its kind, its line number on that side, and its text, already styled. */
private[widgets] final case class DiffSide(kind: DiffKind, number: Option[Int], text: Line)

/** One row of a laid-out [[DiffView]]. `Body` is a unified row with its old and new numbers; `Pair` a split row. */
private[widgets] enum DiffRow:
  case Notice(text: String)
  case FileHeader(title: String)
  case HunkHeader(text: String)
  case Collapsed(count: Int, fold: DiffFold)
  case Body(side: DiffSide, numbers: Seq[Option[Int]])
  case Pair(left: Option[DiffSide], right: Option[DiffSide])

private[widgets] object DiffRow:

  /** A hunk, cut into runs of context and blocks of change; a change is removed lines followed by added ones. */
  private enum Segment:
    case Unchanged(lines: Seq[(Int, Int, String)])
    case Changed(removed: Seq[(Int, String)], added: Seq[(Int, String)])

  /** Digits in the largest line number any hunk of `patch` reaches, so every gutter lines up. */
  def gutterWidth(patch: Patch): Int =
    val largest = patch.hunks.map(h => math.max(h.oldStart + h.oldCount - 1, h.newStart + h.newCount - 1)).maxOption
    largest.fold(1)(n => math.max(1, n.toString.length))

  /** The widest line text among `rows`, in columns — how far a [[DiffViewState]] may scroll sideways. */
  def widestText(rows: IndexedSeq[DiffRow]): Int =
    rows.iterator
      .map {
        case Body(side, _)     => side.text.width
        case Pair(left, right) => math.max(left.fold(0)(_.text.width), right.fold(0)(_.text.width))
        case _                 => 0
      }
      .maxOption
      .getOrElse(0)

  def layout(patch: Patch, options: DiffViewOptions, expanded: Set[DiffFold]): IndexedSeq[DiffRow] =
    val notice = if patch.approximate then IndexedSeq(Notice(options.approximateNotice)) else IndexedSeq.empty
    notice ++ patch.files.toIndexedSeq.zipWithIndex.flatMap { (file, fileIndex) =>
      val title = (file.oldPath, file.newPath) match
        case (Some(before), Some(after)) if before != after => Some(s"$before → $after")
        case (before, after)                                => after.orElse(before)
      val lexer = options.lexer.orElse(if options.lexerFromPath then lexerFor(file) else None)
      title.map(FileHeader(_)).toIndexedSeq ++ file.hunks.zipWithIndex.flatMap((hunk, hunkIndex) =>
        HunkHeader(Patch.header(hunk)) +: body(hunk, options, lexer, expanded, DiffFold(fileIndex, hunkIndex, _))
      )
    }

  /** The built-in lexer the extension of `file`'s path names — its new path, or its old one for a deleted file. */
  private def lexerFor(file: FilePatch): Option[Lexer] =
    file.newPath.orElse(file.oldPath).flatMap { path =>
      val name = path.drop(path.lastIndexOf('/') + 1)
      val dot  = name.lastIndexOf('.')
      Option.when(dot > 0 && dot < name.length - 1)(name.drop(dot + 1)).flatMap(Lexer.forName)
    }

  private def segments(hunk: Hunk): Seq[Segment] =
    val out                  = Vector.newBuilder[Segment]
    var oldLine              = hunk.oldStart
    var newLine              = hunk.newStart
    var context              = Vector.empty[(Int, Int, String)]
    var removed              = Vector.empty[(Int, String)]
    var added                = Vector.empty[(Int, String)]
    def flushChange(): Unit  =
      if removed.nonEmpty || added.nonEmpty then out += Segment.Changed(removed, added)
      removed = Vector.empty
      added = Vector.empty
    def flushContext(): Unit =
      if context.nonEmpty then out += Segment.Unchanged(context)
      context = Vector.empty
    hunk.lines.foreach {
      case DiffLine.Context(text) =>
        flushChange()
        context :+= ((oldLine, newLine, text))
        oldLine += 1
        newLine += 1
      case DiffLine.Removed(text) =>
        flushContext()
        if added.nonEmpty then flushChange()
        removed :+= ((oldLine, text))
        oldLine += 1
      case DiffLine.Added(text)   =>
        flushContext()
        added :+= ((newLine, text))
        newLine += 1
    }
    flushChange()
    flushContext()
    out.result()

  private def body(
      hunk: Hunk,
      options: DiffViewOptions,
      lexer: Option[Lexer],
      expanded: Set[DiffFold],
      foldAt: Int => DiffFold,
  ): Seq[DiffRow] =
    val parts = segments(hunk)
    parts.zipWithIndex.flatMap {
      case (Segment.Unchanged(lines), index)    =>
        val fold = foldAt(index)
        val keep = if expanded.contains(fold) then None else options.contextLines
        folded(lines, keep, leading = index == 0, trailing = index == parts.size - 1).map {
          case Left(count)                     => Collapsed(count, fold)
          case Right((oldLine, newLine, text)) =>
            val side =
              DiffSide(DiffKind.Context, None, styled(text, Style.Default, Set.empty, Style.Default, options, lexer))
            options.layout match
              case DiffLayout.Unified => Body(side, Seq(Some(oldLine), Some(newLine)))
              case DiffLayout.Split   =>
                Pair(Some(side.copy(number = Some(oldLine))), Some(side.copy(number = Some(newLine))))
        }
      case (Segment.Changed(removed, added), _) =>
        val marks    = (0 until math.max(removed.size, added.size)).map { index =>
          if options.wordHighlight && index < removed.size && index < added.size then
            changedWords(removed(index)._2, added(index)._2)
          else (Set.empty[Int], Set.empty[Int])
        }
        val oldSides = removed.zipWithIndex.map { case ((line, text), index) =>
          DiffSide(
            DiffKind.Removed,
            Some(line),
            styled(text, options.deletedStyle, marks(index)._1, options.deletedEmphasis, options, lexer),
          )
        }
        val newSides = added.zipWithIndex.map { case ((line, text), index) =>
          DiffSide(
            DiffKind.Added,
            Some(line),
            styled(text, options.insertedStyle, marks(index)._2, options.insertedEmphasis, options, lexer),
          )
        }
        options.layout match
          case DiffLayout.Unified =>
            oldSides.map(side => Body(side, Seq(side.number, None))) ++
              newSides.map(side => Body(side, Seq(None, side.number)))
          case DiffLayout.Split   =>
            (0 until math.max(oldSides.size, newSides.size)).map(index =>
              Pair(oldSides.lift(index), newSides.lift(index))
            )
    }

  /** The lines of a context run to keep, with `Left(count)` where `count` hidden lines fold into one row. A leading run
    * keeps its last `keep` lines, a trailing one its first, a run between changes both; folding one line into a row
    * saves nothing, so a run is folded only when it would hide at least two.
    */
  private def folded[A](lines: Seq[A], keep: Option[Int], leading: Boolean, trailing: Boolean): Seq[Either[Int, A]] =
    keep match
      case None    => lines.map(Right(_))
      case Some(n) =>
        val head   = if leading && !trailing then 0 else n
        val tail   = if trailing then 0 else n
        val hidden = lines.size - head - tail
        if hidden < 2 then lines.map(Right(_))
        else (lines.take(head).map(Right(_)) :+ Left(hidden)) ++ lines.takeRight(tail).map(Right(_))

  /** A token of a line for the word diff: a run of letters, digits and `_`, a run of whitespace, or one other cluster.
    * Each token remembers the UTF-16 offset it starts at, which is what the styled spans are cut on.
    */
  private def tokens(text: String): IndexedSeq[(String, Int)] =
    val out     = Vector.newBuilder[(String, Int)]
    val current = StringBuilder()
    var start   = 0
    var at      = 0
    var kind    = -1
    CharWidth.graphemeClusters(text).foreach { cluster =>
      val first     = cluster.codePointAt(0)
      val clusterOf =
        if Character.isLetterOrDigit(first) || first == '_' then 0
        else if Character.isWhitespace(first) then 1
        else 2
      if current.nonEmpty && (clusterOf != kind || clusterOf == 2) then
        out += ((current.result(), start))
        current.clear()
        start = at
      current.append(cluster)
      kind = clusterOf
      at += cluster.length
    }
    if current.nonEmpty then out += ((current.result(), start))
    out.result()

  /** The UTF-16 offsets of the characters that differ between a removed line and the added line that replaced it, found
    * by a word-level Myers diff — on the removed side and on the added side.
    */
  private def changedWords(before: String, after: String): (Set[Int], Set[Int]) =
    // Tokenization and offset sets are bounded too, not just Myers' quadratic trace. Minified/generated
    // one-line replacements retain their ordinary inserted/deleted tint without word-level emphasis.
    if before.length > 16384 || after.length > 16384 then (Set.empty, Set.empty)
    else
      val oldTokens = tokens(before)
      val newTokens = tokens(after)
      if oldTokens.size > 2048 || newTokens.size > 2048 then (Set.empty, Set.empty)
      else
        val (edits, exact) = MyersDiff.diffBounded(oldTokens.map(_._1), newTokens.map(_._1), 256)
        if !exact then (Set.empty, Set.empty)
        else
          def offsets(token: (String, Int)): Range = token._2 until token._2 + token._1.length
          val deleted  = edits.filter(_.op == MyersDiff.Op.Delete).flatMap(edit => offsets(oldTokens(edit.oldIndex)))
          val inserted = edits.filter(_.op == MyersDiff.Op.Insert).flatMap(edit => offsets(newTokens(edit.newIndex)))
          (deleted.toSet, inserted.toSet)

  /** `text` as spans: the lexer's styles (or none) over `tint`, with `emphasis` laid over the characters in `marked`.
    * Neighbouring characters that end up in the same style share a span.
    */
  private def styled(
      text: String,
      tint: Style,
      marked: Set[Int],
      emphasis: Style,
      options: DiffViewOptions,
      lexer: Option[Lexer],
  ): Line =
    val lexed  = lexer.fold(Seq(Span.raw(text)))(_.highlightLine(text, options.syntaxTheme).spans)
    val spans  = Vector.newBuilder[Span]
    val run    = StringBuilder()
    var style  = Option.empty[Style]
    var offset = 0
    lexed.foreach { span =>
      span.content.foreach { char =>
        val base    = tint.patch(span.style)
        val current = if marked.contains(offset) then base.patch(emphasis) else base
        if !style.contains(current) && run.nonEmpty then
          spans += Span(run.result(), style.getOrElse(Style.Default))
          run.clear()
        run.append(char)
        style = Some(current)
        offset += 1
      }
    }
    if run.nonEmpty then spans += Span(run.result(), style.getOrElse(Style.Default))
    Line(spans.result())
