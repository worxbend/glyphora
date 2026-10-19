package io.worxbend.tui.dsl

import io.worxbend.tui.core.Rect
import io.worxbend.tui.widgets.ScrollViewState

/** The visibility requests the content of one `scrollView` made during one render — the focused child's area, when the
  * view follows focus, and the first `.ensureVisible` element's area — plus where every request that *could* have been
  * made landed (each focusable and each mark), which the next frame uses to scroll before it paints. Each carries an
  * identity, so a request that is still present on the next frame can be told apart from a new one.
  *
  * Areas are in the scroll view's *content* coordinates — the rects its content subtree is handed while it renders into
  * the offscreen buffer — which is the space `ScrollViewState.reveal` takes. `area` is where the view itself is drawn,
  * in its parent's coordinates, which is how [[toParent]] carries a request out to an enclosing scroll view. Owned by
  * the render that created it and touched only on that render thread.
  */
private[dsl] final class RevealRequests(val followFocus: Boolean, area: Rect, state: ScrollViewState):
  private var visible: Option[(String, Rect)] = None
  private var focused: Option[(String, Rect)] = None
  private val placed                          = collection.mutable.HashMap.empty[String, Rect]

  def offerVisible(id: String, rect: Rect): Unit =
    place(id, rect)
    if visible.isEmpty then visible = Some(id -> rect)

  def offerFocused(id: String, rect: Rect): Unit =
    place(id, rect)
    if followFocus && focused.isEmpty then focused = Some(id -> rect)

  def place(id: String, rect: Rect): Unit = if !placed.contains(id) then placed.update(id, rect)

  /** The request to act on: an explicit `.ensureVisible` wins over following focus. */
  def chosen: Option[(String, Rect)] = visible.orElse(focused)

  /** Every request identity this render could have made, with where it landed. */
  def placements: collection.Map[String, Rect] = placed

  /** `rect`, in this view's content coordinates, as the enclosing view sees it: moved to where the view draws its
    * window and clipped to that window, since that is the most of it the enclosing view can show. A rect scrolled out
    * of the window stands for the whole window.
    */
  def toParent(rect: Rect): Rect =
    val moved = Rect(area.x + rect.x, area.y + rect.y - state.offset, rect.width, rect.height)
    val shown = moved.intersection(area)
    if shown.isEmpty then area else shown

/** Routes visibility requests to every `scrollView` rendering on this thread around the element that makes them.
  *
  * A scroll view pushes its [[RevealRequests]] for exactly the duration of its content render and pops it in a
  * `finally`, the way [[FrameCoordinates]] scopes a viewport transform; the [[TrackedElement]]s and every
  * [[RevealElement]] report into each collector on the stack, innermost first, the rect carried outward through each
  * view's [[RevealRequests.toParent]] — so nested views each scroll their part of the way. With no scroll view
  * rendering — a tree painted outside one — a report goes nowhere. A thread-local stack, so independently running
  * render threads never share one.
  */
private[dsl] object RevealProbe:
  private val stack: ThreadLocal[List[RevealRequests]] = ThreadLocal.withInitial(() => Nil)

  def collecting[A](requests: RevealRequests)(body: => A): A =
    val previous = stack.get()
    stack.set(requests :: previous)
    try body
    finally if previous.isEmpty then stack.remove() else stack.set(previous)

  /** The request identity of the focusable in tab position `index`. */
  def focusId(index: Int): String = s"focus:$index"

  /** The request identity of an element marked `.ensureVisible` and identified by `id`. */
  def visibleId(id: String): String = s"visible:$id"

  /** The element in tab position `index` was handed `area`; `focused` says whether it is the focused one. */
  def tracked(index: Int, area: Rect, focused: Boolean): Unit =
    report(area)((requests, rect) =>
      if focused then requests.offerFocused(focusId(index), rect) else requests.place(focusId(index), rect)
    )

  /** The element in tab position `index` is focused and was handed `area`. */
  def focused(index: Int, area: Rect): Unit = tracked(index, area, focused = true)

  /** An element marked `.ensureVisible`, identified by `id`, was handed `area`. */
  def visible(id: String, area: Rect): Unit =
    report(area)((requests, rect) => requests.offerVisible(visibleId(id), rect))

  private def report(area: Rect)(offer: (RevealRequests, Rect) => Unit): Unit =
    var rect      = area
    var remaining = stack.get()
    while remaining.nonEmpty do
      offer(remaining.head, rect)
      rect = remaining.head.toParent(rect)
      remaining = remaining.tail
