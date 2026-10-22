package io.worxbend.tui.core

/** How adventurous the glyphs written into a [[Buffer]] are allowed to be.
  *
  * The three rungs run floor to ceiling: each one permits everything the rungs below it permit. They are a *floor*, not
  * a measurement — nothing here can interrogate the font a terminal is drawing with, so a rung says "do not go above
  * this", never "this is definitely renderable".
  *
  *   - [Ascii](GlyphSupport.html#Ascii-0) — nothing above U+007E. What survives a non-UTF-8 locale, a serial console,
  *     or a log file that someone will later open in an editor that is not a terminal at all.
  *   - [BoxDrawing](GlyphSupport.html#BoxDrawing-0) — the U+2500 box-drawing block on top of that. Present in
  *     essentially every fixed-width font, including the Linux virtual console's built-in one, which is why it is a
  *     rung of its own.
  *   - [Full](GlyphSupport.html#Full-0) — anything glyphora draws, braille spinners and emoji included. The default,
  *     because a modern terminal emulator with a UTF-8 locale renders all of it.
  *
  * A pure value with no environment reading of its own: `tui-terminal`'s `TerminalGlyphs.detect` is what turns an
  * environment into one of these, and it lives up there so that this type — and therefore `tui-widgets` — stays free of
  * any notion of a terminal.
  */
enum GlyphSupport:
  case Ascii, BoxDrawing, Full

object GlyphSupport:

  extension (support: GlyphSupport)

    /** Whether `support` reaches at least as high as `required` — the one question every glyph catalogue asks.
      *
      * `Full.permits(Ascii)` is `true` and `Ascii.permits(BoxDrawing)` is `false`. Written against the enum's `ordinal`
      * because the cases are declared in floor-to-ceiling order and that ordering is the whole meaning of the type; a
      * case inserted out of order would break this, which is why the declaration above says so.
      */
    def permits(required: GlyphSupport): Boolean = support.ordinal >= required.ordinal
