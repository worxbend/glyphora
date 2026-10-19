package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Modifiers, MouseEvent, MouseEventKind, Position, Rect, Size, Style}
import io.worxbend.tui.terminal.{HeadlessBackend, MouseCaptureMode}
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Hover and pressed states, pointer capture, synthesised click counts and the local coordinates `onMouse` hands a
  * handler — everything the host works out about the pointer before an element sees an event.
  */
final class PointerInteractionSpec extends AnyFunSuite with PilotFixture:

  private def driving(app: TuiApp, size: Size = Size(30, 4))(body: (Pilot, HeadlessBackend) => Unit): Unit =
    val backend = HeadlessBackend(size)
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    body(pilot, backend)
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  private def bold(pilot: Pilot, x: Int, y: Int): Boolean = pilot.cellAt(x, y).style.modifiers.hasAny(Modifiers.Bold)

  /** `[ Go ]` centred on row 0 of a 30-wide frame starts at column 12; rows 1-3 are blank text. */
  private final class CueApp extends TuiApp:
    def view(using ReactiveScope, Theme): Element =
      column(
        button("Go")(()).key("go").hoverStyle(Style.Default.bold).pressedStyle(Style.Default.underline).length(1),
        text("").fill,
      )

  test("moving the pointer over a button marks it hovered and draws its hover style; moving away clears both"):
    val app = CueApp()
    driving(app) { (pilot, _) =>
      assert(!bold(pilot, 12, 0))
      pilot.mouseMove(13, 0).waitForIdle()
      assert(bold(pilot, 12, 0), pilot.cellAt(12, 0).toString)
      assert(pilot.readOnRenderThread(ElementTreeSnapshot.render(app)).contains("hovered"))
      pilot.mouseMove(2, 2).waitForIdle()
      assert(!bold(pilot, 12, 0))
      assert(!pilot.readOnRenderThread(ElementTreeSnapshot.render(app)).contains("hovered"))
    }

  test("a button is pressed from mouse-down until mouse-up, wherever the release lands"):
    val app = CueApp()
    driving(app) { (pilot, _) =>
      pilot.mouseDown(13, 0).waitForIdle()
      assert(pilot.cellAt(12, 0).style.modifiers.hasAny(Modifiers.Underline))
      pilot.mouseUp(2, 3).waitForIdle()
      assert(!pilot.cellAt(12, 0).style.modifiers.hasAny(Modifiers.Underline))
    }

  test("a slider drag keeps going past the slider's edge and clamps at max, because the press captured the pointer"):
    val value = Signal(0)
    val app   = new TuiApp:
      def view(using ReactiveScope, Theme): Element = row(slider(value).length(12), text("").fill)
    driving(app) { (pilot, _) =>
      pilot.mouseDown(1, 0).waitForIdle()
      assert(value.peek == 0)
      pilot.backend.postEvent(
        io.worxbend.tui.core.Event.Mouse(MouseEvent(Position(17, 0), MouseEventKind.Drag, KeyModifiers.None))
      )
      pilot.waitForIdle()
      assert(value.peek == 100)
      pilot.mouseUp(17, 0).waitForIdle()
      // released: a drag with no capture outside the slider reaches nothing
      pilot.backend.postEvent(
        io.worxbend.tui.core.Event.Mouse(MouseEvent(Position(20, 0), MouseEventKind.Drag, KeyModifiers.None))
      )
      pilot.waitForIdle()
      assert(value.peek == 100)
    }

  test("a second press on the same cell is a double click, and onMouse sees local coordinates"):
    var seen = Vector.empty[MouseContext]
    val app  = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        column(
          text("top").length(1),
          row(text("|").length(3), text("hit").onMouse { ctx => seen :+= ctx; true }.fill).length(1),
          text("").fill,
        )
    driving(app) { (pilot, _) =>
      pilot.click(3, 1).click(3, 1).waitForIdle()
      val presses = seen.filter(_.event.kind == MouseEventKind.Down)
      assert(presses.map(_.event.clickCount) == Vector(1, 2))
      assert(presses.forall(_.local == Position(0, 0)))
      assert(presses.forall(_.area == Rect(3, 1, 27, 1)))
      pilot.click(7, 1).waitForIdle()
      val last    = seen.filter(_.event.kind == MouseEventKind.Down).last
      assert(last.local == Position(4, 0) && last.event.clickCount == 1)
    }

  test("presses further apart than 400 ms, or on another cell, start the count again"):
    given ReactiveScope               = ReactiveScope.untracked
    var counts                        = Vector.empty[Int]
    var now                           = 0L
    val host                          = ElementHost()
    host.clock = () => now
    val view: View                    = text("target").onMouse { ctx =>
      if ctx.event.kind == MouseEventKind.Down then counts :+= ctx.event.clickCount
      true
    }
    host.render(Rect(0, 0, 10, 1), Buffer(Rect(0, 0, 10, 1)), Theme.Dark, view)
    def press(x: Int, at: Long): Unit =
      now = at * 1_000_000L
      val _ = host.dispatchMouse(MouseEvent(Position(x, 0), MouseEventKind.Down, KeyModifiers.None))
      val _ = host.dispatchMouse(MouseEvent(Position(x, 0), MouseEventKind.Up, KeyModifiers.None))
    press(1, 0); press(1, 300); press(1, 650); press(1, 1100); press(2, 1200)
    assert(counts == Vector(1, 2, 3, 1, 1))

  test("a TuiApp with mouse capture turns on motion tracking once a frame asks for a hover cue"):
    val withCue = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig.Default.withMouseCapture
      def view(using ReactiveScope, Theme): Element = button("Go")(()).hoverStyle(Style.Default.bold)
    driving(withCue) { (pilot, backend) =>
      pilot.waitUntil("motion tracking on")(backend.mouseCaptureMode.contains(MouseCaptureMode.AllMotion))
    }
    val plain   = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig.Default.withMouseCapture
      def view(using ReactiveScope, Theme): Element = button("Go")(())
    driving(plain) { (_, backend) => assert(backend.mouseCaptureMode.contains(MouseCaptureMode.Buttons)) }

  test("a keyed control stays pressed when the tree reshapes before the next frame; its new neighbour does not"):
    val extra = Signal(false)
    val app   = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("x", "insert")(extra.set(true)))
      def view(using ReactiveScope, Theme): Element =
        def cued(label: String) = button(label)(()).key(label).pressedStyle(Style.Default.underline).length(1)
        column((if extra.get then Seq(cued("X")) else Seq.empty) ++ Seq(cued("A"), cued("B"), text("").fill)*)
    driving(app, Size(30, 5)) { (pilot, _) =>
      pilot.mouseDown(14, 1).waitForIdle() // on B, row 1
      def underlined(row: Int) =
        (0 until 30).exists(x => pilot.cellAt(x, row).style.modifiers.hasAny(Modifiers.Underline))
      assert(underlined(1) && !underlined(0))
      pilot.press("x").waitForIdle() // X lands on row 0: A moves to row 1, B to row 2
      assert(pilot.screenLines(2).contains("B"))
      assert(underlined(2), "B is still the pressed control")
      assert(!underlined(1), "A took B's old place, not its pressed state")
      pilot.mouseUp(14, 4).waitForIdle()
      assert(!underlined(2))
    }

  test(
    "a pressed cue alone also turns on motion tracking, so the cue the control returns to after the release is known"
  ):
    val app = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig.Default.withMouseCapture
      def view(using ReactiveScope, Theme): Element = button("Go")(()).pressedStyle(Style.Default.underline)
    driving(app) { (pilot, backend) =>
      pilot.waitUntil("motion tracking on")(backend.mouseCaptureMode.contains(MouseCaptureMode.AllMotion))
    }
