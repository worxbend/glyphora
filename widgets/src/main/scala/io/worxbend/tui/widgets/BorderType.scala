package io.worxbend.tui.widgets

import io.worxbend.tui.core.GlyphSupport

/** The named frame a [[Block]] draws itself with — the box-drawing set, the dashed set, or the block-element set that
  * picks its corner and edge glyphs. [[BorderGlyphs.of]] turns one of these into the eight glyphs it stands for, and
  * `Block(borderSet = ...)` takes such a record directly when none of the named sets is the one you want.
  *
  * Every glyph in every set here is one terminal column wide, so choosing a set never changes a block's [[Block.inner]]
  * geometry — only its look.
  *
  *   - [Plain](BorderType.html#Plain-0) `┌─┐`, [Rounded](BorderType.html#Rounded-0) `╭─╮`,
  *     [Double](BorderType.html#Double-0) `╔═╗`, [Thick](BorderType.html#Thick-0) `┏━┓` — the four classic box-drawing
  *     weights.
  *   - [Ascii](BorderType.html#Ascii-0) `+-+` — plain ASCII, for a terminal or a font with no box-drawing glyphs, and
  *     for output that has to survive being pasted somewhere that is not a terminal at all.
  *   - The six dashed sets — light or heavy, broken into two, three or four dashes per cell — draw a frame that reads
  *     as provisional or inactive next to a solid one. Their corners stay solid, because a dashed corner glyph does not
  *     exist.
  *   - [QuadrantInside](BorderType.html#QuadrantInside-0) and [QuadrantOutside](BorderType.html#QuadrantOutside-0)
  *     treat each cell as a 2x2 grid of half-cell "pixels", so the frame sits half a cell inside the block's area or
  *     half a cell outside it. Two nested blocks drawn one inside and one outside meet with no gap between them.
  *   - [OneEighthWide](BorderType.html#OneEighthWide-0) and [OneEighthTall](BorderType.html#OneEighthTall-0) are the
  *     "McGugan box", named after Will McGugan: one-eighth block elements pushed right up against the cell edge, which
  *     reads as a hairline rather than as a row of glyphs.
  *   - [ProportionalWide](BorderType.html#ProportionalWide-0) and
  *     [ProportionalTall](BorderType.html#ProportionalTall-0) compensate for a terminal cell being about twice as tall
  *     as it is wide, so the frame looks the same thickness horizontally and vertically.
  *   - [Full](BorderType.html#Full-0) `█` is a solid slab, and [Blank](BorderType.html#Blank-0) draws spaces — the way
  *     to give a block a border-styled margin, or to keep a title's border row without any frame under it.
  */
enum BorderType:
  case Plain, Rounded, Double, Thick
  case Ascii
  case LightDoubleDashed, LightTripleDashed, LightQuadrupleDashed
  case HeavyDoubleDashed, HeavyTripleDashed, HeavyQuadrupleDashed
  case QuadrantInside, QuadrantOutside
  case OneEighthWide, OneEighthTall
  case ProportionalWide, ProportionalTall
  case Full, Blank

  /** This border set, or [Ascii](BorderType.html#Ascii-0) when `support` does not reach the rung this set needs.
    *
    * What lets an application pick a frame once and still run on a terminal that cannot draw it. Every set above needs
    * at least [[io.worxbend.tui.core.GlyphSupport.BoxDrawing]] except [Ascii](BorderType.html#Ascii-0) and
    * [Blank](BorderType.html#Blank-0), which need nothing, so the question is only ever "is box drawing available?" —
    * the quadrant, one-eighth and proportional sets are all box-drawing-block or block-element glyphs and travel
    * together.
    *
    * The replacement is a full set like any other, and, like every set, does not change `Block.inner`.
    */
  def degraded(support: GlyphSupport): BorderType =
    this match
      case BorderType.Ascii | BorderType.Blank           => this
      case _ if support.permits(GlyphSupport.BoxDrawing) => this
      case _                                             => BorderType.Ascii
