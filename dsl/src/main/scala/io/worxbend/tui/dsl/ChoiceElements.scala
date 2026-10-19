package io.worxbend.tui.dsl

import io.worxbend.tui.core.{
  Buffer,
  Color,
  KeyCode,
  KeyEvent,
  KeyModifiers,
  MouseEvent,
  MouseEventKind,
  Rect,
  Text,
  Versioned,
  Widget,
}
import io.worxbend.tui.terminal.PointerShape
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
  override def debugName: String      = "checkbox"
  override def accessibleName: String = s"checkbox $label, ${if checked then "checked" else "unchecked"}"
  def widget: Widget                  = w.Checkbox(label, checked, focusStyled(props))
  private[dsl] def withProps(props: ElementProps): CheckboxElement           = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.OneRow
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
  override def debugName: String                                 = "toggle"
  override def accessibleName: String                            = s"toggle $label, ${if on then "on" else "off"}"
  def widget: Widget                                             = w.Toggle(label, on, focusStyled(props))
  private[dsl] def withProps(props: ElementProps): ToggleElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                     = SizeClaim.OneRow
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
  override def debugName: String                                             = "select"
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(clickActivates(() => if options.nonEmpty then onSelect((selected + 1) % options.size)))
  def widget: Widget = w.Select(options, selected, focusStyled(props))
  private[dsl] def withProps(props: ElementProps): SelectElement             = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(stepsWrapping(options.size, selected, onSelect))

/** A collapsed option chooser: one row showing the option in force, and, while open, the whole option list floating
  * beneath it as a bordered popup.
  *
  * The difference from [[SelectElement]] is what happens with a long list. `select` steps one option per keystroke and
  * never shows more than the current one, so choosing the thirtieth of forty options takes thirty keystrokes and the
  * user cannot see what they are choosing between. A dropdown shows the list.
  *
  * Keys while focused. Closed: Enter, Space or Down opens the list. Open: Up/Down move the highlight, Enter commits it
  * through `onSelect`, Escape closes without changing anything, and Tab closes it on its way to the next control. Note
  * that an open dropdown *consumes* Escape — an app that binds Escape globally will not see it while a list is showing,
  * which is the same bargain any modal makes and the reason the highlight is not the committed value.
  *
  * A click on the closed row opens the list; a click on an option commits it; a click anywhere else — the row itself
  * included — closes the list without committing, and is spent on that: the control under the pointer does not also
  * receive it. The wheel over the list moves the highlight.
  *
  * The list is drawn through the frame root, the way [[PortalElement]] draws, so the node claims one row whether the
  * list is open or not and nothing around it moves when it opens. The popup sits under the row when the frame has room
  * for it there and above the row when it does not, and is clipped to the frame — never to the panel or pane the row
  * sits in. Rendered outside a host, with no frame root to float through, the list paints in place under the row,
  * clipped only by the buffer.
  *
  * `floating` is the framework's slot: the focus pass hands back the decorated scrim and list nodes through it, so the
  * mouse router can find the list where it was painted. Application code never sets it.
  */
