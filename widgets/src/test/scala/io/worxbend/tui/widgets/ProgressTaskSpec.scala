package io.worxbend.tui.widgets

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{Duration, DurationDouble, DurationInt, FiniteDuration}

final class ProgressTaskSpec extends AnyFunSuite:

  /** A clock the test moves by hand: the task reads it whenever it stamps or estimates. */
  private final class Clock:
    var now: FiniteDuration = Duration.Zero

  private def task(total: Option[Long], clock: Clock): ProgressTask =
    ProgressTask("download", total, () => clock.now)

  test("a fresh task has made no progress and has not started"):
    val t = task(Some(100L), Clock())
    assert(t.completed == 0L)
    assert(!t.isStarted)
    assert(t.fraction.contains(0.0))
    assert(t.elapsed == Duration.Zero)
    assert(t.rate.isEmpty)
    assert(t.eta.isEmpty)

  test("advance starts the task, and rate and eta follow the clock"):
    val clock = Clock()
    val t     = task(Some(100L), clock)
    t.advance(0L)
    clock.now = 10.seconds
    t.advance(40L)
    assert(t.isStarted)
    assert(t.fraction.contains(0.4))
    assert(t.elapsed == 10.seconds)
    assert(t.rate.contains(4.0))
    assert(t.eta.contains(15.seconds))
    assert(t.remaining.contains(60L))

  test("finish fills a bounded task and freezes elapsed"):
    val clock = Clock()
    val t     = task(Some(10L), clock)
    t.start()
    clock.now = 2.seconds
    t.finish()
    clock.now = 9.seconds
    assert(t.isFinished)
    assert(t.completed == 10L)
    assert(t.elapsed == 2.seconds)
    assert(t.eta.contains(Duration.Zero))

  test("an unbounded task has no fraction, eta or remaining"):
    val clock = Clock()
    val t     = task(None, clock)
    t.start()
    clock.now = 4.seconds
    t.advance(8L)
    assert(t.fraction.isEmpty)
    assert(t.eta.isEmpty)
    assert(t.remaining.isEmpty)
    assert(t.rate.contains(2.0))

  test("completed is clamped into [0, total] and every mutation moves the version"):
    val t      = task(Some(5L), Clock())
    val before = t.version
    t.update(9L)
    assert(t.completed == 5L)
    t.update(-3L)
    assert(t.completed == 0L)
    t.label = "renamed"
    t.total = Some(7L)
    assert(t.version >= before + 4)

  test("the rate follows a sliding window, so a speed change shows up rather than averaging away"):
    val clock = Clock()
    val t     = ProgressTask("copy", Some(10_000L), () => clock.now, rateWindow = Some(10.seconds))
    t.start()
    // a slow first minute: 1 unit a second
    (1 to 60).foreach { s =>
      clock.now = s.seconds
      t.advance(1L)
    }
    // then 100 units a second for ten seconds
    (61 to 70).foreach { s =>
      clock.now = s.seconds
      t.advance(100L)
    }
    assert(t.rate.contains(100.0), "the last ten seconds, not the 15.1/s average since the start")
    assert(t.eta.contains(89.4.seconds))

  test("rateWindow = None keeps the average since the start"):
    val clock = Clock()
    val t     = ProgressTask("copy", Some(10_000L), () => clock.now, rateWindow = None)
    t.start()
    clock.now = 60.seconds
    t.advance(60L)
    clock.now = 70.seconds
    t.advance(1000L)
    assert(t.rate.contains(1060.0 / 70))

  test("a stall longer than the window leaves the rate and the eta unknown"):
    val clock = Clock()
    val t     = ProgressTask("copy", Some(100L), () => clock.now, rateWindow = Some(5.seconds))
    t.advance(0L)
    clock.now = 1.second
    t.advance(10L)
    clock.now = 30.seconds
    assert(t.rate.isEmpty)
    assert(t.eta.isEmpty)

  test("the default window is ten seconds"):
    assert(ProgressTask.DefaultRateWindow == 10.seconds)

  test("RTP-11 a burst retains bounded rate history instead of allocating per report"):
    val clock = Clock()
    val t     = task(None, clock)
    t.start()
    val bean  = java.lang.management.ManagementFactory.getThreadMXBean
      .asInstanceOf[com.sun.management.ThreadMXBean] // scalafix:ok DisableSyntax; JDK test allocation counter
    val thread    = Thread.currentThread().threadId()
    val before    = bean.getThreadAllocatedBytes(thread)
    var n         = 0
    while n < 100000 do
      t.advance()
      n += 1
    val allocated = bean.getThreadAllocatedBytes(thread) - before
    assert(allocated < 16_000_000L, s"100k same-bucket reports allocated $allocated bytes")
    clock.now = 1.second
    assert(t.rate.contains(100000.0))
    clock.now = 30.seconds
    assert(t.rate.isEmpty)
