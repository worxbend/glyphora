package io.worxbend.tui.dsl

import io.worxbend.tui.core.{
  Buffer,
  KeyCode,
  KeyEvent,
  KeyModifiers,
  MouseEvent,
  MouseEventKind,
  Position,
  Rect,
  Size,
  Style,
}
import io.worxbend.tui.runtime.{Frame, JfrEvents, ReactiveScope}
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

  /** The one recorder of where things were painted — see [[NodeBounds]]. Its per-node table is filled only while
    * [[recordBounds]] is on; its input-target table, which the tracker writes, on every frame.
    */
  private[dsl] val bounds: NodeBounds = NodeBounds()

  private[dsl] var tracker              = FocusTracker(bounds)
  private var session                   = ViewSession(key => boundsOf(key))
  private var lastTree: Option[Element] = None

  /** Each input target's child positions in [[lastTree]], innermost first, recorded while it was decorated — what mouse
    * routing follows to the target a hit resolved.
    */
  private val targetPaths = scala.collection.mutable.HashMap.empty[InputTarget, List[Int]]

  /** The child positions leading to the focused element in [[lastTree]], recorded while it was decorated. */
  private var lastFocusPath: Option[Vector[Int]] = None

  /** Whether the next frames record painted rects — of the narrow set [[recordAllBounds]] describes, or of every node
    * when that is on — which is what [[boundsOf]], the element-tree snapshot and the click-outside backdrop read. Off
    * by default because it costs a wrapper per recorded node per frame; a call to [[boundsOf]] switches it on, as does
    * `TuiApp` for a headless run and for a click-outside modal. Written by the host and `TuiApp`; an embedding host
    * opts in through [[enableBoundsRecording]].
    */
  @volatile private[dsl] var recordBounds: Boolean = false

  /** Whether recording, while on, covers every node rather than the narrow set — keyed nodes, containers, input
    * targets, nodes with a pointer role and everything under a click-outside backdrop — that every reader but the
    * inspector, the debug borders and the element-tree snapshot needs. Off by default; [[enableFullBoundsRecording]]
    * and `TuiApp` (for a headless run and while the inspector or debug borders show) switch it on.
    */
  @volatile private[dsl] var recordAllBounds: Boolean = false

  /** Records the painted rect of *every* node from the next frame on — what [[ElementTreeSnapshot]] and the inspector
    * read for an unkeyed leaf. Costs a wrapper per node per frame, where [[enableBoundsRecording]] costs one only for
    * the nodes a reader can ask about.
    */
  def enableFullBoundsRecording(): Unit =
    recordBounds = true
    recordAllBounds = true

  /** Whether this host records painted rects — see [[enableBoundsRecording]]. */
  def recordingBounds: Boolean = recordBounds

  /** Records painted rects from the next frame on, for [[boundsOf]] and the gestures that hit-test against them: of
    * every keyed node, container, input target and node with a pointer role, and of every node under a click-outside
    * backdrop — not of unkeyed leaves, which nothing but the element-tree snapshot and the inspector asks about (see
    * [[enableFullBoundsRecording]]). Stays on for the life of the host; a call to [[boundsOf]] does the same.
    */
  def enableBoundsRecording(): Unit = recordBounds = true

  /** Pointer capture and click counting between mouse events — see [[PointerCapture]]. */
  private var pointer = PointerCapture()

  /** Mouse text selection and the other pointer gestures run over ordinary routing — see [[PointerGestures]]. */
  private[dsl] val gestures: PointerGestures = PointerGestures(bounds)

  /** Whether a mouse drag over `text`, `markdown`, `log` and the tables selects their text, highlighted in the theme's
    * focus cue and copied through the app clipboard on release — see [[TextSelection]]. Off by default: without mouse
    * capture the terminal selects natively. `TuiApp` turns it on exactly when it runs with `RunnerConfig.mouseCapture`;
    * a host that owns its loop and captures the mouse turns it on itself. Render-thread only.
    */
  def textSelection: Boolean = gestures.selection.enabled

  def textSelection_=(enabled: Boolean): Unit =
    gestures.selection.enabled = enabled
    if !enabled then
      val _ = gestures.selection.clear()

  /** The text the mouse selection covers as of the last [[render]]; `None` when nothing is selected. */
  def selectedText: Option[String] = gestures.selection.text

  /** Copies the current mouse selection into the app clipboard — what `Ctrl+Shift+C` does in a `TuiApp`. `false` when
    * nothing is selected. Outside an app's dispatch the clipboard is the in-memory one of this thread.
    */
  def copySelection(): Boolean =
    selectedText match
      case Some(text) =>
        AppClipboard.current.copy(text)
        true
      case None       => false

  /** Drops the mouse selection; `true` when one was showing, so a frame is owed. */
  def clearSelection(): Boolean = gestures.selection.clear()

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
    tracker = FocusTracker(bounds)
    pointer = PointerCapture()
    val _ = gestures.selection.clear()
    session = ViewSession(key => boundsOf(key))
    lastTree = None
    lastFocusPath = None
    lastFocus = (-1, None)

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
    recordFocusChange()
    targetPaths.clear()
    val recorder = Option.when(recordBounds)(bounds)
    motionDemanded = false
    cuesDemanded = false
    gestures.beginFrame()
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
      gestures = Some(gestures),
      fullBounds = recordAllBounds,
      onTargetPath = (target, trail) => targetPaths(target) = trail,
    )
    lastTree = Some(tree)
    // always opened and closed: with full recording off, the nodes that carry a pointer role still record into it
    bounds.begin()
    try paint(tree)
    finally bounds.publish()
    gestures.afterPaint()
    session.afterPaint(frame)

  /** Focus as of the last frame, for the Flight Recorder `FocusChanged` event; `-1` before the first one. */
  private var lastFocus: (Int, Option[String]) = (-1, None)

  private def recordFocusChange(): Unit =
    val now = (tracker.focusedIndex, tracker.focusedKey)
    if now != lastFocus then
      JfrEvents.focusChanged(lastFocus._1, lastFocus._2, now._1, now._2)
      lastFocus = now

  /** Whether a widget state painted on the last frame — a `TextInputState`, a `ListState`, any
    * [[io.worxbend.tui.core.Versioned]] state a node was handed — has been mutated since that frame. When one has, the
    * view the last [[render]] evaluated is invalidated through its `ReactiveScope`, exactly as a `Signal` write would
    * invalidate it, and this answers `true`.
    *
    * `TuiApp` asks once per loop iteration, which is why a background result written into a state on the render thread
    * shows without a `requestRedraw()`. A host that owns its loop asks the same way, after running queued work and
    * before deciding whether to draw. Render-thread-only.
    */
  def pollStates(): Boolean = session.pollStates() | gestures.selection.takeOwedFrame()

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

  /** Delivers a key to the focused element and the ancestors it bubbles through — after the capture phase, which offers
    * it to the few nodes that take keys whatever holds focus (see [[Element.captureKeyHandler]]).
    *
    * `false` means nothing on screen consumed it and the decision is the host's. This method deliberately does not move
    * focus and does not quit: Tab traversal and Ctrl+C are policy, and policy belongs to whoever owns the loop — call
    * [[focusNext]] or [[focusPrevious]] from your own key handling if you want the usual meaning.
    */
  def dispatchKey(key: KeyEvent): Boolean =
    cancelDrag(key) || lastTree.exists(EventRouter.dispatchCapture(_, key)) ||
      lastTree.exists(EventRouter.dispatchKey(_, key, lastFocusPath)) || keyboardSelect(key) ||
      (lastTree.isDefined && groupArrow(key))

  /** Shift+arrow that nothing on the focus path consumed, while the focused node is selectable text and selection is on
    * (a mouse-capturing app): extends a selection over that node one cell or row at a time — see
    * [[TextSelection.extendBy]].
    */
  private def keyboardSelect(key: KeyEvent): Boolean =
    val step = key match
      case KeyEvent(KeyCode.Left, KeyModifiers.Shift)  => Some((-1, 0))
      case KeyEvent(KeyCode.Right, KeyModifiers.Shift) => Some((1, 0))
      case KeyEvent(KeyCode.Up, KeyModifiers.Shift)    => Some((0, -1))
      case KeyEvent(KeyCode.Down, KeyModifiers.Shift)  => Some((0, 1))
      case _                                           => None
    step.exists { (dx, dy) =>
      gestures.selection.enabled && focusedSelectable.exists { entry =>
        gestures.selection.extendBy(entry, dx, dy)
        true
      }
    }

  /** The innermost selectable node painted inside the focused element's area on the last frame. */
  private def focusedSelectable: Option[NodeBoundsEntry] =
    tracker.areaOf(tracker.focusedIndex).flatMap { focused =>
      bounds.current.entries
        .filter(entry => entry.roles.selectable && focused.contains(entry.visible.position))
        .maxByOption(_.id)
    }

  /** The `close-modal` framework key (Esc) while a drag and drop is in flight abandons it — the ghost goes and the
    * release that follows drops nothing — and is spent on that, ahead of every element.
    */
  private def cancelDrag(key: KeyEvent): Boolean =
    val keys = FrameworkKeys.current.value
    gestures.drag.isActive && keys.fires(keys.closeModal, key) && {
      gestures.drag.end()
      true
    }

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
    * consumed press holds the pointer, drags and the release go to the pressed target wherever they land. A pointer
    * gesture runs over the routing — see [[PointerGestures]]: a press no element consumed on selectable text starts a
    * selection, and while one is being dragged out its drags and release go to it, not to the elements.
    */
  def dispatchMouse(mouse: MouseEvent): Boolean =
    val event = pointer.counted(mouse, clock())
    val under = tracker.mouseHit(event.position)
    val _     = autoScroll(event)
    gestures.intercept(event) match
      case Some(redraw) =>
        // a gesture in progress took the event: no element sees it, but a release still lets the pointer go
        pointer.delivered(event, None, consumed = true)
        val restyled = trackPointerStates(event, under) && cuesDemanded
        redraw || restyled
      case None         =>
        // a pass-through layer above the target hears a press first and lets it go on
        val heard      =
          if event.kind == MouseEventKind.Down then
            tracker.bounds
              .passThroughAt(event.position)
              .foldLeft(false)((any, hit) =>
                lastTree.exists(EventRouter.dispatchMouse(_, event, Some(hit), targetPaths.get)) || any
              )
          else false
        val target     = pointer.route(event, under)
        val focusMoved =
          if event.kind == MouseEventKind.Down then
            target.flatMap(_.target.focusIndex) match
              case Some(index) if index != tracker.focusedIndex =>
                tracker.focusTo(index)
                true
              case _                                            => false
          else false

        val consumed = lastTree.exists(EventRouter.dispatchMouse(_, event, target, targetPaths.get))
        pointer.delivered(event, target, consumed)
        val restyled = trackPointerStates(event, under) && cuesDemanded
        val gestured = lastTree.isDefined && gestures.afterDelivery(event, consumed)
        consumed || focusMoved || restyled || gestured || heard

  /** A drag that is selecting text and has run past the top or bottom of the selected node's rows on screen scrolls the
    * nearest scrollable around that edge one step — a wheel event at the edge cell, routed the way a real wheel there
    * would be — so the selection can reach content the viewport does not show yet. One step per drag event: a terminal
    * reports motion, not a held pointer, so the scrolling follows the pointer's movement.
    */
  private def autoScroll(event: MouseEvent): Boolean =
    event.kind == MouseEventKind.Drag && gestures.selection.edgeScroll(event.position).exists { (edge, down) =>
      val kind  = if down then MouseEventKind.ScrollDown else MouseEventKind.ScrollUp
      val wheel = MouseEvent(edge, kind, KeyModifiers.None)
      lastTree.exists(EventRouter.dispatchMouse(_, wheel, tracker.mouseHit(edge), targetPaths.get))
    }

  /** Updates the hovered and pressed targets the next focus pass stamps, `true` when either changed. Hover follows the
    * pointer over every event that has a position on screen; pressed is set by a press and cleared by a release.
    */
  private def trackPointerStates(event: MouseEvent, under: Option[MouseHit]): Boolean =
    val hovered               = under.map(_.target)
    val hoveredKey            = hovered.flatMap(tracker.bounds.targetBounds).flatMap(_.key)
    val (pressed, pressedKey) = event.kind match
      case MouseEventKind.Down => (hovered, hoveredKey)
      case MouseEventKind.Up   => (None, None)
      case _                   => (tracker.pressedTarget, tracker.pressedKey)
    val changed               = hovered != tracker.hoveredTarget || pressed != tracker.pressedTarget ||
      hoveredKey != tracker.hoveredKey || pressedKey != tracker.pressedKey
    tracker.hoveredTarget = hovered
    tracker.hoveredKey = hoveredKey
    tracker.pressedTarget = pressed
    tracker.pressedKey = pressedKey
    changed

  /** The mouse pointer the last frame wants shown at `position` — see [[EventRouter.pointerShapeAt]]. A host that owns
    * its own loop hands the answer to `Backend.setPointerShape` when it changes; `TuiApp` does so on every mouse event.
    */
  def pointerShapeAt(position: Position): PointerShape =
    lastTree.fold(PointerShape.Default)(
      EventRouter.pointerShapeAt(_, position, tracker.mouseHit(position), targetPaths.get)
    )

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
