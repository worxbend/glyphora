---
title: Build a load generator
description: Build an HTTP load generator in glyphora — concurrent workers off the render thread, a live histogram and throughput trace, percentiles, and a summary screen when the run ends.
---

# Build a load generator

In this tutorial you will build `loadtest`: a terminal load generator in the shape
of `oha`. It fires thousands of requests from a pool of worker threads while the
screen keeps a live progress bar, a throughput trace, a latency histogram, a
percentile table, and a summary when the run ends.

This is the hardest of the three application tutorials, and the reason is one
sentence: a `Signal` may only be written from the render thread, and a load test is
a pool of threads producing results as fast as the network allows. Steps 4 and 5
are where that is resolved; the rest is layout.

> **You need:** JDK 25 or newer, a clone of the repository, and a real terminal.
> Run the app from a terminal window rather than an IDE output panel, because raw
> input needs a controlling TTY.

> **The finished app already ships in the clone**, at `examples/loadtest/`. Build yours
> beside it under a different name — `examples/myloadtest/`, with `build.examples.myloadtest`
> in its `package.mill` and the package renamed to match — or read the finished source
> as you go. Everything below is taken verbatim from it.

## 1. Create the module

Every example in this repository is its own Mill module. Clone the repository and
clear the finished copy out of the way so the files you write are the ones Mill
builds:

```bash
git clone git@github.com:oleksandr-balyshyn/glyphora.git
cd glyphora
rm -rf examples/loadtest
mkdir -p examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest
mkdir -p examples/loadtest/src/test/scala/io/worxbend/tui/examples/loadtest
```

`git checkout -- examples/loadtest` restores the finished version at any point, so
you can diff your file against it after every step.

```scala title="examples/loadtest/package.mill"
package build.examples.loadtest

import mill.*

// Everything an example has in common — the GraalVM pin, `--no-fallback`, and a Pilot-capable
// test submodule — lives in `TuiExampleModule` in build.mill.
object `package` extends build.TuiExampleModule {

  def moduleDeps = Seq(build.dsl)

  def mainClass = Some("io.worxbend.tui.examples.loadtest.Main")
}
```

Mill discovers the module from the file's path, so there is nothing to register
elsewhere. `TuiExampleModule` already carries the `test` submodule the tests at step 12
need, so the build never has to be edited in the middle of a tutorial about
concurrency. Outside this repository the same module is one `ScalaModule` with
`mvnDeps` on `tui-dsl` — see [Getting started](./getting-started).

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Main.scala"
package io.worxbend.tui.examples.loadtest

import io.worxbend.tui.dsl.*

final class LoadTestApp extends TuiApp:
  def view(using ReactiveScope, Theme): Element =
    panel("loadtest")(text("nothing to do yet · q to quit").dim).rounded
      .onKey(Key.char('q')) { quit() }

// `TuiApp` supplies `main`, so the launcher only needs an object that *is* the app.
object Main extends LoadTestApp()
```

```bash
./mill examples.loadtest.run
```

An empty bordered frame fills the terminal and `q` exits cleanly:

```
╭loadtest──────────────────────────────────────────╮
│ nothing to do yet · q to quit                    │
│                                                  │
╰──────────────────────────────────────────────────╯
```

Steps 2, 3 and 4 build the engine and change nothing on screen; their command is
`compile`, and step 5 is where the screen lights up.

## 2. Design the run before the UI

A load test is a stream of completed requests and a tally over them. Model both
before any widget exists, because the widgets then have nothing to decide.

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/RunStats.scala"
package io.worxbend.tui.examples.loadtest

/** One completed request, as the workers hand it back. */
enum Sample:
  case Ok(micros: Long)
  case Failed(reason: String)

final case class RunStats(sent: Int, ok: Int, failed: Int, latencies: Vector[Long]):

  def record(batch: Vector[Sample]): RunStats =
    if batch.isEmpty then this
    else
      val successes = batch.collect { case Sample.Ok(micros) => micros }
      val failures  = batch.collect { case Sample.Failed(reason) => reason }
      RunStats(
        sent = sent + batch.size,
        ok = ok + successes.size,
        failed = failed + failures.size,
        latencies = latencies ++ successes,
      )

object RunStats:
  val empty: RunStats = RunStats(sent = 0, ok = 0, failed = 0, latencies = Vector.empty)
```

Two decisions carry the rest of the app. Latency is whole microseconds rather than
a `FiniteDuration` because everything downstream wants a number: `Sparkline` takes
`Seq[Long]`, percentiles want a sortable key, and the histogram buckets by
arithmetic. And `record` folds a **whole batch** rather than one sample, because
per-sample `copy` would rebuild the tally thousands of times a second for no
visible gain.

One `RunStats` value rather than five separate signals is also deliberate: the tick
that drains a batch writes it once, so the view never sees `sent` updated while
`ok` is still behind, and the frame is invalidated once instead of five times.

```bash
./mill examples.loadtest.compile
```

## 3. A target you always have

A load generator needs something to hit. Make the default a fake with no socket
behind it, so the app runs on a plane and the test suite never needs a network.

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Target.scala"
package io.worxbend.tui.examples.loadtest

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

/** One unit of work, fired over and over from many threads at once. */
trait Target:
  def fire(): Either[String, FiniteDuration]
  def describe: String

