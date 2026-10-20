package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Rect, StatefulWidget, Style, VersionedState, Widget}

/** Caller-owned [[Accordion]] state: which section is open, if any. The single-open policy lives here, so the widget
  * and the DSL node built on `collapsible` cannot disagree about it: opening a section closes whichever was open, and
  * toggling the open one closes it.
  *
  * Render-thread-only and `Versioned`: every mutation raises [[version]], which is how a running app learns it owes a
  * frame.
  */
final class AccordionState(initialOpen: Option[Int] = None) extends VersionedState:

  private var openValue: Option[Int] = initialOpen

  /** The open section, or `None` when every section is folded. */
  def open: Option[Int] = openValue

  def open_=(value: Option[Int]): Unit =
    openValue = value
    touch()

  /** Opens section `index`, closing any other, or closes it when it is the open one. */
  def toggle(index: Int): Unit =
    open = if openValue.contains(index) then None else Some(index)

object AccordionState:

  /** An accordion with `open` unfolded, or none. */
  def apply(open: Option[Int] = None): AccordionState = new AccordionState(open)

object Accordion:

  /** A titled widget body, unfolded when this section is selected in [[AccordionState]]. */
  final case class Section(title: String, body: Widget)

/** A stack of titled sections of which at most one is unfolded: every header takes one row (`▸ title` folded, `▾ title`
  * open) and the open section's body takes every row the headers leave, between its own header and the next. With
  * nothing open the headers sit at the top. An area shorter than the headers keeps as many as fit, from the top.
  *
  * Titles are clipped by display width. The open header draws in `openStyle` over `style`. Stateless apart from the
  * caller-owned [[AccordionState]]; render-thread use only.
  */
final case class Accordion(
    sections: Seq[Accordion.Section],
    style: Style = Style.Default,
    openStyle: Style = Style.Default.bold,
    collapsedSymbol: String = "▸ ",
    expandedSymbol: String = "▾ ",
) extends StatefulWidget[AccordionState]:

  def render(area: Rect, buffer: Buffer, state: AccordionState): Unit =
    if !area.isEmpty then
      val open     = state.open.filter(sections.indices.contains)
      val bodyRows = math.max(0, area.height - sections.size)
      var y        = area.y
      sections.zipWithIndex.foreach { case (Accordion.Section(title, body), index) =>
        if y < area.bottom then
          val isOpen = open.contains(index)
          val marker = if isOpen then expandedSymbol else collapsedSymbol
          val header = if isOpen then style.patch(openStyle) else style
          buffer.setString(area.x, y, CharWidth.substringByWidth(marker + title, area.width), header)
          y += 1
          if isOpen && bodyRows > 0 then
            body.render(Rect(area.x, y, area.width, math.min(bodyRows, area.bottom - y)), buffer)
            y += bodyRows
      }
