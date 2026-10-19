package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Constraint, Direction}

/** What a node claims from its container on each axis, before any explicit `.length`/`.fill` overrides it.
  *
  * A layout claim is a pair — how many rows the node wants and how many columns — not a function of whichever axis it
  * happens to be asked about. Naming the pair is what lets a one-row control say `SizeClaim.OneRow` once instead of
  * re-deriving the answer from a `Direction` every time the layout solver asks.
  */
private[dsl] final case class SizeClaim(vertical: Constraint, horizontal: Constraint):

  /** The half of the claim a container laying its children out along `direction` cares about. */
  def along(direction: Direction): Constraint =
    direction match
      case Direction.Vertical   => vertical
      case Direction.Horizontal => horizontal

private[dsl] object SizeClaim:

  /** Take whatever is going on both axes — the default for a container and for anything content-shaped. */
  val Fill: SizeClaim = SizeClaim(Constraint.Fill(1), Constraint.Fill(1))

  /** Exactly what the node's widget measures on both axes, through `Measured` — `Constraint.Auto` each way. For a node
    * whose widget already knows its own box (a clipped paragraph, a badge), so the node does not recompute the same
    * numbers by hand; an axis the widget cannot measure falls back to a fill, never to zero.
    */
  val Measured: SizeClaim = SizeClaim(Constraint.Auto, Constraint.Auto)

  /** Exactly one row tall, as wide as the container allows — every single-line control. */
  val OneRow: SizeClaim = SizeClaim(Constraint.Length(1), Constraint.Fill(1))

  /** Exactly `count` rows tall, as wide as the container allows. */
  def rows(count: Int): SizeClaim = SizeClaim(Constraint.Length(count), Constraint.Fill(1))

  /** An exact box, for a node whose own widget knows the size it paints. */
  def box(width: Int, height: Int): SizeClaim = SizeClaim(Constraint.Length(height), Constraint.Length(width))
