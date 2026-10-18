package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

final class GenerationalScopeDisposalSpec extends AnyFunSuite:
  test("disposal releases the union of both generations, is idempotent and prevents reuse"):
    var invalidations = 0
    val scope         = ReactiveScope.generational(() => invalidations += 1)
    val old           = Signal(0)
    val current       = Signal(0)
    val shared        = Signal(0)
    val _             = old.get(using scope)
    val _             = shared.get(using scope)
    scope.beginGeneration()
    val _             = current.get(using scope)
    val _             = shared.get(using scope)
    assert(List(old, current, shared).map(_.subscriberCount) == List(1, 1, 1))
    scope.dispose()
    scope.dispose()
    assert(List(old, current, shared).map(_.subscriberCount) == List(0, 0, 0))
    old.set(1)
    current.set(1)
    shared.set(1)
    assert(invalidations == 0)
    intercept[IllegalStateException](old.get(using scope))
    intercept[IllegalStateException](scope.beginGeneration())
    assert(old.subscriberCount == 0)

  test(
    "a map-based per-frame derivation leaves the source's subscriber count stable and does not invalidate the scope"
  ):
    // the pattern the `Computed.dispose` Scaladoc points a `view` body at: a `Derived` holds no edge of its own, so
    // creating one per frame subscribes the scope to the source and nothing else
    var invalidations = 0
    val scope         = ReactiveScope.generational(() => invalidations += 1)
    val source        = Signal(1)
    (1 to 3).foreach { frame =>
      scope.beginGeneration()
      assert(source.map(_ + 1).get(using scope) == 2)
      assert(source.subscriberCount == 1, s"frame $frame grew the source's subscriber set")
      assert(invalidations == 0, s"frame $frame invalidated the scope that drew it")
    }
    // and the scope is still subscribed to the source, so a real change reaches it exactly once
    source.set(5)
    assert(invalidations == 1)

  test("disposing a long-lived computed from its owner invalidates dependents exactly once"):
    // the one place `dispose()` belongs: the owner of a computed created *outside* `view` is going away
    var invalidations = 0
    val scope         = ReactiveScope.generational(() => invalidations += 1)
    val source        = Signal(1)
    val derived       = Computed(source.get + 1)
    scope.beginGeneration()
    assert(derived.get(using scope) == 2)
    assert(source.subscriberCount == 1, "the computed, not the scope, subscribes to the source")
    assert(derived.subscriberCount == 1)
    derived.dispose()
    assert(invalidations == 1, "a dependent must learn it has to re-derive")
    assert(source.subscriberCount == 0)
    assert(derived.subscriberCount == 0)
    derived.dispose()
    assert(invalidations == 1, "a second dispose has nobody left to tell")
    // the documented consequence: until the dependent re-reads, it is subscribed to nothing — which is why this is an
    // owner-exit operation and never something a `view` body does to a computed it just read
    source.set(5)
    assert(invalidations == 1)
