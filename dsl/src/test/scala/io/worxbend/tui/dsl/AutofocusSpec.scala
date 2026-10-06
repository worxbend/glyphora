package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.widgets.TextInputState

import org.scalatest.funsuite.AnyFunSuite

/** Focus used to start on whichever focusable came first in the tab order, with no supported way for a view to say
  * otherwise. `.autofocus` says it. The interesting part is not that it takes focus — it is that it takes focus exactly
  * once, so Tab still works afterwards.
  */
final class AutofocusSpec extends AnyFunSuite with PilotFixture:

  test("autofocus starts the keyboard on the element that asked, not on the first one"):
    val backend = HeadlessBackend(Size(30, 6))
    val first   = TextInputState()
    val second  = TextInputState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        column(input(first), input(second).autofocus)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("x").waitForIdle()
    assert(second.value == "x")
    assert(first.value == "")
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("autofocus does not take focus back after the user tabs away"):
    val backend = HeadlessBackend(Size(30, 6))
    val first   = TextInputState()
    val second  = TextInputState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        column(input(first).autofocus.key("first"), input(second).key("second"))
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("a").waitForIdle()
    assert(first.value == "a")
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    pilot.typeText("b").waitForIdle()
    // Every render still carries the request; if it were honoured each frame this would read "ab" and "".
    assert(first.value == "a")
    assert(second.value == "b")
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("an autofocusing element that appears later claims the keyboard when it does"):
    val backend = HeadlessBackend(Size(30, 6))
    val body    = TextInputState()
    val search  = TextInputState()
    val open    = io.worxbend.tui.runtime.Signal(false)
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+f", "open the search box")(open.set(true)),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element =
        val box = if open.get then Seq(input(search).autofocus.key("search")) else Seq.empty
        column((input(body).key("body") +: box)*)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("a").waitForIdle()
    assert(body.value == "a")
    pilot.pressKey(KeyCode.Char('f'), KeyModifiers.Ctrl).waitForIdle()
    pilot.typeText("b").waitForIdle()
    assert(search.value == "b", "the search box that just appeared should hold the keyboard")
    assert(body.value == "a")
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("autofocus opts a non-interactive element into the tab order the way focusable does"):
    val element = text("read me").autofocus
    assert(element.props.focusable && element.props.autofocus)
    assert(FocusPass.autofocusRequest(column(text("a"), element)).contains(AutofocusRequest(0, None)))

  test("the first request in the tab order wins when two elements ask"):
    val tree = column(text("a").autofocus.key("a"), text("b").autofocus.key("b"))
    assert(FocusPass.autofocusRequest(tree).contains(AutofocusRequest(0, Some("a"))))

  test("a request counts only focusables, so its index is a tab-order position"):
    val tree  = column(text("plain"), text("also plain"), text("wants focus").autofocus.key("k"))
    assert(FocusPass.autofocusRequest(tree).contains(AutofocusRequest(0, Some("k"))))
    val mixed = column(text("first").focusable, text("wants focus").autofocus.key("k"))
    assert(FocusPass.autofocusRequest(mixed).contains(AutofocusRequest(1, Some("k"))))

  test("a tree with no request answers None"):
    assert(FocusPass.autofocusRequest(column(text("a"), text("b").focusable)).isEmpty)