final class FakeTarget(
    seed: Long = 0x10adL,
    failureRate: Double = 0.04,
    pace: FiniteDuration = 1.milli,
) extends Target:

  private val issued = AtomicLong(0L)

  def describe: String = f"fake://in-memory (${failureRate * 100}%.0f%% fail)"

  def fire(): Either[String, FiniteDuration] =
    val ticket = issued.getAndIncrement()
    Thread.sleep(pace.toMillis)
    if FakeTarget.unitInterval(seed ^ FakeTarget.FailureStream, ticket) < failureRate then
      Left(FakeTarget.Failures((ticket % FakeTarget.Failures.size).toInt))
    else Right(latencyOf(ticket))

  /** A cubed uniform: a long right tail, so p99 sits well above p50. */
  private def latencyOf(ticket: Long): FiniteDuration =
    val uniform = FakeTarget.unitInterval(seed ^ FakeTarget.LatencyStream, ticket)
    (FakeTarget.BaseMicros + (FakeTarget.SpreadMicros * uniform * uniform * uniform).toLong).micros
```

The determinism here needs care under concurrency. The *order* results come back in
is a race, so anything derived from a shared mutable generator would differ from run
to run and no test could assert on it. Every request instead takes a ticket from an
atomic counter and derives its outcome from `seed` and that ticket alone, so the
multiset of results for N requests is fixed however the workers interleave. `pace`
is the one thing actually slept: without some wall-clock cost the whole run finishes
inside a single render tick and the live charts have nothing to draw.

Add the companion and the real HTTP target to the same file:

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Target.scala"
object FakeTarget:

  private val BaseMicros   = 900L
  private val SpreadMicros = 42000.0

  // two independent streams off one seed, so changing the failure rate does not
  // reshuffle the latencies
  private val LatencyStream = 0x51ed270bL
  private val FailureStream = 0x2545f491L

  private val Failures: Vector[String] =
    Vector("connection reset by peer", "operation timed out", "HTTP 503")

  /** splitmix64's finalizer: a pure function from a ticket to a value in `[0, 1)`. */
  private def unitInterval(stream: Long, ticket: Long): Double =
    val seeded  = stream + ticket * 0x9e3779b97f4a7c15L
    val mixed   = (seeded ^ (seeded >>> 30)) * 0xbf58476d1ce4e5b9L
    val again   = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL
    val final64 = again ^ (again >>> 31)
    (final64 >>> 11).toDouble / (1L << 53).toDouble

final class HttpTarget(url: String, timeout: FiniteDuration = 5.seconds) extends Target:

  private val client  =
    HttpClient.newBuilder().connectTimeout(java.time.Duration.ofMillis(timeout.toMillis)).build()
  private val request =
    HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofMillis(timeout.toMillis)).GET().build()

  def describe: String = url

  def fire(): Either[String, FiniteDuration] =
    val started = System.nanoTime()
    try
      val response = client.send(request, HttpResponse.BodyHandlers.discarding())
      val elapsed  = (System.nanoTime() - started).nanos
      if response.statusCode() >= 400 then Left(s"HTTP ${response.statusCode()}") else Right(elapsed)
    catch case error: java.io.IOException => Left(Option(error.getMessage).getOrElse(error.toString))
```

The client and request are built once and shared: constructing a client per request
would also measure connection setup and TLS negotiation, which is not what a load
test is asking about. `send` declares exactly `IOException` and
`InterruptedException`, and the interrupt is deliberately left to propagate — it is
how step 4 tears a stuck worker down, and swallowing it here would defeat that.
`./mill examples.loadtest.compile` still passes and the screen is unchanged.

## 4. One Async.run owns the pool

