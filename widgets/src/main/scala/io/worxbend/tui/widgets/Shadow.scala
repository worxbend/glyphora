package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Rect, Style}

/** What a shadow does to the cells it falls on.
  *
  * [[io.worxbend.tui.widgets.ShadowFill.Dim Dim]] is the one that reads as a real shadow over live content: the glyph
  * underneath is kept and only its style changes, so the text behind a floating dialog stays legible while visibly
  * receding. The shade variants overwrite instead, which is the right choice on a terminal that renders the dim
  * attribute as nothing at all — a good many do.
  */
enum ShadowFill:

  /** Keep whatever glyph is already in the cell and dim it. */
  case Dim

  /** Overwrite the cell with `░` (light shade, U+2591) — the faintest of the three shade glyphs. */
  case LightShade

  /** Overwrite the cell with `▒` (medium shade, U+2592). */
  case MediumShade

  /** Overwrite the cell with `▓` (dark shade, U+2593). */
  case DarkShade

  /** Overwrite the cell with `█` (full block, U+2588) — an opaque shadow. */
  case Solid

  /** Overwrite the cell with a glyph of your own.
    *
    * It must be a single terminal column wide. A two-column glyph such as a CJK ideograph would claim the cell next to
    * it as well, and the shadow band is one cell wide in places, so it would tear.
    */
  case Symbol(glyph: String)

/** An offset drop shadow: the band of cells a box casts down and to the right of itself.
  *
  * This is what makes a dialog or a popup read as floating above the screen rather than cut into it. It is a plain
  * immutable value that knows how to repaint a band of an existing [[io.worxbend.tui.core.Buffer Buffer]]; it is not a
  * [[io.worxbend.tui.core.Widget]] of its own, because a shadow is never the thing being drawn — it is something a
  * widget such as [[Block]] casts.
  *
  * `offsetX` and `offsetY` say how far the shadow is displaced, in cells. Positive values put it to the right and
  * below, which is what a light source at the top left produces and what nearly every interface means by a drop shadow;
  * negative values put it to the left and above. Zero on both axes means no shadow at all, and painting one is then a
  * no-op rather than an error.
  *
  * Rendering only ever writes into `bounds` and never into `box` itself, so the widget that casts the shadow can draw
  * itself afterwards without having to repair anything.
  *
  * Renders into a `Buffer` and nothing else, so it carries the buffer's thread constraint: paint from the thread that
  * owns the buffer.
  */
final case class Shadow(
    offsetX: Int = 1,
    offsetY: Int = 1,
    style: Style = Style.Default,
    fill: ShadowFill = ShadowFill.Dim,
):

  /** How many columns a caster has to give up to make room for this shadow, on whichever side it falls. */
  def reservedColumns: Int = math.abs(offsetX)

  /** How many rows a caster has to give up to make room for this shadow, on whichever side it falls. */
  def reservedRows: Int = math.abs(offsetY)

  /** Paints the shadow that `box` casts, clipped to `bounds`.
    *
    * Cells inside `box` are skipped, because the box is about to be drawn over them anyway and repainting them would
    * only be work — and, with the [[ShadowFill.Dim]] fill, would dim the box's own content. Cells outside `bounds` are
    * skipped too, so a box flush against the edge of its area casts a shadow that simply runs out of room instead of
    * writing where it does not belong.
    */
  def render(box: Rect, bounds: Rect, buffer: Buffer): Unit =
    val cast = Rect(box.x + offsetX, box.y + offsetY, box.width, box.height).intersection(bounds)
    if !cast.isEmpty && (offsetX != 0 || offsetY != 0) then
      var y = cast.y
      while y < cast.bottom do
        var x = cast.x
        while x < cast.right do
          if !box.contains(x, y) then repaint(buffer, x, y)
          x += 1
        y += 1

  /** Rewrites one cell of the band according to [[fill]]. */
  private def repaint(buffer: Buffer, x: Int, y: Int): Unit =
    fill match
      case ShadowFill.Dim =>
        // `mapStyle`, not `set`: it changes the style of the stored cell and leaves its glyph alone, which is what
        // keeps a two-column grapheme intact. Reading the cell and writing it back would destroy one, because the
        // second column of a wide grapheme reads back as a blank and writing that blank over it counts as a claim.
        // Patching rather than replacing keeps the foreground colour of whatever the shadow falls across.
        buffer.mapStyle(Rect(x, y, 1, 1))(_.patch(Shadow.Dimming.patch(style)))
      case other          =>
        buffer.set(x, y, Cell(Shadow.glyphOf(other), style))

object Shadow:

  /** The style a [[ShadowFill.Dim]] shadow layers onto the cells it falls on, before the caller's own style. */
  private val Dimming: Style = Style.Default.dim

  /** The default shadow: one cell down and to the right, dimming what is behind it. */
  val Default: Shadow = Shadow()

  /** A shadow that keeps the glyphs underneath and dims them. */
  def dim(offsetX: Int = 1, offsetY: Int = 1): Shadow =
    Shadow(offsetX, offsetY, Style.Default, ShadowFill.Dim)

  /** A shadow painted with a shade glyph rather than by dimming — the fallback for terminals that ignore the dim
    * attribute.
    */
  def shade(fill: ShadowFill, offsetX: Int = 1, offsetY: Int = 1, style: Style = Style.Default): Shadow =
    Shadow(offsetX, offsetY, style, fill)

  /** The glyph an overwriting fill writes. [[ShadowFill.Dim]] never reaches here — it writes no glyph of its own. */
  private def glyphOf(fill: ShadowFill): String =
    fill match
      case ShadowFill.LightShade    => "░"
      case ShadowFill.MediumShade   => "▒"
      case ShadowFill.DarkShade     => "▓"
      case ShadowFill.Solid         => "█"
      case ShadowFill.Symbol(glyph) => glyph
      case ShadowFill.Dim           => " "
