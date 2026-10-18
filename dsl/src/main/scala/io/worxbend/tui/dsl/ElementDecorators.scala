package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Position, Rect, Widget}
import io.worxbend.tui.terminal.PointerShape
import io.worxbend.tui.widgets as w

import java.util.logging.{Level, Logger}
import scala.util.control.NonFatal

/** A node that wraps exactly one other node and is transparent to everything but rendering.
  *
  * Props, children, measurement, and all three built-in handlers delegate to [[inner]]; a decorator only has to say how
  * it paints and how it rebuilds. One definition rather than one hand-copied set of forwarders per wrapper — a
  * decorator that forgot one of them silently changed measurement or dropped a subtree out of focus traversal.
  */
private[dsl] trait DecoratingElement extends Element:

  /** The single node this one wraps. */
  def inner: Element

  def props: ElementProps                                                    = inner.props
  override def children: Seq[Element]                                        = Seq(inner)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     = inner.builtinKeyHandler
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] = inner.builtinMouseHandler
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = inner.builtinPasteHandler
  private[dsl] override def claim: SizeClaim                                 = inner.claim
  private[dsl] override def layoutOnly: Boolean                              = inner.layoutOnly
  override def debugName: String                                             = inner.debugName
  override def accessibleName: String                                        = inner.accessibleName
  override def pointerShape: Option[PointerShape]                            = inner.pointerShape
  private[dsl] override def pointerShapeAt(position: Position, area: Rect): Option[PointerShape] =
    inner.pointerShapeAt(position, area)

  /** With no constraint of its own, a decorator measures as whatever it wraps. The trait default cannot serve here: a
    * decorator's own `widget` is a lambda around `inner.widget`, so it is not a [[io.worxbend.tui.core.Measured]] and
    * the wrapped node's own answer would be lost on the way through.
    */
  private[dsl] override def intrinsicHeight(width: Int): Option[Int] =
    constrainedHeight(inner.intrinsicHeight(width))

/** Wraps a focusable element during the focus pass so its rendered area is recorded for click-to-focus hit-testing. */
private[dsl] final case class TrackedElement(inner: Element, index: Int, tracker: FocusTracker)
    extends DecoratingElement:
  type Self = TrackedElement

  /** The wrapped node's children, not the node itself: [[FocusPass.decorate]] wraps an element *after* deciding what it
    * is and then recurses through `children`, so handing back `inner` would decorate it a second time — and every walk
    * in [[EventRouter]] would offer this element's own handlers twice, once here and once on the identical `inner`.
    */
  override def children: Seq[Element] = inner.children

  def widget: Widget                                                             =
    (area, buffer) =>
      tracker.record(index, area)
      inner.widget.render(area, buffer)
  private[dsl] def withProps(props: ElementProps): TrackedElement                = copy(inner = inner.withProps(props))
  private[dsl] override def withChildren(children: Seq[Element]): TrackedElement =
    copy(inner = inner.withChildren(children))

/** Wraps the content of a [[ScrollViewElement]] during the focus pass so the areas recorded inside it are screen areas.
  *
  * `w.ScrollView` hands its content a rect anchored at (0, 0), draws it into an offscreen buffer covering the
  * scrolled-to window and blits that window into place, so every rect the content subtree is handed is in content
  * coordinates. This node publishes the content-to-screen mapping to [[FrameCoordinates]] for exactly the duration of
  * the content render — pushed from inside that render, so the paths where `ScrollView` draws no content push nothing,
  * and popped in a `finally`. Transparent otherwise: it renders, measures and routes straight to `inner`, and carries
  * neutral props of its own so it never doubles up the wrapped element's handlers or focus state.
  *
  * Owned by the [[FocusPass]] that built it and touched only on the render thread. Portal enqueueing translates through
  * the same context without clipping to the viewport it escapes. A partly visible control records both its translated
  * layout rect and its clipped hit rect. Handlers keep the original origin and dimensions for menu rows, sliders and
  * splitPane dividers; hit-testing admits only positions inside every enclosing viewport.
  */