final case class DropdownElement(
    options: Seq[String],
    selected: Int,
    state: w.DropdownState,
    onSelect: Int => Unit,
    maxVisibleRows: Int = 8,
    props: ElementProps = ElementProps(focusable = true),
    floating: Seq[Element] = Seq.empty,
) extends Element:
  type Self = DropdownElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned] = Seq(state)
  override def debugName: String                          = "dropdown"

  private def dropdownWidget: w.Dropdown =
    w.Dropdown(options, selected, maxVisibleRows, focusStyled(props), props.focusStyle)

  /** While the list shows: the click-outside scrim and then the list, in paint order, so the list is painted last and
    * wins the pointer over the scrim. Both are children for event routing only — the node paints them itself, through
    * the portal queue, because where they go is only known at render time.
    */
  override def children: Seq[Element] =
    if floating.nonEmpty then floating
    else if state.open && options.nonEmpty then
      Seq(DropdownScrimElement(state), DropdownPopupElement(dropdownWidget, options, state, onSelect))
    else Seq.empty

  def widget: Widget =
    val dropdown = dropdownWidget
    (area, buffer) =>
      dropdown.render(Rect(area.x, area.y, area.width, 1), buffer, state)
      children match
        case Seq(scrim, popup) if !props.inert => floatList(area, buffer, dropdown.popupHeight, scrim, popup)
        case _                                 => ()

  /** Places the list under the row, or above it when the frame has less room below than the list needs — whichever side
    * has more room when neither fits, clipped to that room — and hands scrim and list to the frame root.
    *
    * The arithmetic runs in screen space, because room is a property of the frame, and is converted back to this node's
    * own coordinates on the way out: [[PortalQueue.offer]] translates a *local* rectangle, and translation is a pure
    * offset, so the difference between the node's screen rectangle and its local one is the shift to undo.
    */
  private def floatList(area: Rect, buffer: Buffer, popupRows: Int, scrim: Element, popup: Element): Unit =
    val screen     = FrameCoordinates.translate(area)
    // no frame root: there is nothing to run out of room against, so the list opens below as it always did
    val frame      = FrameCoordinates.frameBoundary.getOrElse(Rect(screen.x, screen.y, area.width, 1 + popupRows))
    val roomBelow  = frame.bottom - (screen.y + 1)
    val roomAbove  = screen.y - frame.y
    val opensBelow = roomBelow >= popupRows || roomBelow >= roomAbove
    val height     = math.min(popupRows, if opensBelow then roomBelow else roomAbove)
    if height > 0 then
      val top    = if opensBelow then screen.y + 1 else screen.y - height
      val shiftX = screen.x - area.x
      val shiftY = screen.y - area.y
      val target = Rect(area.x, top - shiftY, area.width, height)
      if PortalQueue.isCollecting then
        PortalQueue.offer(frame.offset(-shiftX, -shiftY), scrim)
        PortalQueue.offer(target, popup)
      else popup.widget.render(target, buffer)

  private[dsl] def withProps(props: ElementProps): DropdownElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): DropdownElement = copy(floating = children)

  /** One row, open or closed: the list floats over the frame, so the container above never makes room for it. */
  private[dsl] override def claim: SizeClaim = SizeClaim.OneRow

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
        // closed, and deliberately *not* consumed: focus traversal still happens, so the list never stays floating
        // over a control that has the keyboard
        case KeyEvent(KeyCode.Tab, _)                       =>
          state.close()
          false
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

  /** The row's own mouse behaviour. A press on the row opens the list; while the list shows, the scrim is what the
    * router finds under the pointer everywhere but on the list, so a press here only ever means "open".
    */
  private def handleMouse(event: MouseEvent, area: Rect): Boolean =
    val _ = area
    event.kind match
      case MouseEventKind.ScrollUp if state.open   =>
        state.selectPrevious(options)
        true
      case MouseEventKind.ScrollDown if state.open =>
        state.selectNext(options)
        true
      case MouseEventKind.Down                     =>
        if state.open then state.close() else if options.nonEmpty then state.openAt(selected)
        true
      case _                                       => false

/** The floating option list of an open [[DropdownElement]]: the popup alone, drawn through the frame root at the
  * rectangle the dropdown chose for it, with the mouse behaviour that used to belong to the popup rows of the node — a
  * press on an option commits it and closes the list, the wheel moves the highlight. Not focusable: the keyboard stays
  * with the row.
  *
  * Built by the dropdown each frame it is open, never by application code; `w.Dropdown` is passed in so the list is
  * drawn with the row's own styles.
  */
private[dsl] final case class DropdownPopupElement(
    dropdown: w.Dropdown,
    options: Seq[String],
    state: w.DropdownState,
    onSelect: Int => Unit,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = DropdownPopupElement
  override def debugName: String = "dropdownPopup"
  def widget: Widget             = (area, buffer) => dropdown.renderPopup(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): DropdownPopupElement      = copy(props = props)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] = Some(handleMouse)

  /** Rows of the popup above its first option: the top border. */
  private val PopupHeaderRows = 1

  /** The popup's top and bottom border rows together — the rows of its area that hold no option. */
  private val PopupBorderRows = 2

  /** The border rows are consumed and commit nothing; a press on an option row commits the option that row shows, which
    * is the state's own arithmetic — `highlightAt` adds the list's scroll offset.
    */
  private def handleMouse(event: MouseEvent, area: Rect): Boolean =
    event.kind match
      case MouseEventKind.ScrollUp   =>
        state.selectPrevious(options)
        true
      case MouseEventKind.ScrollDown =>
        state.selectNext(options)
        true
      case MouseEventKind.Down       =>
        val row = event.position.y - area.y - PopupHeaderRows
        if row >= 0 && row < area.height - PopupBorderRows then
          state.highlightAt(row, options.size).foreach { index =>
            onSelect(index)
            state.close()
          }
        true
      case _                         => false

