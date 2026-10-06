package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, KeyEvent, MouseEvent, MouseEventKind, Rect, Size, Style}
import io.worxbend.tui.runtime.{Frame, ReactiveScope}

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
  * on screen rather than to a freshly evaluated tree.
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

  private[dsl] val tracker              = FocusTracker()
  private var lastTree: Option[Element] = None

  /** Resolves `view` for an area of this size, reconciles focus, decorates the tree with `theme`'s focus cue and paints
    * it into `buffer`.
    *
    * The decorated tree is kept, because the `dispatch*` methods below route against what is actually on screen. Call
    * this before dispatching anything: with no frame rendered yet there is no tree, and every dispatch answers `false`.
    */
  def render(area: Rect, buffer: Buffer, theme: Theme, view: View)(using scope: ReactiveScope): Unit =
    val raw = resolveView(view, Size(area.width, area.height), theme)
    renderTree(raw, theme.focus, tree => tree.widget.render(area, buffer))

  /** [[render]] against a runner's `Frame`, for a host inside a `TerminalRunner`. */
  def render(frame: Frame, theme: Theme, view: View)(using scope: ReactiveScope): Unit =
    val raw = resolveView(view, Size(frame.area.width, frame.area.height), theme)
    renderTree(raw, theme.focus, tree => frame.renderWidget(tree.widget, frame.area))

  /** The view resolved against the size actually being painted. */
  private def resolveView(view: View, size: Size, theme: Theme)(using scope: ReactiveScope): Element =
    ResponsivePass.resolve(view(using scope, theme), size)

  /** The half of [[render]] after the view has been resolved: reconcile, decorate, remember, paint.
    *
    * Split out for [[TuiApp]], which has work of its own to do between resolving the view and reconciling focus (it
    * synchronises the focus layers a pushed screen or an open palette adds) and paints through a `Frame`. Everything
    * that decides *where focus is* lives here, so the app and an embedding host cannot answer that question
    * differently.
    */
  private[dsl] def renderTree(raw: Element, focusStyle: Style, paint: Element => Unit): Unit =
    tracker.reconcile(FocusPass.focusKeys(raw), FocusPass.autofocusRequest(raw))
    val tree = FocusPass.decorate(raw, tracker, focusStyle)
    lastTree = Some(tree)
    paint(tree)

  /** Delivers a key to the focused element and the ancestors it bubbles through.
    *
    * `false` means nothing on screen consumed it and the decision is the host's. This method deliberately does not move
    * focus and does not quit: Tab traversal and Ctrl+C are policy, and policy belongs to whoever owns the loop — call
    * [[focusNext]] or [[focusPrevious]] from your own key handling if you want the usual meaning.
    */
  def dispatchKey(key: KeyEvent): Boolean = lastTree.exists(EventRouter.dispatchKey(_, key))

  /** Hit-tests the pointer, moves focus if a press landed inside a different focusable, then routes the event to the
    * element under it. `true` when focus moved or an element consumed the event.
    */
  def dispatchMouse(mouse: MouseEvent): Boolean =
    val target     = tracker.mouseHit(mouse.position)
    val focusMoved =
      if mouse.kind == MouseEventKind.Down then
        target.flatMap(_.target.focusIndex) match
          case Some(index) if index != tracker.focusedIndex =>
            tracker.focusTo(index)
            true
          case _                                            => false
      else false

    val consumed = lastTree.exists(EventRouter.dispatchMouse(_, mouse, target))
    consumed || focusMoved

  /** Offers a key release to the focused element and its ancestors — see [[EventRouter.dispatchKeyRelease]]. */
  def dispatchKeyRelease(key: KeyEvent): Boolean = lastTree.exists(EventRouter.dispatchKeyRelease(_, key))

  /** Delivers a bracketed-paste payload to the focused element. `false` when nothing took it. */
  def dispatchPaste(text: String): Boolean = lastTree.exists(EventRouter.dispatchPaste(_, text))

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
