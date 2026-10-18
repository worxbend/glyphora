package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Modifiers, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

import java.time.LocalDate

final class DatePickerSpec extends AnyFunSuite:

  private val today = LocalDate.of(2026, 10, 19)

  private def state(at: LocalDate): DatePickerState = DatePickerState(at, () => today)

  test("the picker draws the cursor's month with the cursor highlighted"):
    val buffer = rendered(DatePicker(), state(LocalDate.of(2026, 7, 14)), 20, 8)
    assert(
      trimmedLines(buffer) == Seq(
        "     July 2026",
        "Mo Tu We Th Fr Sa Su",
        "       1  2  3  4  5",
        " 6  7  8  9 10 11 12",
        "13 14 15 16 17 18 19",
        "20 21 22 23 24 25 26",
        "27 28 29 30 31",
        "",
      )
    )
    // the 14th is the second slot of the third grid row: x = 3..4, y = 4
    assert(buffer.get(3, 4).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(6, 4).style.modifiers.hasAny(Modifiers.Reverse))

  test("Right on the last day of a month moves to the first of the next"):
    val picker = state(LocalDate.of(2026, 7, 31))
    picker.moveDays(1)
    assert(picker.cursor == LocalDate.of(2026, 8, 1))
    picker.moveDays(-1)
    assert(picker.cursor == LocalDate.of(2026, 7, 31))

  test("a week down crosses the year, and a month forward clamps to the shorter month"):
    val picker       = state(LocalDate.of(2026, 12, 29))
    picker.moveDays(7)
    assert(picker.cursor == LocalDate.of(2027, 1, 5))
    val endOfJanuary = state(LocalDate.of(2027, 1, 31))
    endOfJanuary.moveMonths(1)
    assert(endOfJanuary.cursor == LocalDate.of(2027, 2, 28))
    endOfJanuary.moveMonths(-2)
    assert(endOfJanuary.cursor == LocalDate.of(2026, 12, 28))

  test("today jumps the cursor to the clock's date and select records the cursor"):
    val picker = state(LocalDate.of(2020, 1, 1))
    assert(picker.selected.isEmpty)
    picker.goToToday()
    assert(picker.cursor == today)
    picker.select()
    assert(picker.selected.contains(today))

  test("the cursor stops at the edges of the range LocalDate can represent"):
    val picker   = state(LocalDate.MAX)
    picker.moveDays(1)
    picker.moveMonths(1)
    assert(picker.cursor == LocalDate.MAX)
    val earliest = state(LocalDate.MIN)
    earliest.moveDays(-7)
    assert(earliest.cursor == LocalDate.MIN)

  test("a chosen date that is not under the cursor keeps its own mark"):
    val picker = state(LocalDate.of(2026, 7, 1))
    picker.select()
    picker.moveDays(1)
    val buffer = rendered(DatePicker(), picker, 20, 8)
    // the 1st (x = 6..7, y = 2) carries the chosen-date style, the 2nd (x = 9..10) the cursor
    assert(buffer.get(7, 2).style.modifiers.hasAny(Modifiers.Underline))
    assert(buffer.get(10, 2).style.modifiers.hasAny(Modifiers.Reverse))

  test("a tiny area clips the grid instead of writing past its edges"):
    val buffer   = Buffer(Rect(0, 0, 12, 6))
    val sentinel = Cell("~", Style.Default)
    buffer.fill(buffer.area, sentinel)
    val area     = Rect(1, 1, 6, 2)
    DatePicker().render(area, buffer, state(LocalDate.of(2026, 7, 14)))
    for
      y <- 0 until 6
      x <- 0 until 12
      if !area.contains(x, y)
    do assert(buffer.get(x, y) == sentinel, s"outside write at ($x, $y)")
    DatePicker().render(Rect(0, 0, 0, 0), buffer, state(LocalDate.of(2026, 7, 14)))
