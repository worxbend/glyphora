package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

final class MemoRefreshSpec extends AnyFunSuite:
  private val entries: Seq[(String, (Int, () => String) => String)] = Seq(
    "useMemo"      -> ((dep, build) => useMemo(dep)(build())),
    "memoIfActive" -> ((dep, build) => ViewState.memoIfActive(Seq(dep))(build)),
  )

  entries.foreach { (name, memo) =>
    test(s"$name keeps the old value but commits changed deps before a throwing rebuild") {
      val store                                          = ViewState()
      var trace                                          = Vector.empty[Int]
      def frame(dep: Int, fail: Boolean = false): String =
        store.beginGeneration()
        ViewState.during(store) {
          memo(
            dep,
            () => {
              trace :+= dep
              if fail then throw new IllegalArgumentException("rebuild")
              s"value-$dep"
            },
          )
        }
      assert(frame(1) == "value-1")
      assert(frame(1, fail = true) == "value-1")
      assert(trace == Vector(1))
      assert(intercept[IllegalArgumentException](frame(2, fail = true)).getMessage == "rebuild")
      assert(frame(2, fail = true) == "value-1")
      assert(trace == Vector(1, 2))
      assert(frame(3) == "value-3")
      assert(trace == Vector(1, 2, 3))
      store.sweep()
      assert(store.slotCount == 1)
      store.beginGeneration()
      store.sweep()
      assert(store.slotCount == 0)
      assert(frame(3) == "value-3")
      assert(trace == Vector(1, 2, 3, 3))
    }

    test(s"$name retries a throwing initial build without retaining a cell") {
      val store                        = ViewState()
      var builds                       = 0
      def frame(fail: Boolean): String =
        store.beginGeneration()
        ViewState.during(store) {
          memo(
            1,
            () => {
              builds += 1
              if fail then throw new IllegalArgumentException("initial")
              "ready"
            },
          )
        }
      intercept[IllegalArgumentException](frame(true))
      assert(store.slotCount == 0)
      assert(frame(false) == "ready")
      assert(frame(true) == "ready")
      assert(builds == 2)
    }
  }

  test("useMemo rejects outside-view calls before evaluating its thunk; memoIfActive builds each time"):
    var builds          = 0
    intercept[IllegalStateException](useMemo(1) { builds += 1; "strict" })
    assert(builds == 0)
    def plain(): String = ViewState.memoIfActive(Seq(1))(() => { builds += 1; s"plain-$builds" })
    assert(plain() == "plain-1")
    assert(plain() == "plain-2")
