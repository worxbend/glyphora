package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

final class LayerIdentitySpec extends AnyFunSuite:
  private val keys = Vector(Some("first"), Some("second"), Some("third"))

  test("entry snapshots distinguish repeated pushes, same-instance replace and reset at equal depth"):
    val stack  = ScreenStack()
    val screen = Screen(text("same"))
    stack.push(screen)
    val first  = stack.entriesNow.head._1
    stack.push(screen)
    assert(stack.entriesNow.map(_._1).distinct.size == 2)
    assert(stack.entriesNow.head._1 == first)
    val before = stack.entriesNow.map(_._1)
    stack.replace(screen)
    assert(stack.entriesNow.head._1 == first)
    assert(stack.entriesNow.last._1 != before.last)
    stack.reset()
    stack.push(screen)
    stack.push(screen)
    assert(stack.entriesNow.map(_._1).toSet.intersect(before.toSet).isEmpty)

  test("changing ancestry beneath an open palette discards outgoing anchors and restores the base"):
    val tracker   = FocusTracker()
    tracker.reconcile(keys, None)
    tracker.focusTo(2)
    val screen    = LayerSnapshot(Vector(LayerIdentity.Screen(1)))
    screen.reconcile(LayerSnapshot.Empty, tracker)
    tracker.reconcile(keys, None)
    tracker.focusTo(1)
    val palette   = LayerSnapshot(screen.ids :+ LayerIdentity.Palette(1))
    palette.reconcile(screen, tracker)
    tracker.reconcile(keys, None)
    tracker.focusTo(2)
    val replaced  = LayerSnapshot(Vector(LayerIdentity.Screen(2), LayerIdentity.Palette(1)))
    replaced.reconcile(palette, tracker)
    tracker.reconcile(keys, None)
    assert(tracker.focusedIndex == 0)
    val uncovered = LayerSnapshot(Vector(LayerIdentity.Screen(2)))
    uncovered.reconcile(replaced, tracker)
    tracker.reconcile(keys, None)
    assert(tracker.focusedIndex == 0)
    LayerSnapshot.Empty.reconcile(uncovered, tracker)
    tracker.reconcile(keys, None)
    assert(tracker.focusedIndex == 2)

  test("palette activations distinguish close and reopen between frames"):
    val palette = CommandPalette(() => KeyBindings())
    palette.open()
    val first   = palette.activationNow
    palette.close()
    palette.open()
    assert(first != palette.activationNow)

  test("screen-local keys and differently typed slots cannot collide with each other or user keys"):
    val state                             = ViewState()
    def evaluate(): (Object, String, Int) =
      state.beginGeneration()
      val result = ViewState.during(state) {
        val first  = state.screen(1)(keyed("same")(useState(new Object)))
        val second = state.screen(2)(keyed("same")(useState("two")))
        val user   = keyed("screen:1")(keyed("same")(useState(3)))
        (first, second, user)
      }
      state.sweep()
      result
    val first                             = evaluate()
    val next                              = evaluate()
    assert(first._1 eq next._1)
    assert(next._2 == "two")
    assert(next._3 == 3)
