package io.worxbend.tui.examples.loadtest

import org.scalatest.funsuite.AnyFunSuite

final class RunStatsSpec extends AnyFunSuite:

  private def record(values: Vector[Long], buckets: Int): RunStats =
    RunStats.empty.record(values.map(Sample.Ok.apply), buckets)

  test("an empty batch returns the same instance even when the requested bucket count changes"):
    val populated = record(Vector(0L, 8L), 4)
    assert(RunStats.empty.record(Vector.empty, 0) eq RunStats.empty)
    assert(populated.record(Vector.empty, 2) eq populated)
    assert(populated.record(Vector.empty, -1) eq populated)

  test("failed batches tally repeated reasons without changing latency data or histogram configuration"):
    val first     = RunStats.empty.record(Vector(Sample.Failed("timeout"), Sample.Failed("timeout")), 4)
    assert(first == RunStats.empty.copy(sent = 2, failed = 2, errors = Map("timeout" -> 2)))
    val populated = first.record(Vector(Sample.Ok(8L), Sample.Ok(0L), Sample.Failed("reset")), 4)
    val next      = populated.record(Vector(Sample.Failed("timeout"), Sample.Failed("reset")), 2)
    assert(next.sent == 7)
    assert(next.ok == 2)
    assert(next.failed == 5)
    assert(next.errors == Map("timeout" -> 3, "reset" -> 2))
    assert(next.latencies == populated.latencies)
    assert(next.summary == populated.summary)
    assert(next.histogram == populated.histogram)

  test("sorted merging retains duplicates and equal endpoints across unsorted batches"):
    val initial = record(Vector(8L, 0L, 4L), 4)
    val next    = initial.record(Vector(Sample.Ok(4L), Sample.Ok(8L), Sample.Ok(2L), Sample.Ok(0L), Sample.Ok(6L)), 4)
    assert(next.latencies == Vector(0L, 0L, 2L, 4L, 4L, 6L, 8L, 8L))
    assert(next.sent == 8)
    assert(next.ok == 8)
    assert(next.failed == 0)
    assert(next.errors.isEmpty)
    assert(next.summary == LatencySummary(8, 0L, 8L, 4L, 4L, 8L, 8L))
    assert(
      next.histogram == Vector(
        LatencyBucket(0L, 2L, 2),
        LatencyBucket(2L, 4L, 1),
        LatencyBucket(4L, 6L, 2),
        LatencyBucket(6L, 8L, 3),
      )
    )
    assert(initial.latencies == Vector(0L, 4L, 8L))

  test("unchanged edges increment counts exactly as a full rebuild including boundaries and the maximum"):
    val initial = record(Vector(0L, 8L), 4)
    val next    = initial.record(Vector(Sample.Ok(2L), Sample.Ok(4L), Sample.Ok(6L)), 4)
    assert(
      next.histogram == Vector(
        LatencyBucket(0L, 2L, 1),
        LatencyBucket(2L, 4L, 1),
        LatencyBucket(4L, 6L, 1),
        LatencyBucket(6L, 8L, 2),
      )
    )
    assert(next.histogram == Histogram.of(next.latencies, 4))
    assert(
      next.histogram.map(bucket => (bucket.lowMicros, bucket.highMicros)) ==
        initial.histogram.map(bucket => (bucket.lowMicros, bucket.highMicros))
    )

  test("expanding either extreme rebuilds the histogram"):
    val initial = record(Vector(0L, 2L, 4L, 6L, 8L), 4)
    val lower   = initial.record(Vector(Sample.Ok(-4L)), 4)
    val higher  = initial.record(Vector(Sample.Ok(12L)), 4)
    val both    = lower.record(Vector(Sample.Ok(12L)), 4)
    assert(lower.histogram == Histogram.of(lower.latencies, 4))
    assert(higher.histogram == Histogram.of(higher.latencies, 4))
    assert(lower.histogram.head.lowMicros == -4L)
    assert(higher.histogram.last.highMicros == 12L)
    assert(
      both.histogram == Vector(
        LatencyBucket(-4L, 0L, 1),
        LatencyBucket(0L, 4L, 2),
        LatencyBucket(4L, 8L, 2),
        LatencyBucket(8L, 12L, 2),
      )
    )

  test("a successful batch rebuilds when the bucket count changes without moving extrema"):
    val initial = record(Vector(0L, 8L), 4)
    val next    = initial.record(Vector(Sample.Ok(2L), Sample.Ok(4L), Sample.Ok(6L)), 3)
    assert(next.histogram == Vector(LatencyBucket(0L, 2L, 2), LatencyBucket(2L, 5L, 1), LatencyBucket(5L, 8L, 2)))
    assert(next.histogram == Histogram.of(next.latencies, 3))

  test("equal values use a unit span and can leave zero-width buckets"):
    val initial = record(Vector(7L), 4)
    val next    = initial.record(Vector(Sample.Ok(7L), Sample.Ok(7L)), 4)
    assert(
      next.histogram == Vector(
        LatencyBucket(7L, 7L, 3),
        LatencyBucket(7L, 7L, 0),
        LatencyBucket(7L, 7L, 0),
        LatencyBucket(7L, 8L, 0),
      )
    )
    assert(next.histogram == Histogram.of(next.latencies, 4))

  test("bounded input matrices retain full-rebuild parity after every contiguous batch partition"):
    val inputs = Vector(
      Vector(0L, 8L, 4L, 2L, 6L, 4L),
      Vector(4L, 4L, 4L, 4L, 4L, 4L),
      Vector(4L, 2L, 6L, -2L, 10L, 4L),
      Vector(-8L, -2L, -6L, -4L, -2L, -8L),
      Vector(Long.MinValue, Long.MaxValue, 0L, -1L, Long.MaxValue, Long.MinValue),
      Vector(Long.MaxValue, Long.MaxValue, Long.MaxValue),
    )
    for
      values  <- inputs
      buckets <- Vector(1, 2, 3, 4, 7)
      mask    <- 0 until (1 << (values.size - 1))
    do
      val cuts    = Vector(0) ++ (1 until values.size).filter(index => (mask & (1 << (index - 1))) != 0) ++
        Vector(values.size)
      val batches = cuts.zip(cuts.tail).map((start, end) => values.slice(start, end))
      batches.foldLeft(RunStats.empty) { (stats, batch) =>
        val next     = stats.record(batch.map(Sample.Ok.apply), buckets)
        val expected = (stats.latencies ++ batch).sorted
        assert(next.latencies == expected, s"values=$values buckets=$buckets mask=$mask batch=$batch")
        assert(next.histogram == Histogram.of(expected, buckets))
        assert(next.histogram.map(_.count).sum == expected.size)
        assert(next.summary == LatencySummary.ofSorted(expected))
        next
      }

  test("summary uses integer mean and the current zero-based percentile indices"):
    assert(LatencySummary.ofSorted(Vector.empty) == LatencySummary.empty)
    assert(LatencySummary.ofSorted(Vector(1L, 2L, 3L, 4L)) == LatencySummary(4, 1L, 4L, 2L, 3L, 4L, 4L))
    assert(LatencySummary.ofSorted((1L to 10L).toVector) == LatencySummary(10, 1L, 10L, 5L, 6L, 10L, 10L))
    assert(LatencySummary.ofSorted(Vector(-4L, -1L)) == LatencySummary(2, -4L, -1L, -2L, -1L, -1L, -1L))
    assert(LatencySummary.ofSorted(Vector(9L)) == LatencySummary(1, 9L, 9L, 9L, 9L, 9L, 9L))

  test("invalid bucket counts return no buckets on success but failed-only batches keep the previous histogram"):
    for buckets <- Vector(0, -1, Int.MinValue) do
      assert(Histogram.of(Vector(1L, 2L), buckets).isEmpty)
      val initial  = record(Vector(0L, 8L), 4)
      val failed   = initial.record(Vector(Sample.Failed("timeout")), buckets)
      assert(failed.histogram == initial.histogram)
      val next     = initial.record(Vector(Sample.Ok(4L)), buckets)
      assert(next.histogram.isEmpty)
      assert(next.latencies == Vector(0L, 4L, 8L))
      val restored = next.record(Vector(Sample.Ok(2L)), 4)
      assert(restored.histogram == Histogram.of(restored.latencies, 4))
    assert(Histogram.of(Vector.empty, 4).isEmpty)

  test("Long extrema retain bounded edges and an exact mean without wrapping"):
    val initial = record(Vector(Long.MaxValue), 1)
    val next    = initial.record(Vector(Sample.Ok(Long.MaxValue)), 1)
    assert(initial.histogram == Vector(LatencyBucket(Long.MaxValue, Long.MaxValue, 1)))
    assert(next.histogram == Vector(LatencyBucket(Long.MaxValue, Long.MaxValue, 2)))
    assert(next.histogram == Histogram.of(next.latencies, 1))
    assert(
      next.summary == LatencySummary(
        2,
        Long.MaxValue,
        Long.MaxValue,
        Long.MaxValue,
        Long.MaxValue,
        Long.MaxValue,
        Long.MaxValue,
      )
    )

  test("large multiplication and signed spans classify without overflow on rebuild and increment"):
    for values <- Vector(Vector(0L, 4611686018427387904L), Vector(Long.MinValue, Long.MaxValue)) do
      val initial = record(values, 3)
      assert(initial.histogram.map(_.count) == Vector(1, 0, 1))
      assert(initial.histogram.head.lowMicros == values.head)
      assert(initial.histogram.last.highMicros == values.last)
      val next    = initial.record(Vector(Sample.Ok(values.last)), 3)
      assert(next.histogram.map(_.count) == Vector(1, 0, 2))
      assert(next.histogram == Histogram.of(next.latencies, 3))
      assert(next.summary.mean == ((values.map(BigInt(_)).sum + BigInt(values.last)) / 3).toLong)
    assert(LatencySummary.ofSorted(Vector(Long.MinValue, Long.MaxValue)).mean == 0L)
