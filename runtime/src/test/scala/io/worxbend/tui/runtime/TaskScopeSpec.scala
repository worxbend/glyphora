package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

final class TaskScopeSpec extends AnyFunSuite:
  test("closing a task scope gates results already queued on its owner"):
    val queued = CountDownLatch(1)
    val loop   = RenderThread.register(Thread.currentThread(), () => queued.countDown())
    try
      val scope     = Async.scope()
      val delivered = AtomicInteger(0)
      scope.runCatching(42)(_ => { val _ = delivered.incrementAndGet() })
      assert(queued.await(3, TimeUnit.SECONDS))
      scope.close()
      scope.close()
      RenderThread.drainPending(loop)
      assert(delivered.get() == 0)
      assertThrows[IllegalStateException](scope.runCatching(7)(_ => ()))
    finally
      loop.close()
      RenderThread.unregister()

  test("individual cancellation gates queued timers without closing siblings"):
    val queued = CountDownLatch(1)
    val loop   = RenderThread.register(Thread.currentThread(), () => queued.countDown())
    try
      val scope     = Async.scope()
      val delivered = AtomicInteger(0)
      val timer     = scope.after(1.millis) { val _ = delivered.incrementAndGet() }
      assert(queued.await(3, TimeUnit.SECONDS))
      timer.cancel()
      RenderThread.drainPending(loop)
      assert(delivered.get() == 0)
      val next      = CountDownLatch(1)
      scope.runCatching(1)(_ => next.countDown())
      val deadline  = System.nanoTime() + 3.seconds.toNanos
      while next.getCount > 0 && System.nanoTime() < deadline do
        RenderThread.drainPending(loop)
        Thread.`yield`()
      assert(next.getCount == 0)
      scope.close()
    finally
      loop.close()
      RenderThread.unregister()

  test("owner retirement closes scopes and discards a running worker's late result"):
    val entered   = CountDownLatch(1)
    val release   = CountDownLatch(1)
    val finished  = CountDownLatch(1)
    val delivered = AtomicInteger(0)
    val loop      = RenderThread.register(Thread.currentThread())
    val scope     = Async.scope()
    try
      scope.runCatching {
        entered.countDown()
        assert(release.await(3, TimeUnit.SECONDS))
        finished.countDown()
        42
      }(_ => { val _ = delivered.incrementAndGet() })
      assert(entered.await(3, TimeUnit.SECONDS))
      loop.close()
      assertThrows[IllegalStateException](scope.every(1.millis)(()))
      release.countDown()
      assert(finished.await(3, TimeUnit.SECONDS))
      RenderThread.drainPending(loop)
      assert(delivered.get() == 0)
    finally
      release.countDown()
      scope.close()
      loop.close()
      RenderThread.unregister()

  test("scoped failures are delivered as Left and repeat cancellation gates queued ticks"):
    val queued = CountDownLatch(1)
    val loop   = RenderThread.register(Thread.currentThread(), () => queued.countDown())
    val scope  = Async.scope()
    try
      val failure    = IllegalStateException("failed IO")
      var seen       = Option.empty[Either[Throwable, Int]]
      scope.runCatching[Int](throw failure)(result => seen = Some(result))
      assert(queued.await(3, TimeUnit.SECONDS))
      RenderThread.drainPending(loop)
      assert(seen.contains(Left(failure)))
      val ticks      = AtomicInteger(0)
      val tickQueued = CountDownLatch(1)
      val inner      = RenderThread.register(Thread.currentThread(), () => tickQueued.countDown())
      try
        val timers = Async.scope()
        timers.every(1.millis) { val _ = ticks.incrementAndGet() }
        assert(tickQueued.await(3, TimeUnit.SECONDS))
        timers.close()
        RenderThread.drainPending(inner)
        assert(ticks.get() == 0)
      finally
        inner.close()
        RenderThread.unregister()
    finally
      scope.close()
      loop.close()
      RenderThread.unregister()

  test("close does not wait for an already admitted callback"):
    val queued  = CountDownLatch(1)
    val entered = CountDownLatch(1)
    val closed  = CountDownLatch(1)
    val loop    = RenderThread.register(Thread.currentThread(), () => queued.countDown())
    val scope   = Async.scope()
    val closer  = new Thread(() => {
      if entered.await(3, TimeUnit.SECONDS) then scope.close()
      closed.countDown()
    })
    try
      var completed = false
      scope.runCatching(1) { _ =>
        entered.countDown()
        assert(closed.await(3, TimeUnit.SECONDS))
        completed = true
      }
      assert(queued.await(3, TimeUnit.SECONDS))
      closer.start()
      RenderThread.drainPending(loop)
      assert(completed)
      closer.join(3000)
      assert(!closer.isAlive)
    finally
      entered.countDown()
      scope.close()
      loop.close()
      RenderThread.unregister()

  test("scope construction needs a registered owner"):
    assertThrows[IllegalStateException](Async.scope())
