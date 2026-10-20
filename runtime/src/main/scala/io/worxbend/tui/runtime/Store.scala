package io.worxbend.tui.runtime

/** One application state value with pure transitions, cached projections and an optional undo/redo history — sugar over
  * a [[Signal]] and [[Computed]]s, not a second reactive system.
  *
  * {{{
  * final case class Todo(items: Vector[String], filter: String)
  *
  * private val store  = Store(Todo(Vector.empty, ""), history = 50)
  * private val shown  = store.select(s => s.items.filter(_.contains(s.filter)))
  *
  * def view(using ReactiveScope, Theme): Element =
  *   column(list(shown.get, state), button("undo")(store.undo()))
  *
  * store.dispatch(s => s.copy(items = s.items :+ "write docs"))
  * }}}
  *
  * `dispatch` applies `action` to the current state and publishes the result; an action that produces a state the
  * signal's [[SignalEquality]] calls unchanged notifies nobody and records no history entry. `select` is a long-lived
  * [[Computed]]: create it once beside the store, not inside `view`, and dispose it with the store's owner — the
  * contract is the one on `Computed`. With `history > 0` the store keeps up to that many *previous* states; `undo`
  * steps back through them and `redo` forward again until the next `dispatch`, which discards the redo branch.
  *
  * Ownership and threads: the render-thread rules of `Signal` and `Computed` apply unchanged. `dispatch`, `undo`,
  * `redo`, `select` and `get` are render-thread calls (or runner-less unit-test calls); [[peek]] may be read from any
  * thread. The history is plain mutable state touched only on that same thread.
  */
final class Store[S] private (initial: S, limit: Int)(using SignalEquality[S]):

  private val signal: Signal[S] = Signal(initial)

  // most recent first; `past.head` is the state one undo away
  private var past: List[S]   = Nil
  private var future: List[S] = Nil

  /** The state as a read-only reactive value, for a `Computed` or a view that wants to read it directly. */
  val state: Reactive[S] = signal

  /** Reads the state and subscribes the tracking scope, as `Signal.get` does. */
  def get(using scope: ReactiveScope): S = signal.get

  /** Reads the state without subscribing; safe from any thread. */
  def peek: S = signal.peek

  /** A cached projection of the state. Long-lived: create it once and dispose it where the store's owner goes away. */
  def select[A](f: S => A): Computed[A] = Computed(f(signal.get))

  /** Applies a pure transition and publishes the result. A transition that changes nothing is a no-op in every sense:
    * no notification, no history entry, no redo branch discarded.
    */
  def dispatch(action: S => S): Unit =
    signal.checkMutation()
    val before = signal.peek
    val after  = action(before)
    if !summon[SignalEquality[S]].unchanged(after, before) then
      remember(before)
      future = Nil
      signal.set(after)

  /** Whether [[undo]] has a state to go back to. */
  def canUndo: Boolean = past.nonEmpty

  /** Whether [[redo]] has a state to go forward to. */
  def canRedo: Boolean = future.nonEmpty

  /** Steps back one state; `false` when there is none (no history configured, or all of it already undone). */
  def undo(): Boolean =
    signal.checkMutation()
    past match
      case Nil               => false
      case previous :: older =>
        future = signal.peek :: future
        past = older
        signal.set(previous)
        true

  /** Steps forward one state undone by [[undo]]; `false` when there is none. */
  def redo(): Boolean =
    signal.checkMutation()
    future match
      case Nil           => false
      case next :: newer =>
        past = signal.peek :: past
        future = newer
        signal.set(next)
        true

  private def remember(state: S): Unit =
    if limit > 0 then past = (state :: past).take(limit)

object Store:

  /** A store starting at `initial`, keeping up to `history` previous states for [[Store.undo]] (none by default).
    * Change detection follows the [[SignalEquality]] in scope, exactly as `Signal(initial)` would.
    */
  def apply[S](initial: S, history: Int = 0)(using SignalEquality[S]): Store[S] =
    require(history >= 0, s"a store's history depth cannot be negative, got $history")
    new Store(initial, history)
