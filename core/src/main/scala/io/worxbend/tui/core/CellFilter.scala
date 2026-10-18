package io.worxbend.tui.core

/** Which cells of an effect's area an [[Effect]] may touch — the argument to [[Effect.filtered]].
  *
  * A filter is asked about the cell '''as it was rendered''', before the effect ran: `CellFilter.fg(Color.Red)` keeps a
  * fade on the red text even when the fade has already darkened it. It is also told the cell's coordinates and the
  * effect's `area`, which is what the geometric filters ([[CellFilter.inner]], [[CellFilter.outer]]) answer from.
  *
  * Filters are immutable values and compose with [[and]], [[or]] and [[not]]; they hold no state, so one filter can be
  * shared between effects and threads.
  */
trait CellFilter:

  /** Whether the effect may change the cell rendered at `(x, y)`, which lies inside `area`. */
  def matches(cell: Cell, x: Int, y: Int, area: Rect): Boolean

  /** Cells both filters accept. */
  final def and(other: CellFilter): CellFilter =
    (cell, x, y, area) => matches(cell, x, y, area) && other.matches(cell, x, y, area)

  /** Cells either filter accepts. */
  final def or(other: CellFilter): CellFilter =
    (cell, x, y, area) => matches(cell, x, y, area) || other.matches(cell, x, y, area)

  /** Cells this filter rejects. */
  final def not: CellFilter =
    (cell, x, y, area) => !matches(cell, x, y, area)

object CellFilter:

  /** Every cell — the filter an unfiltered effect behaves as. */
  val all: CellFilter = (_, _, _, _) => true

  /** Cells that hold a glyph: anything [[Cell.isBlank]] does not call blank. */
  val text: CellFilter = (cell, _, _, _) => !cell.isBlank

  /** Cells whose foreground is exactly `color`. A cell with no foreground of its own matches nothing. */
  def fg(color: Color): CellFilter = (cell, _, _, _) => cell.style.fg.contains(color)

  /** Cells whose background is exactly `color`. A cell with no background of its own matches nothing. */
  def bg(color: Color): CellFilter = (cell, _, _, _) => cell.style.bg.contains(color)

  /** Cells at least `margin` cells in from every edge of the effect's area (the area's `inset(margin)`). */
  def inner(margin: Int): CellFilter =
    // the distance to each edge, rather than `area.inset(margin).contains`, so asking costs no allocation per cell
    (_, x, y, area) =>
      x - area.x >= margin && area.right - 1 - x >= margin && y - area.y >= margin && area.bottom - 1 - y >= margin

  /** Cells within `margin` cells of an edge of the effect's area — exactly the cells [[inner]] rejects. */
  def outer(margin: Int): CellFilter = inner(margin).not

  /** Cells `predicate` accepts — the escape hatch for anything the ready-made filters do not say. */
  def where(predicate: Cell => Boolean): CellFilter = (cell, _, _, _) => predicate(cell)
