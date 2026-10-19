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
  // keyed by tab index rather than by request identity: every focusable under the view reports here on every frame,
  // and building an identity string for each would cost more than the rest of the report
  private val focusables                      = collection.mutable.LongMap.empty[Rect]
  private val marks                           = collection.mutable.HashMap.empty[String, Rect]

  def offerVisible(id: String, rect: Rect): Unit =
    if !marks.contains(id) then marks.update(id, rect)
    if visible.isEmpty then visible = Some(RevealProbe.visibleId(id) -> rect)

  def offerFocused(index: Int, rect: Rect): Unit =
    placeFocusable(index, rect)
    if followFocus && focused.isEmpty then focused = Some(RevealProbe.focusId(index) -> rect)

  def placeFocusable(index: Int, rect: Rect): Unit =
    if !focusables.contains(index.toLong) then focusables.update(index.toLong, rect)

  /** The request to act on: an explicit `.ensureVisible` wins over following focus. */
  def chosen: Option[(String, Rect)] = visible.orElse(focused)

  /** Where every request this render could have made landed, for the next frame to look its request up in. */
  def placements: RevealPlacements = RevealPlacements(focusables, marks)

  /** `rect`, in this view's content coordinates, as the enclosing view sees it: moved to where the view draws its
    * window and clipped to that window, since that is the most of it the enclosing view can show. A rect scrolled out
    * of the window stands for the whole window.
    */
  def toParent(rect: Rect): Rect =
    val moved = Rect(area.x + rect.x, area.y + rect.y - state.offset, rect.width, rect.height)
    val shown = moved.intersection(area)
    if shown.isEmpty then area else shown

/** Where one paint of a scroll view's content placed every focusable (by tab index) and every `.ensureVisible` mark (by
  * its id), in content coordinates — kept on the view's state for the next frame.
  */
private[dsl] final class RevealPlacements(
    focusables: collection.Map[Long, Rect],
    marks: collection.Map[String, Rect],
):

  /** The rect the request identified as `id` ([[RevealProbe.focusId]] or [[RevealProbe.visibleId]]) landed on. */
  def get(id: String): Option[Rect] =
    if id.startsWith(RevealProbe.FocusPrefix) then
      id.stripPrefix(RevealProbe.FocusPrefix).toLongOption.flatMap(focusables.get)
    else if id.startsWith(RevealProbe.VisiblePrefix) then marks.get(id.stripPrefix(RevealProbe.VisiblePrefix))
    else None

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
  def collecting[A](requests: RevealRequests)(body: => A): A =
    val context  = FrameContext.current
    val previous = context.reveals
    context.reveals = requests :: previous
    try body
    finally context.reveals = previous

  val FocusPrefix: String   = "focus:"
  val VisiblePrefix: String = "visible:"

  /** The request identity of the focusable in tab position `index`. */
  def focusId(index: Int): String = FocusPrefix + index

  /** The request identity of an element marked `.ensureVisible` and identified by `id`. */
  def visibleId(id: String): String = VisiblePrefix + id

  /** The element in tab position `index` was handed `area`; `focused` says whether it is the focused one. Runs for
    * every focusable on every frame, so it is a plain loop over the (usually empty) stack.
    */
  def tracked(index: Int, area: Rect, focused: Boolean): Unit =
    var remaining = FrameContext.current.reveals
    var rect      = area
    while remaining.nonEmpty do
      val requests = remaining.head
      if focused then requests.offerFocused(index, rect) else requests.placeFocusable(index, rect)
      remaining = remaining.tail
      if remaining.nonEmpty then rect = requests.toParent(rect)

  /** An element marked `.ensureVisible`, identified by `id`, was handed `area`. */
  def visible(id: String, area: Rect): Unit =
    var remaining = FrameContext.current.reveals
    var rect      = area
    while remaining.nonEmpty do
      val requests = remaining.head
      requests.offerVisible(id, rect)
      remaining = remaining.tail
      if remaining.nonEmpty then rect = requests.toParent(rect)
