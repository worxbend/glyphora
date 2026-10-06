package io.worxbend.tui.widgets

import org.scalatest.funsuite.AnyFunSuite

final class ClusterRowScrollCostSpec extends AnyFunSuite:

  /** Counts actual cluster reads, including reads performed by collection slicing/iteration. No clock threshold. */
  private final class CountedClusters(val length: Int, symbol: String) extends IndexedSeq[String]:
    var reads: Long               = 0
    def apply(index: Int): String =
      assert(index >= 0 && index < length)
      reads += 1
      symbol

  test("initial scrolling and an End jump read at most a linear number of clusters") {
    Seq(1000, 10000).foreach { size =>
      Seq("a", "漢", "\u0301").foreach { symbol =>
        val clusters = CountedClusters(size, symbol)
        val scroll   = ClusterRow.scrolledTo(clusters, 0, size, 20)
        val expected = size - 19 / ClusterRow.renderedWidth(symbol)
        assert(scroll == expected)
        info(s"size=$size symbol=$symbol reads=${clusters.reads}")
        assert(clusters.reads <= 3L * size + 40, s"quadratic scroll: ${clusters.reads} reads for $size clusters")
      }
    }
  }

  test("linear scrolling preserves the original smallest-change rule across cursor and viewport combinations") {
    val clusters = Vector("a", "漢", "\u0301", "👩‍💻", "b", "c")
    for width <- 1 to 15; cursor <- 0 to clusters.size; scroll <- 0 to clusters.size + 2 do
      val cursorWidth = if cursor < clusters.size then ClusterRow.renderedWidth(clusters(cursor)) else 1
      var expected    = math.min(math.min(scroll, cursor), ClusterRow.rightmostUsefulScroll(clusters, width))
      while ClusterRow.visibleWidth(clusters, expected, cursor) + cursorWidth > width && expected < cursor do
        expected += 1
      assert(ClusterRow.scrolledTo(clusters, scroll, cursor, width) == expected)
  }
