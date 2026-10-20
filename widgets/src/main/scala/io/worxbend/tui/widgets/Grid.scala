package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Constraint, GridLayout, GridPlacement, GridTracks, Measured, Rect, Widget}

/** One child of a [[Grid]]: where it sits and what it draws. */
final case class GridEntry(placement: GridPlacement, widget: Widget)

/** Renders each cell into the rectangle its placement covers in a [[GridLayout]].
  *
  * The geometry is the layout's; this widget adds content measurement for `Constraint.Auto` tracks. An `Auto` column is
  * as wide as the widest cell sitting in that column alone (a cell spanning several columns does not size any one of
  * them), measured at the height of the rows it spans. An `Auto` row is as tall as the tallest single-row cell at its
  * solved column width. Both axes are refined until stable, with a sixteen-pass ceiling for mutually dependent
  * measurements. A track no cell measures is laid out as a fill.
  *
  * Cells are painted in the order given, so a later cell covering the same tracks draws over an earlier one. Stateless
  * and immutable; render-thread use only, like every widget.
  */
final case class Grid(layout: GridLayout, cells: Seq[GridEntry]) extends Widget with Measured:

  def render(area: Rect, buffer: Buffer): Unit =
    val solved = tracks(area)
    cells.foreach { cell =>
      val target = layout.cell(solved, cell.placement)
      if !target.isEmpty then cell.widget.render(target, buffer)
    }

  /** Refines both axes from provisional tracks, measuring widths at the rows the cells actually span. */
  private def tracks(area: Rect): GridTracks =
    var solved = layout.tracks(area)
    var stable = false
    var rounds = 0
    while !stable && rounds < 16 do
      val columns = layout.tracks(area, columnWidth(solved), _ => None).columns
      val next    = layout.tracks(area, columnWidth(solved), rowHeight(columns.map(_.width)))
      stable = next == solved
      solved = next
      rounds += 1
    solved

  /** The widest single-column cell at the height of its allocated row span. */
  private def columnWidth(solved: GridTracks)(col: Int): Option[Int] =
    cells
      .filter(cell => cell.placement.col == col && cell.placement.cols == 1)
      .flatMap(cell => LayoutItem.widthOf(cell.widget, layout.cell(solved, cell.placement).height))
      .maxOption

  /** The tallest single-row cell in row `row`, measured at the width its columns span. */
  private def rowHeight(columnWidths: Seq[Int])(row: Int): Option[Int] =
    cells
      .filter(cell => cell.placement.row == row && cell.placement.rows == 1)
      .flatMap { cell =>
        val end     = math.min(columnWidths.size.toLong, cell.placement.col.toLong + cell.placement.cols).toInt
        val spanned = columnWidths.slice(cell.placement.col, end)
        val width   = spanned.sum + math.max(0, layout.columnGap) * math.max(0, spanned.size - 1)
        LayoutItem.heightOf(cell.widget, width)
      }
      .maxOption

  /** The rows the grid needs at `width`: every row a `Length` or a measurable `Auto`, plus the gaps; `None` as soon as
    * one row is a share of whatever space there is.
    */
  override def heightAt(width: Int): Option[Int] =
    val seed    = layout.rows.collect { case Constraint.Length(cells) => math.max(0, cells).toLong }.sum
    val height  = math.min(Int.MaxValue.toLong, math.max(1L, seed)).toInt
    val columns = tracks(Rect(0, 0, math.max(0, width), height)).columns.map(_.width)
    val rows    = layout.rows.indices.map { index =>
      layout.rows(index) match
        case Constraint.Length(cells) => Some(math.max(0, cells))
        case Constraint.Auto          => rowHeight(columns)(index)
        case _                        => None
    }
    LayoutItem.allOrNone(rows).map(_.sum + math.max(0, layout.rowGap) * math.max(0, rows.size - 1))
