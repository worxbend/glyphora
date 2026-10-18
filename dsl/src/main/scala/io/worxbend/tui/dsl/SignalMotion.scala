package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Easing, Tween}
import io.worxbend.tui.runtime.{Cancelable, RenderThread, Signal}

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationLong, FiniteDuration}

/** Moves a numeric signal from its current value to `target` over `duration`, easing with `easing`, on the ticks the
  * run already has — sugar over [[Tween]] for the value a view consumes, with no `onTick` and no elapsed counter.
  *
  * {{{
  * private val ratio = Signal(0.0)
  * button("fill")(ratio.animateTo(1.0, 600.millis, Easing.CubicOut))
  * gauge(ratio.get)
  * }}}
  *
  * Each tick samples the tween at the time elapsed since the start and `set`s the signal, so the view repaints through
  * the ordinary reactive path; the last sample is exactly `target`. Starting another `animateTo` on the same signal
  * replaces the motion in flight from wherever the value has got to. The returned handle stops the motion where it is.
  *
  * Defined for `Signal[Double]` and `Signal[Int]` through [[AnimatedValue]] — an `Int` signal rounds each sample half
  * up, so a scroll offset or a column width moves in whole cells and lands exactly on `target`.
  *
  * Time is the run's own clock — the `nanoTime` handed to `TuiApp.runWith`, so a `ManualClock` steps it in tests — and
  * ticks come from a configured `tickRate` when the app has one, and from the ambient ticker (the one that spins a
  * `spinner`) otherwise, armed for as long as a motion is in flight. A non-positive duration, or a target equal to the
  * current value, sets the target at once and returns a handle with nothing to cancel.
  *
  * Render-thread only, like `Signal.set` itself: start it from a handler, a timer body or `onStart`, and cancel it from
  * the same thread. Motions belong to the run that started them and are dropped when it ends.
  */
extension [A](signal: Signal[A])(using value: AnimatedValue[A])
  def animateTo(target: A, duration: FiniteDuration, easing: Easing = Easing.QuadOut): Cancelable =
    SignalMotion.start(
      signal,
      value.toDouble(signal.peek),
      value.toDouble(target),
      duration,
      easing,
      sample => signal.set(value.fromDouble(sample)),
    )

/** The numeric types [[animateTo]] is defined for: how a value becomes the `Double` a [[Tween]] moves, and how a sample
  * becomes a value again. `Double` is the identity; `Int` rounds half up. Sealed, because a tween over anything else is
  * a different feature — interpolating a `Color` or a `Rect` is not a rounding rule.
  */
sealed trait AnimatedValue[A]:
  def toDouble(value: A): Double
  def fromDouble(sample: Double): A

object AnimatedValue:
  given double: AnimatedValue[Double] with
    def toDouble(value: Double): Double    = value
    def fromDouble(sample: Double): Double = sample

  given int: AnimatedValue[Int] with
    def toDouble(value: Int): Double    = value.toDouble
    def fromDouble(sample: Double): Int = math.round(sample).toInt

/** The motions in flight, per render loop, and the clock each loop samples them against.
  *
  * Per loop for the same reason `AnimationClock` is: a motion `set`s a signal, which is confined to the render thread
  * that owns it, and two runners in one JVM are two such threads. The map is concurrent only so that a loop can be
  * looked up from any thread; each loop's own entry — its motions, its clock — is touched by that loop's render thread
  * alone. `TuiApp` attaches a run's clock and wake-up in `onStart` and releases the entry on exit; a caller belonging
  * to no attached run gets the system clock and no wake-up, which is enough for a unit test that steps motions itself.
  */
private[dsl] object SignalMotion:

  private final class Motion(val startNanos: Long, val tween: Tween, val apply: Double => Unit):
    var cancelled: Boolean = false

  private final class Loop(val clock: () => Long, val wake: () => Unit):
    val motions: mutable.LinkedHashMap[Signal[?], Motion] = mutable.LinkedHashMap.empty

  private val loops = ConcurrentHashMap[RenderThread.RenderLoop, Loop]()

  private def loop: Loop =
    val key = RenderThread.capture()
    Option(loops.get(key)).getOrElse(loops.computeIfAbsent(key, _ => Loop(() => System.nanoTime(), () => ())))

  /** Binds the calling thread's render loop to `clock` (the run's `nanoTime`) and `wake` (its redraw request). */
  private[dsl] def attachToCurrentLoop(clock: () => Long, wake: () => Unit): Unit =
    val _ = loops.put(RenderThread.capture(), Loop(clock, wake))

  /** Drops `loop`'s motions and clock once its runner has exited. */
  private[dsl] def releaseLoop(loop: RenderThread.RenderLoop): Unit =
    val _ = loops.remove(loop)

  /** Whether the calling loop has a motion in flight — what the ambient ticker asks before standing down. */
  private[dsl] def isActive: Boolean =
    Option(loops.get(RenderThread.capture())).exists(_.motions.nonEmpty)

  /** One tick: samples every motion of the calling loop at its clock and sets the signals, dropping finished ones. */
  private[dsl] def step(): Unit =
    Option(loops.get(RenderThread.capture())).foreach { current =>
      if current.motions.nonEmpty then
        val now = current.clock()
        current.motions.filterInPlace { (_, motion) =>
          if motion.cancelled then false
          else
            val elapsed = (now - motion.startNanos).nanos
            motion.apply(motion.tween.at(elapsed))
            !motion.tween.isDone(elapsed)
        }
    }

  private[dsl] def start(
      signal: Signal[?],
      from: Double,
      to: Double,
      duration: FiniteDuration,
      easing: Easing,
      apply: Double => Unit,
  ): Cancelable =
    if duration <= Duration.Zero || from == to then
      apply(to)
      () => ()
    else
      val current = loop
      val motion  = Motion(current.clock(), Tween(from, to, duration, easing), apply)
      current.motions.update(signal, motion)
      // the ambient ticker is retargeted after a frame, so a run with no tick rate owes one frame before it ticks
      current.wake()
      () =>
        motion.cancelled = true
        if current.motions.get(signal).exists(_ eq motion) then
          val _ = current.motions.remove(signal)
