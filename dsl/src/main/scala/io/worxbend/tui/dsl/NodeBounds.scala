package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Position, Rect, Widget}

import scala.collection.mutable

/** Where one node of the decorated tree was painted this frame.
  *
  * `id` is the node's position in the decorated tree's depth-first pre-order and `lastDescendantId` the id of the last
  * node in its subtree, so "is `other` inside this node's subtree" is the range test `id < other.id <=
  * lastDescendantId` with no tree walk. `layout` is the rect the node was handed, translated out of every enclosing
  * scroll viewport onto the frame but not clipped; `visible` is the part of it actually on screen after clipping to
  * each viewport. A node scrolled entirely out of view is not recorded at all.
  *
  * `layoutOnly` marks a node that paints nothing of its own — a `row`, a `column`, a `spacer`, a `layers` — so a query
  * asking "did this press land on something drawn" can skip it; see [[Element.layoutOnly]].
  *
  * `roles` is what the pointer gestures can do with the node — select its text, drag it, drop onto it — see
  * [[PointerRoles]]. A node with a role is recorded on every frame, whether or not full recording is on.
  */
private[dsl] final case class NodeBoundsEntry(
    id: Int,
    lastDescendantId: Int,
    key: Option[String],
    layoutOnly: Boolean,
    layout: Rect,
    visible: Rect,
    roles: PointerRoles = PointerRoles.Empty,
    anchor: Option[String] = None,
)

/** One frame's worth of recorded node bounds, immutable once published.
  *
  * The two indexes are lazy on purpose: a frame is published on every paint, and the one consumer that reads every
  * frame — the click-outside backdrop — scans `entries`, so building a map per frame for the lookups a test or a
  * snapshot makes now and then would be most of the recorder's cost for nothing.
  */
private[dsl] final class NodeBoundsFrame(val entries: collection.IndexedSeq[NodeBoundsEntry]):

  private lazy val byKey: Map[String, NodeBoundsEntry] =
    entries.iterator.filter(_.key.isDefined).map(entry => entry.key.get -> entry).toMap

  private lazy val byId: Map[Int, NodeBoundsEntry] = entries.iterator.map(entry => entry.id -> entry).toMap

  /** The translated, unclipped rect of the node declared with `.key(key)`, when it was on screen. */
  def boundsOf(key: String): Option[Rect] = byKey.get(key).map(_.layout)

  def entry(id: Int): Option[NodeBoundsEntry] = byId.get(id)

  /** Whether a node that paints something of its own was painted at `position` inside node `id`'s subtree. */
  def paintedWithin(id: Int, lastDescendantId: Int, position: Position): Boolean =
    entries.exists(entry =>
      entry.id > id && entry.id <= lastDescendantId && !entry.layoutOnly && entry.visible.contains(position)
    )

  /** The innermost node at `position` that `role` accepts — the last in pre-order, which is the deepest and the one
    * painted on top.
    */
  def innermostAt(position: Position)(role: PointerRoles => Boolean): Option[NodeBoundsEntry] =
    entries.iterator.filter(entry => role(entry.roles) && entry.visible.contains(position)).maxByOption(_.id)

private[dsl] object NodeBoundsFrame:
  val Empty: NodeBoundsFrame = NodeBoundsFrame(Vector.empty)

/** The one recorder of where things were painted: filled during paint by the [[BoundsElement]] wrappers the focus pass
  * adds and by the input-target wrappers (every focusable and every node with mouse behaviour), read by every consumer
  * that wants to know where something landed — `boundsOf(key)` for tests, the click-outside backdrop, the element-tree
  * snapshot, and, through the input-target table, hit-testing, pointer capture, `MouseContext.area` and spatial
  * navigation. There is deliberately no second mechanism for "where was this painted": both tables are placed by the
  * one [[placed]], so a node and the input target wrapping it cannot disagree about translation or clipping.
  *
  * The input-target table is *live*: it is cleared when the focus tracker reconciles a new frame and filled as that
  * frame paints, and an event handled between frames reads what the last paint left. It is recorded on every frame,
  * whatever [[ElementHost.recordingBounds]] says, because routing a click needs it.
  *
  * '''Ownership and threads.''' One recorder belongs to one [[ElementHost]]. [[begin]] and [[record]] are called on
  * that host's render thread only, during a paint; [[publish]] closes the frame on the same thread. The published
  * [[NodeBoundsFrame]] is immutable and read through a volatile reference, so a test thread that has already waited for
  * the frame to be drawn may read [[current]] without racing the paint that produced it.
  *
  * Recording is off until a consumer asks for it; while off, the focus pass adds no wrappers and nothing here is
  * touched per frame.
  */
