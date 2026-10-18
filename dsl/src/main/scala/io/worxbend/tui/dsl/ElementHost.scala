package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, KeyCode, KeyEvent, MouseEvent, MouseEventKind, Position, Rect, Size, Style}
import io.worxbend.tui.runtime.{Frame, ReactiveScope}
import io.worxbend.tui.terminal.PointerShape

/** The DSL's render-and-dispatch engine, with no loop and no key policy of its own.
  *
  * [[TuiApp]] is this class plus opinions: Tab and Shift+Tab move focus, Ctrl+P opens the command palette, Ctrl+C
  * quits, screens stack, toasts expire, a splash plays first. A host that already owns a loop — an application built
  * around its own `TerminalRunner`, or one embedding a glyphora panel in a loop it did not write — constructs an
  * `ElementHost` instead: it calls [[render]] once per frame and a `dispatch*` method per event, and decides for itself
  * what the keys none of them consumed should mean.
  *
  * What it does own is the part that would otherwise have to be rewritten: resolving the responsive view for the size
  * actually being painted, reconciling focus across frames (so the highlight follows the element and not the screen
  * position), decorating the tree with the focus cue, and routing keys, mouse events and pastes to the element that is
  * on screen rather than to a freshly evaluated tree. The same session also owns hook identity and drains portals after
  * ordinary painting. A portal escapes its containers, but never the host's render area intersected with the
  * destination buffer. Hooks survive until they are unvisited in a frame, the host is discarded, or [[reset]] is
  * called. Scheduling, reactive scope disposal and caller-owned resources remain the embedding loop's responsibility.
  *
  * '''Ownership and threads.''' One instance belongs to one loop. Every method must be called on that loop's render
  * thread: the instance holds the focus bookkeeping and the decorated tree of the last frame, and neither is
  * synchronised. Nothing here schedules a frame — a host that changes something redraws when it decides to.
  *
  * '''Focus changes take effect on the next frame.''' Events are routed against the tree the last [[render]] painted,
  * which is the tree the person at the terminal is actually looking at. So [[focusNext]], [[focusToKey]] and
  * [[clearFocus]] change where focus *will* be, and the next `render` is what makes the following key go somewhere
  * else. In a loop that redraws after handling an event — which is every loop — that is invisible; it is written down
  * because a test that dispatches twice with no frame in between would otherwise be puzzling.
  */
