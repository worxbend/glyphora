package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, RejectedExecutionException, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

final class AsyncAdmissionSpec extends AnyFunSuite:
  test("worker overload rejects without running on the caller and recovers after parked work completes"):
    val workers  = math.max(2, Runtime.getRuntime.availableProcessors())
    val entered  = CountDownLatch(workers)
    val release  = CountDownLatch(1)
    val drained  = CountDownLatch(workers + 256)
    val caller   = Thread.currentThread()
    val onCaller = AtomicInteger(0)
    try
      (1 to workers).foreach { _ =>
        Async.onWorker {
          if Thread.currentThread() eq caller then { val _ = onCaller.incrementAndGet() }
          entered.countDown()
          assert(release.await(10, TimeUnit.SECONDS))
          drained.countDown()
        }
      }
      assert(entered.await(5, TimeUnit.SECONDS))
      (1 to 256).foreach(_ => Async.onWorker(drained.countDown()))
      assertThrows[RejectedExecutionException](Async.onWorker { val _ = onCaller.incrementAndGet() })
      assert(onCaller.get() == 0)
      release.countDown()
      assert(drained.await(5, TimeUnit.SECONDS))
      val recovered = CountDownLatch(1)
      Async.onWorker(recovered.countDown())
      assert(recovered.await(5, TimeUnit.SECONDS))
    finally release.countDown()

  Seq(false, true).foreach { catching =>
    val method = if catching then "runCatching" else "run"
    test(s"unscoped $method worker rejection rolls back its reservation without reporting a work failure"):
      val workers                    = math.max(2, Runtime.getRuntime.availableProcessors())
      val entered                    = CountDownLatch(workers)
      val release                    = CountDownLatch(1)
      val finished                   = CountDownLatch(workers + 256)
      val completionQueued           = CountDownLatch(1)
      val caller                     = Thread.currentThread()
      val loop                       = RenderThread.register(
        caller,
        () => {
          if Thread.currentThread() ne caller then completionQueued.countDown()
        },
      )
      val workStarted                = AtomicInteger(0)
      val workErrors                 = AtomicInteger(0)
      var results                    = List.empty[Either[Throwable, Int]]
      def submit(work: => Int): Unit =
        def onDone(value: Either[Throwable, Int]): Unit =
          assert(Thread.currentThread() eq caller)
          results = results :+ value
        if catching then Async.runCatching(work)(onDone)
        else Async.run(work)(value => onDone(Right(value)))(using _ => { val _ = workErrors.incrementAndGet() })
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
        (1 to 1023).foreach(_ => loop.enqueue(() => ()))
        assertThrows[RejectedExecutionException](submit {
          val _ = workStarted.incrementAndGet()
          throw IllegalStateException("rejected work must not start")
        })
        // The rejected worker submission must give back the last owner slot immediately.
        loop.enqueue(() => ())
        assertThrows[RejectedExecutionException](loop.enqueue(() => ()))
        while loop.hasPending do loop.drain()
        assert(results.isEmpty)
        assert(workErrors.get() == 0)
        assert(workStarted.get() == 0)
        release.countDown()
        assert(finished.await(5, TimeUnit.SECONDS))
        submit(42)
        assert(completionQueued.await(3, TimeUnit.SECONDS))
        loop.drain()
        assert(results == List(Right(42)))
        assert(workStarted.get() == 0)
        assert(workErrors.get() == 0)
      finally
        release.countDown()
        RenderThread.unregister()
  }

  test("an active owner rejects excess callbacks without evicting accepted FIFO work"):
    val loop = RenderThread.register(Thread.currentThread())
    val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
    try
      (1 to 1024).foreach(i => loop.enqueue(() => { val _ = seen.append(i) }))
      assertThrows[RejectedExecutionException](loop.enqueue(() => fail("rejected callback ran")))
      while loop.hasPending do loop.drain()
      assert(seen.toList == (1 to 1024).toList)
      loop.enqueue(() => { val _ = seen.append(1025) })
      loop.drain()
      assert(seen.last == 1025)
      loop.close()
      assert(!loop.execute(fail("retired owner executed")))
    finally RenderThread.unregister()

  test("async admission reserves its required continuation before running background work"):
    val entered  = CountDownLatch(1)
    val release  = CountDownLatch(1)
    val enqueued = CountDownLatch(1024)
    val loop     = RenderThread.register(Thread.currentThread(), () => enqueued.countDown())
    var result   = Option.empty[Either[Throwable, Int]]
    try
      Async.runCatching {
        entered.countDown()
        assert(release.await(5, TimeUnit.SECONDS))
        42
      }(value => result = Some(value))
      assert(entered.await(3, TimeUnit.SECONDS))
      (1 to 1023).foreach(_ => loop.enqueue(() => ()))
      assertThrows[RejectedExecutionException](Async.runCatching(fail("rejected work ran"))(_ => ()))
      release.countDown()
      assert(enqueued.await(3, TimeUnit.SECONDS))
      while loop.hasPending do loop.drain()
      assert(result.contains(Right(42)))
    finally
      release.countDown()
      RenderThread.unregister()

  test("default async failure reporting uses its reserved slot even when the owner is saturated"):
    val entered  = CountDownLatch(1)
    val release  = CountDownLatch(1)
    val enqueued = CountDownLatch(1024)
    val failures = scala.collection.mutable.ArrayBuffer.empty[Throwable]
    val loop     = RenderThread.register(
      Thread.currentThread(),
      () => enqueued.countDown(),
      error => {
        val _ = failures.append(error)
      },
    )
    val original = IllegalStateException("failed parked IO")
    try
      Async.run[Int] {
        entered.countDown()
        assert(release.await(5, TimeUnit.SECONDS))
        throw original
      }(_ => fail("failure delivered success"))
      assert(entered.await(3, TimeUnit.SECONDS))
      (1 to 1023).foreach(_ => loop.enqueue(() => ()))
      assertThrows[RejectedExecutionException](loop.enqueue(() => ()))
      release.countDown()
      assert(enqueued.await(3, TimeUnit.SECONDS))
      while loop.hasPending do loop.drain()
      assert(failures.toList == List(original))
    finally
      release.countDown()
      RenderThread.unregister()

  test("a dispatch wake failure escapes the worker without replacing an accepted result or leaking capacity"):
    val failure        = IllegalStateException("wake failed after enqueue")
    val failWake       = AtomicBoolean(true)
    val observed       = AtomicReference[Option[(Thread, Throwable)]](None)
    val reported       = CountDownLatch(1)
    val previous       = Thread.getDefaultUncaughtExceptionHandler
    val renderFailures = scala.collection.mutable.ArrayBuffer.empty[Throwable]
    val caller         = Thread.currentThread()
    val loop           = RenderThread.register(
      caller,
      () => {
        if failWake.compareAndSet(true, false) then throw failure
      },
      error => { val _ = renderFailures.append(error) },
    )
    var results        = List.empty[Int]
    Thread.setDefaultUncaughtExceptionHandler((thread, error) => {
      observed.set(Some((thread, error)))
      reported.countDown()
    })
    try
      Async.run(42)(value => results = results :+ value)
      assert(reported.await(3, TimeUnit.SECONDS), "dispatch failure was swallowed by the occupied result reservation")
      val (worker, error) = observed.get().getOrElse(fail("no uncaught dispatch failure"))
      assert(error eq failure)
      assert(worker ne caller)
      assert(worker.getName.startsWith("glyphora-async-"))
      assert(results.isEmpty)
      (1 to 1023).foreach(_ => loop.enqueue(() => ()))
      assertThrows[RejectedExecutionException](loop.enqueue(() => ()))
      while loop.hasPending do loop.drain()
      assert(results == List(42))
      assert(renderFailures.isEmpty, "dispatch failure must not be re-enqueued as a work failure")
      (1 to 1024).foreach(_ => loop.enqueue(() => ()))
      assertThrows[RejectedExecutionException](loop.enqueue(() => ()))
      while loop.hasPending do loop.drain()
    finally
      Thread.setDefaultUncaughtExceptionHandler(previous)
      RenderThread.unregister()

  test("concurrent external dispatch rejects overload without exceeding owner capacity"):
    val loop      = RenderThread.register(Thread.currentThread())
    val start     = CountDownLatch(1)
    val rejected  = AtomicInteger(0)
    val accepted  = AtomicInteger(0)
    val delivered = AtomicInteger(0)
    val producers = (1 to 8).map { _ =>
      new Thread(() => {
        assert(start.await(3, TimeUnit.SECONDS))
        (1 to 160).foreach { _ =>
          try
            assert(loop.execute { val _ = delivered.incrementAndGet() })
            val _ = accepted.incrementAndGet()
          catch case _: RejectedExecutionException => { val _ = rejected.incrementAndGet() }
        }
      })
    }
    try
      producers.foreach(_.start())
      start.countDown()
      producers.foreach(_.join(5000))
      assert(producers.forall(!_.isAlive))
      assert(accepted.get() == 1024)
      assert(rejected.get() == 256)
      assert(delivered.get() == 0)
      while loop.hasPending do loop.drain()
      assert(delivered.get() == accepted.get())
    finally
      start.countDown()
      producers.foreach(_.join(5000))
      RenderThread.unregister()
