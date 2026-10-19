package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Rect, RgbaImage}

import org.scalatest.funsuite.AnyFunSuite

/** The kitty graphics protocol encoder, read against the protocol documentation
  * (https://sw.kovidgoyal.net/kitty/graphics-protocol/): an APC string
  * `ESC _ G <control keys> ; <base64 payload> ESC \`, RGBA data as `f=32` with its pixel size in `s`/`v`, the cell box
  * in `c`/`r`, `C=1` so the cursor stays put, `q=2` so the terminal never answers into the input stream, and payloads
  * over 4096 base64 bytes split into `m=1` chunks with an `m=0` last one.
  */
final class KittyGraphicsSpec extends AnyFunSuite:

  private val Esc = "\u001b"

  // red, green / blue, white — each fully opaque
  private val twoByTwo = RgbaImage
    .fromRgba(
      2,
      2,
      IArray[Byte](-1, 0, 0, -1, 0, -1, 0, -1, 0, 0, -1, -1, -1, -1, -1, -1),
    )
    .toOption
    .get

  test("a 2x2 RGBA image is one transmit-and-display string with the documented keys"):
    // base64 of ff0000ff 00ff00ff 0000ffff ffffffff, computed outside this code base
    assert(
      KittyGraphics.display(twoByTwo, id = 7, columns = 3, rows = 2) ==
        s"${Esc}_Ga=T,f=32,s=2,v=2,i=7,c=3,r=2,C=1,q=2;/wAA/wD/AP8AAP///////w==$Esc\\"
    )

  test("a payload past 4096 base64 bytes is split into m=1 chunks ending with an m=0 one"):
    val wide     = RgbaImage.fromRgba(2000, 1, IArray.fill[Byte](8000)(1)).toOption.get
    val encoded  = KittyGraphics.display(wide, id = 1, columns = 10, rows = 1)
    val chunks   = encoded.split(s"$Esc\\\\").toSeq
    assert(chunks.size == 3)
    assert(chunks(0).startsWith(s"${Esc}_Ga=T,f=32,s=2000,v=1,i=1,c=10,r=1,C=1,q=2,m=1;"))
    assert(chunks(1).startsWith(s"${Esc}_Gm=1;"))
    assert(chunks(2).startsWith(s"${Esc}_Gm=0;"))
    val payloads = chunks.map(_.dropWhile(_ != ';').drop(1))
    assert(payloads.take(2).forall(_.length == 4096))
    // 8000 bytes of 0x01 encode as "AQEB" repeated; 10668 characters, the last pair padded to "AQE="
    assert(payloads.mkString == "AQEB" * 2666 + "AQE=")

  test("a source rectangle goes out as the documented x, y, w, h keys, in pixels, before the id"):
    val shown = KittyGraphics.display("AAAA", 4, 6, 3, 2, 2, Some(Rect(0, 2, 4, 4)))
    assert(shown == s"${Esc}_Ga=T,f=32,s=4,v=6,x=0,y=2,w=4,h=4,i=3,c=2,r=2,C=1,q=2;AAAA$Esc\\")

  test("deleting an image frees it and all its placements, quietly"):
    assert(KittyGraphics.delete(7) == s"${Esc}_Ga=d,d=I,i=7,q=2$Esc\\")

  test("the probe is the documented 1x1 query"):
    assert(KittyGraphics.Query == s"${Esc}_Gi=31,s=1,v=1,a=q,t=d,f=24;AAAA$Esc\\")
