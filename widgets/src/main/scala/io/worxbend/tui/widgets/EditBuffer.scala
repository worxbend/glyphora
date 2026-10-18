package io.worxbend.tui.widgets

import io.worxbend.tui.core.Text

import java.text.BreakIterator
import java.util.Locale

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
  * Selection is an anchor plus the caret (the head): every motion takes `select = true` to stretch it, and typing,
  * deleting or pasting replaces it. Word motion uses Unicode word boundaries (`java.text.BreakIterator`). Cut, copy and
  * paste go through a [[Clipboard]] the caller supplies; kills go into `killRing`, which several buffers may share.
  *
  * Render-thread-only, and mutating it does not by itself schedule a frame: pair a mutation made outside an event
  * handler with a `Signal` write or `TuiApp.requestRedraw()`.
  */
abstract class EditBuffer(initial: String, multiline: Boolean, killRing: KillRing):

  private var lines: Fragment.Lines        = linesOf(initial)
  private var head: TextPosition           = TextPosition(lines.size - 1, lines.last.size)
  private var anchor: Option[TextPosition] = None
  private var last: LastCommand            = LastCommand.Other
  private val history                      = UndoLog(EditBuffer.UndoLimit)

  /** The whole text, lines joined with `\n`. */
  def value: String = Fragment.text(lines)

  /** Where the caret is — the selection's moving end when there is one. */
  def caret: TextPosition = head

  /** The selected range as `(start, end)`, start first whichever way it was dragged; `None` when nothing is selected.
    */
  def selection: Option[(TextPosition, TextPosition)] =
    anchor.filter(_ != head).map(a => if a < head then (a, head) else (head, a))

  /** The selected text, lines joined with `\n`; empty when nothing is selected. */
  def selectedText: String = selection.fold("")((from, to) => Fragment.text(Fragment.between(lines, from, to)))

  /** The kill ring this buffer kills into and yanks from. */
  def kills: KillRing = killRing

  private[widgets] def clusterLines: Vector[Vector[String]] = lines

  /** Clusters and line breaks the undo history retains — an instrumentation hook, see [[UndoLog.footprint]]. */
  private[widgets] def undoFootprint: Int = history.footprint

  // --- editing ---------------------------------------------------------------------------------------------------

  /** Inserts `text` at the caret, replacing the selection if there is one. A single grapheme typed after the previous
    * one joins its undo step.
    */
  def insert(text: String): Unit =
    selection match
      case Some((from, to)) => replaceRange(from, to, text, EditKind.Other)
      case None             =>
        val typing = !text.contains('\n') && GraphemeEdit.clustersOf(text).size == 1
        replaceRange(head, head, text, if typing then EditKind.Typing else EditKind.Other)

  /** Deletes the selection, or else the grapheme before the caret, joining with the previous line at column zero. */
  def backspace(): Unit =
    if !deleteSelection() then
      val from = stepLeft(head)
      if from != head then replaceRange(from, head, "", EditKind.DeleteBackward)

  /** Deletes the selection, or else the grapheme under the caret, joining with the next line at the end of a line. */
  def delete(): Unit =
    if !deleteSelection() then
      val to = stepRight(head)
      if to != head then replaceRange(head, to, "", EditKind.DeleteForward)

  /** Restores the text and caret from before the most recent undo step; no-op on an empty history. */
  def undo(): Unit =
    history.popUndo().foreach { diff =>
      lines = Fragment.replace(lines, diff.start, diff.inserted, diff.removed)
      settle(diff.caretBefore)
    }

  /** Re-applies the most recently undone step; a fresh edit clears the redo history. */
  def redo(): Unit =
    history.popRedo().foreach { diff =>
      lines = Fragment.replace(lines, diff.start, diff.removed, diff.inserted)
      settle(diff.caretAfter)
    }

  /** Closes the current undo group, so the next keystroke starts a step of its own. */
  def breakUndoGroup(): Unit = history.breakGroup()

  /** Empties the text and forgets the undo history; the kill ring is kept. */
  def clear(): Unit =
    lines = Fragment.Empty
    settle(TextPosition(0, 0))
    history.clear()

  /** Replaces the whole text with `text`, caret at its end, as one undoable step. */
  def setValue(text: String): Unit =
    replaceRange(TextPosition(0, 0), endOfText, text, EditKind.Other)

  // --- clipboard -------------------------------------------------------------------------------------------------

  /** Copies the selection to `clipboard`; does nothing when nothing is selected. */
  def copy(clipboard: Clipboard): Unit = selection.foreach(_ => clipboard.copy(selectedText))

  /** Copies the selection to `clipboard` and deletes it. */
  def cut(clipboard: Clipboard): Unit =
    copy(clipboard)
    val _ = deleteSelection()

  /** Inserts the clipboard's content at the caret, replacing the selection. */
  def paste(clipboard: Clipboard): Unit = clipboard.paste().foreach { text =>
    history.breakGroup()
    insert(text)
    history.breakGroup()
  }

  // --- kill ring -------------------------------------------------------------------------------------------------

  /** `Ctrl+K`: kills from the caret to the end of the line — or the line break itself when already there. */
  def killToEndOfLine(): Unit =
    val to =
      if head.column < lines(head.line).size then TextPosition(head.line, lines(head.line).size) else stepRight(head)
    kill(head, to, backward = false)

  /** `Ctrl+U`: kills from the start of the line to the caret — or the line break before it when already there. */
  def killToStartOfLine(): Unit =
    val from = if head.column > 0 then TextPosition(head.line, 0) else stepLeft(head)
    kill(from, head, backward = true)

  /** `Ctrl+W`: kills the selection, or else back to the start of the word before the caret. */
  def deleteWordBackward(): Unit =
    selection match
      case Some((from, to)) => kill(from, to, backward = true)
      case None             => kill(wordLeftOf(head), head, backward = true)

  /** `Alt+D`: kills forward to the end of the word after the caret. */
  def deleteWordForward(): Unit = kill(head, wordRightOf(head), backward = false)

  /** `Ctrl+Y`: inserts the newest kill at the caret. */
  def yank(): Unit = killRing.newest.foreach { text =>
    val start = selection.fold(head)(_._1)
    history.breakGroup()
    insert(text)
    last = LastCommand.Yanked(start, 0)
  }

  /** `Alt+Y`, straight after a yank: replaces the yanked text with the next older kill, round the ring. */
  def yankPop(): Unit =
    last match
      case LastCommand.Yanked(start, steps) if killRing.size > 1 =>
        killRing.entry(steps + 1).foreach { text =>
          replaceRange(start, head, text, EditKind.Other)
          last = LastCommand.Yanked(start, steps + 1)
        }
      case _                                                     => ()

  // --- motion ----------------------------------------------------------------------------------------------------

  /** One grapheme left; with `select` the selection grows, without it a selection collapses to its start. */
  def moveLeft(select: Boolean = false): Unit =
    moveTo(if select then stepLeft(head) else selection.fold(stepLeft(head))(_._1), select)

  /** One grapheme right; with `select` the selection grows, without it a selection collapses to its end. */
  def moveRight(select: Boolean = false): Unit =
    moveTo(if select then stepRight(head) else selection.fold(stepRight(head))(_._2), select)

  def moveUp(select: Boolean = false): Unit =
    if head.line > 0 then moveTo(TextPosition(head.line - 1, math.min(head.column, lines(head.line - 1).size)), select)

  def moveDown(select: Boolean = false): Unit =
    if head.line < lines.size - 1 then
      moveTo(TextPosition(head.line + 1, math.min(head.column, lines(head.line + 1).size)), select)

  def moveHome(select: Boolean = false): Unit = moveTo(TextPosition(head.line, 0), select)

  def moveEnd(select: Boolean = false): Unit = moveTo(TextPosition(head.line, lines(head.line).size), select)

  /** To the start of the word before the caret (Unicode word boundaries, `java.text.BreakIterator`). */
  def moveWordLeft(select: Boolean = false): Unit = moveTo(wordLeftOf(head), select)

  /** To the end of the word after the caret. */
  def moveWordRight(select: Boolean = false): Unit = moveTo(wordRightOf(head), select)

  /** To the blank line above the current paragraph, or the start of the text. */
  def moveParagraphUp(select: Boolean = false): Unit =
    var index = head.line - 1
    while index >= 0 && isBlank(index) do index -= 1
    while index >= 0 && !isBlank(index) do index -= 1
    moveTo(TextPosition(math.max(0, index), 0), select)

  /** To the blank line below the current paragraph, or the end of the text. */
  def moveParagraphDown(select: Boolean = false): Unit =
    var index = head.line + 1
    while index < lines.size && isBlank(index) do index += 1
    while index < lines.size && !isBlank(index) do index += 1
    moveTo(if index < lines.size then TextPosition(index, 0) else endOfText, select)

  /** To the very start of the text. */
  def moveToStart(select: Boolean = false): Unit = moveTo(TextPosition(0, 0), select)

  /** To the very end of the text. */
  def moveToEnd(select: Boolean = false): Unit = moveTo(endOfText, select)

  /** Selects the whole text, caret at its end. */
  def selectAll(): Unit =
    moveTo(TextPosition(0, 0))
    moveTo(endOfText, select = true)

  /** Drops the selection, leaving the caret where it is. */
  def clearSelection(): Unit = anchor = None

  /** Puts the caret at `position`, clamped into the text; with `select`, the selection stretches from where it was
    * anchored (or from the old caret) to there.
    */
  def moveTo(position: TextPosition, select: Boolean = false): Unit =
    if select then { if anchor.isEmpty then anchor = Some(head) }
    else anchor = None
    history.breakGroup()
    last = LastCommand.Other
    head = clamp(position)

  // --- internals -------------------------------------------------------------------------------------------------

  private def endOfText: TextPosition = TextPosition(lines.size - 1, lines.last.size)

  private def isBlank(line: Int): Boolean = lines(line).forall(_.codePoints().allMatch(Character.isWhitespace))

  private def settle(position: TextPosition): Unit =
    anchor = None
    last = LastCommand.Other
    head = clamp(position)

  private def deleteSelection(): Boolean =
    selection match
      case Some((from, to)) =>
        replaceRange(from, to, "", EditKind.Other)
        true
      case None             => false

  private def kill(from: TextPosition, to: TextPosition, backward: Boolean): Unit =
    if from != to then
      val text = Fragment.text(Fragment.between(lines, from, to))
      if last == LastCommand.Killed then killRing.accumulate(text, backward) else killRing.push(text)
      replaceRange(from, to, "", EditKind.Other)
      last = LastCommand.Killed

  private def wordLeftOf(at: TextPosition): TextPosition =
    if at.column == 0 then stepLeft(at)
    else TextPosition(at.line, WordSpans.of(lines(at.line)).filter(_._1 < at.column).lastOption.fold(0)(_._1))

  private def wordRightOf(at: TextPosition): TextPosition =
    val size = lines(at.line).size
    if at.column == size then stepRight(at)
    else TextPosition(at.line, WordSpans.of(lines(at.line)).find(_._2 > at.column).fold(size)(_._2))

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
    settle(TextPosition(from.line + affected.size - 1, caretColumn))
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

