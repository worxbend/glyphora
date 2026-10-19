package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Color, Measured, Rect, StatefulWidget, Style, VersionedState}

import java.util.Locale

/** One channel of an RGB colour, in the order a [[ColorPicker]] in [[ColorPickerMode.Rgb]] lists its fields. */
enum ColorChannel:
  case Red, Green, Blue

  /** The field's one-letter label. */
  def label: String = toString.take(1)

/** Which part of a [[ColorPicker]] has the keyboard: the palette grid, or one of the RGB fields beside it. */
enum ColorPickerField:
  case Grid
  case Channel(channel: ColorChannel)

/** How a [[ColorPicker]] lets the user pick, and what it chooses. */
enum ColorPickerMode:

  /** The palette grid alone; the chosen colour is the swatch's `Color.Indexed`. */
  case Palette

  /** The palette grid with three numeric R/G/B fields beside it. The fields follow the grid cursor's approximate RGB
    * value until the user types into one; the chosen colour is the fields' `Color.Rgb`, and only while all three hold a
    * value in `0..255`.
    */
  case Rgb

/** Caller-owned state of a [[ColorPicker]]: the palette index under the cursor, the three RGB entry fields, which part
  * has the keyboard, and the colour last chosen.
  *
  * The cursor moves over [[ColorPicker.Grid]], so Up/Down keep the column where the row is long enough and clamp to the
  * row's last swatch where it is not, and no move wraps. Every grid move refills the RGB fields from the swatch's
  * approximate value and gives the keyboard back to the grid. An RGB field holds up to three typed digits; it is valid
  * when it is non-empty and at most `255`, and [[chooseRgb]] chooses only while all three are. The first digit typed
  * into a field that still shows the grid's value replaces that value rather than appending to it.
  *
  * Render-thread-only. Every mutation raises `version` (see [[io.worxbend.tui.core.Versioned]]), so a write made
  * outside the event path is painted too, with no `Signal` write.
  *
  * @param initial
  *   the xterm palette index the cursor starts on, clamped to `0..255`
  */
