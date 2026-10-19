package io.worxbend.tui.core

/** A raster picture: `width` x `height` pixels, each four bytes — red, green, blue, alpha — in row-major order.
  *
  * The one pixel type every layer agrees on. The `PixelImage` widget samples it for its half-block fallback, a buffer
  * carries it in a [[GraphicPlacement]], and the terminal's kitty and iTerm2 encoders turn it into the escape sequence
  * that draws it. That is why it lives in `tui-core` and not beside the widget: the encoders sit in `tui-terminal`,
  * which cannot see `tui-widgets`.
  *
  * Immutable. The byte array is copied in by every constructor and never handed out mutable, so an image can be shared
  * across threads and frames freely — the terminal caches the encoded payload per image, keyed on this value's
  * structural equality.
  */
final class RgbaImage private (val width: Int, val height: Int, private val bytes: Array[Byte]):

  /** The raw `RGBA` bytes, `width * height * 4` of them, row-major. A read-only view; nothing is copied. */
  def rgbaBytes: IArray[Byte] = IArray.unsafeFromArray(bytes)

  /** A fresh, caller-owned copy of the RGBA bytes, for an encoder that needs a plain array. */
  def copyRgbaBytes: Array[Byte] = bytes.clone()

  /** The colour of pixel `(x, y)` without its alpha. Coordinates outside the image are clamped to its edge. */
  def color(x: Int, y: Int): Color.Rgb =
    val index = offset(x, y)
    Color.Rgb(bytes(index) & 0xff, bytes(index + 1) & 0xff, bytes(index + 2) & 0xff)

  /** The opacity of pixel `(x, y)`, `0` transparent to `255` opaque. Coordinates are clamped as in [[color]]. */
  def alpha(x: Int, y: Int): Int = bytes(offset(x, y) + 3) & 0xff

  private def offset(x: Int, y: Int): Int =
    val column = math.max(0, math.min(width - 1, x))
    val row    = math.max(0, math.min(height - 1, y))
    (row * width + column) * 4

  private lazy val contentHash: Int = java.util.Arrays.hashCode(bytes) * 31 + width

  /** Two images are equal when they have the same size and the same bytes. The hash is computed once and cached. */
  override def equals(other: Any): Boolean = other match
    case that: RgbaImage =>
      (this eq that) || (width == that.width && height == that.height && hashCode == that.hashCode &&
        java.util.Arrays.equals(bytes, that.bytes))
    case _               => false

  override def hashCode: Int = contentHash

  override def toString: String = s"RgbaImage(${width}x$height)"

object RgbaImage:

  /** An image over `rgba`, which must hold exactly `width * height * 4` bytes; `Left` names what is wrong otherwise. */
  def fromRgba(width: Int, height: Int, rgba: IArray[Byte]): Either[String, RgbaImage] =
    if width <= 0 || height <= 0 then Left(s"an image needs a positive size, not ${width}x$height")
    else if width.toLong * height * 4 != rgba.length then
      Left(s"a ${width}x$height RGBA image needs ${width.toLong * height * 4} bytes, not ${rgba.length}")
    else Right(RgbaImage(width, height, IArray.genericWrapArray(rgba).toArray))

  /** An image whose pixel `(x, y)` is `pixel(x, y)` as a packed `0xAARRGGBB` integer — the layout of
    * `java.awt.image.BufferedImage.getRGB`, so a decoded picture converts without reshuffling bytes.
    *
    * @throws IllegalArgumentException
    *   if either dimension is not positive — a programmer error, not a condition to recover from.
    */
  def tabulate(width: Int, height: Int)(pixel: (Int, Int) => Int): RgbaImage =
    require(width > 0 && height > 0, s"an image needs a positive size, not ${width}x$height")
    val bytes = new Array[Byte](width * height * 4)
    var y     = 0
    while y < height do
      var x = 0
      while x < width do
        val argb  = pixel(x, y)
        val index = (y * width + x) * 4
        bytes(index) = (argb >>> 16).toByte
        bytes(index + 1) = (argb >>> 8).toByte
        bytes(index + 2) = argb.toByte
        bytes(index + 3) = (argb >>> 24).toByte
        x += 1
      y += 1
    RgbaImage(width, height, bytes)

  /** An opaque image from rows of colours — the shape the half-block `Image` widget takes. Ragged rows are padded with
    * black; `Left` when there are no pixels at all.
    */
  def fromColors(rows: Seq[Seq[Color.Rgb]]): Either[String, RgbaImage] =
    val width = rows.map(_.size).maxOption.getOrElse(0)
    if rows.isEmpty || width == 0 then Left("an image needs at least one pixel")
    else
      Right(tabulate(width, rows.size) { (x, y) =>
        rows(y).lift(x) match
          case Some(Color.Rgb(r, g, b)) => 0xff000000 | ((r & 0xff) << 16) | ((g & 0xff) << 8) | (b & 0xff)
          case None                     => 0xff000000
      })
