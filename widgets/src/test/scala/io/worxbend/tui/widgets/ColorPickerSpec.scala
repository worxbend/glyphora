package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Color, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{line, rendered}
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

final class ColorPickerSpec extends AnyFunSuite:

  test("the grid lays out the 16 system colours, the 6x6x6 cube and the grey ramp"):
    assert(ColorPicker.Grid.size == 16)
    assert(ColorPicker.Grid.flatten == (0 to 255))
    assert(ColorPicker.Grid(0) == (0 to 7))
    assert(ColorPicker.Grid(2).take(7) == Seq(16, 17, 18, 19, 20, 21, 22))
    assert(ColorPicker.Grid(12).head == 196)
    assert(ColorPicker.Grid(15) == (244 to 255))

  test("each swatch is two cells of its own background"):
    val buffer = rendered(ColorPicker(), ColorPickerState(), 36, 17)
    assert(buffer.get(0, 12).style.bg.contains(Color.Indexed(196)))
    assert(buffer.get(1, 12).style.bg.contains(Color.Indexed(196)))
    assert(buffer.get(2, 12).style.bg.contains(Color.Indexed(197)))
    assert(buffer.get(14, 1).style.bg.contains(Color.Indexed(15)))
    assert(buffer.get(16, 1).style.bg.isEmpty)

  test("walking to the bright-red cell and choosing it yields Color.Indexed(196)"):
    val state  = ColorPickerState()
    (1 to 12).foreach(_ => state.moveDown())
    assert(state.index == 196)
    assert(state.choose() == Color.Indexed(196))
    assert(state.chosen.contains(Color.Indexed(196)))
    val buffer = rendered(ColorPicker(), state, 36, 17)
    assert(line(buffer, 12).startsWith("[]"))
    assert(line(buffer, 16).trim == "196  #ff0000")

  test("moves stop at the grid's edges and a column past a shorter row clamps to its end"):
    val state = ColorPickerState()
    state.moveUp()
    state.moveLeft()
    assert(state.index == 0)
    (1 to 20).foreach(_ => state.moveRight())
    assert(state.index == 7)
    state.moveDown()
    state.moveDown()
    assert(state.index == 23)
    (1 to 20).foreach(_ => state.moveRight())
    state.moveUp()
    assert(state.index == 15)
    (1 to 30).foreach(_ => state.moveDown())
    assert(state.index == 251)

  test("an initial index outside the palette is clamped"):
    assert(ColorPickerState(999).index == 255)
    assert(ColorPickerState(-4).index == 0)

  test("the picker measures itself and clips in a small area"):
    assert(ColorPicker().widthAt(0).contains(36))
    assert(ColorPicker().heightAt(36).contains(17))
    assert(ColorPicker(showCaption = false).heightAt(36).contains(16))
    val buffer   = Buffer(Rect(0, 0, 10, 6))
    val sentinel = Cell("~", Style.Default)
    buffer.fill(buffer.area, sentinel)
    val area     = Rect(1, 1, 5, 3)
    ColorPicker().render(area, buffer, ColorPickerState(255))
    for
      y <- 0 until 6
      x <- 0 until 10
      if !area.contains(x, y)
    do assert(buffer.get(x, y) == sentinel)

  test("RGB fields follow the grid cursor until one is typed into"):
    val state = ColorPickerState(196)
    assert(state.channelValue(ColorChannel.Red).contains(255))
    assert(state.channelValue(ColorChannel.Green).contains(0))
    state.focusNextField()
    assert(state.field == ColorPickerField.Channel(ColorChannel.Red))
    state.deleteDigit()
    state.deleteDigit()
    state.deleteDigit()
    assert(state.channelValue(ColorChannel.Red).isEmpty) // empty is invalid
    state.typeDigit(1)
    state.typeDigit(2)
    state.typeDigit(8)
    state.typeDigit(9)                                   // a fourth digit is refused
    assert(state.channelText(ColorChannel.Red) == "128")
    assert(state.chooseRgb().contains(Color.Rgb(128, 0, 0)))
    assert(state.chosen.contains(Color.Rgb(128, 0, 0)))

  test("a channel past 255 is invalid and blocks choosing; Up/Down step a channel within 0..255"):
    val state = ColorPickerState(16) // #000000
    state.focusNextField()
    state.focusNextField()
    state.typeDigit(3) // the first digit replaces the value the field followed from the grid
    state.typeDigit(0)
    state.typeDigit(0)
    assert(state.channelText(ColorChannel.Green) == "300")
    assert(state.channelValue(ColorChannel.Green).isEmpty)
    assert(state.chooseRgb().isEmpty)
    assert(state.chosen.isEmpty)
    state.stepChannel(1) // an invalid entry steps from the nearest valid value
    assert(state.channelValue(ColorChannel.Green).contains(255))
    state.stepChannel(-256)
    assert(state.channelValue(ColorChannel.Green).contains(0))

  test("field focus runs grid, red, green, blue and reports when it reaches either end"):
    val state = ColorPickerState()
    assert(!state.focusPreviousField())
    assert(state.focusNextField())
    assert(state.focusNextField())
    assert(state.focusNextField())
    assert(state.field == ColorPickerField.Channel(ColorChannel.Blue))
    assert(!state.focusNextField())
    assert(state.focusPreviousField())
    assert(state.field == ColorPickerField.Channel(ColorChannel.Green))
    state.moveDown() // a grid move hands focus back to the grid
    assert(state.field == ColorPickerField.Grid)

  test("Rgb mode measures the grid plus the field column and draws the fields beside the grid"):
    val picker = ColorPicker(mode = ColorPickerMode.Rgb)
    assert(picker.widthAt(0).contains(45))
    val state  = ColorPickerState(196)
    val buffer = rendered(picker, state, 45, 17)
    assert(line(buffer, 0).drop(38) == "R [255]")
    assert(line(buffer, 1).drop(38) == "G [  0]")
    assert(line(buffer, 2).drop(38) == "B [  0]")
    assert(buffer.get(38, 4).style.bg.contains(Color.Rgb(255, 0, 0)))
    val area   = Rect(0, 0, 45, 17)
    assert(picker.channelAt(area, 40, 1).contains(ColorChannel.Green))
    assert(picker.channelAt(area, 37, 1).isEmpty) // the gap
    assert(picker.channelAt(area, 40, 3).isEmpty) // under the fields
    assert(ColorPicker().channelAt(area, 40, 1).isEmpty)

  test("golden: Rgb mode with an invalid green entry focused"):
    val state  = ColorPickerState(196)
    state.focusNextField()
    state.focusNextField()
    state.typeDigit(9)
    state.typeDigit(9)
    state.typeDigit(9)
    val buffer = rendered(ColorPicker(mode = ColorPickerMode.Rgb), state, 45, 17)
    GoldenFrames.assertMatchesStyled("color-picker-rgb", buffer)
