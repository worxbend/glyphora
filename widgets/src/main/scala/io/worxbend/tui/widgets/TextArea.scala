package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, StatefulWidget, Style, Text}

import scala.collection.mutable

/** Caller-owned multi-line editing state for [[TextArea]].
  *
  * Text is a vector of lines, each a vector of grapheme clusters — the cursor is `(line, column)` in cluster
  * coordinates and can never split a combining sequence or emoji. Every editing operation snapshots onto a bounded undo
  * stack. Edits and line joins re-segment affected lines; a splice inside a newly merged grapheme maps the cursor to
  * that grapheme's end (right affinity), identically for insertion, deletion and either join direction.
  *
  * Control characters other than the `\n` that separates lines are dropped on the way in — by the constructor as well
  * as by [[insert]] — because a control is zero columns wide but still fills a whole `Cell`, so storing one
  * desynchronises the backend's cursor model from the terminal's.
  *
  * Render-thread-only, and mutating it does not by itself schedule a frame. This is a plain mutable object, invisible
  * to the reactive layer: a background result written straight into it stays off screen until something unrelated
  * happens to repaint. Pair the mutation with a `Signal` write, or call `TuiApp.requestRedraw()` from the same
  * render-thread callback that made it.
  */
final class TextAreaState(initial: String = ""):

  private var lines: Vector[Vector[String]] = clusterLinesOf(initial)
  private var line                          = lines.size - 1
  private var column                        = lines.last.size
  private[widgets] var scrollRow: Int       = 0

  /** Horizontal scroll offset in *columns*, shared by every line — see [[TextArea.scrolledHorizontally]]. */
  private[widgets] var scrollColumn: Int = 0
  private val undoStack                  = mutable.Stack[(Vector[Vector[String]], Int, Int)]()
  private val redoStack                  = mutable.Stack[(Vector[Vector[String]], Int, Int)]()

  def value: String = lines.map(_.mkString).mkString("\n")

  /** Cursor as `(line, column)` in cluster coordinates. */
  def cursor: (Int, Int) = (line, column)

  def lineCount: Int = lines.size

  private[widgets] def clusterLines: Vector[Vector[String]] = lines

  /** Splits `text` into one cluster vector per line, dropping controls *after* the split so that the `\n` doing the
    * splitting survives — [[newline]] is `insert("\n")`. Reads no field, so the field initializer may call it.
    */
  private def clusterLinesOf(text: String): Vector[Vector[String]] =
    Text.splitLines(text).toVector.map(GraphemeEdit.clustersOf)

  def insert(text: String): Unit =
    pushUndo()
    val segments        = clusterLinesOf(text)
    val (before, after) = lines(line).splitAt(column)
    if segments.size == 1 then editLine(column, 0, segments.head.mkString)
    else
      val (first, _)           = GraphemeEdit.joined(before.mkString, segments.head.mkString, "")
      val (last, mappedColumn) = GraphemeEdit.joined("", segments.last.mkString, after.mkString)
      val middle               = segments.drop(1).dropRight(1)
      lines = lines.take(line) ++ (first +: middle :+ last) ++ lines.drop(line + 1)
      line += segments.size - 1
      column = mappedColumn

  def newline(): Unit = insert("\n")

  def backspace(): Unit =
    if column > 0 then
      pushUndo()
      editLine(column - 1, 1, "")
    else if line > 0 then
      pushUndo()
      joinLines(line - 1)

  def delete(): Unit =
    if column < lines(line).size then
      pushUndo()
      editLine(column, 1, "")
    else if line < lines.size - 1 then
      pushUndo()
      joinLines(line)

  private def editLine(from: Int, removed: Int, inserted: String): Unit =
    val (edited, mappedColumn) = GraphemeEdit.splice(lines(line), from, removed, inserted)
    lines = lines.updated(line, edited)
    column = mappedColumn

  private def joinLines(first: Int): Unit =
    val (joined, mappedColumn) = GraphemeEdit.joined(lines(first).mkString, "", lines(first + 1).mkString)
    lines = lines.updated(first, joined).patch(first + 1, Nil, 1)
    line = first
    column = mappedColumn

  def moveLeft(): Unit =
    if column > 0 then column -= 1
    else if line > 0 then
      line -= 1
      column = lines(line).size

  def moveRight(): Unit =
    if column < lines(line).size then column += 1
    else if line < lines.size - 1 then
      line += 1
      column = 0

  def moveUp(): Unit =
    if line > 0 then
      line -= 1
      column = math.min(column, lines(line).size)

  def moveDown(): Unit =
    if line < lines.size - 1 then
      line += 1
      column = math.min(column, lines(line).size)

  def moveHome(): Unit = column = 0

  def moveEnd(): Unit = column = lines(line).size

  /** Restores the text and cursor from before the most recent edit; no-op on an empty history. */
  def undo(): Unit =
    if undoStack.nonEmpty then
      redoStack.push((lines, line, column))
      val (savedLines, savedLine, savedColumn) = undoStack.pop()
      lines = savedLines
      line = savedLine
      column = savedColumn

  /** Re-applies the most recently undone edit; a fresh edit clears the redo history. */
  def redo(): Unit =
    if redoStack.nonEmpty then
      undoStack.push((lines, line, column))
      val (savedLines, savedLine, savedColumn) = redoStack.pop()
      lines = savedLines
      line = savedLine
      column = savedColumn

  private def pushUndo(): Unit =
    redoStack.clear()
    undoStack.push((lines, line, column))
    // the stack's bottom holds the oldest snapshots; drop from there so the newest `UndoLimit` survive
    while undoStack.size > TextAreaState.UndoLimit do undoStack.remove(undoStack.size - 1)

