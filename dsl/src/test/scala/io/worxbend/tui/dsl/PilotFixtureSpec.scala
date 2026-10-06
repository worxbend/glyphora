package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.RenderThread
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.{Args, Reporter}
import org.scalatest.events.{Event, TestFailed}
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

final class PilotFixtureSpec extends AnyFunSuite:

  private def fixtureFailure(failDuringStartup: Boolean): Unit =
    val backend  = HeadlessBackend(Size(20, 3))
    var observed = Option.empty[Pilot]
    var failure  = Option.empty[Throwable]
    val app      = new TuiApp:
      def view(using ReactiveScope, Theme): Element = text("fixture")
    val suite    = new AnyFunSuite with PilotFixture:
      test("failing fixture"):
        val pilot = startPilot(backend)(app.runWith(backend))
        observed = Some(pilot)
        if failDuringStartup then
          pilot.waitUntil("an impossible startup condition", 50.millis) {
            pilot.readOnRenderThread(false)
          }
        else
          pilot.waitForIdle()
          assert(!RenderThread.isRenderThread, "the app must have registered an owner before the failure")
          fail("fixture assertion failed")
    val reporter = new Reporter:
      def apply(event: Event): Unit = event match
        case failed: TestFailed => failure = failed.throwable
        case _                  => ()
    try
      val status = suite.run(None, Args(reporter))
      status.waitUntilCompleted()
      assert(!status.succeeds())
      assert(
        failure.exists(_.getMessage.contains(if failDuringStartup then "startup condition" else "fixture assertion"))
      )
      assert(RenderThread.isRenderThread, "a failed fixture left a registered render owner")
      assert(observed.exists(pilot => !pilot.isRunning), "a failed fixture left its app thread alive")
      assert(!backend.isAlternateScreen, "a failed fixture left its backend unrestored")
    finally observed.foreach(_.close()) // also release the owner when testing a broken fixture implementation

  test("fixture cleanup releases the owner after an assertion fails"):
    fixtureFailure(failDuringStartup = false)

  test("fixture cleanup owns the Pilot before the initial startup wait can fail"):
    fixtureFailure(failDuringStartup = true)

  test("fixture cleanup keeps the assertion primary when app teardown also fails"):
    val backend  = HeadlessBackend(Size(20, 3))
    val primary  = AssertionError("primary fixture assertion")
    var failure  = Option.empty[Throwable]
    val app      = new TuiApp:
      override def onStop(): Unit                   = throw IllegalStateException("teardown failed")
      def view(using ReactiveScope, Theme): Element = text("fixture")
    val suite    = new AnyFunSuite with PilotFixture:
      test("failing fixture"):
        val pilot = startPilot(backend)(app.runWith(backend))
        pilot.waitForIdle()
        throw primary
    val reporter = new Reporter:
      def apply(event: Event): Unit = event match
        case failed: TestFailed => failure = failed.throwable
        case _                  => ()
    val status   = suite.run(None, Args(reporter))
    status.waitUntilCompleted()
    assert(!status.succeeds())
    assert(failure.contains(primary))
    assert(primary.getSuppressed.exists(_.getMessage.contains("teardown failed")))
    assert(RenderThread.isRenderThread, "failed teardown left a registered owner")
    assert(!backend.isAlternateScreen)
