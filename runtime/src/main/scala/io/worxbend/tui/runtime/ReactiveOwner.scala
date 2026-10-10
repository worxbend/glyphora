package io.worxbend.tui.runtime

import scala.collection.mutable

/** Ownership follows connected subscription edges, including graphs evaluated before any runner starts. Only initial
  * binding / retirement transfer walks the component; ordinary owner-thread reads use the volatile fast path. The lock
  * serializes competing first claims, not user callbacks or graph evaluation. Nested runners are distinct owners.
  */
private[runtime] final class ReactiveOwner:
  @volatile private var owner = RenderThread.currentOwner
  private val neighbors       = mutable.Map.empty[ReactiveOwner, Int]

  def check(): Unit = checkTogether(None)

  /** Validate every endpoint's connected component before committing any first claim. */
  def checkTogether(other: Option[ReactiveOwner]): Unit =
    RenderThread.checkRenderThread()
    val current = RenderThread.currentOwner
    if owner != current || other.exists(_.owner != current) then
      ReactiveOwner.synchronized {
        val component = mutable.Set.empty[ReactiveOwner]
        val pending   = mutable.Stack(this)
        pending.pushAll(other)
        while pending.nonEmpty do
          val node = pending.pop()
          if component.add(node) then
            if node.owner.exists(loop => !loop.isClosed && !current.contains(loop)) then
              throw IllegalStateException(
                "Reactive graph belongs to another render owner; use its captured render loop"
              )
            pending.pushAll(node.neighbors.keysIterator)
        component.foreach(_.owner = current)
      }

  def connect(other: ReactiveOwner): Unit = ReactiveOwner.synchronized {
    checkTogether(Some(other))
    neighbors.update(other, neighbors.getOrElse(other, 0) + 1)
    other.neighbors.update(this, other.neighbors.getOrElse(this, 0) + 1)
  }

  def disconnect(other: ReactiveOwner): Unit = ReactiveOwner.synchronized {
    checkTogether(Some(other))
    decrement(other)
    other.decrement(this)
  }

  private def decrement(other: ReactiveOwner): Unit =
    neighbors.get(other).foreach { count =>
      if count == 1 then
        val _ = neighbors.remove(other)
      else neighbors.update(other, count - 1)
    }

private[runtime] object ReactiveOwner
