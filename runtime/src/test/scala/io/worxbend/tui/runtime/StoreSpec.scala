package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

/** `Store` is sugar over `Signal`/`Computed`: one state value, pure transitions through `dispatch`, cached projections
  * through `select`, and an optional bounded undo/redo history. No runner is registered here, so the render-thread
  * guard is a no-op and the reactive graph can be driven from the test thread.
  */
final class StoreSpec extends AnyFunSuite:

  private final case class Counter(count: Int, label: String)

  test("history(3): dispatch 5, undo 3, redo 1 lands on the expected state"):
    val store = Store(0, history = 3)
    (1 to 5).foreach(_ => store.dispatch(_ + 1))
    assert(store.peek == 5)
    assert(store.undo())
    assert(store.undo())
    assert(store.undo())
    assert(store.peek == 2)
    assert(!store.undo(), "only three states are kept, so a fourth undo has nothing to go back to")
    assert(store.peek == 2)
    assert(store.redo())
    assert(store.peek == 3)
    assert(store.canRedo)

  test("a dispatch after an undo discards the redo branch"):
    val store = Store(0, history = 5)
    store.dispatch(_ + 1)
    store.dispatch(_ + 1)
    assert(store.undo())
    store.dispatch(_ + 10)
    assert(store.peek == 11)
    assert(!store.canRedo)
    assert(!store.redo())

  test("without history, undo and redo are no-ops that report nothing happened"):
    val store = Store(Counter(0, "a"))
    store.dispatch(c => c.copy(count = 1))
    assert(!store.canUndo)
    assert(!store.undo())
    assert(store.peek == Counter(1, "a"))

  test("select caches its projection and recomputes only when the state changed"):
    var runs  = 0
    val store = Store(Counter(0, "a"), history = 2)
    val label = store.select { state =>
      runs += 1
      state.label.toUpperCase
    }
    assert(label.peek == "A")
    assert(label.peek == "A")
    assert(runs == 1)
    store.dispatch(identity) // an action that changes nothing notifies nobody
    assert(label.peek == "A")
    assert(runs == 1)
    store.dispatch(_.copy(label = "b"))
    assert(label.peek == "B")
    assert(runs == 2)
    assert(store.undo())
    assert(label.peek == "A")
    assert(runs == 3)

  test("an action that changes nothing records no history entry"):
    val store = Store(1, history = 3)
    store.dispatch(identity)
    assert(!store.canUndo)

  test("the state is readable through a tracking scope and the untracked peek alike"):
    val store       = Store("x")
    var invalidated = 0
    val scope       = ReactiveScope.onInvalidation(() => invalidated += 1)
    assert(store.get(using scope) == "x")
    store.dispatch(_ + "y")
    assert(invalidated == 1)
    assert(store.peek == "xy")
    assert(store.state.peek == "xy")

  test("rejected off-thread dispatch, undo and redo leave the history unchanged"):
    val loop                          = RenderThread.register(Thread.currentThread(), () => ())
    val store                         = Store(0, history = 5)
    def rejected(body: => Unit): Unit =
      val failure = new java.util.concurrent.atomic.AtomicReference[Throwable]()
      val worker  = Thread(() =>
        try body
        catch case error: IllegalStateException => failure.set(error)
      )
      worker.start()
      worker.join(10000)
      assert(!worker.isAlive)
      assert(failure.get() match
        case _: IllegalStateException => true
        case _                        => false)
    try
      store.dispatch(_ + 1)
      val nested = RenderThread.register(Thread.currentThread(), () => ())
      try
        intercept[IllegalStateException](store.dispatch(_ + 1))
        intercept[IllegalStateException](store.undo())
        assert(store.peek == 1 && store.canUndo && !store.canRedo)
      finally
        nested.close()
        RenderThread.unregister()
      rejected(store.dispatch(_ + 1))
      assert(store.peek == 1)
      assert(store.undo())
      assert(store.peek == 0)
      assert(!store.canUndo)
      rejected { val _ = store.redo() }
      assert(!store.canUndo && store.canRedo)
      assert(store.redo())
      rejected { val _ = store.undo() }
      assert(store.peek == 1 && store.canUndo && !store.canRedo)
      assert(store.undo())
      assert(store.peek == 0 && !store.canUndo && store.canRedo)
    finally
      loop.close()
      RenderThread.unregister()
