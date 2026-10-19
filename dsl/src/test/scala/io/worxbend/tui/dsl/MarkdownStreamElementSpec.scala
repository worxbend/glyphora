package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** `markdownStream(stream)` and `markdownStream(stream, scroll)`: Markdown that is still arriving, and a view of it
  * that stays pinned to the newest row until the user scrolls away.
  */
final class MarkdownStreamElementSpec extends AnyFunSuite with PilotFixture:

  test("markdownStream renders the sanitized text in the theme's markdown palette and measures it"):
    val stream  = MarkdownStream("some **bold")
    val element = markdownStream(stream)
    assert(trimmedLines(rendered(element.widget, 20, 1)) == Seq("some bold"))
    assert(element.intrinsicHeight(20).contains(1))
    assert(element.debugName == "markdownStream")

  test("a pinned markdownStream follows new rows, stops when scrolled up, and resumes at End"):
    val backend = HeadlessBackend(Size(20, 3))
    val stream  = MarkdownStream((0 until 6).map(n => s"line $n").mkString("\n"))
    val scroll  = ScrollViewState()
    var next    = 6
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("a", "append") {
          stream.append(s"\nline $next")
          next += 1
        },
        binding("q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = markdownStream(stream, scroll)
    val pilot   = startPilot(backend) { app.runWith(backend) }

    def rows: Seq[String] = pilot.screenLines.map(_.dropRight(1).trim) // the last column is the scrollbar

    pilot.waitForIdle()
    assert(rows == Seq("line 3", "line 4", "line 5"))
    pilot.press("a").waitForIdle()
    assert(rows == Seq("line 4", "line 5", "line 6"))
    pilot.pressKey(KeyCode.Up).pressKey(KeyCode.Up).waitForIdle()
    assert(rows == Seq("line 2", "line 3", "line 4"))
    pilot.press("a").waitForIdle()
    assert(rows == Seq("line 2", "line 3", "line 4")) // scrolled away: the view holds still
    pilot.pressKey(KeyCode.End).waitForIdle()
    pilot.press("a").waitForIdle()
    assert(rows == Seq("line 6", "line 7", "line 8"))
    pilot.press("q")
    assert(pilot.awaitTermination())

  test("markdownStream inside a scrollView pins the view to its newest row, holds when scrolled up, resumes at End"):
    val backend = HeadlessBackend(Size(20, 3))
    val stream  = MarkdownStream((0 until 6).map(n => s"line $n").mkString("\n"))
    val scroll  = ScrollViewState()
    var next    = 6
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("a", "append") {
          stream.append(s"\nline $next")
          next += 1
        },
        binding("q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = scrollView(markdownStream(stream), scroll)
    val pilot   = startPilot(backend) { app.runWith(backend) }

    def rows: Seq[String] = pilot.screenLines.map(_.dropRight(1).trim) // the last column is the scrollbar

    pilot.waitForIdle()
    assert(rows == Seq("line 3", "line 4", "line 5"), "the first frame starts at the tail")
    pilot.press("a").waitForIdle()
    assert(rows == Seq("line 4", "line 5", "line 6"))
    pilot.pressKey(KeyCode.Up).pressKey(KeyCode.Up).waitForIdle()
    assert(rows == Seq("line 2", "line 3", "line 4"))
    pilot.press("a").waitForIdle()
    assert(rows == Seq("line 2", "line 3", "line 4")) // scrolled away: the view holds still
    pilot.pressKey(KeyCode.End).waitForIdle()
    pilot.press("a").waitForIdle()
    assert(rows == Seq("line 6", "line 7", "line 8"))
    pilot.press("q")
    assert(pilot.awaitTermination())
