package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets as w

import org.scalatest.funsuite.AnyFunSuite

/** The jump half of a list's keyboard vocabulary — Home, End, PageUp and PageDown — driven end to end through
  * [[Pilot]], because key routing is the half of the feature the user actually touches. Also pins the bottom-anchored
  * list, whose keys must behave exactly as they do the other way up.
  */
final class ListNavigationSpec extends AnyFunSuite with PilotFixture:

  private val Width  = 20
  private val Height = 5

  private val items: Seq[String] = (0 until 50).map(index => s"item-$index")

  private def startApp(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(Size(Width, Height))
    val testApp = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("End selects the last item and scrolls it into view, Home comes back"):
    val state = w.ListState()
    val pilot = startApp(list(items, state))
    pilot.pressKey(KeyCode.End)
    val _     = pilot.waitForIdle()
    assert(state.selected.contains(49))
    assert(pilot.screenLines.exists(_.contains("> item-49")))
    pilot.pressKey(KeyCode.Home)
    val _     = pilot.waitForIdle()
    assert(state.selected.contains(0))
    assert(pilot.screenLines.exists(_.contains("> item-0")))
    quitApp(pilot)

  test("PageDown and PageUp move the selection ten rows at a time"):
    val state = w.ListState()
    val pilot = startApp(list(items, state))
    pilot.pressKey(KeyCode.PageDown)
    val _     = pilot.waitForIdle()
    assert(state.selected.contains(9)) // from nothing selected, ten places past the sentinel before the first row
    pilot.pressKey(KeyCode.PageDown)
    val _ = pilot.waitForIdle()
    assert(state.selected.contains(19))
    pilot.pressKey(KeyCode.PageUp)
    val _ = pilot.waitForIdle()
    assert(state.selected.contains(9))
    quitApp(pilot)

  test("Up and Down still move one row, so the jump keys did not displace them"):
    val state = w.ListState()
    val pilot = startApp(list(items, state))
    pilot.pressKey(KeyCode.Down)
    val _     = pilot.waitForIdle()
    pilot.pressKey(KeyCode.Down)
    val _     = pilot.waitForIdle()
    assert(state.selected.contains(1))
    quitApp(pilot)

  test("a bottom-anchored list draws against the floor and answers the same keys"):
    val state = w.ListState()
    val pilot = startApp(list(Seq("first", "second"), state).bottomToTop)
    // two items in a five-row screen: the empty rows are above them, and item 0 is on the bottom row
    assert(pilot.screenLines.last.contains("first"))
    assert(pilot.screenLines.head.trim.isEmpty)
    pilot.pressKey(KeyCode.End)
    val _     = pilot.waitForIdle()
    assert(state.selected.contains(1))
    quitApp(pilot)

  test("bottomToTop rebuilds the node rather than mutating it"):
    val state  = w.ListState()
    val plain  = Element.list(Seq("a"), state)
    val flowed = plain.bottomToTop
    assert(plain.direction == w.ListDirection.TopToBottom)
    assert(flowed.direction == w.ListDirection.BottomToTop)
