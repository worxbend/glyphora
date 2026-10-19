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
  * Equality is the picture and the box — what decides whether the terminal already shows it — and ignores the
  * remembered cells. Immutable; owned by the buffer that recorded it.
  */
final class GraphicPlacement private[core] (
    val area: Rect,
    val image: RgbaImage,
    private[core] val underneath: IArray[Cell],
):

  /** The same picture with its box moved by `(dx, dy)`, as a blit moves the cells under it. */
  private[core] def moved(dx: Int, dy: Int): GraphicPlacement = GraphicPlacement(area.offset(dx, dy), image, underneath)

  override def equals(other: Any): Boolean = other match
    case that: GraphicPlacement => area == that.area && image == that.image
    case _                      => false

  override def hashCode: Int = area.hashCode * 31 + image.hashCode

  override def toString: String = s"GraphicPlacement($area, $image)"
