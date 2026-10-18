---
title: Live data & background work
description: Poll a source on a timer in glyphora, run concurrent work off the render thread, cancel cleanly, drop stale responses, and keep a rolling history.
---

# Keep a screen fed with changing data

Every recipe here belongs to an app with a data source it does not control — a
device on the network, a command that costs tens of milliseconds, a pool of threads
answering as fast as a server allows. Each solves one problem: the seam, the timer,
the cancellation, the stale reply. They are lifted from
[Build a sensor dashboard](./build-a-sensor-dashboard) and
[Build a load generator](./build-a-load-generator), which assemble them.

> **The one rule underneath all of this:** a `Signal` may only be written from the
> render thread, and `Signal.set` throws off it while a runner is registered.
> `Async` moves the work off that thread and brings the result back to it. See
> [State & signals](./state-and-signals#the-render-thread-rule) for the guard itself.

Unless a snippet shows additional imports, assume `import io.worxbend.tui.dsl.*`,
which re-exports `Async`, `Cancelable`, `Signal`, and `Computed`.

## Put the source behind an interface

The app should depend on one method, not on an `HttpClient`. Everything you cannot
control goes behind it, and a deterministic implementation ships as the default so
`run` works on a laptop with no device and the tests are exactly reproducible:

```scala
trait SensorClient:
  def read(): Either[String, Reading]

/** The default, so the example runs with no device and the tests are reproducible. */
final class FakeSensor(script: Vector[Either[String, Reading]]) extends SensorClient:
  // read() runs on an Async worker thread, so the cursor cannot be a plain var
  private val cursor = AtomicInteger(0)

  def read(): Either[String, Reading] =
    script(cursor.getAndIncrement() % script.size)
```

`read()` returns `Either` rather than throwing, so a dead sensor is an ordinary
value the view can render instead of an exception caught somewhere unhelpful, and
the cursor is atomic because the method runs on a worker thread. Where the live
source may or may not exist, probe it with a real sample rather than a
file-existence check: `procmon` takes one sample from `ps` at startup and falls
back to synthetic rows, because `ps` can be present and still fail inside a
stripped container.

## Model loading as one signal

One enum, one signal, and a branch in `view` per case. The general shape is in
[Async work & timers](./async-and-timers#model-loading-explicitly); what matters
here is what the enum does **not** carry:

```scala
enum Status:
  case Loading
  case Ready
  case Failed(message: String)

val status: Signal[Status]           = Signal(Status.Loading)
val history: Signal[Vector[Reading]] = Signal(Vector.empty)
```

`Status` holds no reading. The readings live in `history`, so a failed poll leaves
the last good values on screen and adds a line explaining itself, rather than
blanking the pane the user was reading. If the app can also be told to stop
polling, add `Offline` as a fourth case rather than a `Boolean` beside the enum —
a flag and an enum can disagree, and then the view has a state with no branch.

## Fetch once, then every interval

Two calls, not one. The initial load runs immediately; the poller re-arms after it:

```scala
private def refresh(): Unit =
  if !inFlight then
    inFlight = true
    if history.peek.isEmpty then status.set(Status.Loading)
    Async.runCatching(client.read()) { outcome =>
      inFlight = false
      val result = outcome.fold(error => Left(describeThrowable(error)), identity)
      result match
        case Right(reading) => accept(reading)
        case Left(problem)  => status.set(Status.Failed(problem))
    }

private def startPolling(): Unit =
  poller = Some(Async.every(interval.peek)(refresh()))
```

`Async.every` fires first **after** a full interval, so a five-second poller with no
separate initial call leaves the screen on `Loading` for five seconds before it
shows anything. `inFlight` is what stops a source slower than the interval from
queueing reads behind each other: `Async.runCatching` hands back no cancellation
handle, so the only defence is not starting the next read.

## Start the poller in onStart

Not in the constructor. `onStart` runs on the render thread, once, after the
terminal is ready and before the first frame — the earliest moment the app is
certainly on its own render loop:

```scala
override def onStart(): Unit =
  refresh()
  startPolling()

override def onTick(): Unit =
  ageSeconds.set(lastUpdatedNanos.map(at => (System.nanoTime() - at) / 1_000_000_000L))
```

`Async.every` calls `RenderThread.capture()` on the *calling* thread and delivers
its body to whatever loop that returns. A constructor runs before any runner is
registered, so the capture falls back to the detached loop — and with two runners
live in one JVM, which a parallel test suite is exactly, another app's render
thread drains your readings. Unlike `onTick`, `onStart` needs no tick rate to fire.

## Cancel it on the way out

Nothing cancels a repeating task for you. `onStop` runs on every exit path —
`quit()`, an unconsumed `Ctrl+C`, a backend failure, an event handler that threw —
so that is where the handle is released. Keep the handle in a plain `var`; nothing
renders from it:

```scala
override def onStop(): Unit = stopPolling()

private def stopPolling(): Unit =
  poller.foreach(_.cancel())
  poller = None
```

The timer thread is a daemon, so a leaked poller still lets the JVM exit — which is
why this survives casual testing. Under a headless test the runner ends while the
poller keeps firing into a loop nobody drains. `Async.every` has no operation to
change an interval either, so a new cadence means cancel and re-arm.

## Ask for a frame the reactive layer did not see

A continuation that writes a `Signal` schedules its own redraw. A continuation that
mutates **caller-owned widget state** does not: `ListState`, `TextInputState`,
`DataTableState` and `LogState` are plain mutable objects with nothing subscribed to
them, so the screen keeps the previous frame until the next keystroke. Say so:

```scala
Async.run(loadRows()) { rows =>
  tableState.rows = rows
  requestRedraw()
}
```

## Give every timed action a key

`Pilot` can post keys and wait for the queue to drain; it cannot advance a wall-clock
timer deterministically. Give every action a timer performs a key binding that
performs the same thing:

```scala
override def bindings: KeyBindings = KeyBindings(
  binding("r", "refresh")(refresh()),
  binding("+", "slower")(rescale(_ * 2L)),
  binding("-", "faster")(rescale(_ / 2L)),
  binding("q", "quit")(quit()),
)
```

The spec then constructs the app with a ten-second interval so no poll fires behind
its assertions, and presses `r` to produce the second reading exactly where it wants
it. Keep one test that lets the timer run — assert the history grows and
`backend.drawCount` rises with no key pressed — because that is the only test that
proves the poller was ever started. See [Testing](./testing) for `Pilot` itself.

## Drop a stale response

Work already submitted cannot be un-submitted. A run that is reset or restarted
while in flight still finishes, and its callback would declare the *current* run
finished with numbers it never produced. `TaskScope.latest(key)` is the stamp-and-compare
done for you: starting a task under a key that already has one in flight cancels the
earlier task, and its result is discarded even if its work was already running or its
callback already queued — only the newest start under the key is ever delivered.

```scala
private val tasks = Async.scope() // in onStart; closed in onStop

private def start(): Unit =
  if phase.peek != Phase.Running then
    clearCounters()
    phase.set(Phase.Running)
    tasks.latest("run")(runner.drive(plan.peek)) {
      case Right(outcome) => finish(outcome)
      case Left(failure)  => finish(RunOutcome.Crashed(failure.toString))
    }

private def finish(outcome: RunOutcome): Unit =
  absorb()
  phase.set(Phase.Finished(outcome))
```

The same shape fits a search box that reloads on every keystroke — `tasks.latest("search")(api.search(query))(results.set)`
— and anything else where only the most recent answer is the right one. A completed,
failed or cancelled task releases its key, so the next start under it runs normally;
different keys never cancel each other. Cancellation is the scope's usual best effort:
the superseded work runs to completion on its worker and reaches nobody, so give long
work a cooperative stop of its own when finishing it is itself expensive.

The counter is still the right tool on the *producer* side, where no scope sees the
work: `loadtest`'s workers compare the generation they were started with before
enqueuing each result, so a worker from a reset run cannot drop a stale sample into
the next run's counters.

## Own the executor inside one Async.run

When the background work is itself concurrent, submit **one** task that owns a
private pool, from a key binding or `onTick`:

```scala
def start(plan: Plan)(onFinished: RunOutcome => Unit): Unit =
  val mine = generation.incrementAndGet()
  cancelled.set(false)
  Async.runCatching(driveRun(plan, mine)) {
    case Right(outcome) => onFinished(outcome)
    case Left(failure)  => onFinished(RunOutcome.Crashed(failure.toString))
  }

// one Async worker: owns the pool, waits for it, reports how the run ended
private def driveRun(plan: Plan, mine: Int): RunOutcome =
  val threads   = math.max(1, math.min(MaxConcurrency, plan.concurrency))
  val remaining = AtomicInteger(math.max(0, plan.requests))
  val pool      = Executors.newFixedThreadPool(threads, workerFactory)
  val settled   =
    try
      (1 to threads).foreach(_ => pool.execute(() => fireUntilDone(remaining, mine)))
      drainPool(pool) // shutdown, await, interrupt, await again
    finally pool.shutdownNow()
  if !settled then RunOutcome.Crashed("workers did not stop in time")
  else if cancelled.get() then RunOutcome.Stopped
  else RunOutcome.Completed
```

`Async`'s fixed worker pool and bounded waiting queue are shared process-wide with
other background work. Submitting fifty separate tasks does not establish a private
concurrency limit and may exhaust shared admission. One task owning a fixed pool costs
one `Async` thread and keeps this run's limit explicit. `Async.run` returns `Unit`, so
there is no handle to cancel: cancellation is an `AtomicBoolean` the worker loop reads
between requests, plus the timeout the request already carries.

For integrations that need manual dispatch, capture `RenderThread.capture()` on the
owner before leaving it, then use that handle's public `execute` method. Off-thread
admission can throw `RejectedExecutionException`; a retired owner returns false.
`RenderThread.runOnRenderThread` called *from* a worker instead re-resolves the target
at call time and can land on the detached loop when multiple runners are live.
Calling `Async.run` from the owner performs capture and reserved result delivery for you.

## Stream partial progress back

Workers must not touch a `Signal` at all. They push onto a lock-free queue, and the
render thread takes whatever accumulated since the last frame:

```scala
// LoadRunner — workers add to this and never touch a signal
private val completed = ConcurrentLinkedQueue[Sample]()

def drain(): Vector[Sample] =
  val batch = Vector.newBuilder[Sample]
  var next  = completed.poll()
  while next != null do
    batch += next
    next = completed.poll()
  batch.result()

// LoadTestApp — the render thread takes the batch once per frame
override def onTick(): Unit =
  if phase.peek == Phase.Running then
    val batch = runner.drain()
    stats.update(_.record(batch))
    throughput.update(window => (window :+ batch.size.toLong).takeRight(WindowSize))
```

The obvious alternative — one `Async.run` per request, so each result marshals
itself back — is correct and ruinous: a few thousand requests a second queue a few
thousand continuations a second onto the render loop, and the frame never finishes.
Batching at the frame rate costs nothing, because the screen cannot show more than
that anyway.

## Keep a rolling history

Charts and trend arrows need the last N readings, not the last one. Hold them as an
immutable `Vector` in a `Signal` and replace it on every arrival:

```scala
private def accept(reading: Reading): Unit =
  history.update(readings => (readings :+ reading).takeRight(HistoryLength))
  lastUpdatedNanos = Some(System.nanoTime())
  ageSeconds.set(Some(0L))
  status.set(Status.Ready)
```

Replaced, never mutated: a collection modified in place is `==` to itself, and
`Signal.set` notifies nobody when the new value equals the old, so the screen would
keep the first frame for ever. `takeRight` bounds the heap over a run measured in
days. The "12s ago" readout is recomputed in `onTick` and pushed into its own
signal rather than read in `view`, because `view` only re-runs when something else
already caused a redraw — and setting an equal age notifies nobody, so a 250ms tick
still repaints only once a second.

## Read JSON without a dependency

A codec library that derives its instances by reflection at runtime needs a
`reflect-config.json` and defeats a `--no-fallback` native image, so the examples
parse their own payloads. For a flat object of numbers, a regex is enough:

```scala
private val NumericField = """"([A-Za-z0-9_]+)"\s*:\s*(-?\d+(?:\.\d+)?)""".r

def numericFields(body: String): Map[String, Double] =
  val pairs = NumericField.findAllMatchIn(body)
  pairs.map(found => found.group(1) -> found.group(2).toDouble).toMap

def readingFrom(body: String): Either[String, Reading] =
  val numbers = numericFields(body)
  def field(name: String): Either[String, Double] =
    numbers.get(name).toRight(s"missing field '$name'")
  for
    co2  <- field("rco2")
    pm25 <- field("pm02")
    tvoc <- field("tvocIndex")
    temp <- field("atmp")
  yield Reading(co2, pm25, tvoc, temp)
```

Parsing is a separate function from fetching, so it is testable against a captured
payload with no device on the network. When the response is nested rather than
flat, copy `examples/weather`'s
[`Json.scala`](https://github.com/worxbend/glyphora/blob/main/examples/weather/src/main/scala/io/worxbend/tui/examples/weather/Json.scala)
— 155 lines of recursive descent — rather than taking the dependency. See
[Native binaries](./native-image) for what that constraint buys.

## Where to go next

- [Tables & selection](./tables-and-selection) — what a refresh does to sorting,
  scrolling, and the highlighted row;
- [Charts, gauges & status](./charts-and-status) — putting a rolling history on
  screen at a scale that stays comparable between frames;
- [Build a sensor dashboard](./build-a-sensor-dashboard) — these recipes assembled
  into a polling app with an offline mode;
- [Build a load generator](./build-a-load-generator) — the concurrent half, from an
  empty module to a run summary;
- [Async work & timers](./async-and-timers) — `Stopwatch`, `Timer`, and the tick
  loop itself.

Run the two sources everything here was lifted from with
`./mill examples.airsensor.run` and `./mill examples.loadtest.run`.
