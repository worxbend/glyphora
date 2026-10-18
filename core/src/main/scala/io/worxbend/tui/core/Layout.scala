package io.worxbend.tui.core

/** Splits a rectangle into segments along one axis according to a list of [[Constraint]]s.
  *
  * `Layout` owns the positioning half of the problem: it deducts `spacing`, asks `LayoutSolver` how many cells each
  * constraint gets, then places those sizes on the axis according to [[flex]], clamping anything that would run past
  * the far edge. How the sizes themselves are decided is documented on `LayoutSolver`.
  *
  * When the fixed demands exceed the available space, trailing segments are truncated (possibly to zero width) rather
  * than failing — consistent with the library-wide silent-clipping philosophy. A negative `spacing` is treated as zero
  * for the same reason: a negative number in that field is almost always a mistake, and `LayoutSolver` already clamps
  * every constraint input it reads rather than propagating a negative.
  *
  * Overlapping segments *are* reachable, but only by asking for them by name. Set `between` to a [[Spacing.Overlap]]
  * and each segment is pulled back over the one before it so the two share that many columns or rows — which is how two
  * bordered blocks come to share one border line instead of drawing two adjacent ones. `between` takes precedence over
  * `spacing` when it is set, and `spacing` is what every layout that has not heard of `between` keeps using.
  *
  * A `final class` with a private constructor rather than a `case class`, because new constraint or flex behaviour is
  * expected to add fields here. A case class rewrites `apply`, `copy` and `unapply` on every new field, and a consumer
  * compiled against the old shape fails with `NoSuchMethodError`; here the compatibility surface is
  * [[Layout.horizontal]], [[Layout.vertical]] and the builders [[withSpacing]], [[withFlex]], [[spaced]] and
  * [[withoutBetween]], all of which a new field leaves untouched. There is no `copy` and no pattern extractor; equality
  * and `hashCode` are structural over every field.
  */
