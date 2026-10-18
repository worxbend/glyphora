package io.worxbend.tui.dsl

import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}

import org.scalatest.funsuite.AnyFunSuite

/** Construction and rendering of the prototyping and inspection nodes in `ToolElements`. */
final class ToolElementsSpec extends AnyFunSuite:
  given Theme = Theme.default

  test("placeholder draws its label and its size in the theme's colours"):
    val element = placeholder("Sidebar")
    assert(element.debugName == "placeholder")
    val buffer  = rendered(element.widget, 11, 4)
    assert(lines(buffer) == Seq("┌╌╌╌╌╌╌╌╌╌┐", "╎ Sidebar ╎", "╎  11×4   ╎", "└╌╌╌╌╌╌╌╌╌┘"))
    assert(buffer.get(2, 1).style == Theme.default.muted.patch(Theme.default.accent))
    assert(buffer.get(0, 0).style == Theme.default.muted)
