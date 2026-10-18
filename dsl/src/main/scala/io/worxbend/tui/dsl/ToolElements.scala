package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent, Style, Widget}
import io.worxbend.tui.widgets as w

/** A prototyping stand-in: a dashed box with `label` centred in it and the size the layout actually gave it written
  * underneath. Takes whatever room its container hands out, the way the panel it stands in for eventually will, so
  * swapping it for the real content does not move anything around it.
  */
final case class PlaceholderElement(
    label: String,
    labelStyle: Style,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = PlaceholderElement
  override def debugName: String                                      = "placeholder"
  def widget: Widget                                                  = w.Placeholder(label, props.style, labelStyle)
  private[dsl] def withProps(props: ElementProps): PlaceholderElement = copy(props = props)

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
  override def debugName: String                                     = "datePicker"
  def widget: Widget                                                 =
    val picker = w.DatePicker(w.CalendarOptions(style = props.style, highlightStyle = props.focusStyle))
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
  override def debugName: String                                         = "timePicker"
  private def picker: w.TimePicker                                       = w.TimePicker(props.style, props.focusStyle)
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
