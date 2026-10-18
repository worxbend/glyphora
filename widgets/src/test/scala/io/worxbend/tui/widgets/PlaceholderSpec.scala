package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}

import org.scalatest.funsuite.AnyFunSuite

final class PlaceholderSpec extends AnyFunSuite:

  test("a placeholder frames its label and states the size it was given"):
    assert(
      lines(rendered(Placeholder("Sidebar"), 14, 5)) == Seq(
        "┌╌╌╌╌╌╌╌╌╌╌╌╌┐",
        "╎  Sidebar   ╎",
        "╎    14×5    ╎",
        "╎            ╎",
        "└╌╌╌╌╌╌╌╌╌╌╌╌┘",
      )
    )

  test("a wide label is centred by display width, not by character count"):
    assert(
      lines(rendered(Placeholder("サイド"), 12, 4)) == Seq(
        "┌╌╌╌╌╌╌╌╌╌╌┐",
        "╎  サイド  ╎",
        "╎   12×4   ╎",
        "└╌╌╌╌╌╌╌╌╌╌┘",
      )
    )

  test("a label wider than the box is clipped inside the border"):
    assert(lines(rendered(Placeholder("Navigation"), 8, 3)) == Seq("┌╌╌╌╌╌╌┐", "╎Naviga╎", "└╌╌╌╌╌╌┘"))

  test("an area too small for a frame keeps the label and the size, unframed"):
    assert(lines(rendered(Placeholder("Nav"), 5, 2)) == Seq("Nav  ", "5×2  "))
    assert(lines(rendered(Placeholder("Nav"), 2, 1)) == Seq("Na"))

  test("nothing is written outside the given area, and an empty area draws nothing"):
    val buffer   = Buffer(Rect(0, 0, 10, 6))
    val sentinel = Cell("~", Style.Default)
    buffer.fill(buffer.area, sentinel)
    val area     = Rect(2, 1, 5, 3)
    Placeholder("Content").render(area, buffer)
    for
      y <- 0 until 6
      x <- 0 until 10
      if !area.contains(x, y)
    do assert(buffer.get(x, y) == sentinel)
    Placeholder("x").render(Rect(0, 0, 0, 0), buffer)

  test("the label carries its own style over the box style"):
    val buffer = rendered(Placeholder("A", labelStyle = Style.Default.bold), 5, 3)
    assert(buffer.get(2, 1).style == Style.Default.bold)
