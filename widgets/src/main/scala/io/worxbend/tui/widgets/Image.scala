package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Color, Rect, Style, Widget}

import scala.util.control.NonFatal

/** A raster image rendered with half-block cells: every terminal cell shows two vertical pixels (`▀` with the upper
  * pixel as foreground and the lower as background), the universally supported protocol from the reference ecosystems.
  * For a picture the terminal draws in real pixels where it can (kitty graphics, iTerm2), use [[PixelImage]].
  *
  * `pixels` is row-major RGB; the image is scaled to the render area by nearest-neighbor sampling, preserving nothing
  * but coverage — pre-scale for quality.
  */
final case class Image(pixels: Vector[Vector[Color.Rgb]]) extends Widget:

  def render(area: Rect, buffer: Buffer): Unit =
    val sourceHeight = pixels.size
    val sourceWidth  = pixels.headOption.map(_.size).getOrElse(0)
    if !area.isEmpty && sourceHeight > 0 && sourceWidth > 0 then
      val targetRows = area.height * 2 // two pixels per cell vertically
      var y          = 0
      while y < area.height do
        var x = 0
        while x < area.width do
          val upper = sample(x, y * 2, area.width, targetRows, sourceWidth, sourceHeight)
          val lower = sample(x, y * 2 + 1, area.width, targetRows, sourceWidth, sourceHeight)
          buffer.set(area.x + x, area.y + y, Cell("▀", Style(fg = Some(upper), bg = Some(lower))))
          x += 1
        y += 1

  private def sample(
      column: Int,
      row: Int,
      targetWidth: Int,
      targetHeight: Int,
      sourceWidth: Int,
      sourceHeight: Int,
  ): Color.Rgb =
    val sourceY   = math.min(sourceHeight - 1, row * sourceHeight / targetHeight)
    val sourceRow = pixels(sourceY)
    // `sourceWidth` comes from the first row only, so a ragged `pixels` would index past a shorter row
    if sourceRow.isEmpty then Color.Rgb(0, 0, 0)
    else sourceRow(math.min(sourceRow.size - 1, column * sourceWidth / targetWidth))

object Image:

  /** Decodes an image file (PNG/JPEG/GIF via `javax.imageio`) into pixels.
    *
    * Kept out of the `Image` constructor path so apps that never load files (and native-image builds) do not link AWT;
    * native-image users of `fromFile` should verify their platform's `libawt` support.
    */
  def fromFile(path: java.nio.file.Path): Either[String, Image] =
    try
      // `ImageIO.read` reports an unsupported format by returning null rather than throwing
      Option(javax.imageio.ImageIO.read(path.toFile))
        .toRight(s"unsupported image format: $path")
        .map: decoded =>
          Image(Vector.tabulate(decoded.getHeight) { y =>
            Vector.tabulate(decoded.getWidth) { x =>
              val argb = decoded.getRGB(x, y)
              Color.Rgb((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff): Color.Rgb
            }
          })
    catch
      case NonFatal(error) =>
        Left(s"failed to read $path: ${Option(error.getMessage).getOrElse(error.getClass.getName)}")
