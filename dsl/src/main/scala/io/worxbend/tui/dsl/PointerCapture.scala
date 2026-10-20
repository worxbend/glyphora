package io.worxbend.tui.dsl

import io.worxbend.tui.core.{MouseButton, MouseEvent, MouseEventKind, Position, Rect}

/** One mouse event as an element's [[onMouse]] handler sees it: the event itself (absolute position, kind, button,
  * modifiers and the synthesised `clickCount`), the position relative to the element's own top-left cell, and the
  * element's translated layout area.
  *
  * `local` is `event.position` minus `area`'s origin, so `(0, 0)` is the element's top-left cell whatever moves it on
  * screen. While a press on the element has captured the pointer, drags and the release keep arriving here even once
  * the pointer has left the element, so `local` can be negative or past `area`'s size — clamp it the way a slider does.
  */
final case class MouseContext(event: MouseEvent, local: Position, area: Rect)

object MouseContext:

  /** The context for `event` delivered to an element laid out in `area`. */
  def of(event: MouseEvent, area: Rect): MouseContext =
    MouseContext(event, Position(event.position.x - area.x, event.position.y - area.y), area)

/** An input position in one frame's topology, used only to retire unkeyed built-in continuations on structural edits.
  */
private[dsl] final case class CaptureSlot(path: List[Int], kind: String, key: Option[String])

/** The pointer bookkeeping one [[ElementHost]] keeps between mouse events: which target a press captured, and how many
  * presses in a row landed on the same cell.
  *
  * '''Capture.''' A `Down` that some handler or built-in consumed captures the pointer for the target it hit: until the
  * matching `Up`, every `Drag` and that `Up` are routed to that target with its press-time layout area, wherever the
  * pointer is — so a slider or a split divider keeps following a drag that has left its hit area. The `Up` releases the
  * capture after it is delivered. A press nobody consumed captures nothing. Across frames capture follows a unique
  * keyed owner (or the same immutable unkeyed description), retaining press-time geometry. Removing that owner from the
  * input topology, clipping it out, disabling or covering it cancels capture and discards the tail until a fresh press.
  * Unkeyed built-ins can retain their original handlers across rebuilding only while caller-owned signal/state
  * references and the input topology remain the same. Unknown or replaced state identity cancels conservatively.
  *
  * '''Click counting.''' A `Down` with the same button on the same cell as the previous one, within [[DoubleClick]] of
  * it, counts one more; anything else starts again at 1. The count is stamped on the event as `clickCount` before any
  * handler sees it.
  *
  * Owned by one host and touched only on its render thread, like the focus tracker beside it.
  */
private[dsl] final class PointerCapture:

  private var captured: Option[MouseHit]                  = None
  private var owner: Option[Element]                      = None
  private var retired                                     = false
  private var topology                                    = Map.empty[InputTarget, CaptureSlot]
  private var continuation: Option[MouseEvent => Boolean] = None
  private var continuingBuiltin                           = false

  /** An unkeyed built-in gesture keeps its original handlers, never a newly composed replacement's closure. */
  def continue(event: MouseEvent): Option[Boolean] =
    if continuingBuiltin && (event.kind == MouseEventKind.Drag || event.kind == MouseEventKind.Up) then
      continuation.map(_(event))
    else None

  private def builtinOnly(node: Element): Boolean =
    node.builtinMouseHandler.isDefined && node.props.onMouse.isEmpty && node.props.onMouseContext.isEmpty

  private var lastPress: Option[(Position, MouseButton, Long)] = None
  private var count: Int                                       = 0

  /** The target a press captured, if a button is down on one. */
  def capturedHit: Option[MouseHit] = captured

  /** Rebinds keyed/ref-identical owners; rebuilt user handlers cancel conservatively. Built-in-only controls can
    * continue their original immutable handlers while both their stable caller-owned references and the entire input
    * topology are unchanged: restyling an unkeyed slider must not end its drag. Unknown identity, a state replacement
    * or any structural edit cancels that continuation. It never adopts a freshly built control's handlers. Geometry
    * intentionally stays at press time.
    */
  def reconcile(
      nodes: collection.Map[InputTarget, Element],
      bounds: NodeBounds,
      shape: Map[InputTarget, CaptureSlot],
  ): Unit =
    owner.foreach { original =>
      val matches           = nodes.iterator.collect {
        case (target, node)
            if original.props.focusKey.fold(node eq original)(node.props.focusKey.contains) &&
              !node.props.isDisabled && bounds.targetBounds(target).isDefined =>
          target
      }.toVector
      val positionalBuiltin = original.props.focusKey.isEmpty && builtinOnly(original) && topology == shape &&
        captured.exists(hit =>
          nodes
            .get(hit.target)
            .exists(node => !node.props.isDisabled && builtinOnly(node) && original.hasSameCaptureOwner(node)) && bounds
            .targetBounds(hit.target)
            .isDefined
        )
      continuingBuiltin = matches.isEmpty && positionalBuiltin
      if matches.size == 1 then captured = captured.map(_.copy(target = matches.head))
      else if !continuingBuiltin then
        captured = None
        owner = None
        continuation = None
        retired = true
    }

  /** `event` with its `clickCount` synthesised, given the clock reading `nowNanos`. Only a `Down` counts. */
  def counted(event: MouseEvent, nowNanos: Long): MouseEvent =
    if event.kind != MouseEventKind.Down then event
    else
      val continues = lastPress.exists { (position, button, at) =>
        position == event.position && button == event.button && nowNanos - at <= PointerCapture.DoubleClickNanos
      }
      count = if continues then count + 1 else 1
      lastPress = Some((event.position, event.button, nowNanos))
      event.copy(clickCount = count)

  /** Releases capture and click history after terminal focus loss or a cancelled gesture. */
  def reset(): Unit =
    captured = None
    owner = None
    retired = true
    topology = Map.empty
    continuation = None
    continuingBuiltin = false
    lastPress = None
    count = 0

  /** The hit an event is routed against: the captured target for a drag or release while one is held, else `hit`. */
  def route(event: MouseEvent, hit: Option[MouseHit]): Option[MouseHit] =
    event.kind match
      case MouseEventKind.Drag | MouseEventKind.Up => if retired then None else captured.orElse(hit)
      case _                                       => hit

  /** Records what the delivery of `event` did: a consumed press captures `hit`, a release lets go. */
  def delivered(
      event: MouseEvent,
      hit: Option[MouseHit],
      consumed: Boolean,
      node: Option[Element] = None,
      shape: Map[InputTarget, CaptureSlot] = Map.empty,
      deliver: Option[MouseEvent => Boolean] = None,
  ): Unit =
    event.kind match
      case MouseEventKind.Down =>
        captured = if consumed then hit else None
        owner = if captured.isDefined then node else None
        topology = if captured.isDefined then shape else Map.empty
        continuation = if captured.isDefined then deliver else None
        retired = false
        continuingBuiltin = false
      case MouseEventKind.Up   =>
        captured = None
        owner = None
        continuation = None
        continuingBuiltin = false
      case _                   => ()

private[dsl] object PointerCapture:

  /** How close together two presses must be to count as one double click: 400 ms, the common desktop default. */
  val DoubleClickNanos: Long = 400L * 1_000_000L
