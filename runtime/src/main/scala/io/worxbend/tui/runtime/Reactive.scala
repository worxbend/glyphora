package io.worxbend.tui.runtime

import scala.collection.mutable
import scala.compiletime.uninitialized

/** A readable reactive value: a mutable [[Signal]], a cached [[Computed]], or a transparent [[Derived]] view.
  *
  * Reads come in two flavors: `get` requires a [[ReactiveScope]] capability and subscribes the enclosing computation to
  * future changes (automatic dependency tracking — no manual dependency arrays); `peek` reads untracked. Dependency
  * edges are re-established on every recomputation, so conditional reads (`if cond.get then a.get else b.get`)
  * subscribe exactly the branch that actually ran.
  */
sealed trait Reactive[A]:

  /** Read without subscribing the caller.
    *
    * On a [[Computed]] this still re-establishes the computed's *own* dependency edges when the cached value is stale —
    * `peek` promises only that the reader is not subscribed, not that nothing at all subscribes.
    *
    * Which threads may call it is decided by the implementation, not by this trait, and the three differ: see
    * [[Signal]] (any thread), [[Computed]] (render thread only — `peek` writes) and [[Derived]] (any thread its source
    * allows). Do not generalise from one to another.
    */
  def peek: A

  /** Read and subscribe the computation this scope tracks for. */
  def get(using scope: ReactiveScope): A

  /** A derived view of this value: `f` is applied on every read, and the result subscribes to nothing of its own.
    *
    * Safe to create inside a repeatedly evaluated `view` body — reading the result through a scope subscribes that
    * scope to *this* value, not to a per-generation intermediate, so nothing accumulates. `f` therefore re-runs per
    * read: when the derivation is expensive enough to be worth caching, write `Computed { f(source.get) }` instead and
    * dispose it when its owner goes away.
    */
  def map[B](f: A => B): Derived[B] = Derived(f(get))

/** A mutable reactive variable.
  *
  * `set`/`update` mark dependents stale and (via the root scope) schedule a redraw; nothing recomputes eagerly. Setting
  * an equal value notifies nobody — change detection is delegated to the [[SignalEquality]] given in scope at creation
  * (by default `==`, with `Double`/`Float` comparing by IEEE-754 total order). A value mutated *in place* is equal to
  * itself, so `set(sameInstance)` never notifies: hold immutable values in a signal, or set a new instance.
  *
  * Tracked reads and writes belong to one render owner, not just any registered render thread. Construction on an owner
  * binds immediately; runnerless construction binds its connected graph at first owner access. A live foreign owner
  * (including a nested runner) is rejected. After retirement, the graph may bind to a subsequent run. With no running
  * runtime, ordinary sequential unit-test reads and writes remain permitted.
  *
  * Writing is render-thread-only, but [[peek]] may be called from any thread: the value is `@volatile`, so a reader
  * outside the render thread is guaranteed to see the most recently set value rather than an arbitrarily stale one.
  * That guarantee is what makes a test harness sound — `Pilot` drives the app from the test thread while the runner
  * mutates signals on its own, and asserting on `peek` from there would otherwise be reading a field with no
  * happens-before edge to the write. The subscriber set is deliberately *not* published that way; it is touched only on
  * the render thread.
  */
final class Signal[A] private (initial: A, equality: SignalEquality[A]) extends Reactive[A], SubscriberRegistry:

  // @volatile for cross-thread readers of `peek` only — see the class Scaladoc. The cost is a plain load on the read
  // side of every architecture glyphora targets; the fence is on `set`, which is orders of magnitude rarer than the
  // per-frame reads.
  @volatile private var currentValue: A = initial

  def peek: A = currentValue

  def get(using scope: ReactiveScope): A =
    // Subscribe validates both connected components before either can be claimed by this read.
    scope.track(this)
    if scope ne ReactiveScope.untracked then ownership.check()
    currentValue

  def set(value: A): Unit =
    ownership.check()
    if !equality.unchanged(value, currentValue) then
      currentValue = value
      notifySubscribers()

  def update(f: A => A): Unit =
    ownership.check()
    set(f(currentValue))

object Signal:

  /** A signal holding `initial`, whose change detection is delegated to the [[SignalEquality]] in scope.
    *
    * With no local given, the defaults from the [[SignalEquality]] companion apply: `==` for most types, IEEE-754 total
    * order for `Double` and `Float`. A `given SignalEquality[A]` in lexical or imported scope overrides them for the
    * signals created under it.
    */
  def apply[A](initial: A)(using equality: SignalEquality[A]): Signal[A] = new Signal(initial, equality)

/** A value derived from other reactive values.
  *
  * Lazily cached: `set` on a dependency only marks this stale (cascading to dependents); the thunk re-runs on the next
  * read. Each recomputation first unsubscribes from the previous dependency set, then re-subscribes to exactly what the
  * thunk reads this time — the mechanism that makes conditional dependencies correct.
  *
  * The dependency graph must be acyclic. A thunk that reads the value it is itself computing — directly, or around a
  * cycle through other computeds — throws `IllegalStateException` on the read that closes the loop, rather than
  * recursing until the stack runs out.
  *
  * Render-thread-confined in *both* directions, unlike [[Signal]]. Reading is not the safe half here: `peek` (and
  * therefore `get`) recomputes when the cache is stale, and recomputing rewrites the cached value, the stale flag, the
  * dirty epoch and both the dependency and subscriber sets — none of which is volatile or guarded by a lock.
  * `markStale` and `dispose` mutate those same sets. All four enforce the graph's specific render owner, using the same
  * initial-binding and post-retirement rebinding rules as [[Signal]]. In particular `Signal.peek` is explicitly safe
  * off the render thread and `Computed.peek` is not; foreign-owner access throws before touching cached state. Read the
  * signals a background thread needs directly, or marshal the read back with [[RenderThread.runLater]].
  */
