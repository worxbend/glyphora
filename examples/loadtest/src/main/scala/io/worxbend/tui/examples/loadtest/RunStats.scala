package io.worxbend.tui.examples.loadtest

/** One completed request, as the workers hand it back.
  *
  * Latency is carried as whole microseconds rather than a `FiniteDuration` because everything downstream wants a
  * number: `Sparkline` takes `Seq[Long]`, percentiles want a sortable key, and the histogram buckets by arithmetic.
  */
enum Sample:
  case Ok(micros: Long)
  case Failed(reason: String)

/** Everything the screen knows about a run, as one immutable value.
  *
  * One value rather than five separate signals on purpose. The tick that drains a batch of results writes it once, so
  * the view always sees a consistent snapshot (never "sent" updated but "ok" not yet) and the frame is invalidated once
  * instead of five times.
  *
  * `summary` and `histogram` are stored fields, folded in by [[record]] as each batch arrives, rather than `Computed`s
  * derived from `latencies` on every read: the latency vector grows to a million samples, and a derived value over it
  * would re-sort the whole thing on every render tick while a run is in flight. `record` keeps `latencies` sorted by
  * merging each batch in (never re-sorting), so the percentile indices read straight off it; the histogram only pays
  * for a full rebuild when a batch moves its min..max edges, which means a sample set a new fastest or slowest time.
  */
final case class RunStats(
    sent: Int,
    ok: Int,
    failed: Int,
    latencies: Vector[Long],
    errors: Map[String, Int],
    summary: LatencySummary,
    histogram: Vector[LatencyBucket],
):

  /** Folds a whole drained batch in at once — per-sample `copy` would rebuild the error map thousands of times a second
    * for no benefit.
    *
    * The cost is O(batch) plus one merge into the sorted `latencies`; the only O(n) path left is the histogram rebuild
    * on a new extreme, and even a million-request run does that a handful of times.
    */
  def record(batch: Vector[Sample], histogramBuckets: Int): RunStats =
    if batch.isEmpty then this
    else
      val successes = batch.collect { case Sample.Ok(micros) => micros }.sorted
      val failures  = batch.collect { case Sample.Failed(reason) => reason }
      val merged    = mergeSorted(latencies, successes)
      RunStats(
        sent = sent + batch.size,
        ok = ok + successes.size,
        failed = failed + failures.size,
        latencies = merged,
        errors = failures.foldLeft(errors)((tally, reason) => tally.updated(reason, tally.getOrElse(reason, 0) + 1)),
        summary = LatencySummary.ofSorted(merged),
        histogram = foldHistogram(successes, merged, histogramBuckets),
      )

  /** Merges `incoming` (already sorted) into `existing` (already sorted) in O(n + m) — re-sorting the accumulated
    * vector on every batch would be O(n log n) per render tick.
    */
  private def mergeSorted(existing: Vector[Long], incoming: Vector[Long]): Vector[Long] =
    val builder = Vector.newBuilder[Long]
    var left    = 0
    var right   = 0
    while left < existing.length && right < incoming.length do
      if existing(left) <= incoming(right) then
        builder += existing(left)
        left += 1
      else
        builder += incoming(right)
        right += 1
    builder.addAll(existing.drop(left)).addAll(incoming.drop(right))
    builder.result()

  /** Adds the batch's successes onto the current histogram. When the batch leaves the min..max span unchanged every
    * existing bucket edge still partitions the data the same way, so the counts update in place; a new extreme moves
    * every edge, and the only correct response is to rebuild from the merged latencies. Both paths produce exactly what
    * `Histogram.of` would return for the same multiset.
    */
  private def foldHistogram(successes: Vector[Long], merged: Vector[Long], buckets: Int): Vector[LatencyBucket] =
    val edgesUnmoved = histogram.nonEmpty && histogram.length == buckets && merged.nonEmpty &&
      histogram.head.lowMicros == merged.head &&
      histogram.last.highMicros == merged.head + math.max(1L, merged.last - merged.head)
    if successes.isEmpty then histogram
    else if edgesUnmoved then
      val low    = histogram.head.lowMicros
      val span   = math.max(1L, histogram.last.highMicros - low)
      val counts = histogram.map(_.count).toArray
      successes.foreach { value =>
        val index = Histogram.bucketIndex(value, low, buckets, span)
        counts(index) += 1
      }
      histogram.zipWithIndex.map((bucket, index) => bucket.copy(count = counts(index)))
    else Histogram.of(merged, buckets)

object RunStats:
  val empty: RunStats = RunStats(
    sent = 0,
    ok = 0,
    failed = 0,
    latencies = Vector.empty,
    errors = Map.empty,
    summary = LatencySummary.empty,
    histogram = Vector.empty,
  )

/** The percentile block, all in microseconds. */
final case class LatencySummary(count: Int, min: Long, max: Long, mean: Long, p50: Long, p90: Long, p99: Long)

object LatencySummary:

  val empty: LatencySummary = LatencySummary(0, 0L, 0L, 0L, 0L, 0L, 0L)

  /** Over an already-sorted vector every statistic is either an endpoint or one indexed read; sorting here too would be
    * the O(n log n) per-render-tick cost `RunStats.record` is written to avoid.
    */
  def ofSorted(sorted: Vector[Long]): LatencySummary =
    if sorted.isEmpty then empty
    else
      LatencySummary(
        count = sorted.size,
        min = sorted.head,
        max = sorted.last,
        mean = sorted.sum / sorted.size,
        p50 = percentile(sorted, 0.50),
        p90 = percentile(sorted, 0.90),
        p99 = percentile(sorted, 0.99),
      )

  /** Nearest-rank percentile over an already-sorted vector. The toolkit ships no statistics helpers — this is the whole
    * of what a load test needs, so it lives here rather than being hunted for.
    */
  private def percentile(sorted: Vector[Long], quantile: Double): Long =
    sorted(math.min(sorted.size - 1, (sorted.size * quantile).toInt))

/** One bar of the latency histogram: a half-open microsecond range and how many samples landed in it. */
final case class LatencyBucket(lowMicros: Long, highMicros: Long, count: Int)

object Histogram:

  private[loadtest] def bucketIndex(value: Long, low: Long, buckets: Int, span: Long): Int =
    math.min(buckets - 1, ((value - low) * buckets / span).toInt)

  /** Equal-width buckets spanning min..max of the sample set.
    *
    * The edges move as the run goes on, which is the honest thing to draw: a fixed scale chosen from the first hundred
    * requests hides the tail that shows up in the last thousand.
    */
  def of(latencies: Vector[Long], buckets: Int): Vector[LatencyBucket] =
    if latencies.isEmpty || buckets <= 0 then Vector.empty
    else
      val low    = latencies.min
      val span   = math.max(1L, latencies.max - low)
      val counts = Array.fill(buckets)(0)
      latencies.foreach { value =>
        val index = bucketIndex(value, low, buckets, span)
        counts(index) += 1
      }
      Vector.tabulate(buckets) { index =>
        LatencyBucket(low + span * index / buckets, low + span * (index + 1) / buckets, counts(index))
      }
