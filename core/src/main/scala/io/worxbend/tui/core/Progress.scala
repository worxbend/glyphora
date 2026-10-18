package io.worxbend.tui.core

import scala.concurrent.duration.FiniteDuration

/** Where an animation is at a given `elapsed` — the one place that turns time into position.
  *
  * Two flavours of the same question, deliberately kept side by side so they cannot drift apart:
  *
  *   - [[normalized]] is the '''one-shot''' answer: a fraction that runs past 1 once the animation is over. [[Tween]]
  *     and the timed [[Effect]]s use it, then hand it to an [[Easing]], which clamps.
  *   - [[stepped]] and [[steppedAtRate]] are the '''looping''' answers: a whole position in `0 until steps` that wraps
  *     round and round forever. The animated widgets (`Marquee`, `IndeterminateBar`, the spinners) use those.
  *   - [[staggered]] is the '''per-cell''' answer: one cell's own progress through an animation that reaches different
  *     cells at different times. Every [[Pattern]] is a way of choosing each cell's delay for it.
  *
  * `stepped` is exactly `floor(normalized * steps)` folded into one cycle, only evaluated in integer arithmetic so that
  * a sample taken at an exact fraction of the period lands on the step that fraction names rather than one below it.
  *
  * All three are pure functions of the time they are handed: they hold no clock, so the caller owns the clock. See
  * [[Effect]] for what that means in a render loop.
  */
object Progress:

  /** `elapsed / total` as a fraction, with a zero-length animation reading as finished rather than dividing by zero.
    *
    * The result is deliberately *not* clamped: an `elapsed` past `total` returns a value above 1, and callers that care
    * hand it to `Easing.apply`, which clamps into `[0, 1]` itself.
    */
  def normalized(elapsed: FiniteDuration, total: FiniteDuration): Double =
    if total.toNanos == 0 then 1.0 else elapsed.toNanos.toDouble / total.toNanos

  /** Which of `steps` positions a cycle of length `period` is at after `elapsed`, wrapping forever.
    *
    * Returns `0` for a non-positive `steps` or `period`, rather than dividing by zero: both come from caller arithmetic
    * (`area.width - segment`, a configured duration) and a render must not take the app down. A negative `elapsed` —
    * which a caller subtracting two timestamps can produce — wraps backwards through the cycle the same way a positive
    * one wraps forwards, so the animation has no double-width step around time zero.
    */
  def stepped(elapsed: FiniteDuration, period: FiniteDuration, steps: Int): Int =
    if steps <= 0 || period.toNanos <= 0L then 0
    else
      // fold into one cycle *first*, so the multiplication below stays well inside Long: `withinCycle` is smaller than
      // `period`, and an animation period times a step count is nanoseconds-scale arithmetic, not epoch-scale
      val withinCycle = math.floorMod(elapsed.toNanos, period.toNanos)
      (withinCycle * steps / period.toNanos).toInt

  /** Which of `steps` positions a run advancing `perSecond` positions per second is at after `elapsed`, wrapping
    * forever. The rate-flavoured sibling of [[stepped]], for animations configured as "so many cells per second"
    * instead of "one lap per period"; `perSecond <= 0` (or `NaN`) parks at `0`.
    */
  def steppedAtRate(elapsed: FiniteDuration, perSecond: Double, steps: Int): Int =
    if steps <= 0 || !(perSecond > 0.0) then 0
    else
      val advanced = math.floor(elapsed.toNanos / 1e9 * perSecond).toLong
      math.floorMod(advanced, steps.toLong).toInt

  /** One cell's progress through an animation that reaches it late: `progress` is the whole animation's, `delay` in
    * `[0, 1]` is how far behind the leading cell this one starts, and `softness` (above zero) is how much of the run
    * each cell's own transition takes — near zero a hard front, `1` a gentle ramp most cells share.
    *
    * The answer is clamped to `[0, 1]`. A delay-zero cell starts at `progress = 0`, a delay-one cell finishes exactly
    * at `progress = 1`, so every cell has finished once the animation has, whatever the delay and softness. `NaN` reads
    * as zero for every argument, so a degenerate pattern cannot hand an effect an undefined position.
    */
  def staggered(progress: Double, delay: Double, softness: Double): Double =
    val p = unit(progress)
    val d = unit(delay)
    // a zero softness would divide by zero; the smallest positive step keeps the front hard without breaking it
    val s = if softness > MinSoftness then softness else MinSoftness
    // `p >= 1` short-circuits so float rounding in the ramp can never leave a late cell a hair short of finished
    if p >= 1.0 then 1.0 else unit((p * (1.0 + s) - d) / s)

  private val MinSoftness = 1e-9

  private def unit(value: Double): Double =
    if !(value > 0.0) then 0.0 else if value > 1.0 then 1.0 else value
