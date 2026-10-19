package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Position, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.ScrollViewState

import org.scalatest.funsuite.AnyFunSuite

/** A mouse selection belongs to the node it was made on and to a place in that node's content, not to the screen: it
  * stays on its node when the tree around it changes shape, and travels with the content when a scroll view moves.
  */
final class SelectionAnchorSpec extends AnyFunSuite with PilotFixture:

  private def driving(app: TuiApp, size: Size)(body: (Pilot, HeadlessBackend) => Unit): Unit =
    val backend = HeadlessBackend(size)
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    body(pilot, backend)
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  private def reversed(pilot: Pilot, x: Int, y: Int): Boolean =
    pilot.cellAt(x, y).style.modifiers.hasAny(Modifiers.Reverse)

  test("a selection stays on its node when nodes are added before it elsewhere in the tree"):
    val extra = Signal(0)
    val app   = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(mouse = MouseCaptureMode.Buttons)
      def view(using ReactiveScope, Theme): Element =
        row(
          column((0 until 1 + extra.get).map(n => text(s"$n").length(1))*).length(4),
          text("hello world").fill,
        )
    driving(app, Size(16, 4)) { (pilot, backend) =>
      pilot.drag(Position(6, 0), Position(10, 0)).waitForIdle()
      pilot.waitUntil("the release copied")(backend.clipboardContents.contains("llo w"))
      assert((6 to 10).forall(reversed(pilot, _, 0)))
      pilot.readOnRenderThread(extra.set(3))
      pilot.waitForIdle()
      assert(pilot.screenLines(3).startsWith("3"), "the left column grew")
      assert((6 to 10).forall(reversed(pilot, _, 0)), "the highlight is still on hello world")
      assert(!(0 until 4).exists(reversed(pilot, _, 1)))
    }

  test("a selection inside a scroll view moves with the content when the view scrolls"):
    val state = ScrollViewState()
    val app   = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(mouse = MouseCaptureMode.Buttons)
      def view(using ReactiveScope, Theme): Element =
        scrollView(column((0 until 30).map(n => text(f"line $n%02d").length(1))*), state).followFocus(false)
    driving(app, Size(16, 6)) { (pilot, backend) =>
      pilot.drag(Position(0, 3), Position(3, 3)).waitForIdle()
      pilot.waitUntil("the release copied")(backend.clipboardContents.contains("line"))
      assert((0 to 3).forall(reversed(pilot, _, 3)))
      pilot.readOnRenderThread(state.scrollDown(2))
      pilot.waitForIdle()
      assert(pilot.screenLines(1).startsWith("line 03"))
      assert((0 to 3).forall(reversed(pilot, _, 1)), "the highlight moved up with line 03")
      assert(!(0 to 3).exists(reversed(pilot, _, 3)), "and left the row line 05 now occupies")
    }
