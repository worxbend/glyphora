package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Buffer, CharWidth, Line, Rect, Style, Text, Widget}

/** A modal-style dialog drawn over existing content: clears a centered box, borders it, renders the message and a row
  * of buttons with one highlighted.
  *
  * The widget owns no state and manages no stack of its own — it paints over whatever was drawn into the buffer before
  * it, so rendering it last in the view is what makes it modal-looking. The DSL's `Screen` stack (`TuiApp.pushScreen`)
  * builds on exactly that.
  */
final case class Dialog(
    title: String,
    message: Text,
    buttons: Seq[String] = Seq("OK"),
    selectedButton: Int = 0,
    style: Style = Style.Default,
    highlightStyle: Style = Style.Default.reverse,
    borderType: BorderType = BorderType.Double,
) extends Widget:

  def render(area: Rect, buffer: Buffer): Unit =
    // the button row needs its own width plus the two border columns; a box sized from the message alone dropped the
    // last button whenever the labels outgrew the message — the usual case once they are translated
    val wanted = math.max(math.max(message.width + PopupChrome.ExtraWidth, buttonsWidth + 2), 20)
    val box    = area.centered(math.min(area.width, wanted), message.height + 4)
    if box.width >= 4 && box.height >= 4 then
      Clear(style).render(box, buffer)
      Block(Seq(BlockTitle.top(Line.styled(title, style))), borderStyle = style, borderType = borderType)
        .render(box, buffer)
      val inner = box.inset(1)
      Paragraph(message, alignment = Alignment.Center, style = style).render(
        Rect(inner.x, inner.y, inner.width, inner.height - 1),
        buffer,
      )
      renderButtons(inner, buffer)

  private def labels: Seq[String] = buttons.map(label => s"[ $label ]")

  private def buttonsWidth: Int = labels.map(CharWidth.of).sum + math.max(0, labels.size - 1)

  private def renderButtons(inner: Rect, buffer: Buffer): Unit =
    val labels     = this.labels
    val totalWidth = buttonsWidth
    var x          = Alignment.Center.originAt(inner.x, inner.width, totalWidth)
    val y          = inner.bottom - 1
    labels.zipWithIndex.foreach { (label, index) =>
      val buttonStyle = if index == selectedButton then style.patch(highlightStyle) else style
      val width       = CharWidth.of(label)
      // more buttons than the box is wide: drop the ones that do not fit rather than paint over the app behind it
      if x + width <= inner.right then buffer.setString(x, y, label, buttonStyle)
      x += width + 1
    }
