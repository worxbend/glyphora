package io.worxbend.tui.core

/** Turns a list of [[Constraint]]s into one integer size per segment.
  *
  * This is the "how many cells does each segment get" half of [[Layout]]; where those cells sit on the axis (spacing,
  * [[Flex]] offsets, clamping to the rectangle) is [[Layout]]'s own job. The split is what lets either half be read
  * without skimming the other.
  *
  * The solver is a pure function of `(constraints, available)` and holds no state, so it is safe to call from any
  * thread.
  *
  * The algorithm is three steps:
  *   1. every constraint states its fixed demand, and those are handed out first;
  *   1. whatever is left over is shared among the constraints that grow (`Fill` by weight, `Min` and `Max` with weight
  *      one), honoring `Max` caps and re-distributing what a cap refuses;
  *   1. if nothing grew and the constraints between them claim the whole axis proportionally, the cells lost to integer
  *      division are handed back so the segments fill the container exactly.
  *
  * Applications reach this through [[Layout.split]] and rarely call [[solve]] directly; it is public because how a
  * toolkit decides sizes is the part users most need to be able to read, and a `private` object documents it to nobody.
  */
object LayoutSolver:

  /** What one constraint asks of the axis, in every currency the solver spends.
    *
    * Every step of [[solve]] reads a field of this record rather than re-matching on the constraint, so adding a
    * `Constraint` case forces exactly one exhaustive match — [[demandOf]] — to be updated, instead of failing silently
    * into a wildcard branch in each step.
    *
    * @param fixed
    *   cells the constraint claims before any leftover is shared.
    * @param growthWeight
    *   its share of the leftover, relative to the other weights; zero means it does not grow.
    * @param growthCap
    *   the most leftover cells it will accept, on top of [[fixed]]; `Int.MaxValue` means uncapped.
    * @param proportional
    *   present when the constraint expresses a fraction of the axis rather than a number of cells.
    */
  private final case class Demand(
      fixed: Int,
      growthWeight: Int,
      growthCap: Int,
      proportional: Option[Demand.Proportional],
  )

  private object Demand:

    /** The exact fraction of the axis a proportional constraint asked for, and the fraction of a cell that asking for
      * it in whole cells lost to integer division — the key the leftover is ranked by.
      */
    final case class Proportional(share: Double, lostFraction: Double)

    /** A constraint that claims a fixed number of cells and never grows. */
    def fixedCells(cells: Int): Demand = Demand(math.max(0, cells), 0, 0, None)

  /** Classifies one constraint against an axis of `available` cells. The match is exhaustive on purpose: a new
    * `Constraint` case must be given a demand here before the library compiles again.
    */
  private def demandOf(constraint: Constraint, available: Int): Demand =
    val axis = math.max(0, available)
    constraint match
      case Constraint.Length(cells)   => Demand.fixedCells(cells)
      case Constraint.Percentage(pct) => proportionalDemand(axis, math.max(0, pct), 100)
      // a non-positive denominator is not a ratio anyone can honor; it claims nothing rather than dividing by zero
      case Constraint.Ratio(num, den) => proportionalDemand(axis, math.max(0, num), den)
      // a floor that also competes for the leftover, so it keeps growing past its floor
      case Constraint.Min(cells)      => Demand(math.max(0, cells), 1, Int.MaxValue, None)
      // a cap takes only leftover, and only up to the cap
      case Constraint.Max(cells)      => Demand(0, 1, math.max(0, cells), None)
      case Constraint.Fill(weight)    => Demand(0, math.max(0, weight), Int.MaxValue, None)

  /** The demand a `numerator / denominator` fraction of an `axis`-cell axis makes: whole cells now, plus the fraction
    * of a cell the divide threw away so the leftover can be ranked by who lost the most.
    *
    * Two pieces of defensive arithmetic live here rather than at each call site. The whole-cell part goes through
    * [[CellCount.fractionOf]], which multiplies in `Long`, so a `Percentage(25000000)` no longer overflows into a
    * negative demand. And the result is capped at the axis, because no single fraction of the axis can claim more of it
    * than all of it — a cap that matches what [[Constraint.sizeIn]] answers for the same constraint on its own.
    */
  private def proportionalDemand(axis: Int, numerator: Int, denominator: Int): Demand =
    val lostFraction =
      if denominator <= 0 then 0.0 else (axis.toLong * numerator % denominator).toDouble / denominator
    val share        = if denominator <= 0 then 0.0 else numerator.toDouble / denominator
    Demand(
      fixed = math.min(axis, CellCount.fractionOf(axis, numerator, denominator)),
      growthWeight = 0,
      growthCap = 0,
      proportional = Some(Demand.Proportional(share, lostFraction)),
    )

  /** The solved size of each constraint, in the order given, for an axis of `available` cells (spacing already
    * deducted). Sizes may exceed `available` when the fixed demands do; [[Layout]] clamps at the far edge.
    */
  def solve(constraints: Seq[Constraint], available: Int): IndexedSeq[Int] =
    val demands  = withGrowthWhenEveryWeightIsZero(constraints.map(demandOf(_, available)).toIndexedSeq)
    val fixed    = demands.map(_.fixed)
    // summed as `Long`: two `Min(Int.MaxValue)` floors add up to more than an `Int` holds, and an `Int` sum there would
    // wrap around to a negative total and report a huge leftover to share out
    val leftover = CellCount.clamp(available.toLong - fixed.map(_.toLong).sum)
    if leftover <= 0 then fixed
    else
      val growthShares = shareLeftover(demands, leftover)
      // a `Fill`/`Min`/`Max` claimed the slack, so what is still unclaimed is real free space for `Flex` to position
      if growthShares.sum > 0 then fixed.indices.map(i => fixed(i) + growthShares(i))
      else absorbProportionalRemainder(demands, fixed, leftover)

  /** Gives every constraint that is allowed to grow an equal share when they all state a weight of zero.
    *
    * A weight is a *share* of the leftover — it says how much one segment takes relative to its siblings, and it says
    * nothing on its own. `Constraint.Fill(0)` is the only way to reach this method, because `Min` and `Max` set their
    * weight to one themselves. With every weight zero there is no ratio to honour, so the segments that can grow split
    * the leftover evenly; a lone `Fill(0)` is then the whole axis, which is what `Constraint.sizeIn` has always
    * answered for it. Before this, zero-weighted fills were skipped by [[shareLeftover]] and a pane a caller had
    * measured as 20 cells wide rendered as nothing.
    *
    * A zero weight beside a non-zero one is untouched and still claims nothing: there the ratio is real, and 0:1 means
    * "all of it to the other segment".
    */
  private def withGrowthWhenEveryWeightIsZero(demands: IndexedSeq[Demand]): IndexedSeq[Demand] =
    val growable = demands.filter(_.growthCap > 0)
    if growable.isEmpty || growable.exists(_.growthWeight > 0) then demands
    else demands.map(demand => if demand.growthCap > 0 then demand.copy(growthWeight = 1) else demand)

  /** Hands the integer-division remainder to the proportional segments so they fill the container exactly.
    *
    * `Percentage(33) x 3` at width 100 truncates to 33+33+33 and would otherwise leave a stray column at the right
    * edge. Two conditions gate it, and both matter. Every constraint must be proportional — with a `Length` or `Fill`
    * in the mix the leftover is real free space that `Flex` positions, not a rounding artifact. And the constraints
    * must claim the whole axis to within [[claimsWholeAxis]]'s tolerance: `Percentage(50)` on its own asks for half the
    * container and must *get* half, with the other half left free.
    *
    * The spare cells go to the segments whose exact demand lost the most to integer division, so the solved sizes stay
    * as close as integers allow to the ratio the constraints asked for.
    *
    * Preconditions, guaranteed by the only caller: `remainder` is positive and `demands` is non-empty (`Layout.split`
    * rejects an empty constraint list). [[claimsWholeAxis]] is the one condition still tested at runtime.
    */
  private def absorbProportionalRemainder(
      demands: IndexedSeq[Demand],
      sizes: IndexedSeq[Int],
      remainder: Int,
  ): IndexedSeq[Int] =
    if !claimsWholeAxis(demands) then sizes
    else
      val extra = distributeRemainder(remainder, demands.map(_.proportional.fold(0.0)(_.lostFraction)))
      sizes.indices.map(index => sizes(index) + extra(index))

  /** Whether every constraint is proportional *and* together they ask for the whole axis.
    *
    * The tolerance is one percentage point per constraint: an integer percentage can only approximate the share its
    * author meant, so three thirds spell 99% and still mean "all of it", while `Percentage(50)` alone means half and
    * `Percentage(10), Percentage(30)` means a 1:3 split with 60% of the axis left free.
    */
  private def claimsWholeAxis(demands: IndexedSeq[Demand]): Boolean =
    demands.forall(_.proportional.isDefined) &&
      demands.map(_.proportional.fold(0.0)(_.share)).sum > 1.0 - demands.size / 100.0

  /** Shares `leftover` cells among the growing constraints by weight, honoring caps; residue refused by a capped
    * constraint is re-distributed among the still-uncapped until nothing changes.
    */
  private def shareLeftover(demands: IndexedSeq[Demand], leftover: Int): IndexedSeq[Int] =
    val granted    = Array.fill(demands.size)(0)
    var remaining  = leftover
    var progressed = true
    while remaining > 0 && progressed do
      val open   = demands.indices.filter(i => demands(i).growthWeight > 0 && granted(i) < demands(i).growthCap)
      val shares =
        weightedShares(
          remaining,
          open.map(i => demands(i).growthWeight),
          open.map(i => demands(i).growthCap - granted(i)),
        )
      progressed = shares.sum > 0
      open.zip(shares).foreach { (index, share) => granted(index) += share }
      remaining -= shares.sum
    granted.toIndexedSeq

  /** Integer division of `amount` by `weights` with largest-remainder rounding (ties to the earlier index), each share
    * clamped to its `limits` entry.
    */
  private def weightedShares(amount: Int, weights: Seq[Int], limits: Seq[Int]): IndexedSeq[Int] =
    val totalWeight = weights.iterator.map(_.toLong).sum
    if totalWeight == 0 then IndexedSeq.fill(weights.size)(0)
    else
      val base           = weights.map(w => amount.toLong * w / totalWeight).toIndexedSeq
      // Keep remainder ranking integral too: a widened total need not be exactly representable as a Double.
      val remainders     = weights.indices.map(i => amount.toLong * weights(i) % totalWeight).toIndexedSeq
      val remainderCells = distributeRemainder((amount.toLong - base.sum).toInt, remainders)
      weights.indices.map(i => math.min(base(i).toInt + remainderCells(i), limits(i)))

  /** Hands out `amount` single cells among `keys.size` claimants, largest key first and ties to the earlier index,
    * wrapping around once every claimant has had one.
    *
    * This is the largest-remainder rule the whole layout rounds with, in one place: the caller supplies whatever it
    * wants to rank by (the fraction of a cell a proportional demand lost to integer division, the remainder of a
    * weighted division, or all-equal keys when the split is even) and gets back the per-claimant surplus to add on top
    * of its whole-cell share. Wrapping keeps `amount` larger than the number of claimants meaningful; any two claimants
    * then differ by at most one cell.
    *
    * Internal to the layout implementation — [[Layout]] shares it so the even splits behind [[Flex]] round the same way
    * the constraints do — rather than part of what `tui-core` publishes.
    */
  private[core] def distributeRemainder[A: Ordering](amount: Int, keys: IndexedSeq[A]): IndexedSeq[Int] =
    if keys.isEmpty then IndexedSeq.empty
    else
      val ordering = summon[Ordering[A]]
      val order    = keys.indices.sortWith { (left, right) =>
        val compared = ordering.compare(keys(left), keys(right))
        compared > 0 || (compared == 0 && left < right)
      }
      val granted  = Array.fill(keys.size)(0)
      var pending  = amount
      var turn     = 0
      while pending > 0 do
        granted(order(turn % order.length)) += 1
        pending -= 1
        turn += 1
      granted.toIndexedSeq
