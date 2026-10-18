package io.worxbend.tui.widgets

import io.worxbend.tui.core.VersionedState

import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration}

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
  * The rate is the average since the start — completed units over elapsed time — not a sliding window: it settles
  * rather than jitters, and it needs no history. `completed` is clamped into `[0, total]` on every write.
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
  */
final class ProgressTask(
    initialLabel: String,
    initialTotal: Option[Long] = None,
    clock: () => FiniteDuration = ProgressTask.SystemClock,
) extends VersionedState:

  private var currentLabel: String                = initialLabel
  private var currentTotal: Option[Long]          = initialTotal.map(math.max(0L, _))
  private var done: Long                          = 0L
  private var startStamp: Option[FiniteDuration]  = None
  private var finishStamp: Option[FiniteDuration] = None

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
      startStamp = Some(clock())
      touch()

  /** Adds `by` units, starting the task first if it has not been. */
  def advance(by: Long = 1L): Unit =
    start()
    done = clampToTotal(done + by)
    touch()

  /** Sets the completed count outright, starting the task first if it has not been. */
  def update(completed: Long): Unit =
    start()
    done = clampToTotal(completed)
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

  /** Average units per second since the start, or `None` until there is both elapsed time and progress to divide. */
  def rate: Option[Double] =
    val seconds = elapsed.toNanos.toDouble / 1e9
    Option.when(seconds > 0.0 && done > 0L)(done / seconds)

  /** Estimated time left at the average rate: zero once finished, `None` when the total or the rate is unknown. */
  def eta: Option[FiniteDuration] =
    if isFinished then Some(Duration.Zero)
    else
      for
        left  <- remaining
        speed <- rate
      yield (left / speed * 1e9).toLong.nanos

  private def clampToTotal(value: Long): Long =
    val floor = math.max(0L, value)
    currentTotal.fold(floor)(math.min(floor, _))

object ProgressTask:

  /** The monotonic system clock, as a duration since an arbitrary origin. */
  val SystemClock: () => FiniteDuration = () => System.nanoTime().nanos
