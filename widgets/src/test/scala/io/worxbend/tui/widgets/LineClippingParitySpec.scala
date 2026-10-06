package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Buffer, CharWidth, Color, Line, Rect, Span, Style}

import org.scalatest.funsuite.AnyFunSuite

final class LineClippingParitySpec extends AnyFunSuite:

  test("wide truncation stops subsequent spans in both plain and scrolled rendering") {
    Seq(0, 1, 2).foreach { skip =>
      val prefix   = "a" * skip
      val plain    = Line.raw(prefix + "漢x")
      val split    = Line(Vector(Span.raw(prefix), Span.raw("漢"), Span.raw("x")))
      val expected = Buffer(Rect(0, 0, 1, 1))
      val actual   = Buffer(Rect(0, 0, 1, 1))
      LineRenderer.render(expected, 0, 0, plain, 1, skipWidth = skip)
      LineRenderer.render(actual, 0, 0, split, 1, skipWidth = skip)
      assert(actual.get(0, 0) == expected.get(0, 0))
      assert(actual.get(0, 0).symbol == " ")
    }
  }

  test("partitioning at every grapheme boundary preserves clipped symbols") {
    val clusters = CharWidth.graphemeClusters("a漢b👩‍💻cd").toVector
    val plain    = Line.raw(clusters.mkString)
    val split    = Line(clusters.map(Span.raw))
    for width <- 1 to 8; skip <- 0 to 7 do
      val expected = Buffer(Rect(0, 0, width, 1))
      val actual   = Buffer(Rect(0, 0, width, 1))
      LineRenderer.render(expected, 0, 0, plain, width, skipWidth = skip)
      LineRenderer.render(actual, 0, 0, split, width, skipWidth = skip)
      assert((0 until width).map(actual.get(_, 0).symbol) == (0 until width).map(expected.get(_, 0).symbol))
  }

  test("clipping keeps style layering of the surviving span and never draws the tail") {
    val line   = Line(Vector(Span("a", Style.Default.withFg(Color.Red)), Span.raw("漢"), Span.raw("x")))
    val buffer = Buffer(Rect(0, 0, 2, 1))
    assert(LineRenderer.render(buffer, 0, 0, line, 2, Style.Default.bold) == 1)
    assert(buffer.get(0, 0).style == Style.Default.bold.withFg(Color.Red))
    assert(buffer.get(1, 0).symbol == " ")
  }

  test("bounded prefixes match full-span rendering across styles, scrolling, alignment and buffer edges") {
    val line = Line(
      Vector(
        Span("\u0000\u0301a", Style.Default.withFg(Color.Red)),
        Span.raw(""),
        Span("漢👩‍💻", Style.Default.withFg(Color.Blue)),
        Span("\u0301b\u0000c", Style.Default.italic),
      ),
      style = Style.Default.bold,
    )
    val base = Style.Default.withBg(Color.Green)
    for
      bufferWidth <- 1 to 8
      budget      <- 1 to 8
      skip        <- 0 to 6
      alignment   <- Seq(Alignment.Left, Alignment.Center, Alignment.Right)
      x           <- Seq(0, 2, 4)
    do
      val expected = Buffer(Rect(2, 1, bufferWidth, 1))
      val actual   = Buffer(expected.area)
      val start    = alignment.originAt(x, budget, math.max(0, line.width - skip))
      var at       = start
      var skipped  = skip
      var stopped  = false
      // Deliberately measure whole spans here: an independent, unbounded oracle for the optimized render path.
      val spans    = line.spans.iterator
      while spans.hasNext && at < x + budget && !stopped do
        val span  = spans.next()
        val text  =
          if skipped <= 0 then span.content
          else if span.width <= skipped then
            skipped -= span.width
            ""
          else
            val tail = CharWidth.dropByWidth(span.content, skipped)
            skipped = 0
            tail
        val drawn = expected.setString(at, 1, text, base.patch(line.style).patch(span.style), x + budget - at)
        at += drawn
        stopped = drawn < CharWidth.of(text)
      val written  = LineRenderer.render(actual, x, 1, line, budget, base, alignment, skip, line.width)
      assert(written == at - x)
      assert(actual == expected, s"bufferWidth=$bufferWidth budget=$budget skip=$skip alignment=$alignment x=$x")
  }
