package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

/** `memo(deps*)(subtree)` keeps a built subtree in a `ViewState` slot and hands the same instance back on every frame
  * whose deps are `==` to the ones it was built with. Driven through a bare store rather than a `Pilot`, because the
  * contract is about identity across generations and a store makes each generation explicit.
  */
final class MemoSpec extends AnyFunSuite:

  private def frame[A](store: ViewState)(body: => A): A =
    store.beginGeneration()
    val result = ViewState.during(store)(body)
    store.sweep()
    result

  test("the same Element instance comes back across frames while the deps are equal"):
    val store                   = ViewState()
    var builds                  = 0
    def view(dep: Int): Element = memo(dep) {
      builds += 1
      text(s"built for $dep")
    }
    val first                   = frame(store)(view(1))
    val second                  = frame(store)(view(1))
    assert(first eq second)
    assert(builds == 1)

  test("a changed dep rebuilds, and the rebuilt tree is what later frames return"):
    val store                      = ViewState()
    var builds                     = 0
    def view(dep: String): Element = memo(dep) {
      builds += 1
      text(dep)
    }
    val first                      = frame(store)(view("a"))
    val second                     = frame(store)(view("b"))
    assert(first ne second)
    assert(builds == 2)
    val third                      = frame(store)(view("b"))
    assert(third eq second)
    assert(builds == 2)

  test("several deps are compared element by element, and a case-class dep compares structurally"):
    final case class Row(id: Int, label: String)
    val store                                        = ViewState()
    var builds                                       = 0
    def view(rows: Vector[Row], width: Int): Element = memo(rows, width) {
      builds += 1
      text(s"${rows.size}@$width")
    }
    val first                                        = frame(store)(view(Vector(Row(1, "a")), 80))
    val second                                       = frame(store)(view(Vector(Row(1, "a")), 80))
    assert(first eq second)
    assert(frame(store)(view(Vector(Row(1, "a")), 40)) ne first)
    assert(builds == 2)

  test("two memo sites in one view are independent slots"):
    val store    = ViewState()
    val (a, b)   = frame(store)((memo(1)(text("a")), memo(1)(text("b"))))
    val (a2, b2) = frame(store)((memo(1)(text("a")), memo(1)(text("b"))))
    assert(a eq a2)
    assert(b eq b2)
    assert(a ne b)
    assert(store.slotCount == 2)

  test("a memo under each is scoped by the row key, so rows keep their own cache through a reorder"):
    val store                    = ViewState()
    // `each` re-keys the row it is handed, so the identity to compare is what `memo` itself returned
    val seen                     = scala.collection.mutable.Map.empty[String, List[Element]]
    def row(id: String): Element =
      val built = memo(id)(text(id))
      seen(id) = built :: seen.getOrElse(id, Nil)
      built
    val _                        = frame(store)(each(Seq("x", "y"))(identity)(row))
    val _                        = frame(store)(each(Seq("y", "x"))(identity)(row))
    Seq("x", "y").foreach { id =>
      seen(id) match
        case List(second, first) => assert(first eq second, s"row $id was rebuilt after moving")
        case other               => fail(s"expected two frames for $id, saw ${other.size}")
    }

  test("with no view being evaluated, memo simply builds the subtree"):
    var builds = 0
    val built  = memo(1) {
      builds += 1
      text("plain")
    }
    assert(builds == 1)
    assert(built.widget != null) // scalafix:ok DisableSyntax; the point is that a real element came back
