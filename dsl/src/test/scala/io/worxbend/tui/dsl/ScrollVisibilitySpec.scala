package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.widgets.ScrollViewState

import org.scalatest.funsuite.AnyFunSuite

/** A `scrollView` keeps the focused child, and any child marked `.ensureVisible`, on screen by itself. */
final class ScrollVisibilitySpec extends AnyFunSuite with PilotFixture:

  private val items = (0 until 30).map(n => f"item $n%02d")

  private def app(content: => Element): TuiApp =
    new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = content

  test("tabbing through 30 focusables in a 10-row viewport keeps the focused one visible"):
    val backend = HeadlessBackend(Size(20, 10))
    val state   = ScrollViewState()
    val content = column(items.map(label => button(label)(()))*)
    val pilot   = startPilot(backend)(app(scrollView(content, state)).runWith(backend))
    pilot.waitForIdle()
    // the scroll view is itself the first tab stop, so 15 presses land on the fifteenth button: "item 14"
    (1 to 15).foreach(_ => pilot.press("tab").waitForIdle())
    assert(pilot.screenText.contains("item 14"), pilot.screenText)
    assert(state.offset == 5, "scrolled just far enough: row 14 is the last of rows 5..14")
    // and back up again past the top of the window
    (1 to 12).foreach(_ => pilot.press("shift+tab").waitForIdle())
    assert(pilot.screenText.contains("item 02"), pilot.screenText)
    assert(state.offset == 2)
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())

  test("followFocus(false) leaves the offset where the app put it"):
    val backend = HeadlessBackend(Size(20, 10))
    val state   = ScrollViewState()
    val content = column(items.map(label => button(label)(()))*)
    val pilot   = startPilot(backend)(app(scrollView(content, state).followFocus(false)).runWith(backend))
    pilot.waitForIdle()
    (1 to 15).foreach(_ => pilot.press("tab").waitForIdle())
    assert(state.offset == 0)
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())

  test("an element marked ensureVisible is scrolled into view once, and the reader can still scroll away"):
    val backend = HeadlessBackend(Size(20, 10))
    val state   = ScrollViewState()
    val content =
      column(items.zipWithIndex.map((label, n) => if n == 20 then text(label).ensureVisible else text(label))*)
    val pilot   = startPilot(backend)(app(scrollView(content, state)).runWith(backend))
    pilot.waitForIdle()
    assert(pilot.screenText.contains("item 20"), pilot.screenText)
    assert(state.offset == 11)
    // focus is on the scroll view itself, so Home scrolls it; the mark must not drag the view straight back
    pilot.press("home").waitForIdle()
    assert(state.offset == 0)
    assert(!pilot.screenText.contains("item 20"))
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())
