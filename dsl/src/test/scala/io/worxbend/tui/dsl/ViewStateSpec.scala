package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.Signal
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** Coverage for `useSignal` / `useState` / `keyed`: state that belongs to a place in the view rather than to the app.
  *
  * Everything here drives a real app through `Pilot`, because the whole feature is about what survives from one frame
  * to the next — a single evaluation could not tell a working slot from one recreated every time.
  */
final class ViewStateSpec extends AnyFunSuite with PilotFixture:

  /** A reusable piece of view that owns its own counter, written the way a library author would write it: no state
    * parameter, nothing for the caller to declare.
    */
  private def counter(label: String)(using ReactiveScope): Element =
    val count = useSignal(0)
    text(s"$label=${count.get}").focusable.onKeyEvent {
      case KeyEvent(KeyCode.Char('+'), _) =>
        count.update(_ + 1)
        true
      case _                              => false
    }

  test("a hook signal survives redraws instead of being recreated every frame"):
    val backend = HeadlessBackend(Size(20, 3))
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element = counter("n")
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    assert(pilot.screenLines.head.startsWith("n=0"))
    // each press repaints, and a slot recreated per frame would read 0 again every time
    pilot.typeText("+").waitForIdle()
    assert(pilot.screenLines.head.startsWith("n=1"))
    pilot.typeText("+").waitForIdle()
    assert(pilot.screenLines.head.startsWith("n=2"))
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("two calls of the same component keep separate state"):
    val backend = HeadlessBackend(Size(30, 4))
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        column(counter("left"), counter("right"))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    assert(pilot.screenLines.head.startsWith("left=0"))
    assert(pilot.screenLines(1).startsWith("right=0"))
    pilot.typeText("+").waitForIdle() // the first counter has focus, so only it moves
    assert(pilot.screenLines.head.startsWith("left=1"))
    assert(pilot.screenLines(1).startsWith("right=0"))
    pilot.interrupt()
    val _ = pilot.awaitTermination()

  test("keyed siblings keep their own state when a neighbour is removed"):
    val backend = HeadlessBackend(Size(30, 5))
    val items   = Signal(Vector("a", "b", "c"))
    val app     = new TuiApp:
      override def bindings: KeyBindings            =
        KeyBindings(binding("d", "drop the middle item")(items.update(_.filterNot(_ == "b"))))
      def view(using ReactiveScope, Theme): Element =
        column(items.get.map(id => keyed(id)(counter(id)))*)
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    // give each of the three a different value, walking focus along with Tab
    pilot.typeText("+").waitForIdle()
    pilot.press("tab").waitForIdle()
    pilot.typeText("++").waitForIdle()
    pilot.press("tab").waitForIdle()
    pilot.typeText("+++").waitForIdle()
    assert(pilot.screenLines.take(3).map(_.trim) == Seq("a=1", "b=2", "c=3"))
    pilot.press("d").waitForIdle()
    // without `keyed` the survivors would be renumbered and `c` would inherit `b`'s slot, reading 2
    assert(pilot.screenLines.take(2).map(_.trim) == Seq("a=1", "c=3"))
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("a hidden subtree's state is swept and starts fresh when it comes back"):
    val backend = HeadlessBackend(Size(30, 4))
    val shown   = Signal(true)
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("t", "toggle")(shown.update(!_)))
      def view(using ReactiveScope, Theme): Element =
        if shown.get then counter("only") else text("hidden")
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.typeText("+").waitForIdle()
    assert(pilot.screenLines.head.startsWith("only=1"))
    pilot.press("t").waitForIdle()
    assert(pilot.screenLines.head.startsWith("hidden"))
    pilot.press("t").waitForIdle()
    assert(pilot.screenLines.head.startsWith("only=0")) // the slot was released while the branch was gone
    pilot.interrupt()
    val _ = pilot.awaitTermination()

  test("a hook called outside a view evaluation fails with a message naming the alternative"):
    val failure = intercept[IllegalStateException](useSignal(0))
    assert(failure.getMessage.contains("Signal field"))
    assert(failure.getMessage.contains("render thread"))

  test("sweeping releases the slots a generation did not reach"):
    val store = ViewState()
    store.beginGeneration()
    ViewState.during(store) {
      val _ = useSignal(1)
      val _ = keyed("k")(useSignal(2))
    }
    store.sweep()
    assert(store.slotCount == 2)
    store.beginGeneration()
    ViewState.during(store)(useSignal(1))
    store.sweep()
    assert(store.slotCount == 1) // the keyed slot was not reached this time

  test("the same key under two different parents is two different places"):
    val store  = ViewState()
    store.beginGeneration()
    val values = ViewState.during(store) {
      Seq(keyed("outerA")(keyed("shared")(useSignal(1))), keyed("outerB")(keyed("shared")(useSignal(2))))
    }
    store.sweep()
    assert(store.slotCount == 2)
    assert(values.head.peek == 1)
    assert(values(1).peek == 2)

  test("an empty key is still a scope of its own, not the root"):
    val store  = ViewState()
    store.beginGeneration()
    // both hooks are the first call in their own scope, so both used to be numbered "#0": the root path is the empty
    // string and so is a path of one empty key. The second call then read back the first call's Signal[String].
    val values = ViewState.during(store)(Seq(useSignal("a"), keyed("")(useSignal(0))))
    store.sweep()
    assert(store.slotCount == 2)
    assert(values.head.peek == "a")
    assert(values(1).peek == 0)

  test("a key containing the path separator is not the two keys it looks like"):
    val store  = ViewState()
    store.beginGeneration()
    // "a/b" as one key and "a" containing "b" both used to spell the path "a/b", so the second call read back the
    // first one's slot
    val values = ViewState.during(store)(Seq(keyed("a/b")(useSignal("one")), keyed("a")(keyed("b")(useSignal(2)))))
    store.sweep()
    assert(store.slotCount == 2)
    assert(values.head.peek == "one")
    assert(values(1).peek == 2)
