package io.worxbend.tui.widgets

import io.worxbend.tui.core.VersionedState

import scala.concurrent.duration.{Duration, DurationInt, DurationLong, FiniteDuration}

/** Caller-owned progress of one unit of work — what a [[MultiProgress]] row reads.
  *
  * Holds the four things a progress column can be computed from: a `label`, an optional `total` (`None` for work whose
  * size is not known up front), the `completed` count, and the start/finish stamps. Everything a column shows — the
  * fraction, the elapsed time, the rate, the ETA — is derived from those on read, so there is nothing to keep in step.
  *
  * Time comes from `clock`, read whenever the task stamps a start or a finish and whenever an estimate is asked for.
  * The default is the monotonic system clock; a test passes a clock it moves by hand (`ManualClock.elapsed` in
  * `tui-test`), which is what makes an ETA assertion exact. Only differences between two readings are used, so the
  * origin is irrelevant.
  *
  * The rate is measured over a sliding window, `rateWindow` (ten seconds by default): the units completed since the
  * last progress report at or before the window's start, over the time since that report. A download that speeds up or
  * a build stage that slows down shows in the rate and the ETA within one window instead of averaging away, and a stall
  * longer than the window makes both unknown. `rateWindow = None` keeps the average since the start, which settles
  * rather than reacts. Only the reports inside the window plus one anchor are kept, so the history stays small.
  * `completed` is clamped into `[0, total]` on every write.
  *
  * '''Threads.''' Render-thread-only, like every widget state: a worker reports progress by posting `task.advance(n)`
  * back onto the render thread (`RenderThread.capture()` / `Async`), not by calling it directly. Every mutation raises
  * `version` (see [[io.worxbend.tui.core.Versioned]]), so a DSL view painting the task notices a write made outside the
  * event path and redraws.
  *
  * @param initialLabel
  *   the description column's text
  * @param initialTotal
  *   how many units the work has, or `None` when that is unknown
  * @param clock
  *   the time source; defaults to `System.nanoTime`
  * @param rateWindow
  *   how far back [[rate]] and [[eta]] look; `None` averages over the whole run
  */
final class ProgressTask(
    initialLabel: String,
    initialTotal: Option[Long] = None,
    clock: () => FiniteDuration = ProgressTask.SystemClock,
    rateWindow: Option[FiniteDuration] = Some(ProgressTask.DefaultRateWindow),
) extends VersionedState:

  private var currentLabel: String                = initialLabel
  private var currentTotal: Option[Long]          = initialTotal.map(math.max(0L, _))
  private var done: Long                          = 0L
  private var startStamp: Option[FiniteDuration]  = None
  private var finishStamp: Option[FiniteDuration] = None

  // (time, completed) at the start and at every progress report since, oldest first; pruned to the window plus the one
  // report at or before its start, which is the anchor the windowed rate measures from
  private var reports = Vector.empty[(FiniteDuration, Long)]

  def label: String = currentLabel

  def label_=(value: String): Unit =
    currentLabel = value
    touch()

  def total: Option[Long] = currentTotal

  /** Changes the total — work that discovers its size part-way through. `completed` is re-clamped to the new total. */
  def total_=(value: Option[Long]): Unit =
    currentTotal = value.map(math.max(0L, _))
    done = clampToTotal(done)
    touch()

  def completed: Long = done

  /** When [[start]] (or the first [[advance]]) stamped the task, on `clock`'s scale. */
  def startedAt: Option[FiniteDuration] = startStamp

  def isStarted: Boolean = startStamp.isDefined

  def isFinished: Boolean = finishStamp.isDefined

  /** Stamps the start time. A second call does nothing: the first start is the one elapsed time counts from. */
  def start(): Unit =
    if startStamp.isEmpty then
      val now = clock()
      startStamp = Some(now)
      reports = Vector(now -> done)
      touch()

  /** Adds `by` units, starting the task first if it has not been. */
  def advance(by: Long = 1L): Unit =
    start()
    done = clampToTotal(done + by)
    report()
    touch()

  /** Sets the completed count outright, starting the task first if it has not been. */
  def update(completed: Long): Unit =
    start()
    done = clampToTotal(completed)
    report()
    touch()

  /** Marks the work done: a bounded task is filled to its total, and elapsed time stops counting. */
  def finish(): Unit =
    start()
    currentTotal.foreach(done = _)
    if finishStamp.isEmpty then finishStamp = Some(clock())
    touch()

  /** `completed / total` in `[0, 1]`, or `None` when the total is unknown. A zero total reads as complete. */
  def fraction: Option[Double] =
    currentTotal.map(t => if t == 0L then 1.0 else done.toDouble / t)

  /** Units still to do, or `None` when the total is unknown. */
  def remaining: Option[Long] = currentTotal.map(t => math.max(0L, t - done))

  /** Time since the start, frozen at the finish; zero before the task starts. */
  def elapsed: FiniteDuration =
    startStamp.fold(Duration.Zero)(started => finishStamp.getOrElse(clock()) - started).max(Duration.Zero)

  /** Units per second over the last `rateWindow` (since the start when it is `None`), or `None` until there is both
    * elapsed time and progress to divide — and after a stall longer than the window.
    */
  def rate: Option[Double] =
    rateWindow match
      case None         =>
        val seconds = elapsed.toNanos.toDouble / 1e9
        Option.when(seconds > 0.0 && done > 0L)(done / seconds)
      case Some(window) =>
        startStamp.flatMap { _ =>
          val end                  = finishStamp.getOrElse(clock())
          val (anchorAt, anchored) = reports.findLast(_._1 <= end - window).getOrElse(reports.head)
          val seconds              = (end - anchorAt).toNanos.toDouble / 1e9
          val units                = done - anchored
          Option.when(seconds > 0.0 && units > 0L)(units / seconds)
        }

  /** Estimated time left at [[rate]]: zero once finished, `None` when the total or the rate is unknown. */
  def eta: Option[FiniteDuration] =
    if isFinished then Some(Duration.Zero)
    else
      for
        left  <- remaining
        speed <- rate
      yield (left / speed * 1e9).toLong.nanos

  /** Records the progress just made and drops the reports the window no longer needs. */
  private def report(): Unit =
    val now = clock()
    reports = reports :+ (now -> done)
    rateWindow.foreach { window =>
      // keep the newest report at or before the window's start as the anchor; everything older is history nobody reads
      while reports.sizeIs > 1 && reports(1)._1 <= now - window do reports = reports.tail
    }
    if rateWindow.isEmpty then reports = reports.take(1)

  private def clampToTotal(value: Long): Long =
    val floor = math.max(0L, value)
    currentTotal.fold(floor)(math.min(floor, _))

object ProgressTask:

  /** The monotonic system clock, as a duration since an arbitrary origin. */
  val SystemClock: () => FiniteDuration = () => System.nanoTime().nanos

  /** How far back [[ProgressTask.rate]] looks unless told otherwise. */
  val DefaultRateWindow: FiniteDuration = 10.seconds
