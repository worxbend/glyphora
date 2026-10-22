package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Rect, Style, Widget}

/** Blanks every cell of its area, so whatever is drawn after it sits on a clean background.
  *
  * This is the first half of the popup idiom. A dropdown, toast, autocomplete list or dialog drawn over an
  * already-composed frame would otherwise show the content underneath through its own gaps — a menu row shorter than
  * the popup is wide leaves the page text visible to the right of it. Render `Clear` into the popup's rect first, then
  * render the popup into the same rect.
  *
  * `style` is what the blanks carry: [[io.worxbend.tui.core.Style.Default Style.Default]] erases to the terminal's own
  * background, while a style with a background colour paints an opaque panel. Only the area given to `render` is
  * touched, and only the part of it that falls inside the buffer; everything else is left exactly as it was.
  *
  * The widget owns no state and claims no size — it fills whatever rect it is handed, which is why it composes with any
  * container.
  */
final case class Clear(style: Style = Style.Default) extends Widget:

  def render(area: Rect, buffer: Buffer): Unit =
    buffer.fill(area, Cell(" ", style))
