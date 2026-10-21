package io.worxbend.tui.core

/** Where one child sits in a [[GridLayout]]: the top-left track it starts in and how many tracks it covers each way.
  *
  * Rows and columns count from zero. A span below one is read as one, so a placement always names at least one cell.
  */
final case class GridPlacement(row: Int, col: Int, rowSpan: Int = 1, colSpan: Int = 1):

  /** The spans as the grid spends them: never below one. */
  def rows: Int = math.max(1, rowSpan)

  /** @see [[rows]] */
  def cols: Int = math.max(1, colSpan)

/** One child's request to [[GridLayout.autoFlow]]: a fixed position, or none and let the flow choose, plus its spans.
  */
final case class GridItem(position: Option[GridCell], rowSpan: Int, colSpan: Int)

/** A zero-based row and column in a grid. Immutable geometry, safe to share between threads. */
final case class GridCell(row: Int, col: Int)

object GridItem:

  /** The next free cell in reading order, one track each way. */
  val auto: GridItem = GridItem(None, 1, 1)

  /** The next free run of cells that fits `rowSpan` x `colSpan`. */
  def span(rowSpan: Int, colSpan: Int): GridItem = GridItem(None, rowSpan, colSpan)

  /** Exactly at `(row, col)`, covering `rowSpan` x `colSpan` tracks. */
  def at(row: Int, col: Int, rowSpan: Int = 1, colSpan: Int = 1): GridItem =
    GridItem(Some(GridCell(row, col)), rowSpan, colSpan)

/** The column and row tracks one [[GridLayout]] solved for one area — see [[GridLayout.tracks]]. */
final case class GridTracks(columns: Seq[Rect], rows: Seq[Rect])

/** A two-axis layout: a list of column [[Constraint]]s and a list of row constraints, each solved by the same
  * [[LayoutSolver]] a one-axis [[Layout]] uses, with a gap between neighbouring tracks.
  *
  * The grid owns geometry only. It says where track `i` runs ([[tracks]]) and which rectangle a [[GridPlacement]]
  * covers ([[cell]]): a span covers its tracks *and* the gaps between them, so a top bar spanning two columns is one
  * rectangle wide enough to reach the far edge. Which child goes where is the caller's — explicit placements,
  * [[GridLayout.autoFlow]] for children that did not say, or a template of named areas parsed by [[GridAreas]].
  *
  * Columns are solved first and do not depend on the rows, so a caller measuring an `Auto` row can ask for the widths
  * the columns came out at before it decides the row heights.
  *
  * Immutable and safe to share between threads; a `final class` with builders, like [[Layout]], so a new field is not a
  * breaking change.
  */
final class GridLayout private (
    val columns: Seq[Constraint],
    val rows: Seq[Constraint],
    val columnGap: Int,
    val rowGap: Int,
):

  /** The solved tracks with no content measured: `Auto` tracks are laid out as `Fill(1)`. */
  def tracks(area: Rect): GridTracks = tracks(area, _ => None, _ => None)

  /** The solved tracks, with `columnSizes(i)` / `rowSizes(j)` answering how much an `Auto` column / row needs. Column
    * tracks span the area's full height and row tracks its full width; a cell is their intersection.
    */
  def tracks(area: Rect, columnSizes: Int => Option[Int], rowSizes: Int => Option[Int]): GridTracks =
    GridTracks(
      Layout(Direction.Horizontal, columns, columnGap).split(area, columnSizes),
      Layout(Direction.Vertical, rows, rowGap).split(area, rowSizes),
    )

  /** The rectangle `placement` covers in `area` with no content measured. */
  def cell(area: Rect, placement: GridPlacement): Rect = cell(tracks(area), placement)

  /** The rectangle `placement` covers given already-solved `tracks`: from the near edge of its first track to the far
    * edge of its last on each axis. Spans running past the grid are clipped to the tracks that exist; a placement that
    * starts outside the grid covers nothing.
    */
  def cell(tracks: GridTracks, placement: GridPlacement): Rect =
    val cols = covered(tracks.columns, placement.col, placement.cols)
    val rows = covered(tracks.rows, placement.row, placement.rows)
    (cols, rows) match
      case (Some((first, last)), Some((top, bottom))) =>
        Rect(first.x, top.y, math.max(0, last.right - first.x), math.max(0, bottom.bottom - top.y))
      case _                                          => Rect.Zero

  /** The first and last of `count` tracks from `start`, clipped to the tracks there are. */
  private def covered(tracks: Seq[Rect], start: Int, count: Int): Option[(Rect, Rect)] =
    if start < 0 || start >= tracks.size then None
    else Some((tracks(start), tracks(math.min(tracks.size.toLong, start.toLong + count).toInt - 1)))

  /** This grid with `columnGap` blank columns between column tracks and `rowGap` blank rows between row tracks. */
  def withGap(columnGap: Int, rowGap: Int): GridLayout = new GridLayout(columns, rows, columnGap, rowGap)

  override def equals(other: Any): Boolean =
    other match
      case that: GridLayout =>
        columns == that.columns && rows == that.rows && columnGap == that.columnGap && rowGap == that.rowGap
      case _                => false

  override def hashCode(): Int = ((columns.hashCode() * 31 + rows.hashCode()) * 31 + columnGap) * 31 + rowGap

  override def toString: String = s"GridLayout($columns, $rows, columnGap=$columnGap, rowGap=$rowGap)"

