package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

/** `TaskScope.latest(key)` is the replacement for the generation counter the live-data guide used to teach: starting a
  * task under a key that already has one in flight cancels the old one, and only the newest result for that key is ever
  * delivered. The acceptance scenario is the race the counter existed for — the *older* task finishes *after* the newer
  * one — so a `latest` that merely cancelled queued work and let an admitted result through would fail here.
  */
final class TaskScopeLatestSpec extends AnyFunSuite:

  private def pump(loop: RenderThread.RenderLoop)(until: => Boolean): Unit =
    val deadline = System.nanoTime() + 3_000_000_000L
    while !until && System.nanoTime() < deadline do
      RenderThread.drainPending(loop)
      Thread.`yield`()
    RenderThread.drainPending(loop)

  test("a first start that completes after a second start is dropped; the second is delivered exactly once"):
    val loop = RenderThread.register(Thread.currentThread())
    try
      val scope        = Async.scope()
      val firstStarted = CountDownLatch(1)
      val releaseFirst = CountDownLatch(1)
      val delivered    = mutable.ArrayBuffer.empty[Either[Throwable, String]]
      val deliveries   = AtomicInteger(0)

      scope.latest("search") {
        firstStarted.countDown()
        assert(releaseFirst.await(3, TimeUnit.SECONDS))
        "stale"
      } { result =>
        delivered += result
        val _ = deliveries.incrementAndGet()
      }
      assert(firstStarted.await(3, TimeUnit.SECONDS))

      scope.latest("search")("fresh") { result =>
        delivered += result
        val _ = deliveries.incrementAndGet()
      }
      pump(loop)(deliveries.get() >= 1)
      assert(delivered.toList == List(Right("fresh")))

      releaseFirst.countDown()
      // give the first task every chance to sneak its result through before asserting it never did
      pump(loop)(false)
      assert(delivered.toList == List(Right("fresh")), "the superseded task delivered a result")
      assert(deliveries.get() == 1)
      scope.close()
    finally
      loop.close()
      RenderThread.unregister()

  test("different keys do not cancel each other"):
    val loop = RenderThread.register(Thread.currentThread())
    try
      val scope     = Async.scope()
      val delivered = mutable.Set.empty[String]
      scope.latest("a")("one")(result => result.foreach(delivered += _))
      scope.latest("b")("two")(result => result.foreach(delivered += _))
      pump(loop)(delivered.size == 2)
      assert(delivered == Set("one", "two"))
      scope.close()
    finally
      loop.close()
      RenderThread.unregister()

  test("a completed task releases its key, so the next start under it runs and is delivered"):
    val loop = RenderThread.register(Thread.currentThread())
    try
      val scope     = Async.scope()
      val delivered = mutable.ArrayBuffer.empty[String]
      scope.latest("k")("first")(result => result.foreach(delivered += _))
      pump(loop)(delivered.size == 1)
      scope.latest("k")("second")(result => result.foreach(delivered += _))
      pump(loop)(delivered.size == 2)
      assert(delivered.toList == List("first", "second"))
      scope.close()
    finally
      loop.close()
      RenderThread.unregister()

  test("a failure under a key is delivered as Left when it is still the latest"):
    val loop = RenderThread.register(Thread.currentThread())
    try
      val scope   = Async.scope()
      val failure = IllegalStateException("boom")
      var seen    = Option.empty[Either[Throwable, Int]]
      scope.latest[Int]("k")(throw failure)(result => seen = Some(result))
      pump(loop)(seen.isDefined)
      assert(seen.contains(Left(failure)))
      scope.close()
    finally
      loop.close()
      RenderThread.unregister()
