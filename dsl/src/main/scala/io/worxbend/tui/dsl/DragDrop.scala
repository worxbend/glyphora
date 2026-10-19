package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, MouseEvent, MouseEventKind, Position, Rect, Style, Widget}

/** The type of a drag-and-drop payload, as a value: a `draggable[A]` and a `dropTarget[A]` meet only when they were
  * built against the same key, which is what lets a drop hand the target an `A` with no runtime type test and no
  * reflection.
  *
  * Keys compare by identity. The built-in givens cover `String`, `Int` and `Long`; an app's own type gets one by
  * declaring it once where both ends can see it:
  *
  * {{{
  * final case class Card(rank: Int)
  * given DragKey[Card] = DragKey("card")
  *
  * text("7♠").draggable(Card(7))
  * pile.dropTarget[Card](_.rank > 5)(card => moveTo(pile, card))
  * }}}
  *
  * Two keys for one type are two kinds of payload — a `DragKey[String]("file")` and the built-in string key do not meet
  * — which is also how to keep two unrelated drags of the same type apart.
  *
  * How the value crosses without a cast: a payload packed by this key carries a closure that writes the value into this
  * key's own typed slot, and only this key can unpack it. The slot is written and read within one call on the render
  * thread, where every drag is handled.
  */
final class DragKey[A] private (val name: String):

  private var slot: Option[A] = None

  /** `value` as a payload this key, and only this key, can unpack. */
  def pack(value: A): DragPayload = new DragPayload(this, value.toString, () => slot = Some(value))

  /** The value `payload` carries when this key packed it, else `None`. */
  private[dsl] def unpack(payload: DragPayload): Option[A] =
    if payload.key ne this then None
    else
      slot = None
      payload.load()
      val value = slot
      slot = None
      value

  override def toString: String = s"DragKey($name)"

object DragKey:

  /** A new key — a new kind of payload — named `name` for debugging. */
  def apply[A](name: String): DragKey[A] = new DragKey[A](name)

  given string: DragKey[String] = DragKey("String")
  given int: DragKey[Int]       = DragKey("Int")
  given long: DragKey[Long]     = DragKey("Long")

/** A dragged value, packed by its [[DragKey]] — see `draggable`. `label` is the value's `toString`, for debugging. */
final class DragPayload private[dsl] (
    private[dsl] val key: DragKey[?],
    val label: String,
    private[dsl] val load: () => Unit,
):
  override def toString: String = s"DragPayload(${key.name}: $label)"

/** What `dropTarget[A](accept)(onDrop)` installs: whether a payload is one this target takes, and taking it. */
final class DropTarget private[dsl] (
    private[dsl] val accepts: DragPayload => Boolean,
    private[dsl] val drop: DragPayload => Boolean,
)

private[dsl] object DropTarget:

  def of[A](key: DragKey[A], accept: A => Boolean, onDrop: A => Unit): DropTarget =
    new DropTarget(
      payload => key.unpack(payload).exists(accept),
      payload =>
        key.unpack(payload).filter(accept) match
          case Some(value) =>
            onDrop(value)
            true
          case None        => false,
    )

/** One drag in progress, for [[PointerGestures]]: armed by a press on a draggable node, started by the first drag that
  * moves off the pressed cell, ended by the release. While it runs the dragged node paints a ghost of itself under the
  * pointer — through a portal, so it is drawn over everything — and the innermost drop target under the pointer that
  * accepts the payload draws the theme's focus cue.
  *
  * Owned by one host and touched only on its render thread.
  */
