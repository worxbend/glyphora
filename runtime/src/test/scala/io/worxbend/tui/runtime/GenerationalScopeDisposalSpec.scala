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
