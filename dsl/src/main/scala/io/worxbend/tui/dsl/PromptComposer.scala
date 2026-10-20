package io.worxbend.tui.dsl

import io.worxbend.tui.runtime.Async

/** Runtime adapter only. Every request gets a fresh owner scope; cancelling it closes that scope, so retained widget
  * state never reuses a scope from a retired runner. The widget owns draft/history/painting without a runtime edge.
  */
private[dsl] object PromptComposerAsync:
  def attach(state: PromptComposerState): Unit =
    state.scheduleCompletion = (next, deliver) =>
      Async.scopeIfRunning() match
        case None        => () => ()
        case Some(scope) =>
          val _ = scope.after(state.debounce) {
            val _ = scope.latest(state)(state.completions(next.trigger, next.query)) {
              case Right(items) =>
                try deliver(items)
                finally scope.close()
              case Left(_)      => scope.close()
            }
          }
          () => scope.close()