private[dsl] final class ScrollViewportElement(
    val inner: Element,
    state: w.ScrollViewState,
) extends DecoratingElement:
  type Self = ScrollViewportElement

  /** The enclosing scroll view's own area, published each frame just before it renders this content. */
  private var screenArea: Rect = Rect.Zero

  private[dsl] def publishScreenArea(area: Rect): Unit = screenArea = area

  override val props: ElementProps = ElementProps()

  def widget: Widget =
    (area, buffer) =>
      // `area` is the offscreen content rect: its width is the viewport width less any scrollbar column, and
      // `state.offset` has already been clamped by `ScrollView.render`
      val viewport  = Rect(screenArea.x, screenArea.y, area.width, screenArea.height)
      val transform = ViewportTransform(screenArea.x - area.x, screenArea.y - area.y - state.offset, viewport)
      FrameCoordinates.during(transform)(inner.widget.render(area, buffer))

  private[dsl] def withProps(props: ElementProps): ScrollViewportElement =
    val _ = props
    this

  private[dsl] override def withChildren(children: Seq[Element]): Element =
    children.headOption.fold(this)(child => ScrollViewportElement(child, state))

/** Wraps a non-focusable element with user or built-in mouse behavior during the focus pass, so its area is recorded
  * and the mouse router can offer it only the events that landed inside it. Focusable elements need no such wrapper —
  * [[TrackedElement]] already records their area under their focus index.
  */
private[dsl] final case class PointerElement(inner: Element, pointerId: Int, tracker: FocusTracker)
    extends DecoratingElement:
  type Self = PointerElement

  /** The wrapped node's children, for the reason [[TrackedElement.children]] spells out. */
  override def children: Seq[Element] = inner.children

  def widget: Widget                                                             =
    (area, buffer) =>
      tracker.recordPointer(pointerId, area)
      inner.widget.render(area, buffer)
  private[dsl] def withProps(props: ElementProps): PointerElement                = copy(inner = inner.withProps(props))
  private[dsl] override def withChildren(children: Seq[Element]): PointerElement =
    copy(inner = inner.withChildren(children))

/** Confines a failing subtree to its own area: `errorBoundary(fallback)(subtree)`.
  *
  * The view is the app's own code, and a throwable out of it ends the run as `RunnerError.Handler` — correctly, since
  * an app that cannot draw a frame has no defined next frame. But one widget fed a bad value should not take the whole
  * screen down with it. This node catches a non-fatal throwable at either moment the subtree can fail: while it is
  * *built* (`subtree` is by-name and evaluated here, once per element) and while it is *painted* (the inner widget's
  * `render`). Either way `fallback(error)` is painted in the area the subtree was given — on top of a cleared area, so
  * a half-drawn widget leaves no debris — and the failure is logged at `SEVERE` on the `io.worxbend.tui.dsl` logger,
  * where a `JulBridge` ring and a `logConsole` can show it. Nothing is remembered between frames: the view is rebuilt
  * on the next redraw and the subtree gets to try again, so a failure that was transient heals on its own.
  *
  * Transparent otherwise, like every decorator: a healthy subtree measures, focuses and routes events as itself. Fatal
  * throwables (`OutOfMemoryError`, `InterruptedException`) are not caught.
  */
final case class ErrorBoundaryElement(
    build: () => Element,
    fallback: Throwable => Element,
) extends DecoratingElement:
  type Self = ErrorBoundaryElement

  /** The subtree, or the fallback when building it threw. Evaluated once, on first use, on the render thread. */
  lazy val inner: Element =
    try build()
    catch
      case NonFatal(error) =>
        ErrorBoundaryElement.report("building", error)
        fallback(error)

  def widget: Widget =
    (area, buffer) =>
      try inner.widget.render(area, buffer)
      catch
        case NonFatal(error) =>
          ErrorBoundaryElement.report("painting", error)
          w.Clear().render(area, buffer)
          fallback(error).widget.render(area, buffer)

  private[dsl] def withProps(props: ElementProps): ErrorBoundaryElement =
    copy(build = () => inner.withProps(props))

  private[dsl] override def withChildren(children: Seq[Element]): Element =
    children.headOption.fold(this)(child => copy(build = () => child))

private object ErrorBoundaryElement:
  private val log = Logger.getLogger("io.worxbend.tui.dsl")

  private def report(phase: String, error: Throwable): Unit =
    log.log(Level.SEVERE, s"errorBoundary: the subtree threw while $phase; painting the fallback instead", error)
