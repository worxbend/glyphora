package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.{LogLevel, LogRecord, LogRing}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions

import org.scalatest.funsuite.AnyFunSuite

import java.time.{Instant, ZoneOffset}

/** `logConsole(ring, state)`: the console with a caller-owned [[LogConsoleState]] — scroll back through the ring,
  * detach from the tail, and toggle following it again.
  */
final class LogConsoleScrollSpec extends AnyFunSuite with PilotFixture:

  private def numbered(count: Int): LogRing =
    val ring = LogRing(100)
    (1 to count).foreach { i =>
      ring.append(LogRecord(Instant.parse("2026-10-18T12:00:00Z"), LogLevel.Info, "app", s"record $i"))
    }
    ring

  private def messages(element: Element, height: Int): Seq[String] =
    BufferAssertions
      .text(BufferAssertions.rendered(element.widget, 40, height))
      .linesIterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.split(" ").takeRight(2).mkString(" "))
      .toSeq

  test("a state that was never scrolled follows the tail, exactly as the stateless console does"):
    val state = LogConsoleState()
    assert(state.following)
    assert(
      messages(logConsole(numbered(10), state).zone(ZoneOffset.UTC), 3) == Seq("record 8", "record 9", "record 10")
    )

  test("scrolling up detaches from the tail and new records no longer move the view"):
    val ring    = numbered(10)
    val state   = LogConsoleState()
    val console = logConsole(ring, state).zone(ZoneOffset.UTC)
    val _       = messages(console, 3)
    state.scrollUp(2)
    assert(!state.following)
    assert(messages(console, 3) == Seq("record 6", "record 7", "record 8"))
    ring.log(LogLevel.Info, "app", "record 11")
    assert(messages(console, 3) == Seq("record 6", "record 7", "record 8"))

  test("scrolling back down to the end re-attaches, and toggleFollow flips following by hand"):
    val ring    = numbered(10)
    val state   = LogConsoleState()
    val console = logConsole(ring, state).zone(ZoneOffset.UTC)
    val _       = messages(console, 3)
    state.scrollUp(5)
    val _       = messages(console, 3)
    state.scrollDown(5)
    assert(state.following)
    state.toggleFollow()
    assert(!state.following)
    ring.log(LogLevel.Info, "app", "record 11")
    assert(messages(console, 3) == Seq("record 8", "record 9", "record 10"))
    state.toggleFollow()
    assert(messages(console, 3) == Seq("record 9", "record 10", "record 11"))

  test("scrolling never moves past the first record"):
    val state   = LogConsoleState()
    val console = logConsole(numbered(5), state).zone(ZoneOffset.UTC)
    val _       = messages(console, 3)
    state.scrollUp(50)
    assert(messages(console, 3) == Seq("record 1", "record 2", "record 3"))

  test("focused, Up scrolls back and End follows the tail again"):
    val ring    = numbered(10)
    val state   = LogConsoleState()
    val backend = HeadlessBackend(Size(40, 3))
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element = logConsole(ring, state).zone(ZoneOffset.UTC).key("console")
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    assert(pilot.screenLines.last.trim.endsWith("record 10"))
    pilot.pressKey(KeyCode.Up).waitUntil("scrolled back")(pilot.screenLines.last.trim.endsWith("record 9"))
    assert(!state.following)
    pilot.pressKey(KeyCode.End).waitUntil("following again")(pilot.screenLines.last.trim.endsWith("record 10"))
    assert(state.following)
    pilot.interrupt()
    val _       = pilot.awaitTermination()
