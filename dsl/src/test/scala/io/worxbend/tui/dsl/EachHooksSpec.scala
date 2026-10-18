package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.Signal
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** `each` used to stamp only the *focus* key; hook state inside the row still needed a separate `keyed`, and the two
  * identities could disagree. Now `each` scopes the row's hooks under the same key, so a row's `useState` follows the
  * item through a reorder with no `keyed` in sight.
  */
final class EachHooksSpec extends AnyFunSuite with PilotFixture:

  /** Caller-owned mutable state of the kind `useState` is for: not reactive, mutated in the key handler, repainted by
    * the handler's `true`.
    */
  private final class Tally:
    var count: Int = 0

  private def row(id: String): Element =
    val tally = useState(Tally())
    text(s"$id=${tally.count}").focusable.onKeyEvent {
      case KeyEvent(KeyCode.Char('+'), _) =>
        tally.count += 1
        true
      case _                              => false
    }

  test("state declared with useState inside an each row follows the item when the list is reordered"):
    val backend = HeadlessBackend(Size(30, 5))
    val items   = Signal(Vector("a", "b", "c"))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("r", "reverse")(items.update(_.reverse)))
      def view(using ReactiveScope, Theme): Element = column(each(items.get)(identity)(row)*)
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.typeText("+").waitForIdle()
    pilot.press("tab").waitForIdle()
    pilot.typeText("++").waitForIdle()
    pilot.press("tab").waitForIdle()
    pilot.typeText("+++").waitForIdle()
    assert(pilot.screenLines.take(3).map(_.trim) == Seq("a=1", "b=2", "c=3"))
    pilot.press("r").waitForIdle()
    // positional slots would read c=1, b=2, a=3 here: the values stayed on the rows, the names moved
    assert(pilot.screenLines.take(3).map(_.trim) == Seq("c=3", "b=2", "a=1"))
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("the prefixed form scopes hooks under the prefixed key, so two lists over the same ids stay apart"):
    val store = ViewState()
    store.beginGeneration()
    val built = ViewState.during(store) {
      each(Seq("x", "y"), "left")(identity)(id => { val _ = useSignal(0); text(id) }) ++
        each(Seq("x", "y"), "right")(identity)(id => { val _ = useSignal(0); text(id) })
    }
    store.sweep()
    assert(built.size == 4)
    assert(store.slotCount == 4, "every row owns a slot of its own")

  test("each still builds plain elements with no view being evaluated"):
    val rows = each(Seq(1, 2))(_.toString)(n => text(n.toString))
    assert(rows.flatMap(_.props.focusKey) == Seq("1", "2"))
