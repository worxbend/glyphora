package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Buffer, CharWidth, Rect, Style, Widget}

import java.time.format.TextStyle as JTextStyle
import java.time.{DayOfWeek, LocalDate, Year, YearMonth}
import java.util.Locale

/** Everything about a [[Calendar]] that is not the month or the selected day: which headers are shown, how the week
  * runs, the language, the surrounding days, and the styles.
  *
  * Bundled rather than listed on [[Calendar]] itself because the list had grown to ten optional knobs. Every field
  * keeps the default it had as a `Calendar` constructor parameter, so `CalendarOptions()` is exactly the grid the bare
  * constructor drew.
  *
  * @param showTitle
  *   whether the `July 2026` row is drawn. Switched off, the row is not reserved at all, so the first week moves up —
  *   which is what makes a bare month grid usable in a narrow sidebar
  * @param showWeekdays
  *   whether the `Mo Tu We …` row is drawn; switched off, its row goes to the grid too
  * @param firstDayOfWeek
  *   the weekday of the leftmost column, so a US-facing application asks for `DayOfWeek.SUNDAY` and gets a Sunday-first
  *   grid with the day numbers moved to match
  * @param locale
  *   the language of the month name and the weekday abbreviations. `Locale.ENGLISH` rather than `Locale.getDefault`,
  *   deliberately: a widget whose output depends on the machine it runs on cannot be tested by comparing frames, and a
  *   caller who wants the machine's locale can pass `Locale.getDefault` and mean it
  * @param showSurroundingDays
  *   fills the leading and trailing cells of the grid with the days of the months either side, drawn in
  *   `surroundingStyle` so they read as context rather than as part of this month. They are never selectable:
  *   `selected` names a day of the month being shown
  * @param style
  *   the style of the day numbers of the month shown
  * @param headerStyle
  *   the style of the title and the weekday row
  * @param highlightStyle
  *   layered over the selected day's style, last, so the cursor stays visible wherever it lands
  * @param surroundingStyle
  *   the style of the surrounding days — dimmed by default
  * @param dayStyles
  *   gives individual dates their own appearance — a date with an appointment, a public holiday, today, every day of a
  *   streak. Keyed by `java.time.LocalDate` rather than by day-of-month so a caller can hold one map across several
  *   months and hand the same value to each grid. A date's style is layered over the calendar's `style`, and
  *   `highlightStyle` over that in turn: a marked day that is also the selected day reads as selected first
  */
final case class CalendarOptions(
    showTitle: Boolean = true,
    showWeekdays: Boolean = true,
    firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    locale: Locale = Locale.ENGLISH,
    showSurroundingDays: Boolean = false,
    style: Style = Style.Default,
    headerStyle: Style = Style.Default.bold,
    highlightStyle: Style = Style.Default.reverse,
    surroundingStyle: Style = Style.Default.dim,
    dayStyles: Map[LocalDate, Style] = Map.empty,
)

/** A month grid: an optional title row, an optional weekday header, and the day numbers, with an optional highlighted
  * day.
  *
  * `year` and `month` (1–12) name the month; `selected` names a day of that month to draw in the highlight style.
  * Everything else — which headers are shown, how the week runs, the language, the surrounding days and the styles —
  * rides in `options`; see [[CalendarOptions]] for what each knob does.
  *
  * Needs 20 columns (seven three-column day slots, the last one two wide) and one row per header shown plus one row per
  * week the month touches — so up to 8 rows with both headers, and up to 6 with neither. Overflow clips like everything
  * else.
  */
