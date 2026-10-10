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

  /** Identity belongs to a push, not a Screen: the same screen value may occupy several stack entries. */
  private final class Entry(val id: Long, val screen: Screen):
    var active: Boolean = false

  private val stack: Signal[List[Entry]] = Signal(Nil)
  private var running: Boolean           = false
  private var nextId: Long               = 0L

  private def newEntry(screen: Screen): Entry =
    nextId += 1
    Entry(nextId, screen)

  def entriesNow: Vector[(Long, Screen)] = stack.peek.reverseIterator.map(e => (e.id, e.screen)).toVector

  def entries(using scope: ReactiveScope): Vector[(Long, Screen)] =
    stack.get(using scope).reverseIterator.map(e => (e.id, e.screen)).toVector

  /** Enables immediate entry for navigation from `onStart`; retained entries wait until it completes. */
  def beginRun(): Unit = running = true

  /** Reactivates retained entries, outermost first, after app initialization and before rendering. */
  def enterRemaining(): Unit = stack.peek.reverse.foreach(enter)

  private def enter(entry: Entry): Unit =
    if running && !entry.active && stack.peek.contains(entry) then
      // An attempted acquisition owns cleanup even when onEnter throws part-way through.
      entry.active = true
      entry.screen.onEnter()

  private def leave(entry: Entry): Unit =
    if entry.active then
      // Retire before calling user code: reentrant navigation or a throwing cleanup must never release it twice.
      entry.active = false
      entry.screen.onLeave()

  /** Writes navigation before callbacks, so hooks observe the new depth. */
  private def swap(f: List[Entry] => List[Entry]): Option[Entry] =
    val outgoing = stack.peek.headOption
    stack.update(f)
    outgoing

  /** Pushes `screen` and, during a run, enters it after the stack has been written. */
  def push(screen: Screen): Unit =
    val entry = newEntry(screen)
    stack.update(entry :: _)
    enter(entry)

  /** Pops the top screen and leaves it if active. No-op on an empty stack. */
  def pop(): Unit = swap(_.drop(1)).foreach(leave)

  /** Swaps the top screen for `screen` in a single write, so the layer underneath never shows for a frame. On an empty
    * stack this does the same thing as [[push]].
    */
  def replace(screen: Screen): Unit =
    val entry = newEntry(screen)
    swap(entry :: _.drop(1)).foreach(leave)
    enter(entry)

  /** Unwinds everything at once, leaving active entries innermost first. An already-empty stack writes an equal value,
    * which a `Signal` reports to nobody, so no redundant frame is scheduled.
    */
  def reset(): Unit =
    val unwound = stack.peek
    stack.set(Nil)
    leaveEntries(unwound)

  /** Deactivates retained entries innermost first without clearing navigation. Navigation from cleanup creates inactive
    * entries for a later run. Every active entry's cleanup is attempted even if an earlier one throws.
    */
  def leaveAll(): Unit =
    running = false
    leaveEntries(stack.peek)

  private def leaveEntries(entries: List[Entry]): Unit =
    io.worxbend.tui.runtime.Cleanup.all(entries.map(entry => () => leave(entry))*)

  /** The screen on top as a reactive read — `None` means the app's own view is showing. */
  def top(using scope: ReactiveScope): Option[Screen] = stack.get(using scope).headOption.map(_.screen)

  /** [[top]] without subscribing: the spelling for an event handler or for frame bookkeeping. */
  def topNow: Option[Screen] = stack.peek.headOption.map(_.screen)

  /** Every screen, innermost first, without subscribing — for the layer bookkeeping that counts them. */
  def allNow: List[Screen] = stack.peek.map(_.screen)

  def depth(using scope: ReactiveScope): Int = stack.get(using scope).size

  def depthNow: Int = stack.peek.size

  /** The names of the screens on the stack, outermost first — the sequence a breadcrumb wants. A screen with no
    * `Screen.label` contributes nothing rather than a blank.
    */
  def labels(using scope: ReactiveScope): Seq[String] = stack.get(using scope).reverse.flatMap(_.screen.label)

  /** Every screen as a reactive read, outermost first — the order the composed view folds them in. */
  def outermostFirst(using scope: ReactiveScope): List[Screen] = stack.get(using scope).reverse.map(_.screen)

  /** Whether the screen on top is a modal that asked to be closed by `Esc`. Reads through `peek`, because the only
    * caller is the event loop.
    */
  def closesOnEscape: Boolean =
    topNow.exists(screen => screen.presentation == Presentation.Modal && screen.dismissal.byEscape)
