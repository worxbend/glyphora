package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

final class KeyedEffectsSpec extends AnyFunSuite:

  test("starting a keyed effect twice within its duration leaves one active effect"):
    var nanos = 0L
    val stack = EffectStack(() => nanos)
    stack.start("flash", Effect.fadeIn(200.millis))
    nanos = 50.millis.toNanos
    stack.start("flash", Effect.fadeIn(200.millis))
    assert(stack.activeCount == 1)

  test("the replacement is timed from its own start, not the one it replaced"):
    var nanos = 0L
    val stack = EffectStack(() => nanos)
    stack.start("flash", Effect.fadeIn(200.millis))
    nanos = 150.millis.toNanos
    stack.start("flash", Effect.fadeIn(200.millis))
    nanos = 250.millis.toNanos // the first would be done by now; the second has 100 ms to go
    assert(!stack.prune())
    assert(stack.activeCount == 1)
    nanos = 350.millis.toNanos
    assert(stack.prune())
    assert(stack.isEmpty)

  test("different keys and unkeyed effects run side by side"):
    val stack = EffectStack(() => 0L)
    stack.start("flash", Effect.fadeIn(200.millis))
    stack.start("shake", Effect.fadeIn(200.millis))
    stack.start(Effect.fadeIn(200.millis))
    stack.start(Effect.fadeIn(200.millis))
    assert(stack.activeCount == 4)

  test("services without a running app accept a keyed effect and do nothing"):
    AppServices.NoOp.runEffect("flash", Effect.fadeIn(200.millis))