final class Layout private (
    val direction: Direction,
    val constraints: Seq[Constraint],
    val spacing: Int,
    val flex: Flex,
    val between: Option[Spacing],
):

  /** [[spacing]] as the layout actually spends it, when no [[between]] was given. */
  private val gap: Int = math.max(0, spacing)

  /** How far the layout advances past the end of one segment before starting the next.
    *
    * Positive inserts a gap, negative shares cells. Every step that budgets or places a gap reads this one value, so a
    * layout cannot deduct space in one step and hand it back as an overlap in the next — which is exactly the bug the
    * negative-`spacing` clamp was written to prevent.
    */
  private val step: Int = between.map(_.signed).getOrElse(gap)

  def split(area: Rect): Seq[Rect] =
    if constraints.isEmpty then Seq.empty
    else
      val total     = axisExtent(area)
      // a negative step is an overlap, which hands the solver *more* room than the axis has, because the segments will
      // be laid down sharing cells rather than each taking their own
      val available = math.max(0, CellCount.clamp(total.toLong - step.toLong * (constraints.size - 1)))
      val sizes     = LayoutSolver.solve(constraints, available)
      layOutSegments(area, sizes, total)

  /** [[split]], plus the `segments.size + 1` *spacer* rectangles the layout left between and around those segments.
    *
    * `spacers(0)` is the room before the first segment, `spacers(i)` the room between segment `i - 1` and segment `i`,
    * and the last spacer the room after the final segment. Before this existed a caller who wanted to paint something
    * into a gap — a rule between two panes, the grab handle of a draggable splitter, a drop shadow — had to redo the
    * arithmetic `spacing` and [[flex]] had already done, and could get a different answer from the solver. Now the
    * solver hands the gaps back, so the two cannot disagree.
    *
    * The spacers are derived from the segments *after* they were placed and clamped, not from the intended offsets. If
    * the constraints demand more room than `area` has, the trailing segments are squeezed to nothing and the gaps
    * between them are reported as nothing too, rather than as room that is not on screen.
    *
    * A zero-extent spacer is normal and needs no special case at the call site: it is what adjacent segments with no
    * spacing produce, and every widget renders an empty rectangle as nothing. When `constraints` is empty both results
    * are empty — there are no segments, so there is nothing for a gap to sit between.
    *
    * Under a [[Spacing.Overlap]] every inner spacer is zero-extent, because overlapping segments leave no room between
    * them to report. The shared cells belong to both neighbours and are not a gap.
    *
    * @return
    *   the segments, then the spacers.
    */
  def splitWithSpacers(area: Rect): (Seq[Rect], Seq[Rect]) =
    val segments = split(area)
    if segments.isEmpty then (Seq.empty, Seq.empty) else (segments, spacersBetween(area, segments))

  /** The gaps around and between already-placed `segments`, in the order [[splitWithSpacers]] documents.
    *
    * Walks the axis: the first gap runs from the area's near edge to the first segment's start, each middle gap from
    * one segment's end to the next one's start, and the last from the final segment's end to the area's far edge. Each
    * bound is clamped into the area and each extent floored at zero, so a segment that was itself clamped cannot
    * produce a gap that starts outside `area` or has a negative width.
    */
  private def spacersBetween(area: Rect, segments: Seq[Rect]): Seq[Rect] =
    val (axisStart, axisEnd) = axisBounds(area)
    val starts               = segments.map(axisOffsetOf)
    val ends                 = segments.map(segment => axisOffsetOf(segment) + axisExtent(segment))
    (axisStart +: ends).zip(starts :+ axisEnd).map { (from, to) =>
      val begin = math.max(axisStart, math.min(from, axisEnd))
      segmentRect(area, begin, math.max(0, math.min(to, axisEnd) - begin))
    }

  /** [[split]] for a layout whose constraint count is known while writing the code: the segments come back as a tuple,
    * so `val (left, right) = layout.split2(area)` binds two names the compiler checked instead of two `apply` calls it
    * did not.
    *
    * Positional indexing into the `Seq` — `split(area)(2)` — is the shape every caller used before these existed, and
    * it fails at run time, on a terminal already switched into raw mode, where the stack trace is the last thing the
    * user sees. A short result is padded with the empty rectangle `Rect(0, 0, 0, 0)`, which every widget renders as
    * nothing, so a mismatch degrades to a missing pane rather than an exception.
    */
  def split2(area: Rect): (Rect, Rect) =
    val parts = splitInto(area, 2)
    (parts(0), parts(1))

  def split3(area: Rect): (Rect, Rect, Rect) =
    val parts = splitInto(area, 3)
    (parts(0), parts(1), parts(2))

  def split4(area: Rect): (Rect, Rect, Rect, Rect) =
    val parts = splitInto(area, 4)
    (parts(0), parts(1), parts(2), parts(3))

  def split5(area: Rect): (Rect, Rect, Rect, Rect, Rect) =
    val parts = splitInto(area, 5)
    (parts(0), parts(1), parts(2), parts(3), parts(4))

  /** [[split]]'s result as exactly `count` rectangles — grown with empty ones when short — so the tuple helpers index
    * safely.
    */
  private def splitInto(area: Rect, count: Int): Array[Rect] =
    val result = Array.fill(count)(Rect(0, 0, 0, 0))
    val parts  = split(area).iterator
    var index  = 0
    while index < count && parts.hasNext do
      result(index) = parts.next()
      index += 1
    result

  /** How many cells `area` offers along [[direction]] — its width when horizontal, its height when vertical. The four
    * axis helpers are the only place in this file that knows which `Rect` fields the direction selects.
    */
  private def axisExtent(area: Rect): Int =
    direction match
      case Direction.Horizontal => area.width
      case Direction.Vertical   => area.height

  /** Where `area` starts along [[direction]] — its `x` when horizontal, its `y` when vertical. */
  private def axisOffsetOf(area: Rect): Int =
    direction match
      case Direction.Horizontal => area.x
      case Direction.Vertical   => area.y

  /** The first coordinate along [[direction]] and the one past the last. */
  private def axisBounds(area: Rect): (Int, Int) =
    direction match
      case Direction.Horizontal => (area.x, area.right)
      case Direction.Vertical   => (area.y, area.bottom)

  /** A segment covering `size` cells from `start` along [[direction]], spanning `area` fully on the other axis. */
  private def segmentRect(area: Rect, start: Int, size: Int): Rect =
    direction match
      case Direction.Horizontal => Rect(start, area.y, size, area.height)
      case Direction.Vertical   => Rect(area.x, start, area.width, size)

  /** Places the solved `sizes` onto the axis of `area`, applying the [[flex]] offsets and clamping any segment that
    * would run past the far edge.
    */
  private def layOutSegments(area: Rect, sizes: IndexedSeq[Int], total: Int): Seq[Rect] =
    val (axisStart, axisEnd) = axisBounds(area)
    val (leading, gaps)      = flexOffsets(sizes, total)
    // the running cursor is a `Long`: a segment sized `Int.MaxValue` (which `Min(Int.MaxValue)` asks for) added to a
    // start would wrap around in `Int` and place the *next* segment back at the near edge instead of past the far one
    var offset: Long         = axisStart.toLong + leading
    sizes.indices.map { i =>
      val size        = sizes(i)
      // clamped at both ends: an overlap deeper than the previous segment must not walk the cursor back out of the area
      val start       = math.max(axisStart.toLong, math.min(offset, axisEnd.toLong)).toInt
      val clampedSize = math.max(0, math.min(size.toLong, axisEnd.toLong - start)).toInt
      val gapAfter    = if i < sizes.size - 1 then gaps(i) else 0
      offset = start.toLong + size + gapAfter
      segmentRect(area, start, clampedSize)
    }

  /** The leading offset and the inter-segment gaps (each already including the base `spacing`) that realize the current
    * [[flex]] mode, given the solved `sizes` and the axis `total`. `free` is the space the segments and base spacing
    * leave over — zero when a `Fill`/`Min` constraint already consumed everything, which makes every mode collapse to
    * `Start`.
    */
  private def flexOffsets(sizes: IndexedSeq[Int], total: Int): (Int, IndexedSeq[Int]) =
    val segmentCount = sizes.size
    val baseGaps     = step.toLong * math.max(0, segmentCount - 1)
    // summed as `Long` for the same reason the solver does: oversized segments must report *no* free space, not a
    // negative sum that wrapped back around into a positive one and invented room for `Flex` to spread out into
    val free         = math.max(0, CellCount.clamp(total.toLong - sizes.map(_.toLong).sum - baseGaps))
    val betweens     = IndexedSeq.fill(math.max(0, segmentCount - 1))(step)
    if free == 0 then (0, betweens)
    else
      flex match
        case Flex.Start        => (0, betweens)
        case Flex.End          => (free, betweens)
        case Flex.Center       => (free / 2, betweens)
        case Flex.SpaceBetween =>
          if segmentCount <= 1 then (0, betweens)
          else (0, addSpacing(evenSplit(free, segmentCount - 1)))
        case Flex.SpaceEvenly  =>
          val slots = evenSplit(free, segmentCount + 1)
          (slots(0), addSpacing((1 until segmentCount).map(i => slots(i)).toIndexedSeq))
        case Flex.SpaceAround  =>
          val halves = evenSplit(free, 2 * segmentCount)
          val gaps   = (1 until segmentCount).map(i => halves(2 * i - 1) + halves(2 * i)).toIndexedSeq
          (halves(0), addSpacing(gaps))

  /** Splits `total` cells into `parts` as evenly as possible, giving the remainder to the earliest slots. */
  private def evenSplit(total: Int, parts: Int): IndexedSeq[Int] =
    if parts <= 0 then IndexedSeq.empty
    else
      val base  = total / parts
      // all keys equal, so [[LayoutSolver.distributeRemainder]] falls back to index order: the earliest slots get the spare cells
      val extra = LayoutSolver.distributeRemainder(total % parts, IndexedSeq.fill(parts)(0.0))
      (0 until parts).map(i => base + extra(i)).toIndexedSeq

  private def addSpacing(gaps: IndexedSeq[Int]): IndexedSeq[Int] = gaps.map(_ + step)

  /** This layout with `spacing` replaced by an explicit [[Spacing]], so `Layout.horizontal(4, 4).spaced(Overlap(1))`
    * reads at the call site as what it does.
    */
  def spaced(spacing: Spacing): Layout = new Layout(direction, constraints, this.spacing, flex, Some(spacing))

  /** This layout with no explicit [[between]], so [[spacing]] is what it spends between segments again. The way back
    * from [[spaced]] — and the call that makes a later [[withSpacing]] take effect on a layout that was `spaced`.
    */
  def withoutBetween: Layout = new Layout(direction, constraints, spacing, flex, None)

  /** This layout with `cells` empty cells between adjacent segments (negative is treated as zero, as for the field).
    *
    * An explicit [[between]] keeps precedence: `layout.spaced(x).withSpacing(n)` still spends `x`, and `n` is unused
    * until [[withoutBetween]] clears it. `spaced(Spacing.Gap(n))` is the spelling that overrides a `between` in place.
    */
  def withSpacing(cells: Int): Layout = new Layout(direction, constraints, cells, flex, between)

  /** This layout placing its segments by `flex` — see [[Flex]] for the six modes. */
  def withFlex(flex: Flex): Layout = new Layout(direction, constraints, spacing, flex, between)

  /** Structural equality over every field, reference equality tried first. */
  override def equals(other: Any): Boolean =
    other match
      case that: Layout =>
        (this eq that) ||
        (direction == that.direction && spacing == that.spacing && flex == that.flex && between == that.between &&
          constraints == that.constraints)
      case _            => false

  /** Hashes every field [[equals]] compares. */
  override def hashCode(): Int =
    var hash = direction.hashCode()
    hash = 31 * hash + constraints.hashCode()
    hash = 31 * hash + spacing
    hash = 31 * hash + flex.hashCode()
    31 * hash + between.hashCode()

  /** Every field, named, in constructor order — what a failed assertion over two layouts has to show. */
  override def toString: String =
    s"Layout($direction, $constraints, spacing=$spacing, flex=$flex, between=$between)"

