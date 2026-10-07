package io.worxbend.tui.runtime

import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration.DurationInt

import org.scalatest.funsuite.AnyFunSuite

final class AsyncCancellationSpec extends AnyFunSuite:
  test("after cancellation discards a callback already queued but not started"):
    val enqueued = CountDownLatch(1)
    val loop     = RenderThread.register(Thread.currentThread(), () => enqueued.countDown())
    var calls    = 0
    val timer    = Async.after(1.millis) { calls += 1 }
    try
      assert(enqueued.await(2, TimeUnit.SECONDS))
      timer.cancel()
      RenderThread.drainPending(loop)
      assert(calls == 0)
    finally
      timer.cancel()
      loop.close()
      RenderThread.unregister()

  test("every cancellation discards all queued ticks"):
    val enqueued = CountDownLatch(3)
    val loop     = RenderThread.register(Thread.currentThread(), () => enqueued.countDown())
    var calls    = 0
    val timer    = Async.every(1.millis) { calls += 1 }
    try
      assert(enqueued.await(2, TimeUnit.SECONDS))
      timer.cancel()
      timer.cancel()
      RenderThread.drainPending(loop)
      assert(calls == 0)
    finally
      timer.cancel()
      loop.close()
      RenderThread.unregister()

  test("cancelling one queued timer leaves another timer deliverable"):
    val enqueued  = CountDownLatch(2)
    val loop      = RenderThread.register(Thread.currentThread(), () => enqueued.countDown())
    var cancelled = 0
    var delivered = 0
    val first     = Async.after(1.millis) { cancelled += 1 }
    val second    = Async.after(1.millis) { delivered += 1 }
    try
      assert(enqueued.await(2, TimeUnit.SECONDS))
      first.cancel()
      RenderThread.drainPending(loop)
      assert(cancelled == 0)
      assert(delivered == 1)
    finally
      first.cancel()
      second.cancel()
      loop.close()
      RenderThread.unregister()
