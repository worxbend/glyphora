package io.worxbend.tui.dsl

import io.worxbend.tui.core.{CharWidth, Style}
import io.worxbend.tui.runtime.{ReactiveScope, Signal}
import io.worxbend.tui.widgets.NoticeLevel

import scala.concurrent.duration.FiniteDuration

/** The transient notification stack behind [[TuiApp.notify]].
  *
  * One of these belongs to one [[TuiApp]] instance. Every method here runs on that app's render thread — `push` and
  * `dismissAll` from event handlers, `age` from the tick stage, `overlay` from the view evaluation — so the queue needs
  * no synchronisation of its own; the [[Signal]] it lives in enforces that with its own render-thread check.
  *
  * A toast's lifetime is clock time rather than a count of ticks. Ticks are what *notices* the expiry — a run arms its
  * own ambient tick for as long as a toast is live, so no `config.tickRate` is needed for a toast to disappear on its
  * own — but they no longer decide how long "three seconds" is, so the same `notify` call means the same thing in an
  * app that ticks every 20ms and one that ticks every 200ms.
  *
  * `now` is the clock, in nanoseconds, and is a parameter rather than a direct `System.nanoTime()` call so a test can
  * step a toast's expiry without waiting for wall-clock time to pass; `TuiApp` hands in the run's clock, which is the
  * system clock unless a `runWith` overrides it.
  */
private[dsl] final class ToastStack(now: () => Long):

  /** One queued toast: what to say, how loudly, and the clock reading past which it is stale. */
  private final case class ActiveToast(message: String, level: NoticeLevel, expiresAtNanos: Long)

  private val queued: Signal[Vector[ActiveToast]] = Signal(Vector.empty)

  /** Queues a toast that expires `duration` from now. */
  def push(message: String, level: NoticeLevel, duration: FiniteDuration): Unit =
    queued.update(_ :+ ActiveToast(message, level, now() + duration.toNanos))

  /** Whether anything is queued, read without subscribing — so `TuiApp` can ask "does this run still owe ticks?"
    * without the question itself becoming a reason to repaint.
    */
  def isLive: Boolean = queued.peek.nonEmpty

  /** Drops every toast, shown or still queued. */
  def dismissAll(): Unit =
    queued.set(Vector.empty)

  /** Drops the toasts whose deadline has passed. Called once per tick; a no-op — and, crucially, not a signal write —
    * while the stack is empty, so an idle app is not woken up by its own notification machinery.
    */
  def age(): Unit =
    if queued.peek.nonEmpty then
      val at = now()
      queued.update(_.filter(_.expiresAtNanos > at))

  /** The overlay to layer over the app's view, or `None` when nothing is showing.
    *
    * Composed from [[NoticeElement]] rather than painted into the buffer directly, so it inherits the widget layer's
    * clipping and severity icons instead of re-implementing them. Each toast is its own [[Element.positioned]] overlay
    * because the stack is right-aligned and every row is a different width; `areaWidth` is the width of the frame about
    * to be painted, which the render pass publishes before it evaluates the view.
    *
    * Reading the queue here is a *tracked* read: pushing or ageing a toast invalidates the view that rendered it, which
    * is what schedules the redraw that makes it appear and disappear.
    */
  def overlay(areaWidth: Int)(using ReactiveScope, Theme): Option[Element] =
    val active = queued.get
    if active.isEmpty then None
    else
      val overlays = active.takeRight(MaxVisibleToasts).zipWithIndex.map { (toast, index) =>
        val style  = styleOf(toast.level)
        // the trailing space keeps the reversed style reading as a padded badge rather than as text ending mid-cell
        val notice = NoticeElement(s"${toast.message} ", toast.level, None, style, style, style)
        val width  = CharWidth.of(s"${Messages.active.icon(toast.level)} ${toast.message} ")
        val dx     = math.max(0, areaWidth - width - ToastRightMargin)
        Element.positioned(dx, ToastTopRow + index, width, 1)(notice)
      }
      Some(Element.layers(overlays.head, overlays.tail*))

  private def styleOf(level: NoticeLevel)(using theme: Theme): Style =
    noticeLevelStyle(level).reverse

/** How many toasts are drawn at once — older ones stay queued and appear as the visible ones age out. */
private val MaxVisibleToasts = 5

/** How far in from the right edge the toast stack sits, and which row it starts on. Row 0 is normally an app's own
  * chrome, so toasts begin one row below it.
  */
private val ToastRightMargin = 1
private val ToastTopRow      = 1
