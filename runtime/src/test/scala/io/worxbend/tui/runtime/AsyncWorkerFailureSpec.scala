package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

final class AsyncWorkerFailureSpec extends AnyFunSuite:
  private def exposes(expected: Throwable, policy: AsyncErrorHandler): Unit =
    val observed = AtomicReference[Option[Throwable]](None)
    val reported = CountDownLatch(1)
    val previous = Thread.getDefaultUncaughtExceptionHandler
    Thread.setDefaultUncaughtExceptionHandler((_, error) => {
      observed.set(Some(error))
      reported.countDown()
    })
    try
      Async.run[Int](throw IllegalStateException("work"))(_ => ())(using policy)
      assert(reported.await(3, TimeUnit.SECONDS), "worker failure was hidden inside a discarded Future")
      assert(observed.get().contains(expected))
    finally Thread.setDefaultUncaughtExceptionHandler(previous)

  test("rethrow reaches the worker uncaught exception handler"):
    val error = IllegalStateException("work")
    exposes(error, _ => AsyncErrorHandler.rethrow.handle(error))

  test("a throwing custom error handler reaches the worker uncaught exception handler"):
    val error = IllegalArgumentException("reporting failed")
    exposes(error, _ => throw error)
