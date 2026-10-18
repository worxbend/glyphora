package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

/** The two constraints added in 0.20.0: `Auto`, which asks the segment's own content how much of the axis it needs, and
  * `Bounded`, which clamps another constraint's answer between a floor and a ceiling.
  */
final class LayoutAutoBoundedSpec extends AnyFunSuite:

  private val axis = Rect(0, 0, 20, 1)

  // ---- Auto ----

  test("Auto takes the measured size and leaves the rest to the fill"):
    val sizes = Layout.horizontal(Constraint.Auto, Constraint.fill).split(axis, index => Option.when(index == 0)(7))
    assert(sizes.map(_.width) == Seq(7, 13))

  test("Auto with no measurement behaves as a fill, never as zero"):
    val sizes = Layout.horizontal(Constraint.Auto, Constraint.fill).split(axis, _ => None)
    assert(sizes.map(_.width) == Seq(10, 10))

  test("Auto through the plain split, which has no content to ask, is a fill"):
    val sizes = Layout.horizontal(Constraint.Auto, Constraint.fill).split(axis)
    assert(sizes.map(_.width) == Seq(10, 10))

  test("the solver answers Auto directly from the measure function"):
    assert(LayoutSolver.solve(Seq(Constraint.Auto, Constraint.Length(4)), 20, _ => Some(7)) == Seq(7, 4))

  test("a measured size is floored at zero"):
    assert(LayoutSolver.solve(Seq(Constraint.Auto, Constraint.fill), 20, _ => Some(-3)) == Seq(0, 20))

  // ---- Bounded ----

  test("a bounded fill stops at its ceiling and the other fill takes what it refused"):
    val sizes = Layout.horizontal(Constraint.Bounded(5, 8, Constraint.fill), Constraint.fill).split(axis)
    assert(sizes.map(_.width) == Seq(8, 12))

  test("a bounded fill is lifted to its floor and the other fill keeps the remainder"):
    val sizes = Layout.horizontal(Constraint.Bounded(5, 8, Constraint.fill), Constraint.fill).split(Rect(0, 0, 6, 1))
    assert(sizes.map(_.width) == Seq(5, 1))

  test("a bounded fill inside its bounds is an ordinary fill"):
    val sizes = Layout.horizontal(Constraint.Bounded(5, 8, Constraint.fill), Constraint.fill).split(Rect(0, 0, 14, 1))
    assert(sizes.map(_.width) == Seq(7, 7))

  test("bounds clamp a fixed base too"):
    assert(LayoutSolver.solve(Seq(Constraint.Bounded(5, 8, Constraint.Length(30))), 20) == Seq(8))
    assert(LayoutSolver.solve(Seq(Constraint.Bounded(5, 8, Constraint.Length(2))), 20) == Seq(5))

  test("bounds clamp a measured Auto base"):
    assert(
      LayoutSolver.solve(Seq(Constraint.Bounded(5, 8, Constraint.Auto), Constraint.fill), 20, _ => Some(30)) ==
        Seq(8, 12)
    )

  test("a bounded percentage no longer absorbs the proportional remainder"):
    // 33% x 3 of 100 absorbs the stray cell only when every constraint is a plain proportion of the axis
    val plain   = LayoutSolver.solve(Seq.fill(3)(Constraint.Percentage(33)), 100)
    val bounded = LayoutSolver.solve(
      Seq(Constraint.Bounded(0, 10, Constraint.Percentage(33)), Constraint.Percentage(33), Constraint.Percentage(33)),
      100,
    )
    assert(plain == Seq(34, 33, 33))
    assert(bounded == Seq(10, 33, 33))

  test("a ceiling below the floor is raised to the floor, and negatives read as zero"):
    assert(LayoutSolver.solve(Seq(Constraint.Bounded(8, 5, Constraint.fill)), 20) == Seq(8))
    assert(LayoutSolver.solve(Seq(Constraint.Bounded(-4, -2, Constraint.fill)), 20) == Seq(0))

  test("sizeIn clamps the base's own answer"):
    assert(Constraint.Bounded(5, 8, Constraint.fill).sizeIn(20) == 8)
    assert(Constraint.Bounded(5, 8, Constraint.fill).sizeIn(3) == 5)
    assert(Constraint.Bounded(5, 8, Constraint.Length(6)).sizeIn(20) == 6)
    assert(Constraint.Auto.sizeIn(20) == 20)

  test("the builders read as the compound they build"):
    assert(Constraint.fill.atLeast(5) == Constraint.Bounded(5, Int.MaxValue, Constraint.Fill(1)))
    assert(Constraint.fill.atMost(8) == Constraint.Bounded(0, 8, Constraint.Fill(1)))
    assert(Constraint.fill.atLeast(5).atMost(8) == Constraint.Bounded(5, 8, Constraint.Fill(1)))
    assert(Constraint.Bounded(5, 8, Constraint.fill).atLeast(6) == Constraint.Bounded(6, 8, Constraint.Fill(1)))
