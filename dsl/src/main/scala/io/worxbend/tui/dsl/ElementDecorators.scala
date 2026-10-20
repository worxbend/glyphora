package io.worxbend.tui.dsl

import io.worxbend.tui.core.{
  Buffer,
  Cell,
  Constraint,
  Effect,
  GlyphSupport,
  MouseEvent,
  MouseEventKind,
  Position,
  Rect,
  Style,
  Widget,
}
import io.worxbend.tui.terminal.PointerShape
import io.worxbend.tui.runtime.{LogLevel, LogRing}
import io.worxbend.tui.widgets as w

import java.util.logging.{Level, Logger}
import scala.concurrent.duration.FiniteDuration
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

  def props: ElementProps                                                      = inner.props
  override def children: Seq[Element]                                          = Seq(inner)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]       = inner.builtinKeyHandler
  private[dsl] override def bubbledKeyHandler: Option[BuiltinKeyHandler]       = inner.bubbledKeyHandler
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler]   = inner.builtinMouseHandler
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler]   = inner.builtinPasteHandler
  private[dsl] override def captureKeyHandler: Option[BuiltinKeyHandler]       = inner.captureKeyHandler
  private[dsl] override def editsText: Boolean                                 = inner.editsText
  private[dsl] override def claim: SizeClaim                                   = inner.claim
  private[dsl] override def layoutOnly: Boolean                                = inner.layoutOnly
  private[dsl] override def paintedRect(area: Rect): Rect                      = inner.paintedRect(area)
  override def debugName: String                                               = inner.debugName
  // a decorator paints the states its node paints; forwarded so a walk over a decorated tree (a searched scroll view's
  // content identity) sees them even through a decorator, such as `TrackedElement`, that skips `inner` in `children`
  override private[dsl] def watchedStates: Seq[io.worxbend.tui.core.Versioned] = inner.watchedStates
  override def accessibleName: String                                          = inner.accessibleName
  private[dsl] override def componentRole: Option[ComponentRole]               = inner.componentRole
  private[dsl] override def selectionIsFocusCue: Boolean                       = inner.selectionIsFocusCue
  private[dsl] override def passesPointerThrough: Boolean                      = inner.passesPointerThrough
  private[dsl] override def passesWheelThrough: Boolean                        = inner.passesWheelThrough
  override def pointerShape: Option[PointerShape]                              = inner.pointerShape
  private[dsl] override def pointerShapeAt(position: Position, area: Rect): Option[PointerShape] =
    inner.pointerShapeAt(position, area)

  /** With no constraint of its own, a decorator measures as whatever it wraps. The trait default cannot serve here: a
    * decorator's own `widget` is a lambda around `inner.widget`, so it is not a [[io.worxbend.tui.core.Measured]] and
    * the wrapped node's own answer would be lost on the way through.
    */
  private[dsl] override def intrinsicHeight(width: Int): Option[Int] =
    constrainedHeight(inner.intrinsicHeight(width))

  /** @see [[intrinsicHeight]] */
  private[dsl] override def intrinsicWidth(height: Int): Option[Int] =
    constrainedWidth(inner.intrinsicWidth(height))

/** Paint-order accounting under input wrappers, so hit routing keeps the node's original identity and handlers. */
private[dsl] final case class CaretOcclusionElement(inner: Element) extends DecoratingElement:
  type Self = CaretOcclusionElement
  override def children: Seq[Element]                                    = inner.children
  def widget: Widget                                                     = (area, buffer) =>
    CaretOcclusionElement.occlude(inner, area)
    inner.widget.render(area, buffer)
  private[dsl] def withProps(props: ElementProps): CaretOcclusionElement = copy(inner = inner.withProps(props))
  private[dsl] override def withChildren(children: Seq[Element]): CaretOcclusionElement =
    copy(inner = inner.withChildren(children))

private[dsl] object CaretOcclusionElement:
  /** A fill writes its entire allocation even when its child paints no cells (or has a narrower paintedRect). */
  def occlude(node: Element, area: Rect): Unit = node match
    case _: FilledElement      => CaretRegister.occlude(area)
    case bounds: BoundsElement => occlude(bounds.inner, area)
    case reveal: RevealElement => occlude(reveal.inner, area)
    case _ if !node.layoutOnly => CaretRegister.occlude(node.paintedRect(area))
    case _                     => ()

  def around(node: Element): Element = node match
    case _: TrackedElement | _: PointerElement      => node
    case bounds: BoundsElement if bounds.layoutOnly => bounds.copy(inner = around(bounds.inner))
    case _: BoundsElement                           => node
    case _: FilledElement                           => CaretOcclusionElement(node)
    case _ if node.layoutOnly                       => node
    case other                                      => CaretOcclusionElement(other)

