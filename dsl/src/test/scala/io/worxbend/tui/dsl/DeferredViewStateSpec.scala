package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.Signal

import org.scalatest.funsuite.AnyFunSuite

final class DeferredViewStateSpec extends AnyFunSuite:
  test("deferred keyed hooks follow items through reorder, removal and reappearance"):
    val store                         = ViewState()
    var seen                          = Map.empty[String, Signal[String]]
    def frame(ids: Seq[String]): Unit =
      store.beginGeneration()
      seen = Map.empty
      ViewState.during(store) {
        val tree = Element.column(ids.map { id =>
          keyed(id)(Element.responsive { _ =>
            val state = useSignal(id)
            seen += id -> state
            Element.text(state.peek)
          })
        }*)
        val _    = ResponsivePass.resolve(tree, Size(80, 24))
      }
      store.sweep()
    frame(Seq("a", "b", "c"))
    val original                      = seen
    original.foreach((id, state) => state.set(s"edited-$id"))
    frame(Seq("c", "a", "b"))
    assert(seen.forall((id, state) => state eq original(id)))
    frame(Seq("b", "c"))
    assert(seen("b").peek == "edited-b")
    assert(seen("c").peek == "edited-c")
    assert(store.slotCount == 2)
    frame(Seq("a", "b", "c"))
    assert(seen("a").peek == "a")
    assert(seen("b") eq original("b"))

  test("repeated occurrences of the same deferred element have separate positional hooks"):
    val store         = ViewState()
    var seen          = Vector.empty[Signal[Int]]
    def frame(): Unit =
      store.beginGeneration()
      seen = Vector.empty
      ViewState.during(store) {
        val shared = Element.responsive { _ =>
          val state = useSignal(0)
          seen :+= state
          Element.text(state.peek.toString)
        }
        val _      = ResponsivePass.resolve(Element.column(shared, shared), Size(80, 24))
      }
      store.sweep()
    frame()
    assert(!(seen.head eq seen(1)))
    seen.head.set(1)
    seen(1).set(2)
    frame()
    assert(seen.map(_.peek) == Vector(1, 2))

  test("nested deferred scopes preserve eager neighbours and user keys cannot collide with internal paths"):
    val store                         = ViewState()
    var seen                          = Map.empty[String, Signal[String]]
    def hook(label: String): Element  =
      val state = useSignal(label)
      seen += label -> state
      Element.text(state.peek)
    def frame(ids: Seq[String]): Unit =
      store.beginGeneration()
      seen = Map.empty
      ViewState.during(store) {
        val tree = Element.column(
          hook("before"),
          Element.column(ids.map { id =>
            keyed(id) {
              Element.column(
                hook(s"$id-eager"),
                Element
                  .responsive { _ =>
                    Element.column(
                      hook(s"$id-outer"),
                      Element.responsive(_ => keyed("shared")(hook(s"$id-inner"))),
                      hook(s"$id-after"),
                    )
                  }
                  .length(3),
                keyed("deferred:1")(keyed("occurrence:0")(hook(s"$id-user"))),
              )
            }
          }*),
          hook("after"),
        )
        val _    = ResponsivePass.resolve(tree, Size(80, 24))
        val _    = hook("after-resolution")
      }
      store.sweep()
    frame(Seq("a", "b"))
    val original                      = seen
    original.foreach((id, state) => state.set(s"edited-$id"))
    frame(Seq("b", "a"))
    assert(seen.keySet == original.keySet)
    assert(seen.forall((id, state) => (state eq original(id)) && state.peek == s"edited-$id"))
    assert(store.slotCount == seen.size)

  test("unkeyed deferred siblings follow construction position rather than resolution order"):
    val store                         = ViewState()
    var seen                          = Map.empty[String, Signal[String]]
    def frame(reverse: Boolean): Unit =
      store.beginGeneration()
      seen = Map.empty
      ViewState.during(store) {
        val nodes = Seq("a", "b").map { id =>
          Element.responsive { _ =>
            val state = useSignal(id)
            seen += id -> state
            Element.text(state.peek)
          }
        }
        val _     = ResponsivePass.resolve(Element.column((if reverse then nodes.reverse else nodes)*), Size(80, 24))
      }
      store.sweep()
    frame(false)
    val original                      = seen
    frame(true)
    assert(seen.forall((id, state) => state eq original(id)))

  test("direct responsive construction captures keys and copying props preserves that identity"):
    val store                         = ViewState()
    var seen                          = Map.empty[String, Signal[String]]
    def frame(ids: Seq[String]): Unit =
      store.beginGeneration()
      seen = Map.empty
      ViewState.during(store) {
        val nodes = ids.map { id =>
          keyed(id) {
            ResponsiveElement { _ =>
              val state = useSignal(id)
              seen += id -> state
              Element.text(state.peek)
            }.copy(props = ElementProps())
          }
        }
        val _     = ResponsivePass.resolve(Element.column(nodes*), Size(80, 24))
      }
      store.sweep()
    frame(Seq("a", "b"))
    val original                      = seen
    frame(Seq("b", "a"))
    assert(seen.forall((id, state) => state eq original(id)))

  test("a failing delayed builder restores the surrounding hook position"):
    val store                                   = ViewState()
    def frame(resolve: Boolean): Signal[String] =
      store.beginGeneration()
      val result = ViewState.during(store) {
        val node = keyed("child") {
          Element.responsive { _ =>
            val _ = useSignal(42)
            throw new IllegalArgumentException("builder failed") // scalafix:ok DisableSyntax; exercises restoration
          }
        }
        if resolve then
          val failure = intercept[IllegalArgumentException](ResponsivePass.resolve(node, Size(80, 24)))
          assert(failure.getMessage == "builder failed")
        useSignal("after")
      }
      store.sweep()
      result
    val after                                   = frame(true)
    assert(after eq frame(false))
