package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, GraphicPlacement, RgbaImage}

import scala.collection.mutable

/** What a backend owes the terminal for the pictures of one frame: the escape sequences to send before the frame's
  * cells, and those to send after them.
  */
private[terminal] final case class GraphicsWrites(before: String, after: String):
  def isEmpty: Boolean = before.isEmpty && after.isEmpty

private[terminal] object GraphicsWrites:
  val None: GraphicsWrites = GraphicsWrites("", "")

/** Keeps the pictures on screen in step with the [[io.worxbend.tui.core.GraphicPlacement]]s of each frame — the
  * out-of-band half of drawing an image, which the cell diff cannot see.
  *
  * Per frame, in [[compose]]:
  *   1. Placements the terminal cannot draw — no protocol, or something drawn over them later in the same frame
  *      ([[Buffer.covers]]) — are dropped from the buffer, which hands their cells back to ordinary diffing. That is
  *      how a popup over a picture becomes visible and how a terminal with no protocol shows the half-block fallback.
  *   2. A picture on screen that the frame no longer places (gone, moved, covered, or the screen was repainted from
  *      scratch) is taken down: kitty gets a delete; iTerm2 has none, and its cells are repainted by the diff because
  *      they stopped being skipped.
  *   3. A placement not yet on screen is drawn: cursor saved, the box's cells erased (so a transparent pixel shows
  *      blank rather than whatever the terminal last had there), the picture sent at the box's corner, cursor restored.
  *
  * A frame identical to the last writes nothing at all. Encoded payloads are cached per image, so a picture that is
  * covered and uncovered, or scrolled, is re-sent without being re-encoded.
  *
  * Owned by one backend and confined to its render thread, like the frame baseline beside it.
  */
private[terminal] final class GraphicsLayer:

  /** The pictures the terminal is showing now, each with the kitty image id it was sent under. */
  private var shown: Vector[(GraphicPlacement, Int)] = Vector.empty

  private var nextId = 1

  private val payloads = mutable.LinkedHashMap.empty[(ImageProtocol, RgbaImage), String]

  /** Brings the screen's pictures in line with `buffer`'s placements, as described on the class.
    *
    * Must run before `buffer` is diffed, because it changes which of its cells are skipped. `repaintAll` says the
    * screen was (or is about to be) repainted from scratch — after a resize, an erase or a handover — so nothing
    * previously shown can be assumed to survive.
    */
  def compose(buffer: Buffer, protocol: Option[ImageProtocol], repaintAll: Boolean): GraphicsWrites =
    buffer.dropGraphics(placement => protocol.nonEmpty && !buffer.covers(placement))
    val live           = buffer.graphics
    val (kept, gone)   = shown.partition((placement, _) => !repaintAll && live.contains(placement))
    val keptPlacements = kept.map(_._1)
    val added          = live.filterNot(keptPlacements.contains)
    protocol match
      case None       =>
        shown = Vector.empty
        GraphicsWrites.None
      case Some(kind) =>
        val before = kind match
          case ImageProtocol.Kitty  => gone.map((_, id) => KittyGraphics.delete(id)).mkString
          case ImageProtocol.ITerm2 => ""
        val placed = added.map(placement => (placement, allocateId()))
        val after  = placed.map((placement, id) => draw(kind, placement, id)).mkString
        shown = kept ++ placed
        GraphicsWrites(before, after)

  /** Forgets every picture the terminal shows, returning the deletes that take them down — for a backend about to lose
    * the screen to something else (a scroll region it moved, a teardown).
    */
  def forgetAll(protocol: Option[ImageProtocol]): String =
    val deletes =
      if protocol.contains(ImageProtocol.Kitty) then shown.map((_, id) => KittyGraphics.delete(id)).mkString else ""
    shown = Vector.empty
    deletes

  private def allocateId(): Int =
    val id = nextId
    nextId = if nextId == Int.MaxValue then 1 else nextId + 1
    id

  private def draw(kind: ImageProtocol, placement: GraphicPlacement, id: Int): String =
    val area    = placement.area
    val encoded = payload(kind, placement.image)
    val picture = kind match
      case ImageProtocol.Kitty  =>
        KittyGraphics.display(encoded, placement.image.width, placement.image.height, id, area.width, area.height)
      case ImageProtocol.ITerm2 => ITerm2Images.display(encoded, area.width, area.height)
    val erase   = (area.y until area.bottom).map(row => AnsiSequences.moveTo(area.x, row) + s"\u001b[${area.width}X")
    AnsiSequences.SaveCursor + AnsiSequences.ResetStyle + erase.mkString + AnsiSequences.moveTo(area.x, area.y) +
      picture + AnsiSequences.RestoreCursor

  private def payload(kind: ImageProtocol, image: RgbaImage): String =
    val key = (kind, image)
    payloads.remove(key) match
      case Some(encoded) =>
        payloads.update(key, encoded) // most recently used goes last
        encoded
      case None          =>
        val encoded = kind match
          case ImageProtocol.Kitty  => KittyGraphics.payload(image)
          case ImageProtocol.ITerm2 => ITerm2Images.payload(image)
        payloads.update(key, encoded)
        if payloads.size > GraphicsLayer.CachedPayloads then payloads.remove(payloads.head._1)
        encoded

private[terminal] object GraphicsLayer:

  /** How many encoded pictures are kept. A screen rarely shows more than a handful; the cap bounds what a gallery that
    * scrolls through hundreds of images keeps alive.
    */
  val CachedPayloads: Int = 16
