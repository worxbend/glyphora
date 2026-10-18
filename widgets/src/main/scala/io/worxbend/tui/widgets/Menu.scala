package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Measured, Rect, StatefulWidget, Style, VersionedState}

/** One entry in a [[Menu]]: either a row the user can land on, or a rule that divides two groups of them.
  *
  * A sum type rather than one case class with a `separator: Boolean`, because that flag made a labelled, shortcut-
  * carrying, enabled separator representable — four fields describing something with no label, no shortcut and no
  * enabled state. Here a `Separator` has nothing to configure, which is exactly what it is.
  */
enum MenuEntry:

  /** A row the user can land on. A disabled item renders dimmed and keyboard navigation skips it; `shortcut` is
    * right-aligned hint text (e.g. `^S`), never a live binding — the app wires the action.
    */
  case Item(label: String, shortcut: Option[String] = None, enabled: Boolean = true)

  /** A horizontal rule between groups of items; never selectable. */
  case Separator

  /** Whether keyboard/mouse navigation can land on this entry. */
  def selectable: Boolean = this match
    case Item(_, _, enabled) => enabled
    case Separator           => false

/** Caller-owned menu state: the highlighted entry and the scroll offset for menus taller than their popup. Mutable on
  * purpose (the `StatefulWidget` contract); navigation helpers skip separators and disabled entries.
  *
  * `selected` is `None` when nothing is highlighted, which is the honest state of a menu whose entries are all
  * separators or all disabled — [[Menu.render]] normalizes to it, and the widget then paints no highlight at all. Owned
  * by whoever constructs it and read by the render thread, like every other widget state in this module.
  *
  * Render-thread-only. Every mutation raises `version` (see [[io.worxbend.tui.core.Versioned]]), so a DSL node that
  * paints this state notices a write made outside the event path — a background result applied on the render thread —
  * and schedules the frame that shows it, with no `Signal` write and no `requestRedraw()`.
  */
final class MenuState private (initialSelected: Option[Int], initialOffset: Int) extends VersionedState:

  private var selectedValue: Option[Int] = initialSelected
  private var offsetValue: Int           = initialOffset

  /** The highlighted entry, or `None`. */
  def selected: Option[Int] = selectedValue

  def selected_=(value: Option[Int]): Unit =
    selectedValue = value
    touch()

  /** The first entry on screen. */
  def offset: Int = offsetValue

  def offset_=(value: Int): Unit =
    offsetValue = value
    touch()

  /** Moves the highlight to the next selectable entry, wrapping past the last one; a no-op when nothing is selectable.
    *
    * A menu wraps where [[ListView]] and the other list-shaped widgets clamp — see [[Selection]] for why the two rules
    * differ.
    */
  def selectNext(items: Seq[MenuEntry]): Unit =
    Selection
      .nextSelectable(selected, items.size, index => items(index).selectable)
      .foreach(index => selected = Some(index))

  /** Moves the highlight to the previous selectable entry, wrapping past the first one — the mirror of [[selectNext]],
    * including its wrapping rule.
    */
  def selectPrevious(items: Seq[MenuEntry]): Unit =
    Selection
      .previousSelectable(selected, items.size, index => items(index).selectable)
      .foreach(index => selected = Some(index))

  /** Snaps the highlight onto the first selectable entry when it sits on a non-selectable one, and clears it entirely
    * when the menu has nothing selectable at all.
    */
  private[widgets] def normalize(items: Seq[MenuEntry]): Unit =
    if !selected.flatMap(items.lift).exists(_.selectable) then
      selected = Some(items.indexWhere(_.selectable)).filter(_ >= 0)

object MenuState:

  /** A menu state with `selected` highlighted and scrolled to `offset` — see [[ListState.apply]] for why this is the
    * companion's `apply` rather than the constructor.
    */
  def apply(selected: Option[Int] = None, offset: Int = 0): MenuState = new MenuState(selected, offset)

/** A vertical menu / dropdown / context menu rendered as a bordered popup.
  *
  * Labels sit on the left, `shortcut` hints right-aligned; the highlighted row draws with `highlightStyle`, disabled
  * rows dim, separators become a full-width rule. Backend-agnostic and render-to-`Buffer` tested; the DSL wrapper adds
  * focus, key, and mouse handling.
  */
final case class Menu(
    items: Seq[MenuEntry],
    style: Style = Style.Default,
    highlightStyle: Style = Style.Default.reverse,
    disabledStyle: Style = Style.Default.dim,
    borderType: BorderType = BorderType.Rounded,
) extends StatefulWidget[MenuState]
    with Measured:

  /** Natural width: the widest `label  shortcut` plus borders and padding. Independent of the rows given. */
  override def widthAt(height: Int): Option[Int] =
    val _       = height
    val content = items
      .map {
        case MenuEntry.Item(label, shortcut, _) =>
          CharWidth.of(label) + shortcut.map(hint => CharWidth.of(hint) + 2).getOrElse(0)
        case MenuEntry.Separator                => 0
      }
      .maxOption
      .getOrElse(0)
    Some(content + PopupChrome.ExtraWidth)

  /** Natural height: one row per entry plus the top and bottom borders. Independent of the columns given. */
  override def heightAt(width: Int): Option[Int] =
    val _ = width
    Some(items.size + PopupChrome.ExtraHeight)

  def render(area: Rect, buffer: Buffer, state: MenuState): Unit =
    if !area.isEmpty then
      state.normalize(items)
      val block = Block(borderType = borderType, borderStyle = style)
      block.render(area, buffer)
      val inner = block.inner(area)
      if !inner.isEmpty && items.nonEmpty then
        val visible = math.min(inner.height, items.size)
        state.offset = ScrollWindow.offsetFor(state.offset, state.selected, items.size, inner.height)
        var row     = 0
        while row < visible do
          val index = state.offset + row
          if index < items.size then renderItem(buffer, inner, row, index, state.selected)
          row += 1

  private def renderItem(buffer: Buffer, inner: Rect, row: Int, index: Int, selected: Option[Int]): Unit =
    val y = inner.y + row
    items(index) match
      case MenuEntry.Separator                      =>
        val rule = "─".repeat(math.max(0, inner.width))
        buffer.setString(inner.x, y, rule, style)
      case MenuEntry.Item(label, shortcut, enabled) =>
        val rowStyle =
          if selected.contains(index) then style.patch(highlightStyle)
          else if !enabled then disabledStyle
          else style
        // paint the full row so the highlight spans the popup width
        buffer.setString(inner.x, y, " ".repeat(inner.width), rowStyle)
        shortcut match
          case Some(hint) =>
            val padded = hint + " "
            val hintW  = CharWidth.of(padded)
            // the hint shares the row: it claims its own columns plus a two-column gap, and the label is fitted to
            // what remains rather than written full-width and overwritten at the tail. A hint that would leave the
            // label no column at all is dropped instead — a shortcut nobody can read is worth less than the label.
            if hintW + 2 < inner.width then
              val fitted = CharWidth.substringByWidth(" " + label, inner.width - hintW - 2)
              buffer.setString(inner.x, y, fitted, rowStyle)
              buffer.setString(inner.right - hintW, y, padded, rowStyle)
            else buffer.setString(inner.x, y, CharWidth.substringByWidth(" " + label, inner.width), rowStyle)
          case None       =>
            buffer.setString(inner.x, y, CharWidth.substringByWidth(" " + label, inner.width), rowStyle)