/** Wraps a focusable element during the focus pass so its rendered area is recorded for click-to-focus hit-testing. */
private[dsl] final case class TrackedElement(inner: Element, index: Int, tracker: FocusTracker)
    extends DecoratingElement:
  type Self = TrackedElement

  // Placement is known only after painting. Hide omitted targets from capture and routing without a second paint.
  override def props: ElementProps =
    if tracker.isPlaced(InputTarget.Focus(index)) then inner.props
    else inner.props.copy(focusState = inner.props.focusState.copy(inert = true, focused = false))

  /** The wrapped node's children, not the node itself: [[FocusPass.decorate]] wraps an element *after* deciding what it
    * is and then recurses through `children`, so handing back `inner` would decorate it a second time — and every walk
    * in [[EventRouter]] would offer this element's own handlers twice, once here and once on the identical `inner`.
    */
  override def children: Seq[Element] = inner.children

  def widget: Widget                                                             =
    (area, buffer) =>
      CaretOcclusionElement.occlude(inner, area)
      tracker.record(index, area, inner.props.focusKey, Some(inner.paintedRect(area)))
      // every focusable tells the enclosing scroll views where it landed: the focused one so they keep it on screen,
      // the rest so that the frame after focus moves onto one of them can scroll to it before painting
      RevealProbe.tracked(index, area, inner.props.focused)
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

  override def props: ElementProps =
    if tracker.isPlaced(InputTarget.Pointer(pointerId)) then inner.props
    else inner.props.copy(focusState = inner.props.focusState.copy(inert = true, focused = false))

  /** The wrapped node's children, for the reason [[TrackedElement.children]] spells out. */
  override def children: Seq[Element] = inner.children

  def widget: Widget                                                             =
    (area, buffer) =>
      CaretOcclusionElement.occlude(inner, area)
      tracker.recordPointer(
        pointerId,
        area,
        inner.passesPointerThrough,
        inner.props.focusKey,
        Some(inner.paintedRect(area)),
        inner.passesWheelThrough,
      )
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
  * where a `JulBridge` ring and a `logConsole` can show it; built with a `ring` (`errorBoundary(fallback, ring)`) it is
  * appended to that [[LogRing]] at `Error` under the same logger name instead, with no bridge needed. Logged, the same
  * failure again (same type, message and throw site), as a subtree that stays broken produces on every frame, goes out
  * at `FINE`. Nothing is remembered between frames: the view is rebuilt on the next redraw and the subtree gets to try
  * again, so a failure that was transient heals on its own.
  *
  * Transparent otherwise, like every decorator: a healthy subtree measures, focuses and routes events as itself. Fatal
  * throwables (`OutOfMemoryError`, `InterruptedException`) are not caught.
  */
final case class ErrorBoundaryElement(
    build: () => Element,
    fallback: Throwable => Element,
    ring: Option[LogRing] = None,
) extends DecoratingElement:
  type Self = ErrorBoundaryElement

  /** The subtree, or the fallback when building it threw. Evaluated once, on first use, on the render thread. */
  lazy val inner: Element =
    try build()
    catch
      case NonFatal(error) =>
        ErrorBoundaryElement.report("building", error, ring)
        fallback(error)

  def widget: Widget =
    (area, buffer) =>
      try inner.widget.render(area, buffer)
      catch
        case NonFatal(error) =>
          ErrorBoundaryElement.report("painting", error, ring)
          w.Clear().render(area, buffer)
          fallback(error).widget.render(area, buffer)

  private[dsl] def withProps(props: ElementProps): ErrorBoundaryElement =
    copy(build = () => inner.withProps(props))

  private[dsl] override def withChildren(children: Seq[Element]): Element =
    children.headOption.fold(this)(child => copy(build = () => child))

