package io.worxbend.tui.widgets

/** The scale a [[Slider]] moves along: its bounds and how far one keyboard press travels.
  *
  * One value rather than three loose `Int` parameters, because two of the three combinations a caller could previously
  * write did not describe anything the control can do. `step = 0` was the worse one: the DSL's slider consumes
  * Left/Right by construction, so a zero step swallowed both arrow keys, stopped them bubbling to the application's own
  * bindings, and changed nothing on screen. A negative step silently reversed the arrows. Bounds handed over the wrong
  * way round put the minimum above the maximum, which the render arithmetic then had to guess at.
  *
  * The constructor is private so those states stay unreachable: build one with [[SliderRange.of]], which orders the
  * bounds and rejects a step below 1 the way [[ColorRamp]] and [[SpinnerPreset]] reject values they cannot render. That
  * also rules out reopening the hole with `.copy(step = 0)`.
  *
  * @param min
  *   the lowest value the slider can hold; never above `max`
  * @param max
  *   the highest value the slider can hold; never below `min`
  * @param step
  *   how many units Left/Right move the value, always at least 1
  */
final case class SliderRange private (min: Int, max: Int, step: Int):

  // A valid pair of Int bounds can span more than Int.MaxValue; widen before every operation on that span.
  private val span: Long = max.toLong - min.toLong

  /** Clamps a widened value before narrowing it, including the result of a keyboard step. */
  private[tui] def clamp(value: Long): Int = math.max(min.toLong, math.min(value, max.toLong)).toInt

  /** The nearest track position, bounded by the last usable column even at the full signed-Int scale. */
  private[widgets] def positionOf(value: Int, lastPosition: Int): Int =
    if span == 0 || lastPosition <= 0 then 0
    else
      val fraction = (clamp(value.toLong).toLong - min.toLong).toDouble / span.toDouble
      math.max(0L, math.min(lastPosition.toLong, math.round(fraction * lastPosition))).toInt

  /** Maps a pointer offset back to a value, clamping positions outside the track before interpolation. */
  private[tui] def valueAt(position: Long, lastPosition: Int): Int =
    if lastPosition <= 0 then min
    else
      val fraction = math.max(0L, math.min(position, lastPosition.toLong)).toDouble / lastPosition.toDouble
      clamp(min.toLong + math.round(fraction * span.toDouble))

object SliderRange:

  /** A range over `min`..`max` moving `step` units per key press.
    *
    * The bounds are ordered rather than rejected — `of(100, 0)` is the same range as `of(0, 100)`, and a caller
    * computing bounds from data has no way to know which end came out larger. A step below 1 *is* rejected: there is no
    * sensible reading of "move by zero" for a control whose whole purpose is to move.
    *
    * @throws IllegalArgumentException
    *   if `step` is less than 1
    */
  def of(min: Int, max: Int, step: Int = 5): SliderRange =
    require(step >= 1, s"a slider step must be at least 1, got $step")
    SliderRange(math.min(min, max), math.max(min, max), step)

  /** `0` to `100` in steps of 5 — the default, and the range a percentage slider wants. */
  val Percent: SliderRange = of(0, 100, 5)
