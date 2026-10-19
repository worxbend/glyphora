package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, GraphicPayload, Measured, Rect, RgbaImage, Widget}
import io.worxbend.tui.runtime.{Async, TaskScope}
import io.worxbend.tui.terminal.{ITerm2Images, ImageProtocol, KittyGraphics}
import io.worxbend.tui.widgets.PixelImage

import scala.collection.mutable

/** The pictures one host has encoded for its terminal's image protocol, and the ones being encoded now.
  *
  * Why this exists. A picture protocol sends the image itself — base64 RGBA for kitty, a deflated PNG for iTerm2 — and
  * encoding a photo-sized picture takes long enough to drop frames. The backend cannot move that work off the render
  * thread (it cannot see `tui-runtime`), so the DSL does: the first time a `pixelImage` paints a picture,
  * [[payloadFor]] answers `None` and starts the encode on an `Async` worker; the node paints the half-block fallback
  * meanwhile; when the encode finishes, its continuation stores the payload on the render thread and asks the host for
  * a frame, and from then on the node places the picture with the payload attached, which the backend sends as given.
  *
  * Encoded payloads are kept per picture (by value, as the backend's own cache keys them), the [[Kept]] most recently
  * used. Setting [[protocol]] to a different value forgets them all.
  *
  * Threads: render-thread-only, like the host that owns it; the encode itself runs on a worker and touches nothing here
  * until its continuation is back on the render thread. Outside a running app — no render owner to come back to — the
  * encode runs where it is asked for.
  */
private[dsl] final class PictureEncodings(requestFrame: () => Unit):

  private var current: Option[ImageProtocol] = None
  private val ready                          = mutable.LinkedHashMap.empty[RgbaImage, GraphicPayload]
  private val pending                        = mutable.HashSet.empty[RgbaImage]
  private val failed                         = mutable.HashSet.empty[RgbaImage]
  private var scope: Option[TaskScope]       = None

  def protocol: Option[ImageProtocol] = current

  def protocol_=(next: Option[ImageProtocol]): Unit =
    if next != current then
      current = next
      ready.clear()
      pending.clear()
      failed.clear()

  /** The payload for `image` in [[protocol]], once encoded. `None` while the encode runs — the first call starts it —
    * and for a picture whose encode failed (the backend then encodes it itself, as it would with no payload at all).
    */
  def payloadFor(image: RgbaImage): Option[GraphicPayload] =
    current.flatMap { kind =>
      ready.remove(image) match
        case Some(payload) =>
          ready.update(image, payload) // most recently used goes last
          Some(payload)
        case None          =>
          if !pending.contains(image) && !failed.contains(image) then start(kind, image)
          ready.get(image)
    }

  /** Whether `image` failed to encode, so the caller places it without a payload. */
  def hasFailed(image: RgbaImage): Boolean = failed.contains(image)

  private def start(kind: ImageProtocol, image: RgbaImage): Unit =
    taskScope match
      case None        => store(image, encodeNow(kind, image))
      case Some(tasks) =>
        pending += image
        val _ = tasks.runCatching(encodeNow(kind, image)) { result =>
          // back on the render thread; a protocol change while it ran makes the answer stale
          if pending.remove(image) && current.contains(kind) then
            store(image, result.flatten)
            requestFrame()
        }

  private def store(image: RgbaImage, result: Either[Throwable, GraphicPayload]): Unit =
    result match
      case Right(payload) =>
        ready.update(image, payload)
        if ready.size > PictureEncodings.Kept then ready.remove(ready.head._1)
      case Left(_)        => failed += image

  private def encodeNow(kind: ImageProtocol, image: RgbaImage): Either[Throwable, GraphicPayload] =
    try Right(GraphicPayload(kind.tag, PictureEncodings.encode(kind, image)))
    catch case scala.util.control.NonFatal(error) => Left(error)

  private def taskScope: Option[TaskScope] =
    if scope.isEmpty then scope = Async.scopeIfRunning()
    scope

private[dsl] object PictureEncodings:

  /** How many encoded pictures a host keeps. */
  val Kept: Int = 16

  /** The encoder: the protocol's own payload for an image. A seam for tests, which swap in a slow or gated one to watch
    * the fallback frame; one JVM per test class keeps a swap from leaking.
    */
  @volatile private[dsl] var encode: (ImageProtocol, RgbaImage) => String = (kind, image) =>
    kind match
      case ImageProtocol.Kitty  => KittyGraphics.payload(image)
      case ImageProtocol.ITerm2 => ITerm2Images.payload(image)

/** The widget behind `pixelImage`: a [[PixelImage]] whose picture is encoded off the render thread by the painting
  * host's [[PictureEncodings]]. While the payload is not ready it draws the half-block fallback and places nothing;
  * once it is, it places the picture with the payload. Painted with no host (a test rendering the widget directly) or
  * for a terminal with no picture protocol, it is the plain [[PixelImage]].
  */
private[dsl] final case class PicturePainter(picture: PixelImage) extends Widget with Measured:

  def render(area: Rect, buffer: Buffer): Unit =
    FrameContext.current.pictures.filter(_.protocol.isDefined) match
      case None           => picture.render(area, buffer)
      case Some(encoding) =>
        encoding.payloadFor(picture.image) match
          case Some(payload)                             => picture.render(area, buffer, Some(payload))
          case None if encoding.hasFailed(picture.image) => picture.render(area, buffer)
          case None                                      => picture.renderFallback(area, buffer)

  override def heightAt(width: Int): Option[Int] = picture.heightAt(width)
  override def widthAt(height: Int): Option[Int] = picture.widthAt(height)