private[dsl] final class DragGesture:

  private var armed: Option[(NodeBoundsEntry, DragPayload, Position)] = None
  private var source: Option[Int]                                     = None
  private var payload: Option[DragPayload]                            = None
  private var grab: Position                                          = Position(0, 0)
  private var pointer: Position                                       = Position(0, 0)
  private var hovered: Option[Int]                                    = None

  def isActive: Boolean = source.isDefined

  /** Arms a drag of the draggable node at the press, if there is one; `true` when one was armed. */
  def arm(event: MouseEvent, frame: NodeBoundsFrame): Boolean =
    armed = frame.innermostAt(event.position)(_.drag.isDefined).flatMap { entry =>
      entry.roles.drag.map(carried => (entry, carried, event.position))
    }
    armed.isDefined

  /** `Some(redraw)` when the drag takes `event` — the first motion off the pressed cell, every motion after it, and the
    * release that drops — else `None`.
    */
  def intercept(event: MouseEvent, frame: NodeBoundsFrame): Option[Boolean] =
    (source, armed) match
      case (Some(_), _)                           => continue(event, frame)
      case (None, Some((entry, carried, origin))) =>
        event.kind match
          case MouseEventKind.Drag if event.position != origin =>
            armed = None
            source = Some(entry.id)
            payload = Some(carried)
            grab = Position(origin.x - entry.visible.x, origin.y - entry.visible.y)
            continue(event, frame)
          case MouseEventKind.Up | MouseEventKind.Down         =>
            armed = None
            None
          case _                                               => None
      case (None, None)                           => None

  private def continue(event: MouseEvent, frame: NodeBoundsFrame): Option[Boolean] =
    val target = payload.flatMap(carried => frame.innermostAt(event.position)(_.drop.exists(_.accepts(carried))))
    event.kind match
      case MouseEventKind.Drag | MouseEventKind.Moved =>
        pointer = event.position
        hovered = target.map(_.id)
        Some(true)
      case MouseEventKind.Up                          =>
        for
          carried <- payload
          entry   <- target
          drop    <- entry.roles.drop
        do
          val _ = drop.drop(carried)
        end()
        Some(true)
      case MouseEventKind.Down                        =>
        end()
        Some(true)
      case _                                          => Some(false)

  /** Abandons the drag without dropping. */
  def end(): Unit =
    armed = None
    source = None
    payload = None
    hovered = None

  /** The overlay for node `id`, just painted as `node` into `area`: the hover cue on the target under the pointer, and
    * the ghost, queued as a portal, for the dragged node.
    */
  def painted(id: Int, node: Element, area: Rect, buffer: Buffer): Unit =
    if source.isDefined then
      val visible = area.intersection(buffer.area)
      if hovered.contains(id) && !visible.isEmpty then buffer.mapStyle(visible)(_.patch(node.props.focusStyle))
      if source.contains(id) && !visible.isEmpty then
        val ghost   = Buffer(Rect(0, 0, visible.width, visible.height))
        ghost.blit(buffer, Position(0, 0), visible)
        val onFrame = FrameCoordinates.translate(area)
        val local   = Rect(
          pointer.x - grab.x - (onFrame.x - area.x),
          pointer.y - grab.y - (onFrame.y - area.y),
          visible.width,
          visible.height,
        )
        val drawn   = WidgetElement(DragGesture.ghostOf(ghost))
        if PortalQueue.isCollecting then PortalQueue.offer(local, drawn) else drawn.widget.render(local, buffer)

private[dsl] object DragGesture:

  /** The cells of the dragged node, copied as it painted, drawn dimmed wherever the portal puts them. */
  def ghostOf(cells: Buffer): Widget =
    (area, buffer) =>
      buffer.blit(cells, area.position, cells.area)
      val shown = area.intersection(buffer.area).intersection(Rect(area.x, area.y, cells.area.width, cells.area.height))
      if !shown.isEmpty then buffer.mapStyle(shown)(_.patch(Style.Default.dim))

/** Drag and drop — see [[DragKey]]. */
extension [E <: Element](element: E)

  /** Makes this element draggable, carrying `payload`: a press on it followed by motion starts a drag, a ghost of the
    * element follows the pointer, and the release drops `payload` on the innermost `dropTarget` under it that takes it.
    * A press and release without motion is still an ordinary click. The payload's type is its [[DragKey]] — the given
    * in scope for `A`.
    */
  def draggable[A](payload: A)(using key: DragKey[A]): element.Self =
    element.withProps(element.props.copy(dragPayload = Some(key.pack(payload))))

  /** Makes this element a drop target for payloads of type `A` (by its [[DragKey]]) that `accept` takes: while a drag
    * of one hovers it, the element draws the theme's focus cue, and a release over it calls `onDrop` with the value. A
    * payload of another type, or one `accept` refuses, passes over it as if it were not there.
    */
  def dropTarget[A](accept: A => Boolean)(onDrop: A => Unit)(using key: DragKey[A]): element.Self =
    element.withProps(element.props.copy(dropTarget = Some(DropTarget.of(key, accept, onDrop))))
