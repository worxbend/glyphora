package io.worxbend.tui.dsl

import io.worxbend.tui.core.Rect

/** One content-to-parent translation, plus the window that clips ordinary painting in that parent space. Escaping
  * portals use the translation only; visible input records use both operations.
  */
private[dsl] final case class ViewportTransform(dx: Int, dy: Int, viewport: Rect):
  def translate(rect: Rect): Rect = rect.offset(dx, dy)
  def visible(rect: Rect): Rect   = translate(rect).intersection(viewport)

/** The coordinate stack for the render currently executing on this thread. Each viewport pushes for its content render
  * only, innermost first; portal enqueueing captures a screen rectangle before that scope ends. No widget or element
  * retains a transform, and independently running render threads never share one.
  */
private[dsl] object FrameCoordinates:
  /** This thread's stack, kept in its [[FrameContext]]. */
  private def stack: List[ViewportTransform] = FrameContext.current.transforms

  private def stack_=(transforms: List[ViewportTransform]): Unit = FrameContext.current.transforms = transforms

  def root[A](boundary: Rect)(body: => A): A =
    val previous = stack
    stack = List(ViewportTransform(0, 0, boundary))
    try body
    finally stack = previous

  def push(transform: ViewportTransform): Unit = stack = transform :: stack

  def pop(): Unit = stack = stack.drop(1)

  def clear(): Unit = stack = Nil

  def during[A](transform: ViewportTransform)(body: => A): A =
    val previous = stack
    push(transform)
    try body
    finally stack = previous

  /** The host's own boundary — the screen rectangle the frame root established — while a render is executing on this
    * thread, and `None` outside one. What a node that floats content through a portal asks to learn how much room the
    * frame has left below or above it; the viewports between it and the root are deliberately not consulted, because a
    * portal escapes exactly those.
    */
  def frameBoundary: Option[Rect] = stack.lastOption.map(_.viewport)

  /** Screen placement, deliberately not clipped to the windows the content escapes. */
  def translate(area: Rect): Rect = stack.foldLeft(area)((rect, transform) => transform.translate(rect))

  /** Ordinary painting/input geometry, clipped in each intermediate parent space before translating onward. */
  def visible(area: Rect): Rect = stack.foldLeft(area)((rect, transform) => transform.visible(rect))