private[dsl] final class NodeBounds:

  // swapped out on publish rather than copied: the published buffer is never written again
  private var recording = mutable.ArrayBuffer.empty[NodeBoundsEntry]

  /** Where each input target landed this frame, with its paint sequence — see [[PaintedArea]]. */
  private val targets = mutable.Map.empty[InputTarget, PaintedArea]

  /** How many input targets have been recorded so far this frame; the sequence the next one is stamped with. */
  private var targetSequence = 0

  /** `area` (in the coordinates of whatever is painting it) as `(layout, visible)` on the frame: translated out of
    * every enclosing scroll viewport, and clipped to each of them; `None` when no viewport lets any of it through.
    */
  private def placed(area: Rect): Option[(Rect, Rect)] =
    val visible = FrameCoordinates.visible(area)
    Option.when(!visible.isEmpty)((FrameCoordinates.translate(area), visible))

  @volatile private var published: NodeBoundsFrame = NodeBoundsFrame.Empty

  /** Starts a frame: drops what the previous one recorded. */
  def begin(): Unit = recording.clear()

  /** Records `area` for node `id`, mapped onto the frame through the enclosing scroll viewports; a node scrolled fully
    * out of view leaves no entry.
    */
  def record(
      id: Int,
      lastDescendantId: Int,
      key: Option[String],
      layoutOnly: Boolean,
      area: Rect,
      roles: PointerRoles = PointerRoles.Empty,
      anchor: Option[String] = None,
  ): Unit =
    placed(area).foreach { (layout, visible) =>
      recording += NodeBoundsEntry(id, lastDescendantId, key, layoutOnly, layout, visible, roles, anchor)
    }

  /** Closes the frame and makes it the one readers see. */
  def publish(): Unit =
    published = NodeBoundsFrame(recording)
    // the next frame records into a fresh buffer sized like this one, so no frame pays to copy what it recorded
    recording = new mutable.ArrayBuffer[NodeBoundsEntry](math.max(16, recording.size))

  /** The last published frame; empty before the first one. */
  def current: NodeBoundsFrame = published

  /** Records where input `target` rendered: its translated layout rect and its clipped hit rect, stamped with the next
    * paint sequence. A target scrolled entirely out of view is not recorded, so neither hit testing nor wheel bubbling
    * can reach it. Render thread only.
    */
  def recordTarget(
      target: InputTarget,
      area: Rect,
      passThrough: Boolean = false,
      key: Option[String] = None,
      painted: Option[Rect] = None,
      wheelPassThrough: Boolean = false,
  ): Unit =
    placed(painted.getOrElse(area)).foreach { (_, visible) =>
      val layout = FrameCoordinates.translate(area)
      targets(target) = PaintedArea(layout, visible, targetSequence, passThrough, key, wheelPassThrough)
      targetSequence += 1
    }

  /** Starts a frame's input-target table: what the previous frame recorded is dropped and the sequence restarts. */
  def clearTargets(): Unit =
    targets.clear()
    targetSequence = 0

  /** Where input `target` rendered on the frame being painted or last painted. Render thread only. */
  def targetBounds(target: InputTarget): Option[PaintedArea] = targets.get(target)

  /** The input target painted last at screen position `pos` — the one the user sees there — with its unclipped layout
    * rect. Render thread only.
    */
  def targetAt(pos: Position, wheel: Boolean = false): Option[MouseHit] =
    targets
      .filter((_, area) => !area.passThrough && !(wheel && area.wheelPassThrough) && area.hitArea.contains(pos))
      .maxByOption((_, area) => area.sequence)
      .map((target, area) => MouseHit(target, area.layoutArea))

  /** The pass-through targets at `pos` painted above the target [[targetAt]] resolves there — the layers that hear a
    * press on its way to what is under it rather than taking it, such as a `.passThroughOutside` dropdown's scrim.
    * Topmost first. Render thread only.
    */
  def passThroughAt(pos: Position): Seq[MouseHit] =
    val floor = targetAt(pos).flatMap(hit => targets.get(hit.target)).fold(-1)(_.sequence)
    targets.toSeq
      .filter((_, area) => area.passThrough && area.sequence > floor && area.hitArea.contains(pos))
      .sortBy((_, area) => -area.sequence)
      .map((target, area) => MouseHit(target, area.layoutArea))

