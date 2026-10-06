package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

final class FillWeightOverflowSpec extends AnyFunSuite:

  test("large fill weights preserve dominance rather than overflowing the total") {
    val parts = Layout(Direction.Horizontal, Seq(Constraint.Fill(Int.MaxValue), Constraint.Fill(1)))
      .split(Rect(0, 0, 80, 1))
    assert(parts.map(_.width) == Seq(80, 0))
  }

  test("large equal weights keep largest-remainder tie ordering") {
    val parts = Layout(Direction.Horizontal, Seq.fill(3)(Constraint.Fill(Int.MaxValue))).split(Rect(0, 0, 80, 1))
    assert(parts.map(_.width) == Seq(27, 27, 26))
  }

  test("large weighted allocations agree with exact integer largest remainders") {
    val weights = Vector(Int.MaxValue, Int.MaxValue - 1, 1000000000, 1)
    Seq(1, 7, 80, 301).foreach { width =>
      val total    = weights.map(_.toLong).sum
      val base     = weights.map(weight => width.toLong * weight / total)
      val order    = weights.indices.sortBy(index => (-(width.toLong * weights(index) % total), index))
      val extras   = order.take((width - base.sum).toInt).toSet
      val expected = base.indices.map(index => base(index).toInt + (if extras(index) then 1 else 0))
      val parts    = Layout(Direction.Horizontal, weights.map(Constraint.Fill.apply)).split(Rect(0, 0, width, 1))
      assert(parts.map(_.width) == expected)
    }
  }