private object ErrorBoundaryElement:
  private val log = Logger.getLogger("io.worxbend.tui.dsl")

  /** The failures already logged at `SEVERE`, by type, message and throw site — bounded, forgotten wholesale when full.
    * A view is rebuilt on every frame, so a subtree that stays broken would otherwise log the same stack trace on each.
    */
  private val reported = java.util.HashSet[String]()

  private val RememberedFailures = 256

  /** Appends `error` to `ring` when there is one; else logs it at `SEVERE` the first time this failure is seen and at
    * `FINE` on every repeat.
    */
  private def report(phase: String, error: Throwable, ring: Option[LogRing]): Unit =
    val message = s"errorBoundary: the subtree threw while $phase; painting the fallback instead"
    ring match
      case Some(target) => target.log(LogLevel.Error, log.getName, message, Some(error))
      case None         =>
        val site  = error.getStackTrace.headOption.fold("")(_.toString)
        val seen  = s"$phase|${error.getClass.getName}|${error.getMessage}|$site"
        val first = reported.synchronized {
          if reported.size >= RememberedFailures then reported.clear()
          reported.add(seen)
        }
        log.log(if first then Level.SEVERE else Level.FINE, message, error)

/** Wraps an element marked `.ensureVisible` during the focus pass, so the area it is handed is reported to the
  * enclosing `scrollView` through [[RevealProbe]]. `id` identifies the request across frames — the element's focus key
  * when it has one, its position in the decorated tree otherwise. Transparent otherwise, like [[TrackedElement]], whose
  * `children` rule it shares for the same reason.
  */
private[dsl] final case class RevealElement(inner: Element, id: String) extends DecoratingElement:
  type Self = RevealElement
  override def debugName: String      = inner.debugName
  override def children: Seq[Element] = inner.children

  def widget: Widget                                                            =
    (area, buffer) =>
      RevealProbe.visible(id, area)
      inner.widget.render(area, buffer)
  private[dsl] def withProps(props: ElementProps): RevealElement                = copy(inner = inner.withProps(props))
  private[dsl] override def withChildren(children: Seq[Element]): RevealElement =
    copy(inner = inner.withChildren(children))

/** What a [[DecoratedElement]] does around the node it wraps — the five decorators any element can take. */
enum Decoration:

  /** A frame on all four sides in `glyphs`, drawn in `frameStyle`, degraded to what `support` can draw. */
  case Border(glyphs: w.BorderType, frameStyle: Style, support: GlyphSupport)

  /** Blank cells between the decorated box's edge and the node, per side. */
  case Pad(cells: w.Padding)

  /** Every cell of the box cleared to blanks in `fill` before the node paints, and `fill` layered under what it drew.
    */
  case Background(fill: Style)

  /** A drop shadow cast inside the box: the node gives up the cells the shadow falls on. */
  case Shadowed(cast: w.Shadow)

  /** Nothing the node paints outside the box it was handed survives. */
  case Clip

/** A decorator any element can take: `.border(...)`, `.padding(...)`, `.background(...)`, `.shadow`, `.clip`.
  *
  * Built on [[DecoratingElement]], so the wrapped node is its one child — focused, hit-tested, recorded and routed as
  * itself, with its own key and handlers — but unlike the transparent framework wrappers this one is a box of its own.
  * It keeps its own props: the layout extensions the wrapped node carried when it was decorated (`.length`, `.fill`,
  * `.alignSelf`, a grid spot) are lifted onto the decorator, because they were about where the node goes and the
  * decorated box is now what goes there; builders written *after* the decorator — `.fill`, `.fg(...)`, `.onKeyEvent` —
  * land on the box. Its `style` is the content style, layered under what the wrapped node drew inside the decoration,
  * as on every container; a border's own colour is the `frameStyle` the border was given.
  *
  * The decoration's cells are added to the measurement (`text("hi").border()` is four columns by three rows), and the
  * built-in handlers stay on the wrapped node, which is the one the router hands its own area to.
  */
