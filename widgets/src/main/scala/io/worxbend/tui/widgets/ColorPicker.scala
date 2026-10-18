package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Color, Measured, Rect, StatefulWidget, Style}

import java.util.Locale

/** Caller-owned state of a [[ColorPicker]]: the palette index under the cursor and the colour last chosen.
  *
  * The cursor moves over [[ColorPicker.Grid]], so Up/Down keep the column where the row is long enough and clamp to the
  * row's last swatch where it is not, and no move wraps.
  *
  * Render-thread-only, and mutating it does not by itself schedule a frame; the DSL's `colorPicker` node mutates it
  * from its key handler, which repaints.
  *
  * @param initial
  *   the xterm palette index the cursor starts on, clamped to `0..255`
  */
final class ColorPickerState(initial: Int = 0):
  private var row: Int    = 0
  private var column: Int = 0
  moveTo(initial)

  /** The colour last confirmed with [[choose]]; `None` until one is. */
  var chosen: Option[Color] = None

  /** The xterm palette index under the cursor. */
  def index: Int = ColorPicker.Grid(row)(column)

  def moveLeft(): Unit  = column = math.max(0, column - 1)
  def moveRight(): Unit = column = math.min(ColorPicker.Grid(row).size - 1, column + 1)
  def moveUp(): Unit    = moveRow(-1)
  def moveDown(): Unit  = moveRow(1)

  /** Puts the cursor on palette index `target`, clamped to `0..255` — where a click on a swatch lands. */
  def moveTo(target: Int): Unit =
    val entry = math.max(0, math.min(255, target))
    row = ColorPicker.Grid.indexWhere(_.contains(entry))
    column = ColorPicker.Grid(row).indexOf(entry)

  /** Confirms the cursor's swatch: records it in [[chosen]] and returns it. */
  def choose(): Color =
    val color = Color.Indexed(index)
    chosen = Some(color)
    color

  private def moveRow(delta: Int): Unit =
    row = math.max(0, math.min(ColorPicker.Grid.size - 1, row + delta))
    column = math.min(column, ColorPicker.Grid(row).size - 1)

/** A palette grid over the 256 xterm colours: the sixteen system colours, the 6×6×6 cube, and the 24-step grey ramp,
  * each swatch two cells wide in its own background, with `[]` on the cursor's swatch and an optional caption row under
  * the grid naming the index and its approximate RGB value — `196  #ff0000`.
  *
  * The grid is [[ColorPicker.Grid]]: 16 rows, the longest 18 swatches, so the picker measures 36 columns by 17 rows (16
  * without the caption) and clips like anything else in less. The chosen value is a `Color.Indexed`, which every
  * 256-colour terminal draws exactly as the swatch looked.
  *
  * @param showCaption
  *   whether the caption row is drawn under the grid
  * @param style
  *   the caption's style
  * @param markerStyle
  *   the `[]` marker's style, layered over the swatch's background; its foreground defaults to black or white,
  *   whichever stands out against the swatch
  */
final case class ColorPicker(
    showCaption: Boolean = true,
    style: Style = Style.Default,
    markerStyle: Style = Style.Default.bold,
) extends StatefulWidget[ColorPickerState]
    with Measured:

  override def widthAt(height: Int): Option[Int] = Some(ColorPicker.Grid.map(_.size).max * ColorPicker.SwatchWidth)
  override def heightAt(width: Int): Option[Int] = Some(ColorPicker.Grid.size + (if showCaption then 1 else 0))

  def render(area: Rect, buffer: Buffer, state: ColorPickerState): Unit =
    if !area.isEmpty then
      ColorPicker.Grid.zipWithIndex.foreach { (entries, row) =>
        val y = area.y + row
        if y < area.bottom then
          entries.zipWithIndex.foreach { (entry, column) =>
            val x = area.x + column * ColorPicker.SwatchWidth
            if x + ColorPicker.SwatchWidth <= area.right then
              val swatch = Style.Default.withBg(Color.Indexed(entry))
              if entry == state.index then
                buffer.setString(x, y, "[]", swatch.withFg(ColorPicker.contrast(entry)).patch(markerStyle))
              else buffer.setString(x, y, "  ", swatch)
          }
      }
      val captionY = area.y + ColorPicker.Grid.size
      if showCaption && captionY < area.bottom then
        val (r, g, b) = Color.approximateRgb(Color.Indexed(state.index))
        val caption   = String.format(Locale.ROOT, "%d  #%02x%02x%02x", state.index, r, g, b)
        buffer.setString(area.x, captionY, caption, style, area.width)

object ColorPicker:

  /** Columns one swatch takes: two, so a swatch is roughly square in a cell twice as tall as it is wide. */
  private[widgets] val SwatchWidth = 2

  /** The palette as rows of xterm indices, top to bottom: the system colours in two rows of eight; the cube in twelve
    * rows of eighteen, two rows per red level, each row three green levels of six blue swatches; the grey ramp in two
    * rows of twelve. Every index `0..255` appears exactly once.
    */
  val Grid: Vector[Vector[Int]] =
    val system = Vector((0 to 7).toVector, (8 to 15).toVector)
    val cube   = (0 until 12).toVector.map { row =>
      val red = row / 2
      (0 until 18).toVector.map(cell => 16 + red * 36 + ((row % 2) * 3 + cell / 6) * 6 + cell % 6)
    }
    val greys  = Vector((232 to 243).toVector, (244 to 255).toVector)
    system ++ cube ++ greys

  /** Black over a light swatch and white over a dark one, by the swatch's approximate relative luminance. */
  private def contrast(entry: Int): Color =
    val (r, g, b) = Color.approximateRgb(Color.Indexed(entry))
    if 0.299 * r + 0.587 * g + 0.114 * b > 140 then Color.Black else Color.White
