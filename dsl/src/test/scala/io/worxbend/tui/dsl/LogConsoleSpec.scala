package io.worxbend.tui.dsl

import io.worxbend.tui.runtime.{LogLevel, LogRecord, LogRing}
import io.worxbend.tui.testsupport.{BufferAssertions, GoldenFrames}

import org.scalatest.funsuite.AnyFunSuite

import java.time.{Instant, ZoneOffset}

/** `logConsole(ring)`: the tail of a [[LogRing]], filtered by level and logger prefix, drawn through the `Log` widget.
  *
  * Regenerate the fixture with `GLYPHORA_GOLDEN_UPDATE=$PWD/dsl/src/test/resources ./mill dsl.test`.
  */
final class LogConsoleSpec extends AnyFunSuite:

  private val theme = summon[Theme]

  private def at(second: Int): Instant = Instant.parse("2026-10-18T12:00:00Z").plusSeconds(second.toLong)

  private def ring: LogRing =
    val ring = LogRing(100)
    ring.append(LogRecord(at(1), LogLevel.Debug, "app.boot", "reading config"))
    ring.append(LogRecord(at(2), LogLevel.Info, "app.boot", "listening on 8080"))
    ring.append(LogRecord(at(3), LogLevel.Warning, "app.disk", "disk almost full"))
    ring.append(LogRecord(at(4), LogLevel.Error, "net.client", "refused", Some(IllegalStateException("cause"))))
    ring

  private def console: LogConsoleElement = logConsole(ring).zone(ZoneOffset.UTC)

  test("filtered to Warning the console shows only the warning and the error"):
    val frame = BufferAssertions.rendered(console.minLevel(LogLevel.Warning).widget, 80, 4)
    val rows  = BufferAssertions.text(frame).linesIterator.map(_.trim).filter(_.nonEmpty).toSeq
    assert(
      rows == Seq(
        "12:00:03 WARN  app.disk disk almost full",
        "12:00:04 ERROR net.client refused (java.lang.IllegalStateException: cause)",
      )
    )

  test("a logger prefix keeps only records from that logger and its children"):
    val frame = BufferAssertions.rendered(console.loggerPrefix("app").widget, 60, 4)
    val rows  = BufferAssertions.text(frame).linesIterator.map(_.trim).filter(_.nonEmpty).toSeq
    assert(
      rows.map(_.drop(15)) == Seq("app.boot reading config", "app.boot listening on 8080", "app.disk disk almost full")
    )

  test("the console follows the tail when there are more records than rows"):
    val frame = BufferAssertions.rendered(console.widget, 60, 2)
    val rows  = BufferAssertions.text(frame).linesIterator.map(_.trim).toSeq
    assert(rows.head.startsWith("12:00:03"))
    assert(rows(1).startsWith("12:00:04"))

  test("the console renders exactly as recorded"):
    GoldenFrames.assertMatches("log-console", BufferAssertions.rendered(console.widget, 60, 4))

  test("the level column takes the theme's warning and error styles"):
    val frame = BufferAssertions.rendered(console.widget, 60, 4)
    assert(frame.get(9, 2).style == theme.warning) // "WARN" on the third row
    assert(frame.get(9, 3).style == theme.error)   // "ERROR" on the fourth
    assert(frame.get(0, 0).style == theme.muted) // timestamps are muted

  test("an empty ring draws nothing"):
    val frame = BufferAssertions.rendered(logConsole(LogRing(4)).widget, 20, 2)
    assert(BufferAssertions.text(frame).trim.isEmpty)
