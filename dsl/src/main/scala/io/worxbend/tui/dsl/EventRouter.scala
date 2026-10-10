package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyEvent, MouseEvent, MouseEventKind, Rect}

/** Event routing.
  *
  * With a focused element present, a key event starts at the focused element and bubbles up its ancestor chain — at
  * each node the user's `onKeyEvent` runs first, then the framework's built-in behavior (editing, toggling); a `true`
  * result consumes the event. With no focusable elements the tree is walked depth-first, leaves before ancestors, with
  * the same stop-propagation contract.
  *
  * A mouse event starts at the last-painted input target under the pointer, whether focusable or pointer-only, and
  * bubbles along that target's ancestor chain, innermost first. At each node the user's `onMouseEvent` runs first and
  * the element's built-in behavior second. Each handler sees a given event at most once — `false` means keep bubbling
  * outward, never deliver again or fall through to covered siblings.
  *
  * A built-in that declines only reaches an enclosing control's built-in for the wheel ([[reachesOuterBuiltin]]), so a
  * wheel over a button inside a scroll view still scrolls it while a drag stays with the element it landed on.
  *
  * [[FocusTracker]] resolves the target once using the shared paint sequence for both kinds of input node. Deferred
  * portal painting participates in the same sequence; routing looks up that typed identity, not a second geometric
  * search in tree order. Click-to-focus derives from the very same resolution.
  *
  * A subtree marked `props.inert` — every layer a modal or the command palette covers — is skipped by every walk: it
  * receives no event and supplies neither a focus path nor a hit-test path.
  */