Here is the contract, in plain language. glyphora renders from a single thread. That
thread owns every `Signal`: `Signal.set` calls `RenderThread.checkRenderThread()` and
throws if it is called from anywhere else. Key handlers, mouse handlers, `onTick`,
and `Async` completion callbacks all already run there, so ordinary code never
notices — see [State & signals](./state-and-signals#the-render-thread-rule).

A worker thread notices immediately. It cannot write a signal, so it must hand its
result to the render thread, and `Async` is what does the handing. `Async.run` and
`Async.runCatching` call `RenderThread.capture()` on the **calling** thread and then
submit the work, so the continuation returns to the runner that started it.

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/LoadRunner.scala"
package io.worxbend.tui.examples.loadtest

import io.worxbend.tui.runtime.Async

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{ConcurrentLinkedQueue, ExecutorService, Executors, ThreadFactory, TimeUnit}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** How many requests to fire, and how many at a time. */
final case class Plan(requests: Int, concurrency: Int)

enum RunOutcome:
  case Completed
  case Stopped
  case Crashed(reason: String)

final class LoadRunner(target: Target):

  private val completed   = ConcurrentLinkedQueue[Sample]()
  private val cancelled   = AtomicBoolean(false)
  private val liveWorkers = AtomicInteger(0)
  private val generation  = AtomicInteger(0)

  val threadPrefix: String = s"loadtest-worker-${LoadRunner.instances.incrementAndGet()}-"

  def workersAlive: Int = liveWorkers.get()

  /** Starts a run. '''Must be called from the render thread.''' */
  def start(plan: Plan)(onFinished: RunOutcome => Unit): Unit =
    val mine = generation.incrementAndGet()
    cancelled.set(false)
    completed.clear()
    Async.runCatching(driveRun(plan, mine)) {
      case Right(outcome) => onFinished(outcome)
      case Left(failure)  => onFinished(RunOutcome.Crashed(Option(failure.getMessage).getOrElse(failure.toString)))
    }

  /** Asks the workers to stop after their current request. */
  def stop(): Unit = cancelled.set(true)

  /** Takes everything finished since the last call. Called from the render thread. */
  def drain(): Vector[Sample] =
    val batch = Vector.newBuilder[Sample]
    var next  = completed.poll()
    while next != null do
      batch += next
      next = completed.poll()
    batch.result()
```

**The first place beginners get stuck** is calling `start` from a constructor or a
field initialiser. `Async.runCatching` captures the render loop of whatever thread
calls it, and in a constructor no runner is registered yet, so the capture falls back
to a detached loop. With two apps live in one JVM — a parallel test suite is exactly
that — the completion callback is drained by whichever render thread reaches it
first, and your signal is written from the wrong app's thread. Call `start` from a
key binding or `onTick`, where you are already on the render thread.

The second is reaching for `RenderThread.runOnRenderThread` inside the worker
instead. That call re-resolves the target loop at call time, from the worker, and
lands on the detached loop as soon as two runners are live. The documented “capture
before you go async” discipline is not fully available to application code either:
`RenderThread.capture()` is public, but `RenderLoop.enqueue` and `drain` are
`private[runtime]`, so `Async` is the only supported way to use a capture.

Now the private half of the file — the part that actually owns threads:

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/LoadRunner.scala"
  /** Runs on one `Async` worker thread: owns the pool, waits for it, reports how it ended. */
  private def driveRun(plan: Plan, mine: Int): RunOutcome =
    val threads   = math.max(1, math.min(LoadRunner.MaxConcurrency, plan.concurrency))
    val remaining = AtomicInteger(math.max(0, plan.requests))
    val pool      = Executors.newFixedThreadPool(threads, workerFactory)
    val settled   =
      try
        (1 to threads).foreach(_ => pool.execute(() => fireUntilDone(remaining, mine)))
        drainPool(pool)
      finally pool.shutdownNow()
    if !settled then RunOutcome.Crashed("workers did not stop within the settle timeout")
    else if cancelled.get() then RunOutcome.Stopped
    else RunOutcome.Completed

  /** One worker: pull a ticket, fire, repeat. */
  private def fireUntilDone(remaining: AtomicInteger, mine: Int): Unit =
    liveWorkers.incrementAndGet()
    try
      while generation.get() == mine && !cancelled.get() && remaining.getAndDecrement() > 0 do
        val sample = target.fire() match
          case Right(latency) => Sample.Ok(latency.toMicros)
          case Left(reason)   => Sample.Failed(reason)
        if generation.get() == mine then completed.add(sample)
    catch case _: InterruptedException => ()
    finally liveWorkers.decrementAndGet()

  private def drainPool(pool: ExecutorService): Boolean =
    pool.shutdown()
    if pool.awaitTermination(LoadRunner.SettleTimeout.toMillis, TimeUnit.MILLISECONDS) then true
    else
      pool.shutdownNow()
      pool.awaitTermination(LoadRunner.SettleTimeout.toMillis, TimeUnit.MILLISECONDS)

  /** Daemon threads: nothing this example starts may keep the JVM alive. */
  private def workerFactory: ThreadFactory =
    val counter = AtomicInteger(0)
    runnable =>
      val thread = Thread(runnable, s"$threadPrefix${counter.getAndIncrement()}")
      thread.setDaemon(true)
      thread

object LoadRunner:
  private val MaxConcurrency                = 256
  private val SettleTimeout: FiniteDuration = 10.seconds
  private val instances                     = AtomicInteger(0)
```

The whole concurrent phase sits inside a **single** `Async` task that owns a private
fixed pool. `Async`'s own pool is an unbounded, process-wide cached pool with no way
to bound it, so `-c 50` submitted as fifty `Async.run`s would be fifty threads on a
pool shared with everything else in the JVM. One task keeps the concurrency limit
meaningful and consumes one `Async` thread. `Async.run` also hands back no
cancellation handle — only `Async.after` and `Async.every` do — which is why `stop()`
is a flag the worker loop reads between requests rather than an interrupt.

Workers pull tickets from `remaining` rather than being handed a slice of the run up
front: with a slice, one slow worker holds the run open at the end while the others
idle. [Live data & background work](./live-data) argues each of these three choices
in isolation. `./mill examples.loadtest.compile` passes; still nothing on screen.

## 5. Stream batches back

Now connect the two. The workers never touch a signal — they push onto a lock-free
queue — and the app drains that queue once per render tick.

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Main.scala"
package io.worxbend.tui.examples.loadtest

import io.worxbend.tui.dsl.*

import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

enum Phase:
  case Idle
  case Running
  case Finished(outcome: RunOutcome)

final class LoadTestApp(
    target: Target = FakeTarget(),
    initialPlan: Plan = Plan(requests = 500, concurrency = 8),
) extends TuiApp:

  override def config: RunnerConfig = RunnerConfig(tickRate = Some(LoadTestApp.TickRate))

  private val runner = LoadRunner(target)

  val plan: Signal[Plan]              = Signal(initialPlan)
  val phase: Signal[Phase]            = Signal(Phase.Idle)
  val stats: Signal[RunStats]         = Signal(RunStats.empty)
  val elapsed: Signal[FiniteDuration] = Signal(0.millis)

  private var startedNanos: Long = 0L

  override def bindings: KeyBindings = KeyBindings(
    binding("s", "start the run")(start()),
    binding("x", "stop the run")(stop()),
    binding("r", "reset counters")(reset()),
    binding("q", "quit")(quit()),
  )

  /** Stops the workers on the way out, whatever ended the run. */
  override def onStop(): Unit = runner.stop()

  override def onTick(): Unit =
    if phase.peek == Phase.Running then
      absorb()
      elapsed.set((System.nanoTime() - startedNanos).nanos)

  private def absorb(): Unit =
    stats.update(_.record(runner.drain()))

  private def start(): Unit =
    if phase.peek != Phase.Running then
      clearCounters()
      startedNanos = System.nanoTime()
      phase.set(Phase.Running)
      // a key binding runs on the render thread, which is what makes the capture
      // inside Async.runCatching name this app's render loop
      runner.start(plan.peek) { outcome =>
        absorb()
        phase.set(Phase.Finished(outcome))
      }

  private def stop(): Unit =
    if phase.peek == Phase.Running then runner.stop()

  private def reset(): Unit =
    runner.stop()
    clearCounters()
    phase.set(Phase.Idle)

  private def clearCounters(): Unit =
    stats.set(RunStats.empty)
    elapsed.set(0.millis)
```

Add the view and the two companions to the end of the same file:

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Main.scala"
  def view(using ReactiveScope, Theme): Element =
    val current = stats.get
    panel(phaseLabel)(
      text(f"sent ${current.sent}%6d   ok ${current.ok}%6d   failed ${current.failed}%6d"),
      spacer,
      text("s start · x stop · r reset · q quit").dim,
    ).rounded

  private def phaseLabel(using ReactiveScope): String =
    phase.get match
      case Phase.Idle                                 => "Idle - press s to start"
      case Phase.Running                              => "Running"
      case Phase.Finished(RunOutcome.Completed)       => "Completed"
      case Phase.Finished(RunOutcome.Stopped)         => "Stopped"
      case Phase.Finished(RunOutcome.Crashed(reason)) => s"Crashed: $reason"

object Main extends LoadTestApp()

object LoadTestApp:
  private[loadtest] val TickRate: FiniteDuration = 100.millis
```

```bash
./mill examples.loadtest.run
```

Press `s`. The counters climb to 500 and the title changes to `Completed`; `x` stops
a run short and `r` clears it:

```
╭Running───────────────────────────────────────────╮
│ sent    312   ok    301   failed     11          │
│                                                  │
│ s start · x stop · r reset · q quit              │
╰──────────────────────────────────────────────────╯
```

**The third place beginners get stuck** is the alternative to `drain()`: one
`Async.run` per request, so each result marshals itself back. It is correct, and it
is ruinous — a few thousand requests a second queue a few thousand continuations a
second onto the render loop and the frame never finishes. Batching at the frame rate
costs nothing, because the screen cannot show more than that anyway. Ten frames a
second is enough for a chart and cheap enough that an idle app costs nothing: a tick
that changes no signal schedules no repaint.

## 6. Counters and the progress gauge

Give the app its chrome and its two headline panels. Replace `view` and add three
methods:

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Main.scala"
  def view(using ReactiveScope, Theme): Element =
    scaffold(
      topBar = Some(topBar("loadtest", right = headline)),
      statusBar = Some(
        statusBar(
          Seq("s" -> "start", "x" -> "stop", "r" -> "reset", "+/-" -> "conc", "[/]" -> "reqs", "q" -> "quit")
        )
      ),
    )(body)

  private def body(using ReactiveScope, Theme): Element =
    column(progressPanel.length(3), countersPanel.fill)

  private def headline(using ReactiveScope): String =
    val current = plan.get
    s"${target.describe}  n=${current.requests} c=${current.concurrency}"

  private def progressPanel(using ReactiveScope, Theme): Element =
    val current = stats.get
    val total   = plan.get.requests
    panel(phaseLabel)(progressBar(current.sent, total).labelled(s"${current.sent} / $total"))

  private def countersPanel(using ReactiveScope, Theme): Element =
    val current = stats.get
    val seconds = elapsed.get.toMillis / 1000.0
    val rate    = if seconds <= 0.0 then 0.0 else current.sent / seconds
    panel("Counters")(
      text(f"sent     ${current.sent}%7d"),
      text(f"ok       ${current.ok}%7d").fg(Color.Green),
      if current.failed > 0 then text(f"failed   ${current.failed}%7d").fg(Color.Red)
      else text(f"failed   ${current.failed}%7d").dim,
      text(f"req/s    $rate%7.1f").fg(Color.Cyan),
      text(f"elapsed  $seconds%6.1fs"),
      text(f"workers  ${runner.workersAlive}%7d").dim,
    )
```

`statusBar(bindings)` would render every declared hint and run off an 80-column
terminal, so the keys are grouped by hand; the full descriptions still reach the
`Ctrl+P` palette from `bindings`. See [The app shell](./app-shell) for what else
`scaffold` composes. The published example inlines `body` into `view` — splitting it
here keeps every later step to one changed method.

`workers` is not a signal, so it only refreshes because a tick redrew the frame
anyway, which is exactly what it is here to show: the pool filling on `s` and
emptying on `x`.

```bash
./mill examples.loadtest.run
```

```
 loadtest                    fake://in-memory (4% fail)  n=500 c=8
╭Running───────────────────────────────────────────────────────────╮
│ ████████████████████████░░░░░░░░░░░░░░░░░  312 / 500  62%        │
╰──────────────────────────────────────────────────────────────────╯
╭Counters──────────────────────────────────────────────────────────╮
│ sent         312                                                 │
│ ok           301                                                 │
│ failed        11                                                 │
│ req/s      821.1                                                 │
│ elapsed      0.4s                                                │
│ workers        8                                                 │
╰──────────────────────────────────────────────────────────────────╯
 s start · x stop · r reset · +/- conc · [/] reqs · q quit
```

## 7. Throughput, one bar per tick

Each tick drains a batch, and the size of that batch *is* the throughput for the
tick. Keep a rolling window of them.

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Main.scala"
  private val throughput: Signal[Vector[Long]] = Signal(Vector.empty)
  private val peakThroughput: Signal[Long]     = Signal(1L)

  private def absorb(): Unit =
    val batch = runner.drain()
    stats.update(_.record(batch))
    throughput.update(window => (window :+ batch.size.toLong).takeRight(LoadTestApp.ThroughputWindow))
    peakThroughput.update(peak => math.max(peak, batch.size.toLong))

  private def throughputPanel(using ReactiveScope, Theme): Element =
    val window = throughput.get
    val peak   = peakThroughput.get
    panel(s"Throughput (peak $peak per ${LoadTestApp.TickRate.toMillis}ms)")(
      if window.isEmpty then text("no samples yet").dim.fill
      else sparkline(window).max(peak).fg(Color.Cyan).fill
    )

  private def body(using ReactiveScope, Theme): Element =
    column(
      progressPanel.length(3),
      row(countersPanel.percent(38), throughputPanel.fill).fill,
    )
```

Add `private val ThroughputWindow = 60` to `object LoadTestApp`, and extend
`clearCounters` with `throughput.set(Vector.empty)` and `peakThroughput.set(1L)` so a
reset wipes the trace too.

`SparklineElement` is used directly rather than the `sparkline(data)` factory,
because the factory does not expose `max`. Without a pinned ceiling the trace
autoscales to its own maximum on every frame: the line rescales under the reader
mid-run, and a flat trace is drawn identically to a spiky one. Pinning `max` to the
peak seen this run fixes the scale for the whole run.
[Charts, gauges & status](./charts-and-status) covers the same trap for gauges and
bar charts. Run it: the right-hand panel now fills with a live trace as the pool
saturates.

## 8. The latency histogram

Bucket the collected latencies and draw one horizontal bar per bucket. First the
maths, appended to `RunStats.scala`:

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/RunStats.scala"
/** One bar of the histogram: a half-open microsecond range and how many landed in it. */
final case class LatencyBucket(lowMicros: Long, highMicros: Long, count: Int)

object Histogram:

  def of(latencies: Vector[Long], buckets: Int): Vector[LatencyBucket] =
    if latencies.isEmpty || buckets <= 0 then Vector.empty
    else
      val low    = latencies.min
      val span   = math.max(1L, latencies.max - low)
      val counts = Array.fill(buckets)(0)
      latencies.foreach { value =>
        val index = math.min(buckets - 1, ((value - low) * buckets / span).toInt)
        counts(index) += 1
      }
      Vector.tabulate(buckets) { index =>
        LatencyBucket(low + span * index / buckets, low + span * (index + 1) / buckets, counts(index))
      }
```

The edges move as the run goes on, which is the honest thing to draw: a fixed scale
chosen from the first hundred requests hides the tail that shows up in the last
thousand. Now the panel:

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Main.scala"
  private val histogram: Computed[Vector[LatencyBucket]] =
    Computed(Histogram.of(stats.get.latencies, LoadTestApp.HistogramBuckets))

  private def histogramPanel(using ReactiveScope, Theme): Element =
    val buckets = histogram.get
    val tallest = buckets.map(_.count).maxOption.getOrElse(0)
    panel("Latency histogram (ms)")(
      if buckets.isEmpty then text("no samples yet").dim.fill
      else column(buckets.map(bucketRow(_, tallest))*).fill
    )

  private def bucketRow(bucket: LatencyBucket, tallest: Int)(using Theme): Element =
    row(
      text(f"${LoadTestApp.ms(bucket.lowMicros)}%6.2f-${LoadTestApp.ms(bucket.highMicros)}%6.2f").length(14).dim,
      progressBar(bucket.count, tallest).bare.fill,
      text(f"${bucket.count}%5d").length(6),
    ).length(1)

  private def body(using ReactiveScope, Theme): Element =
    column(
      progressPanel.length(3),
      row(countersPanel.percent(38), throughputPanel.fill).length(8),
      histogramPanel.fill,
    )
```

Add `private val HistogramBuckets = 8` and
`private[loadtest] def ms(micros: Long): Double = micros / 1000.0` to
`object LoadTestApp`.

`histogram` is a `Computed` declared **outside** `view`. A `Computed` built inside
`view` re-subscribes on every frame and is never released; out here it recomputes
lazily, once per change to `stats`. The bars are `progressBar(...).bare` rows rather
than `barChart`, because `BarChart` silently drops any bar that does not fully fit
the width — a narrow terminal would lose buckets without saying so — and a bucket
range needs twelve characters of label that a vertical bar cannot carry.

## 9. The percentile table

The toolkit ships no statistics helpers, so the percentiles are app code. Append to
`RunStats.scala`:

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/RunStats.scala"
final case class LatencySummary(count: Int, min: Long, max: Long, mean: Long, p50: Long, p90: Long, p99: Long)

object LatencySummary:

  val empty: LatencySummary = LatencySummary(0, 0L, 0L, 0L, 0L, 0L, 0L)

  def of(latencies: Vector[Long]): LatencySummary =
    if latencies.isEmpty then empty
    else
      val sorted = latencies.sorted
      LatencySummary(
        count = sorted.size,
        min = sorted.head,
        max = sorted.last,
        mean = sorted.sum / sorted.size,
        p50 = percentile(sorted, 0.50),
        p90 = percentile(sorted, 0.90),
        p99 = percentile(sorted, 0.99),
      )

  /** Nearest-rank percentile over an already-sorted vector. */
  private def percentile(sorted: Vector[Long], quantile: Double): Long =
    sorted(math.min(sorted.size - 1, (sorted.size * quantile).toInt))
```

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Main.scala"
  private val latency: Computed[LatencySummary] =
    Computed(LatencySummary.of(stats.get.latencies))

  private def latencyPanel(using ReactiveScope, Theme): Element =
    val summary                                           = latency.get
    def statRow(label: String, micros: Long): Seq[String] = Seq(label, f"${LoadTestApp.ms(micros)}%9.2f")
    panel("Latency (ms)")(
      TableElement(
        rows = Seq(
          statRow("p50", summary.p50),
          statRow("p90", summary.p90),
          statRow("p99", summary.p99),
          statRow("max", summary.max),
          statRow("mean", summary.mean),
          statRow("min", summary.min),
        ),
        widths = Seq(Constraint.Length(6), Constraint.Fill(1)),
        header = Some(Seq("stat", "ms")),
      ).fill
    )

  private def body(using ReactiveScope, Theme): Element =
    column(
      progressPanel.length(3),
      row(countersPanel.percent(38), throughputPanel.fill).length(8),
      row(histogramPanel.fill, latencyPanel.length(28)).fill,
    )
```

Neither `Table` nor `DataTable` can right-align a column, so the numbers are padded
to a fixed width with `%9.2f` and the column is given a fixed `Length`. This screen
is static, so `Table` is the right choice over `DataTable` —
[Tables & selection](./tables-and-selection) draws the line between them. Run it: the
bottom half now carries the histogram beside a six-row percentile table, and p99 sits
well above p50 because of the cubed uniform in step 3.

## 10. Errors, grouped by reason

Failures need a tally, not a log. Replace `RunStats` and its companion:

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/RunStats.scala"
final case class RunStats(
    sent: Int,
    ok: Int,
    failed: Int,
    latencies: Vector[Long],
    errors: Map[String, Int],
):

  def record(batch: Vector[Sample]): RunStats =
    if batch.isEmpty then this
    else
      val successes = batch.collect { case Sample.Ok(micros) => micros }
      val failures  = batch.collect { case Sample.Failed(reason) => reason }
      RunStats(
        sent = sent + batch.size,
        ok = ok + successes.size,
        failed = failed + failures.size,
        latencies = latencies ++ successes,
        errors = failures.foldLeft(errors)((tally, reason) => tally.updated(reason, tally.getOrElse(reason, 0) + 1)),
      )

object RunStats:
  val empty: RunStats = RunStats(sent = 0, ok = 0, failed = 0, latencies = Vector.empty, errors = Map.empty)
```

`Sample.Failed` carries a `String` rather than a `Throwable` because the only thing
the UI does with a failure is tally it by text; a stack trace would be carried
across a thread boundary, held for the length of the run, and never rendered. The
tally is folded once per batch for the same reason `sent` is.

To watch it fill, point the app at a lossier target for one run: construct
`LoadTestApp(FakeTarget(failureRate = 0.5))` in place of `LoadTestApp()` in `Main`,
press `s`, and the `failed` counter turns red at roughly half the requests. Put the
default back before step 11.

## 11. Finish, summarise, and quit without a leak

The run's continuation arrives on the render thread, and it needs a guard.

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Main.scala"
  private val summaryOpen: Signal[Boolean] = Signal(false)
  private var runId: Int                   = 0

  private def start(): Unit =
    if phase.peek != Phase.Running then
      dismissSummary()
      clearCounters()
      startedNanos = System.nanoTime()
      phase.set(Phase.Running)
      runId += 1
      val thisRun = runId
      runner.start(plan.peek)(outcome => finish(thisRun, outcome))

  private def finish(forRun: Int, outcome: RunOutcome): Unit =
    if forRun == runId && phase.peek == Phase.Running then
      absorb()
      elapsed.set((System.nanoTime() - startedNanos).nanos)
      phase.set(Phase.Finished(outcome))
      showSummary()

  /** Stops the workers on the way out, whatever ended the run — `q`, Ctrl+C, a
    * backend failure. `onStop` is the one hook every exit path passes through.
    */
  override def onStop(): Unit = runner.stop()

  private def showSummary(): Unit =
    summaryOpen.set(true)
    pushScreen(Screen { summaryView })

  private def dismissSummary(): Unit =
    if summaryOpen.peek then
      popScreen()
      summaryOpen.set(false)
```

A run that was reset or restarted while in flight still delivers its callback — the
work was submitted and nothing can un-submit it. Comparing `runId` drops the stale
one instead of letting it declare the *current* run finished. `onStop` from step 5
already stops the workers on the way out — every exit path passes through it, `q` and
Ctrl+C alike: `Async`'s threads are daemons, so the JVM would exit regardless, but a
headless test outlives the runner and “no thread survives quit” is a property worth
actually having.

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Main.scala"
  private def summaryView(using ReactiveScope, Theme): Element =
    val current      = stats.get
    val summary      = latency.get
    val seconds      = math.max(0.001, elapsed.get.toMillis / 1000.0)
    val success      = if current.sent == 0 then 0.0 else current.ok * 100.0 / current.sent
    val successColor =
      if success >= 100.0 then Color.Green else if success >= 99.0 then Color.Yellow else Color.Red
    val worstErrors  = current.errors.toSeq.sortBy((_, count) => -count).take(3)
    val body         =
      Seq(
        text(phaseLabel).bold,
        text(f"requests    ${current.sent}%d in $seconds%.2f s"),
        text(f"success     $success%.2f %%").fg(successColor),
        text(f"throughput  ${current.sent / seconds}%.1f req/s"),
        text(f"fastest     ${LoadTestApp.ms(summary.min)}%.2f ms").fg(Color.Green),
        text(f"slowest     ${LoadTestApp.ms(summary.max)}%.2f ms").fg(Color.Yellow),
        text(
          f"p50/p90/p99 ${LoadTestApp.ms(summary.p50)}%.2f / ${LoadTestApp.ms(summary.p90)}%.2f" +
            f" / ${LoadTestApp.ms(summary.p99)}%.2f ms"
        ).fg(Color.Cyan),
        spacer(1),
      ) ++ (
        if worstErrors.isEmpty then Seq(text("no errors").dim)
        else worstErrors.map((reason, count) => text(s"[$count] $reason").fg(Color.Red))
      ) ++ Seq(spacer, text("Enter dismiss  ·  r reset  ·  q quit").dim)
    centered(56, 17)(FilledElement(panel("Run summary")(body*).rounded, summon[Theme].primary))
```

The success rate is the only number coloured by threshold, which is `oha`'s rule and
a good one: colour every number and none of them means anything. The `FilledElement`
is not decoration — `Block` deliberately never paints its interior and a modal
`Screen` layers over the live view, so without a fill the histogram behind the dialog
shows through the gaps between the words.

Finally, the remaining bindings and a command line:

```scala title="examples/loadtest/src/main/scala/io/worxbend/tui/examples/loadtest/Main.scala"
  private def adjustConcurrency(delta: Int): Unit =
    if phase.peek != Phase.Running then
      plan.update(current => current.copy(concurrency = math.max(1, math.min(64, current.concurrency + delta))))

  private def scaleRequests(double: Boolean): Unit =
    if phase.peek != Phase.Running then
      plan.update { current =>
        val scaled = if double then current.requests * 2 else current.requests / 2
        current.copy(requests = math.max(10, math.min(1000000, scaled)))
      }

object LoadTestApp:

  // …TickRate, ThroughputWindow, HistogramBuckets and ms as before

  def fromArgs(args: Array[String]): LoadTestApp =
    val flags  = args.sliding(2, 2).collect { case Array(flag, value) => flag -> value }.toMap
    val plan   = Plan(
      requests = flags.get("--requests").flatMap(_.toIntOption).map(math.max(1, _)).getOrElse(500),
      concurrency = flags.get("--concurrency").flatMap(_.toIntOption).map(math.max(1, _)).getOrElse(8),
    )
    val target = flags.get("--url").map(url => HttpTarget(url)).getOrElse(FakeTarget())
    LoadTestApp(target, plan)
```

Declare the four new keys in `bindings`. `binding` is curried — the action is a second
parameter list — so each one needs its body:

```scala
override def bindings: KeyBindings = KeyBindings(
  binding("s", "start the run")(start()),
  binding("x", "stop the run")(stop()),
  binding("r", "reset counters")(reset()),
  binding("+", "raise concurrency")(adjustConcurrency(1)),
  binding("-", "lower concurrency")(adjustConcurrency(-1)),
  binding("]", "double the request count")(scaleRequests(double = true)),
  binding("[", "halve the request count")(scaleRequests(double = false)),
  binding("enter", "dismiss the summary")(dismissSummary()),
  binding("q", "quit")(quit()),
)
```

This is the one app in the tutorials that cannot simply be `object Main extends
LoadTestApp()`: it has to read the command line before the app exists. Build the app
from the arguments and hand them on to the `main` `TuiApp` already gave it:

```scala title="Main.scala"
object Main:
  def main(args: Array[String]): Unit = LoadTestApp.fromArgs(args).main(args)
```

Unknown flags are ignored on purpose: an example that dies on a typo teaches nothing. The plan is editable only
while idle, so the headline cannot change under a run in flight.

```bash
./mill examples.loadtest.run
./mill examples.loadtest.run --requests 5000 --concurrency 32
```

Press `s`, wait, and the summary lands over the live screen; `Enter` dismisses it:

```
        ╭Run summary───────────────────────────────╮
        │ Completed                                │
        │ requests    500 in 0.71 s                │
        │ success     96.20 %                      │
        │ throughput  704.2 req/s                  │
        │ fastest     0.90 ms                      │
        │ slowest     34.71 ms                     │
        │ p50/p90/p99 1.04 / 4.31 / 22.60 ms       │
        │                                          │
        │ [8] HTTP 503                             │
        │ [7] operation timed out                  │
        │ [4] connection reset by peer             │
        │ Enter dismiss  ·  r reset  ·  q quit     │
        ╰──────────────────────────────────────────╯
```

## 12. Test it

The whole app is testable with no TTY, no network and no sleeping, because
`FakeTarget` is deterministic and `HeadlessBackend` renders the same buffers a
terminal would.

```scala title="examples/loadtest/src/test/scala/io/worxbend/tui/examples/loadtest/LoadTestAppSpec.scala"
package io.worxbend.tui.examples.loadtest

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.CollectionConverters.*

final class LoadTestAppSpec extends AnyFunSuite:

  private def withApp[A](target: Target, plan: Plan)(body: (LoadTestApp, Pilot) => A): A =
    val backend = HeadlessBackend(Size(88, 30))
    val app     = LoadTestApp(target, plan)
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      body(app, pilot)
    }

  /** Polls rather than sleeping a fixed time. */
  private def waitUntil(timeout: FiniteDuration = 20.seconds)(condition: => Boolean): Boolean =
    val deadline = System.nanoTime() + timeout.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(20)
    condition

  private def liveThreadsNamed(prefix: String): Seq[String] =
    Thread.getAllStackTraces.keySet.asScala.toSeq
      .filter(thread => thread.isAlive && thread.getName.startsWith(prefix))
      .map(_.getName)
