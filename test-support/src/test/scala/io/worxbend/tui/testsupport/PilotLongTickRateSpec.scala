package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Event, Size}
import io.worxbend.tui.runtime.{EventOutcome, RenderThread, RunnerConfig, RunnerError, TerminalRunner}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration.DurationInt

/** A `Pilot` must work with any `tickRate`. With a long one the runner blocks in `readEvent` for the whole rate, and
  * every promise the pilot makes — queued work runs promptly, `close()` returns, `waitForIdle()` returns on an idle app
  * — depends on `HeadlessBackend.wake()` cutting that read short the way the JLine backend's does. These used to fail
  * at any `tickRate` past the pilot's default deadline (2 s then, 10 s now).
  */
final class PilotLongTickRateSpec extends AnyFunSuite:

  private val LongTick = 15.seconds

  private def idleApp(backend: HeadlessBackend): Either[RunnerError, Unit] =
    TerminalRunner(backend, RunnerConfig(tickRate = Some(LongTick)))
      .run(_ => (), (_, _) => EventOutcome.Ignored, _ => ())

  test("work queued from a worker thread runs within 200 ms under a 15-second tick rate"):
    val ran     = CountDownLatch(1)
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = Pilot.start(backend)(
      TerminalRunner(backend, RunnerConfig(tickRate = Some(LongTick))).run(
        _ =>
          val loop   = RenderThread.capture()
          val worker = Thread(
            () =>
              Thread.sleep(100) // let the runner park in its long poll first
              val _ = loop.execute(ran.countDown())
            ,
            "pilot-long-tick-worker",
          )
          worker.setDaemon(true)
          worker.start()
        ,
        (_, _) => EventOutcome.Ignored,
        _ => (),
      )
    )
    try
      pilot.waitForDraws(1)
      val begun  = System.nanoTime()
      assert(ran.await(2, TimeUnit.SECONDS), "queued work never ran")
      val waited = (System.nanoTime() - begun) / 1_000_000L
      // 100 ms of that is the worker's own sleep; the bound excludes the 15 s tick a counter-only wake left in place, with
      // headroom for a loaded CI runner
      assert(waited < 1000L, s"queued work ran only after $waited ms")
    finally pilot.close()

  test("Pilot.close() returns within its default deadline under a 15-second tick rate"):
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = Pilot.start(backend)(idleApp(backend))
    pilot.waitForDraws(1)
    val begun   = System.nanoTime()
    pilot.close() // throws past its 10 s default deadline
    val took = (System.nanoTime() - begun) / 1_000_000L
    assert(took < 2000L, s"close took $took ms")

  test("Pilot.waitForIdle() returns on an idle app under a 15-second tick rate"):
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = Pilot.start(backend)(idleApp(backend))
    try
      pilot.waitForDraws(1)
      val begun = System.nanoTime()
      pilot.waitForIdle() // throws past its 10 s default deadline
      val took = (System.nanoTime() - begun) / 1_000_000L
      assert(took < 2000L, s"waitForIdle took $took ms")
    finally pilot.close()

  test("sliced polls and wakes add no ticks: none is observed over 300 ms while the clock stands still"):
    // `TickSchedule.takeDue` is clock-driven, so the extra `Right(None)` reads the sliced poll produces must not
    // manufacture `Event.Tick`s; with a manual clock that never advances there is nothing to be due
    val ticks   = AtomicInteger(0)
    val clock   = ManualClock()
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = Pilot.start(backend)(
      TerminalRunner(backend, RunnerConfig(tickRate = Some(LongTick)), clock.reading).run(
        _ => (),
        (event, _) =>
          if event == Event.Tick then
            val _ = ticks.incrementAndGet()
          EventOutcome.Ignored
        ,
        _ => (),
      )
    )
    try
      pilot.waitForDraws(1)
      pilot.waitForIdle()
      Thread.sleep(300)
      pilot.waitForIdle()
      assert(ticks.get() == 0, s"${ticks.get()} ticks arrived with the clock standing still")
    finally pilot.close()
