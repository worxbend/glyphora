package io.worxbend.tui.testsupport

import io.worxbend.tui.core.{Event, Size, Style}
import io.worxbend.tui.runtime.{EventOutcome, RunnerConfig, TerminalRunner}

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{Duration, DurationInt}

/** Pins the hand-driven clock a tick-driven test runs its app against.
  *
  * The property worth testing is *exactness*: an advance of three tick rates has to produce three ticks and never a
  * fourth, however slow the machine running the suite is. A test that polled a real clock could only assert "at least
  * three", which passes just as happily against a runner that emits a tick on every pass around its loop.
  */
final class ManualClockSpec extends AnyFunSuite:

  private val Rate = 50.millis

  /** An app that counts ticks and paints the count. Its tick handler answers `Redraw`, so every tick paints a frame and
    * `advanceClock`'s frame count is the tick count.
    */
  private def startCounting(clock: ManualClock, ticks: AtomicInteger): Pilot =
    Pilot.start(Size(20, 1)) { backend =>
      TerminalRunner(backend, RunnerConfig(tickRate = Some(Rate)), clock.reading).run(
        _ => (),
        (event, _) =>
          event match
            case Event.Tick =>
              val _ = ticks.incrementAndGet()
              EventOutcome.Redraw
            case _          => EventOutcome.Ignored,
        frame =>
          frame.renderWidget(
            (area, buffer) => buffer.setString(area.x, area.y, s"ticks ${ticks.get()}", Style.Default),
            frame.area,
          ),
      )
    }

  test("a frozen clock produces no ticks at all"):
    val ticks = AtomicInteger(0)
    val pilot = startCounting(ManualClock(), ticks)
    try
      pilot.waitForIdle()
      // several poll periods of real time pass while the pilot settles; a wall-clock runner would have ticked by now
      pilot.press("x").waitForIdle()
      assert(ticks.get() == 0)
      assert(pilot.screenText.startsWith("ticks 0"))
    finally pilot.close()

  test("three advances of one tick rate produce exactly three ticks"):
    val clock = ManualClock()
    val ticks = AtomicInteger(0)
    val pilot = startCounting(clock, ticks)
    try
      pilot.waitForIdle()
      pilot.advanceClock(clock, Rate).advanceClock(clock, Rate).advanceClock(clock, Rate)
      assert(ticks.get() == 3)
      assert(pilot.screenText.startsWith("ticks 3"))
      // and the app stays where it was put: no fourth tick arrives from anywhere
      pilot.press("x").waitForIdle()
      assert(ticks.get() == 3)
    finally pilot.close()

  test("one long advance is still one tick, because the runner rebases on the reading it fired at"):
    // worth pinning rather than assuming: the loop records `lastTick = nanoTime()` when it fires, so a jump of three
    // tick rates does not queue three ticks up — it fires once and puts the next one a whole rate after the jump
    val clock = ManualClock()
    val ticks = AtomicInteger(0)
    val pilot = startCounting(clock, ticks)
    try
      pilot.waitForIdle()
      pilot.advanceClock(clock, Rate * 3)
      pilot.press("x").waitForIdle()
      assert(ticks.get() == 1)
    finally pilot.close()

  test("an advance shorter than the tick rate produces no tick"):
    val clock = ManualClock()
    val ticks = AtomicInteger(0)
    val pilot = startCounting(clock, ticks)
    try
      pilot.waitForIdle()
      pilot.advanceClock(clock, Rate / 2, draws = 0)
      pilot.press("x").waitForIdle()
      assert(ticks.get() == 0)
    finally pilot.close()

  test("advances accumulate, so two halves of a tick rate tick once"):
    val clock = ManualClock()
    val ticks = AtomicInteger(0)
    val pilot = startCounting(clock, ticks)
    try
      pilot.waitForIdle()
      pilot.advanceClock(clock, Rate / 2, draws = 0)
      pilot.advanceClock(clock, Rate / 2, draws = 1)
      assert(ticks.get() == 1)
    finally pilot.close()

  test("elapsed reports how far the clock has been moved"):
    val clock = ManualClock(10.seconds)
    assert(clock.elapsed == Duration.Zero)
    val _     = clock.advance(250.millis)
    assert(clock.elapsed == 250.millis)
    // the reading itself starts where the clock was told to start, which is what a runner comparing two readings sees
    assert(clock.reading() == (10.seconds + 250.millis).toNanos)

  test("a clock cannot be stood still or rewound"):
    val clock = ManualClock()
    assert(intercept[IllegalArgumentException](clock.advance(Duration.Zero)).getMessage.contains("positive"))
    assert(intercept[IllegalArgumentException](clock.advance((-1).milli)).getMessage.contains("positive"))
