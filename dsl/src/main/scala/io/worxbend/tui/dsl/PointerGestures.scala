package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, MouseButton, MouseEvent, MouseEventKind, Rect}

/** What the host's pointer gestures can do with one node: select its text, drag it away carrying `drag`, drop a payload
  * onto it through `drop`. Decided by the focus pass, from the node's props and type, and recorded with the node's
  * bounds so a press can find the node it landed on.
  */
private[dsl] final case class PointerRoles(
    selectable: Boolean,
    drag: Option[DragPayload] = None,
    drop: Option[DropTarget] = None,
):
  def isEmpty: Boolean = !selectable && drag.isEmpty && drop.isEmpty

private[dsl] object PointerRoles:
  val Empty: PointerRoles = PointerRoles(selectable = false)

/** The pointer gestures one [[ElementHost]] runs on top of ordinary event routing: mouse [[TextSelection]] and drag and
  * drop ([[DragGesture]]).
  *
  * '''Routing.''' A gesture in progress takes the events that continue it before any element sees them ([[intercept]]);
  * a gesture starts from a press only once the elements have had it and declined it ([[afterDelivery]]), so a button
  * inside a selectable panel still clicks.
  *
  * '''Composition.''' Each node with a role is recorded into the host's one [[NodeBounds]] recorder by its
  * [[BoundsElement]], and that wrapper calls [[painted]] straight after the node paints — the frame is composed with
  * the gesture's overlay without a pass of its own over the tree.
  *
  * '''Ownership and threads.''' Owned by one host and touched only on its render thread.
  */
private[dsl] final class PointerGestures(val recorder: NodeBounds):

  val selection: TextSelection = TextSelection()

  val drag: DragGesture = DragGesture()

  /** The overlay for node `id`, just painted as `node` into `area`. */
  def painted(
      id: Int,
      roles: PointerRoles,
      node: Element,
      area: Rect,
      buffer: Buffer,
      anchor: Option[String] = None,
  ): Unit =
    if roles.selectable then selection.painted(TextSelection.anchorOf(id, anchor), area, buffer, node.props.focusStyle)
    if roles.drag.isDefined || roles.drop.isDefined then drag.painted(id, node, area, buffer)

  /** Called once the frame is painted. */
  def afterPaint(): Unit = selection.afterPaint()

  /** `Some(redraw)` when a gesture in progress takes `event`, before any element sees it; `None` routes it as usual. */
  def intercept(event: MouseEvent): Option[Boolean] =
    val dragged = drag.intercept(event, recorder.current)
    if dragged.isDefined then dragged
    else if selection.isDragging then
      event.kind match
        case MouseEventKind.Drag =>
          selection.extend(event.position)
          Some(true)
        case MouseEventKind.Up   =>
          selection.release(event.position, AppClipboard.current)
          Some(true)
        case _                   => None
    else None

  /** Starts a gesture from a press the elements declined, and clears the selection on any press. `true` when the next
    * frame looks different.
    */
  def afterDelivery(event: MouseEvent, consumed: Boolean): Boolean =
    if event.kind != MouseEventKind.Down then false
    else
      val cleared = selection.clear()
      // a draggable node arms a drag whether or not its own press handler took the press: it starts only on motion
      if event.button == MouseButton.Left && drag.arm(event, recorder.current) then cleared
      else if consumed || event.button != MouseButton.Left || !selection.enabled then cleared
      else
        recorder.current.innermostAt(event.position)(_.selectable) match
          case Some(entry) =>
            selection.begin(entry, event.position, event.clickCount)
            true
          case None        => cleared
