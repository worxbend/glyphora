package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Buffer, CharWidth, Line, Rect, Span, Style}

import org.scalatest.funsuite.AnyFunSuite

import java.lang.management.ManagementFactory

final class LineRendererCostSpec extends AnyFunSuite:

  test("the rendering prefix reads only the viewport and its first non-fitting cluster") {
    for clusterCount <- Seq(1000, 100000); symbol <- Seq("a", "漢", "👩‍💻", "a\u0301"); budget <- Seq(1, 3, 8) do
      var reads              = 0
      val width              = CharWidth.of(symbol)
      val limit              = (budget + width - 1) / width
      val clusters           = new Iterator[String]:
        def hasNext: Boolean = reads < clusterCount
        def next(): String   =
          reads += 1
          assert(reads <= limit, s"read invisible cluster $reads beyond a $budget-column viewport")
          symbol
      val (prefix, measured) = LineRenderer.boundedPrefix(clusters, budget)
      assert(reads == limit)
      assert(prefix == symbol * limit)
      assert(measured == width * limit)
  }

  test("a premeasured narrow viewport does not allocate for an unrendered Unicode tail") {
    ManagementFactory.getThreadMXBean match
      case bean: com.sun.management.ThreadMXBean =>
        assume(bean.isThreadAllocatedMemorySupported)
        if !bean.isThreadAllocatedMemoryEnabled then bean.setThreadAllocatedMemoryEnabled(true)
        val buffer                            = Buffer(Rect(0, 0, 1, 1))
        val short                             = Line(Vector(Span.raw("漢"), Span.raw("x")))
        val long                              = Line(Vector(Span.raw("漢" * 100000), Span.raw("x")))
        def draw(line: Line, width: Int): Int =
          LineRenderer.render(buffer, 0, 0, line, 1, Style.Default, Alignment.Left, 0, width)
        // Count allocated bytes, not elapsed time; construct and premeasure input outside the measured call.
        (0 until 100).foreach(_ => draw(short, 3))
        val thread                            = Thread.currentThread().threadId()
        val before                            = bean.getThreadAllocatedBytes(thread)
        val drawn                             = draw(long, 200001)
        val bytes                             = bean.getThreadAllocatedBytes(thread) - before
        info(s"allocated bytes for a one-column viewport: $bytes")
        assert(drawn == 0)
        assert(buffer.get(0, 0).symbol == " ")
        // Leave ample room for cold/JIT allocations, but not a String/iterator allocation per invisible cluster.
        assert(bytes < 65536, s"unrendered tail allocated $bytes bytes")
      case _                                     => cancel("this JVM does not expose per-thread allocated bytes")
  }
