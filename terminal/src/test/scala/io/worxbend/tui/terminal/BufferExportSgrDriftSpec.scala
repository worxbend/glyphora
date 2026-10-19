package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, BufferExport, Color, ExportOptions, Rect, Style, UnderlineStyle}

import org.scalatest.funsuite.AnyFunSuite

/** There is one SGR codec, `core.SgrCodec`, and one colour policy per depth, [[ColorDepth.ansiColors]]: the terminal's
  * [[Sgr]], `core.BufferExport.ansi` and the print-mode encoder all write through them. This is where the three meet —
  * every style below must come out of each byte for byte at every depth, and print mode's text must be the core
  * encoder's text, so neither side can grow a second copy of the grammar or of the colour policy again.
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
    Style.of(Color.Rgb(200, 30, 30), Color.Rgb(20, 20, 200)),
  )

  /** The export of a one-cell frame is the absolute SGR, the glyph, and a reset; this takes the SGR off the front. */
  private def exportedSgr(style: Style, depth: ColorDepth): String =
    val buffer = Buffer(Rect(0, 0, 1, 1))
    buffer.setString(0, 0, "x", style)
    val text   = BufferExport.ansi(buffer, ExportOptions(ansiColors = depth.ansiColors))
    text.take(text.indexOf('x'))

  test("every style the export writes is the sequence Sgr.sgr writes at true colour"):
    styles.foreach(style => assert(exportedSgr(style, ColorDepth.TrueColor) == Sgr.sgr(style, ColorDepth.TrueColor)))

  test("the export honours a colour depth exactly the way the terminal does"):
    for
      depth <- ColorDepth.values.toSeq
      style <- styles
    do assert(exportedSgr(style, depth) == Sgr.sgr(style, depth), s"$depth ${style.asSource}")

  test("the default export options keep each colour's own spelling, as before"):
    val buffer = Buffer(Rect(0, 0, 1, 1))
    buffer.setString(0, 0, "x", Style.fg(Color.Rgb(1, 2, 3)))
    assert(
      BufferExport
        .ansi(buffer) == BufferExport.ansi(buffer, ExportOptions(ansiColors = ColorDepth.TrueColor.ansiColors))
    )

  test("print mode's text is the core encoder's text for the same buffer at every depth"):
    val buffer = Buffer(Rect(0, 0, 12, 3))
    buffer.setString(0, 0, "ab", Style.fg(Color.Red).bold)
    buffer.setString(2, 0, "漢", Style.of(Color.Rgb(200, 30, 30), Color.Rgb(20, 20, 200)))
    buffer.setString(4, 0, "go", Style.Default.withLink("https://example.com").underline)
    buffer.setString(0, 1, "  ", Style.bg(Color.Indexed(236)))
    buffer.setString(0, 2, "x", Style.Default.curlyUnderline.withUnderlineColor(Color.Green).dim)
    buffer.setString(1, 2, "y", Style.Default.dim)
    ColorDepth.values.foreach: depth =>
      assert(FrameEncoder.encodeLines(buffer, depth) == BufferExport.ansiLines(buffer, depth.ansiColors), depth)
