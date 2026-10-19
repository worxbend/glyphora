package io.worxbend.tui.core

import org.scalacheck.Gen
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

/** Property-based layout invariants over every constraint kind, `Auto` and `Bounded` included, on both axes.
  *
  * [[LayoutPropertySpec]] covers the six fixed-and-growing kinds on one axis; this suite adds the two measured kinds
  * (`Auto`, `Bounded`), a measure function, both directions, and the weight-monotonicity rule. 500 cases a property:
  * the draws are cheap, and a solver bug tends to live in one corner of a large space.
  */
final class LayoutProperties extends AnyFunSuite with ScalaCheckPropertyChecks:

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 500)

  private val genBase: Gen[Constraint] = Gen.oneOf(
    Gen.chooseNum(0, 30).map(Constraint.Length.apply),
    Gen.chooseNum(0, 100).map(Constraint.Percentage.apply),
    Gen.chooseNum(1, 4).flatMap(d => Gen.chooseNum(0, d).map(n => Constraint.Ratio(n, d))),
    Gen.chooseNum(0, 20).map(Constraint.Min.apply),
    Gen.chooseNum(0, 20).map(Constraint.Max.apply),
    Gen.chooseNum(0, 5).map(Constraint.Fill.apply),
    Gen.const(Constraint.Auto),
  )

  private val genBounded: Gen[Constraint] =
    for
      min  <- Gen.chooseNum(0, 15)
      span <- Gen.chooseNum(0, 25)
      base <- genBase
    yield Constraint.Bounded(min, min + span, base)

  private val genConstraint: Gen[Constraint] = Gen.frequency(4 -> genBase, 1 -> genBounded)

  private val genDirection: Gen[Direction] = Gen.oneOf(Direction.Horizontal, Direction.Vertical)

  /** A list of constraints, the axis length, a spacing and a measured size per segment (`None` = cannot say). */
  private final case class Case(
      constraints: Seq[Constraint],
      axis: Int,
      spacing: Int,
      measured: IndexedSeq[Option[Int]],
      direction: Direction,
  ):
    val area: Rect =
      direction match
        case Direction.Horizontal => Rect(2, 3, axis, 5)
        case Direction.Vertical   => Rect(2, 3, 5, axis)

    def layout: Layout                 = Layout(direction, constraints, spacing)
    def segments: Seq[Rect]            = layout.split(area, measured.lift.andThen(_.flatten))
    def extent(rect: Rect): Int        = if direction == Direction.Horizontal then rect.width else rect.height
    def start(rect: Rect): Int         = if direction == Direction.Horizontal then rect.x else rect.y
    def areaStart: Int                 = start(area)
    def areaEnd: Int                   = areaStart + axis
    def crossMatches(r: Rect): Boolean =
      if direction == Direction.Horizontal then r.y == area.y && r.height == area.height
      else r.x == area.x && r.width == area.width

  private val genCase: Gen[Case] =
    for
      constraints <- Gen.nonEmptyListOf(genConstraint).map(_.take(8))
      axis        <- Gen.chooseNum(0, 120)
      spacing     <- Gen.chooseNum(0, 3)
      measured    <- Gen.listOfN(constraints.size, Gen.option(Gen.chooseNum(0, 40)))
      direction   <- genDirection
    yield Case(constraints, axis, spacing, measured.toIndexedSeq, direction)

  test("every segment lies inside the area, on either axis"):
    forAll(genCase) { c =>
      val segments = c.segments
      assert(segments.size == c.constraints.size)
      segments.foreach { r =>
        assert(c.extent(r) >= 0, s"$r has a negative extent")
        assert(c.start(r) >= c.areaStart && c.start(r) + c.extent(r) <= c.areaEnd, s"$r outside ${c.area}")
        assert(c.crossMatches(r), s"$r does not span the cross axis of ${c.area}")
      }
    }

  test("the segments and the gaps between them sum to at most the axis"):
    forAll(genCase) { c =>
      val segments = c.segments
      val used     = segments.map(c.extent).sum
      assert(used <= c.axis, s"${segments.mkString(", ")} use $used of ${c.axis}")
      segments.zip(segments.drop(1)).foreach((a, b) => assert(c.start(b) >= c.start(a) + c.extent(a)))
    }

  test("raising one Fill's weight never shrinks it, and fills keep using the whole axis"):
    val genFills: Gen[(Seq[Int], Int, Int, Int)] =
      for
        weights <- Gen.nonEmptyListOf(Gen.chooseNum(1, 6)).map(_.take(6))
        index   <- Gen.chooseNum(0, weights.size - 1)
        bump    <- Gen.chooseNum(1, 5)
        axis    <- Gen.chooseNum(0, 150)
      yield (weights, index, bump, axis)

    forAll(genFills) { case (weights, index, bump, axis) =>
      val area   = Rect(0, 0, axis, 1)
      val before = Layout(Direction.Horizontal, weights.map(Constraint.Fill.apply)).split(area).map(_.width)
      val raised = weights.updated(index, weights(index) + bump)
      val after  = Layout(Direction.Horizontal, raised.map(Constraint.Fill.apply)).split(area).map(_.width)
      assert(after(index) >= before(index), s"weights $weights -> $raised: $before -> $after")
      assert(after.sum == axis && before.sum == axis)
    }

  test("a heavier Fill is never narrower than a lighter sibling"):
    forAll(Gen.nonEmptyListOf(Gen.chooseNum(1, 9)).map(_.take(7)), Gen.chooseNum(0, 200)) { (weights, axis) =>
      val widths = Layout(Direction.Horizontal, weights.map(Constraint.Fill.apply)).split(Rect(0, 0, axis, 1))
      for
        i <- weights.indices
        j <- weights.indices
        if weights(i) > weights(j)
      do assert(widths(i).width >= widths(j).width, s"weights $weights gave ${widths.map(_.width)}")
    }

  test("a Bounded segment is solved inside its bounds, and never past its ceiling once laid out"):
    forAll(genCase) { c =>
      val solved = LayoutSolver.solve(c.constraints, c.axis, c.measured.lift.andThen(_.flatten))
      val laid   = c.segments
      c.constraints.zipWithIndex.foreach {
        case (Constraint.Bounded(min, max, _), i) =>
          assert(solved(i) >= min && solved(i) <= max, s"segment $i solved to ${solved(i)}, bounds $min..$max")
          assert(c.extent(laid(i)) <= max, s"segment $i laid out to ${laid(i)}, ceiling $max")
        case _                                    => ()
      }
    }

  test("an Auto segment gets exactly its measured size when every demand fits"):
    val genAuto: Gen[(IndexedSeq[Int], Int)] =
      for
        sizes <- Gen.nonEmptyListOf(Gen.chooseNum(0, 20)).map(_.take(6).toIndexedSeq)
        slack <- Gen.chooseNum(0, 30)
      yield (sizes, sizes.sum + slack)

    forAll(genAuto) { case (sizes, axis) =>
      val widths = Layout(Direction.Horizontal, sizes.map(_ => Constraint.Auto))
        .split(Rect(0, 0, axis, 1), i => Some(sizes(i)))
        .map(_.width)
      assert(widths == sizes, s"measured $sizes in $axis, laid out $widths")
    }

  test("an unmeasurable Auto segment is laid out as a Fill(1), never as zero"):
    forAll(Gen.chooseNum(1, 6), Gen.chooseNum(0, 100)) { (count, axis) =>
      val auto  = Layout(Direction.Vertical, Seq.fill(count)(Constraint.Auto)).split(Rect(0, 0, 1, axis), _ => None)
      val fills = Layout(Direction.Vertical, Seq.fill(count)(Constraint.Fill(1))).split(Rect(0, 0, 1, axis))
      assert(auto == fills)
    }
