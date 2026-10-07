package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Rect, Style, Widget}

/** A one-row value slider: `├───●──────┤` proportional to `value` within `range`.
  *
  * A value outside the range is clamped rather than drawn off the track. `range.step` is not a rendering input — the
  * widget never reads it — but it travels with the bounds because the DSL's slider needs all three to handle a key
  * press, and the three only make sense together.
  */
final case class Slider(
    value: Int,
    range: SliderRange = SliderRange.Percent,
    style: Style = Style.Default,
    knobStyle: Style = Style.Default.bold,
) extends Widget:

  def render(area: Rect, buffer: Buffer): Unit =
    if area.width >= 3 && !area.isEmpty then
      val trackWidth = area.width - 2
      buffer.set(area.x, area.y, Cell("├", style))
      buffer.set(area.right - 1, area.y, Cell("┤", style))
      var x          = area.x + 1
      while x < area.right - 1 do
        buffer.set(x, area.y, Cell("─", style))
        x += 1
      val knob       = area.x + 1 + range.positionOf(value, trackWidth - 1)
      buffer.set(knob, area.y, Cell("●", knobStyle))
