package io.worxbend.tui.testsupport

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.{EventOutcome, TerminalRunner}
import io.worxbend.tui.terminal.HeadlessBackend
import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.duration.DurationInt

final class PilotLifecycleSpec extends AnyFunSuite:
  test("a read waits for its pilot's startup instead of executing inline on the test thread"):
    val begin   = CountDownLatch(1)
    val reading = CountDownLatch(1)
    val landed  = CountDownLatch(1)
    val owner   = AtomicReference[Option[Thread]](None)
    val actual  = AtomicReference[Option[Thread]](None)
    val backend = HeadlessBackend(Size(10, 2))
    val pilot   = Pilot.start(backend) {
      val _ = begin.await(10, TimeUnit.SECONDS)
      TerminalRunner(backend).run(_ => owner.set(Some(Thread.currentThread())), (_, _) => EventOutcome.Ignored, _ => ())
    }
    val reader  = new Thread(() =>
      reading.countDown()
      pilot.readOnRenderThread {
        actual.set(Some(Thread.currentThread()))
        landed.countDown()
      }
    )
    reader.start()
    try
      assert(reading.await(2, TimeUnit.SECONDS))
      assert(!landed.await(100, TimeUnit.MILLISECONDS), "read ran before the runner registered")
      begin.countDown()
      reader.join(3000)
      assert(!reader.isAlive)
      assert(actual.get() == owner.get())
    finally
      begin.countDown()
      try pilot.close()
      finally reader.join(3000)

  test("two simultaneous pilots never execute each other's reads"):
    val parked   = CountDownLatch(1)
    val resume   = CountDownLatch(1)
    val owner    = AtomicReference[Option[Thread]](None)
    val actual   = AtomicReference[Option[Thread]](None)
    val landed   = CountDownLatch(1)
    val aBackend = HeadlessBackend(Size(10, 2))
    val a        = Pilot.start(aBackend) {
      TerminalRunner(aBackend).run(
        _ =>
          owner.set(Some(Thread.currentThread()))
          parked.countDown()
          val _ = resume.await(10, TimeUnit.SECONDS)
        ,
        (_, _) => EventOutcome.Ignored,
        _ => (),
      )
    }
    val b        =
      Pilot.start(Size(10, 2))(backend => TerminalRunner(backend).run(_ => (), (_, _) => EventOutcome.Ignored, _ => ()))
    val reader   = new Thread(() =>
      a.readOnRenderThread {
        actual.set(Some(Thread.currentThread()))
        landed.countDown()
      }
    )
    try
      assert(parked.await(2, TimeUnit.SECONDS))
      b.waitForIdle()
      reader.start()
      assert(!landed.await(100, TimeUnit.MILLISECONDS), "B executed a read belonging to parked A")
      resume.countDown()
      reader.join(3000)
      assert(!reader.isAlive)
      assert(actual.get() == owner.get())
      val bThread   = b.readOnRenderThread(Thread.currentThread())
      assert(!owner.get().contains(bThread))
      a.close()
      var staleRead = false
      intercept[AssertionError](a.readOnRenderThread({ staleRead = true }, 50.millis))
      assert(!staleRead)
      assert(b.readOnRenderThread(Thread.currentThread()) == bThread)
    finally
      resume.countDown()
      try a.close()
      finally
        b.close()
        reader.join(3000)

  test("close cancels a runner even when every interrupt is consumed"):
    val events = AtomicInteger(0)
    val pilot  = Pilot.start(Size(10, 2)) { backend =>
      TerminalRunner(backend).run(
        _ => (),
        (_, _) =>
          val _ = events.incrementAndGet()
          EventOutcome.Redraw
        ,
        _ => (),
      )
    }
    try
      pilot.waitForIdle()
      pilot.close(500.millis)
      assert(!pilot.isRunning)
      assert(events.get() == 0, "shutdown must not inject an application event")
      pilot.close()
    finally pilot.close()

  test("using terminates a live runner after a failing assertion and preserves that failure"):
    val observed = AtomicReference[Option[Pilot]](None)
    val primary  = AssertionError("test body failed")
    val failure  = intercept[AssertionError] {
      Pilot.using(Size(10, 2))(backend =>
        TerminalRunner(backend).run(_ => (), (_, _) => EventOutcome.Redraw, _ => ())
      ) { pilot =>
        observed.set(Some(pilot))
        pilot.waitForIdle()
        throw primary
      }
    }
    assert(failure eq primary)
    assert(observed.get().exists(pilot => !pilot.isRunning))

  test("using preserves a body failure when the registered runner also fails during close"):
    val observed = AtomicReference[Option[Pilot]](None)
    val primary  = AssertionError("body")
    val cleanup  = IllegalStateException("stop")
    val failure  = intercept[AssertionError] {
      Pilot.using(Size(10, 2))(backend =>
        TerminalRunner(backend, onStop = () => throw cleanup).run(_ => (), (_, _) => EventOutcome.Ignored, _ => ())
      ) { pilot =>
        observed.set(Some(pilot))
        pilot.waitForIdle()
        throw primary
      }
    }
    assert(failure eq primary)
    assert(failure.getSuppressed.toList.map(_.getMessage).exists(_.contains("stop")))
    assert(observed.get().nonEmpty)
    observed.get().foreach { pilot =>
      // Close reports the failed runner again, but observes termination without another live owner.
      intercept[AssertionError](pilot.close())
    }

  test("close before registration records cancellation for that pilot alone"):
    val begin = CountDownLatch(1)
    val a     = Pilot.start(Size(10, 2)) { backend =>
      val _ = begin.await(10, TimeUnit.SECONDS)
      TerminalRunner(backend).run(_ => (), (_, _) => EventOutcome.Redraw, _ => ())
    }
    val b     =
      Pilot.start(Size(10, 2))(backend => TerminalRunner(backend).run(_ => (), (_, _) => EventOutcome.Redraw, _ => ()))
    try
      b.waitForIdle()
      intercept[AssertionError](a.close(10.millis))
      begin.countDown()
      assert(a.awaitTermination())
      assert(b.isRunning)
    finally
      begin.countDown()
      try a.close()
      finally b.close()

  test("close is bounded when user code blocks the render thread"):
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val pilot   = Pilot.start(Size(10, 2)) { backend =>
      TerminalRunner(backend).run(
        _ =>
          entered.countDown()
          val _ = release.await(10, TimeUnit.SECONDS)
        ,
        (_, _) => EventOutcome.Ignored,
        _ => (),
      )
    }
    try
      assert(entered.await(2, TimeUnit.SECONDS))
      val before = System.nanoTime()
      intercept[AssertionError](pilot.close(20.millis))
      assert(System.nanoTime() - before < 1.second.toNanos)
    finally
      release.countDown()
      pilot.close()

  test("a pilot follows sequential runner registrations for reads and close"):
    val secondStarted = CountDownLatch(1)
    val owner         = AtomicReference[Option[Thread]](None)
    val firstBackend  = HeadlessBackend(Size(10, 2))
    val backend       = HeadlessBackend(Size(10, 2))
    val pilot         = Pilot.start(backend) {
      TerminalRunner(firstBackend).run(_.quit(), (_, _) => EventOutcome.Ignored, _ => ()).flatMap { _ =>
        TerminalRunner(backend).run(
          _ =>
            owner.set(Some(Thread.currentThread()))
            secondStarted.countDown()
          ,
          (_, _) => EventOutcome.Ignored,
          _ => (),
        )
      }
    }
    try
      assert(secondStarted.await(2, TimeUnit.SECONDS))
      assert(owner.get().contains(pilot.readOnRenderThread(Thread.currentThread())))
      pilot.close(500.millis)
      assert(!pilot.isRunning)
    finally
      backend.postEvent(io.worxbend.tui.core.Event.EndOfInput)
      pilot.close()

  test("returning from a nested runner restores the pilot's enclosing owner"):
    val nestedBackend = HeadlessBackend(Size(10, 2))
    val backend       = HeadlessBackend(Size(10, 2))
    val owner         = AtomicReference[Option[Thread]](None)
    val pilot         = Pilot.start(backend) {
      TerminalRunner(backend).run(
        _ =>
          owner.set(Some(Thread.currentThread()))
          assert(TerminalRunner(nestedBackend).run(_.quit(), (_, _) => EventOutcome.Ignored, _ => ()) == Right(()))
        ,
        (_, _) => EventOutcome.Ignored,
        _ => (),
      )
    }
    try
      pilot.waitForIdle()
      assert(owner.get().contains(pilot.readOnRenderThread(Thread.currentThread())))
      pilot.close(500.millis)
      assert(!pilot.isRunning)
    finally
      backend.postEvent(io.worxbend.tui.core.Event.EndOfInput)
      pilot.close()

  test("cancellation between sequential runners applies to the later registration"):
    val between      = CountDownLatch(1)
    val resume       = CountDownLatch(1)
    val firstBackend = HeadlessBackend(Size(10, 2))
    val backend      = HeadlessBackend(Size(10, 2))
    val pilot        = Pilot.start(backend) {
      TerminalRunner(firstBackend).run(_.quit(), (_, _) => EventOutcome.Ignored, _ => ()).flatMap { _ =>
        between.countDown()
        val _ = resume.await(10, TimeUnit.SECONDS)
        TerminalRunner(backend).run(_ => (), (_, _) => EventOutcome.Ignored, _ => ())
      }
    }
    try
      assert(between.await(2, TimeUnit.SECONDS))
      intercept[AssertionError](pilot.close(10.millis))
      resume.countDown()
      assert(pilot.awaitTermination(500.millis))
      assert(backend.drawCount == 0)
    finally
      resume.countDown()
      backend.postEvent(io.worxbend.tui.core.Event.EndOfInput)
      pilot.close()

  test("a stopped pilot rejects owner-confined reads rather than executing on a different thread"):
    val pilot = Pilot.start(Size(10, 2))(backend =>
      TerminalRunner(backend).run(_.quit(), (_, _) => EventOutcome.Ignored, _ => ())
    )
    try
      assert(pilot.awaitTermination())
      var read = false
      intercept[AssertionError](pilot.readOnRenderThread({ read = true }, 50.millis))
      assert(!read)
    finally pilot.close()
