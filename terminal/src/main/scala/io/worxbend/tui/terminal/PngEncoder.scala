package io.worxbend.tui.terminal

import io.worxbend.tui.core.RgbaImage

import java.io.ByteArrayOutputStream
import java.util.zip.{CRC32, Deflater}

/** A minimal PNG writer (ISO/IEC 15948): one 8-bit RGBA image, no filtering, one zlib-compressed `IDAT` chunk.
  *
  * The iTerm2 inline-image protocol takes a file, not raw pixels, and PNG is the lossless format every implementation
  * of it decodes. `javax.imageio` could write one, but it lives in `java.desktop` and drags AWT into every native image
  * that shows a picture; `java.util.zip` is in `java.base`, so this costs a native binary nothing and needs no
  * configuration under `--no-fallback`.
  *
  * Pure and thread-safe: every call owns its own deflater, which is released before it returns.
  */
private[terminal] object PngEncoder:

  private val Signature: Array[Byte] = Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

  /** The bytes of a PNG file holding `image`. */
  def encode(image: RgbaImage): Array[Byte] =
    val out = ByteArrayOutputStream()
    out.write(Signature)
    chunk(out, "IHDR", header(image))
    chunk(out, "IDAT", compressed(image))
    chunk(out, "IEND", Array.emptyByteArray)
    out.toByteArray

  /** Width, height, bit depth 8, colour type 6 (truecolour with alpha), and the three zero method bytes. */
  private def header(image: RgbaImage): Array[Byte] =
    val bytes = ByteArrayOutputStream()
    writeInt(bytes, image.width)
    writeInt(bytes, image.height)
    bytes.write(Array[Byte](8, 6, 0, 0, 0))
    bytes.toByteArray

  /** Every scanline prefixed with filter type 0 (none), deflated as one zlib stream. */
  private def compressed(image: RgbaImage): Array[Byte] =
    val pixels   = image.copyRgbaBytes
    val stride   = image.width * 4
    val raw      = new Array[Byte]((stride + 1) * image.height)
    var row      = 0
    while row < image.height do
      System.arraycopy(pixels, row * stride, raw, row * (stride + 1) + 1, stride)
      row += 1
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    try
      deflater.setInput(raw)
      deflater.finish()
      val out    = ByteArrayOutputStream(raw.length / 2 + 64)
      val window = new Array[Byte](8192)
      while !deflater.finished() do
        val written = deflater.deflate(window)
        out.write(window, 0, written)
      out.toByteArray
    finally deflater.end()

  /** One chunk: length, type, data, and the CRC-32 of type and data. */
  private def chunk(out: ByteArrayOutputStream, kind: String, data: Array[Byte]): Unit =
    val typeBytes = kind.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
    writeInt(out, data.length)
    out.write(typeBytes)
    out.write(data)
    val crc       = CRC32()
    crc.update(typeBytes)
    crc.update(data)
    writeInt(out, crc.getValue.toInt)

  private def writeInt(out: ByteArrayOutputStream, value: Int): Unit =
    out.write((value >>> 24) & 0xff)
    out.write((value >>> 16) & 0xff)
    out.write((value >>> 8) & 0xff)
    out.write(value & 0xff)
