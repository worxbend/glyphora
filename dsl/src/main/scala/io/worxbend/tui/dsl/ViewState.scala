package io.worxbend.tui.dsl

import io.worxbend.tui.core.Rect
import io.worxbend.tui.runtime.{Cleanup, Signal}

import scala.collection.mutable

/** State that belongs to a *place in the view tree* rather than to the application object.
  *
  * Why this exists. Everything reactive in glyphora is either a `Signal` the app declares as one of its own fields or a
  * caller-owned widget state (`ListState`, `TextInputState`). Both have to be declared next to the app and threaded by
  * hand into the factory call that uses them, which means a reusable piece of view — a collapsible section, a counter,
  * a search box — cannot own anything privately. Its caller has to declare the state and pass it in, and a caller that
  * builds five of them has to declare five.
  *
  * `useSignal` and `useState` remove that. They hand back a value that is created the first frame the call is reached
  * and then given back, the same instance, on every later frame that reaches it again — so the state belongs to the
  * call site rather than to whoever wrote the app.
  *
  * The identity of a call site is the chain of enclosing [[keyed]] scopes plus the position of the call within its
  * scope. That is what makes the one rule worth memorising: **a view must reach the same hook calls in the same order
  * every frame**. A hook behind an `if` that flips changes the positions of everything after it, and the state shifts
  * between call sites. Building elements in a loop needs an identity per iteration, keyed by the item's own identity
  * (its id, not its index), so that adding or removing an item does not renumber its neighbours: `each` provides it
  * together with the focus key, and [[keyed]] is the same scope on its own for a loop `each` does not fit. Declaring a
  * responsive builder reserves one position; its delayed hooks run in a child scope captured at that declaration.
  * Reusing the same responsive value in multiple places gives each occurrence its own positional child scope.
  *
  * Lifetime: a slot is created on the first frame that reaches it and dropped at the end of the first frame that does
  * not. Hiding a subtree and showing it again therefore starts it fresh, which is the behaviour a collapsed panel
  * wants; state that must outlive its own disappearance belongs in a `Signal` field on the app. Full-screen navigation
  * retains ordinary eager state in covered entries without evaluating their builders; resources and discarded
  * responsive slots are still swept. Uncovering restarts the resources with the retained ordinary state.
  *
  * Lifetime hooks. A slot whose value is a [[ViewState.HookResource]] — the cells behind `useEffect` and `useTask` — is
  * disposed when it is swept, when the host is reset, and when the run ends, which is what cancels an effect or a task
  * the moment its node stops being drawn. Effects start *after* the frame that reached them has been painted: the host
  * calls [[afterPaint]] once the paint is published, and that is also when `useArea` cells compare what they answered
  * with where their node actually landed.
  *
  * Ownership and threads: one store belongs to one `ElementHost` mount (one `TuiApp` run) and is touched only while
  * that run's render thread is evaluating or painting the view. Nothing here is synchronised, and nothing needs to be.
  */
