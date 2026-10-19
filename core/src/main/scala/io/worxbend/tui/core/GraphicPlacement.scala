package io.worxbend.tui.core

/** One picture a frame asks an out-of-band painter to draw: `image`, scaled into the box of cells `area`.
  *
  * Recorded by [[Buffer.placeGraphic]], which also reserves the box with [[DiffDirective.Skip]]. A terminal backend
  * that speaks an image protocol reads [[Buffer.graphics]] after the frame is composed and draws each placement through
  * its own escape sequence; one that does not ignores them and gives the cells back, so whatever the widget drew there
  * as a fallback is flushed as ordinary text.
  *
  * The placement remembers the cells that were under it when it was made. A popup, a toast or a menu drawn later in the
  * same frame writes over those cells, and [[Buffer.covers]] notices: a picture an image protocol paints sits *above*
  * the text grid, so the only way to show the popup is to take the picture down while it is covered and put it back
  * once the cells match again.
  *
  * A placement a blit cut — a picture straddling the edge of a scroll view's window — keeps the visible part of its box
  * and says which part in [[crop]]; [[sourcePixels]] is the matching rectangle of the picture, so a backend draws
  * exactly that slice into `area` instead of squeezing the whole picture into it.
  *
  * [[payload]] is the picture already encoded for one image protocol, when whoever placed it encoded it ahead of the
  * frame (the DSL's `pixelImage` does, off the render thread); a backend with no payload for its protocol encodes the
  * picture itself.
  *
  * Equality is the picture, the box and the crop — what decides whether the terminal already shows it — and ignores the
  * remembered cells and the payload. Immutable; owned by the buffer that recorded it.
  */
final class GraphicPlacement private[core] (
    val area: Rect,
    val image: RgbaImage,
    private[core] val underneath: IArray[Cell],
    val crop: Option[GraphicCrop] = None,
    val payload: Option[GraphicPayload] = None,
):

  /** The same picture with its box moved by `(dx, dy)`, as a blit moves the cells under it. */
  private[core] def moved(dx: Int, dy: Int): GraphicPlacement =
    GraphicPlacement(area.offset(dx, dy), image, underneath, crop, payload)

  /** The part of this placement inside `window` (in this placement's own coordinates), as a crop of the whole box. */
  private[core] def croppedTo(window: Rect): GraphicPlacement =
    val visible = area.intersection(window)
    val whole   = crop.getOrElse(GraphicCrop(area.width, area.height, 0, 0))
    val cells   = IArray.tabulate(visible.area) { index =>
      val x = visible.x + index % visible.width - area.x
      val y = visible.y + index / visible.width - area.y
      underneath(y * area.width + x)
    }
    val next    = whole.copy(column = whole.column + visible.x - area.x, row = whole.row + visible.y - area.y)
    GraphicPlacement(visible, image, cells, Some(next), payload)

  /** The rectangle of [[image]], in pixels, that this placement shows: the whole picture, or for a cropped placement
    * the slice under the visible cells — each cell of the whole box standing for an equal share of the pixels.
    */
  def sourcePixels: Rect =
    crop match
      case None        => Rect(0, 0, image.width, image.height)
      case Some(whole) =>
        val left   = whole.column * image.width / whole.columns
        val top    = whole.row * image.height / whole.rows
        val right  = (whole.column + area.width) * image.width / whole.columns
        val bottom = (whole.row + area.height) * image.height / whole.rows
        Rect(left, top, math.max(1, right - left), math.max(1, bottom - top))

  override def equals(other: Any): Boolean = other match
    case that: GraphicPlacement => area == that.area && image == that.image && crop == that.crop
    case _                      => false

  override def hashCode: Int = (area.hashCode * 31 + image.hashCode) * 31 + crop.hashCode

  override def toString: String = s"GraphicPlacement($area, $image${crop.fold("")(c => s", $c")})"

/** Which part of a picture's box a cropped [[GraphicPlacement]] shows: the box was `columns` x `rows` cells, and the
  * placement shows its own area's worth of them starting at cell (`column`, `row`) of that box.
  */
final case class GraphicCrop(columns: Int, rows: Int, column: Int, row: Int)

/** A picture already encoded for one terminal image protocol: `protocol` names it (`"kitty"`, `"iterm2"` — the terminal
  * module's `ImageProtocol.tag`) and `data` is the payload that protocol sends, base64 included. Built off the render
  * thread by whoever placed the picture, so the frame that draws it does no encoding of its own.
  */
final case class GraphicPayload(protocol: String, data: String):
  override def toString: String = s"GraphicPayload($protocol, ${data.length} chars)"
