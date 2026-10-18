package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Measured, Rect, Style, Widget}

/** The appearance of a [[MultiProgress]]: every field keeps the default the bare widget draws with.
  *
  * @param gap
  *   blank columns between two columns of a row
  * @param style
  *   the base style of every text column
  * @param descriptionStyle
  *   the label column's style, `style` when unset
  * @param fillStyle
  *   the bar's completed part
  * @param trackStyle
  *   the bar's remaining part
  * @param fillSymbol
  *   the glyph the completed part repeats
  * @param trackSymbol
  *   the glyph the remaining part repeats
  */
final case class MultiProgressOptions(
    gap: Int = 1,
    style: Style = Style.Default,
    descriptionStyle: Option[Style] = None,
    fillStyle: Style = Style.Default,
    trackStyle: Style = Style.Default.dim,
    fillSymbol: String = "█",
    trackSymbol: String = "░",
)

/** Rich/Spectre-style multi-task progress: one row per [[ProgressTask]], each row the same [[ProgressColumn]]s.
  *
  * Text columns are as wide as the widest value any row produces (so they line up down the table), measured with
  * `CharWidth`; the flexible bars share whatever the text columns and gaps leave. A bar's filled length is the task's
  * fraction of the bar's cells rounded *down*, so a bar is never full before the work is. Rows past the area's height
  * are not drawn; columns past its width are clipped.
  *
  * Stateless: the tasks are caller-owned, and the widget only reads them.
  */
final case class MultiProgress(
    tasks: Seq[ProgressTask],
    columns: Seq[ProgressColumn] = ProgressColumn.Default,
    options: MultiProgressOptions = MultiProgressOptions(),
) extends Widget
    with Measured:

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty && columns.nonEmpty then
      val visible = tasks.take(area.height)
      val texts   = visible.map(task => columns.map(_.textFor(task)))
      val widths  = columnWidths(texts, area.width)
      visible.zip(texts).zipWithIndex.foreach { case ((task, row), index) =>
        val cursor = RowCursor(buffer, area.y + index, area.x, area.right)
        columns.indices.foreach { c =>
          if c > 0 then cursor.skip(options.gap)
          columns(c) match
            case _: ProgressColumn.Bar => drawBar(cursor, task, widths(c))
            case column                => drawText(cursor, row(c), widths(c), column)
        }
      }

  /** One row per task. */
  override def heightAt(width: Int): Option[Int] =
    val _ = width
    Some(tasks.size)

  private def columnWidths(texts: Seq[Seq[String]], width: Int): IndexedSeq[Int] =
    val fixed    = columns.indices.map { c =>
      columns(c) match
        case ProgressColumn.Bar(w) => w.map(math.max(0, _))
        case column                => Some(texts.map(row => CharWidth.of(row(c))).foldLeft(column.minWidth)(math.max))
    }
    val flexible = fixed.count(_.isEmpty)
    val used     = fixed.flatten.sum + options.gap * (columns.size - 1)
    val left     = math.max(0, width - used)
    val share    = if flexible == 0 then 0 else left / flexible
    var extra    = if flexible == 0 then 0 else left % flexible
    fixed.map {
      case Some(w) => w
      case None    =>
        val w = share + (if extra > 0 then 1 else 0)
        extra -= 1
        w
    }

  private def drawText(cursor: RowCursor, text: String, width: Int, column: ProgressColumn): Unit =
    val pad   = math.max(0, width - CharWidth.of(text))
    val style = column match
      case ProgressColumn.Description => options.descriptionStyle.getOrElse(options.style)
      case _                          => options.style
    if column.alignsRight then
      cursor.skip(pad)
      cursor.write(text, style)
    else
      cursor.write(text, style)
      cursor.skip(pad)

  private def drawBar(cursor: RowCursor, task: ProgressTask, width: Int): Unit =
    val cells  = math.max(0, width)
    val glyph  = math.max(1, CharWidth.of(options.fillSymbol))
    val track  = math.max(1, CharWidth.of(options.trackSymbol))
    val ratio  = if task.isFinished then 1.0 else Fraction.clamped(task.fraction.getOrElse(0.0))
    val filled = (ratio * cells).toInt
    val start  = cursor.at
    cursor.write(options.fillSymbol * (filled / glyph), options.fillStyle)
    val drawn  = cursor.at - start
    cursor.write(options.trackSymbol * (math.max(0, cells - drawn) / track), options.trackStyle)
    cursor.skip(cells - (cursor.at - start))
