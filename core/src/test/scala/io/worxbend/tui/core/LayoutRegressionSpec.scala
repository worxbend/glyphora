package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

final class LayoutRegressionSpec extends AnyFunSuite:
  test("LAYOUT-3 spans clip without overflow and auto-flow skips enormous occupied bands"):
    val layout = GridLayout(Seq.fill(2)(Constraint.fill), Seq.fill(2)(Constraint.fill))
    assert(layout.cell(Rect(0, 0, 8, 6), GridPlacement(1, 1, Int.MaxValue, Int.MaxValue)) == Rect(4, 3, 4, 3))
    val placed = GridLayout.autoFlow(2, Seq(GridItem.at(0, 0, Int.MaxValue, 2), GridItem.auto))
    assert(placed.last == GridPlacement(Int.MaxValue, 0))
    val beside = GridLayout.autoFlow(2, Seq(GridItem.span(Int.MaxValue, 1), GridItem.auto))
    assert(beside.last == GridPlacement(0, 1))

  test("LAYOUT-3 rectangle auto-flow preserves sparse reading order across mixed spans"):
    val random = new scala.util.Random(7L)
    (1 to 200).foreach { _ =>
      val width                                  = 1 + random.nextInt(5)
      val items                                  = Vector.fill(8) {
        val rows = 1 + random.nextInt(3)
        val cols = 1 + random.nextInt(5)
        if random.nextBoolean() then GridItem.at(random.nextInt(5), random.nextInt(width), rows, cols)
        else GridItem.span(rows, cols)
      }
      val placed                                 = GridLayout.autoFlow(width, items)
      val occupied                               = scala.collection.mutable.Set.empty[GridCell]
      def cells(p: GridPlacement): Seq[GridCell] =
        for r <- p.row until p.row + p.rows; c <- p.col until p.col + p.cols yield GridCell(r, c)
      items.zip(placed).filter(_._1.position.isDefined).foreach((_, p) => occupied ++= cells(p))
      var cursor                                 = 0
      items.zip(placed).filter(_._1.position.isEmpty).foreach { (item, actual) =>
        val cols                     = math.min(width, item.colSpan)
        var index                    = cursor
        def candidate: GridPlacement = GridPlacement(index / width, index % width, item.rowSpan, cols)
        while index % width + cols > width || cells(candidate).exists(occupied.contains) do index += 1
        assert(actual == candidate)
        occupied ++= cells(actual)
        cursor = index + cols
      }
    }

  test("STANDARDS-4 GridItem accepts named coordinates, and explicit coordinates drive placement"):
    val cell = GridCell(row = 2, col = 3)
    val item = GridItem(Some(cell), rowSpan = 1, colSpan = 2)
    assert(item.position.exists(p => p.row == 2 && p.col == 3))
    assert(GridLayout.autoFlow(5, Seq(item)) == Seq(GridPlacement(2, 3, 1, 2)))

  test("LAYOUT-8 small percentages leave their unclaimed axis free even with many segments"):
    assert(LayoutSolver.solve(Seq.fill(120)(Constraint.Percentage(0)), 240).sum == 0)
    assert(LayoutSolver.solve(Seq.fill(51)(Constraint.Percentage(1)), 100).sum == 51)
    assert(LayoutSolver.solve(Seq.fill(60)(Constraint.Percentage(1)), 1000).sum == 600)
    assert(LayoutSolver.solve(Seq.fill(3)(Constraint.Percentage(33)), 100).sum == 100)

  test("LAYOUT-9 nested bounds clamp inside out, including disjoint intervals"):
    val constraints = Seq(
      Constraint.Bounded(10, 20, Constraint.Bounded(0, 5, Constraint.fill)),
      Constraint.Bounded(0, 5, Constraint.Bounded(10, 20, Constraint.fill)),
      Constraint.Bounded(5, 20, Constraint.Bounded(10, 15, Constraint.fill)),
    )
    assert(constraints.map(c => LayoutSolver.solve(Seq(c), 30).head) == Seq(10, 5, 15))
    constraints.foreach(c => assert(LayoutSolver.solve(Seq(c), 30).head == c.sizeIn(30)))
