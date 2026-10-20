package io.worxbend.tui.widgets

import io.worxbend.tui.core.RgbaImage

import java.io.ByteArrayOutputStream
import java.util.zip.{DataFormatException, Inflater}

/** A PNG reader (ISO/IEC 15948) built on `java.util.zip` alone, so a native image can load pictures without linking
  * `java.desktop` — under GraalVM `javax.imageio` needs AWT's JNI metadata, and without it the first decoded picture
  * aborts the process.
  *
  * Covers what pictures for a terminal are saved as: greyscale, truecolour, palette, greyscale-with-alpha and
  * truecolour-with-alpha, at 8 bits per channel (16-bit channels are read through their high byte; 1-, 2- and 4-bit
  * greyscale and palette are unpacked), with all five row filters and palette transparency (`tRNS`). Interlaced (Adam7)
  * files are refused with a `Left` — re-save without interlacing, or decode with [[ImageIoLoader.fromBytes]] on the
  * JVM.
  *
  * Pure and thread-safe; malformed, unsupported and over-budget inputs return `Left` (VM failures are not caught).
  */
private[widgets] object PngDecoder:

  private val Signature: Seq[Byte] = Seq(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

  /** Conservative terminal-picture budgets; neither encoded bytes nor an IHDR can request an unbounded raster. */
  private[widgets] val MaxInputBytes: Int = 32 * 1024 * 1024
  private val MaxPixels: Long             = 4L * 1024 * 1024
  private val MaxInflatedBytes: Long      = 32L * 1024 * 1024

  private final case class Header(width: Int, height: Int, bitDepth: Int, colorType: Int, interlaced: Boolean):
    def channels: Int = colorType match
      case 0 => 1
      case 2 => 3
      case 3 => 1
      case 4 => 2
      case _ => 4

    def bitsPerPixel: Int  = channels * bitDepth
    def bytesPerPixel: Int = math.max(1, bitsPerPixel / 8)
    def stride: Int        = ((width.toLong * bitsPerPixel + 7) / 8).toInt

  def decode(bytes: Array[Byte]): Either[String, RgbaImage] =
    if bytes.length > MaxInputBytes then Left("PNG exceeds the 32 MiB encoded-input limit")
    else if bytes.length < 8 || bytes.take(8).toSeq != Signature then Left("not a PNG file: the signature is missing")
    else
      try chunks(bytes).flatMap(assemble)
      catch case _: DataFormatException => Left("corrupt PNG: the image data does not inflate")

  /** Every chunk after the signature as (type, data), up to and including `IEND`; `Left` if the file is cut short. */
  private def chunks(bytes: Array[Byte]): Either[String, Vector[(String, Array[Byte])]] =
    val out    = Vector.newBuilder[(String, Array[Byte])]
    var offset = 8
    var ended  = false
    var failed = Option.empty[String]
    while !ended && failed.isEmpty do
      if offset + 8 > bytes.length then failed = Some("truncated PNG: a chunk header is cut off")
      else
        val length = readInt(bytes, offset)
        val kind   = String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1)
        if length < 0 || offset.toLong + 12 + length > bytes.length then
          failed = Some(s"truncated PNG: the $kind chunk is cut off")
        else
          out += ((kind, java.util.Arrays.copyOfRange(bytes, offset + 8, offset + 8 + length)))
          offset += 12 + length
          ended = kind == "IEND"
    failed.toLeft(out.result())

  private def assemble(all: Vector[(String, Array[Byte])]): Either[String, RgbaImage] =
    for
      ihdr   <- all.collectFirst { case ("IHDR", data) => data }.toRight("corrupt PNG: no IHDR chunk")
      header <- parseHeader(ihdr)
      _ <- Either.cond(!header.interlaced, (), "interlaced PNG files are not supported; re-save without interlacing")
      palette = all.collectFirst { case ("PLTE", data) => data }.getOrElse(Array.emptyByteArray)
      alphas  = all.collectFirst { case ("tRNS", data) => data }.getOrElse(Array.emptyByteArray)
      _   <- Either.cond(header.colorType != 3 || palette.nonEmpty, (), "corrupt PNG: a palette image has no PLTE")
      raw <- inflate(all.collect { case ("IDAT", data) => data }, header)
      _   <- Either.cond(
        (0 until header.height).forall(row => (raw(row * (header.stride + 1)) & 0xff) <= 4),
        (),
        "corrupt PNG: invalid row filter",
      )
    yield toImage(header, unfilter(raw, header), palette, alphas)

  private def parseHeader(data: Array[Byte]): Either[String, Header] =
    if data.length < 13 then Left("corrupt PNG: IHDR is too short")
    else
      val header = Header(readInt(data, 0), readInt(data, 4), data(8) & 0xff, data(9) & 0xff, (data(12) & 0xff) == 1)
      val depths = header.colorType match
        case 0         => Set(1, 2, 4, 8, 16)
        case 3         => Set(1, 2, 4, 8)
        case 2 | 4 | 6 => Set(8, 16)
        case _         => Set.empty[Int]
      if header.width <= 0 || header.height <= 0 then Left("corrupt PNG: the image has no pixels")
      else if header.width.toLong * header.height > MaxPixels then Left("PNG exceeds the 4 Mi-pixel limit")
      else if (header.stride.toLong + 1) * header.height > MaxInflatedBytes then
        Left("PNG exceeds the 32 MiB inflated-raster limit")
      else if data(10) != 0 || data(11) != 0 || (data(12) & 0xff) > 1 then
        Left("unsupported PNG: compression, filter or interlace method")
      else if !depths.contains(header.bitDepth) then
        Left(s"unsupported PNG: colour type ${header.colorType} at ${header.bitDepth} bits")
      else Right(header)

  private def inflate(parts: Seq[Array[Byte]], header: Header): Either[String, Array[Byte]] =
    val expected   = (header.stride.toLong + 1) * header.height
    val compressed = parts.iterator.map(_.length.toLong).sum
    // DEFLATE cannot expand beyond 1032:1. Reject impossible headers before creating any raster storage.
    if expected > compressed * 1032L then Left("truncated PNG: declared raster exceeds the compressed-data ceiling")
    else
      val inflater = Inflater()
      try
        // Feed chunks directly: no second copy of the compressed input. Grow only as real bytes inflate.
        val chunks = parts.iterator
        val out    = ByteArrayOutputStream()
        val block  = new Array[Byte](8192)
        var filled = 0L
        var stuck  = false
        while !inflater.finished() && !stuck && filled <= expected do
          if inflater.needsInput() && chunks.hasNext then inflater.setInput(chunks.next())
          val read = inflater.inflate(block)
          filled += read
          if filled <= expected then out.write(block, 0, read)
          if read == 0 && !inflater.finished() &&
            (inflater.needsDictionary() || !inflater.needsInput() || !chunks.hasNext)
          then stuck = true
        if filled > expected then Left("corrupt PNG: excess image data")
        else if filled != expected || !inflater.finished() then Left("truncated PNG: the image data ends early")
        else Right(out.toByteArray)
      finally inflater.end()

  /** Undoes the per-row filters in place; the result is `height` rows of `stride` bytes, filter bytes dropped. */
  private def unfilter(raw: Array[Byte], header: Header): Array[Byte] =
    val stride = header.stride
    val bpp    = header.bytesPerPixel
    var row    = 0
    while row < header.height do
      val filter = raw(row * (stride + 1)) & 0xff
      val source = row * (stride + 1) + 1
      val target = row * stride
      var i      = 0
      while i < stride do
        val x     = raw(source + i) & 0xff
        val a     = if i >= bpp then raw(target + i - bpp) & 0xff else 0
        val b     = if row > 0 then raw(target - stride + i) & 0xff else 0
        val c     = if row > 0 && i >= bpp then raw(target - stride + i - bpp) & 0xff else 0
        val value = filter match
          case 1 => x + a
          case 2 => x + b
          case 3 => x + (a + b) / 2
          case 4 => x + paeth(a, b, c)
          case _ => x
        raw(target + i) = value.toByte
        i += 1
      row += 1
    raw

  private def paeth(a: Int, b: Int, c: Int): Int =
    val p  = a + b - c
    val pa = math.abs(p - a)
    val pb = math.abs(p - b)
    val pc = math.abs(p - c)
    if pa <= pb && pa <= pc then a else if pb <= pc then b else c

  private def toImage(header: Header, data: Array[Byte], palette: Array[Byte], alphas: Array[Byte]): RgbaImage =
    val stride                          = header.stride
    val wide                            = header.bitDepth == 16
    def sample(y: Int, index: Int): Int =
      if wide then data(y * stride + index * 2) & 0xff
      else if header.bitDepth == 8 then data(y * stride + index) & 0xff
      else
        // a packed 1-, 2- or 4-bit sample, most significant first
        val bit   = index * header.bitDepth
        val byte  = data(y * stride + bit / 8) & 0xff
        val shift = 8 - header.bitDepth - bit % 8
        (byte >> shift) & ((1 << header.bitDepth) - 1)
    def grey(level: Int): Int           =
      if header.bitDepth >= 8 then level else level * 255 / ((1 << header.bitDepth) - 1)
    RgbaImage.tabulate(header.width, header.height) { (x, y) =>
      val c = header.channels
      header.colorType match
        case 0 =>
          val level = grey(sample(y, x))
          0xff000000 | (level << 16) | (level << 8) | level
        case 2 => argb(255, sample(y, x * c), sample(y, x * c + 1), sample(y, x * c + 2))
        case 3 =>
          val entry = sample(y, x)
          val alpha = if entry < alphas.length then alphas(entry) & 0xff else 255
          if entry * 3 + 2 >= palette.length then argb(alpha, 0, 0, 0)
          else argb(alpha, palette(entry * 3) & 0xff, palette(entry * 3 + 1) & 0xff, palette(entry * 3 + 2) & 0xff)
        case 4 =>
          val level = sample(y, x * c)
          argb(sample(y, x * c + 1), level, level, level)
        case _ => argb(sample(y, x * c + 3), sample(y, x * c), sample(y, x * c + 1), sample(y, x * c + 2))
    }

  private def argb(a: Int, r: Int, g: Int, b: Int): Int = (a << 24) | (r << 16) | (g << 8) | b

  private def readInt(bytes: Array[Byte], offset: Int): Int =
    ((bytes(offset) & 0xff) << 24) | ((bytes(offset + 1) & 0xff) << 16) | ((bytes(offset + 2) & 0xff) << 8) |
      (bytes(offset + 3) & 0xff)
