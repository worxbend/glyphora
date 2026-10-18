package io.worxbend.tui.examples.airsensor

import io.worxbend.tui.dsl.*

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Where the last poll left the app. Three cases, and the view has a screen for each of them — that is the whole point:
  * there is no fourth, unhandled state in which the user is looking at an empty pane wondering whether the program is
  * working.
  */
enum Status:
  case Loading
  case Ready
  case Failed(message: String)

/** airsensor: an indoor air-quality dashboard modelled on `worxbend/airgradient-cli`, polling a sensor on a timer and
  * mapping every reading onto a threshold band.
  *
  * What it teaches:
  *   - **Polling on a timer.** `Async.every` re-arms a background read; the continuation lands back on the render
  *     thread, which is the only place a `Signal` may be written.
  *   - **A loading → ready → error state machine.** A failed poll keeps the last good reading on screen and says so,
  *     rather than blanking the pane.
  *   - **Threshold bands.** Raw numbers become `Good`/`Moderate`/`Elevated`/`Unhealthy`, shown as *both* a colour and a
  *     word, because colour alone is not accessible.
  *   - **Metric cards.** One data-driven card shape — value, band, ramped bar, scale — reused for every metric.
  *   - **Trend arrows from a rolling history.** A bounded `Vector` in a `Signal`, with a dead band so the arrow reports
  *     the room rather than the sensor's jitter.
  *
  * Keys: `r` refresh now · `+` slower · `-` faster · `h` toggle history · `?` help · `q`/`Esc` quit.
  */
