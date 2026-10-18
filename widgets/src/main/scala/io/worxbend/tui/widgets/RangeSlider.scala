package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Rect, StatefulWidget, Style}

/** Which thumb of a [[RangeSlider]] the keyboard moves. */
enum RangeThumb:
  case Low, High

/** Caller-owned state of a [[RangeSlider]]: the two values, the minimum distance between them, and which thumb has the
  * keyboard.
  *
  * The invariant `range.min <= low`, `low + minGap <= high`, `high <= range.max` holds after every call, so the thumbs
  * can meet (with `minGap = 0`) but never cross: a thumb pushed into the other one stops there. A `minGap` larger than
  * the whole range is cut down to the range. The constructor orders and clamps what it is handed rather than rejecting
  * it, as [[SliderRange.of]] orders its bounds.
  *
  * Render-thread-only, and mutating it does not by itself schedule a frame; the DSL's `rangeSlider` node mutates it
  * from its key handler, which repaints.
  *
  * @param range
  *   the bounds and the per-press step
  * @param initialLow
  *   the lower thumb's starting value
  * @param initialHigh
  *   the upper thumb's starting value
  * @param minGap
  *   how close the two values may come; 0 lets them meet
  */
final class RangeSliderState(val range: SliderRange, initialLow: Int, initialHigh: Int, minGap: Int = 0):

  /** The gap actually enforced: never negative, never wider than the range itself. */
  val gap: Int = math.max(0, math.min(minGap.toLong, range.max.toLong - range.min.toLong).toInt)

  private var lowValue: Int  = range.clamp(math.min(initialLow, initialHigh).toLong)
  private var highValue: Int =
    range.clamp(math.max(math.max(initialLow, initialHigh).toLong, lowValue.toLong + gap))
  lowValue = range.clamp(math.min(lowValue.toLong, highValue.toLong - gap))

  /** The thumb the keyboard moves; starts on the lower one. */
  var thumb: RangeThumb = RangeThumb.Low

  def low: Int  = lowValue
  def high: Int = highValue

  /** Moves the focused thumb `steps` times `range.step`, stopping at the bound or at the other thumb. */
  def step(steps: Int): Unit = moveTo(focusedValue.toLong + steps.toLong * range.step)

  /** Moves the focused thumb as far down as it can go. */
  def toMin(): Unit = moveTo(Long.MinValue / 2)

  /** Moves the focused thumb as far up as it can go. */
  def toMax(): Unit = moveTo(Long.MaxValue / 2)

  /** Puts the focused thumb at `value`, or as near to it as the bounds and the other thumb allow. */
  def moveTo(value: Long): Unit =
    thumb match
      case RangeThumb.Low  => lowValue = range.clamp(math.min(value, highValue.toLong - gap))
      case RangeThumb.High => highValue = range.clamp(math.max(value, lowValue.toLong + gap))

  /** Hands the keyboard to the upper thumb; `false` when it already had it, so a caller can let Tab move on. */
  def focusNext(): Boolean =
    val moved = thumb == RangeThumb.Low
    thumb = RangeThumb.High
    moved

  /** Hands the keyboard to the lower thumb; `false` when it already had it. */
  def focusPrevious(): Boolean =
    val moved = thumb == RangeThumb.High
    thumb = RangeThumb.Low
    moved

  private def focusedValue: Int = if thumb == RangeThumb.Low then lowValue else highValue

/** A one-row slider with two thumbs, `├─●━━━━●─┤`: the stretch between them drawn heavy in `rangeStyle`, the thumb the
  * keyboard moves in `focusedThumbStyle`.
  *
  * The widget only draws; the DSL's `rangeSlider` node moves the focused thumb with Left/Right/Home/End and switches
  * thumbs with Tab/Shift+Tab. Needs three columns — two end caps and one track cell — and draws nothing in less. When
  * both thumbs land on one cell the focused one is drawn.
  *
  * @param style
  *   the end caps and the track outside the selection
  * @param rangeStyle
  *   the selected stretch between the thumbs
  * @param thumbStyle
  *   the thumb that does not have the keyboard
  * @param focusedThumbStyle
  *   the thumb that does
  */
final case class RangeSlider(
    style: Style = Style.Default,
    rangeStyle: Style = Style.Default,
    thumbStyle: Style = Style.Default.bold,
    focusedThumbStyle: Style = Style.Default.bold.reverse,
) extends StatefulWidget[RangeSliderState]:

  def render(area: Rect, buffer: Buffer, state: RangeSliderState): Unit =
    if area.width >= 3 && !area.isEmpty then
      val last          = area.width - 3
      val low           = area.x + 1 + state.range.positionOf(state.low, last)
      val high          = area.x + 1 + state.range.positionOf(state.high, last)
      buffer.set(area.x, area.y, Cell("├", style))
      buffer.set(area.right - 1, area.y, Cell("┤", style))
      (area.x + 1 until area.right - 1).foreach { x =>
        val inside = x > low && x < high
        buffer.set(x, area.y, if inside then Cell("━", rangeStyle) else Cell("─", style))
      }
      val (back, front) =
        if state.thumb == RangeThumb.Low then ((high, thumbStyle), (low, focusedThumbStyle))
        else ((low, thumbStyle), (high, focusedThumbStyle))
      Seq(back, front).foreach((x, thumb) => buffer.set(x, area.y, Cell("●", thumb)))
