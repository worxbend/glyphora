package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Color, Measured, Rect, RgbaImage, Style, Widget}

/** How a [[PixelImage]] fits its picture into the area it is given. */
enum ImageFit:

  /** The largest box with the picture's own shape, centred in the area — nothing is distorted. */
  case Contain

  /** The whole area, whatever its shape — the picture is stretched to fill it. */
  case Stretch

/** The appearance knobs of a [[PixelImage]]. `PixelImageOptions()` is the widget its bare constructor draws.
  *
  * @param fit
  *   how the picture fits the area; see [[ImageFit]].
  * @param cellAspect
  *   how many times taller than wide one terminal cell is, used to keep the picture's shape under [[ImageFit.Contain]].
  *   Two is what nearly every monospace font gives; a widget cannot ask the terminal, because widgets never see one.
  * @param background
  *   the colour translucent pixels are blended over in the half-block fallback. A picture protocol blends over the real
  *   cells instead.
  */
final case class PixelImageOptions(
    fit: ImageFit = ImageFit.Contain,
    cellAspect: Double = 2.0,
    background: Color.Rgb = Color.Rgb(0, 0, 0),
)

/** A picture drawn by the terminal itself — the kitty graphics protocol or iTerm2 inline images — with a half-block
  * rendering in the cells for every terminal that can do neither.
  *
  * Rendering does two things, in this order: it draws the half-block fallback (two pixels per cell, `▀` with the upper
  * pixel as foreground and the lower as background, as the [[Image]] widget does), then places the picture over those
  * same cells with [[io.worxbend.tui.core.Buffer.placeGraphic]]. Which one the user sees is the backend's decision,
  * made from what the terminal answered at start-up: a terminal that draws pictures gets the picture and the fallback
  * cells are never flushed; any other terminal gets the fallback as ordinary text. Something drawn over the picture
  * later in the frame — a popup, a menu — takes the picture down until it goes away again.
  *
  * The widget is backend-agnostic and immutable. `image` should be shared between frames rather than rebuilt: the
  * terminal caches each picture's encoding by value, and an unchanged picture in an unchanged box sends nothing.
  *
  * Measured under [[ImageFit.Contain]]: it knows how many rows a width needs to keep the picture's shape, and the
  * reverse. A stretched picture fits whatever it is given, so it cannot say.
  */
final case class PixelImage(image: RgbaImage, options: PixelImageOptions = PixelImageOptions())
    extends Widget
    with Measured:

  def render(area: Rect, buffer: Buffer): Unit =
    val box = boxIn(area)
    if !box.isEmpty then
      drawFallback(box, buffer)
      buffer.placeGraphic(box, image)

  override def heightAt(width: Int): Option[Int] =
    if options.fit == ImageFit.Stretch || width <= 0 then None else Some(rowsFor(width))

  override def widthAt(height: Int): Option[Int] =
    if options.fit == ImageFit.Stretch || height <= 0 then None else Some(columnsFor(height))

  /** The rows that keep the picture's shape at `columns` wide, at least one. */
  private def rowsFor(columns: Int): Int =
    math.max(1, math.round(columns.toDouble * image.height / image.width / aspect).toInt)

  /** The columns that keep the picture's shape at `rows` tall, at least one. */
  private def columnsFor(rows: Int): Int =
    math.max(1, math.round(rows.toDouble * aspect * image.width / image.height).toInt)

  /** The cell aspect actually used: a non-positive or non-finite option would divide by nothing, so it reads as 2. */
  private def aspect: Double =
    if options.cellAspect > 0 && !options.cellAspect.isInfinite then options.cellAspect else 2.0

  /** Where the picture goes inside `area`. */
  private def boxIn(area: Rect): Rect =
    if area.isEmpty then area
    else
      options.fit match
        case ImageFit.Stretch => area
        case ImageFit.Contain =>
          val (columns, rows) =
            val tall = rowsFor(area.width)
            if tall <= area.height then (area.width, tall)
            else (math.min(area.width, columnsFor(area.height)), area.height)
          Rect(area.x + (area.width - columns) / 2, area.y + (area.height - rows) / 2, columns, rows)

  private def drawFallback(box: Rect, buffer: Buffer): Unit =
    val pixelRows = box.height * 2
    var y         = 0
    while y < box.height do
      var x = 0
      while x < box.width do
        val upper = sample(x, y * 2, box.width, pixelRows)
        val lower = sample(x, y * 2 + 1, box.width, pixelRows)
        buffer.set(box.x + x, box.y + y, Cell("▀", Style(fg = Some(upper), bg = Some(lower))))
        x += 1
      y += 1

  /** The nearest pixel to target position `(column, row)` of a `targetWidth` x `targetHeight` grid, blended over the
    * background by its alpha.
    */
  private def sample(column: Int, row: Int, targetWidth: Int, targetHeight: Int): Color.Rgb =
    val sourceX                              = column * image.width / targetWidth
    val sourceY                              = row * image.height / targetHeight
    val Color.Rgb(red, green, blue)          = image.color(sourceX, sourceY)
    val alpha                                = image.alpha(sourceX, sourceY)
    val Color.Rgb(br, bg, bb)                = options.background
    def blend(channel: Int, under: Int): Int =
      (channel * alpha + math.max(0, math.min(255, under)) * (255 - alpha) + 127) / 255
    Color.Rgb(blend(red, br), blend(green, bg), blend(blue, bb))
