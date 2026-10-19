package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Text selection from the keyboard: Shift+arrows on a focused, selectable node. */
final class KeyboardSelectionSpec extends AnyFunSuite with PilotFixture:

  private final class NotesApp extends TuiApp:
    override def config: RunnerConfig             = RunnerConfig(mouse = MouseCaptureMode.Buttons)
    def view(using ReactiveScope, Theme): Element =
      column(text("title").length(1), text("hello world\nsecond row").focusable.key("notes").length(2), text("").fill)

  private def driving(body: (Pilot, HeadlessBackend) => Unit): Unit =
    val backend = HeadlessBackend(Size(16, 4))
    val pilot   = startPilot(backend)(NotesApp().runWith(backend)).waitForIdle()
    body(pilot, backend)
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  private def reversed(pilot: Pilot, x: Int, y: Int): Boolean =
    pilot.cellAt(x, y).style.modifiers.hasAny(Modifiers.Reverse)

  test("Shift+Right on a focused text selects from its first cell, one more cell per press, and copies on request"):
    driving { (pilot, backend) =>
      pilot.press("shift+right", "shift+right", "shift+right").waitForIdle()
      assert((0 to 2).forall(reversed(pilot, _, 1)) && !reversed(pilot, 3, 1))
      pilot.press("ctrl+shift+c").waitForIdle()
      pilot.waitUntil("copied")(backend.clipboardContents.isDefined)
      assert(backend.clipboardContents.contains("hel"))
    }

  test("Shift+Down extends the selection a row; Shift+Left takes a cell back"):
    driving { (pilot, backend) =>
      pilot.press("shift+right", "shift+right", "shift+down", "shift+left").waitForIdle()
      pilot.press("ctrl+shift+c").waitForIdle()
      pilot.waitUntil("copied")(backend.clipboardContents.isDefined)
      assert(backend.clipboardContents.contains("hello world\ns"), backend.clipboardContents)
    }

/** A drag-selection that runs past the edge of a `scrollView` scrolls it, so the selection can reach rows below. */
final class SelectionAutoScrollSpec extends AnyFunSuite with PilotFixture:

  test("dragging below the viewport scrolls the view one row per drag, and the selection follows the content"):
    val scroll  = ScrollViewState()
    val lines   = (0 until 20).map(i => f"line$i%02d").mkString("\n")
    val app     = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(mouse = MouseCaptureMode.Buttons)
      def view(using ReactiveScope, Theme): Element = column(scrollView(text(lines), scroll).length(4), text("").fill)
    val backend = HeadlessBackend(Size(12, 6))
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    pilot.mouseDown(0, 1).waitForIdle()
    (1 to 3).foreach { _ =>
      pilot.backend.postEvent(
        io.worxbend.tui.core.Event.Mouse(
          io.worxbend.tui.core.MouseEvent(
            io.worxbend.tui.core.Position(3, 5),
            io.worxbend.tui.core.MouseEventKind.Drag,
            KeyModifiers.None,
          )
        )
      )
      pilot.waitForIdle()
    }
    assert(pilot.readOnRenderThread(scroll.offset) == 3)
    pilot.mouseUp(3, 5).waitForIdle()
    pilot.waitUntil("copied")(backend.clipboardContents.isDefined)
    val copied  = backend.clipboardContents.getOrElse("")
    // only rows on screen are known to a selection, so the copy runs from the top of the viewport to the row that
    // scrolled in last
    assert(copied.linesIterator.toSeq == Seq("line03", "line04", "line05", "line06"), copied)
    pilot.interrupt()
    val _       = pilot.awaitTermination()