final class ColorPickerState(initial: Int = 0) extends VersionedState:
  private var row: Int                           = 0
  private var column: Int                        = 0
  private var entries: Map[ColorChannel, String] = Map.empty
  private var edited: Set[ColorChannel]          = Set.empty
  private var fieldValue: ColorPickerField       = ColorPickerField.Grid
  moveTo(initial)

  /** The part of the picker that has the keyboard. */
  def field: ColorPickerField = fieldValue

  /** The digits in `channel`'s field, as typed — possibly empty, possibly past `255`. */
  def channelText(channel: ColorChannel): String = entries.getOrElse(channel, "")

  /** `channel`'s value, or `None` while its field is empty or past `255`. */
  def channelValue(channel: ColorChannel): Option[Int] =
    channelText(channel).toIntOption.filter(value => value >= 0 && value <= ColorPicker.ChannelMax)

  /** Moves the keyboard one part on — grid, red, green, blue. `false`, with nothing changed, from the blue field. */
  def focusNextField(): Boolean = shiftField(1)

  /** Moves the keyboard one part back. `false`, with nothing changed, from the grid. */
  def focusPreviousField(): Boolean = shiftField(-1)

  /** Gives the keyboard to `target`. */
  def focusField(target: ColorPickerField): Unit =
    fieldValue = target
    touch()

  /** Types `digit` (`0..9`) into the focused RGB field; a fourth digit, a digit on the grid, or one outside `0..9` is
    * ignored.
    */
  def typeDigit(digit: Int): Unit = focusedChannel.foreach { channel =>
    if digit >= 0 && digit <= 9 then
      val current = if edited(channel) then channelText(channel) else ""
      if current.length < ColorPicker.ChannelDigits then edit(channel, current + digit.toString)
  }

  /** Deletes the last digit of the focused RGB field. */
  def deleteDigit(): Unit = focusedChannel.foreach(channel => edit(channel, channelText(channel).dropRight(1)))

  /** Moves the focused RGB field's value by `delta`, clamped to `0..255`. An empty or out-of-range entry steps from the
    * nearest valid value.
    */
  def stepChannel(delta: Int): Unit = focusedChannel.foreach { channel =>
    val from = channelText(channel).toIntOption.getOrElse(0)
    val to   = clampChannel(clampChannel(from).toLong + delta)
    edit(channel, to.toString)
  }

  /** Confirms the RGB fields: records `Color.Rgb` of the three in [[chosen]] and returns it — or returns `None`, with
    * nothing recorded, while any field is empty or past `255`.
    */
  def chooseRgb(): Option[Color] =
    val rgb = for
      r <- channelValue(ColorChannel.Red)
      g <- channelValue(ColorChannel.Green)
      b <- channelValue(ColorChannel.Blue)
    yield Color.Rgb(r, g, b)
    rgb.foreach(color => chosen = Some(color))
    rgb

  private def focusedChannel: Option[ColorChannel] = field match
    case ColorPickerField.Channel(channel) => Some(channel)
    case ColorPickerField.Grid             => None

  private def shiftField(delta: Int): Boolean =
    val order  = ColorPickerField.Grid +: ColorChannel.values.toSeq.map(ColorPickerField.Channel(_))
    val target = order.indexOf(field) + delta
    if target < 0 || target >= order.size then false
    else
      focusField(order(target))
      true

  private def edit(channel: ColorChannel, text: String): Unit =
    entries = entries.updated(channel, text)
    edited = edited + channel
    touch()

  private def clampChannel(value: Long): Int = math.max(0L, math.min(ColorPicker.ChannelMax.toLong, value)).toInt

  /** Refills the fields from the swatch under the cursor and hands the keyboard back to the grid. */
  private def followCursor(): Unit =
    val (r, g, b) = Color.approximateRgb(Color.Indexed(index))
    entries = Map(ColorChannel.Red -> r.toString, ColorChannel.Green -> g.toString, ColorChannel.Blue -> b.toString)
    edited = Set.empty
    fieldValue = ColorPickerField.Grid

  /** The colour last confirmed with [[choose]]; `None` until one is. */
  def chosen: Option[Color] = chosenValue

  def chosen_=(value: Option[Color]): Unit =
    chosenValue = value
    touch()

  private var chosenValue: Option[Color] = None

  /** The xterm palette index under the cursor. */
  def index: Int = ColorPicker.Grid(row)(column)

  def moveLeft(): Unit =
    column = math.max(0, column - 1)
    followCursor()
    touch()

  def moveRight(): Unit =
    column = math.min(ColorPicker.Grid(row).size - 1, column + 1)
    followCursor()
    touch()

  def moveUp(): Unit   = moveRow(-1)
  def moveDown(): Unit = moveRow(1)

  /** Puts the cursor on palette index `target`, clamped to `0..255` — where a click on a swatch lands. */
  def moveTo(target: Int): Unit =
    val entry = math.max(0, math.min(255, target))
    row = ColorPicker.Grid.indexWhere(_.contains(entry))
    column = ColorPicker.Grid(row).indexOf(entry)
    followCursor()
    touch()

  /** Confirms the cursor's swatch: records it in [[chosen]] and returns it. */
  def choose(): Color =
    val color = Color.Indexed(index)
    chosen = Some(color)
    color

  private def moveRow(delta: Int): Unit =
    row = math.max(0, math.min(ColorPicker.Grid.size - 1, row + delta))
    column = math.min(column, ColorPicker.Grid(row).size - 1)
    followCursor()
    touch()

/** A palette grid over the 256 xterm colours: the sixteen system colours, the 6×6×6 cube, and the 24-step grey ramp,
  * each swatch two cells wide in its own background, with `[]` on the cursor's swatch and an optional caption row under
  * the grid naming the index and its approximate RGB value — `196  #ff0000`.
  *
  * The grid is [[ColorPicker.Grid]]: 16 rows, the longest 18 swatches, so the picker measures 36 columns by 17 rows (16
  * without the caption) and clips like anything else in less. In [[ColorPickerMode.Palette]] the chosen value is a
  * `Color.Indexed`, which every 256-colour terminal draws exactly as the swatch looked. In [[ColorPickerMode.Rgb]] a
  * column of three fields — `R [255]`, `G [  0]`, `B [  0]` — and a preview swatch of their colour sit two columns to
  * the right of the grid (45 columns in all); the focused field is drawn in `highlightStyle` and an invalid one in
  * `invalidStyle`, and the preview is blank while any field is invalid.
  *
  * @param showCaption
  *   whether the caption row is drawn under the grid
  * @param mode
  *   palette only, or palette plus RGB entry fields; see [[ColorPickerMode]]
  * @param style
  *   the caption's and the RGB fields' style
  * @param markerStyle
  *   the `[]` marker's style, layered over the swatch's background; its foreground defaults to black or white,
  *   whichever stands out against the swatch
  * @param highlightStyle
  *   layered over the RGB field that has the keyboard
  * @param invalidStyle
  *   layered over an RGB field that is empty or past `255`
  */
