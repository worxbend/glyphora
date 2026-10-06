package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Color, Event, KeyCode, KeyEvent, Size, Style}
import io.worxbend.tui.runtime.{EventOutcome, Frame, RunnerError, RunnerHandle, TerminalRunner}
import io.worxbend.tui.terminal.{BackendError, HeadlessBackend}

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.CountDownLatch
import scala.concurrent.duration.DurationInt

/** Pins that a throwable escaping the app body reaches the *test* thread. Every one of these assertions passes
  * vacuously against the pre-fix `Pilot`, which read a dead thread as a clean exit: `waitForIdle` returned `this`,
  * `awaitTermination` returned `true`, and `screenLines` returned `Seq.empty`.
  */
final class PilotFailureSpec extends AnyFunSuite:

  private val Boom = "the view blew up"

  /** A pilot whose app has already crashed and whose failure has already been observed once, so the accessors under
    * test race nothing.
    */
  private def crashedPilot(): Pilot =
    val backend = HeadlessBackend(Size(10, 3))
    val pilot   = Pilot.start(backend)(throw IllegalStateException(Boom))
    val _       = intercept[AssertionError](pilot.waitForIdle())
    pilot

  test("waitForIdle reports a crashed app thread with the original throwable as the cause"):
    val backend = HeadlessBackend(Size(10, 3))
    val pilot   = Pilot.start(backend)(throw IllegalStateException(Boom))
    val error   = intercept[AssertionError](pilot.waitForIdle())
    assert(error.getMessage.contains("tui-pilot-app"))
    assert(error.getCause.getMessage == Boom)
    assert(error.getCause.getClass == classOf[IllegalStateException])

  test("awaitTermination refuses to call a crash a clean exit"):
    // The acceptance criterion allows "returns false (or throws)"; throwing is the only arm that keeps the
    // diagnostic, and `assert(pilot.awaitTermination())` at the existing call sites then names the real cause.
    val error = intercept[AssertionError](crashedPilot().awaitTermination())
    assert(error.getCause.getMessage == Boom)

  test("screenLines surfaces the app failure instead of an empty frame"):
    val pilot     = crashedPilot()
    val fromLines = intercept[AssertionError](pilot.screenLines)
    assert(fromLines.getCause.getMessage == Boom)
    val fromText  = intercept[AssertionError](pilot.screenText)
    assert(fromText.getCause.getMessage == Boom)

  test("lastFrame, cellAt and isRunning report the crash rather than a misleading diagnostic"):
    val pilot = crashedPilot()
    val frame = intercept[AssertionError](pilot.lastFrame)
    assert(frame.getCause.getMessage == Boom)
    assert(!frame.getMessage.contains("nothing has been drawn yet"))
    assert(intercept[AssertionError](pilot.cellAt(0, 0)).getCause.getMessage == Boom)
    assert(intercept[AssertionError](pilot.isRunning).getCause.getMessage == Boom)

  test("a clean quit still reports success and never throws"):
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = Pilot.start(backend)(TerminalRunner(backend).run(_ => (), quitOnQ, render))
    try
      pilot.waitForIdle()
      assert(pilot.screenLines.nonEmpty)
      assert(pilot.isRunning)
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination())
      assert(pilot.screenLines.nonEmpty)
      assert(!pilot.isRunning)
    finally pilot.close()

  test("a runner that returned Left is not a clean exit"):
    // the silent pass this replaces: the app thread finished without throwing, so every observation read as healthy
    // and a test asserting on the last frame passed against whatever was on screen when the run failed
    val backend = HeadlessBackend(Size(10, 3))
    val failure = RunnerError.Backend(BackendError.UnsupportedTerminal("cannot restore"))
    val pilot   = Pilot.start(backend)(Left(failure))
    val error   = intercept[AssertionError](pilot.awaitTermination())
    assert(error.getMessage.contains("terminal not supported: cannot restore"))
    assert(intercept[AssertionError](pilot.screenLines).getMessage.contains("cannot restore"))

  /** The app blocks on a latch rather than on a sleep so the busy app outlives the assertion by microseconds instead of
    * seconds: a test that leaves a live app thread behind hands its cost to whatever runs next, which on a two-core CI
    * runner executing several forked JVMs at once is not free.
    */
  test("an app that never goes idle still reports the timeout, not a crash"):
    val backend = HeadlessBackend(Size(10, 3))
    val release = CountDownLatch(1)
    val pilot   = Pilot.start(backend) { release.await(); Right(()) }
    try
      val error = intercept[AssertionError](pilot.waitForIdle(100.millis))
      assert(error.getMessage.contains("did not go idle"))
      assert(Option(error.getCause).isEmpty)
    finally
      release.countDown()
      pilot.close()
    assert(pilot.awaitTermination()) // and the app really is gone before the next test starts

  private def render(frame: Frame): Unit =
    frame.renderWidget(
      (area, buffer) => buffer.setString(area.x, area.y, "still here", Style.Default.withFg(Color.Cyan)),
      frame.area,
    )

  private def quitOnQ(event: Event, handle: RunnerHandle): EventOutcome =
    event match
      case Event.Key(KeyEvent(KeyCode.Char('q'), _)) =>
        handle.quit()
        EventOutcome.Ignored
      case _                                         => EventOutcome.Redraw
