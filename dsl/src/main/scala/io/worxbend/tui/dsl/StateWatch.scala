package io.worxbend.tui.dsl

import io.worxbend.tui.core.Versioned

/** Collects the versioned widget states a resolved tree paints, each once, with its current version. */
private[dsl] object StateWatch:

  def collect(tree: Element): Vector[(Versioned, Long)] =
    val seen                      = java.util.IdentityHashMap[Versioned, java.lang.Boolean]()
    val found                     = Vector.newBuilder[(Versioned, Long)]
    def walk(node: Element): Unit =
      node.watchedStates.foreach { state =>
        if !seen.containsKey(state) then
          val _ = seen.put(state, java.lang.Boolean.TRUE)
          found += state -> state.version
      }
      node.children.foreach(walk)
    walk(tree)
    found.result()
