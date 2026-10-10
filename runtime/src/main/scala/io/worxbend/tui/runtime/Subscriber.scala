package io.worxbend.tui.runtime

import scala.collection.mutable

/** Something that depends on reactive values and must be told when one of them changes.
  *
  * `markStale` is a dirty flag, not a recomputation trigger — recomputation happens lazily on the next read.
  */
private[runtime] trait Subscriber:
  def markStale(): Unit

  /** Forget the reverse edge too, while both endpoints are still confined to the checked owner. */
  private[runtime] def detached(dependency: Subscribable): Unit = ()
  private[runtime] def reactiveOwner: Option[ReactiveOwner]     = None

/** A reactive value that subscribers can attach to. The counterpart of [[Subscriber]]: `Computed` implements both (it
  * subscribes to its dependencies and is subscribed to by its dependents).
  */
private[runtime] trait Subscribable:
  private[runtime] def subscribe(subscriber: Subscriber): Unit
  private[runtime] def unsubscribe(subscriber: Subscriber): Unit

/** The subscriber bookkeeping shared by [[Signal]] and [[Computed]]: who is listening, and how they are told.
  *
  * Mixed into the reactive values themselves rather than held as a field, so both types answer `subscribe` /
  * `unsubscribe` / `subscriberCount` identically and there is one place to change how a notification is delivered.
  *
  * Render-thread-confined: the set is a plain `mutable.LinkedHashSet` with no volatile field and no lock, so every
  * member here must be called on the render thread. `Signal.peek` is the deliberate exception in this package, and it
  * does not touch this set. Insertion order is preserved so that notification order is reproducible in tests.
  */
private[runtime] trait SubscriberRegistry extends Subscribable:

  protected val ownership = new ReactiveOwner
  private val subscribers = mutable.LinkedHashSet[Subscriber]()

  private[runtime] def subscribe(subscriber: Subscriber): Unit =
    ownership.checkTogether(subscriber.reactiveOwner)
    if subscribers.add(subscriber) then subscriber.reactiveOwner.foreach(ownership.connect)

  private[runtime] def unsubscribe(subscriber: Subscriber): Unit =
    ownership.checkTogether(subscriber.reactiveOwner)
    if subscribers.remove(subscriber) then
      subscriber.reactiveOwner.foreach(ownership.disconnect)
      subscriber.detached(this)

  /** Live subscriber count. Package-private: regression tests assert that repeated derivation does not grow it. */
  private[runtime] def subscriberCount: Int =
    ownership.check()
    subscribers.size

  /** Marks every current subscriber stale.
    *
    * Iterates a copy: a subscriber may unsubscribe (or subscribe another) while being notified — a `Computed` that
    * recomputes during the cascade rewrites its own edges — and mutating the set under its own iterator would throw.
    */
  protected def notifySubscribers(): Unit =
    subscribers.toSeq.foreach(_.markStale())

  /** Drops every subscriber. Notify first when the dependents still need to know they were detached. */
  protected def clearSubscribers(): Unit =
    ownership.check()
    subscribers.toSeq.foreach(unsubscribe)