private[dsl] object EventRouter:

  /** Routes a key press.
    *
    * With a focused element the event starts there and bubbles to its ancestors. Without one there are two different
    * states, and they are told apart deliberately:
    *   - the tree contains no focusable at all — a dashboard of plain text, say. There is no chain to bubble along, so
    *     the event is offered to every element depth-first; that is the only way a handler on a non-focusable element
    *     can ever see a key.
    *   - the tree *has* focusables and the app took focus off them (`clearFocus`). Then nothing is meant to receive the
    *     key: the documented contract is that it comes straight back out unconsumed, on its way to the app's bindings.
    *     Walking the tree here would fire the `onKeyEvent` of elements the person at the terminal is not pointing at.
    */
  def dispatchKey(root: Element, event: KeyEvent): Boolean =
    pathToFocused(root) match
      case Some(leafToRoot)           => leafToRoot.exists(handlesKey(_, event))
      case None if hasFocusable(root) => false
      case None                       => dispatchKeyDepthFirst(root, event)

  /** Whether this tree offers anything to focus, ignoring subtrees a layer above has made inert — those are exactly the
    * elements that are out of the tab order, so a modal's focusables are what counts while it is open.
    */
  private def hasFocusable(element: Element): Boolean =
    !element.props.inert && (element.props.focusable || element.children.exists(hasFocusable))

  /** Routes the resolved [[MouseHit]] along its own ancestor path. There is no second hit test or covered-sibling
    * fallback: a declining overlay bubbles outward, not through to the control it hides. No hit means no delivery.
    */
  def dispatchMouse(root: Element, event: MouseEvent, hit: Option[MouseHit]): Boolean =
    hit.flatMap(found => pathToTarget(root, found.target)).getOrElse(Nil).exists(handlesMouse(_, event, hit))

  /** Identity of a decorated input node. Focus and pointer numbering occupy distinct typed namespaces. */
  private def targetOf(element: Element): Option[InputTarget] =
    element match
      case tracked: TrackedElement => Some(InputTarget.Focus(tracked.index))
      case pointer: PointerElement => Some(InputTarget.Pointer(pointer.pointerId))
      case _                       => None

  /** Original layout bounds, only while the pointer is inside the control's visible portion. */
  private def coveredArea(element: TrackedElement, event: MouseEvent): Option[Rect] =
    element.tracker
      .areaOf(element.index)
      .filter(_.contains(event.position))
      .flatMap(_ => element.tracker.layoutAreaOf(element.index))

  /** The user's `onMouseEvent` first, then the framework's own behavior for this element. */
  private def handlesMouse(element: Element, event: MouseEvent, hit: Option[MouseHit]): Boolean =
    element.props.onMouse.exists(_(event)) ||
      builtinArea(element, event, hit).exists(area => element.builtinMouseHandler.exists(_(event, area)))

  /** The area a built-in behavior runs against, and the gate on whether it runs at all.
    *
    * On the hit element that is its translated layout area, not the clipped bounds used to resolve the hit. On an
    * *outer* tracked element it is that element's own layout area, only while the pointer is inside its visible bounds,
    * and only for a wheel event — see [[reachesOuterBuiltin]].
    */
  private def builtinArea(element: Element, event: MouseEvent, hit: Option[MouseHit]): Option[Rect] =
    element match
      case tracked: TrackedElement =>
        hit
          .collect { case MouseHit(InputTarget.Focus(index), area) if index == tracked.index => area }
          .orElse(if reachesOuterBuiltin(event.kind) then coveredArea(tracked, event) else scala.None)
      case pointer: PointerElement =>
        hit.collect { case MouseHit(InputTarget.Pointer(id), area) if id == pointer.pointerId => area }
      case _                       => scala.None

  /** Whether a built-in that declined this event may hand it to an enclosing control's built-in.
    *
    * Only the wheel does. Scrolling is the one gesture whose target is the nearest *scrollable* ancestor rather than
    * the innermost thing under the pointer, so a wheel over a button inside a scroll view has to reach the scroll view
    * — that is the whole reason this fallback exists.
    *
    * A press or a drag must not: an outer control's built-in reads the pointer against its own whole area, so
    * `SplitPaneElement` would move its divider for a drag anywhere in either pane. That was harmless only while
    * built-ins ran on the hit element alone, which for a splitPane meant the pointer was over no smaller focusable —
    * effectively the divider or dead space. Drag-selecting inside a `textInput` in a pane must not yank the divider.
    */
  private def reachesOuterBuiltin(kind: MouseEventKind): Boolean =
    kind match
      case MouseEventKind.ScrollUp | MouseEventKind.ScrollDown | MouseEventKind.ScrollLeft |
          MouseEventKind.ScrollRight =>
        true
      case _ => false

  /** The path to the nearest element satisfying `matches` in this subtree, innermost first and including every ancestor
    * up to `element` — the shared walk behind the input-target and focused-element lookups, inert guard included.
    */
  private def pathWhere(element: Element, matches: Element => Boolean): Option[List[Element]] =
    if element.props.inert then None
    else if matches(element) then Some(List(element))
    else
      element.children
        .to(LazyList)
        .map(pathWhere(_, matches))
        .collectFirst { case Some(path) => path :+ element }

  private def pathToTarget(element: Element, target: InputTarget): Option[List[Element]] =
    pathWhere(element, node => targetOf(node).contains(target))

  /** Routes a key *release* to the focused element and its ancestors, innermost first.
    *
    * Three deliberate differences from [[dispatchKey]], all of them the same decision: a release is not a press.
    *   - Only the user's own `.onKeyRelease` handler is offered. No built-in behaviour fires, because every built-in in
    *     the library is written against a press and would run twice for one keystroke.
    *   - There is no depth-first fallback for a tree with nothing focusable. A release with no focused element has
    *     nowhere sensible to go, and offering it to every node would fire a handler for a key the user pressed while
    *     something else entirely had the keyboard.
    *   - The caller does not fall back to the application's bindings — see `TuiApp`. A binding is a press vocabulary,
    *     and firing it on the way up would run every chord twice.
    */
  def dispatchKeyRelease(root: Element, event: KeyEvent): Boolean =
    pathToFocused(root) match
      case Some(leafToRoot) => leafToRoot.exists(_.props.onKeyUp.exists(_(event)))
      case None             => false

  /** Delivers a bracketed paste to the focused element's paste behavior. */
  def dispatchPaste(root: Element, text: String): Boolean =
    pathToFocused(root) match
      case Some(leafToRoot) =>
        val leaf = leafToRoot.head
        leaf.props.onPaste.exists(_(text)) || leaf.builtinPasteHandler.exists(_(text))
      case None             => false

  /** The user's `onKeyEvent` first, then the framework's own behavior for this element.
    *
    * A built-in only runs on the *focused* element. Both key walks offer the event to elements that are not focused —
    * the ancestors an unconsumed key bubbles through, and every node of the depth-first walk when nothing is focusable
    * at all — and a built-in firing there would mean typing into an unfocused text input, or one arrow key moving two
    * nested lists. The user's own handler is deliberately not gated: `onKeyEvent` on a container is how an app takes
    * keys that no focused descendant wanted.
    */
  private def handlesKey(element: Element, event: KeyEvent): Boolean =
    element.props.onKey.exists(_(event)) ||
      (element.props.focused && element.builtinKeyHandler.exists(_(event)))

  private def dispatchKeyDepthFirst(element: Element, event: KeyEvent): Boolean =
    !element.props.inert &&
      (element.children.exists(dispatchKeyDepthFirst(_, event)) || handlesKey(element, event))

  /** The focused element and its ancestors, innermost first. */
  private def pathToFocused(element: Element): Option[List[Element]] =
    pathWhere(element, node => node.props.focused && node.props.focusable)
