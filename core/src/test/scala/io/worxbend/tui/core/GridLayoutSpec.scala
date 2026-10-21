package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

final class GridLayoutSpec extends AnyFunSuite:

  private val area = Rect(0, 0, 21, 7)

  // two 10-wide columns with a 1-cell gap; a 3-row top bar, a 1-row gap, the rest below
  private val grid =
    GridLayout(Seq(Constraint.fill, Constraint.fill), Seq(Constraint.Length(3), Constraint.fill)).withGap(1, 1)

  test("each cell is the intersection of its column track and its row track"):
    assert(grid.cell(area, GridPlacement(0, 0)) == Rect(0, 0, 10, 3))
    assert(grid.cell(area, GridPlacement(1, 1)) == Rect(11, 4, 10, 3))

  test("a span covers its tracks and the gaps between them"):
    assert(grid.cell(area, GridPlacement(0, 0, colSpan = 2)) == Rect(0, 0, 21, 3))
    assert(grid.cell(area, GridPlacement(0, 1, rowSpan = 2)) == Rect(11, 0, 10, 7))

  test("a placement outside the grid is clipped to the tracks that exist, or empty"):
    assert(grid.cell(area, GridPlacement(1, 1, colSpan = 5)) == Rect(11, 4, 10, 3))
    assert(grid.cell(area, GridPlacement(4, 0)).isEmpty)
    assert(grid.cell(area, GridPlacement(-1, 0)).isEmpty)

  test("Auto tracks take the measured size"):
    val auto   = GridLayout(Seq(Constraint.Auto, Constraint.fill), Seq(Constraint.Auto, Constraint.fill))
    val tracks = auto.tracks(Rect(0, 0, 20, 10), col => Option.when(col == 0)(6), row => Option.when(row == 0)(2))
    assert(tracks.columns.map(_.width) == Seq(6, 14))
    assert(tracks.rows.map(_.height) == Seq(2, 8))

  test("auto-flow fills row by row and skips cells a span already took"):
    val placed = GridLayout.autoFlow(
      columns = 2,
      items = Seq(GridItem.span(1, 2), GridItem.auto, GridItem.at(1, 1), GridItem.auto),
    )
    assert(
      placed == Seq(GridPlacement(0, 0, 1, 2), GridPlacement(1, 0), GridPlacement(1, 1), GridPlacement(2, 0))
    )

  test("auto-flow moves an item too wide for the rest of the row onto the next row"):
    val placed = GridLayout.autoFlow(2, Seq(GridItem.auto, GridItem.span(1, 2)))
    assert(placed == Seq(GridPlacement(0, 0), GridPlacement(1, 0, 1, 2)))

  test("a sparse cursor retries the free prefix on the next row before the tall blocker ends"):
    val placed = GridLayout.autoFlow(4, Seq(GridItem.at(0, 2, 10, 2), GridItem.auto, GridItem.span(1, 2)))
    assert(placed == Seq(GridPlacement(0, 2, 10, 2), GridPlacement(0, 0), GridPlacement(1, 0, 1, 2)))

  test("widened occupied row ends saturate public coordinates without wrapping the cursor"):
    val placed = GridLayout.autoFlow(
      2,
      Seq(
        GridItem.at(0, 0, Int.MaxValue, 2),
        GridItem.at(Int.MaxValue, 0, Int.MaxValue, 2),
        GridItem.auto,
        GridItem.auto,
      ),
    )
    assert(placed.takeRight(2) == Seq(GridPlacement(Int.MaxValue, 0), GridPlacement(Int.MaxValue, 0)))

  test("nonpositive grids and spans normalize only flowed items, leaving explicit placements unchanged"):
    val placed = GridLayout.autoFlow(0, Seq(GridItem.at(-2, -3, 0, -1), GridItem.span(0, -1), GridItem.span(2, 9)))
    assert(placed == Seq(GridPlacement(-2, -3, 0, -1), GridPlacement(0, 0), GridPlacement(1, 0, 2, 1)))

  test("a template of named areas resolves to the rectangle each name covers"):
    val areas = GridAreas.parse("top top", "main side")
    assert(
      areas == Right(
        Map(
          "top"  -> GridPlacement(0, 0, 1, 2),
          "main" -> GridPlacement(1, 0),
          "side" -> GridPlacement(1, 1),
        )
      )
    )

  test("a dot leaves a template cell empty"):
    assert(GridAreas.parse("a .", ". b").map(_.keySet) == Right(Set("a", "b")))

  test("a template with ragged rows or a non-rectangular area is refused"):
    assert(GridAreas.parse("a a", "b").isLeft)
    assert(GridAreas.parse("a a", "a b").isLeft)
    assert(GridAreas.parse("a b a").isLeft)
