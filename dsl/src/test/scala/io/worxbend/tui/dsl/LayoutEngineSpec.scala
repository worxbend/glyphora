package io.worxbend.tui.dsl

import io.worxbend.tui.testsupport.{BufferAssertions, GoldenFrames}
import io.worxbend.tui.widgets.Paragraph

import org.scalatest.funsuite.AnyFunSuite

/** The 0.20.0 layout vocabulary on elements: `.auto`, compound `.minSize`/`.maxSize`, `intrinsicWidth`, and cross-axis
  * alignment with `.alignItems` / `.alignSelf`.
  */
final class LayoutEngineSpec extends AnyFunSuite:

  private def render(element: Element, width: Int, height: Int): Seq[String] =
    BufferAssertions.lines(BufferAssertions.rendered(element.widget, width, height))

  // ---- intrinsicWidth ----

  test("text measures its display width, a wide character counting two columns"):
    assert(text("abc").intrinsicWidth(1).contains(3))
    assert(text("漢字").intrinsicWidth(1).contains(4))
    assert(text("ab\nabcde").intrinsicWidth(2).contains(5))
    assert(line("ab", "cd").intrinsicWidth(1).contains(4))

  test("rows sum their children's widths and gaps, columns take the widest, panels add their frame"):
    assert(row(text("ab"), text("cde")).gap(1).intrinsicWidth(1).contains(6))
    assert(column(text("ab"), text("cdef")).intrinsicWidth(2).contains(4))
    assert(panel(text("abc")).intrinsicWidth(3).contains(5))
    assert(row(text("ab"), spacer).intrinsicWidth(1).isEmpty) // a fill child cannot say
    assert(text("ab").length(7).intrinsicWidth(1).contains(7))
    assert(text("ab").fill.intrinsicWidth(1).isEmpty)

  // ---- .auto ----

  test("an auto child of a column is exactly as tall as it measures"):
    val tall = widget(Paragraph(Text.raw("1\n2\n3"))).auto
    assert(render(column(tall, text("x").fill), 1, 5) == Seq("1", "2", "3", "x", " "))

  test("an auto child of a row is exactly as wide as it measures, and measures as its content"):
    val node = row(widget(Paragraph(Text.raw("abc"))).auto, text("|").fill)
    assert(render(node, 6, 1) == Seq("abc|  "))
    assert(text("abc").auto.intrinsicWidth(1).contains(3))

  // ---- compound constraints ----

  test("minSize, maxSize and a size compose into one bounded constraint, in either order"):
    assert(
      spacer.minSize(20).maxSize(60).fill.props.constraint.contains(Constraint.Bounded(20, 60, Constraint.Fill(1)))
    )
    assert(
      spacer.fill.maxSize(60).minSize(20).props.constraint.contains(Constraint.Bounded(20, 60, Constraint.Fill(1)))
    )
    assert(spacer.auto.maxSize(30).props.constraint.contains(Constraint.Bounded(0, 30, Constraint.Auto)))
    assert(spacer.minSize(8).props.constraint.contains(Constraint.Min(8)), "a floor on its own is still a Min")
    assert(spacer.maxSize(8).props.constraint.contains(Constraint.Max(8)), "a ceiling on its own is still a Max")

  test("a bounded fill stops at its ceiling beside a plain fill"):
    val node = row(text("a").fill.maxSize(8), text("b").fill)
    assert(render(node, 20, 1) == Seq("a" + " " * 7 + "b" + " " * 11))

  test("a bounded auto measures as its content held inside the bounds"):
    assert(text("abcdef").auto.maxSize(4).intrinsicWidth(1).contains(4))
    assert(text("ab").auto.minSize(5).intrinsicWidth(1).contains(5))

  // ---- cross-axis alignment ----

  test("a centred 10-wide child of a 30-wide column paints at x=10"):
    val frame = BufferAssertions.rendered(column(text("0123456789").alignSelf(CrossAlign.Center)).widget, 30, 1)
    GoldenFrames.assertMatches("cross-align-center", frame)
    assert(BufferAssertions.line(frame, 0) == " " * 10 + "0123456789" + " " * 10)

  test("alignItems places every child, alignSelf overrides it, and an unmeasurable child stretches"):
    val node = column(
      text("ab"),
      text("cd").alignSelf(CrossAlign.Start),
      widget((area, buffer) => buffer.setString(area.x, area.y, "?" * area.width, Style.Default))
        .rows(1)
        .alignSelf(CrossAlign.Center),
    ).alignItems(CrossAlign.End)
    assert(render(node, 6, 3) == Seq("    ab", "cd    ", "??????"))

  test("a row aligns its children vertically"):
    val node = row(text("a"), widget(Paragraph(Text.raw("1\n2\n3")))).alignItems(CrossAlign.End)
    assert(render(node, 2, 3) == Seq(" 1", " 2", "a3"))