final class ElementHost:

  private[dsl] var tracker              = FocusTracker()
  private var session                   = ViewSession(key => boundsOf(key))
  private var lastTree: Option[Element] = None

  /** The child positions leading to the focused element in [[lastTree]], recorded while it was decorated. */
  private var lastFocusPath: Option[Vector[Int]] = None

  /** The one recorder of where every node was painted — see [[NodeBounds]]. Filled only while [[recordBounds]] is on.
    */
  private[dsl] val bounds: NodeBounds = NodeBounds()

  /** Whether the next frames record the painted rect of every node, which is what [[boundsOf]], the element-tree
    * snapshot and the click-outside backdrop read. Off by default because it costs a wrapper per node per frame; a call
    * to [[boundsOf]] switches it on, as does `TuiApp` for a headless run and for a click-outside modal.
    */
  @volatile var recordBounds: Boolean = false

  /** Pointer capture and click counting between mouse events — see [[PointerCapture]]. */
  private var pointer = PointerCapture()

  /** The clock click counting reads, in nanoseconds; a test swaps in a hand-moved one. */
  private[dsl] var clock: () => Long = () => System.nanoTime()

  /** Whether the last frame contained a node that only shows under all-motion tracking — a `.hoverStyle`, or an
    * explicit `.pointer(shape)`. A host that owns its loop and sees this turn true asks its backend for
    * `MouseCaptureMode.AllMotion`; `TuiApp` does so by itself when it runs with mouse capture on.
    */
  def wantsMouseMotion: Boolean = motionDemanded

  private var motionDemanded = false

  /** Whether the last frame drew a hover or pressed cue, so a change of hovered/pressed target owes a frame. */
  private var cuesDemanded = false

  /** Retires this mount's hooks, focus history and dispatch tree. The next render starts a fresh mount. Call only
    * between frames on the owning thread. Caller-owned state, subscriptions and resources are not closed: the embedding
    * loop owns its ReactiveScope and must dispose it separately.
    */
  def reset(): Unit =
    session.dispose()
    tracker = FocusTracker()
    pointer = PointerCapture()
    session = ViewSession(key => boundsOf(key))
    lastTree = None
    lastFocusPath = None

  /** Resolves `view` for an area of this size, reconciles focus, decorates the tree with `theme`'s focus cue and paints
    * it into `buffer`.
    *
    * The decorated tree is kept, because the `dispatch*` methods below route against what is actually on screen. Call
    * this before dispatching anything: with no frame rendered yet there is no tree, and every dispatch answers `false`.
    */
  def render(area: Rect, buffer: Buffer, theme: Theme, view: View)(using scope: ReactiveScope): Unit =
    val frame = session.resolve(view, Size(area.width, area.height), theme)
    renderFrame(frame, theme.focus, tree => session.paint(tree, area, buffer), theme.disabled, theme)

  /** [[render]] against a runner's `Frame`, for a host inside a `TerminalRunner`. */
  def render(frame: Frame, theme: Theme, view: View)(using scope: ReactiveScope): Unit =
    renderComposed(frame, theme, view, () => ())

  private[dsl] def renderComposed(frame: Frame, theme: Theme, view: View, beforeFocus: () => Unit)(using
      scope: ReactiveScope
  ): Unit =
    val resolved = session.resolve(view, Size(frame.area.width, frame.area.height), theme)
    beforeFocus()
    renderFrame(resolved, theme.focus, tree => paintWithCaret(frame, tree), theme.disabled, theme)

  /** Paints `tree` into `frame`, then parks the frame's caret where the focused text node said its insertion point is.
    *
    * The node reports the cell from inside its render, through [[CaretRegister]], which maps it through the scroll
    * views above it while their coordinate stack is live; here, after the paint, it is already a screen cell and goes
    * straight to `Frame.setCursorPosition`. A frame in which nothing reported — focus is on a button, the focused field
    * is scrolled out of view, the node asked for a painted caret only — asks for no cursor, and the runner hides it.
    */
  private def paintWithCaret(frame: Frame, tree: Element): Unit =
    val caret = CaretRegister.collect {
      frame.renderWidget((area, buffer) => session.paint(tree, area, buffer), frame.area)
    }
    caret.foreach(frame.setCursorPosition)

  /** [[renderFrame]] for a tree that needs no resolving — a test composing one by hand. The analysis the resolution
    * pass would have collected is collected by one walk here instead.
    */
  private[dsl] def renderTree(
      raw: Element,
      focusStyle: Style,
      paint: Element => Unit,
      disabledStyle: Style = Style.Default.dim,
      theme: Theme = Theme.default,
  ): Unit =
    renderFrame(ResolvedFrame(raw, FrameAnalysis.of(raw)), focusStyle, paint, disabledStyle, theme)

  /** The half of [[render]] after the view has been resolved: reconcile, decorate, remember, paint.
    *
    * Split out for [[TuiApp]], which has work of its own to do between resolving the view and reconciling focus (it
    * synchronises the focus layers a pushed screen or an open palette adds) and paints through a `Frame`. Everything
    * that decides *where focus is* lives here, so the app and an embedding host cannot answer that question
    * differently.
    *
    * The frame costs two walks of the tree before its paint: the resolution pass, which collected the tab order,
    * autofocus request, focus scopes and watched states on the way ([[FrameAnalysis]]), and the decoration below, which
    * records the path to the focused element for key routing. Reconciling needs the whole tab order before any node can
    * be told whether it is focused, which is what keeps those two walks apart.
    */
  private[dsl] def renderFrame(
      frame: ResolvedFrame,
      focusStyle: Style,
      paint: Element => Unit,
      disabledStyle: Style = Style.Default.dim,
      theme: Theme = Theme.default,
  ): Unit =
    val raw      = frame.tree
    val analysis = frame.analysis
    tracker.reconcile(analysis.focusKeys, analysis.autofocus, analysis.scopes)
    val recorder = Option.when(recordBounds)(bounds)
    motionDemanded = false
    cuesDemanded = false
    // a tree with a consumer of bounds in it (a click-outside backdrop) turns recording on for the frames after this one
    val tree     = FocusPass.decorate(
      raw,
      tracker,
      focusStyle,
      recorder,
      onBoundsDemand = () => recordBounds = true,
      disabledStyle = disabledStyle,
      onMotionDemand = () => motionDemanded = true,
      onCueDemand = () => cuesDemanded = true,
      theme = theme,
      onFocusPath = path => lastFocusPath = path,
    )
    lastTree = Some(tree)
    recorder.foreach(_.begin())
    try paint(tree)
    finally recorder.foreach(_.publish())
    session.afterPaint(frame)

  /** Whether a widget state painted on the last frame — a `TextInputState`, a `ListState`, any
    * [[io.worxbend.tui.core.Versioned]] state a node was handed — has been mutated since that frame. When one has, the
    * view the last [[render]] evaluated is invalidated through its `ReactiveScope`, exactly as a `Signal` write would
    * invalidate it, and this answers `true`.
    *
    * `TuiApp` asks once per loop iteration, which is why a background result written into a state on the render thread
    * shows without a `requestRedraw()`. A host that owns its loop asks the same way, after running queued work and
    * before deciding whether to draw. Render-thread-only.
    */
  def pollStates(): Boolean = session.pollStates()

  /** The rect the element declared with `.key(key)` was painted into on the last [[render]], in the coordinates of the
    * buffer that frame was drawn into and mapped out of any scroll view it sits in — so a test can click its centre
    * without knowing the layout. `None` when no node on the last frame carried the key, when it was scrolled entirely
    * out of view, or when recording was off for that frame.
    *
    * Asking is the opt-in: a call on a host that was not recording switches [[recordBounds]] on, so the *next* frame
    * answers. Safe to call from another thread once that thread has observed the frame as drawn — the answer is a read
    * of the last published frame, not of the paint in progress.
    */
  def boundsOf(key: String): Option[Rect] =
    recordBounds = true
    bounds.current.boundsOf(key)

  /** How the element declared with `.key(key)` is announced on the last frame: its [[Element.accessibleName]] followed
    * by the interaction states that hold — `button Save, disabled`, `input, focused`. This is the text a status line,
    * an announcement region or a test reads to tell a screen-reader user what the cursor is on; colour alone (the
    * dimmed disabled style, the reversed focus cue) announces nothing. `None` when no node on the last frame carried
    * the key. Render-thread only.
    */
  def accessibleLabel(key: String): Option[String] =
    lastTree.flatMap(find(_, key)).map { node =>
      val props  = node.props
      val states =
        Option.when(props.isDisabled)("disabled") ++ Option.when(props.focused)("focused") ++
          Option.when(props.pressed)("pressed")
      (node.accessibleName +: states.toSeq).mkString(", ")
    }

  private def find(node: Element, key: String): Option[Element] =
    if node.props.focusKey.contains(key) then Some(node) else node.children.iterator.flatMap(find(_, key)).nextOption()

  /** The focus-decorated tree the last [[render]] painted — what events are routed against and what
    * [[ElementTreeSnapshot]] prints. `None` before the first frame. Render-thread only, like the tree itself.
    */
  private[dsl] def decoratedTree: Option[Element] = lastTree

  /** Delivers a key to the focused element and the ancestors it bubbles through.
    *
    * `false` means nothing on screen consumed it and the decision is the host's. This method deliberately does not move
    * focus and does not quit: Tab traversal and Ctrl+C are policy, and policy belongs to whoever owns the loop — call
    * [[focusNext]] or [[focusPrevious]] from your own key handling if you want the usual meaning.
    */
  def dispatchKey(key: KeyEvent): Boolean =
    lastTree.exists(EventRouter.dispatchKey(_, key, lastFocusPath)) || (lastTree.isDefined && groupArrow(key))

  /** An unmodified arrow nobody on the focus path consumed moves focus inside the focused element's focus group. That
    * is the group's own behaviour rather than app policy, so it belongs here and an embedding host gets it too.
    */
  private def groupArrow(key: KeyEvent): Boolean =
    if !key.modifiers.isEmpty then false
    else
      key.code match
        case KeyCode.Right | KeyCode.Down => tracker.moveInGroup(forward = true)
        case KeyCode.Left | KeyCode.Up    => tracker.moveInGroup(forward = false)
        case _                            => false

  /** Moves focus to the nearest focusable painted in `direction` from the focused one — spatial navigation, which
    * `TuiApp` binds to Alt+arrows when `spatialNavigation` is on. `true` when focus moved; takes effect next frame.
    */
  def focusInDirection(direction: SpatialDirection): Boolean = tracker.focusInDirection(direction)

  /** Hit-tests the pointer, moves focus if a press landed inside a different focusable, then routes the event to the
    * element under it. `true` when focus moved, an element consumed the event, or the hovered or pressed target changed
    * while the frame draws a `.hoverStyle`/`.pressedStyle` cue (so the next frame looks different).
    *
    * On the way it synthesises the event's `clickCount` and applies pointer capture — see [[PointerCapture]]: while a
    * consumed press holds the pointer, drags and the release go to the pressed target wherever they land.
    */
  def dispatchMouse(mouse: MouseEvent): Boolean =
    val event      = pointer.counted(mouse, clock())
    val under      = tracker.mouseHit(event.position)
    val target     = pointer.route(event, under)
    val focusMoved =
      if event.kind == MouseEventKind.Down then
        target.flatMap(_.target.focusIndex) match
          case Some(index) if index != tracker.focusedIndex =>
            tracker.focusTo(index)
            true
          case _                                            => false
      else false

    val consumed = lastTree.exists(EventRouter.dispatchMouse(_, event, target))
    pointer.delivered(event, target, consumed)
    val restyled = trackPointerStates(event, under) && cuesDemanded
    consumed || focusMoved || restyled

  /** Updates the hovered and pressed targets the next focus pass stamps, `true` when either changed. Hover follows the
    * pointer over every event that has a position on screen; pressed is set by a press and cleared by a release.
    */
  private def trackPointerStates(event: MouseEvent, under: Option[MouseHit]): Boolean =
    val hovered = under.map(_.target)
    val pressed = event.kind match
      case MouseEventKind.Down => hovered
      case MouseEventKind.Up   => None
      case _                   => tracker.pressedTarget
    val changed = hovered != tracker.hoveredTarget || pressed != tracker.pressedTarget
    tracker.hoveredTarget = hovered
    tracker.pressedTarget = pressed
    changed

  /** The mouse pointer the last frame wants shown at `position` — see [[EventRouter.pointerShapeAt]]. A host that owns
    * its own loop hands the answer to `Backend.setPointerShape` when it changes; `TuiApp` does so on every mouse event.
    */
  def pointerShapeAt(position: Position): PointerShape =
    lastTree.fold(PointerShape.Default)(EventRouter.pointerShapeAt(_, position, tracker.mouseHit(position)))

  /** Offers a key release to the focused element and its ancestors — see [[EventRouter.dispatchKeyRelease]]. */
  def dispatchKeyRelease(key: KeyEvent): Boolean =
    lastTree.exists(EventRouter.dispatchKeyRelease(_, key, lastFocusPath))

  /** Delivers a bracketed-paste payload to the focused element. `false` when nothing took it. */
  def dispatchPaste(text: String): Boolean = lastTree.exists(EventRouter.dispatchPaste(_, text, lastFocusPath))

  /** Moves focus to the next focusable in tab order, wrapping at the end. `true` when it moved. */
  def focusNext(): Boolean = tracker.focusNext()

  /** Moves focus to the previous focusable in tab order, wrapping at the start. `true` when it moved. */
  def focusPrevious(): Boolean = tracker.focusPrevious()

  /** Moves focus to the element carrying `key` — the string an element was given by `.key("email")`. `false` when no
    * element on the last frame had it, in which case focus stays where it was.
    */
  def focusToKey(key: String): Boolean = tracker.focusToKey(key)

  /** The `focusKey` of the focused element as of the last [[render]]: `None` when that element has no key, when nothing
    * is focused, or before the first frame.
    */
  def focusedKey: Option[String] = tracker.focusedKey

  /** Takes focus off everything. Until focus moves again no element renders focused and every key comes straight back
    * out of [[dispatchKey]] as unconsumed.
    */
  def clearFocus(): Unit = tracker.clearFocus()
