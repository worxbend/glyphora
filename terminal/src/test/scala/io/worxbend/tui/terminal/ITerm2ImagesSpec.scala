package io.worxbend.tui.terminal

import io.worxbend.tui.core.RgbaImage

import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

/** The iTerm2 inline-image encoder, read against iTerm2's documentation (https://iterm2.com/documentation-images.html):
  * `ESC ] 1337 ; File = <key=value;…> : <base64 file contents> BEL`, with `inline=1`, the file's byte `size`, the box
  * in cells as bare `width`/`height` numbers, and a PNG as the file.
  */
final class ITerm2ImagesSpec extends AnyFunSuite:

  private val Esc = "\u001b"
  private val Bel = "\u0007"

  private val image = RgbaImage.tabulate(3, 2)((x, y) => if (x + y) % 2 == 0 then 0xffff0000 else 0x800000ff)

  private def fileBytes(sequence: String): Array[Byte] =
    Base64.getDecoder.decode(sequence.dropWhile(_ != ':').drop(1).stripSuffix(Bel))

  test("the OSC 1337 header names an inline file of the payload's size in a box of cells"):
    val sequence = ITerm2Images.display(image, columns = 4, rows = 2)
    val size     = fileBytes(sequence).length
    assert(
      sequence.startsWith(
        s"$Esc]1337;File=inline=1;size=$size;width=4;height=2;preserveAspectRatio=0;doNotMoveCursor=1:"
      )
    )
    assert(sequence.endsWith(Bel))

  test("the payload is a PNG file"):
    val bytes = fileBytes(ITerm2Images.display(image, columns = 4, rows = 2))
    // the eight-byte PNG signature, ISO/IEC 15948 section 5.2
    assert(bytes.take(8).toSeq == Seq(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte))

  test("an independent PNG decoder reads back every pixel, alpha included"):
    val decoded = ImageIO.read(ByteArrayInputStream(PngEncoder.encode(image)))
    assert(decoded.getWidth == 3 && decoded.getHeight == 2)
    for y <- 0 until 2; x <- 0 until 3 do
      val expected = if (x + y) % 2 == 0 then 0xffff0000 else 0x800000ff
      assert(decoded.getRGB(x, y) == expected, s"pixel ($x, $y)")
