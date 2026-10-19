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

  // a picture squeezed into the visible part of its box would be drawn distorted, so a box the buffer cannot hold whole
  // is not placed at all and its cells keep flushing whatever fallback the widget drew
  test("a placement the buffer cannot hold whole is not recorded and reserves nothing"):
    val buffer = frame { b =>
      b.placeGraphic(Rect(8, 3, 4, 4), image)
      b.placeGraphic(Rect(20, 20, 2, 2), image)
    }
    assert(buffer.graphics.isEmpty)
    assert(buffer.diffDirective(9, 3) == DiffDirective.Default)

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

  test("a blit moves a placement that lands whole, and drops one the destination clips"):
    val offscreen = Buffer(Rect(0, 0, 4, 4))
    placed(offscreen, Rect(0, 1, 2, 1))
    val moved     = frame(_.blit(offscreen, Position(5, 2), Rect(0, 0, 4, 4)))
    assert(moved.graphics.map(_.area) == Seq(Rect(5, 3, 2, 1)))
    assert(!moved.covers(moved.graphics.head))
    val clipped   = frame(_.blit(offscreen, Position(9, 0), Rect(0, 0, 4, 4)))
    assert(clipped.graphics.isEmpty)
    assert(clipped.diffDirective(9, 1) == DiffDirective.Default)

  test("blitting a region back replaces the placements it lands on instead of doubling them"):
    val buffer = frame(placed(_, Rect(0, 0, 2, 1)))
    val local  = frame(_ => ())
    local.blit(buffer, Position(0, 0), buffer.area)
    buffer.blit(local, Position(0, 0), local.area)
    assert(buffer.graphics.size == 1)
