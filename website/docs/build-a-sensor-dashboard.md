---
title: Build a sensor dashboard
description: Build an air-quality dashboard in glyphora — polled sensor readings, threshold bands, metric cards with gauges and trends, and a screen that survives a dead sensor.
---

# Build a sensor dashboard

You will build `airsensor`: an indoor air-quality dashboard that polls a sensor on a
timer, turns every raw number into a threshold band, and keeps the last good reading
on screen when the sensor stops answering. Three files, twelve steps, and every step
runs.

The finished application ships in this repository as
[`examples/airsensor`](https://github.com/oleksandr-balyshyn/glyphora/tree/main/examples/airsensor).
Every snippet below is that source, cut back to the state the tutorial has reached;
the file there carries fuller comments than the fences here.

> **You need:** a clone of glyphora, JDK 25 or newer, and a real terminal — not an
> IDE output panel, because raw input needs a controlling TTY. You do not need a
> sensor or a network: the default data source is a scripted fake.

> **The finished app already ships in the clone**, at `examples/airsensor/`. Build yours
> beside it under a different name — `examples/myairsensor/`, with `build.examples.myairsensor`
> in its `package.mill` and the package renamed to match — or read the finished source
> as you go. Everything below is taken verbatim from it.

## 1. Create the module

Mill discovers example modules by directory, so a new `package.mill` is the whole
registration. Create `examples/airsensor/` and put this in it:

```scala title="examples/airsensor/package.mill"
package build.examples.airsensor

import mill.*

// Everything an example has in common — the GraalVM pin, `--no-fallback`, and a Pilot-capable
// test submodule — lives in `TuiExampleModule` in build.mill.
object `package` extends build.TuiExampleModule {

  def moduleDeps = Seq(build.dsl)

  def mainClass = Some("io.worxbend.tui.examples.airsensor.Main")
}
```

Why `build.dsl` is the only entry the module needs, and what `TuiExampleModule` supplies
on top of it, is argued in
[Build a process monitor](./build-a-process-monitor#1-create-the-module). Outside
this repository the same app is one `mvn"io.worxbend::tui-dsl:0.17.0"` dependency;
see [Getting started](./getting-started#1-add-glyphora).

Every Scala source below lives in
`examples/airsensor/src/main/scala/io/worxbend/tui/examples/airsensor/`, so the
fences name bare files from here on.

```scala title="Main.scala"
package io.worxbend.tui.examples.airsensor

import io.worxbend.tui.dsl.*

final class AirSensorApp extends TuiApp:

  override def bindings: KeyBindings = KeyBindings(
    binding("q", "quit")(quit()),
  )

  def view(using ReactiveScope, Theme): Element =
    scaffold(
      topBar = Some(topBar("airsensor")),
      statusBar = Some(statusBar(bindings)),
    )(centered(52, 3)(panel("airsensor")(text("no reading yet").dim).rounded))

// `TuiApp` supplies `main`, so the launcher only needs an object that *is* the app.
// The class stays a class so tests can build a fresh instance per case.
object Main extends AirSensorApp()
```

```bash
./mill examples.airsensor.run
```

A title bar, a centred panel, and a status bar built from the one binding you
declared. Press `q` to leave:

```
 airsensor
            ╭airsensor─────────────────────────────────────────╮
            │no reading yet                                    │
            ╰──────────────────────────────────────────────────╯
 q quit
```

## 2. The reading, the client, and its fake

The dashboard shows five numbers that must never disagree with each other, so they
arrive as one immutable snapshot rather than five signals. The seam to the outside
world is one trait returning `Either`, which makes a dead sensor an ordinary value
the view can render instead of an exception thrown on a thread with nowhere to catch
it.

```scala title="Sensor.scala"
package io.worxbend.tui.examples.airsensor

import java.util.concurrent.atomic.AtomicInteger

final case class Reading(co2Ppm: Double, pm25: Double, tvocIndex: Double, temperatureC: Double)

trait SensorClient:
  def read(): Either[String, Reading]

final class FakeSensor(script: Vector[Either[String, Reading]] = FakeSensor.DefaultScript)
    extends SensorClient:
  require(script.nonEmpty, "a scripted sensor needs at least one entry")

  // read() runs on an Async worker thread, so the cursor cannot be a plain var
  private val cursor = AtomicInteger(0)

  def read(): Either[String, Reading] =
    script(cursor.getAndIncrement() % script.size)

object FakeSensor:
  val DefaultScript: Vector[Either[String, Reading]] =
    Vector(
      Reading(co2Ppm = 640, pm25 = 4.1, tvocIndex = 72, temperatureC = 21.2),
      Reading(co2Ppm = 715, pm25 = 6.8, tvocIndex = 96, temperatureC = 21.6),
      Reading(co2Ppm = 905, pm25 = 12.4, tvocIndex = 148, temperatureC = 22.1),
      Reading(co2Ppm = 1180, pm25 = 24.9, tvocIndex = 212, temperatureC = 22.8),
      Reading(co2Ppm = 1465, pm25 = 41.2, tvocIndex = 268, temperatureC = 23.4),
      Reading(co2Ppm = 1720, pm25 = 58.6, tvocIndex = 331, temperatureC = 24.1),
      Reading(co2Ppm = 1290, pm25 = 33.5, tvocIndex = 240, temperatureC = 23.6),
      Reading(co2Ppm = 880, pm25 = 11.2, tvocIndex = 130, temperatureC = 22.4),
    ).map(Right(_))
```

```bash
./mill examples.airsensor.compile
```

Nothing on screen changes yet — no view reads a `Reading`. The script is not
arbitrary: a small room with the door shut and then opened, climbing through every
band and falling back, so a reader sees every colour within a minute rather than a
flat green screen. Making the fake the **default** is what lets step 12 test the
whole app with no socket at all. See
[Live data & background work](./live-data#put-the-source-behind-an-interface).

## 3. Three states, one signal

A poll is in flight, finished, or failed. One enum renders in one place; the
alternative — parallel `loading`, `error`, and `value` flags — admits states such as
"loading and failed" that nothing prevents and no branch draws.

```scala title="Main.scala"
package io.worxbend.tui.examples.airsensor

import io.worxbend.tui.dsl.*

enum Status:
  case Loading
  case Ready
  case Failed(message: String)

final class AirSensorApp extends TuiApp:

  // the banner's state carries no reading: the readings live in `history`, so a
  // failure does not take the last good values down with it
  val status: Signal[Status]           = Signal(Status.Loading)
  val history: Signal[Vector[Reading]] = Signal(Vector.empty)

  override def bindings: KeyBindings = KeyBindings(
    binding("q", "quit")(quit()),
  )

  def view(using ReactiveScope, Theme): Element =
    scaffold(
      topBar = Some(topBar("airsensor")),
      statusBar = Some(statusBar(bindings)),
    )(body)

  private def body(using ReactiveScope, Theme): Element =
    history.get.lastOption match
      case None          => firstLoadPane
      case Some(reading) => text(s"CO2 ${reading.co2Ppm} ppm").bold

  private def firstLoadPane(using ReactiveScope, Theme): Element =
    val message = status.get match
      case Status.Failed(problem)        => notice(s"$problem — press r to retry", NoticeLevel.Error)
      case Status.Loading | Status.Ready => spinner("waiting for the first reading...")
    centered(52, 3)(panel("airsensor")(message).rounded)
```

Both signals are **fields**, not locals in `view`. `view` re-runs on every redraw, so
a `Signal` constructed inside it would be a fresh empty signal every frame and the
history could never grow past one entry. Run it again: the panel now holds a spinner
and a line naming what it is waiting for. Even the empty screen says something.

## 4. Fetch once, off the render thread

`client.read()` blocks. Calling it from a key handler would freeze the frame until
the sensor answers. `Async.runCatching` runs the work on a daemon worker and delivers
the result back on the render thread that started it, so the signal writes in the
callback are legal and no thread plumbing appears at the call site.

```scala title="Main.scala"
// the class header gains a client, defaulted so the app runs with no device
final class AirSensorApp(client: SensorClient = FakeSensor()) extends TuiApp:

  private var inFlight = false

  override def bindings: KeyBindings = KeyBindings(
    binding("r", "refresh")(refresh()),
    binding("q", "quit")(quit()),
  )

  private def refresh(): Unit =
    if !inFlight then
      inFlight = true
      if history.peek.isEmpty then status.set(Status.Loading)
      Async.runCatching(client.read()) { outcome =>
        inFlight = false
        // two failure shapes collapse into one: an Either the client returned, and a
        // throwable it did not expect
        val result = outcome.fold(error => Left(AirSensorApp.describeThrowable(error)), identity)
        result match
          case Right(reading) => accept(reading)
          case Left(problem)  => status.set(Status.Failed(problem))
      }

  private def accept(reading: Reading): Unit =
    history.update(readings => (readings :+ reading).takeRight(AirSensorApp.HistoryLength))
    status.set(Status.Ready)

object AirSensorApp:
  val HistoryLength: Int = 240

  private[airsensor] def describeThrowable(error: Throwable): String =
    Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
```

Run it and press `r`: the spinner is replaced by `CO2 640.0 ppm`. `inFlight` is the
only defence against a sensor slower than the poll interval queueing reads behind
each other, because `Async.runCatching` hands back no cancellation handle — the fix
is not starting the second read. `history` is replaced rather than mutated: a
collection modified in place is `==` to itself, and `Signal.set` would notify nobody.
`HistoryLength` bounds it so a run left up overnight cannot grow the heap.

## 5. Poll, and refresh on demand

`Async.every` re-arms a repeating task and hands back a `Cancelable`. Where it is
started and where it is stopped both have concrete wrong answers.

```scala title="Main.scala"
import scala.concurrent.duration.{DurationInt, FiniteDuration}

final class AirSensorApp(
    client: SensorClient = FakeSensor(),
    initialInterval: FiniteDuration = 5.seconds,
) extends TuiApp:

  override def config: RunnerConfig = RunnerConfig(tickRate = Some(AirSensorApp.TickRate))

  private val interval: Signal[FiniteDuration] = Signal(initialInterval)
  private val ageSeconds: Signal[Option[Long]] = Signal(None)
  private var poller: Option[Cancelable]       = None
  private var lastUpdatedNanos: Option[Long]   = None

  override def onStart(): Unit =
    refresh()
    startPolling()

  override def onStop(): Unit = stopPolling()

  override def onTick(): Unit =
    ageSeconds.set(lastUpdatedNanos.map(at => (System.nanoTime() - at) / 1_000_000_000L))

  private def startPolling(): Unit =
    poller = Some(Async.every(interval.peek)(refresh()))

  private def stopPolling(): Unit =
    poller.foreach(_.cancel())
    poller = None

  private def accept(reading: Reading): Unit =
    history.update(readings => (readings :+ reading).takeRight(AirSensorApp.HistoryLength))
    lastUpdatedNanos = Some(System.nanoTime())
    ageSeconds.set(Some(0L))
    status.set(Status.Ready)
```

> **Where this goes wrong:** the poller starts in `onStart`, not in the constructor.
> `Async.every` captures its target render loop from the *calling* thread, and the
> constructor runs before any runner is registered — a poller started there attaches
> to no loop at all and its readings are discarded forever. `onStart` runs on the
> render thread, before the first frame, which is the earliest correct moment. See
> [Live data & background work](./live-data#start-the-poller-in-onstart).

The `refresh()` beside `startPolling()` is not redundant. `Async.every` fires first
*after* a full interval, so without a separate initial load the screen sits on
`Loading` for five seconds. And nothing cancels a repeating task for you, which is
what `onStop` is for: it runs on every exit path — `q`, `Esc`, `Ctrl+C`, a backend
failure — so the poller cannot outlive the run. The timer thread is a daemon so the
JVM still exits either way, but under a headless test an uncancelled poller keeps
firing into a loop nobody drains.

```scala title="Main.scala"
  override def bindings: KeyBindings = KeyBindings(
    binding("r", "refresh")(refresh()),
    binding("+", "slower")(rescale(_ * 2L)),
    binding("-", "faster")(rescale(_ / 2L)),
    binding("q", "quit")(quit()),
    // the same action on a second key, hidden so the hint line does not say "quit" twice
    binding("esc", "quit")(quit()).copy(showInHints = false),
  )

  private def rescale(adjust: FiniteDuration => FiniteDuration): Unit =
    val next = clamp(adjust(interval.peek))
    if next != interval.peek then
      interval.set(next)
      // `Async.every` has no "change the interval" operation: a new cadence means
      // cancel and re-arm
      if poller.nonEmpty then
        stopPolling()
        startPolling()
      notify(s"polling every ${describe(next)}", NoticeLevel.Info)

  private def clamp(duration: FiniteDuration): FiniteDuration =
    if duration < AirSensorApp.MinInterval then AirSensorApp.MinInterval
    else if duration > AirSensorApp.MaxInterval then AirSensorApp.MaxInterval
    else duration

  private def describe(duration: FiniteDuration): String =
    if duration < 1.second then s"${duration.toMillis}ms" else s"${duration.toSeconds}s"

  private def headline(using ReactiveScope): String =
    val cadence = s"every ${describe(interval.get)}"
    ageSeconds.get match
      case Some(seconds) => s"$cadence · updated ${seconds}s ago"
      case None          => s"$cadence · no reading yet"

object AirSensorApp:
  val TickRate: FiniteDuration    = 250.millis
  val HistoryLength: Int          = 240
  val MinInterval: FiniteDuration = 100.millis
  val MaxInterval: FiniteDuration = 60.seconds

  // keep the helper from the previous step — the poll callback still calls it
  private[airsensor] def describeThrowable(error: Throwable): String =
    Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
```

Pass the headline to the title bar — `topBar("airsensor", right = headline)` — and
run. The number now climbs every five seconds with no key pressed, `+` and `-` change
the cadence with a toast, and the bar reads `every 5s · updated 2s ago`. Ageing the
reading in `onTick` rather than in `view` is what keeps that number honest between
polls; setting an equal value notifies nobody, so the header repaints once a second
even though the tick runs four times a second.

## 6. Bands, not booleans

`812 ppm` means nothing to most people; `Moderate` means something to everyone. The
band is a small closed enum rather than a colour, because the screen has to say it
twice — colour for the glance, the word for every reader who cannot rely on colour.

```scala title="Bands.scala"
package io.worxbend.tui.examples.airsensor

import io.worxbend.tui.dsl.*

enum Band:
  case Good, Moderate, Elevated, Unhealthy, VeryUnhealthy

  def label: String = this match
    case Good          => "Good"
    case Moderate      => "Moderate"
    case Elevated      => "Elevated"
    case Unhealthy     => "Unhealthy"
    case VeryUnhealthy => "Very unhealthy"

  // the severity the toolkit already understands, so badges and toasts need no
  // parallel vocabulary
  def level: NoticeLevel = this match
    case Good                      => NoticeLevel.Success
    case Moderate | Elevated       => NoticeLevel.Warning
    case Unhealthy | VeryUnhealthy => NoticeLevel.Error

  def style(using theme: Theme): Style = this match
    case Good                => theme.success
    case Moderate | Elevated => theme.warning
    case Unhealthy           => theme.error
    case VeryUnhealthy       => theme.error.bold

object Band:

  // four upper-inclusive cut-offs; anything above the last is the worst band
  def cascade(good: Double, moderate: Double, elevated: Double, unhealthy: Double)(value: Double): Band =
    if value <= good then Good
    else if value <= moderate then Moderate
    else if value <= elevated then Elevated
    else if value <= unhealthy then Unhealthy
    else VeryUnhealthy

  def worst(bands: Seq[Band]): Band =
    bands.maxByOption(_.ordinal).getOrElse(Good)
```

The colours come from the theme rather than from hard-coded ANSI values, so a
re-theme moves the whole app at once. Append the index the hero panel will show, and
give `Reading` the value the device does not report:

```scala title="Bands.scala"
object AirQuality:

  // (concentration low, concentration high, index low, index high), in ug/m3
  private val Breakpoints: Vector[(Double, Double, Double, Double)] = Vector(
    (0.0, 9.0, 0.0, 50.0),
    (9.1, 35.4, 51.0, 100.0),
    (35.5, 55.4, 101.0, 150.0),
    (55.5, 125.4, 151.0, 200.0),
    (125.5, 225.4, 201.0, 300.0),
    (225.5, 325.4, 301.0, 500.0),
  )

  def aqiFromPm25(pm25: Double): Double =
    // the EPA truncates to one decimal before interpolating, so two sensors reporting
    // 9.04 and 9.09 report the same index
    val truncated = math.floor(math.max(0.0, pm25) * 10.0) / 10.0
    Breakpoints.find((_, concentrationHigh, _, _) => truncated <= concentrationHigh) match
      case Some((concentrationLow, concentrationHigh, indexLow, indexHigh)) =>
        ((indexHigh - indexLow) / (concentrationHigh - concentrationLow)) * (truncated - concentrationLow) + indexLow
      case None                                                             => 500.0
```

```scala title="Sensor.scala"
final case class Reading(co2Ppm: Double, pm25: Double, tvocIndex: Double, temperatureC: Double):
  def aqi: Double = AirQuality.aqiFromPm25(pm25)
```

Put a banded value on screen by replacing `body` — temporarily; step 7 moves those
four cut-offs into a value:

```scala title="Main.scala"
  private def body(using ReactiveScope, Theme): Element =
    history.get.lastOption match
      case None          => firstLoadPane
      case Some(reading) =>
        val band = Band.cascade(50.0, 100.0, 150.0, 200.0)(reading.aqi)
        text(s"AQI ${math.round(reading.aqi)} ${band.label}").bold.styled(_.patch(band.style))
```

Run it: the line reads `AQI 23 Good` in green, then turns amber as the script opens
the door. The word is not decoration — it is the half of the pairing that survives a
monochrome terminal. See
[Charts, gauges & status](./charts-and-status#classify-a-reading-into-a-band).

## 7. One metric card, used four times

Five metrics need the same five facts each: how to pull the number out of a
`Reading`, how to print it, what unit it wears, how far its gauge runs, and how to
band it. Bundling them into one value is what lets the view draw every card with the
same handful of lines — a sixth metric then costs one entry in `Metric.All`.

```scala title="Bands.scala"
final case class Metric(
    label: String,
    unit: String,
    decimals: Int,
    gaugeMax: Double,
    read: Reading => Double,
    classify: Double => Band,
):

  def valueText(reading: Reading): String = format(read(reading))

  // printed under the bar so the bar carries a scale rather than being decorative
  def scaleText: String = s"${format(gaugeMax)} $unit".trim

  def ratio(reading: Reading): Double = read(reading) / gaugeMax

  def bandOf(reading: Reading): Band = classify(read(reading))

  private def format(value: Double): String = s"%.${decimals}f".format(value)

object Metric:

  val Aqi: Metric  = Metric("AQI", "", 0, 500.0, _.aqi, Band.cascade(50.0, 100.0, 150.0, 200.0))
  val Co2: Metric  = Metric("CO2", "ppm", 0, 2000.0, _.co2Ppm, Band.cascade(800.0, 1000.0, 1500.0, 2000.0))
  val Pm25: Metric = Metric("PM2.5", "ug/m3", 1, 125.4, _.pm25, Band.cascade(9.0, 35.4, 55.4, 125.4))
  val Tvoc: Metric = Metric("TVOC", "index", 0, 400.0, _.tvocIndex, Band.cascade(100.0, 200.0, 300.0, 400.0))
  val Temperature: Metric = Metric("Temp", "C", 1, 40.0, _.temperatureC, comfort)

  val Cards: Seq[Metric] = Seq(Co2, Pm25, Tvoc, Temperature)
  val All: Seq[Metric]   = Aqi +: Cards

  private def comfort(celsius: Double): Band =
    if celsius >= 18.0 && celsius <= 26.0 then Band.Good
    else if celsius >= 16.0 && celsius <= 30.0 then Band.Moderate
    else Band.Elevated
```

Temperature is the odd one out: comfort is a *range*, not a ceiling, so it gets a
banded classifier instead of a cascade and tops out at `Elevated` — a cold room is
uncomfortable, not unhealthy. Its gauge runs to 40 C rather than the reference
client's 100 C, which would leave every habitable reading in the first fifth of the
bar. Now the card, and the row of four:

```scala title="Main.scala"
  private def cards(reading: Reading)(using ReactiveScope, Theme): Element =
    row(Metric.Cards.map(metric => card(metric, reading).fill)*).gap(1)

  private def card(metric: Metric, reading: Reading)(using ReactiveScope, Theme): Element =
    val band = metric.bandOf(reading)
    panel(metric.label)(
      text(s"${metric.valueText(reading)} ${metric.unit}".trim).bold.styled(_.patch(band.style)).length(1),
      text(band.label).styled(_.patch(band.style)).length(1),
      progressBar(metric.ratio(reading)).bare.ramp(ColorRamp.Traffic).length(1),
      text(s"of ${metric.scaleText}").dim.length(1),
    ).styled(_.patch(band.style))
```

Point `body`'s `Some(reading)` branch at `cards(reading)` and run: four bordered
cards, each with a value, a band word, a ramped bar, and its own ceiling underneath.
`.bare` drops the percentage label, because a percentage of an arbitrary gauge
maximum is a number nobody asked for; `.ramp` colours the bar by value, which
`gauge` cannot do. See
[Charts, gauges & status](./charts-and-status#colour-a-bar-by-value).

## 8. A grid that does not reflow

Panes sized by their content move when the content changes width, and a dashboard
whose cards jump on every refresh is unreadable. Every pane gets an explicit height
instead.

```scala title="Main.scala"
  // a Computed *field*, not a local in `view`: a Computed created inside `view`
  // re-subscribes to its dependencies on every frame and is never released
  val worstBand: Computed[Band] = Computed {
    history.get.lastOption.fold(Band.Good)(reading => Band.worst(Metric.All.map(_.bandOf(reading))))
  }

  private def body(using ReactiveScope, Theme): Element =
    history.get.lastOption match
      case None          => firstLoadPane
      case Some(reading) =>
        column(banner.length(1), hero(reading).length(5), cards(reading).length(6), spacer)

  private def banner(using ReactiveScope, Theme): Element =
    status.get match
      case Status.Loading         => spinner("reading sensor...")
      case Status.Ready           => notice(s"air quality: ${worstBand.get.label}", worstBand.get.level)
      case Status.Failed(problem) => notice(s"$problem — showing the last good reading", NoticeLevel.Error)
```

The severity the banner carries is the *air's*, not the sensor's, so its icon agrees
with the colour the cards are already wearing. Here is the hero panel:

```scala title="Main.scala"
  private def hero(reading: Reading)(using ReactiveScope, Theme): Element =
    val band = Metric.Aqi.bandOf(reading)
    panel("Air quality index")(
      row(
        text(s"AQI ${Metric.Aqi.valueText(reading)}").bold.styled(_.patch(band.style)).length(10),
        text(band.label).styled(_.patch(band.style)).length(16),
        spacer,
        badge(band.level),
      ).length(1),
      gauge(Metric.Aqi.ratio(reading))
        .label(s"${Metric.Aqi.valueText(reading)} of ${Metric.Aqi.scaleText}")
        .ramp(ColorRamp.Traffic)
        .styled(_ => theme.muted)
        .length(1),
      text(s"derived from PM2.5 ${Metric.Pm25.valueText(reading)} ${Metric.Pm25.unit}").dim.length(1),
    ).rounded
```

Three builders do the work. `.label(...)` replaces the default percentage caption with
fixed text. `.ramp(ColorRamp.Traffic)` colours the fill green through amber to red, so
the bar reads as "how bad is it" rather than "how far along is it" — which is what an
air-quality index means and a download bar does not. `.styled(...)` sets the track
colour; without it the gauge takes the ambient theme's loading palette, the same two
colours the `progressBar` in each card is already using. Run it and the shape is
fixed: banner, hero, cards, then blank space waiting for step 10.

## 9. Trends from a rolling history

An arrow beside a value answers "is this getting worse" without the reader holding
two frames in their head. It compares the newest sample against one a few back, and a
dead band stops it flickering.

```scala title="Bands.scala"
enum Trend:
  case Rising, Falling, Steady

  // one column wide in every case, so a column of cards stays aligned
  def arrow: String = this match
    case Rising  => "▲"
    case Falling => "▼"
    case Steady  => "·"

  def label: String = this match
    case Rising  => "rising"
    case Falling => "falling"
    case Steady  => "steady"

object Trend:

  // three samples at a five-second cadence is fifteen seconds — long enough that the
  // arrow describes the room rather than the sensor's own jitter
  private val Window = 3

  def between(samples: Seq[Double], deadband: Double): Trend =
    if samples.sizeIs < 2 then Steady
    else
      val latest  = samples.last
      val earlier = samples(math.max(0, samples.size - 1 - Window))
      if latest - earlier > deadband then Rising
      else if earlier - latest > deadband then Falling
      else Steady
```

The dead band is scaled to each metric rather than fixed, because one degree of
temperature and one part per million of CO2 are not the same size of change:

```scala title="Main.scala"
  private def trendOf(metric: Metric)(using ReactiveScope): Trend =
    Trend.between(history.get.map(metric.read), deadband = metric.gaugeMax * 0.01)

  private def card(metric: Metric, reading: Reading)(using ReactiveScope, Theme): Element =
    val band  = metric.bandOf(reading)
    val trend = trendOf(metric)
    panel(metric.label)(
      text(s"${metric.valueText(reading)} ${metric.unit}".trim).bold.styled(_.patch(band.style)).length(1),
      row(
        text(band.label).styled(_.patch(band.style)).fill,
        text(trend.arrow).dim.length(1),
      ).length(1),
      progressBar(metric.ratio(reading)).bare.ramp(ColorRamp.Traffic).length(1),
      text(s"of ${metric.scaleText}").dim.length(1),
    ).styled(_.patch(band.style))
```

Add the same to the hero row — `val trend = trendOf(Metric.Aqi)` and a
`text(s"${trend.arrow} ${trend.label}").dim.length(10)` column between the band word
and the `spacer` — then run. Without the dead band a metric wobbling by a fraction of
a unit flips between up and down on nearly every refresh, which reads as noise rather
than as information. See
[Charts, gauges & status](./charts-and-status#show-a-trend-arrow).

## 10. A sparkline with a fixed ceiling

The history pane is why step 4 kept a bounded `Vector` rather than the latest value
alone. One caveat governs it: a sparkline autoscales to its own maximum unless given
one, which makes a calm series look exactly as dramatic as a spike.

```scala title="Main.scala"
  private val showHistory: Signal[Boolean] = Signal(true)
  private val showHelp: Signal[Boolean]    = Signal(false)

  private def historyPane(reading: Reading)(using ReactiveScope, Theme): Element =
    val readings = history.get
    panel(s"History · last ${readings.size} readings")(Metric.All.map(sparkRow(_, readings, reading))*)

  private def sparkRow(metric: Metric, readings: Vector[Reading], latest: Reading)(using Theme): Element =
    // Sparkline takes whole numbers. Scaling by ten keeps PM2.5's one decimal; pinning
    // `max` to the metric's own ceiling is what makes two frames comparable.
    val samples = readings.map(entry => math.round(metric.read(entry) * 10.0))
    row(
      text(metric.label).dim.length(8),
      sparkline(samples).max(math.round(metric.gaugeMax * 10.0))
        .styled(_.patch(metric.bandOf(latest).style))
        .fill,
      text(metric.valueText(latest)).length(8),
    ).length(1)
```

The `sparkline` factory hides `max`, so the pane constructs `SparklineElement`
directly — see
[Charts, gauges & status](./charts-and-status#fix-a-sparklines-ceiling). Add the pane
to `body` and the two overlay keys, `binding("h", "history")(showHistory.update(!_))`
and `binding("?", "help")(showHelp.update(!_))`:

```scala title="Main.scala"
  def view(using ReactiveScope, Theme): Element =
    val shell = scaffold(
      topBar = Some(topBar("airsensor", right = headline)),
      statusBar = Some(statusBar(bindings)),
    )(body)
    if showHelp.get then layers(shell, centered(44, 10)(helpOverlay(bindings, "airsensor keys"))) else shell

  private def body(using ReactiveScope, Theme): Element =
    history.get.lastOption match
      case None          => firstLoadPane
      case Some(reading) =>
        val panes = Seq(banner.length(1), hero(reading).length(5), cards(reading).length(6))
        // an unshown history pane collapses to flexible blank space rather than to a
        // zero-height panel, so the cards above keep their sizes instead of stretching
        column((panes ++ Seq(if showHistory.get then historyPane(reading).fill else spacer))*)
```

Run it. This is the finished dashboard, three readings in:

```
 airsensor                                        every 5s · updated 2s ago
▲ air quality: Moderate
╭Air quality index─────────────────────────────────────────────────────────╮
│AQI 57    Moderate        · steady                                   WARN │
│                                57 of 500                                 │
│derived from PM2.5 12.4 ug/m3                                             │
╰──────────────────────────────────────────────────────────────────────────╯
╭CO2──────────────╮ ╭PM2.5───────────╮ ╭TVOC────────────╮ ╭Temp────────────╮
│905 ppm          │ │12.4 ug/m3      │ │148 index       │ │22.1 C          │
│Moderate        ·│ │Moderate       ·│ │Moderate       ·│ │Good           ·│
│━━━━━━━━─────────│ │━━──────────────│ │━━━━━━──────────│ │━━━━━━━━━───────│
│of 2000 ppm      │ │of 125.4 ug/m3  │ │of 400 index    │ │of 40.0 C       │
╰─────────────────╯ ╰────────────────╯ ╰────────────────╯ ╰────────────────╯
╭History · last 3 readings─────────────────────────────────────────────────╮
│AQI      ▁▁                                                       57      │
│CO2     ▃▃▄                                                       905     │
│PM2.5     ▁                                                       12.4    │
│TVOC    ▁▂▃                                                       148     │
│Temp    ▄▄▄                                                       22.1    │
│                                                                          │
╰──────────────────────────────────────────────────────────────────────────╯
 r refresh  │  + slower  │  - faster  │  h history  │  ? help  │  q quit
```

The gauge and the bars carry colour that plain text cannot show. The band word beside
every value is what survives on a terminal that renders neither colour nor dim, which
is the reason it is there rather than the reason it is redundant.

## 11. A real device, and a stale reading

`/measures/current` on an AirGradient ONE returns a flat object of numbers, so a full
JSON parser would be more machinery than the payload deserves — and a
runtime-deriving codec library would fail a `--no-fallback` native build. This reads
`"name": number` pairs and ignores everything else.

```scala title="Sensor.scala"
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration

final class AirGradientClient(
    baseUrl: String = "http://airgradient.local",
    httpClient: HttpClient = HttpClient.newBuilder().connectTimeout(JDuration.ofSeconds(5)).build(),
) extends SensorClient:

  def read(): Either[String, Reading] =
    fetch(s"$baseUrl/measures/current").flatMap(AirGradientClient.readingFrom)

  private def fetch(url: String): Either[String, String] =
    try
      val request  = HttpRequest.newBuilder(URI.create(url)).timeout(JDuration.ofSeconds(5)).GET().build()
      val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
      if response.statusCode() == 200 then Right(response.body())
      else Left(s"sensor returned HTTP ${response.statusCode()}")
    catch
      case e: java.io.IOException  => Left(describe(e))
      case e: InterruptedException => Left(describe(e))

  private def describe(error: Throwable): String =
    Option(error.getMessage).getOrElse(error.getClass.getSimpleName)

object AirGradientClient:

  private val NumericField = """"([A-Za-z0-9_]+)"\s*:\s*(-?\d+(?:\.\d+)?)""".r

  def numericFields(body: String): Map[String, Double] =
    NumericField.findAllMatchIn(body).map(matched => matched.group(1) -> matched.group(2).toDouble).toMap

  // split out from the client so parsing is testable without a device on the network
  def readingFrom(body: String): Either[String, Reading] =
    val numbers                                     = numericFields(body)
    def field(name: String): Either[String, Double] = numbers.get(name).toRight(s"missing field '$name'")
    for
      co2  <- field("rco2")
      pm25 <- field("pm02")
      tvoc <- field("tvocIndex")
      temp <- field("atmp")
    yield Reading(co2, pm25, tvoc, temp)
```

The five-second timeout matters: a device that has dropped off the network has to
fail as a message on screen rather than as a poller frozen forever. Point the app at
it and run with no such device present:

```scala title="Main.scala"
object Main extends AirSensorApp(AirGradientClient("http://airgradient.local"))
```

The first-load pane reports the connection failure and offers `r`. Once a reading has
arrived, a later failure does *not* replace the cards: the banner turns red and reads
`showing the last good reading`, while the title bar's `updated 42s ago` keeps
counting. A dashboard that discards its last known number is less useful than one
that admits the number is old. Revert `Main` to `AirSensorApp()` before the next step
so the tests and the default run stay device-free.

## 12. Test it

`HeadlessBackend` records the buffers a terminal would have received and `Pilot`
posts real events into it, so the whole dashboard is testable without a PTY.

```scala title="AirSensorAppSpec.scala"
package io.worxbend.tui.examples.airsensor

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{DurationInt, FiniteDuration}

final class AirSensorAppSpec extends AnyFunSuite:

  private val clean = Reading(co2Ppm = 640, pm25 = 4.1, tvocIndex = 72, temperatureC = 21.2)
  private val foul  = Reading(co2Ppm = 1900, pm25 = 90.0, tvocIndex = 380, temperatureC = 31.0)

  // long enough that no timer fires behind the assertions, so only `r` produces a
  // second reading
  private val Manual = 10.seconds

  private def withApp[A](
      script: Vector[Either[String, Reading]],
      interval: FiniteDuration = Manual,
  )(body: (AirSensorApp, Pilot, HeadlessBackend) => A): A =
    val backend = HeadlessBackend(Size(96, 30))
    val app     = AirSensorApp(FakeSensor(script), interval)
    // Preserve runWith's Either so Pilot reports runner failures on the test thread.
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      body(app, pilot, backend)
    }

  private def waitFor(timeout: FiniteDuration = 5.seconds)(predicate: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    while !predicate && System.nanoTime() < deadline do Thread.sleep(20)
```

`waitFor` is the one piece of machinery this suite adds over
[Testing](./testing). `waitForIdle` proves the posted event queue drained; it says
nothing about an `Async` continuation landing afterwards, and ticks cannot be driven
deterministically — so the assertions poll for a condition instead of sleeping for a
guessed number of milliseconds.

```scala title="AirSensorAppSpec.scala"
  test("a failed poll explains itself and keeps the last good reading on screen"):
    withApp(Vector(Right(clean), Left("sensor offline"))) { (app, pilot, _) =>
      waitFor()(pilot.screenText.contains("640 ppm"))

      pilot.press("r")
      waitFor()(pilot.screenText.contains("sensor offline"))

      val screen = pilot.screenText
      assert(screen.contains("showing the last good reading"))
      assert(screen.contains("640 ppm")) // the cards are still there
      assert(app.status.peek == Status.Failed("sensor offline"))
      assert(app.history.peek == Vector(clean))

      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("readings arrive on the poll timer with no key presses"):
    withApp(Vector(Right(clean), Right(foul)), interval = 150.millis) { (app, pilot, backend) =>
      val drawsBefore = backend.drawCount
      waitFor()(app.history.peek.sizeIs >= 2)

      assert(backend.drawCount > drawsBefore) // the timer alone drove repaints
      assert(pilot.screenText.contains("History · last"))

      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("AQI interpolates between the EPA's PM2.5 breakpoints"):
    assert(math.round(AirQuality.aqiFromPm25(9.0)) == 50L)
    assert(math.round(AirQuality.aqiFromPm25(35.4)) == 100L)
    // banding is upper-inclusive, so 9.0 ug/m3 is still the top of Good
    assert(Metric.Pm25.classify(9.0) == Band.Good)
    assert(Metric.Pm25.classify(9.1) == Band.Moderate)
```

```bash
./mill examples.airsensor.test
```

The classifier tests sit on the boundaries, because that is where a cascade is wrong
if it is wrong at all. The screen assertions read the band **word** rather than a
cell style: the glyphs are identical across bands, so a colour assertion needs
`pilot.cellAt` and a coordinate the layout is free to move — see
[Testing](./testing#assert-style-separately) for when that trade pays.

Then the binary:

```bash
./mill examples.airsensor.nativeImage
./out/examples/airsensor/nativeImage.dest/native-executable
```

Nothing in the app reflects, so this builds with `--no-fallback` and no
`reflect-config.json`. See [Native binaries](./native-image).

## What you learned

| What you built | Where the reasoning goes deeper |
|---|---|
| the poller, its lifecycle, and the separate initial fetch | [Live data & background work](./live-data#start-the-poller-in-onstart) |
| `Async.runCatching` and the bounded rolling history | [Live data & background work](./live-data#keep-a-rolling-history) |
| bands, trends, and bars coloured by value | [Charts, gauges & status](./charts-and-status#classify-a-reading-into-a-band) |
| the sparkline's pinned ceiling | [Charts, gauges & status](./charts-and-status#fix-a-sparklines-ceiling) |
| panes with fixed heights that never reflow | [Layout & style](./layout-and-style) |
| the band word beside every colour | [Unicode & accessibility](./unicode-and-accessibility) |
| `Pilot`, `HeadlessBackend`, and polled assertions | [Testing](./testing) |

## Where to go next

- [Build a process monitor](./build-a-process-monitor) — the same shape over a
  sortable table whose selection has to survive every refresh.
- [Build a load generator](./build-a-load-generator) — concurrent work you own,
  rather than a source you poll.
- [The app shell](./app-shell) — screens, the command palette, and themes, for when
  one pane is no longer enough.
- [Async work & timers](./async-and-timers) — the render-thread contract every
  callback above obeys.
