package io.worxbend.tui.core.palette

import io.worxbend.tui.core.{Color, ExportPalette}

/** The xterm 256-colour table as an [[ExportPalette]]: the sixteen ANSI colours at the values most emulators ship, the
  * 6x6x6 cube and the 24-step grey ramp — the same decoding [[Color.approximateRgb]] performs, published under the name
  * a reader of an exported screenshot would look for.
  *
  * A real terminal lets the user re-theme the first sixteen entries; an export has no terminal to ask, so this palette
  * paints them at xterm's own defaults. Supply another [[ExportPalette]] through `ExportOptions.palette` to render a
  * screenshot in a specific terminal theme. Stateless and safe to share between threads.
  */
object Xterm256 extends ExportPalette:

  /** The colour at palette `index`, clamped into `0..255`, as a concrete [[Color.Rgb]]. */
  def entry(index: Int): Color =
    val (r, g, b) = Color.approximateRgb(Color.Indexed(index))
    Color.Rgb(r, g, b)

  def rgb(color: Color): (Int, Int, Int) = Color.approximateRgb(color)