final class ViewState private[dsl] (private[dsl] val startResources: Boolean = true):

  private val slots: mutable.LinkedHashMap[String, ViewState.Slot] = mutable.LinkedHashMap.empty
  private val occurrences: mutable.Map[List[String], Int]          = mutable.Map.empty
  private var resolvedOccurrences: Map[List[String], Int]          = Map.empty

  private[dsl] def resolved(): Unit = resolvedOccurrences = occurrences.toMap

  /** Repainting an unresolved fallback revisits its identity, rather than allocating another occurrence each tick. */
  private[dsl] def beforePaint(): Unit =
    occurrences.clear()
    occurrences ++= resolvedOccurrences

  /** The chain of user-key and deferred-position segments, innermost first. Segment kinds have distinct prefixes. */
  private var path: List[String] = Nil

  /** How many hook calls have already been made inside the innermost scope. */
  private var ordinal: Int = 0

  /** Only an each row admits the legacy, directly nested keyed(rowKey) spelling. */
  private var eachKey: Option[String] = None

  /** Which frame is being evaluated. A slot reached during this frame is stamped with it; anything left holding an
    * older stamp when the frame ends was not reached and is swept.
    */
  private var generation: Long = 0L

  /** Work queued during evaluation for after the paint — effect starts and restarts — in the order it was queued. */
  private val paintQueue = mutable.ArrayBuffer.empty[() => Unit]

  /** The `useArea` cells reached by the generation being evaluated, checked against the paint in [[afterPaint]]. */
  private val reachedAreas = mutable.ArrayBuffer.empty[ViewState.AreaCell]

  /** What a hook calls when something it owns changed between frames — a task's result arrived, a node's area moved —
    * and the view must be evaluated again. The host's session points it at a signal the view reads; a bare store (a
    * unit test) leaves it inert.
    */
  private[dsl] var requestFrame: () => Unit = () => ()

  /** Where the node declared with `.key(key)` was painted on the last published frame — the host's bounds recorder. */
  private[dsl] var areaOf: String => Option[Rect] = _ => None

  /** Starts a new frame: nothing has been reached yet, and the scope path is back at the root. */
  private[dsl] def beginGeneration(): Unit =
    generation += 1
    occurrences.clear()
    reachedAreas.clear()
    path = Nil
    ordinal = 0
    eachKey = None

  /** Drops every slot the generation that just ended did not reach, disposing the hook resources among them. */
  private[dsl] def sweep(): Unit =
    val stale   = slots.collect { case (id, slot) if slot.generation != generation => id }.toList
    val retired = stale.flatMap(slots.remove).map(_.value)
    Cleanup.all(retired.map(value => () => ViewState.dispose(value))*)

  /** Runs once the frame this generation built has been painted and its bounds published: queued effects start, and a
    * `useArea` cell whose node landed somewhere other than what it answered asks for the frame that corrects it.
    */
  private[dsl] def afterPaint(): Unit =
    val moved  = reachedAreas.exists(cell => cell.key.flatMap(areaOf) != cell.seen)
    reachedAreas.clear()
    val queued = paintQueue.toList
    paintQueue.clear()
    queued.foreach(run => run())
    if moved then requestFrame()

  /** Disposes every slot — the end of the mount. Caller-owned state reached through `useState` is not closed. */
  private[dsl] def disposeAll(): Unit =
    val values = slots.valuesIterator.map(_.value).toList
    slots.clear()
    paintQueue.clear()
    reachedAreas.clear()
    Cleanup.all(values.map(value => () => ViewState.dispose(value))*)

  /** Queues `action` for [[afterPaint]]. */
  private[dsl] def onAfterPaint(action: () => Unit): Unit = paintQueue += action

  /** Records that a `useArea` cell answered this generation. */
  private[dsl] def reachedArea(cell: ViewState.AreaCell): Unit = reachedAreas += cell

  /** The innermost user key on the scope path — the `each` row or `keyed` scope a hook call sits in. */
  private[dsl] def innermostKey: Option[String] =
    path.collectFirst { case segment if segment.startsWith("key:") => segment.stripPrefix("key:") }

  /** Keeps ordinary eager state in covered navigation entries without evaluating their builders. Resource hooks and
    * deferred responsive hooks remain unvisited and are swept, matching the disappearance contract.
    */
  private[dsl] def retainCoveredScreens(ids: Set[Long]): Unit =
    val keys = ids.map(id => s"screen:$id")
    slots.valuesIterator.foreach { slot =>
      if !slot.deferred && !ViewState.isResource(slot.value) && slot.screen.exists(keys.contains) then
        slot.generation = generation
    }

  /** Refreshes one memo slot. Commit deps before rebuilding: a throwing rebuild retains the previous value. */
  private[dsl] def memo[A](deps: Seq[Any])(build: () => A): A =
    val cell = slot(() => ViewState.MemoCell(deps, build()))
    if cell.deps != deps then
      cell.deps = deps
      cell.value = build()
    cell.value

  /** How many slots are alive — for tests that want to prove a hidden subtree's state was actually released. */
  private[dsl] def slotCount: Int = slots.size

  /** Runs `body` with `key` pushed onto the scope path, so hooks inside it belong to this key rather than to the
    * position the body happens to occupy among its siblings.
    *
    * A keyed scope opened first inside an each row of the same key is that scope, not a second one under it:
    * `each(items)(_.id)(item => keyed(item.id)(row(item)))` — code written for 0.19, where `each` opened no hook scope
    * and the `keyed` was needed — keeps the one path `keyed(item.id)` alone had, rather than nesting it as `id/id`.
    */
  private[dsl] def scoped[A](key: String)(body: => A): A =
    val segment = s"key:$key"
    if ordinal == 0 && eachKey.contains(key) then
      eachKey = None
      // Keep the body's hook numbering: restoring it to one would alias a second body hook with a later row hook.
      body
    else nested(segment)(body)

  private[dsl] def each[A](key: String)(body: => A): A =
    nested(s"key:$key") {
      eachKey = Some(key)
      body
    }

  private[dsl] def screen[A](id: Long)(body: => A): A = nested(s"screen:$id")(body)

  private def nested[A](key: String)(body: => A): A =
    val savedPath    = path
    val savedOrdinal = ordinal
    val savedEachKey = eachKey
    eachKey = None
    path = key :: savedPath
    ordinal = 0
    try body
    finally
      path = savedPath
      // a whole keyed scope counts as one step of its parent's numbering, so siblings after it stay put
      ordinal = savedOrdinal + 1
      eachKey = savedEachKey

  /** Reserve one positional child at declaration time; its builder may run after keyed scopes have unwound. */
  private def capture(): List[String] =
    val captured = s"deferred:$ordinal" :: path
    ordinal += 1
    captured

  /** Resume a delayed child without advancing or otherwise changing the caller's hook numbering. Repeated uses of one
    * immutable element are numbered in resolution order, independently of distinct declarations.
    */
  private def resume[A](captured: List[String])(body: => A): A =
    val savedPath    = path
    val savedOrdinal = ordinal
    val savedEachKey = eachKey
    eachKey = None
    val occurrence   = occurrences.getOrElse(captured, 0)
    occurrences.update(captured, occurrence + 1)
    path = s"occurrence:$occurrence" :: captured
    ordinal = 0
    try body
    finally
      path = savedPath
      ordinal = savedOrdinal
      eachKey = savedEachKey

  /** The value this call site owns, creating it the first time the site is reached.
    *
    * The cast is safe because the identifier is derived from the call site, and one call site always asks for the same
    * type: the only way to reach an existing slot with a different `A` is to break the same-order rule the class
    * documentation states, which changes the shape of the view rather than only its types.
    */
  private[dsl] def slot[A](create: () => A): A =
    val id = ViewState.identify(path, ordinal)
    ordinal += 1
    slots.get(id) match
      case Some(existing) =>
        existing.generation = generation
        existing.value.asInstanceOf[A] // scalafix:ok DisableSyntax.asInstanceOf; erased, see the Scaladoc above
      case None =>
        val created = create()
        val _       = slots.put(
          id,
          ViewState.Slot(
            created,
            generation,
            path.find(_.startsWith("screen:")),
            path.exists(_.startsWith("deferred:")),
          ),
        )
        created

