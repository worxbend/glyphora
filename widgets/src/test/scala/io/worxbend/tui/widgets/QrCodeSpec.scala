package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Color, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}

import org.scalatest.funsuite.AnyFunSuite

import scala.io.Source
import scala.util.Using

/** The QR encoder is checked against matrices produced by an independent encoder, never against itself.
  *
  * `HELLO WORLD` at version 1, error-correction level M, byte mode, is written out below as a literal; the other
  * fixtures under `qr/` cover a two-block version (4), version information (7), the 16-bit length field and eight
  * blocks (10), and multi-byte UTF-8. Every matrix was produced by two independent encoders that agree module for
  * module: Nayuki's qrcodegen 1.8.0 (byte segment, no level boost), which also chose the mask, and python-qrcode 8.2
  * forced to that version, level and mask. Each fixture's first line records what was asked for. (segno 1.6.6 was tried
  * first and rejected: it appends a spurious zero byte when the data already ends on a byte boundary.)
  */
final class QrCodeSpec extends AnyFunSuite:

  private val header = """.*: (\d+)-([LMQH]) mask (\d) text='(.*)'""".r

  private def rows(matrix: QrMatrix): Seq[String] =
    (0 until matrix.size).map(y => (0 until matrix.size).map(x => if matrix.dark(x, y) then '#' else '.').mkString)

  // Nayuki's qrcodegen 1.8.0, `QrCode.encode_segments([QrSegment.make_bytes(b"HELLO WORLD")], Ecc.MEDIUM,
  // boostecl=False)` -> version 1, mask 4; python-qrcode 8.2 in 8-bit byte mode at version 1, level M, mask 4 draws the
  // same 441 modules.
  private val HelloWorld1M = Seq(
    "#######.##..#.#######",
    "#.....#....#..#.....#",
    "#.###.#..#.#..#.###.#",
    "#.###.#.#..#..#.###.#",
    "#.###.#.###.#.#.###.#",
    "#.....#.#..#..#.....#",
    "#######.#.#.#.#######",
    "........#..##........",
    "#...#.######.#####..#",
    "...#....#.###....####",
    "..######..##.##.#..#.",
    "#####...##...#.......",
    "#####.#.#.#.#.##..##.",
    "........#.#.####.#.##",
    "#######.###.#.#.##.#.",
    "#.....#..#.###.##..##",
    "#.###.#.##.#.##...##.",
    "#.###.#..#..#...##.##",
    "#.###.#..###...###...",
    "#.....#....#.#.......",
    "#######.#########.#.#",
  )

  test("HELLO WORLD at level M encodes to the known version-1 module matrix"):
    val matrix = QrEncoder.encode("HELLO WORLD", QrEcc.Medium).toOption.get
    assert(matrix.version == 1)
    assert(matrix.mask == 4)
    assert(rows(matrix) == HelloWorld1M)

  for fixture <- Seq("url-q", "long-l", "long-h", "utf8-m") do
    test(s"the $fixture fixture matches the independent encoder module for module"):
      val (headerLine, expected) = Using.resource(Source.fromResource(s"qr/$fixture.txt")(using scala.io.Codec.UTF8)) {
        source =>
          val all = source.getLines().toVector
          (all.head, all.tail)
      }
      val header(version, level, mask, text) = headerLine: @unchecked
      val ecc                    = level match
        case "L" => QrEcc.Low
        case "M" => QrEcc.Medium
        case "Q" => QrEcc.Quartile
        case _   => QrEcc.High
      val matrix                 = QrEncoder.encode(text, ecc).toOption.get
      assert((matrix.version, matrix.mask) == (version.toInt, mask.toInt))
      assert(rows(matrix) == expected)

  test("the smallest version that holds the data is chosen, and data past version 10 is refused"):
    assert(QrEncoder.encode("a" * 14, QrEcc.Medium).map(_.version) == Right(1))
    assert(QrEncoder.encode("a" * 15, QrEcc.Medium).map(_.version) == Right(2))
    assert(QrEncoder.encode("a" * 271, QrEcc.Low).map(_.version) == Right(10))
    assert(QrEncoder.encode("a" * 272, QrEcc.Low).isLeft)
    assert(QrEncoder.encode("", QrEcc.High).map(_.version) == Right(1))

  test("the widget draws two modules per cell with half blocks inside a quiet zone"):
    val code         = QrCode("HELLO WORLD", QrEcc.Medium, quietZone = 1)
    assert(code.widthAt(0).contains(23))
    assert(code.heightAt(23).contains(12))
    val buffer       = rendered(code, 23, 12)
    val shown        = lines(buffer)
    // row 0 pairs the quiet row with module row 0; row 1 pairs module rows 1 and 2
    assert(shown(0) == " " + HelloWorld1M(0).map(c => if c == '#' then '▄' else ' ') + " ")
    val expectedRow1 = HelloWorld1M(1)
      .zip(HelloWorld1M(2))
      .map {
        case ('#', '#') => '█'
        case ('#', _)   => '▀'
        case (_, '#')   => '▄'
        case _          => ' '
      }
      .mkString
    assert(shown(1) == " " + expectedRow1 + " ")
    assert(buffer.get(5, 5).style.fg.contains(Color.Black))
    assert(buffer.get(5, 5).style.bg.contains(Color.White))

  test("text too long for version 10 draws a short notice instead of a code"):
    assert(lines(rendered(QrCode("x" * 400), 12, 1)) == Seq("QR: too long"))

  test("an area smaller than the code clips without writing outside it"):
    val buffer   = Buffer(Rect(0, 0, 12, 8))
    val sentinel = Cell("~", Style.Default)
    buffer.fill(buffer.area, sentinel)
    val area     = Rect(1, 1, 6, 3)
    QrCode("HELLO WORLD").render(area, buffer)
    for
      y <- 0 until 8
      x <- 0 until 12
      if !area.contains(x, y)
    do assert(buffer.get(x, y) == sentinel)
    QrCode("x").render(Rect(0, 0, 0, 0), buffer)
