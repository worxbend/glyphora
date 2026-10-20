package io.worxbend.tui.core

/** Concrete red, green and blue channels in `0..255`, as painted by an [[ExportPalette]]. Immutable and safe to share
  * between threads; distinct from [[Color.Rgb]], which is a terminal colour request.
  */
final case class Rgb(r: Int, g: Int, b: Int):
  require(r >= 0 && r <= 255 && g >= 0 && g <= 255 && b >= 0 && b <= 255, "RGB channels must be in 0..255")