final case class DecoratedElement(
    inner: Element,
    decoration: Decoration,
    override val props: ElementProps = ElementProps(),
) extends DecoratingElement:
  type Self = DecoratedElement

  override def debugName: String =
    decoration match
      case _: Decoration.Border     => "border"
      case _: Decoration.Pad        => "padding"
      case _: Decoration.Background => "background"
      case _: Decoration.Shadowed   => "shadow"
      case Decoration.Clip          => "clip"

  override def accessibleName: String                                                            = inner.accessibleName
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]                         = None
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler]                     = None
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler]                     = None
  private[dsl] override def componentRole: Option[ComponentRole]                                 = None
  override def pointerShape: Option[PointerShape]                                                = props.pointerShape
  private[dsl] override def pointerShapeAt(position: Position, area: Rect): Option[PointerShape] =
    val _ = (position, area)
    pointerShape

  /** Padding and clipping draw nothing of their own; a border, a background and a shadow do. */
  private[dsl] override def layoutOnly: Boolean =
    decoration match
      case _: Decoration.Pad | Decoration.Clip => true
      case _                                   => false

  /** The columns and rows the decoration itself costs: `(horizontal, vertical)`. */
  private def chromeCells: (Int, Int) =
    decoration match
      case _: Decoration.Border      => (2, 2)
      case Decoration.Pad(cells)     => (cells.horizontalCells, cells.verticalCells)
      case Decoration.Shadowed(cast) => (cast.reservedColumns, cast.reservedRows)
      case _                         => (0, 0)

  /** The wrapped node's default claim grown by the decoration, so a one-row control in a border claims three rows. */
  private[dsl] override def claim: SizeClaim =
    val (columns, rows)                                       = chromeCells
    def grown(constraint: Constraint, cells: Int): Constraint =
      constraint match
        case Constraint.Length(length) => Constraint.Length(length + cells)
        case other                     => other
    val base                                                  = inner.claim
    SizeClaim(grown(base.vertical, rows), grown(base.horizontal, columns))

  private[dsl] override def intrinsicHeight(width: Int): Option[Int] =
    val (columns, rows) = chromeCells
    constrainedHeight(inner.intrinsicHeight(math.max(0, width - columns)).map(_ + rows))

  private[dsl] override def intrinsicWidth(height: Int): Option[Int] =
    val (columns, rows) = chromeCells
    constrainedWidth(inner.intrinsicWidth(math.max(0, height - rows)).map(_ + columns))

  def widget: Widget =
    (area, buffer) =>
      decoration match
        case Decoration.Border(glyphs, frameStyle, support) =>
          val block = w.Block(borderStyle = frameStyle, borderType = glyphs.degraded(support))
          block.render(area, buffer)
          paintInner(block.inner(area), buffer)
        case Decoration.Pad(cells)                          =>
          paintInner(DecoratedElement.inset(area, cells), buffer)
        case Decoration.Background(fill)                    =>
          buffer.fill(area, Cell(" ", fill))
          paintInner(area, buffer)
          buffer.mapStyle(area)(fill.patch)
        case Decoration.Shadowed(cast)                      =>
          val box = DecoratedElement.shadowBox(area, cast)
          if box != area then cast.render(box, area, buffer)
          paintInner(box, buffer)
        case Decoration.Clip                                =>
          if !area.isEmpty then
            // the node paints into a buffer that *is* its area, seeded with what is already there, so whatever it
            // writes outside falls off the edge; only the area is copied back
            val offscreen = Buffer(area)
            offscreen.blit(buffer, area.position, area)
            paintInner(area, offscreen)
            buffer.blit(offscreen, area.position, area)

  /** The wrapped node in `area`, with this box's content style layered under whatever it drew. */
  private def paintInner(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      inner.widget.render(area, buffer)
      if props.style != Style.Default then buffer.mapStyle(area)(props.style.patch)

  private[dsl] def withProps(props: ElementProps): DecoratedElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): DecoratedElement =
    copy(inner = children.headOption.getOrElse(inner))

private[dsl] object DecoratedElement:

  /** `element` wrapped in `decoration`, with the layout extensions it carried moved onto the wrapper. */
  def around(element: Element, decoration: Decoration): DecoratedElement =
    val props = element.props
    DecoratedElement(
      element.withProps(props.copy(constraint = None, alignSelf = None, gridSpot = GridSpot.Flowed)),
      decoration,
      ElementProps(constraint = props.constraint, alignSelf = props.alignSelf, gridSpot = props.gridSpot),
    )

  /** `area` less `cells` on each side, or an empty rect at the inner corner when nothing is left. */
  def inset(area: Rect, cells: w.Padding): Rect =
    val left   = math.max(0, cells.left)
    val top    = math.max(0, cells.top)
    val width  = area.width - cells.horizontalCells
    val height = area.height - cells.verticalCells
    if width <= 0 || height <= 0 then Rect(area.x + left, area.y + top, 0, 0)
    else Rect(area.x + left, area.y + top, width, height)

  /** The box a shadow leaves the node: `area` less the shadow's offset on the side it falls, or all of `area` when the
    * area is too small to keep a cell for the node — a shadow with nothing left to cast it is dropped, as `w.Block`
    * drops its own.
    */
  def shadowBox(area: Rect, cast: w.Shadow): Rect =
    val width  = area.width - cast.reservedColumns
    val height = area.height - cast.reservedRows
    if width < 1 || height < 1 then area
    else
      val x = area.x + (if cast.offsetX < 0 then cast.reservedColumns else 0)
      val y = area.y + (if cast.offsetY < 0 then cast.reservedRows else 0)
      Rect(x, y, width, height)

