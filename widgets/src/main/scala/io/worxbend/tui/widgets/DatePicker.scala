package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, StatefulWidget, Style, VersionedState}

import java.time.LocalDate
import scala.util.Try

/** Caller-owned state of a [[DatePicker]]: the date under the cursor, and the date the user last chose.
  *
  * The cursor is a whole `LocalDate`, not a day of a fixed month, so moving it past the end of a month simply lands in
  * the next one — Right on the 31st of July is the 1st of August — and the picker's grid follows it. A move that would
  * leave the range `LocalDate` can represent leaves the cursor where it is rather than throwing.
  *
  * Render-thread-only. Every mutation raises `version` (see [[io.worxbend.tui.core.Versioned]]), so a write made
  * outside the event path is painted too, with no `Signal` write.
  *
  * @param initial
  *   where the cursor starts With `min` or `max` set the cursor never leaves the range they bound: every move,
  *   `goToToday` and a direct write to [[cursor]] clamp to the nearer bound, so a date outside it can never be chosen.
  *   The picker draws such days in its `disabledStyle` and a click on one does nothing.
  *
  * @param today
  *   the clock `goToToday` reads; injectable so a test does not depend on the day it runs
  * @param min
  *   the earliest date the cursor may reach, inclusive; `None` for no lower bound
  * @param max
  *   the latest date the cursor may reach, inclusive; `None` for no upper bound. A `max` before `min` leaves only `min`
  *   reachable
  */
final class DatePickerState(
    initial: LocalDate,
    today: () => LocalDate = () => LocalDate.now(),
    val min: Option[LocalDate] = None,
    val max: Option[LocalDate] = None,
) extends VersionedState:

  /** The date under the cursor — the highlighted day, and the month the grid shows. Always inside `min`..`max`. */
  def cursor: LocalDate = cursorValue

  /** Moves the cursor to `value`, or to the nearer bound when `value` lies outside `min`..`max`. */
  def cursor_=(value: LocalDate): Unit =
    cursorValue = clamp(value)
    touch()

  private var cursorValue: LocalDate = clamp(initial)

  /** Whether `date` lies inside `min`..`max` — a day the cursor can reach and the user can choose. */
  def isSelectable(date: LocalDate): Boolean = clamp(date) == date

  private def clamp(date: LocalDate): LocalDate =
    val floored = max.filter(date.isAfter).getOrElse(date)
    min.filter(floored.isBefore).getOrElse(floored)

  /** The date the user last confirmed with [[select]]; `None` until they do. */
  def selected: Option[LocalDate] = selectedValue

  def selected_=(value: Option[LocalDate]): Unit =
    selectedValue = value
    touch()

  private var selectedValue: Option[LocalDate] = None

  /** Moves the cursor `days` days, across month and year boundaries. */
  def moveDays(days: Int): Unit = cursor = Try(cursor.plusDays(days.toLong)).getOrElse(cursor)

  /** Moves the cursor `months` months; a day the target month does not have clamps to its last day. */
  def moveMonths(months: Int): Unit = cursor = Try(cursor.plusMonths(months.toLong)).getOrElse(cursor)

  /** Puts the cursor on today's date. */
  def goToToday(): Unit = cursor = today()

  /** Records the cursor's date as the chosen one. */
  def select(): Unit = selected = Some(cursor)

/** An interactive month grid: [[Calendar]] showing whichever month [[DatePickerState.cursor]] is in, with the cursor
  * day in `options.highlightStyle` and the chosen date, when it is not under the cursor, in `selectedStyle`.
  *
  * The widget only draws; the keys — arrows move a day or a week, PageUp/PageDown a month, `t` jumps to today, Enter
  * chooses — are the DSL's `datePicker` node calling the state's methods. Needs the 20 columns and up to 8 rows a
  * [[Calendar]] needs, and clips like one.
  *
  * @param options
  *   the grid's headers, week start, locale and styles, exactly as a [[Calendar]] takes them. Any `dayStyles` given
  *   here still apply; the chosen date's style is layered over its entry
  * @param selectedStyle
  *   marks the chosen date while the cursor is elsewhere
  * @param disabledStyle
  *   layered over the days outside the state's `min`..`max`, which the cursor cannot reach
  */
final case class DatePicker(
    options: CalendarOptions = CalendarOptions(),
    selectedStyle: Style = Style.Default.underline,
    disabledStyle: Style = Style.Default.dim,
) extends StatefulWidget[DatePickerState]:

  def render(area: Rect, buffer: Buffer, state: DatePickerState): Unit =
    if !area.isEmpty then calendarFor(state).render(area, buffer)

  /** The selectable date drawn at `x`, `y` when the picker is rendered into `area`, or `None` for anything else — a
    * header, a gap, an empty slot, or a day outside the state's bounds. The DSL's click handler goes through this.
    */
  def dateAt(area: Rect, x: Int, y: Int, state: DatePickerState): Option[LocalDate] =
    calendarFor(state).dateAt(area, x, y).filter(state.isSelectable)

  private def calendarFor(state: DatePickerState): Calendar =
    val cursor    = state.cursor
    val first     = cursor.withDayOfMonth(1)
    val disabled  = (0 until cursor.lengthOfMonth).map(day => first.plusDays(day.toLong)).filterNot(state.isSelectable)
    val withLimit = disabled.foldLeft(options.dayStyles) { (styles, date) =>
      styles.updated(date, styles.get(date).fold(disabledStyle)(_.patch(disabledStyle)))
    }
    val dayStyles = state.selected.fold(withLimit) { chosen =>
      withLimit.updated(chosen, withLimit.get(chosen).fold(selectedStyle)(_.patch(selectedStyle)))
    }
    Calendar(cursor.getYear, cursor.getMonthValue, Some(cursor.getDayOfMonth), options.copy(dayStyles = dayStyles))
