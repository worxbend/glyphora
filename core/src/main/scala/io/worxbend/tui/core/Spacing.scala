package io.worxbend.tui.core

/** How much room a [[Layout]] leaves between two adjacent segments.
  *
  * `Gap(cells)` inserts that many empty cells between them, which is what `Layout`'s plain `spacing: Int` has always
  * done. `Overlap(cells)` does the opposite: it pulls each segment back over the one before it so the two *share* that
  * many columns or rows.
  *
  * Overlap is what lets two bordered blocks sit side by side sharing a single border line, instead of drawing two
  * adjacent lines with a doubled-up look — the "collapsed borders" style of a table or a grid. Draw the left block into
  * the first segment and the right one into the second, and the right block's left border lands exactly on the left
  * block's right border.
  *
  * Both cases clamp a negative argument to zero: the direction is carried by which case you chose, never by the sign of
  * the number. That is deliberate. `Layout`'s `spacing: Int` field clamps a negative value to zero for the same reason
  * — a caller who wrote `spacing = -2` almost certainly meant a typo, not a request to overlap — and this type exists
  * so that asking for an overlap is something you say out loud rather than something you get by accident.
  */
enum Spacing:
  case Gap(cells: Int)
  case Overlap(cells: Int)

  /** How far the layout advances *past* the end of one segment before starting the next: positive for a
    * [[io.worxbend.tui.core.Spacing.Gap Gap]], negative for an [[io.worxbend.tui.core.Spacing.Overlap Overlap]], zero
    * for either with a count of zero.
    *
    * This is the single number every step of the layout arithmetic reads, so no step can budget for a gap while another
    * places an overlap.
    */
  def signed: Int = this match
    case Gap(cells)     => math.max(0, cells)
    case Overlap(cells) => -math.max(0, cells)

object Spacing:
  /** Segments placed flush against each other: no gap, no shared cells. */
  val none: Spacing = Gap(0)
