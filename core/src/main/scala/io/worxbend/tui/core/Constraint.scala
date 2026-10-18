package io.worxbend.tui.core

/** How much of a split axis one segment claims (see [[Layout.split]]).
  *
  * `Length`/`Percentage`/`Ratio` are fixed demands; `Min` is a floor that also competes for leftover space; `Max` is a
  * cap that only takes leftover space; `Fill` divides leftover space by weight.
  *
  * `Auto` asks the segment's own content: the measuring overload of [[Layout.split]] hands the solver one size per
  * segment, and an `Auto` segment claims exactly what was measured for it. Where nothing was measured — the content
  * cannot say, or the caller used the plain `split` — `Auto` is a `Fill(1)`, never zero, for the same reason
  * [[Measured]] makes `None` mean "cannot say" rather than "nothing".
  *
  * `Bounded(min, max, base)` clamps whatever `base` would have been given: "fill, but at least 20 and at most 60" is
  * `Bounded(20, 60, Fill(1))`, and [[atLeast]] / [[atMost]] build it one bound at a time. A segment pushed to a bound
  * is frozen there and the space it gave up or took is shared out again among the others, so a bounded fill beside a
  * plain one settles the way a flex layout does rather than the way a fixed `Min` floor does.
  *
  * This enum grows: a `match` over it that must survive the next case should carry a wildcard.
  */
enum Constraint:
  case Length(cells: Int)
  case Percentage(pct: Int)
  case Ratio(numerator: Int, denominator: Int)
  case Min(cells: Int)
  case Max(cells: Int)
  case Fill(weight: Int = 1)
  case Auto
  case Bounded(min: Int, max: Int, base: Constraint)

  /** This constraint with a floor of `cells`: the segment never comes out shorter. On a [[Bounded]] the floor is
    * replaced; on anything else the result is `Bounded(cells, Int.MaxValue, this)`.
    */
  def atLeast(cells: Int): Constraint =
    this match
      case Bounded(_, max, base) => Bounded(cells, max, base)
      case other                 => Bounded(cells, Int.MaxValue, other)

  /** This constraint with a ceiling of `cells`: the segment never comes out longer. On a [[Bounded]] the ceiling is
    * replaced; on anything else the result is `Bounded(0, cells, this)`.
    */
  def atMost(cells: Int): Constraint =
    this match
      case Bounded(min, _, base) => Bounded(min, cells, base)
      case other                 => Bounded(0, cells, other)

  /** What this one constraint makes of an axis `available` cells long, with no other constraint competing for it.
    *
    * This is the cheap single-segment answer — a popup's width, a sidebar's height, a measurement pass — and it is
    * deliberately not the solver. [[LayoutSolver.solve]] classifies every segment's demand, shares the leftover space
    * between the segments that can grow, and hands back the cells lost to integer division; with a single segment the
    * two agree, but `sizeIn` states the rule directly instead of asking the reader to trace three passes.
    *
    * `Length`, `Percentage`, `Ratio`, `Max`, `Fill` and `Auto` never answer more than `available`. `Min` is a floor, so
    * it is the one case that can: `Min(10).sizeIn(5)` is 10, and the caller decides whether to scroll, clip, or grow
    * the container. `Bounded` clamps its base's answer, so its floor can exceed the axis the same way. `Auto` has no
    * content to ask here and answers the whole axis, as the solver does for an unmeasured `Auto`. A negative
    * `available` is read as zero, and so is a negative `pct`, `num` or `cells`.
    *
    * The arithmetic runs in `Long` and is brought back into `Int` at the end, so a constraint whose numbers are far
    * larger than any terminal — `Percentage(25000000)`, `Ratio(Int.MaxValue, 1)` — still answers a cell count inside
    * the axis rather than one that wrapped around into the negatives.
    *
    * Two deliberate differences from ratatui, the Rust toolkit these constraints follow. Its `Fill(n)` reuses the
    * weight as a length; here `Fill.weight` is only a share of leftover space and means nothing on its own, so a lone
    * `Fill` takes the whole axis whatever its weight. And the arithmetic stays in whole cells rather than floating
    * point, matching `LayoutSolver`, so `sizeIn` and a one-element `solve` cannot disagree by a rounded cell.
    */
  def sizeIn(available: Int): Int =
    val axis = math.max(0, available)
    this match
      case Length(cells)           => math.min(axis, math.max(0, cells))
      case Percentage(pct)         => math.min(axis, CellCount.fractionOf(axis, pct, 100))
      case Ratio(num, den)         => math.min(axis, CellCount.fractionOf(axis, num, den))
      case Min(cells)              => math.max(axis, math.max(0, cells))
      case Max(cells)              => math.min(axis, math.max(0, cells))
      case Fill(_)                 => axis
      case Auto                    => axis
      case Bounded(min, max, base) =>
        val floor = math.max(0, min)
        math.max(floor, math.min(math.max(floor, max), base.sizeIn(available)))

object Constraint:
  def fill: Constraint = Fill(1)

  /** [[Auto]] as a value, for call sites that read better with a lower-case name beside [[fill]]. */
  def auto: Constraint = Auto

  /** One [[Length]] per cell count, in the order given: `Constraint.lengths(3, 10, 3)`. */
  def lengths(cells: Int*): Seq[Constraint] = cells.map(Length.apply)

  /** One [[Percentage]] per percentage, in the order given: `Constraint.percentages(25, 50, 25)`. */
  def percentages(pcts: Int*): Seq[Constraint] = pcts.map(Percentage.apply)

  /** One [[Ratio]] per `(numerator, denominator)` pair: `Constraint.ratios(1 -> 3, 2 -> 3)`.
    *
    * Pairs rather than a flat list of numbers, because a flat list lets a caller pass an odd number of arguments and
    * only find out at run time.
    */
  def ratios(fractions: (Int, Int)*): Seq[Constraint] = fractions.map((num, den) => Ratio(num, den))

  /** One [[Min]] floor per cell count: `Constraint.mins(10, 10)`. */
  def mins(cells: Int*): Seq[Constraint] = cells.map(Min.apply)

  /** One [[Max]] cap per cell count: `Constraint.maxes(20, 20)`. */
  def maxes(cells: Int*): Seq[Constraint] = cells.map(Max.apply)

  /** One [[Fill]] per weight, so `Constraint.fills(1, 2, 1)` splits the leftover space 1:2:1. */
  def fills(weights: Int*): Seq[Constraint] = weights.map(Fill.apply)
