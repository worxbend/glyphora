package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Modifiers, Rect, Style}

import java.time.{DayOfWeek, LocalDate, Year}
import java.util.Locale
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class CalendarSpec extends AnyFunSuite:

  test("renders the title, weekday header, and day grid for July 2026"):
    val lines = trimmedLines(rendered(Calendar(2026, 7), 20, 8))
    assert(lines(0).contains("July 2026"))
    assert(lines(1) == "Mo Tu We Th Fr Sa Su")
    // 2026-07-01 is a Wednesday: first row starts at the We column
    assert(lines(2) == "       1  2  3  4  5")
    assert(lines(3) == " 6  7  8  9 10 11 12")
    assert(lines(6).startsWith("27 28 29 30 31"))

  test("a month starting on Monday fills the first row"):
    val lines = trimmedLines(rendered(Calendar(2026, 6), 20, 8))
    assert(lines(2) == " 1  2  3  4  5  6  7")

  test("day cells that do not fit the area are dropped, not written past its edges"):
    // the full grid needs 20 columns and 2 + weeks rows; this area has neither
    val buffer = Buffer(Rect(0, 0, 24, 12))
    Calendar(2026, 7).render(Rect(0, 0, 11, 5), buffer)
    val lines  = trimmedLines(buffer)
    assert(lines.forall(_.length <= 11))
    assert(lines.drop(5).forall(_.isEmpty))

  test("the selected day is highlighted"):
    val buffer = rendered(Calendar(2026, 7, selected = Some(1)), 20, 8)
    // day 1 sits in the We column (x = 6..7) on the first grid row (y = 2)
    assert(buffer.get(7, 2).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(10, 2).style.modifiers.hasAny(Modifiers.Reverse))

  test("a date named in dayStyles is drawn with that style layered over the calendar's"):
    val marked = Map(LocalDate.of(2026, 7, 2) -> Style.Default.bold)
    val buffer = rendered(Calendar(2026, 7, options = CalendarOptions(dayStyles = marked)), 20, 8)
    // 2026-07-01 is a Wednesday, so the 2nd sits in the Th column (x = 9..10) on the first grid row
    assert(buffer.get(10, 2).symbol == "2")
    assert(buffer.get(10, 2).style.modifiers.hasAny(Modifiers.Bold))
    assert(!buffer.get(7, 2).style.modifiers.hasAny(Modifiers.Bold))

  test("dates in other months are ignored, so one map can be shared across grids"):
    val marked = Map(LocalDate.of(2026, 8, 2) -> Style.Default.bold)
    val buffer = rendered(Calendar(2026, 7, options = CalendarOptions(dayStyles = marked)), 20, 8)
    assert(buffer.get(10, 2).symbol == "2")
    assert(!buffer.get(10, 2).style.modifiers.hasAny(Modifiers.Bold))

  test("the selection is layered over a marked day, so the cursor stays visible"):
    val marked = Map(LocalDate.of(2026, 7, 1) -> Style.Default.bold)
    val buffer = rendered(Calendar(2026, 7, selected = Some(1), options = CalendarOptions(dayStyles = marked)), 20, 8)
    assert(buffer.get(7, 2).style.modifiers.hasAny(Modifiers.Reverse))
    assert(buffer.get(7, 2).style.modifiers.hasAny(Modifiers.Bold))

  test("switching the title off frees its row instead of blanking it"):
    val lines = trimmedLines(rendered(Calendar(2026, 7, options = CalendarOptions(showTitle = false)), 20, 8))
    assert(lines(0) == "Mo Tu We Th Fr Sa Su")
    assert(lines(1) == "       1  2  3  4  5")

  test("with neither header the grid starts on the first row of the area"):
    val calendar = Calendar(2026, 7, options = CalendarOptions(showTitle = false, showWeekdays = false))
    val lines    = trimmedLines(rendered(calendar, 20, 6))
    assert(lines(0) == "       1  2  3  4  5")
    assert(lines(4).startsWith("27 28 29 30 31"))

  test("a Sunday-first week moves both the header and the day numbers"):
    val lines =
      trimmedLines(rendered(Calendar(2026, 7, options = CalendarOptions(firstDayOfWeek = DayOfWeek.SUNDAY)), 20, 8))
    assert(lines(1) == "Su Mo Tu We Th Fr Sa")
    // 2026-07-01 is a Wednesday: the fourth column when the week starts on Sunday, the third when it starts on Monday
    assert(lines(2) == "          1  2  3  4")

  test("a week starting mid-week still lands every day in the right column"):
    val lines =
      trimmedLines(rendered(Calendar(2026, 7, options = CalendarOptions(firstDayOfWeek = DayOfWeek.SATURDAY)), 20, 8))
    assert(lines(1) == "Sa Su Mo Tu We Th Fr")
    assert(lines(2) == "             1  2  3")

  test("the locale sets the language of the month name and the weekday abbreviations"):
    val lines = trimmedLines(rendered(Calendar(2026, 7, options = CalendarOptions(locale = Locale.FRENCH)), 20, 8))
    assert(lines(0).toLowerCase(Locale.ROOT).contains("juil"))
    assert(lines(1).startsWith("lu"))

  test("a locale whose abbreviations are wide characters keeps the columns aligned"):
    val lines = trimmedLines(rendered(Calendar(2026, 7, options = CalendarOptions(locale = Locale.JAPANESE)), 20, 8))
    // each abbreviation is one character occupying two columns, so the header is still 7 * 3 - 1 columns wide
    assert(CharWidth.of(lines(1)) == 20)
    assert(lines(2) == "       1  2  3  4  5")

  test("the selection follows the configured start of the week"):
    val buffer = rendered(
      Calendar(2026, 7, selected = Some(1), options = CalendarOptions(firstDayOfWeek = DayOfWeek.SUNDAY)),
      20,
      8,
    )
    // the 1st is a Wednesday, the fourth Sunday-first column, so its two cells are x = 9..10
    assert(buffer.get(10, 2).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(7, 2).style.modifiers.hasAny(Modifiers.Reverse))

  test("surrounding days fill the leading and trailing cells of the grid"):
    val lines = trimmedLines(rendered(Calendar(2026, 7, options = CalendarOptions(showSurroundingDays = true)), 20, 8))
    // June 2026 ends on Tuesday the 30th, so the two cells before Wednesday the 1st are the 29th and the 30th
    assert(lines(2) == "29 30  1  2  3  4  5")
    // July ends on a Friday; the last row is completed with the 1st and 2nd of August
    assert(lines(6) == "27 28 29 30 31  1  2")

  test("surrounding days are dimmed and days of the month are not"):
    val buffer = rendered(Calendar(2026, 7, options = CalendarOptions(showSurroundingDays = true)), 20, 8)
    assert(buffer.get(0, 2).style.modifiers.hasAny(Modifiers.Dim))
    assert(!buffer.get(7, 2).style.modifiers.hasAny(Modifiers.Dim))

  test("a surrounding day sharing a number with the selected day is not highlighted"):
    // 2026-08-01 trails July's grid, and the 1st of July is selected
    val buffer =
      rendered(Calendar(2026, 7, selected = Some(1), options = CalendarOptions(showSurroundingDays = true)), 20, 8)
    assert(buffer.get(16, 6).symbol == "1")
    assert(!buffer.get(16, 6).style.modifiers.hasAny(Modifiers.Reverse))
    assert(buffer.get(7, 2).style.modifiers.hasAny(Modifiers.Reverse))

  test("dayStyles reach surrounding days too, so a marked date shows in either month"):
    val marked = Map(LocalDate.of(2026, 8, 1) -> Style.Default.bold)
    val buffer =
      rendered(Calendar(2026, 7, options = CalendarOptions(showSurroundingDays = true, dayStyles = marked)), 20, 8)
    assert(buffer.get(16, 6).style.modifiers.hasAny(Modifiers.Bold))

  test("no surrounding days are drawn when the switch is off"):
    val lines = trimmedLines(rendered(Calendar(2026, 7), 20, 8))
    assert(lines(2) == "       1  2  3  4  5")
    assert(lines(6) == "27 28 29 30 31")

  test("a month with no room before it draws no leading days rather than failing"):
    // the very first month LocalDate can represent has no previous month to borrow days from; starting the week on a
    // Tuesday puts that month's 1st in the last column, so all six leading cells would have to come from before it
    val calendar = Calendar(
      Year.MIN_VALUE,
      1,
      options = CalendarOptions(firstDayOfWeek = DayOfWeek.TUESDAY, showSurroundingDays = true),
    )
    val lines    = trimmedLines(rendered(calendar, 20, 8))
    assert(lines(2) == "                   1")

  test("day numbers render in the calendar's own locale, not the process default"):
    // day-of-month formatting used to go through the `f"%2d"` interpolator, which reads the process's default
    // FORMAT locale regardless of the `locale` constructor parameter — contradicting the class's own stated purpose
    // ("a widget whose output depends on the machine it runs on cannot be tested by comparing frames"). A locale
    // whose numbering system is not ASCII digits proves the day numbers actually route through `locale`, by
    // matching exactly what String.format(locale, "%2d", n) produces for that locale — no digit shape hardcoded.
    val arabicIndic = Locale.forLanguageTag("ar-SA-u-nu-arab")
    val expectedOne = String.format(arabicIndic, "%2d", 1)
    assert(expectedOne != " 1", "the test locale must actually use non-ASCII digits, or this proves nothing")
    val lines       = trimmedLines(rendered(Calendar(2026, 7, options = CalendarOptions(locale = arabicIndic)), 20, 8))
    assert(lines.exists(_.contains(expectedOne)))

  test("CalendarOptions() draws exactly what the bare constructor drew"):
    assert(Calendar(2026, 7, Some(14)) == Calendar(2026, 7, Some(14), CalendarOptions()))
    assert(
      rendered(Calendar(2026, 7, Some(14)), 20, 8) == rendered(Calendar(2026, 7, Some(14), CalendarOptions()), 20, 8)
    )

  test("dateAt answers a surrounding day only while surrounding days are drawn"):
    val area  = Rect(0, 0, 20, 8)
    val plain = Calendar(2026, 7)
    assert(plain.dateAt(area, 0, 2).isEmpty) // Monday slot before 1 July
    assert(plain.dateAt(area, 6, 2).contains(LocalDate.of(2026, 7, 1)))
    val filled = Calendar(2026, 7, options = CalendarOptions(showSurroundingDays = true))
    assert(filled.dateAt(area, 0, 2).contains(LocalDate.of(2026, 6, 29)))
    assert(filled.dateAt(area, 18, 6).contains(LocalDate.of(2026, 8, 2)))
    assert(filled.dateAt(area, 0, 7).isEmpty) // no sixth week row
    assert(filled.dateAt(Rect(0, 0, 10, 8), 9, 2).isEmpty) // the day is clipped by the area
