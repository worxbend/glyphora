package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.{HeadlessBackend, PointerShape}
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** The mouse pointer following what is under it: a hand over a button or a link, an I-beam over a text field, a resize
  * arrow over a split divider, and the default everywhere else.
  */
final class PointerShapeSpec extends AnyFunSuite with PilotFixture:

  /** Rows: 0 button, 1 input, 2 link, 3 a plain clickable with an explicit pointer, 4 plain text, 5-8 a horizontal
    * split pane with a one-column divider between its panes.
    */
  private final class PointerApp extends TuiApp:
    private val field                             = TextInputState()
    private val split                             = Signal(50)
    def view(using ReactiveScope, Theme): Element =
      column(
        button("Go")(()),
        input(field),
        link("docs", "https://example.invalid"),
        text("custom").onClick(()).pointer(PointerShape.Wait),
        text("plain"),
        splitPane(text("left"), text("right"), split),
      )

  private def driving(body: (Pilot, HeadlessBackend) => Unit): Unit =
    val backend = HeadlessBackend(Size(20, 9))
    val pilot   = startPilot(backend)(PointerApp().runWith(backend))
    pilot.waitForIdle()
    body(pilot, backend)
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("a button and a link show the hand, a text field the I-beam"):
    driving { (pilot, backend) =>
      pilot.mouseMove(0, 0).waitUntil("hand over the button")(backend.currentPointerShape == PointerShape.Pointer)
      pilot.mouseMove(0, 1).waitUntil("I-beam over the input")(backend.currentPointerShape == PointerShape.Text)
      pilot.mouseMove(1, 2).waitUntil("hand over the link")(backend.currentPointerShape == PointerShape.Pointer)
    }

  test("an explicit .pointer wins, and empty space hands the default back"):
    driving { (pilot, backend) =>
      pilot.mouseMove(2, 3).waitUntil("the explicit shape")(backend.currentPointerShape == PointerShape.Wait)
      pilot.mouseMove(15, 4).waitUntil("default over nothing")(backend.currentPointerShape == PointerShape.Default)
    }

  test("a split pane shows the resize arrow over its divider and nothing special over its panes"):
    driving { (pilot, backend) =>
      // the divider is the one column of row 6 neither pane covers; where exactly is the layout solver's business
      val shapes = (0 until 20).map { x =>
        pilot.mouseMove(0, 0).waitUntil("hand over the button")(backend.currentPointerShape == PointerShape.Pointer)
        pilot.mouseMove(x, 6).waitUntil("the pointer settled")(backend.currentPointerShape != PointerShape.Pointer)
        backend.currentPointerShape
      }
      assert(shapes.count(_ == PointerShape.ResizeHorizontal) == 1, shapes.toString)
      assert(shapes.forall(shape => shape == PointerShape.ResizeHorizontal || shape == PointerShape.Default))
    }
