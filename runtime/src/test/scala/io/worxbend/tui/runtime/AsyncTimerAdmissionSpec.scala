package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, ScheduledThreadPoolExecutor, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationInt

final class AsyncTimerAdmissionSpec extends AnyFunSuite:
  Seq(false, true).foreach { scoped =>
    val kind = if scoped then "scoped" else "unscoped"
    test(s"a $kind repeating timer retains its slot after a body failure and resumes delivery"):
      val queued       = new java.util.concurrent.LinkedBlockingQueue[Boolean]()
      val failures     = scala.collection.mutable.ArrayBuffer.empty[Throwable]
      val loop         = RenderThread.register(
        Thread.currentThread(),
        () => { val _ = queued.add(true) },
        error => { val _ = failures += error },
      )
      val scope        = if scoped then Some(Async.scope()) else None
      val failure      = IllegalStateException("timer body failed")
      var calls        = 0
      def body(): Unit =
        calls += 1
        if calls == 1 then throw failure
      val timer        = scope match
        case Some(group) => group.every(1.millis)(body())
        case None        => Async.every(1.millis)(body())
      try
        assert(queued.poll(3, TimeUnit.SECONDS))
        loop.drain(budget = 1)
        assert(calls == 1)
        assert(failures.toList == List(failure))
        assert(queued.poll(3, TimeUnit.SECONDS))
        (1 to 1023).foreach(_ => loop.enqueue(() => ()))
        assertThrows[java.util.concurrent.RejectedExecutionException](loop.enqueue(() => ()))
        loop.drain(budget = 1)
        assert(calls == 2)
        timer.cancel()
        while loop.hasPending do loop.drain()
        assert(failures.toList == List(failure))
        (1 to 1024).foreach(_ => loop.enqueue(() => ()))
        while loop.hasPending do loop.drain()
      finally
        timer.cancel()
        scope.foreach(_.close())
        RenderThread.unregister()
  }

  private def schedulerTurn(): Unit =
    val reached          = CountDownLatch(1)
    val marker: Runnable = () => reached.countDown()
    val _                = Async.scheduler.schedule(marker, 30L, TimeUnit.MILLISECONDS)
    assert(reached.await(3, TimeUnit.SECONDS))

  test("an unscoped repeating timer coalesces pending ticks and resumes after delivery"):
    val queued = CountDownLatch(1)
    val wakes  = AtomicInteger(0)
    val loop   = RenderThread.register(
      Thread.currentThread(),
      () => {
        val _ = wakes.incrementAndGet()
        queued.countDown()
      },
    )
    var calls  = 0
    val timer  = Async.every(1.millis) { calls += 1 }
    try
      assert(queued.await(3, TimeUnit.SECONDS))
      schedulerTurn()
      assert(wakes.get() == 1, "stalled owner accumulated timer callbacks")
      loop.drain(budget = 1)
      assert(calls == 1)
      schedulerTurn()
      assert(wakes.get() == 2)
      timer.cancel()
      loop.drain()
      assert(calls == 1)
      assert(!loop.hasPending)
    finally
      timer.cancel()
      RenderThread.unregister()

  test("a scoped repeating timer coalesces pending ticks and close removes its delivery"):
    val queued = CountDownLatch(1)
    val wakes  = AtomicInteger(0)
    val loop   = RenderThread.register(
      Thread.currentThread(),
      () => {
        val _ = wakes.incrementAndGet()
        queued.countDown()
      },
    )
    val scope  = Async.scope()
    var calls  = 0
    scope.every(1.millis) { calls += 1 }
    try
      assert(queued.await(3, TimeUnit.SECONDS))
      schedulerTurn()
      assert(wakes.get() == 1)
      loop.drain(budget = 1)
      assert(calls == 1)
      schedulerTurn()
      assert(wakes.get() == 2)
      scope.close()
      assert(!loop.hasPending)
      loop.drain()
      assert(calls == 1)
    finally
      scope.close()
      RenderThread.unregister()

  Seq(false, true).foreach { scoped =>
    val kind = if scoped then "scoped" else "unscoped"
    test(s"a $kind repeating timer coalesces ticks while its owner callback is blocked running"):
      val queued       = CountDownLatch(1)
      val wakes        = AtomicInteger(0)
      val caller       = Thread.currentThread()
      val loop         = RenderThread.register(
        caller,
        () => {
          val _ = wakes.incrementAndGet()
          queued.countDown()
        },
      )
      val scope        = if scoped then Some(Async.scope()) else None
      var calls        = 0
      def body(): Unit =
        assert(Thread.currentThread() eq caller)
        calls += 1
        // Wait on the same scheduler after several ticks are due, keeping this owner callback admitted throughout.
        schedulerTurn()
        assert(wakes.get() == 1, "ticks queued a second delivery while the first body was still running")
      val timer        = scope match
        case Some(group) => group.every(1.millis)(body())
        case None        => Async.every(1.millis)(body())
      try
        assert(queued.await(3, TimeUnit.SECONDS))
        loop.drain(budget = 1)
        assert(calls == 1)
        schedulerTurn()
        assert(wakes.get() == 2, "timer failed to resume once the admitted body completed")
        timer.cancel()
        assert(!loop.hasPending)
        loop.drain()
        assert(calls == 1)
      finally
        timer.cancel()
        scope.foreach(_.close())
        RenderThread.unregister()
  }

  test("cancelled scheduled entries are removed immediately without auto-cancelling unscoped timers at owner exit"):
    val scheduler = Async.scheduler match
      case executor: ScheduledThreadPoolExecutor => executor
      case _ => fail("scheduler must expose a remove-on-cancel executor rather than hide the retaining default")
    val baseline  = scheduler.getQueue.size()
    RenderThread.register(Thread.currentThread())
    val scope     = Async.scope()
    val oneShot   = Async.after(1.hour)(fail("long timer ran"))
    val repeating = Async.every(1.hour)(fail("long repeating timer ran"))
    scope.after(1.hour)(fail("long scoped timer ran"))
    scope.every(1.hour)(fail("long scoped repeating timer ran"))
    try
      assert(scheduler.getQueue.size() == baseline + 4)
      oneShot.cancel()
      assert(scheduler.getQueue.size() == baseline + 3, "cancelled one-shot retained until its deadline")
      scope.close()
      assert(scheduler.getQueue.size() == baseline + 1, "scope close retained cancelled scheduled entries")
      RenderThread.unregister()
      assert(scheduler.getQueue.size() == baseline + 1, "unscoped repeating lifetime changed at owner exit")
      repeating.cancel()
      assert(scheduler.getQueue.size() == baseline, "cancelled repeating timer retained until its deadline")
    finally
      oneShot.cancel()
      repeating.cancel()
      scope.close()
      RenderThread.unregister()

  test("timer admission rejects saturation synchronously and cancellation allows a replacement"):
    val loop   = RenderThread.register(Thread.currentThread())
    val scope  = Async.scope()
    val timers = scala.collection.mutable.ArrayBuffer.empty[Cancelable]
    try
      (1 to 1024).foreach(_ => { val _ = timers.append(scope.after(1.hour)(fail("long timer ran"))) })
      assertThrows[java.util.concurrent.RejectedExecutionException](Async.every(1.millis)(()))
      assertThrows[java.util.concurrent.RejectedExecutionException](scope.after(1.hour)(()))
      timers.head.cancel()
      val replacement = Async.after(1.hour)(())
      replacement.cancel()
      scope.close()
      (1 to 1024).foreach(_ => loop.enqueue(() => ()))
      while loop.hasPending do loop.drain()
    finally
      timers.foreach(_.cancel())
      scope.close()
      RenderThread.unregister()
