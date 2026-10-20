package io.worxbend.tui.widgets

import io.worxbend.tui.core.Color

import org.scalatest.funsuite.AnyFunSuite

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO

/** Decoding picture files into an [[io.worxbend.tui.core.RgbaImage]]. */
final class ImageLoaderSpec extends AnyFunSuite:

  private def png(argb: Int*): Array[Byte] =
    val picture = BufferedImage(argb.size, 1, BufferedImage.TYPE_INT_ARGB)
    argb.zipWithIndex.foreach((pixel, x) => picture.setRGB(x, 0, pixel))
    val out     = ByteArrayOutputStream()
    assert(ImageIO.write(picture, "png", out))
    out.toByteArray

  test("a PNG decodes to its pixels, alpha included"):
    val image = ImageIoLoader.fromBytes(png(0xffff0000, 0x4000ff00)).toOption.get
    assert(image.width == 2 && image.height == 1)
    assert(image.color(0, 0) == Color.Rgb(255, 0, 0) && image.alpha(0, 0) == 255)
    assert(image.color(1, 0) == Color.Rgb(0, 255, 0) && image.alpha(1, 0) == 0x40)

  test("the explicit JVM adapter retains JPEG, GIF and BMP decoding"):
    Seq("jpeg", "gif", "bmp").foreach { format =>
      val picture = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
      val out     = ByteArrayOutputStream()
      assert(ImageIO.write(picture, format, out), s"fixture writer for $format")
      val decoded = ImageIoLoader.fromBytes(out.toByteArray)
      assert(decoded.exists(image => image.width == 3 && image.height == 2), s"$format: $decoded")
    }

  test("a file decodes the same as its bytes"):
    val file = Files.createTempFile("glyphora-image", ".png")
    try
      Files.write(file, png(0xff0000ff))
      assert(ImageIoLoader.fromFile(file).map(_.color(0, 0)) == Right(Color.Rgb(0, 0, 255)))
    finally Files.deleteIfExists(file): Unit

  test("bytes that are no picture are a Left, not a throw"):
    assert(ImageIoLoader.fromBytes("not a picture".getBytes).isLeft)

  test("a missing file is a Left naming it"):
    val missing = java.nio.file.Path.of("/nonexistent/glyphora.png")
    assert(ImageIoLoader.fromFile(missing).left.exists(_.contains("glyphora.png")))

  // ------------------------------------------------------------------ the built-in PNG decoder

  /** A `width` x `height` picture of `kind` with a pattern busy enough that the writer picks varied row filters. */
  private def pattern(kind: Int, width: Int = 7, height: Int = 5): BufferedImage =
    val picture = BufferedImage(width, height, kind)
    for y <- 0 until height; x <- 0 until width do
      val alpha = if kind == BufferedImage.TYPE_INT_ARGB then (x * 37 + y * 11) % 256 else 255
      picture.setRGB(x, y, (alpha << 24) | ((x * 40) % 256 << 16) | ((y * 60) % 256 << 8) | ((x * y * 9) % 256))
    picture

  private def encoded(picture: BufferedImage): Array[Byte] =
    val out = ByteArrayOutputStream()
    assert(ImageIO.write(picture, "png", out))
    out.toByteArray

  private def assertSamePixels(picture: BufferedImage): Unit =
    val decoded = ImageLoader.decodePng(encoded(picture))
    assert(decoded.isRight, decoded)
    val image   = decoded.toOption.get
    assert(image.width == picture.getWidth && image.height == picture.getHeight)
    for y <- 0 until picture.getHeight; x <- 0 until picture.getWidth do
      // a grey BufferedImage converts its stored byte through a linear colour space in getRGB; the file holds the byte
      val argb =
        if picture.getType == BufferedImage.TYPE_BYTE_GRAY then
          val level = picture.getRaster.getSample(x, y, 0)
          0xff000000 | (level << 16) | (level << 8) | level
        else picture.getRGB(x, y)
      val rgb  = Color.Rgb((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff)
      assert(image.color(x, y) == rgb && image.alpha(x, y) == ((argb >>> 24) & 0xff), s"pixel ($x, $y)")

  test("the built-in decoder reads truecolour with alpha"):
    assertSamePixels(pattern(BufferedImage.TYPE_INT_ARGB))

  test("the built-in decoder reads truecolour"):
    assertSamePixels(pattern(BufferedImage.TYPE_INT_RGB))

  test("the built-in decoder reads greyscale"):
    assertSamePixels(pattern(BufferedImage.TYPE_BYTE_GRAY))

  test("the built-in decoder reads a palette"):
    assertSamePixels(pattern(BufferedImage.TYPE_BYTE_INDEXED, width = 16, height = 3))

  test("the built-in decoder reads a picture large enough to need several deflate blocks"):
    assertSamePixels(pattern(BufferedImage.TYPE_INT_ARGB, width = 300, height = 200))

  test("the built-in decoder refuses what is not a PNG"):
    assert(ImageLoader.decodePng("GIF89a".getBytes).left.exists(_.contains("PNG")))

  test("the built-in decoder refuses a truncated file instead of throwing"):
    val bytes = encoded(pattern(BufferedImage.TYPE_INT_RGB))
    assert(ImageLoader.decodePng(bytes.take(bytes.length / 2)).isLeft)

  test("readPng reads a file with the built-in decoder"):
    val file = Files.createTempFile("glyphora-image", ".png")
    try
      Files.write(file, encoded(pattern(BufferedImage.TYPE_INT_RGB)))
      assert(ImageLoader.readPng(file).map(_.width) == Right(7))
    finally Files.deleteIfExists(file): Unit