object GridLayout:

  /** A grid of `columns` x `rows` tracks with no gap; add one with [[GridLayout.withGap]]. */
  def apply(columns: Seq[Constraint], rows: Seq[Constraint]): GridLayout = new GridLayout(columns, rows, 0, 0)

  /** Resolves every item to a placement on a grid `columns` wide, in the order given.
    *
    * Fixed items are placed first, where they asked to be, so a flowed item never lands on top of one. Each flowed item
    * then takes the first run of free cells, in reading order, that its spans fit into, starting the search where the
    * previous flowed item ended — CSS grid's "sparse" auto-placement, so the flow never goes back to fill an earlier
    * hole. A column span wider than the grid is narrowed to the grid. Rows grow as far as the items need; the caller
    * decides how many row tracks to solve.
    */
  def autoFlow(columns: Int, items: Seq[GridItem]): Seq[GridPlacement] =
    val width    = math.max(1, columns)
    val explicit =
      items.map(item => item.position.map(cell => GridPlacement(cell.row, cell.col, item.rowSpan, item.colSpan)))
    // Keep occupied rectangles, not one allocation per spanned cell. Searches advance between rectangle edges.
    val taken    = scala.collection.mutable.ArrayBuffer.from(explicit.flatten)
    var cursor   = 0L
    items.zip(explicit).map { (item, slot) =>
      slot.getOrElse {
        val cols       = math.min(width, math.max(1, item.colSpan))
        val rows       = math.max(1, item.rowSpan)
        val (row, col) = nextSlot(width, rows, cols, cursor, taken)
        // Coordinates beyond the public Int domain are unpaintable, rather than wrapping into an earlier row.
        val placement  = GridPlacement(math.min(row, Int.MaxValue.toLong).toInt, col, rows, cols)
        taken += placement
        cursor = row * width + col + cols
        placement
      }
    }

  /** Finds a sparse slot without reserving it. The row stays widened even beyond paintable Int coordinates so the
    * caller advances its cursor from the searched row, not the saturated public placement.
    */
  private def nextSlot(
      width: Int,
      rows: Int,
      cols: Int,
      cursor: Long,
      taken: scala.collection.Seq[GridPlacement],
  ): (Long, Int) =
    var row   = cursor / width
    var from  = (cursor % width).toInt
    var found = false
    var col   = 0
    while !found && row <= Int.MaxValue do
      val blockers                     = taken.filter(p => p.row.toLong < row + rows && p.row.toLong + p.rows > row)
      def freeColumn(start: Int): Long =
        var candidate = start.toLong
        blockers.sortBy(_.col).foreach { p =>
          if p.col.toLong < candidate + cols && p.col.toLong + p.cols > candidate then candidate = p.col.toLong + p.cols
        }
        candidate
      val candidate                    = freeColumn(from)
      if candidate + cols <= width then
        col = candidate.toInt
        found = true
      else
        // A sparse cursor can leave a usable prefix behind; it is available again on the very next row.
        val next = if from > 0 && freeColumn(0) + cols <= width then row + 1
        else blockers.map(p => p.row.toLong + p.rows).filter(_ > row).minOption.getOrElse(row + 1)
        row = next
        from = 0
    (row, col)

/** Named grid areas from a text template, the way CSS `grid-template-areas` spells them.
  *
  * Each string is one row; whitespace separates its cells; a name repeated across neighbouring cells makes that name's
  * area span them, and a `.` leaves a cell unnamed. `GridAreas.parse("top top", "main side")` names a two-column top
  * bar over two single cells.
  */
object GridAreas:

  /** The placement each name covers, or why the template is not a grid: rows of different lengths, or a name whose
    * cells do not form one filled rectangle.
    */
  def parse(rows: String*): Either[String, Map[String, GridPlacement]] =
    val cells = rows.map(_.trim.split("\\s+").toVector.filter(_.nonEmpty)).toVector
    val width = cells.headOption.map(_.size).getOrElse(0)
    if cells.exists(_.size != width) then Left(s"every template row must have $width cells: ${rows.mkString(" / ")}")
    else
      val named   = for
        (row, r)  <- cells.zipWithIndex
        (name, c) <- row.zipWithIndex
        if name != "."
      yield (name, (r, c))
      val grouped = named.groupMap(_._1)(_._2)
      val areas   = grouped.map { (name, positions) =>
        val top    = positions.map(_._1).min
        val left   = positions.map(_._2).min
        val height = positions.map(_._1).max - top + 1
        val width  = positions.map(_._2).max - left + 1
        name -> (GridPlacement(top, left, height, width), positions.size == height * width)
      }
      areas.collectFirst { case (name, (_, false)) => name } match
        case Some(name) => Left(s"grid area '$name' is not a rectangle")
        case None       => Right(areas.map((name, area) => name -> area._1))
