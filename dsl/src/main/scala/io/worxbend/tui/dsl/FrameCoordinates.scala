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
  private val current: ThreadLocal[List[ViewportTransform]] = ThreadLocal.withInitial(() => Nil)

  def push(transform: ViewportTransform): Unit = current.set(transform :: current.get())

  def pop(): Unit =
    val rest = current.get().drop(1)
    if rest.isEmpty then clear() else current.set(rest)

  def clear(): Unit = current.remove()

  def during[A](transform: ViewportTransform)(body: => A): A =
    val previous = current.get()
    push(transform)
    try body
    finally
      if previous.isEmpty then clear() else current.set(previous)

  /** Screen placement, deliberately not clipped to the windows the content escapes. */
  def translate(area: Rect): Rect = current.get().foldLeft(area)((rect, transform) => transform.translate(rect))

  /** Ordinary painting/input geometry, clipped in each intermediate parent space before translating onward. */
  def visible(area: Rect): Rect = current.get().foldLeft(area)((rect, transform) => transform.visible(rect))
