package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, KeyCode, KeyEvent, Size}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.concurrent.{CountDownLatch, CyclicBarrier, LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Routing with **two** runners alive in one JVM.
  *
  * `Async` spends five lines warning that resolving the target loop inside the worker instead of at the call site
  * "still compiles and still passes a single-runner test" — and every other routing test in this repo has exactly one
  * runner, which is the case where the answer is the same either way: with one registered loop `RenderThread.capture()`
  * returns it from any thread. So the mutation the comment warns about is invisible to the whole suite. These tests are
  * the two-runner case where it is not.
  *
  * The shape every test here uses: **park runner A inside its own event handler** after it has armed the work, and
  * leave runner B looping. A parked runner drains nothing, so a continuation that reached the shared detached queue
  * instead of A's private one is picked up by B — with B's thread name on it — while a correctly routed one waits for
  * A. That turns a race into a deterministic answer in both directions.
  */
final class TwoRunnerRoutingSpec extends AnyFunSuite:

  /** Generous enough that a loaded CI box never trips it; a routing bug fails on the assertion long before this. */
  private val AwaitMillis: Long = 10_000L

  /** How long a *wrongly* routed body is given to show up on the other runner before we conclude it did not. The other
    * runner iterates every [[TickInterval]], so this is two orders of magnitude more than it needs.
    */
  private val WrongRunnerMillis: Long = 750L

  /** A tick rate, so an idle runner's `readEvent` poll is short and it drains its queue promptly. Without one the loop
    * still polls (100 ms), just coarsely.
    */
  private val TickInterval: FiniteDuration = 5.millis

  /** One [[TerminalRunner]] on its own thread over its own [[HeadlessBackend]], with a way to run a body *on that
    * runner's render thread* — inside its event handler — and then park the thread there until [[resume]].
    */
  private final class RunnerUnderTest(label: String, ready: CyclicBarrier):

    private val backend  = HeadlessBackend(Size(20, 3))
    private val commands = LinkedBlockingQueue[() => Unit]()
    private val parked   = CountDownLatch(1)
    private val started  = CountDownLatch(1)
    private val loopRef  = AtomicReference[Option[RenderThread.RenderLoop]](None)
    private val outcome  = AtomicReference[Option[Either[RunnerError, Unit]]](None)
    private val thread   = new Thread(() => runToCompletion(), label)

    private def runToCompletion(): Unit =
      val runner = TerminalRunner(backend, RunnerConfig(tickRate = Some(TickInterval)))
      outcome.set(Some(runner.run(_ => onStart(), onEvent, _ => ())))

    /** The first moment this thread is certainly registered — and the barrier that holds both runners here until the
      * other one is registered too, so nothing is armed while the registry still has a single entry.
      */
    private def onStart(): Unit =
      loopRef.set(Some(RenderThread.capture()))
      val _ = ready.await(AwaitMillis, TimeUnit.MILLISECONDS)
      started.countDown()

    private def onEvent(event: Event, handle: RunnerHandle): EventOutcome =
      event match
        case Event.Key(KeyEvent(KeyCode.Char('c'), _)) =>
          Option(commands.poll()).foreach(command => command())
          EventOutcome.Ignored
        case Event.Key(KeyEvent(KeyCode.Char('q'), _)) =>
          handle.quit()
          EventOutcome.Ignored
        case _                                         => EventOutcome.Ignored

    def start(): Unit = thread.start()

    def awaitStarted(): Unit =
      assert(started.await(AwaitMillis, TimeUnit.MILLISECONDS), s"$label never reached onStart")

    /** The queue this runner was handed when it registered. */
    def loop: RenderThread.RenderLoop =
      loopRef.get().getOrElse(fail(s"$label never captured its render loop"))

    /** The name this runner's render thread reports — what a continuation records to say where it ran. */
    def threadName: String = label

    /** Runs `body` on this runner's render thread and leaves the thread parked there until [[resume]]. Returns only
      * once `body` has actually run, so the caller may reason about what is armed.
      */
    def armAndPark(body: => Unit): Unit =
      val armed = CountDownLatch(1)
      commands.put { () =>
        body
        armed.countDown()
        val _ = parked.await(AwaitMillis, TimeUnit.MILLISECONDS)
      }
      backend.postEvent(Event.Key(KeyEvent.of(KeyCode.Char('c'))))
      assert(armed.await(AwaitMillis, TimeUnit.MILLISECONDS), s"$label never ran the body it was handed")

    /** Parks the render thread without arming anything. */
    def park(): Unit = armAndPark(())

    def resume(): Unit = parked.countDown()

    def stop(): Unit =
      resume()
      backend.postEvent(Event.Key(KeyEvent.of(KeyCode.Char('q'))))
      thread.join(AwaitMillis)
      assert(!thread.isAlive, s"$label never exited")
      outcome.get() match
        case Some(Right(())) => ()
        case other           => fail(s"$label exited with $other")

  /** Starts two runners and lets neither arm anything until both are registered. */
  private def withTwoRunners(body: (RunnerUnderTest, RunnerUnderTest) => Unit): Unit =
    val ready = CyclicBarrier(2)
    val a     = RunnerUnderTest("routing-runner-a", ready)
    val b     = RunnerUnderTest("routing-runner-b", ready)
    a.start()
    b.start()
    try
      a.awaitStarted()
      b.awaitStarted()
      body(a, b)
    finally
      try a.stop()
      finally b.stop()

  /** Collects the thread a body ran on, once per run, without ever asserting off the test thread: a ScalaTest failure
    * thrown inside a queued body is absorbed by the drain and reported as `RunnerError.QueuedTask`, which is not how a
    * routing bug should surface.
    */
  private final class Landing(expected: Int):
    private val names  = AtomicReference[List[String]](Nil)
    private val runs   = AtomicInteger(0)
    private val landed = CountDownLatch(expected)

    def record(): Unit =
      val _ = names.updateAndGet(seen => Thread.currentThread().getName :: seen)
      val _ = runs.incrementAndGet()
      landed.countDown()

    /** Whether the body ran within `millis`. */
    def awaitWithin(millis: Long): Boolean = landed.await(millis, TimeUnit.MILLISECONDS)

    def threads: List[String] = names.get().distinct
    def count: Int            = runs.get()

  test("two registered runners own distinct render loops, and a thread belonging to neither captures neither"):
    // the precondition the rest of this suite rests on: `RenderThread.capture()` must refuse to guess once there is
    // more than one runner, rather than handing an unregistered caller some runner's private queue
    withTwoRunners { (a, b) =>
      assert(a.loop ne b.loop, "both runners were handed the same queue")

      val captured = AtomicReference[Option[RenderThread.RenderLoop]](None)
      val outsider = new Thread(() => captured.set(Some(RenderThread.capture())), "routing-outsider-capture")
      outsider.start()
      outsider.join(AwaitMillis)
      assert(!outsider.isAlive, "the outsider thread never finished")

      val third = captured.get().getOrElse(fail("the outsider never captured a loop"))
      assert(third ne a.loop, "an unregistered thread was routed to one runner's private queue")
      assert(third ne b.loop, "an unregistered thread was routed to the other runner's private queue")
    }

  test("Async.run delivers its continuation to the runner that armed it, never to the other one"):
    // the mutation `Async.deliverToRenderThread` warns about: `capture()` inside the worker resolves nothing (the
    // worker belongs to no runner, and with two registered there is no sole loop to fall back to), so the
    // continuation lands on the detached queue and runner B runs it while A is parked
    withTwoRunners { (a, b) =>
      val landing = Landing(1)
      val value   = AtomicReference[Option[Int]](None)

      a.armAndPark {
        Async.run(20 + 22) { result =>
          value.set(Some(result))
          landing.record()
        }
      }

      assert(
        !landing.awaitWithin(WrongRunnerMillis),
        s"the continuation ran while its own runner was parked, on ${landing.threads}",
      )
      a.resume()
      assert(landing.awaitWithin(AwaitMillis), "the continuation never reached a render thread at all")
      assert(landing.threads == List(a.threadName), s"delivered off the arming runner: ${landing.threads}")
      assert(!landing.threads.contains(b.threadName))
      assert(landing.count == 1, s"the continuation ran ${landing.count} times")
      assert(value.get().contains(42))
    }

  test("Async.after resumes on the runner that armed it, never on the other one"):
    // `resumeOnRenderThread` captures on the arming thread for the same reason; the timer thread belongs to no runner
    withTwoRunners { (a, b) =>
      val landing = Landing(1)

      a.armAndPark {
        val _ = Async.after(20.millis)(landing.record())
      }

      assert(
        !landing.awaitWithin(WrongRunnerMillis),
        s"the timer body ran while the arming runner was parked, on ${landing.threads}",
      )
      a.resume()
      assert(landing.awaitWithin(AwaitMillis), "the timer body never reached a render thread at all")
      assert(landing.threads == List(a.threadName), s"resumed off the arming runner: ${landing.threads}")
      assert(!landing.threads.contains(b.threadName))
    }

  test("Async.every ticks onto the runner that armed it, never onto the other one"):
    withTwoRunners { (a, b) =>
      val landing = Landing(3)
      val handle  = AtomicReference[Option[Cancelable]](None)

      a.armAndPark {
        handle.set(Some(Async.every(10.millis)(landing.record())))
      }
      try
        assert(
          !landing.awaitWithin(WrongRunnerMillis),
          s"ticks ran while the arming runner was parked, on ${landing.threads}",
        )
        a.resume()
        assert(landing.awaitWithin(AwaitMillis), "the repeating body never reached a render thread at all")
        assert(landing.threads == List(a.threadName), s"ticks were split across runners: ${landing.threads}")
        assert(!landing.threads.contains(b.threadName))
      finally
        // the scheduler is a process-lifetime singleton: an uncancelled `every` keeps firing into a later test
        handle.get().foreach(cancelable => cancelable.cancel())
    }

  test("runLater from an unregistered thread lands on the bounded detached queue, not on either runner's"):
    // "lands on the detached queue" is asserted by its *bound*, not by which thread eventually drains it: a runner's
    // own loop is unlimited, so a body that had been routed to one could never have been dropped. Both runners are
    // parked while the queueing happens, so nothing drains the queue underneath the count.
    withTwoRunners { (a, b) =>
      val cap     = RenderThread.DetachedQueueLimit
      val queued  = cap * 2
      val landing = Landing(cap)
      a.park()
      b.park()

      val dropsBefore = RenderThread.detachedDrops
      val outsider    = new Thread(
        () => (1 to queued).foreach(_ => RenderThread.runLater(landing.record())),
        "routing-outsider-queue",
      )
      outsider.start()
      outsider.join(AwaitMillis)
      assert(!outsider.isAlive, "the outsider thread never finished queueing")

      assert(
        RenderThread.detachedDrops - dropsBefore == (queued - cap).toLong,
        "the queued bodies were not subject to the unattributed queue's cap, so they did not land on it",
      )
      assert(landing.count == 0, "a parked runner drained the queue")

      a.resume()
      b.resume()
      assert(landing.awaitWithin(AwaitMillis), s"only ${landing.count} of $cap surviving bodies were ever drained")
      assert(
        landing.threads.forall(name => name == a.threadName || name == b.threadName),
        s"unattributed work was drained off a render thread: ${landing.threads}",
      )
    }

  test("a long-lived async reporting policy binds its destination per invocation"):
    val landing             = Landing(2)
    given AsyncErrorHandler = AsyncErrorHandler.onRenderThread(_ => landing.record())
    withTwoRunners { (a, b) =>
      a.armAndPark {
        Async.run[Int](throw IllegalStateException("boom-a"))(_ => ())
      }
      assert(!landing.awaitWithin(WrongRunnerMillis))
      assert(landing.count == 0, s"runner B delivered A's error: ${landing.threads}")
      b.armAndPark {
        Async.run[Int](throw IllegalStateException("boom-b"))(_ => ())
      }
      a.resume()
      b.resume()
      assert(landing.awaitWithin(AwaitMillis))
      assert(landing.threads.toSet == Set(a.threadName, b.threadName))
      assert(landing.count == 2)
    }
