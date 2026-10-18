package io.worxbend.tui.core

/** How an export paints a [[Color]] that has no RGB value of its own.
  *
  * A terminal resolves a named or indexed colour through the user's theme; a file written by [[BufferExport]] has no
  * terminal, so the palette makes that choice instead. [[Color.Rgb]] values pass through unchanged by every sensible
  * palette; what a palette decides is the sixteen named colours and the 256-colour table. [[Color.Reset]] never reaches
  * a palette — `BufferExport` resolves it to the export's own default foreground or background first.
  *
  * Implementations must be pure: an export calls this once per cell, from whichever thread is exporting.
  */
trait ExportPalette:

  /** The `(r, g, b)` channels, each in `0..255`, that `color` is painted with. */
  def rgb(color: Color): (Int, Int, Int)

object ExportPalette:

  /** The xterm 256-colour table, [[palette.Xterm256]] — what an export uses unless told otherwise. */
  val Default: ExportPalette = palette.Xterm256
