package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Buffer, CharWidth, Rect}

import java.util.regex.Pattern

/** One frame after it reached the terminal: the exact cells that were flushed, the area they covered, and which frame
  * of the run this was.
  *
  * `buffer` is a snapshot, not the composer's live buffer. The composer reuses one buffer for as long as the terminal
  * keeps its size, so handing out the live one would give an observer a value that quietly changes underneath it on the
  * next frame. Retaining this snapshot is safe, and is the point. The snapshot isolates later rendering, not caller
  * mutation: `Buffer` is publicly mutable. Treat it as read-only when sharing a frame with another thread. `lines` and
  * `text` intentionally recompute rather than caching a result that mutation could make stale.
  *
  * `count` is the same number the [[Frame]] carried: the first frame of a run is `0`. It counts *composed* frames, so a
  * frame the backend's diff turned into no output at all is still counted here.
  *
  * Handed to [[RunnerConfig.onFrame]] on the render thread, inside the frame it describes. Keep the body short and do
  * not block in it: the loop is not reading input while it runs.
  */
final case class CompletedFrame(buffer: Buffer, area: Rect, count: Long):

  /** The frame as one string per row, trailing blanks stripped — for a log line, a bug report, or an "export what is on
    * screen" command. A wide grapheme occupies one entry and its continuation columns none, so the text matches what
    * the terminal shows rather than how many cells it took.
    */
  def lines: Seq[String] = FrameText.lines(buffer)

  /** [[lines]] joined with newlines. */
  def text: String = lines.mkString("\n")

/** Turns a flushed [[Buffer]] into plain text.
  *
  * Deliberately a second, small implementation rather than a call into `BufferAssertions.trimmedLines`: `test-support`
  * depends on `runtime`, not the other way round, so sharing one owner would mean moving a text-formatting helper down
  * into `core`'s published surface — a bigger change than the twenty lines here. `CompletedFrameSpec` asserts the two
  * agree on the same buffer, which is what keeps them from drifting.
  */
private[runtime] object FrameText:

  /** Whitespace at the end of a row, compiled once rather than per row of every frame. */
  private val TrailingSpaces: Pattern = Pattern.compile("\\s+$")

  def lines(buffer: Buffer): Seq[String] =
    for y <- buffer.area.y until buffer.area.bottom
    yield TrailingSpaces.matcher(rowText(buffer, y)).replaceFirst("")

  /** One row as a string, stepping over the continuation columns a wide grapheme occupies.
    *
    * The step comes from `CharWidth`, never from the symbol's length: a CJK ideograph is one character and two columns,
    * and an emoji built from several code points joined by zero-width joiners is many characters and two columns.
    */
  private def rowText(buffer: Buffer, y: Int): String =
    val row = StringBuilder()
    var x   = buffer.area.x
    while x < buffer.area.right do
      val cell = buffer.get(x, y)
      row ++= cell.symbol
      x += math.max(1, CharWidth.of(cell.symbol))
    row.result()