```

Passing `app.runWith(backend)` straight through is not noise: `runWith` returns an
`Either[RunnerError, Unit]`, and `Pilot.using` takes that result so a run that failed
— an unrestorable terminal, a handler that threw — fails the test rather than reading
as a clean exit. `waitForIdle` proves the posted key events were consumed; it says nothing
about a background run finishing, whose results only reach the UI on a later render
tick — under parallel test load that tick can be starved for a while, which is why
every assertion about a run waits on a condition with a generous deadline rather
than sleeping.

```scala title="examples/loadtest/src/test/scala/io/worxbend/tui/examples/loadtest/LoadTestAppSpec.scala"
  test("a completed run accounts for every request and raises the summary screen"):
    withApp(FakeTarget(failureRate = 0.0), Plan(requests = 60, concurrency = 6)) { (app, pilot) =>
      pilot.press("s")

      assert(waitUntil()(app.phase.peek == Phase.Finished(RunOutcome.Completed)))
      val finished = app.stats.peek
      assert(finished.sent == 60)
      assert(finished.ok == 60)
      assert(finished.latencies.size == 60)
      assert(waitUntil()(pilot.screenText.contains("Run summary")))
      assert(pilot.screenText.contains("no errors"))

      pilot.press("q")
      assert(pilot.awaitTermination(5.seconds))
    }

  test("quitting mid-run leaves no worker thread behind"):
    withApp(FakeTarget(failureRate = 0.0, pace = 2.millis), Plan(requests = 5000, concurrency = 8)) { (app, pilot) =>
      pilot.press("s")
      assert(waitUntil()(app.stats.peek.sent > 0))
      assert(liveThreadsNamed(app.workerThreadPrefix).nonEmpty, "the pool should be busy before we quit")

      pilot.press("q")
      assert(pilot.awaitTermination(5.seconds))

      assert(waitUntil()(app.workersAlive == 0))
      assert(waitUntil(5.seconds)(liveThreadsNamed(app.workerThreadPrefix).isEmpty))
    }
