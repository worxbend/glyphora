package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Buffer, Event, Rect, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.DurationInt

final class LatestFrameObserverSpec extends AnyFunSuite:

  test("close during error reporting drops pending delivery but does not interrupt the admitted handler"):
    val entered  = CountDownLatch(1)
    val release  = CountDownLatch(1)
    val failure  = IllegalStateException("observation failed")
    val errors   = new java.util.concurrent.LinkedBlockingQueue[Throwable]()
    val counts   = new java.util.concurrent.LinkedBlockingQueue[Long]()
    val observer = LatestFrameObserver(
      frame => {
        val _ = counts.add(frame.count)
        throw failure
      },
      error => {
        val _ = errors.add(error)
        entered.countDown()
        assert(release.await(5, TimeUnit.SECONDS))
      },
    )
    try
      observer(frame(0))
      assert(entered.await(2, TimeUnit.SECONDS))
      observer(frame(1))
      observer.close()
      observer(frame(2))
      assert(!observer.awaitTermination(scala.concurrent.duration.Duration.Zero))
      assert(!observer.awaitTermination((-1).second))
    finally
      release.countDown()
      observer.close()
    assert(observer.awaitTermination(2.seconds))
    assert(errors.size() == 1)
    assert(errors.poll() eq failure)
    assert(counts.size() == 1)
    assert(counts.poll() == 0L)

  test("fatal observation bypasses the nonfatal policy and retires pending delivery"):
    val entered     = CountDownLatch(1)
    val release     = CountDownLatch(1)
    val failure     = LinkageError("fatal observation")
    val uncaught    = new java.util.concurrent.LinkedBlockingQueue[Throwable]()
    val policyCalls = new java.util.concurrent.atomic.AtomicInteger()
    val calls       = new java.util.concurrent.atomic.AtomicInteger()
    val observer    = LatestFrameObserver(
      _ => {
        Thread.currentThread().setUncaughtExceptionHandler((_, error) => { val _ = uncaught.add(error) })
        val _ = calls.incrementAndGet()
        entered.countDown()
        assert(release.await(5, TimeUnit.SECONDS))
        throw failure
      },
      _ => { val _ = policyCalls.incrementAndGet() },
    )
    try
      observer(frame(0))
      assert(entered.await(2, TimeUnit.SECONDS))
      observer(frame(1))
      release.countDown()
      assert(uncaught.poll(2, TimeUnit.SECONDS) eq failure)
      observer(frame(2))
    finally
      release.countDown()
      observer.close()
    assert(observer.awaitTermination(2.seconds))
    assert(policyCalls.get() == 0)
    assert(calls.get() == 1)

  private def frame(count: Long): CompletedFrame =
    val area = Rect(0, 0, 2, 1)
    CompletedFrame(Buffer(area), area, count)

  test("a caller-owned observer delivers on its worker and can be closed"):
    val delivered = CountDownLatch(1)
    val thread    = AtomicReference[Thread]()
    val observer  = LatestFrameObserver(
      _ => {
        thread.set(Thread.currentThread())
        delivered.countDown()
      },
      error => throw error,
    )
    try
      observer(frame(0))
      assert(delivered.await(2, TimeUnit.SECONDS))
      assert(thread.get() ne Thread.currentThread())
    finally observer.close()
    assert(observer.awaitTermination(2.seconds))

  test("a blocked callback leaves only the latest pending frame for serial delivery"):
    val entered  = CountDownLatch(1)
    val release  = CountDownLatch(1)
    val latest   = CountDownLatch(1)
    val counts   = scala.collection.mutable.ArrayBuffer.empty[Long]
    val observer = LatestFrameObserver(
      frame => {
        counts.synchronized { val _ = counts += frame.count }
        if frame.count == 0 then
          entered.countDown()
          assert(release.await(5, TimeUnit.SECONDS))
        else latest.countDown()
      },
      error => throw error,
    )
    try
      observer(frame(0))
      assert(entered.await(2, TimeUnit.SECONDS))
      (1L to 10000L).foreach(count => observer(frame(count)))
      assert(counts.synchronized(counts.toVector) == Vector(0L))
      release.countDown()
      assert(latest.await(2, TimeUnit.SECONDS))
    finally
      release.countDown()
      observer.close()
    assert(observer.awaitTermination(2.seconds))
    assert(counts.synchronized(counts.toVector) == Vector(0L, 10000L))

  test("close drops pending frames without waiting for or interrupting an admitted callback"):
    val entered       = CountDownLatch(1)
    val release       = CountDownLatch(1)
    val returned      = CountDownLatch(1)
    val closeReturned = CountDownLatch(1)
    val counts        = scala.collection.mutable.ArrayBuffer.empty[Long]
    val observer      = LatestFrameObserver(
      frame => {
        counts.synchronized { val _ = counts += frame.count }
        entered.countDown()
        assert(release.await(5, TimeUnit.SECONDS))
        returned.countDown()
      },
      error => throw error,
    )
    try
      observer(frame(0))
      assert(entered.await(2, TimeUnit.SECONDS))
      observer(frame(1))
      val closer = new Thread(() => {
        observer.close()
        observer.close()
        closeReturned.countDown()
      })
      closer.setDaemon(true)
      closer.start()
      assert(closeReturned.await(2, TimeUnit.SECONDS))
      assert(!observer.awaitTermination(1.millis))
      observer(frame(2))
    finally
      release.countDown()
      observer.close()
    assert(observer.awaitTermination(2.seconds))
    assert(returned.getCount == 0)
    assert(counts.synchronized(counts.toVector) == Vector(0L))

  test("callback failures reach the explicit worker-side handler and delivery can continue"):
    val failure        = IllegalStateException("observation failed")
    val errors         = new java.util.concurrent.LinkedBlockingQueue[Throwable]()
    val delivered      = CountDownLatch(1)
    val callbackThread = AtomicReference[Thread]()
    val handlerThread  = AtomicReference[Thread]()
    val observer       = LatestFrameObserver(
      frame => {
        callbackThread.set(Thread.currentThread())
        if frame.count == 0 then throw failure
        delivered.countDown()
      },
      error => {
        handlerThread.set(Thread.currentThread())
        val _ = errors.add(error)
      },
    )
    try
      observer(frame(0))
      assert(errors.poll(2, TimeUnit.SECONDS) eq failure)
      assert(handlerThread.get() eq callbackThread.get())
      assert(handlerThread.get() ne Thread.currentThread())
      observer(frame(1))
      assert(delivered.await(2, TimeUnit.SECONDS))
    finally observer.close()
    assert(observer.awaitTermination(2.seconds))

  test("a throwing error handler escapes to the worker uncaught handler and retires pending delivery"):
    val entered       = CountDownLatch(1)
    val release       = CountDownLatch(1)
    val original      = IllegalStateException("observer failed")
    val policyFailure = IllegalArgumentException("error handler failed")
    val uncaught      = new java.util.concurrent.LinkedBlockingQueue[Throwable]()
    val counts        = scala.collection.mutable.ArrayBuffer.empty[Long]
    val observer      = LatestFrameObserver(
      frame => {
        Thread.currentThread().setUncaughtExceptionHandler((_, error) => { val _ = uncaught.add(error) })
        counts.synchronized { val _ = counts += frame.count }
        entered.countDown()
        assert(release.await(5, TimeUnit.SECONDS))
        throw original
      },
      error => {
        assert(error eq original)
        throw policyFailure
      },
    )
    try
      observer(frame(0))
      assert(entered.await(2, TimeUnit.SECONDS))
      observer(frame(1))
      release.countDown()
      assert(uncaught.poll(2, TimeUnit.SECONDS) eq policyFailure)
      observer(frame(2))
    finally
      release.countDown()
      observer.close()
    assert(observer.awaitTermination(2.seconds))
    assert(counts.synchronized(counts.toVector) == Vector(0L))

  test("bounded termination waiting includes an in-flight uncaught error handler"):
    val entered  = CountDownLatch(1)
    val release  = CountDownLatch(1)
    val observer = LatestFrameObserver(
      _ => {
        Thread
          .currentThread()
          .setUncaughtExceptionHandler((_, _) => {
            entered.countDown()
            assert(release.await(5, TimeUnit.SECONDS))
          })
        throw IllegalStateException("observe")
      },
      error => throw error,
    )
    try
      observer(frame(0))
      assert(entered.await(2, TimeUnit.SECONDS))
      assert(!observer.awaitTermination(1.millis))
    finally
      release.countDown()
      observer.close()
    assert(observer.awaitTermination(2.seconds))

  test("closing an idle observer wakes and retires its worker"):
    val counts   = new java.util.concurrent.atomic.AtomicInteger()
    val observer = LatestFrameObserver(_ => { val _ = counts.incrementAndGet() }, error => throw error)
    observer.close()
    observer(frame(0))
    assert(observer.awaitTermination(2.seconds))
    assert(counts.get() == 0)

  test("runner stop closes without awaiting a blocked observer and retained snapshots survive buffer reuse"):
    val backend  = HeadlessBackend(Size(4, 1))
    val entered  = CountDownLatch(1)
    val release  = CountDownLatch(1)
    val ended    = CountDownLatch(1)
    val result   = AtomicReference[Either[RunnerError, Unit]]()
    val errors   = AtomicReference[Throwable]()
    val texts    = scala.collection.mutable.ArrayBuffer.empty[String]
    val observer = LatestFrameObserver(
      frame => {
        entered.countDown()
        assert(release.await(5, TimeUnit.SECONDS))
        texts.synchronized { val _ = texts += frame.text }
      },
      errors.set,
    )
    backend.postEvent(Event.Tick)
    backend.postEvent(Event.Tick)
    backend.postEvent(Event.EndOfInput)
    val runner   = new Thread(() => {
      try
        result.set(
          TerminalRunner(backend, RunnerConfig(onFrame = Some(observer)), onStop = () => observer.close()).run(
            _ => (),
            (event, handle) => {
              assert(entered.await(2, TimeUnit.SECONDS))
              if event == Event.EndOfInput then
                handle.quit()
                EventOutcome.Ignored
              else EventOutcome.Redraw
            },
            frame => frame.buffer.setString(0, 0, frame.count.toString, Style.Default),
          )
        )
      finally ended.countDown()
    })
    runner.setDaemon(true)
    try
      runner.start()
      assert(ended.await(2, TimeUnit.SECONDS))
      assert(result.get() == Right(()))
      assert(backend.drawCount == 3L)
      assert(!backend.isRawMode)
      assert(!backend.isAlternateScreen)
      assert(!observer.awaitTermination(1.millis))
    finally
      release.countDown()
      observer.close()
      runner.join(2000L)
    assert(observer.awaitTermination(2.seconds))
    assert(Option(errors.get()).isEmpty)
    assert(texts.synchronized(texts.toVector) == Vector("0"))
