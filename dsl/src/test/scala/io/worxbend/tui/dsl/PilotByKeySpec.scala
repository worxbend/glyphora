package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Position, Rect, Size}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** Driving an app by element key instead of by coordinate: `app.boundsOf(key)` names where a keyed element landed on
  * the last frame, and `pilot.click(rect)` / `pilot.hover(rect)` aim at that rect's centre. The two halves are kept on
  * different sides of the module boundary on purpose — `tui-test` cannot see `tui-dsl`, so the pilot learns nothing
  * about keys and the app learns nothing about pilots.
  */
final class PilotByKeySpec extends AnyFunSuite with PilotFixture:

  private final class FormApp extends TuiApp:
    var saves: Int                     = 0
    var hovered: Option[Position]      = None
    override def bindings: KeyBindings = KeyBindings(binding("ctrl+q", "quit")(quit()))

    def view(using ReactiveScope, Theme): Element =
      column(
        text("Settings"),
        row(spacer, button("Save")(saves += 1).key("save").length(10), spacer(3)).length(1),
        text("status line")
          .key("status")
          .onMouseEvent { event =>
            if event.kind == MouseEventKind.Moved then hovered = Some(event.position)
            true
          },
        spacer,
      )

  private def started(): (FormApp, io.worxbend.tui.testsupport.Pilot) =
    val backend = HeadlessBackend(Size(40, 8))
    val app     = FormApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    (app, pilot)

  test("a headless run records bounds without being asked, so boundsOf answers after the first frame"):
    val (app, pilot) = started()
    // the row is one row tall at y = 1, and the button takes ten cells ending three before the right edge
    assert(app.boundsOf("save").contains(Rect(27, 1, 10, 1)))
    assert(app.boundsOf("status").contains(Rect(0, 2, 40, 1)))
    assert(app.boundsOf("missing").isEmpty)
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())

  test("pilot.click(rect) lands on the rect's centre, and the button under that key sees the press"):
    val (app, pilot) = started()
    pilot.click(app.boundsOf("save").get).waitForIdle()
    assert(app.saves == 1)
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())

  test("pilot.hover(rect) moves the pointer to the rect's centre"):
    val (app, pilot) = started()
    pilot.hover(app.boundsOf("status").get).waitForIdle()
    assert(app.hovered.contains(Position(20, 2)))
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())

  test("boundsOf is None before any frame and after the run is over"):
    val app     = FormApp()
    assert(app.boundsOf("save").isEmpty)
    val backend = HeadlessBackend(Size(40, 8))
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())
    assert(app.boundsOf("save").isEmpty)
