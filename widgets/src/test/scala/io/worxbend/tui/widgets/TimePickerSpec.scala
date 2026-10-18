package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Modifiers, Rect}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}

import org.scalatest.funsuite.AnyFunSuite

import java.time.LocalTime

final class TimePickerSpec extends AnyFunSuite:

  test("the time is drawn as hh:mm:ss with the focused field highlighted"):
    val state  = TimePickerState(LocalTime.of(9, 5, 7))
    val buffer = rendered(TimePicker(), state, 10, 1)
    assert(lines(buffer) == Seq("09:05:07  "))
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(buffer.get(1, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(3, 0).style.modifiers.hasAny(Modifiers.Reverse))
    state.nextField()
    val moved  = rendered(TimePicker(), state, 10, 1)
    assert(moved.get(3, 0).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!moved.get(0, 0).style.modifiers.hasAny(Modifiers.Reverse))

  test("23:59 plus one minute wraps to 00:00"):
    val state = TimePickerState(LocalTime.of(23, 59), withSeconds = false)
    state.nextField()
    state.increment()
    assert(state.time == LocalTime.of(0, 0))
    state.decrement()
    assert(state.time == LocalTime.of(23, 59))

  test("each field steps by its own unit and the hour wraps round midnight"):
    val state = TimePickerState(LocalTime.of(0, 0, 0))
    state.decrement()
    assert(state.time == LocalTime.of(23, 0, 0))
    state.nextField()
    state.nextField()
    state.decrement()
    assert(state.time == LocalTime.of(22, 59, 59))

  test("field focus stops at either end, and without seconds there are two fields"):
    val state = TimePickerState(LocalTime.NOON, withSeconds = false)
    state.previousField()
    assert(state.field == TimeField.Hour)
    state.nextField()
    state.nextField()
    assert(state.field == TimeField.Minute)
    assert(lines(rendered(TimePicker(), state, 6, 1)) == Seq("12:00 "))

  test("the picker measures its own width and clips in a narrow area"):
    assert(TimePicker().widthFor(TimePickerState(LocalTime.NOON)) == 8)
    assert(TimePicker().widthFor(TimePickerState(LocalTime.NOON, withSeconds = false)) == 5)
    assert(lines(rendered(TimePicker(), TimePickerState(LocalTime.NOON), 4, 1)) == Seq("12:0"))
    val buffer = rendered(TimePicker(), TimePickerState(LocalTime.NOON), 4, 1)
    TimePicker().render(Rect(0, 0, 0, 0), buffer, TimePickerState(LocalTime.NOON))