/** What the previous command was, for the two commands that care: a kill straight after a kill accumulates, and
  * `yankPop` only follows a yank (remembering where the yanked text starts and how far round the ring it is).
  */
private enum LastCommand:
  case Other, Killed
  case Yanked(start: TextPosition, steps: Int)

/** Word spans of one line, in cluster indices, from `java.text.BreakIterator`'s word instance: the segments holding a
  * letter or digit, so punctuation and spaces between words are skipped by word motion.
  */
private[widgets] object WordSpans:

  def of(clusters: Vector[String]): Vector[(Int, Int)] =
    val text                 = clusters.mkString
    val starts               = clusters.scanLeft(0)(_ + _.length)
    // a boundary inside a cluster snaps right, so a span never splits a grapheme
    def at(offset: Int): Int = starts.indexWhere(_ >= offset)
    val iterator             = BreakIterator.getWordInstance(Locale.ROOT)
    iterator.setText(text)
    val spans                = Vector.newBuilder[(Int, Int)]
    var from                 = iterator.first()
    var to                   = iterator.next()
    while to != BreakIterator.DONE do
      if holdsWord(text, from, to) then spans += ((at(from), at(to)))
      from = to
      to = iterator.next()
    spans.result()

  private def holdsWord(text: String, from: Int, to: Int): Boolean =
    var index = from
    var found = false
    while index < to && !found do
      val point = text.codePointAt(index)
      found = Character.isLetterOrDigit(point)
      index += Character.charCount(point)
    found
