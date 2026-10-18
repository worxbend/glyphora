package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

final class BlitDestinationCropSpec extends AnyFunSuite:

  test("negative destination placement blanks a visible continuation rather than preserving stale content") {
    val source = Buffer(Rect(0, 0, 3, 1))
    source.setString(0, 0, "漢x", Style.Default)
    val target = Buffer(Rect(0, 0, 3, 1))
    target.setString(0, 0, "###", Style.Default)
    target.blit(source, Position(-1, 0))
    assert(target.get(0, 0) == Cell.Empty)
    assert(target.get(1, 0).symbol == "x")
    assert(target.get(2, 0).symbol == "#")
  }

  test("destination cropping maps back to nonzero source and destination origins") {
    val source = Buffer(Rect(20, 30, 4, 2))
    source.setString(20, 31, "漢xy", Style.Default)
    val target = Buffer(Rect(5, 7, 3, 1))
    target.setString(5, 7, "###", Style.Default)
    target.blit(source, Position(4, 6))
    assert(target.get(5, 7) == Cell.Empty)
    assert(target.get(6, 7).symbol == "x")
    assert(target.get(7, 7).symbol == "y")
  }

  test("source-window and destination crops compose without shifting the surviving text") {
    val source = Buffer(Rect(10, 10, 4, 1))
    source.setString(10, 10, "漢xy", Style.Default)
    val target = Buffer(Rect(0, 0, 3, 1))
    target.setString(0, 0, "###", Style.Default)
    target.blit(source, Position(-3, 0), Rect(8, 10, 6, 1))
    assert(target.get(0, 0) == Cell.Empty)
    assert(target.get(1, 0).symbol == "x")
    assert(target.get(2, 0).symbol == "y")
  }

  // A cut wide glyph is blanked, but the blank must keep its owner's style: `set` and `fill` both keep a styled blank
  // where a two-column glyph has no second column, and a scrolled coloured panel would otherwise show a
  // default-coloured hole at the edge where its last CJK character was cut. The destination is prefilled in a
  // different colour so the assertion can tell "kept the owner's style" from "kept whatever was there".
  private val red  = Style.Default.withBg(Color.Red)
  private val blue = Style.Default.withBg(Color.Blue)

  /** A red-backed `a漢b` in four columns — cells `a`, `漢`, its continuation, `b`. */
  private def redSource(): Buffer = {
    val source = Buffer.filled(Rect(0, 0, 4, 1), Cell(" ", red))
    source.setString(0, 0, "a漢b", red)
    source
  }

  test("a wide glyph cut by the window end keeps its style") {
    val target = Buffer.filled(Rect(0, 0, 4, 1), Cell(" ", blue))
    target.blit(redSource(), Position(0, 0), Rect(0, 0, 2, 1))
    assert(target.get(0, 0) == Cell("a", red))
    assert(target.get(1, 0) == Cell(" ", red))
    assert(target.get(2, 0) == Cell(" ", blue)) // outside the window: untouched
  }

  test("a wide glyph cut by the destination edge keeps its style") {
    val target = Buffer.filled(Rect(0, 0, 2, 1), Cell(" ", blue))
    target.blit(redSource(), Position(0, 0))
    assert(target.get(0, 0) == Cell("a", red))
    assert(target.get(1, 0) == Cell(" ", red))
  }

  test("a wide glyph cut by a negative placement keeps the owner's style") {
    val target = Buffer.filled(Rect(0, 0, 2, 1), Cell(" ", blue))
    // the window opens on the continuation half of 漢, whose owner sits one column left of it
    target.blit(redSource(), Position(-2, 0))
    assert(target.get(0, 0) == Cell(" ", red))
    assert(target.get(1, 0) == Cell("b", red))
  }
