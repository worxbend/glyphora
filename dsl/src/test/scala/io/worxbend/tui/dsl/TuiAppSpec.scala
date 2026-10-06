package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

final class TuiAppSpec extends AnyFunSuite with PilotFixture:

  /** A miniature counter app exercising the full reactive path: Signal → view → key handler → redraw. */
  private final class CounterApp extends TuiApp:
    val count                                     = Signal(0)
    def view(using ReactiveScope, Theme): Element =
      panel("Counter")(
        text(s"count: ${count.get}")
      ).onKeyEvent {
        case KeyEvent(KeyCode.Char('+'), _) =>
          count.update(_ + 1)
          true
        case KeyEvent(KeyCode.Char('q'), _) =>
          quit()
          true
        case _                              => false
      }

  /** The counter app exactly as the README and getting-started guide document it: `+`/`-`/`q` declared through
    * [[KeyBindings]] rather than an element handler, so the `+` spec is parsed at declaration time.
    */
  private final class DocumentedCounterApp extends TuiApp:
    val count = Signal(0)

    override def bindings: KeyBindings = KeyBindings(
      binding("+", "increment")(count.update(_ + 1)),
      binding("-", "decrement")(count.update(_ - 1)),
      binding("q", "quit")(quit()),
    )

    def view(using ReactiveScope, Theme): Element =
      scaffold(statusBar = Some(statusBar(bindings))) {
        centered(34, 7) {
          panel("Counter")(
            text(s"Count: ${count.get}").bold.fg(Color.Cyan)
          ).rounded
        }
      }

  test("the documented counter app binds '+' and '-' through KeyBindings"):
    val backend = HeadlessBackend(Size(40, 12))
    val app     = DocumentedCounterApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.press("+", "+", "-").waitForIdle()
    assert(app.count.peek == 1)
    pilot.press("q")
    assert(pilot.awaitTermination())

  test("a TuiApp renders its view and reacts to signal updates from key handlers"):
    val backend = HeadlessBackend(Size(20, 4))
    val app     = CounterApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenLines(1).startsWith("│count: 0"))
    pilot.press("+", "+").waitForIdle()
    assert(pilot.screenLines(1).startsWith("│count: 2"))
    pilot.press("q")
    assert(pilot.awaitTermination())

  test("an unconsumed Ctrl+C quits by default"):
    val backend = HeadlessBackend(Size(20, 4))
    val app     = CounterApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.press("ctrl+c")
    assert(pilot.awaitTermination())

  test("an event that touches no signal the view read schedules no redraw"):
    val backend     = HeadlessBackend(Size(20, 4))
    val app         = CounterApp()
    val pilot       = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    val drawsBefore = backend.drawCount
    pilot.press("x").waitForIdle()
    assert(backend.drawCount == drawsBefore)
    pilot.press("q")
    assert(pilot.awaitTermination())
