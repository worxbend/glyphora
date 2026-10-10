package io.worxbend.tui.dsl

import io.worxbend.tui.core.Rect

import scala.collection.mutable

/** Where a [[PortalElement]] parks its content during a render pass so that the frame root can draw it afterwards, on
  * top of everything the tree has already painted.
  *
  * "Frame-scoped" means the queue is armed for exactly one frame: [[begin]] arms it, [[drain]] takes what has piled up,
  * and [[end]] disarms it again. A portal rendered while nothing has armed the queue — a construction test that calls
  * `element.widget.render(...)` directly, with no [[TuiApp]] around it — has no frame root to hand its content to, so
  * it paints in place, clipped to its parent, rather than disappearing.
  *
  * "Thread-scoped" means each render thread gets its own queue. Every `Runner` owns its own render thread and several
  * runners can live in one JVM at once, so a shared queue would let one runner draw another runner's popups. A
  * `ThreadLocal` gives each of them a private one for free, and there is never more than one writer per queue because
  * the whole render pass happens on that one thread.
  */
private[dsl] object PortalQueue:

  /** The portals collected so far in the current pass, in the order they were rendered. */
  private final class Pending:
    val entries: mutable.ArrayBuffer[(Rect, Element)] = mutable.ArrayBuffer.empty

  /** `None` until a frame root arms this thread, and again once it disarms. Removing the value puts the initial `None`
    * back, so "disarmed" and "never armed" are the same state.
    */
  private val current: ThreadLocal[Option[Pending]] = ThreadLocal.withInitial(() => Option.empty[Pending])

  /** Arms collection for one render pass on this thread, discarding anything an earlier pass left behind. */
  def begin(): Unit = current.set(Some(new Pending))

  /** Disarms collection. Called once the frame root has finished draining, so a render that happens outside a frame
    * falls back to drawing in place instead of queueing into a pass that will never be drained.
    */
  def end(): Unit = current.remove()

  /** Isolates nested hosts and restores the outer queue even when painting fails. */
  def during[A](body: => A): A =
    val previous = current.get()
    begin()
    try body
    finally current.set(previous)

  /** Whether a frame root is collecting on this thread — that is, whether [[offer]] will actually be drawn later. */
  def isCollecting: Boolean = current.get().isDefined

  /** Hands `content` to the frame root, translating its local `target` to screen coordinates without viewport clipping.
    * It is drawn after the tree, once the enclosing content coordinate scopes have ended. Does nothing when nothing is
    * collecting; callers check [[isCollecting]] first and draw in place instead.
    */
  def offer(target: Rect, content: Element): Unit =
    current.get().foreach(pending => pending.entries += (FrameCoordinates.translate(target) -> content))

  /** Takes everything queued so far and empties the queue.
    *
    * The queue is emptied rather than merely read so that a portal opened *by* portal content is picked up by the next
    * round of draining instead of being drawn twice or dropped.
    */
  def drain(): Seq[(Rect, Element)] =
    current.get() match
      case None          => Seq.empty
      case Some(pending) =>
        val taken = pending.entries.toSeq
        pending.entries.clear()
        taken