final case class ColorPicker(
    showCaption: Boolean = true,
    mode: ColorPickerMode = ColorPickerMode.Palette,
    style: Style = Style.Default,
    markerStyle: Style = Style.Default.bold,
    highlightStyle: Style = Style.Default.reverse,
    invalidStyle: Style = Style.Default.withFg(Color.Red),
) extends StatefulWidget[ColorPickerState]
    with Measured:

  override def widthAt(height: Int): Option[Int] = Some(gridWidth + (mode match
    case ColorPickerMode.Palette => 0
    case ColorPickerMode.Rgb     => ColorPicker.FieldGap + ColorPicker.FieldWidth))
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
        buffer.setString(area.x, captionY, caption, style, math.min(area.width, gridWidth))
      if mode == ColorPickerMode.Rgb then renderFields(area, buffer, state)

  /** The RGB field drawn at `x`, `y` when the picker is rendered into `area` — `None` in [[ColorPickerMode.Palette]],
    * and for any cell that is not on one of the three field rows right of the grid. A click goes through this.
    */
  def channelAt(area: Rect, x: Int, y: Int): Option[ColorChannel] =
    val fieldX = area.x + gridWidth + ColorPicker.FieldGap
    val inside = mode == ColorPickerMode.Rgb && x >= fieldX && x < math.min(area.right, fieldX + ColorPicker.FieldWidth)
    ColorChannel.values.lift(y - area.y).filter(_ => inside && y >= area.y)

  private def gridWidth: Int = ColorPicker.Grid.map(_.size).max * ColorPicker.SwatchWidth

  /** The R/G/B column and the preview swatch under it, clipped to `area`. */
  private def renderFields(area: Rect, buffer: Buffer, state: ColorPickerState): Unit =
    val x     = area.x + gridWidth + ColorPicker.FieldGap
    val width = area.right - x
    if width > 0 then
      ColorChannel.values.zipWithIndex.foreach { (channel, row) =>
        val y = area.y + row
        if y < area.bottom then
          val text      = state.channelText(channel)
          val padded    = " " * math.max(0, ColorPicker.ChannelDigits - text.length) + text
          val valid     = state.channelValue(channel).isDefined
          val focused   = state.field == ColorPickerField.Channel(channel)
          val validated = if valid then style else style.patch(invalidStyle)
          val value     = if focused then validated.patch(highlightStyle) else validated
          buffer.setString(x, y, s"${channel.label} [", style, width)
          buffer.setString(x + 3, y, padded, value, math.max(0, width - 3))
          buffer.setString(x + 3 + ColorPicker.ChannelDigits, y, "]", style, math.max(0, width - 6))
      }
      val previewY = area.y + ColorChannel.values.length + 1
      if previewY < area.bottom then
        val preview = for
          r <- state.channelValue(ColorChannel.Red)
          g <- state.channelValue(ColorChannel.Green)
          b <- state.channelValue(ColorChannel.Blue)
        yield Style.Default.withBg(Color.Rgb(r, g, b))
        preview.foreach(swatch => buffer.setString(x, previewY, " " * ColorPicker.FieldWidth, swatch, width))

object ColorPicker:

  /** Columns one swatch takes: two, so a swatch is roughly square in a cell twice as tall as it is wide. */
  private[widgets] val SwatchWidth = 2

  /** The largest value an RGB field accepts, and the digits it holds. */
  private[widgets] val ChannelMax    = 255
  private[widgets] val ChannelDigits = 3

  /** Columns between the grid and the RGB fields, and the width of one `R [255]` field. */
  private[widgets] val FieldGap   = 2
  private[widgets] val FieldWidth = 7

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
