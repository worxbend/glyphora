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
  */
private[dsl] final case class NodeBoundsEntry(
    id: Int,
    lastDescendantId: Int,
    key: Option[String],
    layoutOnly: Boolean,
    layout: Rect,
    visible: Rect,
)

/** One frame's worth of recorded node bounds, immutable once published. */
private[dsl] final class NodeBoundsFrame(val entries: Vector[NodeBoundsEntry]):

  private val byKey: Map[String, NodeBoundsEntry] =
    entries.iterator.filter(_.key.isDefined).map(entry => entry.key.get -> entry).toMap

  private val byId: Map[Int, NodeBoundsEntry] = entries.iterator.map(entry => entry.id -> entry).toMap

  /** The translated, unclipped rect of the node declared with `.key(key)`, when it was on screen. */
  def boundsOf(key: String): Option[Rect] = byKey.get(key).map(_.layout)

  def entry(id: Int): Option[NodeBoundsEntry] = byId.get(id)

  /** Whether a node that paints something of its own was painted at `position` inside node `id`'s subtree. */
  def paintedWithin(id: Int, lastDescendantId: Int, position: Position): Boolean =
    entries.exists(entry =>
      entry.id > id && entry.id <= lastDescendantId && !entry.layoutOnly && entry.visible.contains(position)
    )

private[dsl] object NodeBoundsFrame:
  val Empty: NodeBoundsFrame = NodeBoundsFrame(Vector.empty)

/** The one per-node bounds recorder: filled during paint by the [[BoundsElement]] wrappers the focus pass adds, read by
  * every consumer that wants to know where a node landed — `boundsOf(key)` for tests, the click-outside backdrop, the
  * element-tree snapshot. There is deliberately no second mechanism for "where was this node painted".
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

  private val recording = mutable.ArrayBuffer.empty[NodeBoundsEntry]

  @volatile private var published: NodeBoundsFrame = NodeBoundsFrame.Empty

  /** Starts a frame: drops what the previous one recorded. */
  def begin(): Unit = recording.clear()

  /** Records `area` for node `id`, mapped onto the frame through the enclosing scroll viewports; a node scrolled fully
    * out of view leaves no entry.
    */
  def record(id: Int, lastDescendantId: Int, key: Option[String], layoutOnly: Boolean, area: Rect): Unit =
    val visible = FrameCoordinates.visible(area)
    if !visible.isEmpty then
      recording += NodeBoundsEntry(id, lastDescendantId, key, layoutOnly, FrameCoordinates.translate(area), visible)

  /** Closes the frame and makes it the one readers see. */
  def publish(): Unit = published = NodeBoundsFrame(recording.toVector)

  /** The last published frame; empty before the first one. */
  def current: NodeBoundsFrame = published

/** Wraps a node during the focus pass so its painted area is recorded into the host's [[NodeBounds]]. Transparent to
  * everything else: props, children, measurement and handlers all read through to `inner`, the way the other decorators
  * in `ElementDecorators` do, so the tree routes and lays out exactly as it would unwrapped.
  *
  * Added only while recording is on, which is what keeps a frame with recording off free of per-node allocations.
  */
private[dsl] final case class BoundsElement(inner: Element, id: Int, lastDescendantId: Int, recorder: NodeBounds)
    extends DecoratingElement:
  type Self = BoundsElement

  /** The wrapped node's children, not the node itself — see `TrackedElement.children` for why. */
  override def children: Seq[Element] = inner.children

  def widget: Widget =
    (area, buffer) =>
      recorder.record(id, lastDescendantId, inner.props.focusKey, inner.layoutOnly, area)
      inner.widget.render(area, buffer)

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
