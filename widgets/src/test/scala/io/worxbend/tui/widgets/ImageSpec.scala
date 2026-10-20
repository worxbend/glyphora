package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Color, Rect}

import org.scalatest.funsuite.AnyFunSuite

final class ImageSpec extends AnyFunSuite:

  private val red: Color.Rgb  = Color.Rgb(255, 0, 0)
  private val blue: Color.Rgb = Color.Rgb(0, 0, 255)

  test("each cell packs two vertical pixels as fg/bg of a half block"):
    val image  = Image(Vector(Vector(red), Vector(blue)))
    val buffer = Buffer(Rect(0, 0, 1, 1))
    image.render(buffer.area, buffer)
    val cell   = buffer.get(0, 0)
    assert(cell.symbol == "▀")
    assert(cell.style.fg.contains(red))
    assert(cell.style.bg.contains(blue))

  test("nearest-neighbor sampling scales the image to the area"):
    val image  = Image(Vector.fill(4)(Vector.fill(4)(red)))
    val buffer = Buffer(Rect(0, 0, 2, 1))
    image.render(buffer.area, buffer)
    assert((0 until 2).forall(x => buffer.get(x, 0).style.fg.contains(red)))

  test("fromFile decodes a generated png"):
    val file  = java.nio.file.Files.createTempFile("glyphora", ".png")
    val awt   = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
    awt.setRGB(0, 0, 0xff0000)
    awt.setRGB(1, 1, 0x0000ff)
    javax.imageio.ImageIO.write(awt, "png", file.toFile)
    val image = ImageIoLoader.imageFromFile(file)
    assert(image.exists(_.pixels(0)(0) == red))
    assert(image.exists(_.pixels(1)(1) == blue))

  test("fromFile reports unreadable files as Left"):
    assert(ImageIoLoader.imageFromFile(java.nio.file.Path.of("/no/such/file.png")).isLeft)
