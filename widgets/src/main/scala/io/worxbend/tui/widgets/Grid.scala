package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Constraint, GridLayout, GridPlacement, GridTracks, Measured, Rect, Widget}

/** One child of a [[Grid]]: where it sits and what it draws. */
final case class GridCell(placement: GridPlacement, widget: Widget)

/** Renders each cell into the rectangle its placement covers in a [[GridLayout]].
  *
  * The geometry is the layout's; this widget adds content measurement for `Constraint.Auto` tracks. An `Auto` column is
  * as wide as the widest cell sitting in that column alone (a cell spanning several columns does not size any one of
  * them), and an `Auto` row as tall as the tallest such cell *at the width its columns came out at* — columns are
  * solved first, so a wrapping paragraph is measured at the width it will really be drawn at. A track no cell measures
  * is laid out as a fill.
  *
  * Cells are painted in the order given, so a later cell covering the same tracks draws over an earlier one. Stateless
  * and immutable; render-thread use only, like every widget.
  */
final case class Grid(layout: GridLayout, cells: Seq[GridCell]) extends Widget with Measured:

  def render(area: Rect, buffer: Buffer): Unit =
    val solved = tracks(area)
    cells.foreach { cell =>
      val target = layout.cell(solved, cell.placement)
      if !target.isEmpty then cell.widget.render(target, buffer)
    }

  /** The tracks for `area`, with `Auto` columns and then `Auto` rows measured from their single-track cells. */
  private def tracks(area: Rect): GridTracks =
    val columns = layout.tracks(area, columnWidth, _ => None).columns
    val widths  = columns.map(_.width)
    layout.tracks(area, columnWidth, rowHeight(widths))

  /** The widest single-column cell in column `col`, or `None` when no cell there measures its width. */
  private def columnWidth(col: Int): Option[Int] =
    cells
      .filter(cell => cell.placement.col == col && cell.placement.cols == 1)
      .flatMap(cell => LayoutItem.widthOf(cell.widget, 1))
      .maxOption

  /** The tallest single-row cell in row `row`, measured at the width its columns span. */
  private def rowHeight(columnWidths: Seq[Int])(row: Int): Option[Int] =
    cells
      .filter(cell => cell.placement.row == row && cell.placement.rows == 1)
      .flatMap { cell =>
        val spanned = columnWidths.slice(cell.placement.col, cell.placement.col + cell.placement.cols)
        val width   = spanned.sum + math.max(0, layout.columnGap) * math.max(0, spanned.size - 1)
        LayoutItem.heightOf(cell.widget, width)
      }
      .maxOption

  /** The rows the grid needs at `width`: every row a `Length` or a measurable `Auto`, plus the gaps; `None` as soon as
    * one row is a share of whatever space there is.
    */
  override def heightAt(width: Int): Option[Int] =
    val columns = layout.tracks(Rect(0, 0, math.max(0, width), 1), columnWidth, _ => None).columns.map(_.width)
    val rows    = layout.rows.indices.map { index =>
      layout.rows(index) match
        case Constraint.Length(cells) => Some(math.max(0, cells))
        case Constraint.Auto          => rowHeight(columns)(index)
        case _                        => None
    }
    LayoutItem.allOrNone(rows).map(_.sum + math.max(0, layout.rowGap) * math.max(0, rows.size - 1))