final class Computed[A] private (thunk: ReactiveScope ?=> A) extends Reactive[A], Subscriber, SubscriberRegistry:

  override private[runtime] def reactiveOwner: Option[ReactiveOwner] = Some(ownership)

  private var cachedValue: A = uninitialized
  private var stale          = true
  // counts invalidations rather than just flagging them, so a recomputation can tell whether it was invalidated again
  // while its own thunk was running
  private var dirtyEpoch     = 0L
  private var recomputing    = false
  private val dependencies   = mutable.LinkedHashSet[Subscribable]()

  override private[runtime] def detached(dependency: Subscribable): Unit =
    val _ = dependencies.remove(dependency)

  def peek: A =
    ownership.check()
    if stale then recompute()
    cachedValue

  def get(using scope: ReactiveScope): A =
    // Subscribe validates both connected components before either can be claimed by this read.
    scope.track(this)
    if scope ne ReactiveScope.untracked then ownership.check()
    peek

  /** Flags this value dirty and cascades to dependents.
    *
    * Dependents are notified when this node *becomes* stale — an already-stale node has told them once and does not
    * repeat itself. The exception is an invalidation raised while the thunk is running (a dependency written from
    * inside it): that one arrived after the notification that started this recomputation, refers to the value being
    * produced right now, and would otherwise never reach anyone.
    */
  def markStale(): Unit =
    ownership.check()
    dirtyEpoch += 1
    val wasFresh = !stale
    stale = true
    if wasFresh || recomputing then notifySubscribers()

  /** Detaches this computed from its dependencies and dependents.
    *
    * A `Computed` created inside a repeatedly-evaluated context (a `view` body) re-subscribes on every evaluation and
    * is otherwise never released — long-lived derived values belong outside `view`, and short-lived ones should be
    * disposed. The app root scope prunes its own stale subscriptions automatically; this handles the computed's
    * internal edges.
    *
    * Dependents are invalidated on the way out: they still hold an edge *to* this computed, so they must re-derive
    * rather than keep a cache this computed no longer maintains. Reading a disposed computed re-attaches it — dispose
    * detaches, it does not close.
    */
  def dispose(): Unit =
    ownership.check()
    dependencies.toSeq.foreach(_.unsubscribe(this))
    dependencies.clear()
    stale = true
    dirtyEpoch += 1
    notifySubscribers()
    clearSubscribers()

  private def recompute(): Unit =
    // a thunk that reads its own value — directly, or around a cycle through another computed — would otherwise
    // recurse until the stack runs out. `StackOverflowError` is fatal, so a render loop cannot report it through its
    // `NonFatal` handler and the runner dies with nothing pointing at the cycle. A programming error, not a
    // recoverable condition, so it throws rather than returning a stale value and pretending the graph is acyclic.
    if recomputing then
      throw IllegalStateException(
        "Computed value depends on itself: its body read the value it is in the middle of computing, " +
          "directly or through a cycle of other Computed values"
      )
    dependencies.toSeq.foreach(_.unsubscribe(this))
    dependencies.clear()
    val recomputeScope: ReactiveScope = dependency =>
      dependency.subscribe(this)
      val _ = dependencies.add(dependency)
    val epochBefore                   = dirtyEpoch
    recomputing = true
    cachedValue =
      try thunk(using recomputeScope)
      finally recomputing = false
    // a thunk that wrote to one of its own dependencies invalidated the value it just produced: stay stale so the next
    // read re-runs, rather than caching a value that is already out of date
    stale = dirtyEpoch != epochBefore

object Computed:
  def apply[A](thunk: ReactiveScope ?=> A): Computed[A] = new Computed(thunk)

/** A transparent view of another reactive value: `read` re-runs on every access and nothing is cached.
  *
  * Unlike [[Computed]] this holds no subscription of its own — a tracked read passes the caller's scope straight
  * through to the underlying values, so the caller subscribes to the source rather than to an intermediate. There is
  * consequently no lifecycle and nothing to dispose: an instance created inside a `view` body and abandoned after one
  * frame leaves no edge behind. The trade is that `read` is evaluated per access; use [[Computed]] when the derivation
  * is expensive enough to be worth caching, and give that computed an owner that disposes it.
  *
  * Holds no mutable state, so an instance may be read from any thread its source allows.
  */
final class Derived[A] private (read: ReactiveScope ?=> A) extends Reactive[A]:

  def peek: A                            = read(using ReactiveScope.untracked)
  def get(using scope: ReactiveScope): A = read(using scope)

object Derived:
  def apply[A](read: ReactiveScope ?=> A): Derived[A] = new Derived(read)
