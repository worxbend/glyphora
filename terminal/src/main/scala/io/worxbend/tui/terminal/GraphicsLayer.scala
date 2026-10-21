package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, GraphicPlacement, Rect, RgbaImage}

import scala.collection.mutable

/** What a backend owes the terminal for the pictures of one frame: the escape sequences to send before the frame's
  * cells, and those to send after them.
  */
private[terminal] final case class GraphicsWrites(before: String, after: String, commit: () => Unit):
  def isEmpty: Boolean = before.isEmpty && after.isEmpty

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
  *      A cropped placement (a picture a scroll view's edge cuts) shows only its slice: kitty is sent the whole image
  *      with a source rectangle, iTerm2 — which has no such key — is sent the slice re-encoded as a PNG of its own.
  *
  * A frame identical to the last writes nothing at all. A placement that carries a payload already encoded for this
  * protocol is sent as given; otherwise encoded payloads are cached per image and slice, so a picture that is covered
  * and uncovered, or scrolled, is re-sent without being re-encoded.
  *
  * Owned by one backend and confined to its render thread, like the frame baseline beside it.
  */
private[terminal] final class GraphicsLayer:

  /** The pictures the terminal is showing now, each with the kitty image id it was sent under. */
  private var shown: Vector[(GraphicPlacement, Int)] = Vector.empty

  // A failed flush may have delivered some image bytes. Keep their ids until a successful batch deletes them;
  // they are never treated as a confirmed placement that an identical retry could skip.
  private var uncertain: Vector[(GraphicPlacement, Int)] = Vector.empty

  private var nextId = 1

  private val payloads = mutable.LinkedHashMap.empty[(ImageProtocol, RgbaImage, Option[Rect]), String]

  /** Brings the screen's pictures in line with `buffer`'s placements, as described on the class.
    *
    * Must run before `buffer` is diffed, because it changes which of its cells are skipped. `repaintAll` says the
    * screen was (or is about to be) repainted from scratch — after a resize, an erase or a handover — so nothing
    * previously shown can be assumed to survive. The returned update commits confirmed placements only after output
    * succeeds; uncommitted image ids remain deletion obligations in case a failed batch was partly delivered.
    */
  def compose(buffer: Buffer, protocol: Option[ImageProtocol], repaintAll: Boolean): GraphicsWrites =
    buffer.dropGraphics(placement => protocol.nonEmpty && !buffer.covers(placement))
    val live           = buffer.graphics
    val (kept, gone)   = shown.partition((placement, _) => !repaintAll && live.contains(placement))
    val keptPlacements = kept.map(_._1)
    val added          = live.filterNot(keptPlacements.contains)
    protocol match
      case None       =>
        prepared("", "", Vector.empty)
      case Some(kind) =>
        val before = kind match
          case ImageProtocol.Kitty  => (gone ++ uncertain).map((_, id) => KittyGraphics.delete(id)).mkString
          case ImageProtocol.ITerm2 => ""
        val placed = added.map(placement => (placement, allocateId()))
        val after  = placed.map((placement, id) => draw(kind, placement, id)).mkString
        uncertain ++= placed
        prepared(before, after, kept ++ placed)

  /** Prepares deletes for a screen about to move. Commit only after their output succeeds, just like a frame. */
  def forgetAll(protocol: Option[ImageProtocol]): GraphicsWrites =
    val deletes =
      if protocol.contains(ImageProtocol.Kitty) then
        (shown ++ uncertain).map((_, id) => KittyGraphics.delete(id)).mkString
      else ""
    prepared(deletes, "", Vector.empty)

  private def prepared(before: String, after: String, next: Vector[(GraphicPlacement, Int)]): GraphicsWrites =
    GraphicsWrites(
      before,
      after,
      () => {
        shown = next
        uncertain = Vector.empty
      },
    )

  private def allocateId(): Int =
    val id = nextId
    nextId = if nextId == Int.MaxValue then 1 else nextId + 1
    id

  private def draw(kind: ImageProtocol, placement: GraphicPlacement, id: Int): String =
    val area     = placement.area
    val source   = placement.crop.map(_ => placement.sourcePixels)
    val supplied = placement.payload.filter(_.protocol == kind.tag).map(_.data)
    val picture  = kind match
      case ImageProtocol.Kitty  =>
        val encoded = supplied.getOrElse(payload(kind, placement.image, None))
        KittyGraphics.display(
          encoded,
          placement.image.width,
          placement.image.height,
          id,
          area.width,
          area.height,
          source,
        )
      case ImageProtocol.ITerm2 =>
        // the protocol has no source rectangle: a slice is a picture of its own
        val encoded = if source.isEmpty then supplied.getOrElse(payload(kind, placement.image, None))
        else payload(kind, placement.image, source)
        ITerm2Images.display(encoded, area.width, area.height)
    val erase    = (area.y until area.bottom).map(row => AnsiSequences.moveTo(area.x, row) + s"\u001b[${area.width}X")
    AnsiSequences.SaveCursor + AnsiSequences.ResetStyle + erase.mkString + AnsiSequences.moveTo(area.x, area.y) +
      picture + AnsiSequences.RestoreCursor

  private def payload(kind: ImageProtocol, image: RgbaImage, slice: Option[Rect]): String =
    val key = (kind, image, slice)
    payloads.remove(key) match
      case Some(encoded) =>
        payloads.update(key, encoded) // most recently used goes last
        encoded
      case None          =>
        val encoded = kind match
          case ImageProtocol.Kitty  => KittyGraphics.payload(image)
          case ImageProtocol.ITerm2 => ITerm2Images.payload(slice.fold(image)(GraphicsLayer.cropped(image, _)))
        payloads.update(key, encoded)
        if payloads.size > GraphicsLayer.CachedPayloads then payloads.remove(payloads.head._1)
        encoded

private[terminal] object GraphicsLayer:

  /** The pixels of `image` inside `region`, as a picture of their own. */
  def cropped(image: RgbaImage, region: Rect): RgbaImage =
    val bytes = image.rgbaBytes
    RgbaImage.tabulate(region.width, region.height) { (x, y) =>
      val at = ((region.y + y) * image.width + region.x + x) * 4
      (bytes(at + 3) & 0xff) << 24 | (bytes(at) & 0xff) << 16 | (bytes(at + 1) & 0xff) << 8 | (bytes(at + 2) & 0xff)
    }

  /** How many encoded pictures are kept. A screen rarely shows more than a handful; the cap bounds what a gallery that
    * scrolls through hundreds of images keeps alive.
    */
  val CachedPayloads: Int = 16
