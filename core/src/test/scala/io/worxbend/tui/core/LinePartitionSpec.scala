package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

final class LinePartitionSpec extends AnyFunSuite:
  private val samples      = Seq("e\u0301", "👩‍💻", "🇫🇮", "❤\ufe0f", "☀\ufe0e", "👍🏽")
  private val base         = Style.Default.withBg(Color.Blue)
  private val owner        = Style.Default.withFg(Color.Red)
  private val continuation = Style.Default.withFg(Color.Green).italic

  samples.foreach { cluster =>
    test(s"buffer clipping is invariant under every span partition: $cluster"):
      val whole = Line(Seq(Span(cluster, owner), Span("xy", continuation)), style = Style.Default.bold)
      for
        split  <- 1 until cluster.length
        budget <- 0 to 5
        x      <- Seq(-1, 0, 2, 5)
      do
        val splitLine     = whole.copy(spans =
          Seq(Span(cluster.take(split), owner), Span(cluster.drop(split), continuation), Span("xy", continuation))
        )
        val expected      = Buffer(Rect(0, 0, 6, 1))
        val actual        = Buffer(expected.area)
        val expectedWidth = expected.setLine(x, 0, whole, budget, base)
        assert(actual.setLine(x, 0, splitLine, budget, base) == expectedWidth)
        for column <- 0 until 6 do assert(actual.get(column, 0) == expected.get(column, 0))

    test(s"line traversal owns a cross-span cluster at its first code unit: $cluster"):
      // Include splits inside surrogate pairs, empty fragments and splits inside every Unicode continuation.
      for split <- 1 until cluster.length do
        val line     = Line(
          Seq(
            Span.raw(""),
            Span(cluster.take(split), owner),
            Span.raw(""),
            Span(cluster.drop(split), continuation),
            Span("x", continuation),
          ),
          style = Style.Default.bold,
        )
        val clusters = line.styledGraphemes(base).toVector
        assert(
          clusters == Vector(
            StyledGrapheme(cluster, base.patch(line.style).patch(owner)),
            StyledGrapheme("x", base.patch(line.style).patch(continuation)),
          )
        )
        for mode <- Seq(WidthMode.Narrow, WidthMode.Wide) do
          assert(line.widthIn(mode) == CharWidth.of(cluster + "x", mode))
  }
