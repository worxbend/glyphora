package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Event, MouseEvent, MouseEventKind, Position, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

final class MouseGestureRegressionSpec extends AnyFunSuite with PilotFixture:

  private def start(app: TuiApp): Pilot =
    val backend = HeadlessBackend(Size(20, 4))
    startPilot(backend)(app.runWith(backend, () => 0L)).waitForIdle()

  private def move(pilot: Pilot, x: Int, y: Int): Unit =
    pilot.backend.postEvent(Event.Mouse(MouseEvent(Position(x, y), MouseEventKind.Drag, KeyModifiers.None)))
    val _ = pilot.waitForIdle()

  test("log append and internal scroll retire selection rather than copying text that moved under it"):
    val state = LogState()
    (0 until 10).foreach(i => state.append(s"line $i"))
    val app   = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig.Default.withMouseCapture
      def view(using ReactiveScope, Theme): Element = log(state)
    val pilot = start(app)
    pilot.drag(Position(0, 0), Position(5, 0)).waitForIdle()
    pilot.waitUntil("line 6 copied")(pilot.backend.clipboardContents.contains("line 6"))
    pilot.readOnRenderThread(state.append("line 10"))
    pilot.waitForIdle().press("ctrl+shift+c").waitForIdle()
    assert(pilot.backend.clipboardContents.contains("line 6"))
    assert(!pilot.cellAt(0, 0).style.modifiers.hasAny(Modifiers.Reverse))
    pilot.drag(Position(0, 0), Position(5, 0)).waitForIdle()
    pilot.waitUntil("line 7 copied")(pilot.backend.clipboardContents.contains("line 7"))
    pilot.press("up").waitForIdle().press("ctrl+shift+c").waitForIdle()
    assert(pilot.backend.clipboardContents.contains("line 7"))

  test("a following markdown stream clears stale cell selection after an append"):
    val stream   = MarkdownStream((0 until 10).map(i => s"line $i").mkString("\n\n"))
    val scroll   = ScrollViewState()
    val app      = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig.Default.withMouseCapture
      def view(using ReactiveScope, Theme): Element = markdownStream(stream, scroll)
    val pilot    = start(app)
    val row      = pilot.screenLines.indexWhere(_.contains("line"))
    assert(row >= 0)
    pilot.drag(Position(0, row), Position(5, row)).waitForIdle()
    pilot.waitUntil("stream selection copied")(pilot.backend.clipboardContents.isDefined)
    val original = pilot.backend.clipboardContents
    pilot.readOnRenderThread(stream.append("\n\nline 10"))
    pilot.waitForIdle().press("ctrl+shift+c").waitForIdle()
    assert(pilot.backend.clipboardContents == original)
    assert(!pilot.cellAt(0, row).style.modifiers.hasAny(Modifiers.Reverse))

  test("a captured slider inside a draggable container keeps its drag"):
    val value = Signal(0)
    val app   = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        column(row(slider(value).length(12), text("").fill).length(1).draggable("card"), text("").fill)
    val pilot = start(app)
    pilot.mouseDown(1, 0).waitForIdle()
    move(pilot, 17, 0)
    pilot.mouseUp(17, 0).waitForIdle()
    assert(value.peek == 100)

  test("the first new press after a lost release is routed normally, not spent cancelling the stale drag"):
    var clicks = 0
    val app    = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        column(text("card").draggable("card").length(1), button("go")(clicks += 1).length(1), text("").fill)
    val pilot  = start(app)
    pilot.mouseDown(1, 0).waitForIdle()
    move(pilot, 5, 3)
    pilot.click(9, 1).waitForIdle()
    assert(pilot.readOnRenderThread(clicks) == 1)
    assert(!pilot.screenLines(3).contains("card"))

  test("focus loss cancels drag and pointer capture and clears pressed cues"):
    var drops = 0
    val value = Signal(0)
    val app   = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        column(
          text("card").draggable("card").length(1),
          text("bin").dropTarget[String](_ => true)(_ => drops += 1).length(1),
          row(slider(value).length(12).pressedStyle(Style.Default.underline), text("").fill).length(1),
          text("").fill,
        )
    val pilot = start(app)
    pilot.mouseDown(1, 0).waitForIdle()
    move(pilot, 5, 3)
    pilot.backend.postEvent(Event.FocusLost)
    pilot.waitForIdle()
    assert(!pilot.screenLines(3).contains("card"))
    pilot.mouseUp(1, 1).waitForIdle()
    assert(pilot.readOnRenderThread(drops) == 0)
    pilot.mouseDown(1, 2).waitForIdle()
    assert(pilot.cellAt(1, 2).style.modifiers.hasAny(Modifiers.Underline))
    pilot.backend.postEvent(Event.FocusLost)
    pilot.waitForIdle()
    assert(!pilot.cellAt(1, 2).style.modifiers.hasAny(Modifiers.Underline))
    move(pilot, 17, 2)
    assert(value.peek == 0, "the old slider must not retain capture")

  test("plain Escape and a rebound close-modal sequence both cancel a drag without dropping"):
    var drops = 0
    val app   = new TuiApp:
      override def frameworkKeys: FrameworkKeys     = FrameworkKeys.default.copy(closeModal = keyseq"f2 x")
      def view(using ReactiveScope, Theme): Element =
        column(
          text("card").draggable("card").length(1),
          text("bin").dropTarget[String](_ => true)(_ => drops += 1).length(1),
          text("").fill,
        )
    val pilot = start(app)
    Seq(Seq("esc"), Seq("f2", "x"), Seq("f2", "esc")).foreach { keys =>
      pilot.mouseDown(1, 0).waitForIdle()
      move(pilot, 5, 3)
      pilot.press(keys*).waitForIdle()
      assert(!pilot.screenLines(3).contains("card"))
      pilot.mouseUp(1, 1).waitForIdle()
      assert(pilot.readOnRenderThread(drops) == 0)
    }
