package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

/** Covers [[Layout.splitWithSpacers]]: the gap rectangles a split leaves between and around its segments.
  *
  * Pure geometry, so these assert on `Rect` values directly — `BufferAssertions` is for widgets that draw.
  */
final class LayoutSpacerSpec extends AnyFunSuite:

  /** The extent of a rectangle along `direction` — its width horizontally, its height vertically. */
  private def extentOf(direction: Direction, rect: Rect): Int =
    direction match
      case Direction.Horizontal => rect.width
      case Direction.Vertical   => rect.height

  private def offsetOf(direction: Direction, rect: Rect): Int =
    direction match
      case Direction.Horizontal => rect.x
      case Direction.Vertical   => rect.y

  /** Asserts the promise the Scaladoc makes: spacer, segment, spacer, segment, …, spacer laid end to end with no hole
    * and no overlap, from the near edge of the area to the far one, each rectangle spanning the cross axis fully.
    */
  private def assertTiles(layout: Layout, area: Rect): Unit =
    val (segments, spacers) = layout.splitWithSpacers(area)
    assert(spacers.size == segments.size + 1, "there is one more spacer than there are segments")
    val direction           = layout.direction
    val interleaved = spacers.head +: segments.zip(spacers.tail).flatMap((segment, spacer) => Seq(segment, spacer))
    val (nearEdge, farEdge) = direction match
      case Direction.Horizontal => (area.x, area.right)
      case Direction.Vertical   => (area.y, area.bottom)
    val walked              = interleaved.foldLeft(nearEdge) { (cursor, rect) =>
      assert(offsetOf(direction, rect) == cursor, s"$rect does not start where the previous rectangle ended ($cursor)")
      assert(extentOf(direction, rect) >= 0, s"$rect has a negative extent")
      cursor + extentOf(direction, rect)
    }
    assert(walked == farEdge, "the tiling does not reach the far edge of the area")
    val crossAxisMatches    = interleaved.forall { rect =>
      direction match
        case Direction.Horizontal => rect.y == area.y && rect.height == area.height
        case Direction.Vertical   => rect.x == area.x && rect.width == area.width
    }
    assert(crossAxisMatches, "a rectangle does not span the cross axis fully")

  private def spacerExtents(layout: Layout, area: Rect): Seq[Int] =
    layout.splitWithSpacers(area)._2.map(extentOf(layout.direction, _))

  test("segments and spacers tile the area exactly, on both axes"):
    assertTiles(Layout.horizontal(3, 4).withSpacing(1), Rect(2, 1, 10, 5))
    assertTiles(Layout.vertical(3, 4).withSpacing(1), Rect(2, 1, 10, 12))
    assertTiles(Layout.horizontal(0.5, 0.5), Rect(0, 0, 9, 3))

  test("adjacent segments with no spacing leave zero-extent spacers"):
    // a four-wide area exactly filled by two two-wide segments: every gap is empty, and that is not a special case
    assert(spacerExtents(Layout.horizontal(2, 2), Rect(0, 0, 4, 1)) == Seq(0, 0, 0))

  test("spacing shows up as the inner spacer"):
    val layout = Layout(Direction.Horizontal, Seq(Constraint.Length(2), Constraint.Length(2)), spacing = 3)
    assert(spacerExtents(layout, Rect(0, 0, 7, 1)) == Seq(0, 3, 0))

  test("a negative spacing is treated as zero here too"):
    val negative = Layout(Direction.Horizontal, Seq(Constraint.Length(2), Constraint.Length(2)), spacing = -3)
    val none     = Layout(Direction.Horizontal, Seq(Constraint.Length(2), Constraint.Length(2)), spacing = 0)
    assert(negative.splitWithSpacers(Rect(0, 0, 7, 1)) == none.splitWithSpacers(Rect(0, 0, 7, 1)))

  test("Flex.End moves the free space into the leading spacer"):
    val layout = Layout(Direction.Horizontal, Seq(Constraint.Length(2), Constraint.Length(2)), flex = Flex.End)
    assert(spacerExtents(layout, Rect(0, 0, 10, 1)) == Seq(6, 0, 0))

  test("Flex.Center splits the free space, remainder to the head"):
    // 10 cells, 4 used, 6 free: the leading spacer takes 3 and the trailing one the other 3
    val even = Layout(Direction.Horizontal, Seq(Constraint.Length(2), Constraint.Length(2)), flex = Flex.Center)
    assert(spacerExtents(even, Rect(0, 0, 10, 1)) == Seq(3, 0, 3))
    // 9 cells, 4 used, 5 free: `free / 2` is 2, so the head gets 2 and the tail keeps the odd cell
    assert(spacerExtents(even, Rect(0, 0, 9, 1)) == Seq(2, 0, 3))

  test("Flex.SpaceBetween puts nothing at the ends and everything in the middle"):
    val layout = Layout(Direction.Horizontal, Seq(Constraint.Length(2), Constraint.Length(2)), flex = Flex.SpaceBetween)
    assert(spacerExtents(layout, Rect(0, 0, 10, 1)) == Seq(0, 6, 0))

  test("Flex.SpaceEvenly gives every gap the same share, remainder to the earliest"):
    // 10 cells, 6 used by three 2-wide segments, 4 free over 4 slots: one cell each
    val layout = Layout(Direction.Horizontal, Seq(2, 2, 2).map(Constraint.Length.apply), flex = Flex.SpaceEvenly)
    assert(spacerExtents(layout, Rect(0, 0, 10, 1)) == Seq(1, 1, 1, 1))
    // 11 cells, 5 free over 4 slots: the earliest slot takes the spare cell
    assert(spacerExtents(layout, Rect(0, 0, 11, 1)) == Seq(2, 1, 1, 1))

  test("Flex.SpaceAround gives each segment a half-gap on either side"):
    // 10 cells, 6 used, 4 free split into six halves (0,1,1,1,1,0 by earliest-slot remainder): ends get one half,
    // each inner gap gets two adjacent halves
    val layout = Layout(Direction.Horizontal, Seq(2, 2, 2).map(Constraint.Length.apply), flex = Flex.SpaceAround)
    assert(spacerExtents(layout, Rect(0, 0, 10, 1)).sum == 4)
    assert(spacerExtents(layout, Rect(0, 0, 10, 1)) == Seq(1, 2, 1, 0))

  test("constraints that overrun the area report only gaps that are really on screen"):
    // three 6-wide segments and two 2-cell gaps demand 22 cells in an 8-cell area. The solver gives the first segment
    // all 6 solvable cells and the other two nothing; placing them spends the first gap and then runs out of area, so
    // the spacers are 0, 2, 0, 0 — the one gap that is genuinely blank space on screen, and no phantom ones after it.
    val layout = Layout(Direction.Horizontal, Seq(6, 6, 6).map(Constraint.Length.apply), spacing = 2)
    val area   = Rect(0, 0, 8, 1)
    assert(layout.split(area).map(_.width) == Seq(6, 0, 0))
    assert(spacerExtents(layout, area) == Seq(0, 2, 0, 0))
    assertTiles(layout, area)

  test("a segment clamped away at the far edge cannot produce a gap outside the area"):
    // the last segment starts past the far edge, so both it and the gap around it collapse rather than being reported
    // at coordinates no buffer has
    val layout              = Layout(Direction.Horizontal, Seq(10, 10).map(Constraint.Length.apply), spacing = 6)
    val area                = Rect(2, 0, 5, 1)
    val (segments, spacers) = layout.splitWithSpacers(area)
    assert(segments.forall(segment => segment.x >= area.x && segment.right <= area.right))
    assert(spacers.forall(spacer => spacer.x >= area.x && spacer.right <= area.right))
    assertTiles(layout, area)

  test("an empty constraint list yields no segments and no spacers"):
    assert(Layout(Direction.Horizontal, Seq.empty).splitWithSpacers(Rect(0, 0, 10, 1)) == (Seq.empty, Seq.empty))

  test("a zero-extent area still tiles, with everything empty"):
    assertTiles(Layout.horizontal(2, 2), Rect(3, 3, 0, 0))

  test("split agrees with splitWithSpacers"):
    val layouts = Seq(
      Layout.horizontal(3, 4).withSpacing(2),
      Layout.vertical(0.3, 0.7),
      Layout(Direction.Horizontal, Seq(Constraint.Min(3), Constraint.Length(2)), flex = Flex.SpaceAround),
      Layout(Direction.Vertical, Seq.empty),
    )
    val areas   = Seq(Rect(0, 0, 10, 10), Rect(4, 2, 7, 3), Rect(0, 0, 1, 1))
    for layout <- layouts; area <- areas do assert(layout.split(area) == layout.splitWithSpacers(area)._1)