/** The invisible, frame-sized layer an open [[DropdownElement]] floats under its list: it paints nothing and exists so
  * that a press anywhere outside the list closes the list — and goes no further, the way a click outside a menu is
  * spent on dismissing it. Painted before the list, so the list wins the pointer on its own cells. Everything but a
  * press is declined, so the wheel still reaches whatever scrolls beneath.
  */
private[dsl] final case class DropdownScrimElement(state: w.DropdownState, props: ElementProps = ElementProps())
    extends Element:
  type Self = DropdownScrimElement
  override def debugName: String                                             = "dropdownScrim"
  def widget: Widget                                                         = (_, _) => ()
  private[dsl] def withProps(props: ElementProps): DropdownScrimElement      = copy(props = props)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] = Some { (event, _) =>
    event.kind match
      case MouseEventKind.Down =>
        state.close()
        true
      case _                   => false
  }

/** Mutually exclusive options: Up/Down move the selection while focused. */
final case class RadioGroupElement(
    options: Seq[String],
    selected: Int,
    onSelect: Int => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = RadioGroupElement
  override def debugName: String = "radioGroup"
  def widget: Widget             = w.RadioGroup(options, selected, props.style, focusStyled(props).bold)
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
  override def debugName: String = "slider"

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
  override def debugName: String = "paginator"
  def widget: Widget             = w.Paginator(current, total, props.style, focusStyled(props).bold)
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
  override def debugName: String                                             = "button"
  override def accessibleName: String                                        = s"button $label"
  private[dsl] override def componentRole: Option[ComponentRole]             = Some(ButtonRole.Default)
  def widget: Widget                                                         = w.Button(label, focusStyled(props))
  override def pointerShape: Option[PointerShape]                            =
    props.pointerShape.orElse(Some(PointerShape.Pointer))
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
  override def debugName: String = "confirmDialog"

  def widget: Widget =
    w.Dialog(title, Text.raw(message), buttons, selected, props.style, cursorCue(props))

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

/** An interactive month grid over a caller-owned [[w.DatePickerState]]. While focused, Left/Right move the cursor a day
  * and Up/Down a week — across month and year ends, so the grid follows — PageUp/PageDown move it a month, `t` jumps to
  * today, and Enter or Space chooses the cursor's date and hands it to `onSelect`. The cursor wears the focus style;
  * the chosen date, once the cursor leaves it, is underlined.
  */
final case class DatePickerElement(
    state: w.DatePickerState,
    onSelect: java.time.LocalDate => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = DatePickerElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]            = Seq(state)
  override def debugName: String                                     = "datePicker"
  def widget: Widget                                                 =
    val picker = w.DatePicker(w.CalendarOptions(style = props.style, highlightStyle = cursorCue(props)))
    (area, buffer) => picker.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): DatePickerElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                         = SizeClaim.box(DatePickerWidth, DatePickerHeight)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler] =
    Some(
      keys {
        case KeyEvent(KeyCode.Left, _)                         => state.moveDays(-1)
        case KeyEvent(KeyCode.Right, _)                        => state.moveDays(1)
        case KeyEvent(KeyCode.Up, _)                           => state.moveDays(-DaysPerWeek)
        case KeyEvent(KeyCode.Down, _)                         => state.moveDays(DaysPerWeek)
        case KeyEvent(KeyCode.PageUp, _)                       => state.moveMonths(-1)
        case KeyEvent(KeyCode.PageDown, _)                     => state.moveMonths(1)
        case KeyEvent(KeyCode.Char('t'), mods) if mods.isEmpty => state.goToToday()
        case KeyEvent(KeyCode.Enter | KeyCode.Char(' '), _)    =>
          state.select()
          onSelect(state.cursor)
      }
    )

  /** The grid a [[w.Calendar]] draws with both headers: seven two-column days with one-column gaps, and a title row, a
    * weekday row and up to six weeks.
    */
  private val DatePickerWidth  = 20
  private val DatePickerHeight = 8
  private val DaysPerWeek      = 7

/** A one-row `hh:mm:ss` field over a caller-owned [[w.TimePickerState]]. While focused, Left/Right move between the
  * hour, minute and second fields and Up/Down step the time by the focused field's unit, wrapping past midnight — one
  * minute up from `23:59` is `00:00`. The focused field wears the focus style.
  */