object TextAreaState:
  private val UndoLimit = 100

/** A multi-line text editor view: vertical and horizontal scroll follow the cursor, which renders as a highlighted cell
  * (`showCursor = false` for unfocused areas). No syntax highlighting.
  */
final case class TextArea(
    showCursor: Boolean = true,
    style: Style = Style.Default,
    cursorStyle: Style = Style.Default.reverse,
) extends StatefulWidget[TextAreaState]:

  def render(area: Rect, buffer: Buffer, state: TextAreaState): Unit =
    if !area.isEmpty then
      val (cursorLine, cursorColumn) = state.cursor
      state.scrollRow = ScrollWindow.offsetFor(state.scrollRow, Some(cursorLine), state.clusterLines.size, area.height)
      state.scrollColumn = scrolledHorizontally(state, cursorColumn, area.width)
      state.clusterLines.slice(state.scrollRow, state.scrollRow + area.height).zipWithIndex.foreach { (clusters, row) =>
        val lineIndex = state.scrollRow + row
        renderLine(buffer, area, clusters, area.y + row, state, lineIndex == cursorLine)
      }

  /** Paints one visible line. The blank one past the last cluster is left untouched unless the cursor sits on it, so an
    * editor does not paint its own background over whatever is behind the end of a short line — see [[ClusterRow.draw]]
    * for the wide-glyph and cursor rules.
    */
  private def renderLine(
      buffer: Buffer,
      area: Rect,
      clusters: Vector[String],
      y: Int,
      state: TextAreaState,
      isCursorLine: Boolean,
  ): Unit =
    val (_, cursorColumn)    = state.cursor
    // The offset is a column count shared by every line, and this line's clusters may not have a boundary there: a
    // cluster straddling the offset is skipped and the row starts that much further right, so the columns of all the
    // lines still line up. Counting the offset in clusters instead — which is what this used to pass — scrolled a line
    // of wide characters roughly twice as far as the ASCII lines around it, and often clean off the screen.
    val (firstCluster, from) = ClusterRow.clusterAtColumn(clusters, state.scrollColumn)
    ClusterRow.draw(
      buffer,
      x0 = area.x + (from - state.scrollColumn),
      y = y,
      right = area.right,
      clusters = clusters,
      scroll = firstCluster,
      cursorAt = cursorColumn,
      showCursor = showCursor && isCursorLine,
      style = style,
      cursorStyle = cursorStyle,
      paintEndCell = false,
    )

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
  private def scrolledHorizontally(state: TextAreaState, cursorColumn: Int, width: Int): Int =
    val (cursorLine, _) = state.cursor
    val clusters        = state.clusterLines(cursorLine)
    val (scrollFrom, _) = ClusterRow.clusterAtColumn(clusters, state.scrollColumn)
    val scrolledTo      = ClusterRow.scrolledTo(clusters, scrollFrom, cursorColumn, width)
    ClusterRow.columnOfCluster(clusters, scrolledTo)
