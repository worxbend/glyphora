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
  * "Thread-scoped" means each render thread gets its own queue — a [[FrameSlot]] — so one runner never draws another
  * runner's popups.
  */
private[dsl] object PortalQueue:

  /** The portals collected so far in the current pass, in the order they were rendered. */
  private[dsl] final class Pending:
    val entries: mutable.ArrayBuffer[(Rect, Element)] = mutable.ArrayBuffer.empty

  /** Unarmed until a frame root arms this thread, and again once it disarms. */
  private val current: FrameSlot[Pending] =
    FrameSlot(() => new Pending, _.portals, (context, pending) => context.portals = pending)

  /** Arms collection for one render pass on this thread, discarding anything an earlier pass left behind. */
  def begin(): Unit = current.arm()

  /** Disarms collection. Called once the frame root has finished draining, so a render that happens outside a frame
    * falls back to drawing in place instead of queueing into a pass that will never be drained.
    */
  def end(): Unit = current.disarm()

  /** Isolates nested hosts and restores the outer queue even when painting fails. */
  def during[A](body: => A): A = current.around(_ => body)

  /** Whether a frame root is collecting on this thread — that is, whether [[offer]] will actually be drawn later. */
  def isCollecting: Boolean = current.armed.isDefined

  /** Hands `content` to the frame root, translating its local `target` to screen coordinates without viewport clipping.
    * It is drawn after the tree, once the enclosing content coordinate scopes have ended. Does nothing when nothing is
    * collecting; callers check [[isCollecting]] first and draw in place instead.
    */
  def offer(target: Rect, content: Element): Unit =
    current.armed.foreach(pending => pending.entries += (FrameCoordinates.translate(target) -> content))

  /** How many portals are queued so far in this pass — a point [[rollback]] can return to. */
  def mark: Int = current.armed.fold(0)(_.entries.size)

  /** Forgets every portal queued after `mark`: what a subtree that is about to be painted a second time in the same
    * pass queued the first time, so the frame root does not draw it twice.
    */
  def rollback(mark: Int): Unit =
    current.armed.foreach(pending => pending.entries.dropRightInPlace(math.max(0, pending.entries.size - mark)))

  /** Takes everything queued so far and empties the queue.
    *
    * The queue is emptied rather than merely read so that a portal opened *by* portal content is picked up by the next
    * round of draining instead of being drawn twice or dropped.
    */
  def drain(): Seq[(Rect, Element)] =
    current.armed match
      case None          => Seq.empty
      case Some(pending) =>
        val taken = pending.entries.toSeq
        pending.entries.clear()
        taken
