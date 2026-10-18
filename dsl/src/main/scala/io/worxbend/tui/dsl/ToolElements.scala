package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, KeyCode, KeyEvent, KeyModifiers, MouseEventKind, Style, Widget}
import io.worxbend.tui.macros.PrettyShape
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
  override def debugName: String                                             = "colorPicker"
  private def picker: w.ColorPicker                                          = w.ColorPicker(style = props.style)
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

/** A scrollable hex dump of `bytes` over a caller-owned [[w.HexViewerState]]. While focused, Left/Right move the
  * selected byte by one, Up/Down by a row, PageUp/PageDown by ten rows and Home/End to either end; the wheel moves a
  * row on hover. The view scrolls to keep the selection on screen, and the selected byte wears the focus style in both
  * the hex and the ASCII column.
  */
final case class HexViewerElement(
    bytes: IndexedSeq[Byte],
    state: w.HexViewerState,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = HexViewerElement
  override def debugName: String                                             = "hexViewer"
  private val viewer: w.HexViewer                                            = w.HexViewer(bytes)
  def widget: Widget                                                         =
    val current = viewer.copy(style = props.style, highlightStyle = props.focusStyle)
    (area, buffer) => current.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): HexViewerElement          = copy(props = props)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(wheelScrolls(() => move(-viewer.bytesPerRow), () => move(viewer.bytesPerRow)))
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    val row = viewer.bytesPerRow
    Some(
      keys {
        case KeyEvent(KeyCode.Left, _)     => move(-1)
        case KeyEvent(KeyCode.Right, _)    => move(1)
        case KeyEvent(KeyCode.Up, _)       => move(-row)
        case KeyEvent(KeyCode.Down, _)     => move(row)
        case KeyEvent(KeyCode.PageUp, _)   => move(-row * PageStep)
        case KeyEvent(KeyCode.PageDown, _) => move(row * PageStep)
        case KeyEvent(KeyCode.Home, _)     => state.select(0, bytes.size)
        case KeyEvent(KeyCode.End, _)      => state.select(bytes.size - 1, bytes.size)
      }
    )

  private def move(delta: Int): Unit = state.moveBy(delta, bytes.size)

/** A tree with columns over a caller-owned [[w.TreeState]]. While focused, Up/Down move the selection through the
  * visible rows, Enter toggles the selected branch, Right expands it and Left collapses it; the wheel moves the
  * selection on hover. The selected row wears the focus style across every column.
  */
final case class TreeTableElement[K](
    table: w.TreeTable[K],
    state: w.TreeState[K],
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = TreeTableElement[K]
  override def debugName: String                                             = "treeTable"
  private def nodes                                                          = table.nodes
  def widget: Widget                                                         =
    val current = table.copy(style = props.style, highlightStyle = props.focusStyle)
    (area, buffer) => current.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): TreeTableElement[K]       = copy(props = props)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(wheelScrolls(() => state.selectPrevious(nodes), () => state.selectNext(nodes)))
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(
      keys {
        case KeyEvent(KeyCode.Enter, _)                                                    => state.toggle(nodes)
        case KeyEvent(KeyCode.Right, _) if !state.selected.exists(state.expanded.contains) => state.toggle(nodes)
        case KeyEvent(KeyCode.Left, _) if state.selected.exists(state.expanded.contains)   => state.toggle(nodes)
      }.orElse(selectionKeys(() => state.selectNext(nodes), () => state.selectPrevious(nodes)))
    )

/** A read-only tree view of a described value — see [[w.Inspect]]. Claims exactly the rows its tree measures, so it
  * stacks in a `column` like text; put it in a [[scrollView]] when the value is taller than the screen.
  */
final case class InspectElement(
    value: w.PrettyValue,
    labelStyle: Style,
    guideStyle: Style,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = InspectElement
  override def debugName: String                                  = "inspect"
  private def inspector: w.Inspect                                =
    w.Inspect(value, style = props.style, labelStyle = labelStyle, guideStyle = guideStyle)
  def widget: Widget                                              = inspector
  private[dsl] def withProps(props: ElementProps): InspectElement = copy(props = props)
  private[dsl] override def claim: SizeClaim = inspector.heightAt(0).fold(SizeClaim.Fill)(SizeClaim.rows)

/** The [[PrettyShape]] that builds `tui-widgets`' [[w.PrettyValue]] — the glue between the derivation in `tui-macros`
  * and the widget, neither of which can name the other.
  */
private[dsl] object PrettyValueShape extends PrettyShape[w.PrettyValue]:
  def atom(text: String): w.PrettyValue                                              = w.PrettyValue.Atom(text)
  def sequence(name: String, items: Seq[w.PrettyValue]): w.PrettyValue               = w.PrettyValue.Seq(name, items)
  def map(name: String, entries: Seq[(w.PrettyValue, w.PrettyValue)]): w.PrettyValue = w.PrettyValue.Map(name, entries)
  def product(name: String, fields: Seq[(String, w.PrettyValue)]): w.PrettyValue = w.PrettyValue.Product(name, fields)

/** A scannable QR code for `text` — see [[w.QrCode]]. Claims exactly the box the symbol and its quiet zone measure, so
  * it sits beside text in a `row` without being stretched; the code is only readable whole, so give it that room.
  */
final case class QrCodeElement(
    code: w.QrCode,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = QrCodeElement
  override def debugName: String                                 = "qrCode"
  def widget: Widget                                             = code
  private[dsl] def withProps(props: ElementProps): QrCodeElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                     =
    (code.widthAt(0), code.heightAt(0)) match
      case (Some(width), Some(height)) => SizeClaim.box(width, height)
      case _                           => SizeClaim.Fill
