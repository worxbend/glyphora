package io.worxbend.tui.dsl

import io.worxbend.tui.core.Rect

/** The visibility requests the content of one `scrollView` made during one render: the focused child's area, when the
  * view follows focus, and the first `.ensureVisible` element's area. Each carries an identity, so a request that is
  * still present on the next frame can be told apart from a new one.
  *
  * Areas are in the scroll view's *content* coordinates — the rects its content subtree is handed while it renders into
  * the offscreen buffer — which is the space `ScrollViewState.reveal` takes. Owned by the render that created it and
  * touched only on that render thread.
  *
  * `atEnd` is whether the view was scrolled to the end of its content when this render began; only then does a
  * follow-the-tail leaf (a `markdownStream` in a `scrollView`) ask to keep its newest row in view.
  */
private[dsl] final class RevealRequests(val followFocus: Boolean, val atEnd: Boolean = false):
  private var visible: Option[(String, Rect)] = None
  private var tail: Option[(String, Rect)]    = None
  private var focused: Option[(String, Rect)] = None

  def offerVisible(id: String, area: Rect): Unit = if visible.isEmpty then visible = Some(id -> area)

  def offerTail(id: String, area: Rect): Unit = if atEnd && tail.isEmpty then tail = Some(id -> area)

  def offerFocused(id: String, area: Rect): Unit = if followFocus && focused.isEmpty then focused = Some(id -> area)

  /** The request to act on: an explicit `.ensureVisible` wins over a followed tail, which wins over following focus. */
  def chosen: Option[(String, Rect)] = visible.orElse(tail).orElse(focused)

/** Routes visibility requests to the innermost `scrollView` rendering on this thread.
  *
  * A scroll view pushes its [[RevealRequests]] for exactly the duration of its content render and pops it in a
  * `finally`, the way [[FrameCoordinates]] scopes a viewport transform; the focused [[TrackedElement]] and every
  * [[RevealElement]] report into whichever collector is on top. With no scroll view rendering — a tree painted outside
  * one — a report goes nowhere. A per-thread stack in [[FrameContext]], so independently running render threads never
  * share one.
  */
private[dsl] object RevealProbe:
  def collecting[A](requests: RevealRequests)(body: => A): A =
    val context  = FrameContext.current
    val previous = context.reveals
    context.reveals = requests :: previous
    try body
    finally context.reveals = previous

  /** The element in tab position `index` is focused and was handed `area`. */
  def focused(index: Int, area: Rect): Unit =
    FrameContext.current.reveals.headOption.foreach(_.offerFocused(s"focus:$index", area))

  /** An element marked `.ensureVisible`, identified by `id`, was handed `area`. */
  def visible(id: String, area: Rect): Unit =
    FrameContext.current.reveals.headOption.foreach(_.offerVisible(s"visible:$id", area))

  /** A follow-the-tail leaf, identified by `id`, has its newest row at `area`; acted on only when the enclosing view
    * was at its end when the render began. `id` should change whenever the tail moves, so each new row is a new
    * request.
    */
  def tail(id: String, area: Rect): Unit =
    FrameContext.current.reveals.headOption.foreach(_.offerTail(s"tail:$id", area))
