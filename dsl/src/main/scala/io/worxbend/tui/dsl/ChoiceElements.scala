package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent, MouseEvent, MouseEventKind, Rect, Text, Widget}
import io.worxbend.tui.widgets as w

/** A labelled checkbox. Space/Enter (or a click) flips it while focused.
  *
  * The node holds the *value* the view read, not the signal behind it: the factory does the tracked read, so the view's
  * scope subscribes and any writer at all — a background load, another control — repaints the checkbox. `onChange`
  * carries the new value back to whoever owns the state.
  */
final case class CheckboxElement(
    label: String,
    checked: Boolean,
    onChange: Boolean => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = CheckboxElement
  def widget: Widget                                               = w.Checkbox(label, checked, focusStyled(props))
  private[dsl] def withProps(props: ElementProps): CheckboxElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                       = SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(toggleOnActivate(() => onChange(!checked)))
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(clickActivates(() => onChange(!checked)))

/** A labelled on/off switch — a [[CheckboxElement]] in switch clothing, with the same Space/Enter/click activation. */
final case class ToggleElement(
    label: String,
    on: Boolean,
    onChange: Boolean => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = ToggleElement
  def widget: Widget                                                         = w.Toggle(label, on, focusStyled(props))
  private[dsl] def withProps(props: ElementProps): ToggleElement             = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(toggleOnActivate(() => onChange(!on)))
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(clickActivates(() => onChange(!on)))

/** A one-row option cycler. Left/Right step through `options` while focused (wrapping at both ends) and a click
  * advances one; the selection is an index into `options`, so it survives nothing but a stable option list.
  */
final case class SelectElement(
    options: Seq[String],
    selected: Int,
    onSelect: Int => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = SelectElement
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(clickActivates(() => if options.nonEmpty then onSelect((selected + 1) % options.size)))
  def widget: Widget = w.Select(options, selected, focusStyled(props))
  private[dsl] def withProps(props: ElementProps): SelectElement             = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(stepsWrapping(options.size, selected, onSelect))

/** A collapsed option chooser: one row showing the option in force, and, while open, the whole option list beneath it
  * as a bordered popup.
  *
  * The difference from [[SelectElement]] is what happens with a long list. `select` steps one option per keystroke and
  * never shows more than the current one, so choosing the thirtieth of forty options takes thirty keystrokes and the
  * user cannot see what they are choosing between. A dropdown shows the list.
  *
  * Keys while focused. Closed: Enter, Space or Down opens the list. Open: Up/Down move the highlight, Enter commits it
  * through `onSelect`, Escape closes without changing anything. Note that an open dropdown *consumes* Escape — an app
  * that binds Escape globally will not see it while a list is showing, which is the same bargain any modal makes and
  * the reason the highlight is not the committed value.
  *
  * A click on the closed row opens the list; a click on an option commits it; a click on the row while open closes it
  * again. The wheel moves the highlight.
  *
  * The popup is drawn inside the node's own area, not floated over the screen, so while it is open the node claims
  * `1 + popup` rows and the layout around it moves down. That is what makes it work inside any panel or column today,
  * with no overlay machinery; the visible cost is that a dropdown near the bottom of a short area gets a clipped list
  * rather than one that opens upwards.
  */
final case class DropdownElement(
    options: Seq[String],
    selected: Int,
    state: w.DropdownState,
    onSelect: Int => Unit,
    maxVisibleRows: Int = 8,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = DropdownElement

  def widget: Widget =
    val dropdown = w.Dropdown(options, selected, maxVisibleRows, focusStyled(props), props.focusStyle)
    (area, buffer) => dropdown.render(area, buffer, state)

  private[dsl] def withProps(props: ElementProps): DropdownElement = copy(props = props)

  /** One row when closed; the row plus the popup when open. The claim changes with the state on purpose — that is how
    * the container above makes room for the list on the frame it opens.
    */
  private[dsl] override def claim: SizeClaim =
    if state.open then SizeClaim.rows(w.Dropdown(options, selected, maxVisibleRows).openHeight) else SizeClaim.OneRow

  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     = Some(handleKey)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] = Some(handleMouse)

  private def handleKey(event: KeyEvent): Boolean =
    if !state.open then openingKey(event)
    else
      event match
        case KeyEvent(KeyCode.Enter | KeyCode.Char(' '), _) =>
          state.highlighted.filter(options.indices.contains).foreach(onSelect)
          state.close()
          true
        case KeyEvent(KeyCode.Escape, _)                    =>
          state.close()
          true
        case KeyEvent(KeyCode.Down, _)                      =>
          state.selectNext(options)
          true
        case KeyEvent(KeyCode.Up, _)                        =>
          state.selectPrevious(options)
          true
        case _                                              => false

  /** Anything else is left alone while closed, so Tab still reaches focus traversal and an app binding still sees its
    * own keys — a closed dropdown is a label, and should behave like one.
    */
  private def openingKey(event: KeyEvent): Boolean =
    event match
      case KeyEvent(KeyCode.Enter | KeyCode.Char(' ') | KeyCode.Down, _) if options.nonEmpty =>
        state.openAt(selected)
        true
      case _                                                                                 => false

  private def handleMouse(event: MouseEvent, area: Rect): Boolean =
    event.kind match
      case MouseEventKind.ScrollUp if state.open   =>
        state.selectPrevious(options)
        true
      case MouseEventKind.ScrollDown if state.open =>
        state.selectNext(options)
        true
      case MouseEventKind.Down                     => handlePress(event.position.y - area.y)
      case _                                       => false

  /** Rows of an open dropdown above the first option: the closed row and the popup's top border. */
  private val PopupHeaderRows = 2

  /** `offsetY` is how many rows below the node's own top the press landed. Row 0 is the closed row; the popup starts at
    * row 1, and its first option is at row 2 because row 1 is the popup's top border. Which option a painted row holds
    * is the state's own arithmetic — `highlightAt` adds the popup's scroll offset.
    */
  private def handlePress(offsetY: Int): Boolean =
    if offsetY == 0 then
      if state.open then state.close() else if options.nonEmpty then state.openAt(selected)
      true
    else if state.open then
      state.highlightAt(offsetY - PopupHeaderRows, options.size).foreach { row =>
        onSelect(row)
        state.close()
      }
      true
    else false

/** Mutually exclusive options: Up/Down move the selection while focused. */
final case class RadioGroupElement(
    options: Seq[String],
    selected: Int,
    onSelect: Int => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = RadioGroupElement
  def widget: Widget = w.RadioGroup(options, selected, props.style, focusStyled(props).bold)
  private[dsl] def withProps(props: ElementProps): RadioGroupElement     = copy(props = props)
  private[dsl] override def claim: SizeClaim                             = SizeClaim.rows(math.max(1, options.size))
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler] =
    if options.isEmpty then None
    else
      Some(
        keys {
          case KeyEvent(KeyCode.Down, _) => onSelect(math.min(selected + 1, options.size - 1))
          case KeyEvent(KeyCode.Up, _)   => onSelect(math.max(selected - 1, 0))
        }
      )

/** A value slider: Left/Right adjust by `range.step`, Home/End jump to the bounds, while focused.
  *
  * The bounds and the step arrive together as a [[io.worxbend.tui.widgets.SliderRange]]. That is what keeps the key
  * handler honest: it consumes Left/Right by construction, so a zero step would swallow both arrows and do nothing with
  * them, and a negative one would reverse them.
  */
final case class SliderElement(
    value: Int,
    onChange: Int => Unit,
    range: w.SliderRange = w.SliderRange.Percent,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = SliderElement

  /** Columns the slider's chrome takes off the draggable track: the two end caps and the knob itself. An area no wider
    * than this has no room for a movable knob, so a drag there changes nothing.
    */
  private val SliderChromeWidth = 3

  /** How far in from the area's left edge the draggable track starts — one column, past the `├` end cap. */
  private val SliderTrackInset = 1

  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some { (event, area) =>
      event.kind match
        case MouseEventKind.Down | MouseEventKind.Drag =>
          if area.width > SliderChromeWidth then
            val position = event.position.x.toLong - area.x.toLong - SliderTrackInset
            onChange(range.valueAt(position, area.width - SliderChromeWidth))
          true
        case _                                         => false
    }
  def widget: Widget = w.Slider(value, range, props.style, focusStyled(props).bold)
  private[dsl] def withProps(props: ElementProps): SliderElement             = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(
      keys {
        case KeyEvent(KeyCode.Left, _)  => onChange(range.clamp(value.toLong - range.step.toLong))
        case KeyEvent(KeyCode.Right, _) => onChange(range.clamp(value.toLong + range.step.toLong))
        case KeyEvent(KeyCode.Home, _)  => onChange(range.min)
        case KeyEvent(KeyCode.End, _)   => onChange(range.max)
      }
    )

/** A page indicator: Left/Right change the page while focused. */
final case class PaginatorElement(
    current: Int,
    total: Int,
    onChange: Int => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = PaginatorElement
  def widget: Widget = w.Paginator(current, total, props.style, focusStyled(props).bold)
  private[dsl] def withProps(props: ElementProps): PaginatorElement      = copy(props = props)
  private[dsl] override def claim: SizeClaim                             = SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler] =
    if total == 0 then None
    else
      Some(
        keys {
          case KeyEvent(KeyCode.Left, _)  => onChange(math.max(0, current - 1))
          case KeyEvent(KeyCode.Right, _) => onChange(math.min(total - 1, current + 1))
        }
      )

/** A pressable button: Enter or Space triggers `action` while focused. */
final case class ButtonElement(
    label: String,
    action: () => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = ButtonElement
  def widget: Widget                                                         = w.Button(label, focusStyled(props))
  private[dsl] def withProps(props: ElementProps): ButtonElement             = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     = Some(toggleOnActivate(action))
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] = Some(clickActivates(action))

/** A dialog that owns its keys: Left/Right (and Tab) move between the buttons, Space or Enter presses the selected one,
  * Esc cancels.
  *
  * [[Element.dialog]] draws the same picture and answers no keys at all — its own documentation calls it "a picture of
  * a dialog, not a controller". That left every application writing the same three things by hand for a "really quit?":
  * a selected-index signal, the Left/Right/Enter/Esc wiring, and the modal [[Screen]] to push it on. This node is the
  * controller half; [[Screen.confirm]] is the whole thing, selection state included.
  *
  * Selection is caller-owned, like every other control in this package: the node holds the index the view read and an
  * `onSelect` to carry a new one back. `onPress` is handed the index of the button that was pressed — button 0 is the
  * confirming one by convention, because that is the order the labels are given in.
  */
final case class ConfirmDialogElement(
    title: String,
    message: String,
    buttons: Seq[String],
    selected: Int,
    onSelect: Int => Unit,
    onPress: Int => Unit,
    onCancel: () => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = ConfirmDialogElement

  def widget: Widget =
    w.Dialog(title, Text.raw(message), buttons, selected, props.style, props.focusStyle)

  private[dsl] def withProps(props: ElementProps): ConfirmDialogElement = copy(props = props)

  /** Esc and Tab first, then Space/Enter to press, then Left/Right to move.
    *
    * The order is the layering rule the rest of this file follows: this node's own keys, then the shared vocabulary it
    * falls through to. Tab is bound here rather than left to the app's focus traversal because a modal dialog is
    * normally the only focusable on screen, where Tab would otherwise do nothing at all.
    */
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler] =
    Some(
      keys {
        case KeyEvent(KeyCode.Escape, _) => onCancel()
        case KeyEvent(KeyCode.Tab, _)    => onSelect(nextIndex)
      }
        .orElse(toggleOnActivate(() => onPress(selected)))
        .orElse(stepsWrapping(buttons.size, selected, onSelect))
    )

  /** A click presses the selected button rather than the button under the pointer: the widget centres its labels and
    * reports no per-button geometry, so there is nothing to hit-test against. Keyboard selection then a click is the
    * gesture this supports; clicking a specific button needs geometry the widget does not publish.
    */
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(clickActivates(() => onPress(selected)))

  private def nextIndex: Int = if buttons.isEmpty then 0 else (selected + 1) % buttons.size
