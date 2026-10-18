package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

/** Covers the shape [[Layout]] took when it stopped being a `case class`: a `final class` with a private constructor, a
  * companion `apply` that keeps every positional and named construction compiling next to the union-typed
  * `apply(direction)(constraints*)` overload, `with*` builders for the fields that had no builder, and structural
  * equality over every field.
  */
final class LayoutBuildersSpec extends AnyFunSuite:

  private val constraints = Seq(Constraint.Length(3), Constraint.Length(4))
  private val area        = Rect(0, 0, 12, 1)

  test("every with* builder round-trips its field"):
    assert(Layout.horizontal(3, 4).withSpacing(2) == Layout(Direction.Horizontal, constraints, spacing = 2))
    assert(Layout.horizontal(3, 4).withFlex(Flex.End) == Layout(Direction.Horizontal, constraints, flex = Flex.End))
    assert(
      Layout.horizontal(3, 4).spaced(Spacing.Overlap(1))
        == Layout(Direction.Horizontal, constraints, between = Some(Spacing.Overlap(1)))
    )
    assert(Layout.horizontal(3, 4).spaced(Spacing.Overlap(1)).withoutBetween == Layout.horizontal(3, 4))

  test("each builder leaves every other field as it was"):
    val base = Layout(Direction.Vertical, constraints, spacing = 1, flex = Flex.Center, between = Some(Spacing.Gap(2)))
    assert(base.withSpacing(5) == Layout(Direction.Vertical, constraints, 5, Flex.Center, Some(Spacing.Gap(2))))
    assert(base.withFlex(Flex.End) == Layout(Direction.Vertical, constraints, 1, Flex.End, Some(Spacing.Gap(2))))
    assert(
      base
        .spaced(Spacing.Overlap(1)) == Layout(Direction.Vertical, constraints, 1, Flex.Center, Some(Spacing.Overlap(1)))
    )
    assert(base.withoutBetween == Layout(Direction.Vertical, constraints, 1, Flex.Center, None))

  test("an explicit between keeps precedence over withSpacing until it is cleared"):
    // documented on `withSpacing`: `between` is what the layout spends when it is set, and `spacing` only otherwise
    val gapped = Layout.horizontal(3, 4).spaced(Spacing.Gap(3))
    assert(gapped.withSpacing(1).split(area) == gapped.split(area))
    assert(gapped.withSpacing(1).withoutBetween.split(area) == Layout.horizontal(3, 4).withSpacing(1).split(area))
    assert(Layout.horizontal(3, 4).withSpacing(1).split(area) == Seq(Rect(0, 0, 3, 1), Rect(4, 0, 4, 1)))

  test("equals and hashCode agree on all fields"):
    val layout          =
      Layout(Direction.Horizontal, constraints, spacing = 1, flex = Flex.Center, between = Some(Spacing.Gap(2)))
    val twin            = Layout.horizontal(3, 4).withSpacing(1).withFlex(Flex.Center).spaced(Spacing.Gap(2))
    assert(twin == layout)
    assert(twin.hashCode == layout.hashCode)
    val variants        = Seq(
      Layout(Direction.Vertical, constraints, 1, Flex.Center, Some(Spacing.Gap(2))),
      Layout(Direction.Horizontal, constraints :+ Constraint.Fill(1), 1, Flex.Center, Some(Spacing.Gap(2))),
      layout.withSpacing(2),
      layout.withFlex(Flex.End),
      layout.spaced(Spacing.Overlap(2)),
      layout.withoutBetween,
    )
    for variant <- variants do
      assert(variant != layout, variant.toString)
      assert(variant.hashCode != layout.hashCode, variant.toString)
    val notALayout: Any = "not a layout"
    assert(layout != notALayout)

  test("the companion apply and the union-typed overload both still resolve"):
    // one overload has defaults and the other is curried, so the pair is legal — this pins that it stays so
    val named      = Layout(Direction.Horizontal, Seq(Constraint.Length(3)))
    val shorthand  = Layout(Direction.Horizontal)(3, 0.5)
    val factory    = Layout.horizontal(3, 4)
    val positional = Layout(Direction.Horizontal, constraints, 2, Flex.Center)
    val empty      = Layout(Direction.Horizontal, Seq.empty)
    assert(named.constraints == Seq(Constraint.Length(3)))
    assert(shorthand == Layout(Direction.Horizontal, Seq(Constraint.Length(3), Constraint.Percentage(50))))
    assert(factory == Layout(Direction.Horizontal, constraints))
    assert(positional.spacing == 2 && positional.flex == Flex.Center)
    assert(empty.constraints.isEmpty && empty.split(area).isEmpty)

  test("toString is stable"):
    // the constraints print as whatever collection the caller passed, exactly as the derived case-class text did
    val layout = Layout(Direction.Horizontal, List(Constraint.Length(3)))
    assert(layout.toString == "Layout(Horizontal, List(Length(3)), spacing=0, flex=Start, between=None)")