object ViewState:

  /** The identifier of one call site: its chain of enclosing keys, outermost first, plus its position in its scope.
    *
    * Each key is written as its own length, a colon, and then the key itself — `"3:a/b"` — rather than being joined
    * with a plain separator. That is what makes two different paths impossible to spell the same way, and both ways of
    * spelling one used to be reachable:
    *
    *   - the root scope has no keys at all, and so did a path made of one empty key, so `useSignal("a")` at the root
    *     and `keyed("")(useSignal(0))` were both `"#0"`;
    *   - `keyed("a/b")` and `keyed("a")(keyed("b"))` both spelled `"a/b"`.
    *
    * In each case the second call found the first one's slot and got its value back through the unchecked cast in
    * [[slot]], which then failed as a `ClassCastException` wherever the value was actually used. Prefixing the length
    * removes the ambiguity: a reader of the identifier always knows where one key ends, whatever characters it holds.
    */
  private def identify(path: List[String], ordinal: Int): String =
    val id = StringBuilder()
    path.reverse.foreach { key =>
      id ++= key.length.toString
      id += ':'
      id ++= key
      id += '/'
    }
    id += '#'
    id ++= ordinal.toString
    id.result()

  /** A slot value that holds something to release — a running effect, a task scope — when its call site goes away. */
  private[dsl] trait HookResource:
    def dispose(): Unit

  private def isResource(value: Any): Boolean =
    value match
      case _: HookResource => true
      case _               => false

  private def dispose(value: Any): Unit =
    value match
      case resource: HookResource => resource.dispose()
      case _                      => ()

  /** The slot behind `useArea`: the innermost key it answers for, and the rect it last answered. */
  private[dsl] final class AreaCell(val key: Option[String]):
    var seen: Option[Rect] = None

  /** One call site's value, plus the last frame that reached it. */
  private final class Slot(
      val value: Any,
      var generation: Long,
      val screen: Option[String],
      val deferred: Boolean,
  )

  private object Slot:
    def apply(value: Any, generation: Long, screen: Option[String], deferred: Boolean): Slot =
      new Slot(value, generation, screen, deferred)

  /** The store the view currently being evaluated belongs to.
    *
    * Per thread, because each `Runner` owns its own render thread and several runners can share a JVM. There is never
    * more than one writer per entry: a view is evaluated on the render thread and nowhere else.
    */
  private def active: Option[ViewState] = FrameContext.current.viewState

  /** Capture lexical hook identity without retaining a run's store. Plain construction stays usable without a view. */
  private[dsl] def deferred[A, B](build: A => B): A => B =
    active match
      case None        => build
      case Some(store) =>
        val captured = store.capture()
        input =>
          active match
            case Some(currentStore) => currentStore.resume(captured)(build(input))
            case None               => build(input)

  /** A keyed collection is one declaration, regardless of how many rows it builds this frame. */
  private[dsl] def collectionIfActive[A](body: => A): A =
    active match
      case None        => body
      case Some(store) =>
        val savedOrdinal = store.ordinal
        try body
        finally store.ordinal = savedOrdinal + 1

  /** The hook-identity half of `each`: a keyed scope when a view is being evaluated, and a plain call otherwise.
    *
    * Unlike [[keyed]] this does not insist on a view, because `each` is also a plain element builder — construction
    * tests and helpers call it with no store installed, and an identity that nothing will ever look up again costs
    * nothing to skip.
    */
  private[dsl] def scopeIfActive[A](key: String)(body: => A): A =
    active match
      case Some(store) => store.each(key)(body)
      case None        => body

  /** The slot behind `memo`: the value built last time with its deps, rebuilt only when the deps differ. Generic rather
    * than typed to `Element` so this file names nothing from the element tree: `ElementFactories` reaches in here, and
    * a reference back out would make the two complete each other.
    */
  private[dsl] final class MemoCell[A](var deps: Seq[Any], var value: A)

  /** `memo`'s cache when a view is being evaluated, and a plain build otherwise — the same stance as [[scopeIfActive]],
    * and for the same reason: a subtree built outside a view has no frame to be reused on.
    */
  private[dsl] def memoIfActive[A](deps: Seq[Any])(build: () => A): A =
    active match
      case Some(store) => store.memo(deps)(build)
      case None        => build()

  /** Evaluates `body` with `store` installed as the one hooks attach to. */
  private[dsl] def during[A](store: ViewState)(body: => A): A =
    val context  = FrameContext.current
    val previous = context.viewState
    context.viewState = Some(store)
    try body
    finally context.viewState = previous

  /** The installed store, or a failure explaining what to do instead.
    *
    * Calling a hook from an event handler, a timer or a plain unit test is not a mistake worth guessing around: there
    * is no place in the tree for the state to belong to, so the message names the alternative rather than inventing an
    * anonymous slot that nothing would ever reach again.
    */
  private[dsl] def current: ViewState =
    active match
      case Some(store) => store
      case None        =>
        throw new IllegalStateException(
          "Hooks (useSignal, useState, useEffect, useMemo, useTask, useArea) and keyed may only be called while an " +
            "ElementHost or TuiApp view is being evaluated on the render " +
            "thread. An event handler, a timer body or a test helper has no place in the view tree to attach state " +
            "to; declare a Signal field on the app and read that instead."
        )

