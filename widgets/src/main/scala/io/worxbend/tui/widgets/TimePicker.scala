package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Rect, StatefulWidget, Style, VersionedState}

import java.time.LocalTime
import java.util.Locale

/** One of the fields of a [[TimePicker]], in the order focus moves through them. */
enum TimeField:
  case Hour, Minute, Second

/** Caller-owned state of a [[TimePicker]]: the time, and which field the arrows change.
  *
  * Stepping a field moves the whole time by that field's unit and wraps round midnight, so one minute up from `23:59`
  * is `00:00` and one hour down from `00:30` is `23:30` — the clock-face reading of "up", not a field that wraps on its
  * own while the others stand still.
  *
  * Render-thread-only. Every mutation raises `version` (see [[io.worxbend.tui.core.Versioned]]), so a write made
  * outside the event path is painted too, with no `Signal` write.
  *
  * @param initial
  *   the starting time; its nanoseconds are dropped, and its seconds too when `withSeconds` is off
  * @param withSeconds
  *   whether the picker has a seconds field. Without it the time is `hh:mm` and focus moves between two fields
  */
final class TimePickerState(initial: LocalTime, val withSeconds: Boolean = true) extends VersionedState:

  /** The time shown. */
  def time: LocalTime = timeValue

  def time_=(value: LocalTime): Unit =
    timeValue = normalize(value)
    pendingDigit = None
    touch()

  private def normalize(value: LocalTime): LocalTime =
    if withSeconds then value.withNano(0) else value.withSecond(0).withNano(0)

  private var timeValue: LocalTime = normalize(initial)

  /** The field the arrows change. A hidden `Second` is normalized to the last visible field, `Minute`. */
  def field: TimeField = fieldValue

  def field_=(value: TimeField): Unit =
    fieldValue = if !withSeconds && value == TimeField.Second then TimeField.Minute else value
    pendingDigit = None
    touch()

  private var fieldValue: TimeField = TimeField.Hour

  /** The fields this picker has, in focus order. */
  def fields: Seq[TimeField] = if withSeconds then TimeField.values.toSeq else Seq(TimeField.Hour, TimeField.Minute)

  /** Focus moves one field right, stopping at the last. */
  def nextField(): Unit = field = fields(math.min(fields.size - 1, fields.indexOf(field) + 1))

  /** Focus moves one field left, stopping at the first. */
  def previousField(): Unit = field = fields(math.max(0, fields.indexOf(field) - 1))

  /** The time one unit of the focused field later, wrapping past midnight. */
  def increment(): Unit = step(1)

  /** The time one unit of the focused field earlier, wrapping past midnight. */
  def decrement(): Unit = step(-1)

  /** Types one digit into the focused field, the way a time input on a form takes it.
    *
    * The first digit sets the field to that value and waits for a second; the second makes a two-digit value, clamped
    * to the field's maximum (`23` for the hour, `59` otherwise — so `2`, `9` on the hour is `23`), and moves focus to
    * the next field. A first digit no second one could follow without passing the maximum (`3`–`9` on the hour, `6`–`9`
    * on a minute or second) is committed at once as `0d`. Moving between fields or stepping drops a half-typed digit.
    * On the last field focus stays put. A `digit` outside `0..9` is ignored.
    */
  def typeDigit(digit: Int): Unit =
    if digit >= 0 && digit <= 9 then
      val maximum = if field == TimeField.Hour then 23 else 59
      pendingDigit match
        case Some(first) =>
          setField(math.min(maximum, first * 10 + digit))
          advance()
        case None        =>
          setField(digit)
          if digit * 10 > maximum then advance() else pendingDigit = Some(digit)

  private var pendingDigit: Option[Int] = None

  private def setField(value: Int): Unit =
    timeValue = field match
      case TimeField.Hour   => timeValue.withHour(value)
      case TimeField.Minute => timeValue.withMinute(value)
      case TimeField.Second => timeValue.withSecond(value)
    touch()

  private def advance(): Unit =
    pendingDigit = None
    nextField()

  private def step(delta: Long): Unit =
    pendingDigit = None
    time = field match
      case TimeField.Hour   => time.plusHours(delta)
      case TimeField.Minute => time.plusMinutes(delta)
      case TimeField.Second => time.plusSeconds(delta)

/** A one-row `hh:mm:ss` time field with the focused field in `highlightStyle`.
  *
  * The widget only draws; Left/Right moving between fields, Up/Down stepping the time and digits typed into the focused
  * field are the DSL's `timePicker` node calling [[TimePickerState]]'s methods. [[widthFor]] gives the columns the
  * state needs — 8, or 5 without seconds.
  *
  * @param style
  *   the digits and separators
  * @param highlightStyle
  *   layered over `style` on the focused field
  */
final case class TimePicker(
    style: Style = Style.Default,
    highlightStyle: Style = Style.Default.reverse,
) extends StatefulWidget[TimePickerState]:

  /** Columns `state`'s time takes: two per field plus one separator between neighbours. */
  def widthFor(state: TimePickerState): Int = state.fields.size * FieldStride - 1

  def render(area: Rect, buffer: Buffer, state: TimePickerState): Unit =
    if !area.isEmpty then
      val text  = state.fields
        .map {
          case TimeField.Hour   => state.time.getHour
          case TimeField.Minute => state.time.getMinute
          case TimeField.Second => state.time.getSecond
        }
        .map(value => String.format(Locale.ROOT, "%02d", value))
        .mkString(":")
      buffer.setString(area.x, area.y, CharWidth.substringByWidth(text, area.width), style)
      // every glyph here is one column wide, so field `i` occupies columns `3i` and `3i + 1`
      val index = state.fields.indexOf(state.field)
      if index >= 0 then
        val fieldX = area.x.toLong + index * FieldStride
        if fieldX < area.right then
          val highlight = Rect(fieldX.toInt, area.y, FieldWidth, 1).intersection(area)
          buffer.setStyle(highlight, style.patch(highlightStyle))

  private val FieldWidth  = 2
  private val FieldStride = FieldWidth + 1
