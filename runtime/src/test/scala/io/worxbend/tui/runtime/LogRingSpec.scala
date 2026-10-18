package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import java.util.logging.{Level, Logger}

final class LogRingSpec extends AnyFunSuite:

  private def record(message: String, level: LogLevel = LogLevel.Info): LogRecord =
    LogRecord(Instant.EPOCH, level, "test", message)

  test("appending past capacity drops the oldest record"):
    val ring = LogRing(capacity = 3)
    Seq("a", "b", "c", "d").foreach(message => ring.append(record(message)))
    assert(ring.snapshot.map(_.message) == Vector("b", "c", "d"))
    assert(ring.size == 3)

  test("a non-positive capacity is refused"):
    assertThrows[IllegalArgumentException](LogRing(0))

  test("clear empties the ring"):
    val ring = LogRing(2)
    ring.append(record("a"))
    ring.clear()
    assert(ring.snapshot.isEmpty)

  test("levels order from Trace to Error and answer atLeast"):
    assert(LogLevel.Error.atLeast(LogLevel.Warning))
    assert(LogLevel.Warning.atLeast(LogLevel.Warning))
    assert(!LogLevel.Info.atLeast(LogLevel.Warning))
    assert(
      LogLevel.values.toSeq == Seq(LogLevel.Trace, LogLevel.Debug, LogLevel.Info, LogLevel.Warning, LogLevel.Error)
    )

  test("JulBridge delivers a java.util.logging warning into the ring, with its logger name and throwable"):
    val ring   = LogRing(10)
    // a logger of its own rather than the root, so the handler is attached to and removed from exactly this test's
    // logger and a sibling suite running in the same JVM cannot see it
    val logger = Logger.getLogger("io.worxbend.tui.runtime.LogRingSpec.a.b")
    logger.setUseParentHandlers(false) // keep the root ConsoleHandler from echoing these into the test output
    val bridge = JulBridge.attach(ring, logger)
    try
      logger.warning("disk almost full")
      logger.log(Level.SEVERE, "boom", IllegalStateException("cause"))
      logger.fine("ignored: below the logger's own level")
      val records = ring.snapshot
      assert(records.map(_.level) == Vector(LogLevel.Warning, LogLevel.Error))
      assert(records.map(_.message) == Vector("disk almost full", "boom"))
      assert(records.forall(_.logger == "io.worxbend.tui.runtime.LogRingSpec.a.b"))
      assert(records(1).throwable.exists(_.getMessage == "cause"))
    finally bridge.cancel()
    logger.warning("after detach")
    assert(ring.size == 2)

  test("JulBridge folds the seven JUL levels onto the five LogLevels"):
    assert(JulBridge.levelOf(Level.FINEST) == LogLevel.Trace)
    assert(JulBridge.levelOf(Level.FINER) == LogLevel.Trace)
    assert(JulBridge.levelOf(Level.FINE) == LogLevel.Debug)
    assert(JulBridge.levelOf(Level.CONFIG) == LogLevel.Debug)
    assert(JulBridge.levelOf(Level.INFO) == LogLevel.Info)
    assert(JulBridge.levelOf(Level.WARNING) == LogLevel.Warning)
    assert(JulBridge.levelOf(Level.SEVERE) == LogLevel.Error)

  test("JulBridge formats parameterised messages the way JUL would"):
    val ring   = LogRing(10)
    val logger = Logger.getLogger("io.worxbend.tui.runtime.LogRingSpec.params")
    logger.setUseParentHandlers(false)
    val bridge = JulBridge.attach(ring, logger)
    try
      logger.log(Level.WARNING, "{0} of {1} failed", Array[AnyRef](Integer.valueOf(2), Integer.valueOf(5)))
      assert(ring.snapshot.map(_.message) == Vector("2 of 5 failed"))
    finally bridge.cancel()