class AirSensorApp(
    client: SensorClient = FakeSensor(),
    initialInterval: FiniteDuration = 5.seconds,
) extends TuiApp:

  override def config: RunnerConfig = RunnerConfig(tickRate = Some(AirSensorApp.TickRate))

  // ---- state that must outlive `view` ----------------------------------------------------------
  // `view` re-runs on every redraw, so anything constructed inside it is rebuilt from scratch each frame.

  /** What the banner reports. Deliberately carries no reading: the readings live in [[history]], so a failure does not
    * take the last good values down with it.
    */
  val status: Signal[Status] = Signal(Status.Loading)

  /** The rolling window the sparklines and the trend arrows are computed from. Bounded, and replaced rather than
    * mutated — a collection modified in place is `==` to itself and `Signal.set` would notify nobody.
    */
  val history: Signal[Vector[Reading]] = Signal(Vector.empty)

  /** The worst band across every metric — a `Computed` **field**, not a local in `view`. A `Computed` created inside
    * `view` re-subscribes to its dependencies on every frame and is never released.
    */
  val worstBand: Computed[Band] = Computed {
    history.get.lastOption.fold(Band.Good)(reading => Band.worst(Metric.All.map(_.bandOf(reading))))
  }

  private val interval: Signal[FiniteDuration] = Signal(initialInterval)
  private val ageSeconds: Signal[Option[Long]] = Signal(None)
  private val showHistory: Signal[Boolean]     = Signal(true)
  private val showHelp: Signal[Boolean]        = Signal(false)

  /** The polling half of the app; the view below stays a pure function of the signals it writes. */
  private val sensorPoller: SensorPoller =
    SensorPoller(client, interval, history, status, ageSeconds, describe, (message, level) => notify(message, level))

  // ---- keys ------------------------------------------------------------------------------------

  override def bindings: KeyBindings = KeyBindings(
    binding("r", "refresh")(sensorPoller.refresh()),
    binding("+", "slower")(sensorPoller.rescale(_ * 2L)),
    binding("-", "faster")(sensorPoller.rescale(_ / 2L)),
    binding("h", "history")(showHistory.update(!_)),
    binding("?", "help")(showHelp.update(!_)),
    binding("q", "quit")(quit()),
    // the same action on a second key, hidden from the status bar so the hint line does not say "quit" twice
    binding("esc", "quit")(quit()).copy(showInHints = false),
  )

  // ---- lifecycle -------------------------------------------------------------------------------

  /** Runs on the render thread before the first frame — the earliest point at which arming the poller is correct.
    *
    * `Async.every` captures its target render loop from the *calling* thread, and this app is constructed before any
    * runner exists, so a poller started in a field initialiser would attach to no loop and its readings would be
    * discarded forever.
    */
  override def onStart(): Unit =
    sensorPoller.refresh()
    sensorPoller.start()

  /** Runs on every exit path — `q`, `Esc`, Ctrl+C, a backend failure.
    *
    * Nothing cancels a repeating task for you. Its thread is a daemon so the JVM still exits, but under a headless test
    * the runner ends while the poller keeps firing into a loop nobody drains.
    */
  override def onStop(): Unit = sensorPoller.stop()

  override def onTick(): Unit =
    // Setting an equal value notifies nobody, so this repaints the header once a second even though it runs four
    // times a second. Recomputing the age here rather than in `view` is what keeps "12s ago" honest between polls.
    ageSeconds.set(sensorPoller.lastUpdatedAt.map(at => (System.nanoTime() - at) / 1_000_000_000L))

  // ---- view ------------------------------------------------------------------------------------

  def view(using ReactiveScope, Theme): Element =
    val shell = scaffold(
      topBar = Some(topBar("airsensor", right = headline)),
      statusBar = Some(statusBar(bindings)),
    )(body)
    if showHelp.get then layers(shell, centered(44, 10)(helpOverlay(bindings, "airsensor keys"))) else shell

  private def headline(using ReactiveScope): String =
    val cadence = s"every ${describe(interval.get)}"
    ageSeconds.get match
      case Some(seconds) => s"$cadence · updated ${seconds}s ago"
      case None          => s"$cadence · no reading yet"

  private def body(using ReactiveScope, Theme): Element =
    history.get.lastOption match
      case None          => firstLoadPane
      case Some(reading) =>
        val panes = Seq(banner.length(1), hero(reading).length(5), cards(reading).length(6))
        // an unshown history pane collapses to flexible blank space rather than to a zero-height panel, so the cards
        // above it keep their sizes instead of stretching
        column((panes ++ Seq(if showHistory.get then historyPane(reading).fill else spacer))*)

  /** The only screen with nothing to show, and even it says what it is waiting for. */
  private def firstLoadPane(using ReactiveScope, Theme): Element =
    val message = status.get match
      case Status.Failed(problem)        => notice(s"$problem — press r to retry", NoticeLevel.Error)
      case Status.Loading | Status.Ready => spinner("waiting for the first reading...")
    centered(52, 3)(panel("airsensor")(message).rounded)

  /** One line that always says what the app is doing. A failed poll never blanks the cards: it explains itself and
    * leaves the last good reading up, marked as stale.
    *
    * The severity carried here is the *air's*, not the sensor's, so the notice icon agrees with the colour the cards
    * are already wearing.
    */
  private def banner(using ReactiveScope, Theme): Element =
    status.get match
      case Status.Loading         => spinner("reading sensor...")
      case Status.Ready           => notice(s"air quality: ${worstBand.get.label}", worstBand.get.level)
      case Status.Failed(problem) => notice(s"$problem — showing the last good reading", NoticeLevel.Error)

  private def hero(reading: Reading)(using ReactiveScope, Theme): Element =
    val band  = Metric.Aqi.bandOf(reading)
    val trend = trendOf(Metric.Aqi)
    panel("Air quality index")(
      row(
        text(s"AQI ${Metric.Aqi.valueText(reading)}").bold.styled(_.patch(band.style)).length(10),
        text(band.label).styled(_.patch(band.style)).length(16),
        text(s"${trend.arrow} ${trend.label}").dim.length(10),
        spacer,
        badge(band.level),
      ).length(1),
      // `ColorRamp.Traffic` walks the fill green -> amber -> red, so the hero bar reads as "how bad is it" at a
      // glance rather than merely "how far along is it"
      gauge(Metric.Aqi.ratio(reading))
        .label(s"${Metric.Aqi.valueText(reading)} of ${Metric.Aqi.scaleText}")
        .ramp(ColorRamp.Traffic)
        .styled(_ => theme.muted)
        .length(1),
      text(s"derived from PM2.5 ${Metric.Pm25.valueText(reading)} ${Metric.Pm25.unit}").dim.length(1),
    ).rounded

  private def cards(reading: Reading)(using ReactiveScope, Theme): Element =
    grid(cols = Metric.Cards.map(_ => Constraint.Fill(1)), rows = Seq(Constraint.Fill(1)), gap = 1)(
      Metric.Cards.map(card(_, reading))*
    )

  /** Every card is the same six lines; only the [[Metric]] differs. Band shows twice on purpose — as the panel's colour
    * and as a word inside it.
    */
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

  private def historyPane(reading: Reading)(using ReactiveScope, Theme): Element =
    val readings = history.get
    panel(s"History · last ${readings.size} readings")(Metric.All.map(sparkRow(_, readings, reading))*)

  private def sparkRow(metric: Metric, readings: Vector[Reading], latest: Reading)(using Theme): Element =
    // Sparkline takes whole numbers, and it autoscales to its own maximum unless given one — which would make a calm
    // series look exactly as dramatic as a spike. `SparkScale` keeps PM2.5's one decimal; pinning `max` to the
    // metric's own ceiling is what makes two frames comparable.
    val samples = readings.map(entry => math.round(metric.read(entry) * AirSensorApp.SparkScale))
    row(
      text(metric.label).dim.length(8),
      sparkline(samples)
        .max(math.round(metric.gaugeMax * AirSensorApp.SparkScale))
        .styled(_.patch(metric.bandOf(latest).style))
        .fill,
      text(metric.valueText(latest)).length(8),
    ).length(1)

  private def trendOf(metric: Metric)(using ReactiveScope): Trend =
    Trend.between(history.get.map(metric.read), deadband = metric.gaugeMax * AirSensorApp.DeadbandFraction)

  private def describe(duration: FiniteDuration): String =
    if duration < 1.second then s"${duration.toMillis}ms" else s"${duration.toSeconds}s"

