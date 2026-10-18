package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Modifiers, Rect}
import io.worxbend.tui.testsupport.BufferAssertions.{line, rendered}
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

final class BreadcrumbSpec extends AnyFunSuite:

  private val trail = Seq("Home", "Projects", "glyphora", "widgets", "Breadcrumb")

  test("a trail that fits is drawn whole, separated, with the last item bold"):
    val buffer = rendered(Breadcrumb(Seq("Home", "Docs", "API")), 20, 1)
    assert(line(buffer, 0) == "Home › Docs › API   ")
    assert(buffer.get(14, 0).style.modifiers.hasAny(Modifiers.Bold))
    assert(!buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Bold))

  test("a trail too wide folds its middle items into … from the front, keeping the first and the last"):
    val crumb = Breadcrumb(trail)
    assert(line(rendered(crumb, 42, 1), 0) == "Home › … › glyphora › widgets › Breadcrumb")
    assert(line(rendered(crumb, 34, 1), 0) == "Home › … › widgets › Breadcrumb   ")
    assert(line(rendered(crumb, 30, 1), 0).trim == "Home › … › Breadcrumb")
    GoldenFrames.assertMatches("breadcrumb-truncated", rendered(crumb, 30, 1))

  test("narrower than first … last, the first item goes too and finally the last is clipped by display width"):
    assert(line(rendered(Breadcrumb(trail), 20, 1), 0).trim == "… › Breadcrumb")
    assert(line(rendered(Breadcrumb(Seq("首页", "设置", "显示器")), 10, 1), 0) == "… › 显示器")
    assert(line(rendered(Breadcrumb(trail), 6, 1), 0) == "Bread…")

  test("segments say which item every column belongs to, so a click can be resolved"):
    val segments = Breadcrumb(trail).segments(34)
    assert(segments.map(_.item) == Seq(Some(0), None, Some(3), Some(4)))
    assert(segments.map(_.x) == Seq(0, 7, 11, 21))
    assert(Breadcrumb(trail).itemAt(34, 12).contains(3))
    assert(Breadcrumb(trail).itemAt(34, 7).isEmpty)

  test("it measures one row and its full width, and draws nothing into an empty area"):
    assert(Breadcrumb(Seq("Home", "Docs")).widthAt(1).contains(11))
    assert(Breadcrumb(Seq("Home", "Docs")).heightAt(5).contains(1))
    assert(Breadcrumb(Seq.empty).widthAt(1).contains(0))
    val buffer = rendered(Breadcrumb(trail), 10, 2)
    Breadcrumb(trail).render(Rect(0, 0, 0, 0), buffer)
