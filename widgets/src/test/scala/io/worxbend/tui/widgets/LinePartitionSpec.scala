package io.worxbend.tui.widgets

import io.worxbend.tui.core.*
import org.scalatest.funsuite.AnyFunSuite

final class LinePartitionSpec extends AnyFunSuite:
  private val samples = Seq("e\u0301", "👩‍💻", "🇫🇮", "❤\ufe0f", "☀\ufe0e", "👍🏽")
  private val owner   = Style.Default.withFg(Color.Red)
  private val other   = Style.Default.withFg(Color.Green).italic

  samples.foreach { cluster =>
    test(s"wrapping and paragraph measurement ignore span partitions: $cluster"):
      val whole = Line(
        Seq(Span("a " + cluster, owner), Span("xy z", other)),
        alignment = Some(Alignment.Right),
        style = Style.Default.bold,
      )
      for
        split  <- 1 until cluster.length
        width  <- 1 to 6
        blanks <- Seq(WrapBlanks.KeepIndent, WrapBlanks.DropAll, WrapBlanks.KeepAll)
      do
        val partitioned = whole.copy(spans =
          Seq(Span("a " + cluster.take(split), owner), Span(cluster.drop(split), other), Span("xy z", other))
        )
        val expected    = Paragraph.wrapLine(whole, width, blanks)
        val actual      = Paragraph.wrapLine(partitioned, width, blanks)
        assert(actual.map(_.plainText) == expected.map(_.plainText))
        assert(
          actual.map(_.styledGraphemes(Style.Default).toVector) ==
            expected.map(_.styledGraphemes(Style.Default).toVector)
        )
        assert(Paragraph.wrappedRowCount(partitioned, width, blanks) == expected.size)
        assert(actual.forall(_.alignment == whole.alignment))
        assert(actual.forall(_.style == whole.style))
      for
        width    <- 1 to 6
        overflow <- Seq(Overflow.Clip, Overflow.Wrap, Overflow.WrapTrimmed, Overflow.WrapPreserved)
      do
        val partitioned =
          whole.copy(spans = whole.spans.flatMap(span => span.content.map(char => Span(char.toString, span.style))))
        val expected    = Paragraph(Text(Seq(whole)), overflow = overflow)
        val actual      = Paragraph(Text(Seq(partitioned)), overflow = overflow)
        assert(actual.heightAt(width) == expected.heightAt(width))
        assert(actual.widthAt(1) == expected.widthAt(1))
        val left        = Buffer(Rect(1, 1, width, 12))
        val right       = Buffer(left.area)
        expected.render(left.area, left)
        actual.render(right.area, right)
        assert(left == right)

    test(s"aligned and scrolled line rendering ignores span partitions: $cluster"):
      val whole = Line(Seq(Span(cluster, owner), Span("xy", other)), style = Style.Default.bold)
      for
        split     <- 1 until cluster.length
        width     <- 0 to 5
        skip      <- 0 to 4
        alignment <- Seq(Alignment.Left, Alignment.Center, Alignment.Right)
      do
        val partitioned   = whole.copy(spans =
          Seq(Span(cluster.take(split), owner), Span.raw(""), Span(cluster.drop(split), other), Span("xy", other))
        )
        val expected      = Buffer(Rect(0, 0, 7, 1))
        val actual        = Buffer(expected.area)
        val expectedWidth = LineRenderer.render(expected, 1, 0, whole, width, alignment = alignment, skipWidth = skip)
        assert(
          LineRenderer.render(
            actual,
            1,
            0,
            partitioned,
            width,
            alignment = alignment,
            skipWidth = skip,
          ) == expectedWidth
        )
        for column <- 0 until 7 do assert(actual.get(column, 0) == expected.get(column, 0))
  }
