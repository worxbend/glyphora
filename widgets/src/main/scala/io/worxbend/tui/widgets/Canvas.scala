package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Line, Rect, Widget}

/** How many drawable sub-pixels one terminal cell contributes to a [[Canvas]].
  *
  * The cases trade resolution against how likely a terminal's font is to have the glyphs. Roughly, in descending order
  * of safety: [[io.worxbend.tui.widgets.CanvasResolution.Cell Cell]] and
  * [[io.worxbend.tui.widgets.CanvasResolution.HalfBlock HalfBlock]] work everywhere,
  * [[io.worxbend.tui.widgets.CanvasResolution.Quadrant Quadrant]] and
  * [[io.worxbend.tui.widgets.CanvasResolution.Braille Braille]] work almost everywhere,
  * [[io.worxbend.tui.widgets.CanvasResolution.Sextant Sextant]] needs a font from 2020 or later, and
  * [[io.worxbend.tui.widgets.CanvasResolution.Octant Octant]] one from 2024 or later. A missing glyph shows as the
  * terminal's replacement box, so pick the finest resolution you are willing to see fail.
  *
  * Resolution is not the only difference. Braille draws *sparse dots* with gaps between them, which reads as a line;
  * the block-drawing cases fill their whole sub-pixel, which reads as a solid area. For a filled chart the block cases
  * look better even at the same dot count, which is why [[io.worxbend.tui.widgets.CanvasResolution.Octant Octant]]
  * exists next to [[io.worxbend.tui.widgets.CanvasResolution.Braille Braille]] at the identical 2×4.
  */
enum CanvasResolution:
  /** One marker glyph per hit cell (the coarsest, works everywhere). */
  case Cell

  /** 1×2 sub-pixels per cell via half-block glyphs (`▀`, `▄`, `█`). */
  case HalfBlock

  /** 2×2 sub-pixels per cell via quadrant blocks (`▘`, `▚`, `▟`, …) — four times the area of a cell, solid rather than
    * dotted, and in every font that has the half blocks.
    */
  case Quadrant

  /** 2×3 sub-pixels per cell via the sextant blocks of Unicode 13's Symbols for Legacy Computing. Half again the
    * vertical resolution of [[io.worxbend.tui.widgets.CanvasResolution.Quadrant Quadrant]], at the cost of needing a
    * font that covers `U+1FB00`–`U+1FB3B`.
    */
  case Sextant

  /** 2×4 sub-pixels per cell via braille patterns — the smoothest lines. */
  case Braille

  /** 2×4 sub-pixels per cell via the octant blocks of Unicode 16's Symbols for Legacy Computing Supplement.
    *
    * The same dot count as [[io.worxbend.tui.widgets.CanvasResolution.Braille Braille]] but filled instead of dotted,
    * so areas and thick strokes read as solid. It is the newest of the six and the least widely supported: fonts
    * published before 2024 will not have `U+1CD00`– `U+1CDE5` and the terminal will draw replacement boxes.
    */
  case Octant

/** A piece of text pinned to a world coordinate on a [[Canvas]] — a city on a map, a threshold on a plot.
  *
  * The `(x, y)` is in the canvas's own coordinate system, the same one shapes speak, with y pointing up. That is what
  * makes a label worth having over writing the text yourself next to the canvas: when the bounds change, the label
  * moves with the thing it names instead of staying where you put it.
  *
  * Text lands at whole-*cell* granularity whatever the [[CanvasResolution]] is, because there is no half of a cell for
  * a character to sit in: the label starts in the cell containing the point and runs right, clipped at the canvas's own
  * right edge.
  */
final case class CanvasLabel(x: Double, y: Double, line: Line)

/** A free-form drawing surface: shapes describe themselves in a world coordinate system (`xBounds` right-ward,
  * `yBounds` up-ward) and the canvas maps them onto its cell grid, at whichever [[CanvasResolution]] it was given.
  *
  * `marker` is read only at [[CanvasResolution.Cell]], where there is one dot per cell; a marker that is not exactly
  * one column wide is replaced by [[Marker.Dot]] rather than allowed to smear into the next cell.
  *
  * `labels` pins pieces of text to world coordinates — a city on a map, a threshold on a plot — and, like the shapes,
  * is content rather than appearance, so it sits with the data ahead of the drawing configuration. Labels are drawn
  * after every shape, so a dot cannot land on top of the text that names it.
  */
final case class Canvas(
    xBounds: Bounds,
    yBounds: Bounds,
    shapes: Seq[Shape],
    labels: Seq[CanvasLabel] = Seq.empty,
    marker: String = Marker.Dot,
    resolution: CanvasResolution = CanvasResolution.Cell,
) extends Widget:

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      val painter = Painter(area, xBounds, yBounds, resolution, marker)
      shapes.foreach(_.draw(painter))
      labels.foreach(label => painter.print(label.x, label.y, label.line))
      painter.flush(buffer)
