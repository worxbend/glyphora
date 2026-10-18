package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, KeyCode, KeyEvent, MouseEvent, MouseEventKind, Rect, Text, Widget}
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
  override def debugName: String = "dropdown"

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