/** Wraps a node during the focus pass so its painted area is recorded into the host's [[NodeBounds]]. Transparent to
  * everything else: props, children, measurement and handlers all read through to `inner`, the way the other decorators
  * in `ElementDecorators` do, so the tree routes and lays out exactly as it would unwrapped.
  *
  * Added to every node only while recording is on, which is what keeps a frame with recording off free of per-node
  * allocations. A node with [[PointerRoles]] is wrapped even while full recording is off, with `gestures` set: after
  * the node paints, the gestures draw their overlay on it — the selection highlight, a drop target's hover cue, a
  * dragged node's ghost — which is how they are composed into the frame without any widget knowing about them.
  *
  * `anchor` is the identity a mouse selection holds on to across frames — the node's key, or its path of child
  * positions from the root — set only on a selectable node; see [[TextSelection]].
  */
private[dsl] final case class BoundsElement(
    inner: Element,
    id: Int,
    lastDescendantId: Int,
    recorder: NodeBounds,
    roles: PointerRoles = PointerRoles.Empty,
    gestures: Option[PointerGestures] = None,
    anchor: Option[String] = None,
) extends DecoratingElement:
  type Self = BoundsElement

  /** The wrapped node's children, not the node itself — see `TrackedElement.children` for why. */
  override def children: Seq[Element] = inner.children

  def widget: Widget =
    (area, buffer) =>
      if !inner.layoutOnly then CaretRegister.occlude(inner.paintedRect(area))
      recorder.record(
        id,
        lastDescendantId,
        inner.props.focusKey,
        inner.layoutOnly,
        inner.paintedRect(area),
        roles,
        anchor,
      )
      inner.widget.render(area, buffer)
      if !roles.isEmpty then gestures.foreach(_.painted(id, roles, inner, area, buffer, anchor))

  /** A [[BoundsAwareElement]] underneath gets the behaviour that reads where its subtree was painted; every other
    * node's built-in passes through untouched. The frame is looked up when the event arrives, so the behaviour sees the
    * paint that preceded the event and never the one in progress.
    */
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    inner match
      case aware: BoundsAwareElement =>
        Some((event, area) =>
          aware.builtinMouseHandlerWithBounds(NodeSubtreeBounds(recorder.current, id, lastDescendantId))(event, area)
        )
      case _                         => inner.builtinMouseHandler

  private[dsl] def withProps(props: ElementProps): BoundsElement = copy(inner = inner.withProps(props))

  private[dsl] override def withChildren(children: Seq[Element]): BoundsElement =
    copy(inner = inner.withChildren(children))

/** The painted bounds of one node's descendants on the last published frame — what a [[BoundsAwareElement]]'s mouse
  * behaviour is handed in place of a bare event.
  */
private[dsl] final case class NodeSubtreeBounds(frame: NodeBoundsFrame, id: Int, lastDescendantId: Int):

  /** Whether some descendant that paints something of its own — not a `row`, `column` or `spacer` — covers `position`.
    */
  def paintedAt(position: Position): Boolean = frame.paintedWithin(id, lastDescendantId, position)

/** A node whose framework mouse behaviour needs to know where its own subtree was painted — the click-outside backdrop,
  * which has to tell a press on the dialog's border from a press beside it.
  *
  * While bounds are being recorded, [[BoundsElement]] runs [[builtinMouseHandlerWithBounds]] in place of the node's
  * plain `builtinMouseHandler`. While they are not, the plain handler runs — and meeting one of these during the focus
  * pass is what switches recording on for the frames that follow, so the plain handler is only ever the first-frame
  * fallback on a terminal that was not recording yet.
  */
private[dsl] trait BoundsAwareElement extends Element:
  private[dsl] def builtinMouseHandlerWithBounds(subtree: NodeSubtreeBounds): BuiltinMouseHandler
