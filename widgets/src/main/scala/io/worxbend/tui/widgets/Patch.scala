package io.worxbend.tui.widgets

import io.worxbend.tui.core.Text

import scala.util.matching.Regex

/** One line of a [[Hunk]]: unchanged context, a line only the new side has, or a line only the old side had. `text` is
  * the line without its `' '`/`'+'`/`'-'` marker and without a line terminator.
  */
enum DiffLine(val text: String):
  case Context(override val text: String) extends DiffLine(text)
  case Added(override val text: String)   extends DiffLine(text)
  case Removed(override val text: String) extends DiffLine(text)

/** A contiguous region of change: its first line on each side (1-based) and the lines in order.
  *
  * The line counts of the `@@ -oldStart,oldCount +newStart,newCount @@` header are derived from `lines` rather than
  * stored beside them, so a hunk cannot disagree with its own header. A side with no lines in the hunk follows the
  * unified-diff convention: its start is the number of the line *before* the hunk (`0` at the top of a file). `section`
  * is the free text git prints after the closing `@@` — usually the enclosing function — or empty.
  */
final case class Hunk(oldStart: Int, newStart: Int, lines: Seq[DiffLine], section: String = ""):

  /** How many old-side lines the hunk covers: its context and removed lines. */
  def oldCount: Int = lines.count:
    case DiffLine.Added(_) => false
    case _                 => true

  /** How many new-side lines the hunk covers: its context and added lines. */
  def newCount: Int = lines.count:
    case DiffLine.Removed(_) => false
    case _                   => true

/** The hunks of one file, with the paths its `---`/`+++` header named — `None` for `/dev/null` (an added or deleted
  * file) or when there was no header at all, as for a diff computed by [[Patch.of]].
  */
final case class FilePatch(oldPath: Option[String], newPath: Option[String], hunks: Seq[Hunk])

/** A unified diff: one [[FilePatch]] per file, in order — what `git diff` prints, read by [[Patch.parse]], or the
  * difference between two texts computed by [[Patch.of]]. An immutable value, safe to share between threads; drawn by
  * [[DiffView]].
  *
  * `approximate` is `true` for a [[Patch.of]] that passed its edit-distance cap: the patch still turns the old text
  * into the new one, but the part the cap cut short is one block of removals followed by one of additions rather than a
  * line-by-line comparison. A [[DiffView]] says so on its first row.
  */
final case class Patch(files: Seq[FilePatch], approximate: Boolean = false):

  /** Every hunk of every file, in order. */
  def hunks: Seq[Hunk] = files.flatMap(_.hunks)

  /** The patch as unified-diff text, one `\n`-terminated line each: a `--- a/…`/`+++ b/…` header for every file that
    * has a path on either side (`/dev/null` for the missing one), then its hunks. [[Patch.parse]] reads it back to an
    * equal patch. git's extended headers (`diff --git`, `index`, modes) are not kept by the parser and not written
    * here.
    */
  def unified: String =
    val out                      = StringBuilder()
    def emit(line: String): Unit = out.append(line).append('\n')
    files.foreach { file =>
      if file.oldPath.isDefined || file.newPath.isDefined then
        emit("--- " + file.oldPath.fold(Patch.DevNull)("a/" + _))
        emit("+++ " + file.newPath.fold(Patch.DevNull)("b/" + _))
      file.hunks.foreach { hunk =>
        emit(Patch.header(hunk))
        hunk.lines.foreach {
          case DiffLine.Context(text) => emit(" " + text)
          case DiffLine.Added(text)   => emit("+" + text)
          case DiffLine.Removed(text) => emit("-" + text)
        }
      }
    }
    out.result()

/** Why [[Patch.parse]] gave up: `line` is the 1-based line of the input it could not make sense of. */
final case class PatchError(line: Int, message: String)

