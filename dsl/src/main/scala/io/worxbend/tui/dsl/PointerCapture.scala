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

/** The pointer bookkeeping one [[ElementHost]] keeps between mouse events: which target a press captured, and how many
  * presses in a row landed on the same cell.
  *
  * '''Capture.''' A `Down` that some handler or built-in consumed captures the pointer for the target it hit: until the
  * matching `Up`, every `Drag` and that `Up` are routed to that target with its press-time layout area, wherever the
  * pointer is — so a slider or a split divider keeps following a drag that has left its hit area. The `Up` releases the
  * capture after it is delivered. A press nobody consumed captures nothing.
  *
  * '''Click counting.''' A `Down` with the same button on the same cell as the previous one, within [[DoubleClick]] of
  * it, counts one more; anything else starts again at 1. The count is stamped on the event as `clickCount` before any
  * handler sees it.
  *
  * Owned by one host and touched only on its render thread, like the focus tracker beside it.
  */
private[dsl] final class PointerCapture:

  private var captured: Option[MouseHit] = None

  private var lastPress: Option[(Position, MouseButton, Long)] = None
  private var count: Int                                       = 0

  /** The target a press captured, if a button is down on one. */
  def capturedHit: Option[MouseHit] = captured

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

  /** The hit an event is routed against: the captured target for a drag or release while one is held, else `hit`. */
  def route(event: MouseEvent, hit: Option[MouseHit]): Option[MouseHit] =
    event.kind match
      case MouseEventKind.Drag | MouseEventKind.Up => captured.orElse(hit)
      case _                                       => hit

  /** Records what the delivery of `event` did: a consumed press captures `hit`, a release lets go. */
  def delivered(event: MouseEvent, hit: Option[MouseHit], consumed: Boolean): Unit =
    event.kind match
      case MouseEventKind.Down => captured = if consumed then hit else None
      case MouseEventKind.Up   => captured = None
      case _                   => ()

private[dsl] object PointerCapture:

  /** How close together two presses must be to count as one double click: 400 ms, the common desktop default. */
  val DoubleClickNanos: Long = 400L * 1_000_000L
