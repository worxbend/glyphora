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
  *   where the cursor starts
  * @param today
  *   the clock `goToToday` reads; injectable so a test does not depend on the day it runs
  */
final class DatePickerState(initial: LocalDate, today: () => LocalDate = () => LocalDate.now()) extends VersionedState:

  /** The date under the cursor — the highlighted day, and the month the grid shows. */
  def cursor: LocalDate = cursorValue

  def cursor_=(value: LocalDate): Unit =
    cursorValue = value
    touch()

  private var cursorValue: LocalDate = initial

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
  */
final case class DatePicker(
    options: CalendarOptions = CalendarOptions(),
    selectedStyle: Style = Style.Default.underline,
) extends StatefulWidget[DatePickerState]:

  def render(area: Rect, buffer: Buffer, state: DatePickerState): Unit =
    if !area.isEmpty then
      val cursor    = state.cursor
      val dayStyles = state.selected.fold(options.dayStyles) { chosen =>
        options.dayStyles.updated(chosen, options.dayStyles.get(chosen).fold(selectedStyle)(_.patch(selectedStyle)))
      }
      Calendar(cursor.getYear, cursor.getMonthValue, Some(cursor.getDayOfMonth), options.copy(dayStyles = dayStyles))
        .render(area, buffer)
