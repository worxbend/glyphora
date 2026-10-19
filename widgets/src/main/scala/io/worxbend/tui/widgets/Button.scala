package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Buffer, CharWidth, Measured, Rect, Style, Widget}

/** A pressable control rendered as `[ label ]`, centered in its area. Stateless — press handling lives with the caller
  * (the DSL element activates on Enter/Space while focused). Measures one row and the bracketed label's display width,
  * which is what a toolbar or a `flow` lays it out by.
  */
final case class Button(
    label: String,
    style: Style = Style.Default,
) extends Widget
    with Measured:

  override def widthAt(height: Int): Option[Int] =
    val _ = height
    Some(CharWidth.of(s"[ $label ]"))

  override def heightAt(width: Int): Option[Int] =
    val _ = width
    Some(1)

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      val text   = s"[ $label ]"
      val fitted = CharWidth.substringByWidth(text, area.width)
      val startX = Alignment.Center.originAt(area.x, area.width, CharWidth.of(fitted))
      buffer.setString(startX, area.y + area.height / 2, fitted, style)
