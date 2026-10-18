package io.worxbend.tui.widgets

import io.worxbend.tui.core.Text

/** The editing engine [[TextInputState]] and [[TextAreaState]] share: grapheme-cluster lines, a caret, and coalesced
  * diff-based undo.
  *
  * Text is a vector of lines, each a vector of grapheme clusters, so the caret — a [[TextPosition]] — can never split a
  * combining sequence or an emoji. [[GraphemeEdit]] stays the splice owner: every edit re-segments the lines it touches
  * and maps the caret with right affinity, so a splice inside a newly merged grapheme lands after it.
  *
  * Control characters are dropped on the way in, by the constructor as well as by [[insert]]; a multi-line buffer keeps
  * the `\n` that separates lines, a single-line one drops it with the rest.
  *
  * Undo stores diffs, not snapshots, and coalesces consecutive single-grapheme inserts (and consecutive deletes) into
  * one step — see [[UndoLog]] for the grouping rule. A caret move closes the group.
  *
  * Render-thread-only, and mutating it does not by itself schedule a frame: pair a mutation made outside an event
  * handler with a `Signal` write or `TuiApp.requestRedraw()`.
  */
abstract class EditBuffer(initial: String, multiline: Boolean):

  private var lines: Fragment.Lines = linesOf(initial)
  private var head: TextPosition    = TextPosition(lines.size - 1, lines.last.size)
  private val history               = UndoLog(EditBuffer.UndoLimit)

  /** The whole text, lines joined with `\n`. */
  def value: String = Fragment.text(lines)

  /** Where the caret is. */
  def caret: TextPosition = head

  private[widgets] def clusterLines: Vector[Vector[String]] = lines

  /** Clusters and line breaks the undo history retains — an instrumentation hook, see [[UndoLog.footprint]]. */
  private[widgets] def undoFootprint: Int = history.footprint

  // --- editing ---------------------------------------------------------------------------------------------------

  /** Inserts `text` at the caret. A single grapheme typed after the previous one joins its undo step. */
  def insert(text: String): Unit =
    val kind =
      if !text.contains('\n') && GraphemeEdit.clustersOf(text).size == 1 then EditKind.Typing else EditKind.Other
    replaceRange(head, head, text, kind)

  /** Deletes the grapheme before the caret, joining with the previous line at column zero. */
  def backspace(): Unit =
    val from = stepLeft(head)
    if from != head then replaceRange(from, head, "", EditKind.DeleteBackward)

  /** Deletes the grapheme under the caret, joining with the next line at the end of a line. */
  def delete(): Unit =
    val to = stepRight(head)
    if to != head then replaceRange(head, to, "", EditKind.DeleteForward)

  /** Restores the text and caret from before the most recent undo step; no-op on an empty history. */
  def undo(): Unit =
    history.popUndo().foreach { diff =>
      lines = Fragment.replace(lines, diff.start, diff.inserted, diff.removed)
      head = diff.caretBefore
    }

  /** Re-applies the most recently undone step; a fresh edit clears the redo history. */
  def redo(): Unit =
    history.popRedo().foreach { diff =>
      lines = Fragment.replace(lines, diff.start, diff.removed, diff.inserted)
      head = diff.caretAfter
    }

  /** Empties the text and forgets the undo history. */
  def clear(): Unit =
    lines = Fragment.Empty
    head = TextPosition(0, 0)
    history.clear()

  // --- motion ----------------------------------------------------------------------------------------------------

  def moveLeft(): Unit = moveTo(stepLeft(head))

  def moveRight(): Unit = moveTo(stepRight(head))

  def moveUp(): Unit =
    if head.line > 0 then moveTo(TextPosition(head.line - 1, math.min(head.column, lines(head.line - 1).size)))

  def moveDown(): Unit =
    if head.line < lines.size - 1 then
      moveTo(TextPosition(head.line + 1, math.min(head.column, lines(head.line + 1).size)))

  def moveHome(): Unit = moveTo(TextPosition(head.line, 0))

  def moveEnd(): Unit = moveTo(TextPosition(head.line, lines(head.line).size))

  /** Puts the caret at `position`, clamped into the text. */
  def moveTo(position: TextPosition): Unit =
    history.breakGroup()
    head = clamp(position)

  // --- internals -------------------------------------------------------------------------------------------------

  private def clamp(position: TextPosition): TextPosition =
    val line = math.max(0, math.min(position.line, lines.size - 1))
    TextPosition(line, math.max(0, math.min(position.column, lines(line).size)))

  private def stepLeft(at: TextPosition): TextPosition =
    if at.column > 0 then at.copy(column = at.column - 1)
    else if at.line > 0 then TextPosition(at.line - 1, lines(at.line - 1).size)
    else at

  private def stepRight(at: TextPosition): TextPosition =
    if at.column < lines(at.line).size then at.copy(column = at.column + 1)
    else if at.line < lines.size - 1 then TextPosition(at.line + 1, 0)
    else at

  /** Splits `text` the way this buffer stores it: per line for a multi-line buffer, dropping controls *after* the split
    * so the `\n` doing the splitting survives. Reads no field, so the field initializer may call it.
    */
  private def linesOf(text: String): Fragment.Lines =
    if multiline then Text.splitLines(text).toVector.map(GraphemeEdit.clustersOf)
    else Vector(GraphemeEdit.clustersOf(text))

  /** The one edit primitive: replaces `[from, to)` with `text`, re-segmenting through [[GraphemeEdit]], and records the
    * clusters that actually changed as one [[TextDiff]].
    */
  private[widgets] def replaceRange(from: TextPosition, to: TextPosition, text: String, kind: EditKind): Unit =
    val segments                = linesOf(text)
    val before                  = lines(from.line).take(from.column).mkString
    val after                   = lines(to.line).drop(to.column)
    val (affected, caretColumn) =
      if segments.size == 1 then
        val (joined, column) = GraphemeEdit.joined(before, segments.head.mkString, after.mkString)
        (Vector(joined), column)
      else
        val (first, _)     = GraphemeEdit.joined(before, segments.head.mkString, "")
        val (last, column) = GraphemeEdit.joined("", segments.last.mkString, after.mkString)
        ((first +: segments.slice(1, segments.size - 1)) :+ last, column)
    val old                     = lines.slice(from.line, to.line + 1)
    val caretBefore             = head
    lines = lines.take(from.line) ++ affected ++ lines.drop(to.line + 1)
    head = TextPosition(from.line + affected.size - 1, caretColumn)
    val diff                    = trimmed(from, old, affected, after.size, caretBefore, kind)
    if diff.removed != diff.inserted then history.record(diff)

  /** The diff between the `old` and `fresh` runs of whole lines starting at `from.line`, trimmed to the clusters that
    * differ: the shared prefix may not reach past the edit's start, nor the shared suffix into the edit itself.
    */
  private def trimmed(
      from: TextPosition,
      old: Fragment.Lines,
      fresh: Fragment.Lines,
      untouchedTail: Int,
      caretBefore: TextPosition,
      kind: EditKind,
  ): TextDiff =
    val prefix                      = old.head.iterator.zip(fresh.head.iterator).takeWhile(_ == _).size.min(from.column)
    def room(lines: Fragment.Lines) = if lines.size == 1 then lines.head.size - prefix else lines.last.size
    val suffix                      = old.last.reverseIterator
      .zip(fresh.last.reverseIterator)
      .takeWhile(_ == _)
      .size
      .min(untouchedTail)
      .min(room(old))
      .min(room(fresh))
    def cut(lines: Fragment.Lines): Fragment.Lines =
      if lines.size == 1 then Vector(lines.head.slice(prefix, lines.head.size - suffix))
      else (lines.head.drop(prefix) +: lines.slice(1, lines.size - 1)) :+ lines.last.dropRight(suffix)
    TextDiff(TextPosition(from.line, prefix), cut(old), cut(fresh), caretBefore, head, kind)

object EditBuffer:
  private val UndoLimit = 100
