package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

/** A buffer's record of the pictures an out-of-band painter is to draw over it: [[Buffer.placeGraphic]] reserves the
  * cells, the placement travels with every operation that composes frames, and a later write over the reserved cells
  * reads as the picture being covered.
  */
final class GraphicPlacementSpec extends AnyFunSuite:

  private val image = RgbaImage.tabulate(2, 2)((_, _) => 0xff00ff00)

  private def frame(write: Buffer => Unit): Buffer =
    val buffer = Buffer(Rect(0, 0, 10, 4))
    write(buffer)
    buffer

  private def placed(buffer: Buffer, area: Rect): Unit =
    buffer.setString(area.x, area.y, "ab", Style.Default)
    buffer.placeGraphic(area, image)

  test("placing a graphic reserves its cells and records it"):
    val buffer = frame(placed(_, Rect(1, 1, 2, 1)))
    assert(buffer.graphics.map(p => (p.area, p.image)) == Seq((Rect(1, 1, 2, 1), image)))
    assert(buffer.diffDirective(1, 1) == DiffDirective.Skip)
    assert(buffer.diffDirective(2, 1) == DiffDirective.Skip)
    assert(buffer.diffDirective(3, 1) == DiffDirective.Default)

  // a picture squeezed into the visible part of its box would be drawn distorted, so a box the buffer holds only part
  // of is placed as a crop of the whole box (what a scroll view's windowed content buffer does), and a box wholly
  // outside it is not placed at all
  test("a placement the buffer holds only part of is cropped; one wholly outside is not recorded"):
    val buffer = frame { b =>
      b.placeGraphic(Rect(8, 3, 4, 4), image)
      b.placeGraphic(Rect(20, 20, 2, 2), image)
    }
    assert(buffer.graphics.map(_.area) == Seq(Rect(8, 3, 2, 1)))
    assert(buffer.graphics.head.crop.contains(GraphicCrop(columns = 4, rows = 4, column = 0, row = 0)))
    assert(buffer.diffDirective(9, 3) == DiffDirective.Skip)
    assert(buffer.diffDirective(7, 3) == DiffDirective.Default)

  test("reset forgets every placement"):
    val buffer = frame(placed(_, Rect(0, 0, 2, 1)))
    buffer.reset()
    assert(buffer.graphics.isEmpty)

  test("snapshot and copyFrom carry placements"):
    val buffer = frame(placed(_, Rect(0, 0, 2, 1)))
    assert(buffer.snapshot.graphics == buffer.graphics)
    val target = frame(_ => ())
    target.copyFrom(buffer)
    assert(target.graphics == buffer.graphics)

  test("a placement is uncovered while its cells hold what they held when it was placed"):
    val buffer    = frame(placed(_, Rect(0, 0, 2, 1)))
    val placement = buffer.graphics.head
    assert(!buffer.covers(placement))
    buffer.setString(1, 0, "!", Style.Default)
    assert(buffer.covers(placement))

  test("a blit moves a placement that lands whole, and crops one the destination clips"):
    val offscreen = Buffer(Rect(0, 0, 4, 4))
    placed(offscreen, Rect(0, 1, 2, 1))
    val moved     = frame(_.blit(offscreen, Position(5, 2), Rect(0, 0, 4, 4)))
    assert(moved.graphics.map(_.area) == Seq(Rect(5, 3, 2, 1)))
    assert(moved.graphics.head.crop.isEmpty)
    assert(!moved.covers(moved.graphics.head))
    val clipped   = frame(_.blit(offscreen, Position(9, 0), Rect(0, 0, 4, 4)))
    assert(clipped.graphics.map(_.area) == Seq(Rect(9, 1, 1, 1)))
    assert(clipped.graphics.head.crop.contains(GraphicCrop(columns = 2, rows = 1, column = 0, row = 0)))
    assert(clipped.diffDirective(9, 1) == DiffDirective.Skip)
    assert(!clipped.covers(clipped.graphics.head))

  /** What a scroll view does: the picture's box straddles the top of the window it copies out of its content. */
  test("a source window that cuts a placement keeps the visible part as a crop of the whole box"):
    val content = Buffer(Rect(0, 0, 6, 8))
    content.setString(1, 2, "abcd", Style.Default)
    content.setString(1, 3, "efgh", Style.Default)
    content.setString(1, 4, "ijkl", Style.Default)
    content.placeGraphic(Rect(1, 2, 4, 3), RgbaImage.tabulate(8, 6)((_, _) => 0xff0000ff))
    val screen  = frame(_.blit(content, Position(0, 0), Rect(0, 3, 6, 4)))
    val crop    = screen.graphics.head
    assert(crop.area == Rect(1, 0, 4, 2))
    assert(crop.crop.contains(GraphicCrop(columns = 4, rows = 3, column = 0, row = 1)))
    assert(crop.sourcePixels == Rect(0, 2, 8, 4), "rows 1..2 of 3 are pixel rows 2..5 of 6")
    assert(!screen.covers(crop))
    screen.setString(2, 1, "!", Style.Default)
    assert(screen.covers(crop))

  test("an uncropped placement's source is the whole picture"):
    val buffer = frame(placed(_, Rect(0, 0, 2, 1)))
    assert(buffer.graphics.head.sourcePixels == Rect(0, 0, 2, 2))

  test("blitting a region back replaces the placements it lands on instead of doubling them"):
    val buffer = frame(placed(_, Rect(0, 0, 2, 1)))
    val local  = frame(_ => ())
    local.blit(buffer, Position(0, 0), buffer.area)
    buffer.blit(local, Position(0, 0), local.area)
    assert(buffer.graphics.size == 1)
