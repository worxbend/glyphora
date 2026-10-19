package io.worxbend.tui.dsl

import io.worxbend.tui.core.Versioned

/** The versioned widget states a resolved tree paints, each once, with the version a poll compares against.
  *
  * A frame collects its states in the fused resolution walk ([[FrameAnalysis.states]]) and reads their versions here
  * once the paint is done; [[collect]] is the stand-alone walk, kept as the reference the fused one is checked against.
  *
  * A [[io.worxbend.tui.widgets.LazyListState]] whose paint reached an item that was not built is recorded as already
  * stale ([[Stale]]), so the next poll asks for the frame that builds it — the paint itself is the mutation there. A
  * `ScrollViewState` that scrolled after its paint (a reveal it could not resolve beforehand) is stale the same way.
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
          found += state -> versionOf(state)
      }
      node.children.foreach(walk)
    walk(tree)
    found.result()

  /** Each of `states` with the version a poll compares against. */
  def versions(states: Vector[Versioned]): Vector[(Versioned, Long)] = states.map(state => state -> versionOf(state))

  private def versionOf(state: Versioned): Long =
    state match
      case lazyList: io.worxbend.tui.widgets.LazyListState if lazyList.missedItems => Stale
      // a scroll view that moved its offset after painting owes the frame that shows it
      case scroll: io.worxbend.tui.widgets.ScrollViewState if scroll.owesFrame     => Stale
      case _                                                                       => state.version
