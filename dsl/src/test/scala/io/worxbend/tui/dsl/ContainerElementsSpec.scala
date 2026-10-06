package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}
import io.worxbend.tui.widgets.ScrollViewState

import org.scalatest.funsuite.AnyFunSuite

final class ContainerElementsSpec extends AnyFunSuite with PilotFixture:

  /** These tests build elements outside a running app, so nothing needs subscribing. */
  private given ReactiveScope = ReactiveScope.untracked

  test("layers paint later children over earlier ones"):
    val stacked = layers(text("bottom layer"), text("top"))
    assert(trimmedLines(rendered(stacked.widget, 14, 1)) == Seq("toptom layer"))

  test("tabbedContent renders the tab row and only the selected page"):
    val selected = Signal(1)
    val element  = tabbedContent("One" -> text("page one"), "Two" -> text("page two"))(selected)
    val lines    = trimmedLines(rendered(element.widget, 20, 3))
    assert(lines.head.startsWith("One │ Two"))
    assert(lines(1) == "page two")
    assert(!lines.exists(_.contains("page one")))

  test("collapsible hides its body and excludes it from children when closed"):
    val open     = Signal(false)
    val element  = collapsible("Details", open)(text("body text"))
    assert(trimmedLines(rendered(element.widget, 20, 3)) == Seq("▸ Details", "", ""))
    assert(element.children.isEmpty)
    open.set(true)
    val reopened = collapsible("Details", open)(text("body text"))
    assert(trimmedLines(rendered(reopened.widget, 20, 3)).take(2) == Seq("▾ Details", "body text"))
    assert(reopened.children.size == 1)

  test("splitPane divides by the percent signal"):
    val split   = Signal(50)
    val element = splitPane(text("LL").fill, text("RR").fill, split)
    val lines   = trimmedLines(rendered(element.widget, 11, 1))
    assert(lines.head.startsWith("LL"))
    assert(lines.head.contains("RR"))

  test("a focused scrollView scrolls with the arrow keys end to end"):
    val backend = HeadlessBackend(Size(12, 3))
    val state   = ScrollViewState()
    val content = column((0 until 8).map(n => text(s"row $n").fill)*)
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = scrollView(content, contentHeight = 8, state)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenLines.head.startsWith("row 0"))
    pilot.pressKey(KeyCode.Down).pressKey(KeyCode.Down).waitForIdle()
    assert(pilot.screenLines.head.startsWith("row 2"))
    pilot.pressKey(KeyCode.PageDown).waitForIdle()
    assert(state.offset == 5)
    pilot.pressKey(KeyCode.Char('q'))
    assert(pilot.awaitTermination())

  test("a focused tabbedContent switches pages with left/right"):
    val backend  = HeadlessBackend(Size(20, 3))
    val selected = Signal(0)
    val app      = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        // no explicit `selected.get`: the factory reads the signal tracked, so switching pages re-renders on its own
        tabbedContent("One" -> text("page one"), "Two" -> text("page two"))(selected)
    val pilot    = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenText.contains("page one"))
    pilot.pressKey(KeyCode.Right).waitForIdle()
    assert(pilot.screenText.contains("page two"))
    pilot.pressKey(KeyCode.Char('q'))
    assert(pilot.awaitTermination())
