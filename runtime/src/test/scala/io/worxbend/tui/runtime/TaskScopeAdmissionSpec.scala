package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, RejectedExecutionException, TimeUnit}

final class TaskScopeAdmissionSpec extends AnyFunSuite:
  test("a throwing admitted callback releases its slot without cancelling a queued sibling"):
    val queued   = CountDownLatch(2)
    val failures = scala.collection.mutable.ArrayBuffer.empty[Throwable]
    val loop     =
      RenderThread.register(Thread.currentThread(), () => queued.countDown(), error => { val _ = failures += error })
    val scope    = Async.scope()
    val failure  = IllegalStateException("callback failed")
    var sibling  = Option.empty[Int]
    try
      scope.runCatching(1)(_ => throw failure)
      scope.runCatching(2)(value => sibling = value.toOption)
      assert(queued.await(3, TimeUnit.SECONDS))
      (1 to 1022).foreach(_ => loop.enqueue(() => ()))
      assertThrows[RejectedExecutionException](loop.enqueue(() => ()))
      while loop.hasPending do loop.drain()
      assert(failures.toList == List(failure))
      assert(sibling.contains(2))
      (1 to 1024).foreach(_ => loop.enqueue(() => ()))
      assertThrows[RejectedExecutionException](loop.enqueue(() => ()))
      while loop.hasPending do loop.drain()
    finally
      scope.close()
      RenderThread.unregister()

  for failsWork <- List(false, true) do
    test(s"a dispatch wake failure preserves an accepted scoped outcome (work failure: $failsWork)"):
      val failure   = IllegalStateException("scoped wake failed")
      val workError = IllegalArgumentException("work failed")
      val failWake  = new java.util.concurrent.atomic.AtomicBoolean(true)
      val uncaught  = new java.util.concurrent.LinkedBlockingQueue[Throwable]()
      val previous  = Thread.getDefaultUncaughtExceptionHandler
      val loop      = RenderThread.register(
        Thread.currentThread(),
        () => {
          if failWake.compareAndSet(true, false) then throw failure
        },
      )
      val scope     = Async.scope()
      var result    = Option.empty[Either[Throwable, Int]]
      Thread.setDefaultUncaughtExceptionHandler((_, error) => { val _ = uncaught.add(error) })
      try
        scope.runCatching {
          if failsWork then throw workError else 42
        }(value => result = Some(value))
        assert(uncaught.poll(3, TimeUnit.SECONDS) eq failure)
        assert(loop.hasPending, "wake failure revoked an accepted continuation")
        loop.drain()
        assert(result.contains(if failsWork then Left(workError) else Right(42)))
        (1 to 1024).foreach(_ => loop.enqueue(() => ()))
        while loop.hasPending do loop.drain()
      finally
        Thread.setDefaultUncaughtExceptionHandler(previous)
        scope.close()
        RenderThread.unregister()

  test("fatal worker failure retires its unused reservation without invoking the result callback"):
    val failure   = LinkageError("fatal scoped work")
    val uncaught  = new java.util.concurrent.LinkedBlockingQueue[Throwable]()
    val previous  = Thread.getDefaultUncaughtExceptionHandler
    val loop      = RenderThread.register(Thread.currentThread())
    val scope     = Async.scope()
    var delivered = false
    Thread.setDefaultUncaughtExceptionHandler((_, error) => { val _ = uncaught.add(error) })
    try
      scope.runCatching[Int](throw failure)(_ => delivered = true)
      assert(uncaught.poll(3, TimeUnit.SECONDS) eq failure)
      assert(!loop.hasPending)
      assert(!delivered)
      (1 to 1024).foreach(_ => loop.enqueue(() => ()))
      while loop.hasPending do loop.drain()
    finally
      Thread.setDefaultUncaughtExceptionHandler(previous)
      scope.close()
      RenderThread.unregister()

  test("scoped worker admission reserves a callback and cancellation immediately frees that capacity"):
    val entered   = CountDownLatch(1)
    val release   = CountDownLatch(1)
    val finished  = CountDownLatch(1)
    val loop      = RenderThread.register(Thread.currentThread())
    val scope     = Async.scope()
    var delivered = false
    try
      val task = scope.runCatching {
        entered.countDown()
        assert(release.await(5, TimeUnit.SECONDS))
        finished.countDown()
        42
      }(_ => delivered = true)
      assert(entered.await(3, TimeUnit.SECONDS))
      (1 to 1023).foreach(_ => loop.enqueue(() => ()))
      assertThrows[RejectedExecutionException](scope.runCatching(fail("rejected work ran"))(_ => ()))
      task.cancel()
      loop.enqueue(() => ())
      release.countDown()
      assert(finished.await(3, TimeUnit.SECONDS))
      while loop.hasPending do loop.drain()
      assert(!delivered)
      scope.close()
    finally
      release.countDown()
      scope.close()
      RenderThread.unregister()

  test("worker-pool rejection rolls back the scoped delivery reservation"):
    val workers  = math.max(2, Runtime.getRuntime.availableProcessors())
    val entered  = CountDownLatch(workers)
    val release  = CountDownLatch(1)
    val finished = CountDownLatch(workers + 256)
    val loop     = RenderThread.register(Thread.currentThread())
    val scope    = Async.scope()
    try
      (1 to workers).foreach(_ =>
        Async.onWorker {
          entered.countDown()
          assert(release.await(10, TimeUnit.SECONDS))
          finished.countDown()
        }
      )
      assert(entered.await(5, TimeUnit.SECONDS))
      (1 to 256).foreach(_ => Async.onWorker(finished.countDown()))
      assertThrows[RejectedExecutionException](scope.runCatching(fail("rejected worker ran"))(_ => ()))
      (1 to 1024).foreach(_ => loop.enqueue(() => ()))
      while loop.hasPending do loop.drain()
      release.countDown()
      assert(finished.await(5, TimeUnit.SECONDS))
    finally
      release.countDown()
      scope.close()
      RenderThread.unregister()