/** The decorators any element takes. Each wraps the element in a [[DecoratedElement]]; see there for what moves onto
  * the wrapper and what stays on the element.
  */
extension [E <: Element](element: E)

  /** A frame on all four sides in the theme's border style — `text("hi").border()`, `.border(BorderType.Rounded)`. The
    * panel-free way to box a single node; a `panel` adds a title, captions and slots on top of this.
    */
  def border(glyphs: w.BorderType = w.BorderType.Plain)(using theme: Theme): DecoratedElement =
    LateBinding.themed(theme)(theme =>
      DecoratedElement.around(element, Decoration.Border(glyphs, theme.border, theme.glyphs))
    )

  /** A frame drawn in `frameStyle` — the border's own colour, not the content's (that is the element's `style`). */
  def border(glyphs: w.BorderType, frameStyle: Style)(using theme: Theme): DecoratedElement =
    LateBinding.themed(theme)(theme =>
      DecoratedElement.around(element, Decoration.Border(glyphs, frameStyle, theme.glyphs))
    )

  /** Blank cells around the element, per side — what `panel(...).borderless.padded(...)` was written for. */
  def padding(cells: w.Padding): DecoratedElement = DecoratedElement.around(element, Decoration.Pad(cells))

  /** Padding that reads as `cells` even all the way round — `cells` rows above and below, twice that many columns
    * either side — the same `Padding.proportional` a panel's `.padding(cells)` reserves.
    */
  def padding(cells: Int): DecoratedElement = padding(w.Padding.proportional(cells))

  /** `vertical` blank rows above and below, `horizontal` blank columns left and right: the CSS two-value shorthand. */
  def padding(vertical: Int, horizontal: Int): DecoratedElement =
    padding(w.Padding.symmetric(horizontal, vertical))

  /** The element's whole area cleared to `fill` first, with `fill` layered under whatever the element drew — a
    * background that the element's own colours still win over.
    */
  def background(fill: Style): DecoratedElement = DecoratedElement.around(element, Decoration.Background(fill))

  /** The default drop shadow, one cell down and to the right, dimming what is behind it. The element gives up that
    * column and row of its area to the shadow, which never paints outside the area.
    */
  def shadow: DecoratedElement = shadow(w.Shadow.Default)

  /** A shadow of your own: another offset, a shade glyph instead of dimming — see `Shadow.shade`. */
  def shadow(cast: w.Shadow): DecoratedElement = DecoratedElement.around(element, Decoration.Shadowed(cast))

  /** Keeps whatever the element paints inside the area it is handed: a hand-written `widget` that strays outside its
    * rect is cut off at the edge rather than drawing over its neighbours. A `portal` inside escapes all the same — it
    * paints at the frame root, which is what a portal is for.
    */
  def clip: DecoratedElement = DecoratedElement.around(element, Decoration.Clip)

/** The node [[dismissibleOverlay]] builds: `content` painted over the whole area, with the outside-press behaviour as
  * the node's own built-in rather than a sibling backdrop's, so it can read where its subtree was painted. Transparent
  * to layout and measurement — it claims and measures as `content` does.
  *
  * Owned by the view that built it, like every node; the recorded bounds it reads belong to the host and are resolved
  * when a press arrives, on the render thread.
  */
final case class DismissibleOverlayElement(
    content: Element,
    onOutsidePress: () => Unit,
    props: ElementProps = ElementProps(),
) extends Element
    with BoundsAwareElement:
  type Self = DismissibleOverlayElement
  override def debugName: String = "dismissibleOverlay"

  override def children: Seq[Element] = Seq(content)

  def widget: Widget = (area, buffer) => content.widget.render(area, buffer)

  private[dsl] def withProps(props: ElementProps): DismissibleOverlayElement = copy(props = props)

  private[dsl] override def withChildren(children: Seq[Element]): DismissibleOverlayElement =
    copy(content = children.headOption.getOrElse(content))

  private[dsl] override def claim: SizeClaim = content.claim

  private[dsl] override def intrinsicHeight(width: Int): Option[Int] =
    constrainedHeight(content.intrinsicHeight(width))

  private[dsl] override def intrinsicWidth(height: Int): Option[Int] =
    constrainedWidth(content.intrinsicWidth(height))

  /** With no bounds recorded there is nothing to compare the press against, so every press that reached this node —
    * which only happens when no control of the dialog took it — is treated as outside, the pre-recording behaviour.
    */
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some((event, _) => press(event, inside = false))

  private[dsl] def builtinMouseHandlerWithBounds(subtree: NodeSubtreeBounds): BuiltinMouseHandler =
    (event, _) => press(event, inside = subtree.paintedAt(event.position))

  /** A press is consumed either way — the dialog took it, or it closed the dialog — and anything else bubbles on. */
  private def press(event: MouseEvent, inside: => Boolean): Boolean =
    event.kind == MouseEventKind.Down && {
      if !inside then onOutsidePress()
      true
    }