object Patch:

  /** A patch with nothing in it. */
  val empty: Patch = Patch(Seq.empty)

  private val DevNull: String = "/dev/null"

  private val HunkHeader: Regex = """^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@ ?(.*)$""".r

  /** The `@@ -s,c +s,c @@ section` line of `hunk`, with a count of 1 left out the way git leaves it out. */
  private[widgets] def header(hunk: Hunk): String =
    def range(start: Int, count: Int): String = if count == 1 then start.toString else s"$start,$count"
    val section                               = if hunk.section.isEmpty then "" else " " + hunk.section
    s"@@ -${range(hunk.oldStart, hunk.oldCount)} +${range(hunk.newStart, hunk.newCount)} @@$section"

  /** Reads unified-diff text — `git diff`, `diff -u`, a `.patch` file — into a [[Patch]].
    *
    * Each `---`/`+++` pair starts a file; a leading `a/` or `b/` and anything after a tab (a timestamp) are dropped
    * from the path, and `/dev/null` reads as no path. Each `@@` header starts a hunk whose body must hold exactly the
    * lines its counts promise (an omitted count is 1); an empty body line counts as an empty context line, since some
    * tools strip the trailing space. `\ No newline at end of file` markers and everything outside a hunk — git's
    * extended headers, a commit message — are skipped. A body that ends early, a line with no marker inside a body, or
    * a header that does not parse is a [[PatchError]] naming the input line.
    */
  def parse(text: String): Either[PatchError, Patch] =
    val input   = if text.isEmpty then IndexedSeq.empty else Text.splitLines(text.stripSuffix("\n")).toIndexedSeq
    val files   = Vector.newBuilder[FilePatch]
    var oldPath = Option.empty[String]
    var newPath = Option.empty[String]
    var hunks   = Vector.empty[Hunk]
    var open    = false
    var error   = Option.empty[PatchError]
    var index   = 0

    def closeFile(): Unit =
      if open then files += FilePatch(oldPath, newPath, hunks)
      oldPath = None
      newPath = None
      hunks = Vector.empty
      open = false

    while error.isEmpty && index < input.size do
      val line = input(index).stripSuffix("\r")
      if line.startsWith("--- ") && index + 1 < input.size && input(index + 1).startsWith("+++ ") then
        closeFile()
        oldPath = path(line)
        newPath = path(input(index + 1).stripSuffix("\r"))
        open = true
        index += 2
      else if line.startsWith("@@") then
        line match
          case HunkHeader(oldStart, oldCount, newStart, newCount, section) =>
            val expectedOld = Option(oldCount).fold(1)(_.toInt)
            val expectedNew = Option(newCount).fold(1)(_.toInt)
            val body        = Vector.newBuilder[DiffLine]
            var seenOld     = 0
            var seenNew     = 0
            index += 1
            while error.isEmpty && (seenOld < expectedOld || seenNew < expectedNew) do
              if index >= input.size then error = Some(PatchError(index + 1, "the hunk ends before its header said"))
              else
                val bodyLine = input(index).stripSuffix("\r")
                // the marker is one ASCII character of the wire format, so dropping one char is exact
                bodyLine.headOption match
                  case None | Some(' ') =>
                    body += DiffLine.Context(bodyLine.drop(1))
                    seenOld += 1
                    seenNew += 1
                  case Some('+')        =>
                    body += DiffLine.Added(bodyLine.drop(1))
                    seenNew += 1
                  case Some('-')        =>
                    body += DiffLine.Removed(bodyLine.drop(1))
                    seenOld += 1
                  case Some('\\')       => ()
                  case Some(_) => error = Some(PatchError(index + 1, s"expected a hunk line, found \"$bodyLine\""))
                index += 1
            if error.isEmpty && (seenOld > expectedOld || seenNew > expectedNew) then
              error = Some(PatchError(index, "the hunk holds more lines than its header said"))
            hunks :+= Hunk(oldStart.toInt, newStart.toInt, body.result(), section)
            open = true
          case _                                                           =>
            error = Some(PatchError(index + 1, s"malformed hunk header \"$line\""))
      else index += 1
    closeFile()
    error.toLeft(Patch(files.result()))

  private def path(header: String): Option[String] =
    val name = header.drop(4).takeWhile(_ != '\t')
    if name == DevNull then None
    else Some(if name.startsWith("a/") || name.startsWith("b/") then name.drop(2) else name)

  /** The line-based difference between `before` and `after`, as one headerless [[FilePatch]], with `context` unchanged
    * lines kept around each change — 3, the `git diff` default. Changes whose context windows touch share a hunk.
    * Computed with Myers' algorithm, which finds a shortest edit script and, among the shortest, prefers to delete
    * before it inserts — as long as that script needs at most `maxEditDistance` added plus removed lines
    * ([[DefaultMaxEditDistance]]). Past that the common prefix and suffix are still matched, everything between them is
    * one removal followed by one addition, and the patch is [[Patch.approximate]]: valid, bounded in memory, not
    * shortest.
    *
    * A text is split on `\n` (a `\r` before it is dropped), and one trailing newline ends the last line rather than
    * starting an empty one — so `"a\n"` and `"a"` compare equal, and the "no newline at end of file" distinction is not
    * reported.
    */
  def of(before: String, after: String, context: Int = 3, maxEditDistance: Int = DefaultMaxEditDistance): Patch =
    require(context >= 0, s"context must not be negative, was $context")
    require(maxEditDistance >= 0, s"maxEditDistance must not be negative, was $maxEditDistance")
    val oldLines       = lines(before)
    val newLines       = lines(after)
    val (edits, exact) = MyersDiff.diffBounded(oldLines, newLines, maxEditDistance)
    val changes        = edits.indices.filter(index => edits(index).op != MyersDiff.Op.Equal)
    if changes.isEmpty then empty
    else
      val groups = changes.tail.foldLeft(Vector((changes.head, changes.head))) { case (acc, change) =>
        val (first, last) = acc.last
        if change - last - 1 <= 2 * context then acc.init :+ (first, change) else acc :+ (change, change)
      }
      val hunks  = groups.map { (first, last) =>
        val from      = math.max(0, first - context)
        val until     = math.min(edits.size, last + context + 1)
        val oldBefore = edits.take(from).count(_.op != MyersDiff.Op.Insert)
        val newBefore = edits.take(from).count(_.op != MyersDiff.Op.Delete)
        val body      = edits.slice(from, until).map { edit =>
          edit.op match
            case MyersDiff.Op.Equal  => DiffLine.Context(oldLines(edit.oldIndex))
            case MyersDiff.Op.Delete => DiffLine.Removed(oldLines(edit.oldIndex))
            case MyersDiff.Op.Insert => DiffLine.Added(newLines(edit.newIndex))
        }
        val hunk      = Hunk(oldBefore, newBefore, body)
        hunk.copy(
          oldStart = if hunk.oldCount == 0 then oldBefore else oldBefore + 1,
          newStart = if hunk.newCount == 0 then newBefore else newBefore + 1,
        )
      }
      Patch(Seq(FilePatch(None, None, hunks)), approximate = !exact)

  /** The edit distance past which [[of]] stops looking for a shortest diff: 2 000 added plus removed lines. The
    * search's memory grows with the square of the distance — about 16 MB at this cap, where two unrelated 50 000-line
    * texts would have wanted gigabytes — so past it the unmatched middle becomes one replacement and the patch is
    * marked [[Patch.approximate]].
    */
  val DefaultMaxEditDistance: Int = 2000

  private def lines(text: String): IndexedSeq[String] =
    if text.isEmpty then IndexedSeq.empty
    else Text.splitLines(text.stripSuffix("\n")).map(_.stripSuffix("\r")).toIndexedSeq