```

Both tests need `workersAlive` and `workerThreadPrefix` on the app, forwarding to the
runner: add `def workersAlive: Int = runner.workersAlive` and
`def workerThreadPrefix: String = runner.threadPrefix`. The second test is the one
worth having: the pool is not the runner's to garbage-collect, so `q` has to stop it,
and the thread-name prefix is unique per `LoadRunner` so the assertion cannot be
satisfied by another suite's pool dying.

```bash
./mill examples.loadtest.test
```

The finished suite adds four more cases — failures tallied by reason, stopping
mid-flight, reset, and the plan frozen while running. See [Testing](./testing) for
`Pilot`, `BufferAssertions`, and style assertions.

## Ship it as a binary

`package.mill` already declares `NativeImageModule` and `--no-fallback`, so there is
nothing further to configure:

```bash
./mill examples.loadtest.nativeImage
./out/examples/loadtest/nativeImage.dest/native-executable --requests 2000 --concurrency 16
```

The binary starts in milliseconds and needs no JVM on the machine that runs it.
Nothing in this app reflects, so no `reflect-config.json` is generated — see
[Native binaries](./native-image). Run the binary with no TTY and it prints
`glyphora: terminal not supported: dumb terminal (no TTY attached)` and exits 1, which is
what CI asserts for every example.

## What you learned

| Step | Goes deeper in |
|---|---|
| 4, 5 — capture, the private pool, batched handoff | [Live data & background work](./live-data) |
| 7, 8 — pinned sparkline ceiling, horizontal histogram | [Charts, gauges & status](./charts-and-status) |
| 9 — `Table` for a static grid, right-aligned numbers | [Tables & selection](./tables-and-selection) |
| 6, 11 — scaffold, bindings, palette, modal screens | [The app shell](./app-shell) |
| 12 — `Pilot`, headless frames, style assertions | [Testing](./testing) |

The three traps, restated once: start background work from the render thread and
never from a constructor; do not marshal one continuation per result; and stop the
pool before you quit, because the runtime will not do it for you.

## Where to go next

- [Build a process monitor](./build-a-process-monitor) — the same live-data shape
  over a sortable table instead of charts.
- [Build a sensor dashboard](./build-a-sensor-dashboard) — polled readings,
  threshold bands, and an offline mode.
- [Live data & background work](./live-data) — poller lifecycle, cancellation, and
  stale responses, argued in isolation.
- [Async work & timers](./async-and-timers) — the smaller `Async` patterns this app
  did not need.
- [Read the loadtest source](https://github.com/oleksandr-balyshyn/glyphora/tree/main/examples/loadtest)
  — the finished module, with the reasoning kept beside the line that implements it.