final case class TimePickerElement(
    state: w.TimePickerState,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = TimePickerElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                = Seq(state)
  override def debugName: String                                         = "timePicker"
  private def picker: w.TimePicker                                       = w.TimePicker(props.style, cursorCue(props))
  def widget: Widget                                                     =
    val current = picker
    (area, buffer) => current.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): TimePickerElement     = copy(props = props)
  private[dsl] override def claim: SizeClaim                             = SizeClaim.box(picker.widthFor(state), 1)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler] =
    Some(
      keys {
        case KeyEvent(KeyCode.Left, _)  => state.previousField()
        case KeyEvent(KeyCode.Right, _) => state.nextField()
        case KeyEvent(KeyCode.Up, _)    => state.increment()
        case KeyEvent(KeyCode.Down, _)  => state.decrement()
      }
    )

/** A two-thumb range slider over a caller-owned [[w.RangeSliderState]]. While focused, Left/Right move the thumb that
  * has the keyboard by `state.range.step` and Home/End send it to its limit; a thumb stops at the other one (and at the
  * state's minimum gap), so the two never cross. Tab hands the keyboard from the lower thumb to the upper one and
  * Shift+Tab back; from the upper thumb Tab is left unconsumed, so focus moves on to the next control as usual and a
  * user is never trapped inside the slider. A press or drag on the track moves whichever thumb is nearer. `onChange`
  * hears the pair after every move.
  */
final case class RangeSliderElement(
    state: w.RangeSliderState,
    onChange: (Int, Int) => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = RangeSliderElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state)
  override def debugName: String                                             = "rangeSlider"
  def widget: Widget                                                         =
    val slider = w.RangeSlider(props.style, props.style.bold, props.style.bold, focusStyled(props).bold)
    (area, buffer) => slider.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): RangeSliderElement        = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some { event =>
      val shifted = event.modifiers.hasAny(KeyModifiers.Shift)
      event.code match
        case KeyCode.Tab if shifted => state.focusPrevious()
        case KeyCode.Tab            => state.focusNext()
        case KeyCode.Left           => changed(state.step(-1))
        case KeyCode.Right          => changed(state.step(1))
        case KeyCode.Home           => changed(state.toMin())
        case KeyCode.End            => changed(state.toMax())
        case _                      => false
    }
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some { (event, area) =>
      event.kind match
        case MouseEventKind.Down | MouseEventKind.Drag if area.width > ChromeWidth =>
          val value = state.range.valueAt(event.position.x.toLong - area.x.toLong - 1, area.width - ChromeWidth)
          if event.kind == MouseEventKind.Down then
            val nearerHigh = math.abs(value.toLong - state.high) < math.abs(value.toLong - state.low)
            if nearerHigh then state.focusNext() else state.focusPrevious()
          changed(state.moveTo(value.toLong))
        case _                                                                     => false
    }

  private def changed(move: => Unit): Boolean =
    move
    onChange(state.low, state.high)
    true

  /** Columns the end caps and one thumb take off the draggable track. */
  private val ChromeWidth = 3

/** A 256-colour palette grid over a caller-owned [[w.ColorPickerState]]. While focused, the arrows move the cursor
  * swatch and Enter or Space chooses it, recording it in `state.chosen` and handing the `Color.Indexed` to `onSelect`.
  * A click on a swatch moves the cursor there and chooses it. Claims the 36×17 box the grid and its caption measure.
  */
final case class ColorPickerElement(
    state: w.ColorPickerState,
    onSelect: Color => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = ColorPickerElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state)
  override def debugName: String                                             = "colorPicker"
  private def picker: w.ColorPicker                                          =
    w.ColorPicker(style = props.style, markerStyle = Style.Default.bold.patch(cursorCue(props)))
  def widget: Widget                                                         =
    val current = picker
    (area, buffer) => current.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): ColorPickerElement        = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 =
    (picker.widthAt(0), picker.heightAt(0)) match
      case (Some(width), Some(height)) => SizeClaim.box(width, height)
      case _                           => SizeClaim.Fill
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(
      keys {
        case KeyEvent(KeyCode.Left, _)                      => state.moveLeft()
        case KeyEvent(KeyCode.Right, _)                     => state.moveRight()
        case KeyEvent(KeyCode.Up, _)                        => state.moveUp()
        case KeyEvent(KeyCode.Down, _)                      => state.moveDown()
        case KeyEvent(KeyCode.Enter | KeyCode.Char(' '), _) => onSelect(state.choose())
      }
    )
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some { (event, area) =>
      val row    = event.position.y - area.y
      val column = (event.position.x - area.x) / 2
      val hit    = w.ColorPicker.Grid.lift(row).flatMap(_.lift(column)).filter(_ => event.position.x >= area.x)
      (event.kind, hit) match
        case (MouseEventKind.Down, Some(index)) =>
          state.moveTo(index)
          onSelect(state.choose())
          true
        case _                                  => false
    }
