package io.worxbend.tui.widgets

import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, DataOutputStream}
import java.lang.management.ManagementFactory
import java.util.zip.{CRC32, DeflaterOutputStream}

final class PngLimitsSpec extends AnyFunSuite:

  private def png(
      width: Int,
      height: Int,
      pixels: Array[Byte],
      depth: Int = 16,
      kind: Int = 6,
      padding: Int = 0,
  ): Array[Byte] =
    val out                                           = ByteArrayOutputStream()
    val data                                          = DataOutputStream(out)
    data.write(Array(137, 80, 78, 71, 13, 10, 26, 10).map(_.toByte))
    def chunk(name: String, bytes: Array[Byte]): Unit =
      val tag = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
      val crc = CRC32()
      crc.update(tag)
      crc.update(bytes)
      data.writeInt(bytes.length)
      data.write(tag)
      data.write(bytes)
      data.writeInt(crc.getValue.toInt)
    val header                                        = ByteArrayOutputStream()
    val fields                                        = DataOutputStream(header)
    fields.writeInt(width)
    fields.writeInt(height)
    fields.write(Array(depth, kind, 0, 0, 0).map(_.toByte))
    chunk("IHDR", header.toByteArray)
    val zipped                                        = ByteArrayOutputStream()
    val deflater                                      = DeflaterOutputStream(zipped)
    deflater.write(pixels)
    deflater.close()
    chunk("IDAT", zipped.toByteArray ++ new Array[Byte](padding))
    chunk("IEND", Array.emptyByteArray)
    out.toByteArray

  test("a tiny lying header never allocates its declared raster"):
    val bytes           = png(1024, 1024, Array.emptyByteArray)
    (0 until 10).foreach(_ => assert(ImageLoader.decodePng(bytes).isLeft))
    val bean            = ManagementFactory.getThreadMXBean match
      case allocated: com.sun.management.ThreadMXBean => allocated
      case _                                          => fail("allocation accounting requires a HotSpot ThreadMXBean")
    val thread          = Thread.currentThread().threadId()
    val before          = bean.getThreadAllocatedBytes(thread)
    assert(ImageLoader.decodePng(bytes).isLeft)
    val allocated       = bean.getThreadAllocatedBytes(thread) - before
    info(s"lying PNG: ${bytes.length} input bytes, $allocated allocated bytes")
    assert(allocated < 1024 * 1024, s"header-driven allocation: $allocated")
    // Padding passes the conservative compressed-size ceiling but is not actual raster data.
    val padded          = png(1024, 1024, Array.emptyByteArray, padding = 9000)
    val beforePadded    = bean.getThreadAllocatedBytes(thread)
    assert(ImageLoader.decodePng(padded).isLeft)
    val allocatedPadded = bean.getThreadAllocatedBytes(thread) - beforePadded
    info(s"padded lying PNG: ${padded.length} input bytes, $allocatedPadded allocated bytes")
    assert(allocatedPadded < 1024 * 1024)

  test("all five row filters reconstruct known greyscale samples in place"):
    val raw      = Array[Byte](0, 10, 20, 1, 30, 10, 2, 20, 20, 3, 45, 15, 4, 20, 10)
    val image    = ImageLoader.decodePng(png(2, 5, raw, depth = 8, kind = 0)).toOption.get
    val expected = Seq(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
    assert((0 until 5).flatMap(y => (0 until 2).map(x => image.color(x, y).r)) == expected)

  test("oversized, malformed and excess raster inputs are recoverable errors"):
    assert(ImageLoader.decodePng(new Array[Byte](32 * 1024 * 1024 + 1)).left.exists(_.contains("encoded-input limit")))
    assert(ImageLoader.decodePng(png(8192, 8192, Array.emptyByteArray)).isLeft)
    assert(ImageLoader.decodePng(png(-1, 2, Array.emptyByteArray)).isLeft)
    assert(ImageLoader.decodePng(png(1, 1, Array[Byte](0, 1), depth = 8)).isLeft)
    assert(ImageLoader.decodePng(png(1, 1, Array[Byte](5, 1, 2, 3, 4), depth = 8)).isLeft)
    assert(ImageLoader.decodePng(png(1, 1, new Array[Byte](10000), depth = 8)).isLeft)
    val valid = png(1, 1, Array[Byte](0, -1, 0, 0, -1), depth = 8)
    assert(ImageLoader.decodePng(valid).map(_.alpha(0, 0)) == Right(255))
    (0 until valid.length).foreach(size => assert(ImageLoader.decodePng(valid.take(size)).isLeft, s"prefix $size"))