object Layout:

  /** A layout from its fields — `spacing`, `flex` and `between` default to no gap, [[Flex.Start]] and none.
    *
    * This is a construction convenience, not the compatibility surface. Adding a field changes its descriptor and
    * renumbers its default-argument getters, so a consumer compiled against `Layout(direction, constraints, spacing =
    * …)` still fails with `NoSuchMethodError` on the next field until it recompiles; at that growth the previous arity
    * is kept as a plain default-free overload so compiled callers keep working. Code that must survive a new field
    * without recompiling starts from [[horizontal]] or [[vertical]] and builds with the `with*` methods and [[spaced]].
    */
  def apply(
      direction: Direction,
      constraints: Seq[Constraint],
      spacing: Int = 0,
      flex: Flex = Flex.Start,
      between: Option[Spacing] = None,
  ): Layout = new Layout(direction, constraints, spacing, flex, between)

  /** Constraint shorthand: a plain `Int` means `Length(cells)`, a `Double` means a fraction of the whole (`0.5` →
    * `Percentage(50)`, truncating — use `Constraint.Ratio` when exact thirds matter), and any `Constraint` passes
    * through. A union-typed overload rather than implicit `Conversion`s so call sites need no language import.
    */
  def apply(direction: Direction)(constraints: (Int | Double | Constraint)*): Layout =
    Layout(
      direction,
      constraints.map {
        case cells: Int             => Constraint.Length(cells)
        case fraction: Double       => Constraint.Percentage(percentageOf(fraction))
        case constraint: Constraint => constraint
      },
    )

  /** The whole percentage points in `fraction`, read from the decimal the caller wrote rather than from the binary
    * number the machine stored.
    *
    * A `Double` cannot hold most decimals exactly: the nearest `Double` to `0.29` is very slightly *below* it, so the
    * plain product `0.29 * 100` is `28.999999999999996` and truncating it gave 28 — a caller who asked for an exact 29%
    * of the axis got 28%. `BigDecimal.valueOf` goes through the shortest decimal that round-trips to the same `Double`
    * (`"0.29"`), so the multiplication happens in base ten and yields exactly `29.0`.
    *
    * Truncation is still what happens to a genuinely inexact fraction — `0.333` becomes 33, not 34 — because a fraction
    * that cannot be said in whole percentage points has no exact answer to round to, and rounding up would make three
    * thirds claim 102% of the axis.
    *
    * A fraction that is not a finite number claims nothing, and one large enough to overflow an `Int` is held at
    * `Int.MaxValue`, because `BigDecimal.toInt` on an out-of-range value silently returns the wrong number rather than
    * failing. Negative fractions become zero here for the same reason `Constraint.Percentage` floors them at zero: a
    * segment cannot claim less than none of the axis.
    */
  private def percentageOf(fraction: Double): Int =
    if !fraction.isFinite then 0
    else
      val points = BigDecimal.valueOf(fraction) * 100
      points.max(BigDecimal(0)).min(BigDecimal(Int.MaxValue)).toInt

  def horizontal(constraints: (Int | Double | Constraint)*): Layout =
    apply(Direction.Horizontal)(constraints*)

  def vertical(constraints: (Int | Double | Constraint)*): Layout =
    apply(Direction.Vertical)(constraints*)
