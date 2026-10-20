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

  test("a row that also wraps itself in keyed(k) shares the scope each opened, so it keeps keyed(k)'s state"):
    val store                               = ViewState()
    val seen                                = scala.collection.mutable.ArrayBuffer.empty[Signal[Int]]
    def row(id: String): Element            =
      seen += useSignal(0)
      text(id)
    def frame(build: => Seq[Element]): Unit =
      store.beginGeneration()
      val _ = ViewState.during(store)(build)
      store.sweep()
    // the 0.19 spelling: a keyed scope per row, written by hand
    frame(Seq("a", "b").map(id => keyed(id)(row(id))))
    seen.foreach(_.set(7))
    seen.clear()
    // both at once, as code written for 0.19 reads after the upgrade: one scope, not `a/a`, so nothing resets
    frame(each(Seq("a", "b"))(identity)(id => keyed(id)(row(id))))
    assert(seen.map(_.peek) == Seq(7, 7))
    assert(store.slotCount == 2)

  test("nested each rows with colliding parent keys retain state without shifting later outer hooks"):
    val store                                     = ViewState()
    def frame(ids: Seq[String]): Seq[Signal[Int]] =
      store.beginGeneration()
      val seen = scala.collection.mutable.ArrayBuffer.empty[Signal[Int]]
      val _    = ViewState.during(store) {
        each(Seq("1"))(identity) { _ =>
          val children = each(ids)(identity) { id =>
            seen += useSignal(0)
            val second = useSignal(0)
            text(s"$id=${second.peek}")
          }
          val outer    = useSignal(false)
          assert(!outer.peek)
          column(children*)
        }
      }
      store.sweep()
      seen.toSeq
    frame(Seq("1", "2")).foreach(_.set(7))
    assert(frame(Seq("2", "1")).map(_.peek) == Seq(7, 7))
    assert(frame(Seq("0")).map(_.peek) == Seq(0))

  test("BUGS-1 replacing a colliding nested key never aliases a later Boolean hook with an Int hook"):
    val store                              = ViewState()
    def frame(id: String): Signal[Boolean] =
      store.beginGeneration()
      var result = Option.empty[Signal[Boolean]]
      val _      = ViewState.during(store) {
        each(Seq("1"))(identity) { _ =>
          val children = each(Seq(id))(identity) { child =>
            val first  = useSignal(0)
            val second = useSignal(0)
            text(s"$child=${first.peek + second.peek}")
          }
          val outer    = useSignal(false)
          result = Some(outer)
          column(children*)
        }
      }
      store.sweep()
      result.getOrElse(fail("no outer hook"))
    val original                           = frame("1")
    original.set(true)
    val after                              = frame("0")
    assert(after.peek)
    assert(after eq original)

  test("BUGS-1 adding and removing nested keyed rows preserves the later outer hook"):
    val store                                    = ViewState()
    def frame(ids: Seq[String]): Signal[Boolean] =
      store.beginGeneration()
      var result = Option.empty[Signal[Boolean]]
      val _      = ViewState.during(store) {
        each(Seq("1"))(identity) { _ =>
          val children = each(ids)(identity)(id => { val _ = useSignal(0); text(id) })
          result = Some(useSignal(false))
          column(children*)
        }
      }
      store.sweep()
      result.getOrElse(fail("no outer hook"))
    val original                                 = frame(Seq("1"))
    original.set(true)
    assert(frame(Seq("0", "1")).peek)
    assert(frame(Seq.empty).peek)

  for delayed <- Seq(false, true) do
    test(s"BUGS-4 focus follows controls beneath nonfocusable each roots, responsive=$delayed"):
      val backend = HeadlessBackend(Size(30, 5))
      val items   = Signal(Vector("a", "b", "c"))
      val app     = new TuiApp:
        override def bindings: KeyBindings            =
          KeyBindings(binding("ctrl+r", "rotate")(items.update(v => v.last +: v.init)))
        def view(using ReactiveScope, Theme): Element =
          column(each(items.get)(identity) { id =>
            val state = useState(TextInputState())
            if delayed then responsive(_ => Element.row(text(id).length(2), input(state)))
            else Element.row(text(id).length(2), input(state))
          }*)
      val pilot   = startPilot(backend)(app.runWith(backend))
      pilot.waitForIdle().press("tab").typeText("X").waitForIdle()
      pilot.press("ctrl+r").waitForIdle().typeText("Y").waitForIdle()
      assert(pilot.screenLines.map(_.trim).filter(_.nonEmpty) == Seq("c", "a", "b XY"))

  test("each still builds plain elements with no view being evaluated"):
    val rows = each(Seq(1, 2))(_.toString)(n => text(n.toString))
    assert(rows.flatMap(_.props.focusKey) == Seq("1", "2"))
