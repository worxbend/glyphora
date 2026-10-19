package io.worxbend.tui.dsl

import io.worxbend.tui.testsupport.{BufferAssertions, GoldenFrames}

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** `grid`, `flow` and `dock`: the three containers 0.20.0 adds over the existing row/column solver. */
final class GridFlowDockSpec extends AnyFunSuite:

  private def frame(element: Element, width: Int, height: Int): Buffer =
    BufferAssertions.rendered(element.widget, width, height)

  private def lines(element: Element, width: Int, height: Int): Seq[String] =
    BufferAssertions.lines(frame(element, width, height))

  private val cols = Seq(Constraint.Fill(1), Constraint.Fill(1))
  private val rows = Seq(Constraint.Length(3), Constraint.Fill(1))

  // ---- grid ----

  private def dashboard(using Theme): Element =
    grid(cols, rows, gap = 1)(
      panel("top")(text("bar")).span(cols = 2),
      panel("main")(text("m")).at(1, 0),
      panel("side")(text("s")).at(1, 1),
    )

  test("a 2x2 grid with a top bar spanning both columns renders as recorded"):
    GoldenFrames.assertMatches("grid-span-top-bar", frame(dashboard, 25, 8))

  test("the spanning top bar reaches across both columns and the gap between them"):
    val out = lines(dashboard, 25, 8)
    assert(out(0).startsWith("┌top") && out(0).endsWith("┐"))
    assert(out(0).length == 25)
    assert(out(4).startsWith("┌main") && out(4).contains("┐ ┌side"))

  test("an areas template places children exactly as the explicit placements do"):
    val named = grid(cols, rows, gap = 1)(
      panel("top")(text("bar")).area("top"),
      panel("main")(text("m")).area("main"),
      panel("side")(text("s")).area("side"),
    ).areas("top top", "main side")
    BufferAssertions.assertEquals(frame(named, 25, 8), frame(dashboard, 25, 8))

  test("children that say nothing are auto-flowed in reading order around fixed ones"):
    val flowed = grid(cols, Seq(Constraint.Length(1), Constraint.Length(1)))(
      text("b").at(0, 1),
      text("a"),
      text("c"),
    )
    assert(lines(flowed, 4, 2) == Seq("a b ", "c   "))

  test("a scrollbar and a notice take .at(row, col) as a grid placement like every other node"):
    // both used to declare a member `at` of their own, which shadowed the grid placement on exactly these two nodes
    val placed = grid(Seq(Constraint.Length(1), Constraint.Length(1)), Seq(Constraint.Length(1)))(
      scrollbar(4).thumbAt(0).at(0, 1),
      text("a").at(0, 0),
    )
    assert(lines(placed, 2, 1) == Seq("a█"))
    val noted  = grid(Seq(Constraint.Fill(1)), Seq(Constraint.Length(1), Constraint.Length(1)))(
      text("top").at(0, 0),
      notice("hi", NoticeLevel.Info).stamped(java.time.LocalTime.of(9, 5)).at(1, 0),
    )
    assert(lines(noted, 20, 2).head.startsWith("top"))
    assert(lines(noted, 20, 2)(1).contains("hi"))

  test("no element node declares a member named `at`, so the grid placement is never shadowed"):
    // the placement extension itself is the one `at` there may be
    val Placement = "  def at(row: Int, col: Int): element.Self ="
    val root      = DocumentationSources.repoRoot.getOrElse(cancel("not running from a checkout"))
    val directory = root.resolve("dsl/src/main/scala/io/worxbend/tui/dsl")
    val offenders = Using.resource(Files.list(directory))(
      _.iterator.asScala
        .filter(path =>
          Files.readString(path).linesIterator.exists(line => line.startsWith("  def at(") && line != Placement)
        )
        .toVector
    )
    assert(offenders.isEmpty, offenders.mkString(", "))

  test("an Auto row is as tall as its content and the grid measures its rows"):
    val node = grid(Seq(Constraint.Fill(1)), Seq(Constraint.Auto, Constraint.Length(1)), gap = 1)(
      text("1\n2"),
      text("x"),
    )
    assert(node.intrinsicHeight(10).contains(2 + 1 + 1))
    assert(lines(node, 1, 4) == Seq("1", "2", " ", "x"))

  test("a malformed template fails where it is written"):
    intercept[IllegalArgumentException](grid(cols, rows)().areas("a a", "a b"))

  // ---- flow ----

  private def chips: Element = flow(gap = 1)(Seq.fill(4)(text("[chip-1]"))*)

  test("four 8-wide chips wrap onto two rows in a 20-wide area"):
    GoldenFrames.assertMatches("flow-four-chips", frame(chips, 20, 2))
    assert(lines(chips, 20, 2) == Seq("[chip-1] [chip-1]   ", "[chip-1] [chip-1]   "))
    assert(chips.intrinsicHeight(20).contains(2))

  test("a CJK chip counts double width when deciding where to wrap"):
    // "漢字漢字" is 8 columns: two of them fill 17 of 20 columns and push the third chip down
    val node = flow()(text("漢字漢字"), text("漢字漢字"), text("[ab]"))
    assert(node.intrinsicHeight(20).contains(2))
    assert(BufferAssertions.line(frame(node, 20, 2), 1).startsWith("[ab]"))

  test("rowGap separates the wrapped lines"):
    val node = flow(gap = 1, rowGap = 1)(text("aa"), text("bb"), text("cc"))
    assert(lines(node, 5, 3) == Seq("aa bb", "     ", "cc   "))

  // ---- dock ----

  test("dock puts top and bottom across the width and left and right beside the centre"):
    val node = dock(top = text("TT"), bottom = text("BB"), left = text("L"), right = text("R"))(text("c").fill)
    assert(lines(node, 5, 4) == Seq("TT   ", "Lc  R", "     ", "BB   "))

  test("a docked edge left out costs nothing"):
    assert(lines(dock(left = text("L"))(text("c")), 3, 1) == Seq("Lc "))
