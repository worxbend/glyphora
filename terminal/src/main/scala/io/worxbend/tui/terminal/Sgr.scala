package io.worxbend.tui.terminal

import io.worxbend.tui.core.{SgrCodec, Style}

/** The frame encoder's SGR (Select Graphic Rendition) entry points: a [[io.worxbend.tui.core.Style]] in, the escape
  * sequence that paints it out at a [[ColorDepth]].
  *
  * The grammar itself is `core.SgrCodec`, the one copy that `core.BufferExport`'s ANSI forms write through too, and the
  * colour policy is [[ColorDepth.ansiColors]]; this object only binds the two. Split out of [[AnsiSequences]] because
  * that is a catalogue of whole-terminal control sequences and changes for unrelated reasons.
  */
private[terminal] object Sgr:

  /** Full SGR sequence for `style`, starting from a reset so no previous attribute leaks through; colors are
    * downsampled to what `depth` can display.
    */
  def sgr(style: Style, depth: ColorDepth = ColorDepth.TrueColor): String = SgrCodec.absolute(style, depth.ansiColors)

  /** The SGR that moves a terminal already showing `from` to showing `to`, writing only what actually changed.
    *
    * [[sgr]] is the *absolute* form: it opens with a reset (`ESC[0`) and then restates the whole style, so no attribute
    * of whatever came before can leak through. That is always correct, and it is expensive. A run of cells that differs
    * from its neighbour only in the bold flag still rewrites both truecolour colours — roughly thirty bytes where five
    * would do. Over a link where bytes cost time (ssh, mosh, a serial console) that is the largest remaining per-frame
    * cost after the frame diff itself.
    *
    * This form assumes the terminal is currently in exactly `from`. [[FrameEncoder]] guarantees that, because `from` is
    * the style it last emitted on this frame; no other caller should use it. Returns the empty string when the two
    * styles render identically — including when they differ only in a field SGR does not carry, such as the hyperlink.
    * Falls back to `sgr(to, depth)` byte for byte whenever the difference cannot be expressed safely as a delta.
    */
  def sgrDelta(from: Style, to: Style, depth: ColorDepth): String = SgrCodec.delta(from, to, depth.ansiColors)