final case class Calendar(
    year: Int,
    month: Int,
    selected: Option[Int] = None,
    options: CalendarOptions = CalendarOptions(),
) extends Widget:

  import options.*

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      // prev/next-month navigation naturally produces month 0 and 13, and every other widget here clips rather than
      // throwing out of `render`; `YearMonth.of` would raise `DateTimeException`
      val yearMonth = clampedMonth
      if showTitle then drawTitle(area, buffer, yearMonth)
      if showWeekdays then drawWeekdayHeader(area, buffer)
      drawDays(area, buffer, yearMonth)

  /** The date drawn at column `x`, row `y` when this calendar is rendered into `area`, or `None` for a header, the gap
    * between two days, a cell outside the area, or an empty slot. A surrounding day answers only while
    * `showSurroundingDays` draws it. This is the hit test a click on the grid goes through: it uses the same geometry
    * [[render]] does, so a click lands on the day it was drawn over.
    */
  def dateAt(area: Rect, x: Int, y: Int): Option[LocalDate] =
    val row     = y - gridTop(area)
    val column  = x - area.x
    val dayLeft = x - column                                  % SlotStride
    val onDay   = column >= 0 && column < GridWidth && column % SlotStride < DayColumnWidth
    if area.isEmpty || !onDay || row < 0 || y >= area.bottom || dayLeft + DayColumnWidth > area.right then None
    else
      val first   = clampedMonth.atDay(1)
      val slot    = row * DaysPerWeek + column / SlotStride
      val inMonth = slot >= columnOf(first) && slot < columnOf(first) + clampedMonth.lengthOfMonth
      if inMonth || (showSurroundingDays && slot < filledSlots(first)) then dateAt(first, slot - columnOf(first))
      else None

  /** The month named by `year` and `month`, clamped into what `YearMonth` accepts. */
  private def clampedMonth: YearMonth =
    YearMonth.of(math.max(Year.MIN_VALUE, math.min(Year.MAX_VALUE, year)), math.max(1, math.min(12, month)))

  /** Slots the grid fills when surrounding days are shown: every slot of every week row the month touches. */
  private def filledSlots(first: LocalDate): Int =
    math.ceil((columnOf(first) + clampedMonth.lengthOfMonth) / DaysPerWeek.toDouble).toInt * DaysPerWeek

  private def drawTitle(area: Rect, buffer: Buffer, yearMonth: YearMonth): Unit =
    val title  = s"${yearMonth.getMonth.getDisplayName(JTextStyle.FULL, locale)} ${yearMonth.getYear}"
    val fitted = CharWidth.substringByWidth(title, area.width)
    val startX = Alignment.Center.originAt(area.x, math.min(area.width, GridWidth), CharWidth.of(fitted))
    buffer.setString(startX, area.y, fitted, headerStyle)

  /** The `Mo Tu We …` strip, on the row under the title when there is one.
    *
    * The row is checked against the area's bottom for the same reason [[drawDays]] checks it: with `showTitle` on, this
    * row is `area.y + 1`, which is outside a one-row rect — and `Buffer.setString` clips to the *buffer*, not to the
    * rectangle this widget was handed, so an unguarded write landed on whatever the neighbouring widget had drawn
    * there. A one-row calendar is not exotic: it is what a `Constraint` solver hands out when the pane it lives in is
    * squeezed.
    */
  private def drawWeekdayHeader(area: Rect, buffer: Buffer): Unit =
    val header = weekDays.map(weekdayLabel).mkString(" ")
    val y      = if showTitle then area.y + 1 else area.y
    if y < area.bottom then buffer.setString(area.x, y, CharWidth.substringByWidth(header, area.width), headerStyle)

  /** One weekday abbreviation, cut and padded to exactly the two columns a day slot is wide.
    *
    * The cut goes through `CharWidth` rather than through the string's character count, because a locale whose
    * abbreviations are CJK characters (Japanese `月`, `火`, ...) puts two *columns* in one character, and taking two
    * characters there would push every following column one place to the right.
    */
  private def weekdayLabel(day: DayOfWeek): String =
    val cut = CharWidth.substringByWidth(day.getDisplayName(JTextStyle.SHORT, locale), DayColumnWidth)
    cut + " " * math.max(0, DayColumnWidth - CharWidth.of(cut))

  private def drawDays(area: Rect, buffer: Buffer, yearMonth: YearMonth): Unit =
    val first       = yearMonth.atDay(1)
    val firstColumn = columnOf(first)
    val monthSlots  = firstColumn + yearMonth.lengthOfMonth
    // with surrounding days the grid is filled out to the end of the last week the month touches, so no week row is
    // left half-drawn; without them it stops at the last day of the month, as it always has
    val slots       =
      if showSurroundingDays then math.ceil(monthSlots / DaysPerWeek.toDouble).toInt * DaysPerWeek else monthSlots
    (0 until slots).foreach { slot =>
      val inMonth = slot >= firstColumn && slot < monthSlots
      if inMonth || showSurroundingDays then
        dateAt(first, slot - firstColumn).foreach { date =>
          val x = area.x + (slot % DaysPerWeek) * SlotStride
          val y = gridTop(area) + slot / DaysPerWeek
          // a grid cell is two columns wide: drop the ones the area cannot hold rather than write past its edges
          if x + 2 <= area.right && y < area.bottom then
            // `String.format`, not the `f` interpolator: `f"%2d"` formats through the default FORMAT locale (its
            // digits and padding, not just its number grouping), which is exactly what this widget's own `locale`
            // parameter exists to avoid — see the class Scaladoc.
            buffer.setString(x, y, String.format(locale, "%2d", date.getDayOfMonth), styleFor(date, inMonth))
        }
    }

  /** The style one day cell is drawn with: the calendar's `style` for a day of this month and `surroundingStyle` for a
    * day borrowed from the month either side, then whatever `dayStyles` says about that date, then `highlightStyle` for
    * the selected day.
    *
    * `selected` names a day of *this* month, so a surrounding day that happens to carry the same number is never
    * mistaken for the cursor.
    */
  private def styleFor(date: LocalDate, inMonth: Boolean): Style =
    val base   = if inMonth then style else surroundingStyle
    val marked = dayStyles.get(date).fold(base)(base.patch)
    if inMonth && selected.contains(date.getDayOfMonth) then marked.patch(highlightStyle) else marked

  /** `offset` days after the first of the month, or `None` when that date falls outside the range `LocalDate` can
    * represent.
    *
    * `LocalDate.plusDays` throws there, and this widget clamps rather than throws out of `render` — a calendar sitting
    * on the very first month `LocalDate` knows about has no previous month to borrow days from, so it draws none.
    */
  private def dateAt(first: LocalDate, offset: Int): Option[LocalDate] =
    val epochDay = first.toEpochDay + offset
    if epochDay >= LocalDate.MIN.toEpochDay && epochDay <= LocalDate.MAX.toEpochDay then
      Some(LocalDate.ofEpochDay(epochDay))
    else None

  /** The first row the day grid may use: whichever headers are shown come first, and a header that is switched off
    * gives its row back to the grid rather than leaving it blank.
    */
  private def gridTop(area: Rect): Int =
    area.y + (if showTitle then 1 else 0) + (if showWeekdays then 1 else 0)

  /** Column index of a date's weekday, counting from `firstDayOfWeek`. `floorMod` rather than `%` because the
    * subtraction is negative for every weekday that falls before the configured start of the week.
    */
  private def columnOf(date: LocalDate): Int =
    math.floorMod(date.getDayOfWeek.getValue - firstDayOfWeek.getValue, DaysPerWeek)

  /** The seven weekdays in the order this calendar's columns run. */
  private def weekDays: Seq[DayOfWeek] = (0 until DaysPerWeek).map(offset => firstDayOfWeek.plus(offset.toLong))

  private val DayColumnWidth = 2
  private val DaysPerWeek    = 7

  /** Columns from one day slot's left edge to the next: the day's own columns plus the single blank that separates two
    * days.
    */
  private val SlotStride = DayColumnWidth + 1

  /** Columns the whole grid needs: seven slots at [[SlotStride]], less the trailing separator the last slot does not
    * have.
    */
  private val GridWidth = DaysPerWeek * SlotStride - 1
