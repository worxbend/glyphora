package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, CharWidth, Color, Measured, Rect, Style, Widget}

/** A scannable QR code for `text`, drawn two modules to a cell with half-block glyphs (`▀`, `▄`, `█`), so a version-1
  * code with its quiet zone is 25 columns by 13 rows.
  *
  * The text is encoded as UTF-8 in byte mode at the smallest version from 1 to 10 that holds it at `ecc` — up to 271
  * bytes at [[QrEcc.Low]], 119 at [[QrEcc.High]]. Text longer than that draws the notice `QR: too long` instead, cut to
  * the area. Every module is painted with an explicit foreground and background, `darkColor` on `lightColor`, because a
  * reader needs dark modules on a light ground and cannot be left to whatever the terminal's own background happens to
  * be. A code is only readable whole; in an area smaller than [[widthAt]] × [[heightAt]] the widget still clips safely,
  * but the result will not scan.
  *
  * The symbol is encoded once, when the widget is built; rendering only copies it. Immutable and thread-safe.
  *
  * @param text
  *   what the code carries — a URL, a pairing token, a Wi-Fi string
  * @param ecc
  *   how much damage the code survives, traded against capacity
  * @param quietZone
  *   blank modules around the symbol on every side. The standard asks for 4; 2 is usually enough on a screen
  * @param darkColor
  *   the dark modules
  * @param lightColor
  *   the light modules and the quiet zone
  */
final case class QrCode(
    text: String,
    ecc: QrEcc = QrEcc.Medium,
    quietZone: Int = 2,
    darkColor: Color = Color.Black,
    lightColor: Color = Color.White,
) extends Widget
    with Measured:

  /** The encoded symbol, or why there is none. */
  val encoded: Either[String, QrMatrix] = QrEncoder.encode(text, ecc)

  private val margin = math.max(0, quietZone)
  private val side   = encoded.fold(_ => 0, _.size + 2 * margin)

  override def widthAt(height: Int): Option[Int] = Some(encoded.fold(_ => CharWidth.of(QrCode.TooLong), _ => side))
  override def heightAt(width: Int): Option[Int] = Some(encoded.fold(_ => 1, _ => (side + 1) / 2))

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      encoded match
        case Left(_)       => buffer.setString(area.x, area.y, QrCode.TooLong, Style.Default, area.width)
        case Right(matrix) =>
          val style = Style.Default.withFg(darkColor).withBg(lightColor)
          for
            row    <- 0 until math.min(area.height, (side + 1) / 2)
            column <- 0 until math.min(area.width, side)
          do
            val top    = matrix.dark(column - margin, row * 2 - margin)
            val bottom = matrix.dark(column - margin, row * 2 + 1 - margin)
            val glyph  = (top, bottom) match
              case (true, true)  => "█"
              case (true, false) => "▀"
              case (false, true) => "▄"
              case _             => " "
            buffer.set(area.x + column, area.y + row, Cell(glyph, style))

object QrCode:

  /** What is drawn in place of a code whose text does not fit version 10. */
  private val TooLong = "QR: too long"
