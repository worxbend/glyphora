package io.worxbend.tui.widgets

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

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