/** The polling half of [[AirSensorApp]]: arming and re-arming the timer, keeping slower-than-the-interval sensors from
  * queueing reads behind each other, and folding each finished read into the signals the view renders from.
  *
  * Everything it touches arrives as a constructor signal, so the class never builds an `Element` and the app never
  * touches a `Cancelable`.
  */
private class SensorPoller(
    client: SensorClient,
    interval: Signal[FiniteDuration],
    history: Signal[Vector[Reading]],
    status: Signal[Status],
    ageSeconds: Signal[Option[Long]],
    describe: FiniteDuration => String,
    notifyUser: (String, NoticeLevel) => Unit,
):

  // Plain vars, not signals: these are read and written only on the render thread and nothing renders from them
  // directly, so making them reactive would buy an extra repaint and nothing else.
  private var poller: Option[Cancelable]     = None
  private var inFlight                       = false
  private var lastUpdatedNanos: Option[Long] = None

  /** When the last accepted reading landed, so the app's tick can age the "updated Ns ago" line between polls. */
  def lastUpdatedAt: Option[Long] = lastUpdatedNanos

  def start(): Unit =
    poller = Some(Async.every(interval.peek)(refresh()))

  def stop(): Unit =
    poller.foreach(_.cancel())
    poller = None

  def rescale(adjust: FiniteDuration => FiniteDuration): Unit =
    val next = clamp(adjust(interval.peek))
    if next != interval.peek then
      interval.set(next)
      // `Async.every` has no "change the interval" operation, so a new cadence means cancel and re-arm.
      if poller.nonEmpty then
        stop()
        start()
      notifyUser(s"polling every ${describe(next)}", NoticeLevel.Info)

  private def clamp(duration: FiniteDuration): FiniteDuration =
    if duration < AirSensorApp.MinInterval then AirSensorApp.MinInterval
    else if duration > AirSensorApp.MaxInterval then AirSensorApp.MaxInterval
    else duration

  /** Starts one read. Runs on the render thread; the read itself does not.
    *
    * `inFlight` is what stops a sensor slower than the poll interval from queueing reads behind each other — there is
    * no cancellation handle for `Async.runCatching`, so the only defence is not starting the next one.
    */
  def refresh(): Unit =
    if !inFlight then
      inFlight = true
      if history.peek.isEmpty then status.set(Status.Loading)
      // `runCatching` runs the read on a worker thread and marshals this callback back onto the render thread that
      // started it — so the signal writes below are legal, and no `RenderThread` plumbing appears at the call site.
      Async.runCatching(client.read()) { outcome =>
        inFlight = false
        // two failure shapes collapse into one: an `Either` the client returned, and a throwable it did not expect
        val result = outcome.fold(error => Left(AirGradientClient.describeThrowable(error)), identity)
        result match
          case Right(reading) => accept(reading)
          case Left(problem)  => status.set(Status.Failed(problem))
      }

  private def accept(reading: Reading): Unit =
    history.update(readings => (readings :+ reading).takeRight(AirSensorApp.HistoryLength))
    lastUpdatedNanos = Some(System.nanoTime())
    ageSeconds.set(Some(0L))
    status.set(Status.Ready)

object AirSensorApp:

  /** Fast enough for a smooth spinner on the first load; the poll cadence is a separate, much slower clock. */
  val TickRate: FiniteDuration = 250.millis

  /** Enough samples to fill a sparkline on a wide terminal, and a hard ceiling so a long run cannot grow the heap. */
  val HistoryLength: Int = 240

  val MinInterval: FiniteDuration = 100.millis
  val MaxInterval: FiniteDuration = 60.seconds

  /** Sparkline takes whole numbers; scaling readings by ten keeps PM2.5's one decimal place visible. */
  private val SparkScale: Double = 10.0

  /** How far a metric may drift, as a fraction of its gauge ceiling, before the trend arrow reports a move. */
  private val DeadbandFraction: Double = 0.01

object Main extends AirSensorApp()
