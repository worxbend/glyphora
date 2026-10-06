package io.worxbend.tui.examples.loadtest

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** Everything here runs headless: no TTY, no network, no device. The only target the suite ever constructs is
  * [[FakeTarget]], whose outcome for request `n` is a pure function of the seed and `n`, so the counts below are exact
  * however the worker threads happen to interleave.
  *
  * Every condition waits through [[Pilot.waitUntil]] rather than a hand-rolled poll: `waitForIdle` proves the posted
  * key events were consumed; it says nothing about a background run finishing, whose results only reach the UI on a
  * later render tick. Under parallel test load that tick can be starved for a while, so each assertion about the run
  * waits on a condition with a generous deadline.
  */
final class LoadTestAppSpec extends AnyFunSuite:

  private def withApp[A](target: Target, plan: Plan)(body: (LoadTestApp, Pilot) => A): A =
    val backend = HeadlessBackend(Size(88, 30))
    val app     = LoadTestApp(target, plan)
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      body(app, pilot)
    }

  private def liveThreadsNamed(prefix: String): Seq[String] =
    Thread.getAllStackTraces.keySet.asScala.toSeq
      .filter(thread => thread.isAlive && thread.getName.startsWith(prefix))
      .map(_.getName)

  test("a completed run accounts for every request and raises the summary screen"):
    withApp(FakeTarget(failureRate = 0.0), Plan(requests = 60, concurrency = 6)) { (app, pilot) =>
      pilot.press("s")

      pilot.waitUntil("the run to finish as Completed")(app.phase.peek == Phase.Finished(RunOutcome.Completed))
      val finished = app.stats.peek
      assert(finished.sent == 60)
      assert(finished.ok == 60)
      assert(finished.failed == 0)
      assert(finished.latencies.size == 60)
      pilot.waitUntil("the summary screen to open")(pilot.screenText.contains("Run summary"))
      assert(pilot.screenText.contains("success"))
      assert(pilot.screenText.contains("no errors"))

      pilot.press("q")
      assert(pilot.awaitTermination(5.seconds))
    }

  test("failures are tallied by reason and do not abort the run"):
    withApp(FakeTarget(failureRate = 1.0, pace = 0.millis), Plan(requests = 40, concurrency = 4)) { (app, pilot) =>
      pilot.press("s")

      pilot.waitUntil("the run to finish as Completed")(app.phase.peek == Phase.Finished(RunOutcome.Completed))
      val finished = app.stats.peek
      assert(finished.sent == 40)
      assert(finished.ok == 0)
      assert(finished.failed == 40)
      assert(finished.errors.values.sum == 40)
      pilot.waitUntil("the summary screen to open")(pilot.screenText.contains("Run summary"))
      assert(pilot.screenText.contains("operation timed out"))

      pilot.press("q")
      assert(pilot.awaitTermination(5.seconds))
    }

  test("stopping mid-flight ends the run short and empties the worker pool"):
    val plan = Plan(requests = 5000, concurrency = 4)
    withApp(FakeTarget(failureRate = 0.0, pace = 2.millis), plan) { (app, pilot) =>
      pilot.press("s")

      pilot.waitUntil("the first results to land")(app.stats.peek.sent > 0)
      pilot.waitUntil("the screen to show Running")(pilot.screenText.contains("Running"))
      pilot.press("x")

      pilot.waitUntil("the run to finish as Stopped")(app.phase.peek == Phase.Finished(RunOutcome.Stopped))
      assert(app.stats.peek.sent > 0)
      assert(app.stats.peek.sent < plan.requests)
      pilot.waitUntil("the worker pool to drain")(app.workersAlive == 0)
      pilot.waitUntil("the screen to show Stopped")(pilot.screenText.contains("Stopped"))

      pilot.press("q")
      assert(pilot.awaitTermination(5.seconds))
    }

  test("reset clears the counters and dismisses the summary"):
    withApp(FakeTarget(failureRate = 0.0), Plan(requests = 30, concurrency = 3)) { (app, pilot) =>
      pilot.press("s")
      pilot.waitUntil("the run to finish as Completed")(app.phase.peek == Phase.Finished(RunOutcome.Completed))
      pilot.waitUntil("the summary screen to open")(pilot.screenText.contains("Run summary"))

      pilot.press("r")
      pilot.waitUntil("the phase to return to Idle")(app.phase.peek == Phase.Idle)
      assert(app.stats.peek == RunStats.empty)
      pilot.waitUntil("the summary screen to close")(!pilot.screenText.contains("Run summary"))
      assert(pilot.screenText.contains("press s to start"))

      pilot.press("q")
      assert(pilot.awaitTermination(5.seconds))
    }

  test("quitting mid-run leaves no worker thread behind"):
    withApp(FakeTarget(failureRate = 0.0, pace = 2.millis), Plan(requests = 5000, concurrency = 8)) { (app, pilot) =>
      pilot.press("s")
      pilot.waitUntil("the first results to land")(app.stats.peek.sent > 0)
      assert(liveThreadsNamed(app.workerThreadPrefix).nonEmpty, "the pool should be busy before we quit")

      pilot.press("q")
      assert(pilot.awaitTermination(5.seconds))

      // The runner is gone, but the pool it started is not the runner's to garbage-collect: `q` has to stop it. The
      // thread-name prefix is unique per LoadRunner, so this cannot be satisfied by some other test's pool dying.
      pilot.waitUntil("the worker pool to drain")(app.workersAlive == 0)
      pilot.waitUntil("every worker thread to die", 5.seconds)(liveThreadsNamed(app.workerThreadPrefix).isEmpty)
    }

  test("the plan is adjustable while idle and frozen while running"):
    // The pace is deliberately slow: this is the one test that presses keys *while the run is in flight*, so the run
    // has to still be in flight when they arrive. At 2ms the 80 requests finished in ~30ms and the `[` occasionally
    // landed after the phase had already left Running, halving the requests and failing the freeze assertion.
    withApp(FakeTarget(failureRate = 0.0, pace = 50.millis), Plan(requests = 40, concurrency = 4)) { (app, pilot) =>
      pilot.press("+", "+", "]").waitForIdle()
      assert(app.plan.peek == Plan(requests = 80, concurrency = 6))
      assert(pilot.screenText.contains("n=80 c=6"))

      pilot.press("s")
      pilot.waitUntil("the run to start")(app.phase.peek == Phase.Running)
      pilot.press("+", "[").waitForIdle()
      assert(app.plan.peek == Plan(requests = 80, concurrency = 6))

      pilot.waitUntil("the run to finish as Completed")(app.phase.peek == Phase.Finished(RunOutcome.Completed))
      assert(app.stats.peek.sent == 80)

      pilot.press("q")
      assert(pilot.awaitTermination(5.seconds))
    }