/** `inner` with `effect` applied, at `elapsed`, over exactly the cells `inner` was painted into — what
  * `element.withEffect(...)` builds. The layout extensions the wrapped node carried are lifted onto this node, as every
  * decorator does, and everything else (focus, handlers, measurement) reads through to `inner`.
  *
  * The effect runs inside the paint, straight after `inner` painted, so it sees the element's own cells and nothing a
  * later sibling or a portal draws on top. Immutable; render-thread-only like every widget paint.
  */
final case class EffectedElement(
    inner: Element,
    effect: Effect,
    elapsed: FiniteDuration,
    override val props: ElementProps = ElementProps(),
) extends DecoratingElement:
  type Self = EffectedElement

  override def debugName: String = "effect"

  def widget: Widget =
    (area, buffer) =>
      inner.widget.render(area, buffer)
      val visible = area.intersection(buffer.area)
      if !visible.isEmpty then effect.process(elapsed, buffer, visible)

  private[dsl] def withProps(props: ElementProps): EffectedElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): EffectedElement =
    copy(inner = children.headOption.getOrElse(inner))

private[dsl] object EffectedElement:

  /** `element` under `effect`, with the layout extensions it carried moved onto the wrapper. */
  def around(element: Element, effect: Effect, elapsed: FiniteDuration): EffectedElement =
    val props = element.props
    EffectedElement(
      element.withProps(props.copy(constraint = None, alignSelf = None, gridSpot = GridSpot.Flowed)),
      effect,
      elapsed,
      ElementProps(constraint = props.constraint, alignSelf = props.alignSelf, gridSpot = props.gridSpot),
    )

/** The node [[withStyle]] builds: `inner`, with `base` cascading onto every node beneath it when painted.
  *
  * The focus pass layers the cascade in and marks the scope applied; a scope painted without one — a widget-level test
  * calling `.widget` directly — applies it itself, eagerly, on that paint. Its own props start from the wrapped node's
  * layout claims and are otherwise its own, so it is never a second focus target for the node it wraps; `.fg(...)` and
  * the other styling builders on the scope add to `base`.
  */
final case class StyleScopeElement private[dsl] (
    inner: Element,
    base: Style,
    override val props: ElementProps,
    private[dsl] val applied: Boolean,
) extends DecoratingElement:
  type Self = StyleScopeElement
  override def debugName: String = inner.debugName

  // the scope is a styling boundary, not a second copy of the node it wraps: none of the wrapped node's behaviour is
  // offered here as well, or the focus pass would make a pointer target of the scope and the router run it twice
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     = None
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] = None
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = None
  private[dsl] override def componentRole: Option[ComponentRole]             = None
  override def pointerShape: Option[PointerShape]                            = props.pointerShape

  /** What this scope cascades onto the nodes beneath it: its base with its own styling builders on top. */
  private[dsl] def cascade: Style = base.patch(props.style)

  def widget: Widget =
    if applied then inner.widget else StyleScopeElement.restyled(cascade)(inner).widget

  private[dsl] def withProps(props: ElementProps): StyleScopeElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): StyleScopeElement =
    children.headOption.fold(this)(child => copy(inner = child))

object StyleScopeElement:

  private[dsl] def apply(inner: Element, base: Style): StyleScopeElement =
    val claims = inner.props
    new StyleScopeElement(
      inner,
      base,
      ElementProps(constraint = claims.constraint, alignSelf = claims.alignSelf, gridSpot = claims.gridSpot),
      applied = false,
    )

  /** The eager fallback for a scope painted with no focus pass: the subtree rebuilt with `base` under every style. */
  private[dsl] def restyled(base: Style)(node: Element): Element =
    node match
      case scope: StyleScopeElement =>
        scope.copy(inner = restyled(base.patch(scope.cascade))(scope.inner), applied = true)
      case _                        =>
        val own = node.withProps(node.props.copy(style = base.patch(node.props.style)))
        own.withChildren(own.children.map(restyled(base)))
