package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Buffer, CharWidth, Rect, Style, Widget}

/** A sized, labelled box for prototyping a layout before the real content exists: a dashed frame, the `label` centred
  * inside it, and under the label the size the box was actually given — `40×12` — so a reader tuning constraints sees
  * what the solver handed out without reaching for a debugger.
  *
  * The size is the whole area's, frame included, because that is the number a `Constraint` controls. An area under
  * three cells in either direction has no room for a frame; the label and the size are then drawn unframed from the
  * top-left corner, clipped like everything else. Every width is measured through `CharWidth`, so a CJK or emoji label
  * centres on its display width.
  *
  * Stateless and immutable; safe to share between frames and threads.
  *
  * @param label
  *   what the box stands in for — `"Sidebar"`, `"Chart goes here"`
  * @param style
  *   the style of the frame and of the size line, painted over the whole area
  * @param labelStyle
  *   layered over `style` for the label alone
  * @param borderType
  *   the frame's glyphs; dashed by default, so a placeholder reads as provisional next to the finished panels beside it
  */
final case class Placeholder(
    label: String,
    style: Style = Style.Default,
    labelStyle: Style = Style.Default,
    borderType: BorderType = BorderType.LightDoubleDashed,
) extends Widget:

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      val size = s"${area.width}×${area.height}"
      if area.width >= 3 && area.height >= 3 then
        val block = Block(style = style, borderStyle = style, borderType = borderType)
        block.render(area, buffer)
        val inner = block.inner(area)
        val rows  = Seq(label -> style.patch(labelStyle), size -> style).take(inner.height)
        val top   = inner.y + (inner.height - rows.size) / 2
        rows.zipWithIndex.foreach { case ((content, rowStyle), index) =>
          val fitted = CharWidth.substringByWidth(content, inner.width)
          val x      = Alignment.Center.originAt(inner.x, inner.width, CharWidth.of(fitted))
          buffer.setString(x, top + index, fitted, rowStyle)
        }
      else
        buffer.setStyle(area, style)
        Seq(label -> style.patch(labelStyle), size -> style).take(area.height).zipWithIndex.foreach {
          case ((content, rowStyle), index) =>
            buffer.setString(area.x, area.y + index, CharWidth.substringByWidth(content, area.width), rowStyle)
        }
