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
