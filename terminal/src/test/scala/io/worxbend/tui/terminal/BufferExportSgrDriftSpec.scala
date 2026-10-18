package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, BufferExport, Color, Rect, Style, UnderlineStyle}

import org.scalatest.funsuite.AnyFunSuite

/** `core.BufferExport.ansi` writes SGR sequences of its own because `tui-core` cannot call this module's [[Sgr]]. This
  * is the one place the two encoders meet: every style below must come out of both byte for byte, so a code added or
  * corrected in one cannot quietly be forgotten in the other.
  */
final class BufferExportSgrDriftSpec extends AnyFunSuite:

  private val styles: Seq[Style] = Seq(
    Style.Default,
    Style.fg(Color.Red).bold,
    Style.of(Color.BrightCyan, Color.Black).italic.underline,
    Style.of(Color.Indexed(196), Color.Indexed(0)),
    Style.bg(Color.Rgb(1, 2, 3)).dim.crossedOut,
    Style.Default.withoutFg.withoutBg.reverse.hidden.blink.rapidBlink,
    Style.Default.curlyUnderline.withUnderlineColor(Color.Green),
    Style.Default.withUnderlineStyle(UnderlineStyle.Dashed).withUnderlineColor(Color.Indexed(7)),
    Style.fg(Color.Rgb(300, -1, 7)),
  )

  /** The export of a one-cell frame is the absolute SGR, the glyph, and a reset; this takes the SGR off the front. */
  private def exportedSgr(style: Style): String =
    val buffer = Buffer(Rect(0, 0, 1, 1))
    buffer.setString(0, 0, "x", style)
    val text   = BufferExport.ansi(buffer)
    text.take(text.indexOf('x'))

  test("every style the export writes is the sequence Sgr.sgr writes at true colour"):
    styles.foreach(style => assert(exportedSgr(style) == Sgr.sgr(style, ColorDepth.TrueColor), style.asSource))
