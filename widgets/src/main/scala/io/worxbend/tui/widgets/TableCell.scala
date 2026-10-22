package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Line, Rect}

/** One cell of a [[Table]] row, and how many columns it covers.
  *
  * A [[Table]] accepts either a bare [[io.worxbend.tui.core.Line Line]] — a cell in one column, which is all a cell
  * could be before this type existed — or a `TableCell`, and the two may be mixed in one row. That union is the same
  * shape [[TableRow]] and [[ListView]] use, so nothing written against the older signature has to change.
  *
  * A spanning cell is what a grouped header is made of. Given four columns, a header row of `TableCell(Line.raw("in"),
  * 2)`, `TableCell(Line.raw("out"), 2)` writes two captions each centred over its own pair of data columns. Without
  * spans the only way to draw that was a second `Table` stacked above the first with its own, hand-matched width
  * constraints — which drifted apart from the real ones the moment either was edited. A full-width note row inside the
  * body is the same trick with one cell and a span covering every column.
  *
  * @param content
  *   the cell's text, clipped to the merged width like any other cell. Never wrapped.
  * @param columnSpan
  *   how many solved columns this cell covers, counting from wherever it lands in its row. The gaps `columnSpacing`
  *   puts between those columns are covered too, because the point is one continuous run of cells rather than several
  *   with holes. Clamped to at least one, and to no more than the columns actually remaining in the row: a span that
  *   ran off the end would either draw outside the table's area or, worse, silently swallow the cells after it.
  */
final case class TableCell(content: Line, columnSpan: Int = 1)

object TableCell:

  /** Either shape a [[Table]] accepts for one cell. */
  type Source = Line | TableCell

  /** A bare [[io.worxbend.tui.core.Line Line]] read as the single-column cell it has always meant. */
  def of(source: Source): TableCell =
    source match
      case cell: TableCell => cell
      case content: Line   => TableCell(content)

  /** How many columns a row of cells asks for in total — its cell count once spans are counted at their full width.
    *
    * This is what the equal-columns fallback in [[TableColumns]] counts, so that a header of two cells spanning two
    * columns each derives four columns and not two.
    */
  private[widgets] def columnCount(cells: Seq[Source]): Int =
    // summed in `Long`, then clamped to `Int.MaxValue`: a row of a few cells each carrying a large `columnSpan`
    // overflows `Int` well before any of them is individually adversarial, and the wrapped, possibly-negative total
    // would otherwise reach `TableColumns.resolve`'s `Seq.fill` unclamped.
    cells
      .foldLeft(0L)((total, source) => total + math.max(1, of(source).columnSpan).toLong)
      .min(Int.MaxValue.toLong)
      .toInt

  /** The rectangle covering `span` solved columns starting at `first`, gaps between them included.
    *
    * Returns an empty rectangle when `first` is past the last column, which every renderer already treats as "draw
    * nothing", so a row with more cells than columns clips instead of throwing.
    */
  private[widgets] def merge(columns: Seq[Rect], first: Int, span: Int): Rect =
    if first >= columns.size then Rect(0, 0, 0, 0)
    else
      val start = columns(first)
      val end   = columns(math.min(first + math.max(1, span), columns.size) - 1)
      start.copy(width = math.max(0, end.x + end.width - start.x))
