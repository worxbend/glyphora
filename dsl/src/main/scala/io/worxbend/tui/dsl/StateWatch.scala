package io.worxbend.tui.dsl

import io.worxbend.tui.core.Versioned

/** Collects the versioned widget states a resolved tree paints, each once, with its current version.
  *
  * A [[io.worxbend.tui.widgets.LazyListState]] whose paint reached an item that was not built is recorded as already
  * stale ([[Stale]]), so the next poll asks for the frame that builds it — the paint itself is the mutation there.
  */
private[dsl] object StateWatch:

  /** A version no state ever reports, so a poll always finds it moved. */
  private val Stale: Long = -1L

  def collect(tree: Element): Vector[(Versioned, Long)] =
    val seen                      = java.util.IdentityHashMap[Versioned, java.lang.Boolean]()
    val found                     = Vector.newBuilder[(Versioned, Long)]
    def walk(node: Element): Unit =
      node.watchedStates.foreach { state =>
        if !seen.containsKey(state) then
          val _ = seen.put(state, java.lang.Boolean.TRUE)
          found += state -> (state match
            case lazyList: io.worxbend.tui.widgets.LazyListState if lazyList.missedItems => Stale
            case _                                                                       => state.version)
      }
      node.children.foreach(walk)
    walk(tree)
    found.result()
