package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, StatefulWidget, Widget}

/** Caller-owned scroll offset for a [[ScrollView]].
  *
  * Render-thread-only, and mutating it does not by itself schedule a frame. This is a plain mutable object, invisible
  * to the reactive layer: a background result written straight into it stays off screen until something unrelated
  * happens to repaint. Pair the mutation with a `Signal` write, or call `TuiApp.requestRedraw()` from the same
  * render-thread callback that made it.
  */
final class ScrollViewState:
  var offset: Int                              = 0
  private[widgets] var lastViewportHeight: Int = 1
  private[widgets] var lastContentHeight: Int  = 0

  /** The furthest down the content can be scrolled: the last row of content, less a viewport's worth.
    *
    * Both figures are recorded by the most recent render, because the state has no way of knowing the size of an area
    * it has never been drawn into. On a state that has never rendered this is `0`, so every move below is a no-op until
    * the first frame — which is the right answer, since there is no content to move over yet.
    */
  private[widgets] def maxOffset: Int = math.max(0, lastContentHeight - lastViewportHeight)

  /** Moves the offset `delta` rows — negative is toward the top — and clamps it into the scrollable range. Every other
    * move on this class goes through here, so there is one owner of the clamp.
    */
  def scrollBy(delta: Int): Unit =
    offset = math.max(0, math.min(maxOffset, offset + delta))

  /** Scrolls so that content row `row` is the first one visible, clamped into the scrollable range. */
  def scrollTo(row: Int): Unit =
    offset = math.max(0, math.min(maxOffset, row))

  /** Scrolls as little as possible to bring content rows `top` until `top + height` into view: a range below the window
    * ends at its bottom edge, a range above it starts at its top edge, and a range already visible changes nothing. A
    * range taller than the viewport is left alone while any of it is in view — it cannot fit, and jumping to its first
    * row would yank away the part the reader is looking at (a click into a tall pane focuses it) — and otherwise shows
    * its first row. Clamped into the scrollable range like every other move, and — like them — a no-op before the first
    * render has recorded the viewport.
    *
    * This replaces the hand-written `scrollTo` arithmetic a view used to do to keep a selection on screen; the DSL's
    * `scrollView` calls it for the focused child and for elements marked `.ensureVisible`.
    */
  def reveal(top: Int, height: Int): Unit =
    val bottom   = top + math.max(0, height)
    val overlaps = top < offset + lastViewportHeight && bottom > offset
    if height > lastViewportHeight && overlaps then ()
    else if top < offset then scrollTo(top)
    else if bottom > offset + lastViewportHeight then scrollTo(math.min(top, bottom - lastViewportHeight))

  /** The identity of the last visibility request the DSL's `scrollView` acted on, so a request that persists across
    * frames scrolls once rather than pinning the view; `None` once no request is present. Written only by the DSL.
    */
  private[tui] var revealedRequest: Option[String] = None

  def scrollUp(count: Int = 1): Unit =
    scrollBy(-count)

  def scrollDown(count: Int = 1): Unit =
    scrollBy(count)

  /** Jumps to the top of the content — the Home key's move. */
  def first(): Unit =
    offset = 0

  /** Jumps to the bottom of the content — the End key's move. A no-op before the first render, which is when the
    * content and viewport heights this depends on are first recorded.
    */
  def last(): Unit =
    offset = maxOffset

  /** Scrolls up by one viewport — the PageUp move. Falls back to a single row before the first render, so a key press
    * that arrives early still does something rather than nothing.
    */
  def pageUp(): Unit =
    scrollBy(-math.max(1, lastViewportHeight))

  /** Scrolls down by one viewport — the PageDown move, the mirror of [[pageUp]]. */
  def pageDown(): Unit =
    scrollBy(math.max(1, lastViewportHeight))

/** A vertically scrollable window over content taller than the viewport.
  *
  * Each frame the content is handed its full `contentHeight` rect — it lays itself out as if all of it were on screen —
  * but the offscreen buffer it draws into covers only the scrolled-to window, so rows above and below are clipped by
  * the buffer's own bounds instead of being drawn and thrown away. That keeps the per-frame cost proportional to the
  * viewport rather than to the document: a 10 000-row content used to allocate two 2-million-element arrays and fill
  * them on every keystroke. The window is then blitted into place and a scrollbar drawn on the right edge when the
  * content overflows. Content height is explicit (the caller knows its data) — an intrinsic-measure pass is future
  * work.
  */
final case class ScrollView(
    content: Widget,
    contentHeight: Int,
    showScrollbar: Boolean = true,
) extends StatefulWidget[ScrollViewState]:

  def render(area: Rect, buffer: Buffer, state: ScrollViewState): Unit =
    if !area.isEmpty && contentHeight > 0 then
      val overflows      = contentHeight > area.height
      val scrollbarWidth = if showScrollbar && overflows then 1 else 0
      val contentWidth   = area.width - scrollbarWidth
      if contentWidth > 0 then
        state.lastViewportHeight = area.height
        state.lastContentHeight = contentHeight
        state.offset = math.max(0, math.min(state.offset, contentHeight - area.height))
        // the window in the content's own coordinate space: rows `state.offset` up to the bottom of the viewport
        val window    = Rect(0, state.offset, contentWidth, math.min(area.height, contentHeight - state.offset))
        val offscreen = Buffer(window)
        // the content still receives the whole `contentHeight` rect, so anything that positions itself relative to the
        // document (a header at row 0, a footer at the last row) lands where it always did; the buffer clips the rest
        content.render(Rect(0, 0, contentWidth, contentHeight), offscreen)
        buffer.blit(offscreen, area.position, window)
        if scrollbarWidth == 1 then Scrollbar(contentHeight, state.offset).render(area, buffer)
