package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Event, MouseEvent, MouseEventKind, Position, Size}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** The mouse-side counterpart of the `onKey` coverage in [[GrammarSpec]]: `.onClick`, `.onClickAt`, `.onHover`,
  * `.onDrag`, `.onDragEnd` and `.onScroll`.
  *
  * Most of these assert against `props.onMouse` directly rather than through a running app, because what is under test
  * is dispatch — which kinds a handler claims and which it passes on — and not what anything renders. The last test
  * drives a real app so that the wiring from a terminal mouse report all the way to `.onClick` is covered end to end.
  */
final class MouseGrammarSpec extends AnyFunSuite with PilotFixture:

  private def at(kind: MouseEventKind, x: Int = 0, y: Int = 0): MouseEvent =
    MouseEvent(Position(x, y), kind, KeyModifiers.None)

  private def deliver(element: Element, event: MouseEvent): Boolean =
    element.props.onMouse.exists(_(event))

  test("onClick fires on a press and consumes it, and declines every other kind"):
    var clicks = 0
    val el     = text("x").onClick { clicks += 1 }
    assert(deliver(el, at(MouseEventKind.Down)))
    assert(clicks == 1)
    Seq(MouseEventKind.Up, MouseEventKind.Drag, MouseEventKind.Moved, MouseEventKind.ScrollUp).foreach { kind =>
      assert(!deliver(el, at(kind)), s"$kind should not have been consumed")
    }
    assert(clicks == 1)

  /** The positions a handler is told are the absolute terminal cells the `MouseEvent` carried, not coordinates relative
    * to the element — an element that wants its own coordinate space subtracts its area's origin itself.
    */
  test("onClickAt, onHover, onDrag and onDragEnd each claim exactly their own kind and report the cell"):
    var seen = List.empty[(String, Position)]
    val el   = text("x")
      .onClickAt(pos => seen = ("click", pos) :: seen)
      .onHover(pos => seen = ("hover", pos) :: seen)
      .onDrag(pos => seen = ("drag", pos) :: seen)
      .onDragEnd(pos => seen = ("end", pos) :: seen)
    assert(deliver(el, at(MouseEventKind.Down, 3, 4)))
    assert(deliver(el, at(MouseEventKind.Moved, 5, 6)))
    assert(deliver(el, at(MouseEventKind.Drag, 7, 8)))
    assert(deliver(el, at(MouseEventKind.Up, 9, 10)))
    assert(
      seen.reverse == List(
        ("click", Position(3, 4)),
        ("hover", Position(5, 6)),
        ("drag", Position(7, 8)),
        ("end", Position(9, 10)),
      )
    )
    // nothing above claims a wheel step, so an unbound kind still bubbles
    assert(!deliver(el, at(MouseEventKind.ScrollDown)))

  test("onScroll runs the matching direction and consumes only wheel steps"):
    var offset = 0
    val el     = text("x").onScroll(up = offset -= 1, down = offset += 1)
    assert(deliver(el, at(MouseEventKind.ScrollUp)))
    assert(deliver(el, at(MouseEventKind.ScrollDown)))
    assert(deliver(el, at(MouseEventKind.ScrollDown)))
    assert(offset == 1)
    assert(!deliver(el, at(MouseEventKind.Down)))

  /** The composition rule: each handler passes on what it does not claim, so several of them layer instead of the last
    * one silently replacing the ones before it. A hand-written `onMouseEvent` underneath keeps working the same way.
    */
  test("mouse handlers compose with each other and with a raw onMouseEvent underneath"):
    var raw    = 0
    var clicks = 0
    var wheels = 0
    val el     = text("x")
      .onMouseEvent { _ =>
        raw += 1
        true
      }
      .onScroll(up = wheels += 1, down = wheels += 1)
      .onClick { clicks += 1 }
    assert(deliver(el, at(MouseEventKind.Down)))
    assert(deliver(el, at(MouseEventKind.ScrollUp)))
    assert(deliver(el, at(MouseEventKind.Moved)))
    assert(clicks == 1)
    assert(wheels == 1)
    assert(raw == 1) // only the kind neither combinator claimed reached the raw handler

  /** The annotation is half the assertion: like the key handlers, each mouse handler hands back the element's own type,
    * so the node-specific builders stay reachable after the binding.
    */
  test("mouse handlers keep the element's own type"):
    val bound: PanelElement = panel(text("x")).onClick(()).onScroll((), ()).rounded
    assert(bound.borderType == BorderType.Rounded)

  /** End to end: a mouse report from the backend, through the runner, the focus pass's pointer-area filtering and the
    * event router, into an `.onClick` on a plain (non-focusable) element.
    */
  test("onClick receives a real press routed through a running app, and only inside the element"):
    var clicks                      = 0
    val backend                     = HeadlessBackend(Size(20, 4))
    val testApp                     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        column(text("target").length(1).onClick { clicks += 1 }, text("elsewhere").length(1))
    val pilot                       = startPilot(backend) { testApp.runWith(backend) }
    pilot.waitForIdle()
    def press(x: Int, y: Int): Unit =
      backend.postEvent(Event.Mouse(MouseEvent(Position(x, y), MouseEventKind.Down, KeyModifiers.None)))
      val _ = pilot.waitForIdle()
    press(2, 0)
    assert(clicks == 1)
    press(2, 1) // the row below the target: outside its recorded area, so the handler must not fire
    assert(clicks == 1)
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