/** A `Signal` owned by this call site: created on the first frame that reaches this call, handed back unchanged on
  * every later frame that reaches it, and dropped one frame after the call stops being reached.
  *
  * It is an ordinary `Signal` — writing it invalidates the view that read it and schedules a redraw, exactly as a
  * `Signal` declared as a field on the app does. The only thing that changes is who owns it:
  *
  * {{{
  * def counter(label: String)(using ReactiveScope, Theme): Element =
  *   val count = useSignal(0)
  *   row(text(s"\$label: \${count.get}"), button("+")(count.update(_ + 1)))
  * }}}
  *
  * `initial` is by-name and is evaluated only on the frame that creates the slot, so an expensive starting value costs
  * nothing on later frames.
  *
  * Read the ordering rule on [[ViewState]] before using this inside an `if` or a loop.
  */
def useSignal[A](initial: => A): Signal[A] =
  ViewState.current.slot(() => Signal(initial))

/** A plain mutable value owned by this call site — the spelling for the caller-owned widget states (`ListState`,
  * `TextInputState`, `TreeState`) that a reusable piece of view wants to keep private:
  *
  * {{{
  * def searchBox(using ReactiveScope, Theme): Element =
  *   input(useState(TextInputState()))
  * }}}
  *
  * The slot itself is not reactive, but the widget states are: each is [[io.worxbend.tui.core.Versioned]], and the host
  * watches the ones it painted, so a mutation made outside the event path — on the render thread — is drawn on the next
  * loop iteration, exactly as it is for a state the app declares as a field.
  *
  * `create` is by-name and runs only on the frame that creates the slot.
  */
def useState[S](create: => S): S =
  ViewState.current.slot(() => create)

/** Gives everything built inside `body` an identity of its own, so sibling instances of the same call site keep their
  * own state.
  *
  * Without it, three rows built by the same `map` all share one call site and therefore one set of slots. Key them by
  * the item's own identity — never by its index, which renumbers the survivors when an item is removed and hands each
  * of them their neighbour's state:
  *
  * {{{
  * column(rows.map(row => keyed(row.id)(rowView(row)))*)
  * }}}
  *
  * Keys have to be unique among siblings only, not across the whole view: a key is a step in a path, so the same key
  * under two different parents is two different places.
  */
def keyed[A](key: String)(body: => A): A =
  ViewState.current.scoped(key)(body)
