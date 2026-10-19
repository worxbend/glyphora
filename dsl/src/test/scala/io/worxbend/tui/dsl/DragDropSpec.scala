package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Event, MouseEvent, MouseEventKind, Position, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Drag and drop: typed payloads through `DragKey`, the portal-drawn ghost, and hover feedback on targets. */
final class DragDropSpec extends AnyFunSuite with PilotFixture:

  private def driving(app: TuiApp, size: Size = Size(16, 3))(body: Pilot => Unit): Unit =
    val backend = HeadlessBackend(size)
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    body(pilot)
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  private def dragTo(pilot: Pilot, position: Position): Pilot =
    pilot.backend.postEvent(Event.Mouse(MouseEvent(position, MouseEventKind.Drag, KeyModifiers.None)))
    pilot.waitForIdle()

  private def reversed(pilot: Pilot, x: Int, y: Int): Boolean =
    pilot.cellAt(x, y).style.modifiers.hasAny(Modifiers.Reverse)

  /** `[a]` at columns 0-2 of row 0, a String bin at 11-15 of row 0 and an Int bin at 11-15 of row 1. */
  private final class Board(onString: String => Unit, onInt: Int => Unit = _ => ()) extends TuiApp:
    def view(using ReactiveScope, Theme): Element =
      column(
        row(
          text("[a]").draggable("a").length(3),
          text("").fill,
          text("bin").dropTarget[String](_ => true)(onString).length(5),
        ).length(1),
        row(text("").fill, text("ints").dropTarget[Int](_ => true)(onInt).length(5)).length(1),
        text("").fill,
      )

  test("draggable(\"a\") dropped on a dropTarget[String] calls onDrop(\"a\")"):
    var dropped = Vector.empty[String]
    driving(Board(s => dropped :+= s)) { pilot =>
      pilot.drag(Position(1, 0), Position(12, 0)).waitForIdle()
      assert(dropped == Vector("a"))
    }

  test("a target of another type ignores the payload"):
    var ints    = Vector.empty[Int]
    var strings = Vector.empty[String]
    driving(Board(s => strings :+= s, i => ints :+= i)) { pilot =>
      pilot.drag(Position(1, 0), Position(12, 1)).waitForIdle()
      assert(ints.isEmpty && strings.isEmpty)
    }

  test("the ghost follows the pointer mid-drag, drawn over everything"):
    driving(Board(_ => ())) { pilot =>
      pilot.mouseDown(1, 0).waitForIdle()
      dragTo(pilot, Position(6, 2))
      pilot.assertGolden("drag-ghost")
      pilot.mouseUp(6, 2).waitForIdle()
      assert(pilot.screenLines(2).trim.isEmpty, "the ghost is gone once the drag ends")
    }

  test("Esc cancels a drag in flight: the ghost goes, and the release drops nothing"):
    var dropped = Vector.empty[String]
    driving(Board(s => dropped :+= s)) { pilot =>
      pilot.mouseDown(1, 0).waitForIdle()
      dragTo(pilot, Position(6, 2))
      assert(pilot.screenLines(2).contains("[a]"), "the ghost is showing")
      pilot.press("esc").waitForIdle()
      assert(!pilot.screenLines(2).contains("[a]"), "the ghost is gone")
      dragTo(pilot, Position(12, 0))
      pilot.mouseUp(12, 0).waitForIdle()
      assert(dropped.isEmpty)
    }

  test("a target that accepts the payload shows the hover cue while the pointer is over it; one that does not, none"):
    driving(Board(_ => ())) { pilot =>
      pilot.mouseDown(1, 0).waitForIdle()
      // the ghost covers columns 11-13 under the pointer; the rest of the target shows the cue
      dragTo(pilot, Position(12, 0))
      assert(reversed(pilot, 15, 0))
      dragTo(pilot, Position(12, 1))
      assert(!reversed(pilot, 15, 0) && !reversed(pilot, 15, 1))
      pilot.mouseUp(12, 1).waitForIdle()
    }

  test("accept filters the payload: a refusing target is no drop target for it"):
    var dropped = Vector.empty[String]
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        row(
          text("x").draggable("x").length(3),
          text("").fill,
          text("bin").dropTarget[String](_ == "y")(s => dropped :+= s).length(5),
        )
    driving(app) { pilot =>
      pilot.drag(Position(1, 0), Position(12, 0)).waitForIdle()
      assert(dropped.isEmpty)
    }

  test("a press and release without motion on a draggable button still clicks it"):
    var clicks = 0
    var drops  = 0
    val app    = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        row(
          button("go")(clicks += 1).draggable("go").length(6),
          text("").fill,
          text("bin").dropTarget[String](_ => true)(_ => drops += 1).length(5),
        )
    driving(app) { pilot =>
      pilot.click(2, 0).waitForIdle()
      assert(clicks == 1 && drops == 0)
    }

  test("a DragKey for an app's own type carries it, without reflection"):
    final case class Card(rank: Int)
    given DragKey[Card] = DragKey("card")
    var dropped         = Vector.empty[Card]
    val app             = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        row(
          text("7").draggable(Card(7)).length(3),
          text("").fill,
          text("pile").dropTarget[Card](_.rank > 5)(c => dropped :+= c).length(5),
        )
    driving(app) { pilot =>
      pilot.drag(Position(1, 0), Position(12, 0)).waitForIdle()
      assert(dropped == Vector(Card(7)))
    }
