package io.worxbend.tui.runtime

/** The capability that makes a reactive read *tracked*: `Reactive.get` requires one and reports the read to it, so
  * whoever owns the scope learns what was read and can subscribe to changes. Reads that should not subscribe anything
  * use `peek` instead — or [[ReactiveScope.untracked]] when an API demands a scope.
  */
trait ReactiveScope:
  private[runtime] def track(dependency: Subscribable): Unit

/** A tracking scope for a repeatedly re-evaluated computation (an app's `view`): reads subscribe `onInvalidate`, and
  * [[beginGeneration]] — called before each re-evaluation — unsubscribes from values that stopped being read, so
  * signals owned by closed screens or discarded branches do not accumulate stale subscriptions.
  */
final class GenerationalScope private[runtime] (onInvalidate: () => Unit) extends ReactiveScope:

  private var disposed               = false
  private val subscriber: Subscriber = () => if !disposed then onInvalidate()

  // Two buffers swapped per generation, never reallocated: `track` adds every value the in-progress generation reads;
  // `beginGeneration` unsubscribes whatever the previous generation read but the new one did not, then hands the
  // emptied buffer to `track` and keeps what was just read for next time. `subscribe`/`unsubscribe` are idempotent set
  // operations, so clearing a buffer and refilling it is sound.
  private var previous: scala.collection.mutable.Set[Subscribable] = scala.collection.mutable.Set.empty
  private var current: scala.collection.mutable.Set[Subscribable]  = scala.collection.mutable.Set.empty

  private[runtime] def track(dependency: Subscribable): Unit =
    RenderThread.checkRenderThread()
    if disposed then throw IllegalStateException("a disposed reactive scope cannot track reads")
    dependency.subscribe(subscriber)
    val _ = current.add(dependency)

  /** Marks the start of a new evaluation: values read two generations ago but not renewed since are dropped. */
  def beginGeneration(): Unit =
    RenderThread.checkRenderThread()
    if disposed then throw IllegalStateException("a disposed reactive scope cannot begin a generation")
    previous.foreach { dependency =>
      if !current.contains(dependency) then dependency.unsubscribe(subscriber)
    }
    val swap = previous
    previous = current
    current = swap
    current.clear()

  /** Releases subscriptions from both generations. Idempotent; call on the owning render thread before its runner
    * unregisters. The scope cannot be reused after disposal.
    */
  def dispose(): Unit =
    RenderThread.checkRenderThread()
    if !disposed then
      disposed = true
      (previous ++ current).foreach(_.unsubscribe(subscriber))
      previous.clear()
      current.clear()

object ReactiveScope:

  /** Every reactive value read through this scope will invoke `onInvalidate` when it later changes. This is the root
    * scope an application's render loop evaluates `view` under: any `Signal.set` reachable from the last evaluation
    * schedules a redraw.
    */
  def onInvalidation(onInvalidate: () => Unit): ReactiveScope =
    val subscriber: Subscriber = () => onInvalidate()
    dependency => dependency.subscribe(subscriber)

  /** Reads through this scope subscribe nothing — equivalent to `peek`, for tests and non-reactive contexts. */
  val untracked: ReactiveScope = _ => ()

  /** An [[onInvalidation]] scope that also prunes subscriptions not renewed each generation — the right scope for a
    * render loop's repeatedly evaluated view.
    */
  def generational(onInvalidate: () => Unit): GenerationalScope =
    GenerationalScope(onInvalidate)
