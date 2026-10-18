package io.worxbend.tui.widgets

import scala.collection.mutable

/** A position in an [[EditBuffer]]: a zero-based line and a column counted in grapheme clusters, where the column equal
  * to the line's length means "end of line". Ordered by line, then column.
  */
final case class TextPosition(line: Int, column: Int) extends Ordered[TextPosition]:
  def compare(that: TextPosition): Int =
    if line != that.line then Integer.compare(line, that.line) else Integer.compare(column, that.column)

/** A piece of an editor's text as cluster lines: one entry is a run within a line, each further entry is one line break
  * further down. Never empty — the empty fragment is `Vector(Vector.empty)`.
  */
private[widgets] object Fragment:
  type Lines = Vector[Vector[String]]

  val Empty: Lines = Vector(Vector.empty)

  /** The position just past `fragment` when it is laid down at `start`. */
  def end(start: TextPosition, fragment: Lines): TextPosition =
    if fragment.size == 1 then TextPosition(start.line, start.column + fragment.head.size)
    else TextPosition(start.line + fragment.size - 1, fragment.last.size)

  /** Clusters plus line breaks — the unit an [[UndoLog]] measures its footprint in. */
  def weight(fragment: Lines): Int = fragment.map(_.size).sum + fragment.size - 1

  def text(fragment: Lines): String = fragment.map(_.mkString).mkString("\n")

  /** The part of `lines` between `from` and `to`. */
  def between(lines: Lines, from: TextPosition, to: TextPosition): Lines =
    if from.line == to.line then Vector(lines(from.line).slice(from.column, to.column))
    else (lines(from.line).drop(from.column) +: lines.slice(from.line + 1, to.line)) :+ lines(to.line).take(to.column)

  /** `lines` with the `removed` fragment that starts at `start` replaced by `inserted`. Pure cluster surgery: no
    * re-segmentation, which is what makes undo exact — the fragments were cut from a document that was already
    * segmented.
    */
  def replace(lines: Lines, start: TextPosition, removed: Lines, inserted: Lines): Lines =
    val stop   = end(start, removed)
    val prefix = lines(start.line).take(start.column)
    val suffix = lines(stop.line).drop(stop.column)
    val middle =
      if inserted.size == 1 then Vector(prefix ++ inserted.head ++ suffix)
      else ((prefix ++ inserted.head) +: inserted.slice(1, inserted.size - 1)) :+ (inserted.last ++ suffix)
    lines.take(start.line) ++ middle ++ lines.drop(stop.line + 1)

/** What kind of keystroke produced a [[TextDiff]] — the only thing coalescing needs to know about intent. */
private[widgets] enum EditKind:
  case Typing, DeleteBackward, DeleteForward, Other

/** One reversible change: at `start`, `removed` was replaced by `inserted`, moving the caret from `caretBefore` to
  * `caretAfter`. Both fragments hold only the clusters that differ, so a diff weighs what was typed, not the document.
  */
private[widgets] final case class TextDiff(
    start: TextPosition,
    removed: Fragment.Lines,
    inserted: Fragment.Lines,
    caretBefore: TextPosition,
    caretAfter: TextPosition,
    kind: EditKind,
)

/** The undo and redo history of one [[EditBuffer]], as diffs.
  *
  * Coalescing: while a group is open, a single-grapheme insert directly after the previous one extends that entry, and
  * so do consecutive backspaces or forward deletes — typing "hello" is one undo step. A whitespace grapheme typed after
  * a non-whitespace one opens a new group, so a sentence undoes word by word. Any caret move, selection change, undo or
  * redo closes the group ([[breakGroup]]). The history keeps the newest `limit` entries; a fresh edit clears redo.
  *
  * Owned by its buffer; render-thread-only like the buffer.
  */
private[widgets] final class UndoLog(limit: Int):
  private val undone    = mutable.ArrayDeque.empty[TextDiff]
  private val redoable  = mutable.ArrayDeque.empty[TextDiff]
  private var groupOpen = false

  def record(diff: TextDiff): Unit =
    redoable.clear()
    val merged = undone.lastOption.filter(_ => groupOpen).flatMap(coalesced(_, diff))
    merged match
      case Some(entry) => undone(undone.size - 1) = entry
      case None        =>
        undone.append(diff)
        while undone.size > limit do
          val _ = undone.removeHead()
    groupOpen = true

  def breakGroup(): Unit = groupOpen = false

  /** The newest entry, moved onto the redo stack; the caller reverts it. */
  def popUndo(): Option[TextDiff] =
    groupOpen = false
    undone.removeLastOption().map { diff =>
      redoable.append(diff)
      diff
    }

  /** The newest undone entry, moved back onto the undo stack; the caller re-applies it. */
  def popRedo(): Option[TextDiff] =
    groupOpen = false
    redoable.removeLastOption().map { diff =>
      undone.append(diff)
      diff
    }

  def clear(): Unit =
    undone.clear()
    redoable.clear()
    groupOpen = false

  /** Clusters and line breaks retained across both stacks — what an instrumented test asserts on. */
  def footprint: Int =
    (undone.iterator ++ redoable.iterator)
      .map(diff => Fragment.weight(diff.removed) + Fragment.weight(diff.inserted))
      .sum

  private def isBlank(cluster: String): Boolean = cluster.codePoints().allMatch(Character.isWhitespace)

  private def singleCluster(fragment: Fragment.Lines): Boolean = fragment.size == 1 && fragment.head.size == 1

  private def coalesced(last: TextDiff, next: TextDiff): Option[TextDiff] =
    (last.kind, next.kind) match
      case (EditKind.Typing, EditKind.Typing)
          if last.removed == Fragment.Empty && next.removed == Fragment.Empty && last.inserted.size == 1 &&
            singleCluster(next.inserted) && Fragment.end(last.start, last.inserted) == next.start &&
            !(isBlank(next.inserted.head.head) && last.inserted.head.lastOption.exists(c => !isBlank(c))) =>
        Some(last.copy(inserted = Vector(last.inserted.head ++ next.inserted.head), caretAfter = next.caretAfter))
      case (EditKind.DeleteBackward, EditKind.DeleteBackward)
          if last.inserted == Fragment.Empty && next.inserted == Fragment.Empty && last.removed.size == 1 &&
            singleCluster(next.removed) && Fragment.end(next.start, next.removed) == last.start =>
        Some(
          last.copy(
            start = next.start,
            removed = Vector(next.removed.head ++ last.removed.head),
            caretAfter = next.caretAfter,
          )
        )
      case (EditKind.DeleteForward, EditKind.DeleteForward)
          if last.inserted == Fragment.Empty && next.inserted == Fragment.Empty && last.removed.size == 1 &&
            singleCluster(next.removed) && next.start == last.start =>
        Some(last.copy(removed = Vector(last.removed.head ++ next.removed.head), caretAfter = next.caretAfter))
      case _ => None
