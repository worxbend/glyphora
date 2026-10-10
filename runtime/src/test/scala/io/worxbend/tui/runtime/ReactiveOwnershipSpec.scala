package io.worxbend.tui.runtime

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.duration.DurationInt

final class ReactiveOwnershipSpec extends AnyFunSuite:
  private def registered[A](body: => A): A =
    val loop = RenderThread.register(Thread.currentThread())
    try body
    finally
      loop.close()
      RenderThread.unregister()

  test("nested owners cannot read, mutate, subscribe to or dispose the enclosing graph"):
    val signal   = Signal(1)
    val computed = Computed(signal.get * 2)
    val scope    = ReactiveScope.generational(() => ())
    registered {
      assert(computed.get(using scope) == 2)
      registered {
        assert(signal.peek == 1)
        assert(signal.map(_ + 1).peek == 2)
        assertThrows[IllegalStateException](signal.set(3))
        assertThrows[IllegalStateException](signal.get(using ReactiveScope.onInvalidation(() => ())))
        assertThrows[IllegalStateException](computed.peek)
        assertThrows[IllegalStateException](computed.dispose())
        assertThrows[IllegalStateException](scope.beginGeneration())
        assertThrows[IllegalStateException](scope.dispose())
        val foreign = Signal(0)
        assertThrows[IllegalStateException](foreign.get(using scope))
      }
      signal.set(2)
      assert(computed.peek == 4)
      scope.dispose()
      computed.dispose()
    }

  test("runnerless cached graphs bind together on first owner access and rebind after retirement"):
    val signal   = Signal(1)
    val computed = Computed(signal.get * 2)
    assert(computed.peek == 2)
    registered {
      assert(computed.peek == 2)
      registered {
        assertThrows[IllegalStateException](signal.set(9))
      }
    }
    registered {
      signal.set(3)
      assert(computed.peek == 6)
      computed.dispose()
    }

  test("runnerless subscriptions bind their scopes with the dependency"):
    val signal = Signal(1)
    val scope  = ReactiveScope.generational(() => ())
    val _      = signal.get(using scope)
    registered {
      signal.set(2)
      registered {
        assertThrows[IllegalStateException](scope.dispose())
      }
      scope.dispose()
    }

  test("unregister retires the owner even without a separately closed loop"):
    val signal = Signal(0)
    RenderThread.register(Thread.currentThread())
    val scope  = Async.scope()
    try signal.set(1)
    finally RenderThread.unregister()
    assertThrows[IllegalStateException](scope.after(1.millis)(()))
    registered {
      signal.set(2)
      assert(signal.peek == 2)
    }

  test("retained state runs through two sequential TerminalRunner lifetimes"):
    val signal   = Signal(0)
    val computed = Computed(signal.get * 2)
    for expected <- 1 to 2 do
      val runner  = TerminalRunner(HeadlessBackend(Size(10, 2)))
      val outcome = runner.run(
        handle => {
          signal.update(_ + 1)
          assert(computed.peek == expected * 2)
          handle.quit()
        },
        (_, _) => EventOutcome.Ignored,
        _ => (),
      )
      assert(outcome == Right(()))
    assert(signal.peek == 2)
    computed.dispose()

  test("disposed computeds forget detached generational dependents across both buffers"):
    for previousGeneration <- Seq(false, true) do
      val computed = Computed(1)
      val scope    = ReactiveScope.generational(() => ())
      assert(computed.get(using scope) == 1)
      if previousGeneration then scope.beginGeneration()
      computed.dispose()
      registered {
        assert(computed.peek == 1)
        registered {
          scope.beginGeneration()
          scope.dispose()
          scope.dispose()
        }
        assert(computed.subscriberCount == 0)
      }

  test("disposed computeds forget detached computed dependents"):
    val source    = Computed(1)
    val dependent = Computed(source.get + 1)
    assert(dependent.peek == 2)
    source.dispose()
    registered {
      assert(source.peek == 1)
      registered {
        dependent.dispose()
      }
      assert(source.subscriberCount == 0)
    }
    assert(dependent.peek == 2)
    dependent.dispose()
    source.dispose()

  test("rejected reads leave fresh graphs available while the rejecting owner remains live"):
    for generational <- Seq(false, true) do
      val signal   = Signal(1)
      val computed = Computed(signal.get + 1)
      assert(computed.peek == 2)
      registered {
        val scope     = if generational then ReactiveScope.generational(() => ())
        else ReactiveScope.onInvalidation(() => ())
        val attempted = new java.util.concurrent.CountDownLatch(1)
        val release   = new java.util.concurrent.CountDownLatch(1)
        val failure   = new java.util.concurrent.atomic.AtomicReference[Throwable]()
        val worker    = new Thread(() =>
          registered[Unit] {
            try
              assertThrows[IllegalStateException](signal.get(using scope))
              assertThrows[IllegalStateException](computed.get(using scope))
            catch case error: Throwable => failure.set(error)
            finally attempted.countDown()
            assert(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
          }
        )
        worker.start()
        try
          assert(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS))
          Option(failure.get()).foreach(throw _)
          signal.set(3)
          assert(computed.get(using scope) == 4)
          computed.dispose()
        finally
          release.countDown()
          worker.join(5000)
        assert(!worker.isAlive)
      }

  test("rejected foreign dependencies do not claim a fresh scope or owner endpoint"):
    val scope      = ReactiveScope.generational(() => ())
    val plain      = ReactiveScope.onInvalidation(() => ())
    val freshOwner = new ReactiveOwner
    registered {
      val signal    = Signal(1)
      val owned     = new ReactiveOwner
      val attempted = new java.util.concurrent.CountDownLatch(1)
      val release   = new java.util.concurrent.CountDownLatch(1)
      val failure   = new java.util.concurrent.atomic.AtomicReference[Throwable]()
      val worker    = new Thread(() =>
        registered[Unit] {
          try
            assertThrows[IllegalStateException](signal.get(using scope))
            assertThrows[IllegalStateException](signal.get(using plain))
            assertThrows[IllegalStateException](freshOwner.connect(owned))
          catch case error: Throwable => failure.set(error)
          finally attempted.countDown()
          assert(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
      )
      worker.start()
      try
        assert(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS))
        Option(failure.get()).foreach(throw _)
        assert(signal.get(using scope) == 1)
        assert(signal.get(using plain) == 1)
        freshOwner.check()
      finally
        release.countDown()
        worker.join(5000)
      assert(!worker.isAlive)
      scope.dispose()
    }

  test("competing valid owners claim a graph once without poisoning the losing scope"):
    val signal    = Signal(1)
    val computed  = Computed(signal.get + 1)
    assert(computed.peek == 2)
    val scopes    = Vector.fill(2)(ReactiveScope.generational(() => ()))
    val start     = new java.util.concurrent.CyclicBarrier(2)
    val attempted = new java.util.concurrent.CountDownLatch(2)
    val winners   = new java.util.concurrent.atomic.AtomicInteger()
    val failures  = new java.util.concurrent.ConcurrentLinkedQueue[Throwable]()
    val workers   = scopes.map { scope =>
      new Thread(() =>
        registered[Unit] {
          try
            val _   = start.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val won =
              try
                assert(computed.get(using scope) == 2)
                val _ = winners.incrementAndGet()
                true
              catch case _: IllegalStateException => false
            attempted.countDown()
            assert(attempted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            if won then
              signal.set(2)
              assert(computed.peek == 3)
            else
              val independent = Signal(9)
              assert(independent.get(using scope) == 9)
              assertThrows[IllegalStateException](computed.peek)
            scope.dispose()
            val _   = start.await(5, java.util.concurrent.TimeUnit.SECONDS)
          catch
            case error: Throwable =>
              val _ = failures.add(error)
              attempted.countDown()
        }
      )
    }
    workers.foreach(_.start())
    workers.foreach(_.join(10000))
    assert(workers.forall(!_.isAlive))
    assert(failures.isEmpty, failures.toString)
    assert(winners.get() == 1)
    computed.dispose()

  test("plain invalidation scopes cannot cross owners even with a new signal"):
    registered {
      val scope = ReactiveScope.onInvalidation(() => ())
      registered {
        val other = Signal(0)
        assertThrows[IllegalStateException](other.get(using scope))
      }
    }
