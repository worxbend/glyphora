package io.worxbend.tui.dsl

import io.worxbend.tui.runtime.{ReactiveScope, Signal}

/** The stack of screens layered over an app's own view, and the `Screen.onEnter`/`Screen.onLeave` ordering that goes
  * with pushing and popping them.
  *
  * Owned by one [[TuiApp]] instance and written only from the render thread, like every other piece of that app's state
  * — the stack itself is a `Signal`, so a view that reads [[top]], [[depth]] or [[labels]] recomputes when navigation
  * moves. Every method here is about *where navigation stands*; deciding what to compose out of it (merging a screen's
  * bindings over the app's, suppressing focus in the layer below a modal) stays in `TuiApp`.
  *
  * Reads come in two spellings on purpose. The `using ReactiveScope` ones subscribe the caller, which is what a `view`
  * wants; the `*Now` ones read through `Signal.peek` and subscribe nothing, which is what an event handler or the
  * frame-composition bookkeeping wants — subscribing from there would attach a dependency to whatever view happened to
  * be recomputing.
  */
private[dsl] final class ScreenStack:

  private val stack: Signal[List[Screen]] = Signal(Nil)

  /** Applies `f` to the stack and answers the screen that *was* on top, so the caller can run its `onLeave` after the
    * write. One helper rather than a `case _ :: tail => … case Nil => …` per operation: the list surgery is total by
    * construction, and the enter/leave ordering — write the stack first, so a callback that reads [[depthNow]] sees the
    * new state — is stated once.
    */
  private def swap(f: List[Screen] => List[Screen]): Option[Screen] =
    val outgoing = stack.peek.headOption
    stack.update(f)
    outgoing

  /** Pushes `screen` and runs its `onEnter`, after the stack has been written. */
  def push(screen: Screen): Unit =
    stack.update(screen :: _)
    screen.onEnter()

  /** Pops the top screen and runs its `onLeave`. No-op on an empty stack. */
  def pop(): Unit = swap(_.drop(1)).foreach(_.onLeave())

  /** Swaps the top screen for `screen` in a single write, so the layer underneath never shows for a frame. On an empty
    * stack this does the same thing as [[push]].
    */
  def replace(screen: Screen): Unit =
    swap(screen :: _.drop(1)).foreach(_.onLeave())
    screen.onEnter()

  /** Unwinds everything at once, running each `onLeave` innermost first. An already-empty stack writes an equal value,
    * which a `Signal` reports to nobody, so no redundant frame is scheduled.
    */
  def reset(): Unit =
    val unwound = stack.peek
    stack.set(Nil)
    leave(unwound)

  /** Runs `onLeave` for everything still on the stack, innermost first, *without* writing the signal — so every
    * `onEnter` is matched exactly once however a run finished.
    *
    * Unlike [[reset]], this preserves per-instance state across runs. Every callback is attempted even if an earlier
    * one throws; later failures are suppressed on the first.
    */
  def leaveAll(): Unit = leave(stack.peek)

  private def leave(screens: List[Screen]): Unit =
    io.worxbend.tui.runtime.Cleanup.all(screens.map(screen => () => screen.onLeave())*)

  /** The screen on top as a reactive read — `None` means the app's own view is showing. */
  def top(using scope: ReactiveScope): Option[Screen] = stack.get(using scope).headOption

  /** [[top]] without subscribing: the spelling for an event handler or for frame bookkeeping. */
  def topNow: Option[Screen] = stack.peek.headOption

  /** Every screen, innermost first, without subscribing — for the layer bookkeeping that counts them. */
  def allNow: List[Screen] = stack.peek

  def depth(using scope: ReactiveScope): Int = stack.get(using scope).size

  def depthNow: Int = stack.peek.size

  /** The names of the screens on the stack, outermost first — the sequence a breadcrumb wants. A screen with no
    * `Screen.label` contributes nothing rather than a blank.
    */
  def labels(using scope: ReactiveScope): Seq[String] = stack.get(using scope).reverse.flatMap(_.label)

  /** Every screen as a reactive read, outermost first — the order the composed view folds them in. */
  def outermostFirst(using scope: ReactiveScope): List[Screen] = stack.get(using scope).reverse

  /** Whether the screen on top is a modal that asked to be closed by `Esc`. Reads through `peek`, because the only
    * caller is the event loop.
    */
  def closesOnEscape: Boolean =
    topNow.exists(screen => screen.presentation == Presentation.Modal && screen.dismissal.byEscape)
