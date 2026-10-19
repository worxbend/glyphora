package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Color, DiffDirective, Rect, RgbaImage}
import io.worxbend.tui.testsupport.BufferAssertions.rendered
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

/** [[PixelImage]]: a half-block fallback in the cells, a picture placement over them for a terminal that can draw it,
  * and a box shaped like the picture.
  */
final class PixelImageSpec extends AnyFunSuite:

  // four quadrants: red, green / blue, half-transparent white
  private val quadrants = RgbaImage.tabulate(4, 4) { (x, y) =>
    (x < 2, y < 2) match
      case (true, true)   => 0xffff0000
      case (false, true)  => 0xff00ff00
      case (true, false)  => 0xff0000ff
      case (false, false) => 0x80ffffff
  }

  test("with no protocol the cells hold a half-block rendering, the alpha blended over the background"):
    val buffer = rendered(PixelImage(quadrants), 4, 2)
    assert(buffer.get(0, 0).symbol == "▀")
    assert(buffer.get(0, 0).style.fg.contains(Color.Rgb(255, 0, 0)))
    assert(buffer.get(3, 1).style.fg.contains(Color.Rgb(128, 128, 128)))
    GoldenFrames.assertMatchesStyled("pixel-image-fallback", buffer)

  test("the picture is placed over exactly the cells of its fallback"):
    val buffer = rendered(PixelImage(quadrants), 4, 2)
    assert(buffer.graphics.map(p => (p.area, p.image)) == Seq((Rect(0, 0, 4, 2), quadrants)))
    assert(buffer.diffDirective(0, 0) == DiffDirective.Skip)

  test("contain keeps the picture's shape, centred in a wider area"):
    // a square picture is twice as many cells wide as tall at the default 2:1 cell aspect
    val buffer = rendered(PixelImage(quadrants), 10, 2)
    assert(buffer.graphics.map(_.area) == Seq(Rect(3, 0, 4, 2)))
    assert(buffer.get(0, 0).symbol == " ")

  test("stretch fills the whole area"):
    val buffer = rendered(PixelImage(quadrants, PixelImageOptions(fit = ImageFit.Stretch)), 10, 2)
    assert(buffer.graphics.map(_.area) == Seq(Rect(0, 0, 10, 2)))

  test("an empty area draws and places nothing"):
    val buffer = rendered(PixelImage(quadrants), 0, 0)
    assert(buffer.graphics.isEmpty)

  test("it measures the rows a width needs, and the columns a height needs"):
    assert(PixelImage(quadrants).heightAt(8) == Some(4))
    assert(PixelImage(quadrants).widthAt(3) == Some(6))
    assert(PixelImage(quadrants, PixelImageOptions(fit = ImageFit.Stretch)).heightAt(8).isEmpty)
