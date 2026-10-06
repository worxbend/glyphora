package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, MouseEvent, MouseEventKind, Position, Rect, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.ScrollViewState

import org.scalatest.funsuite.AnyFunSuite

final class FrameCoordinatesSpec extends AnyFunSuite:

  test("escaping portals compose nested scroll origins and offsets without viewport clipping"):
    val outer  = ScrollViewState()
    val inner  = ScrollViewState()
    outer.offset = 2
    inner.offset = 1
    var clicks = 0
    val popup  = portal(1, 5, 6, 1)(button("POP") { clicks += 1 })
    val root   = positioned(5, 4, 20, 4)(
      scrollView(positioned(2, 3, 12, 3)(scrollView(popup, contentHeight = 10, inner)), contentHeight = 12, outer)
    )
    val host   = ElementHost()
    val buffer = Buffer(Rect(0, 0, 40, 16))
    PortalQueue.begin()
    try
      host.renderTree(root, Style.Default, tree => tree.widget.render(buffer.area, buffer))
      val queued = PortalQueue.drain()
      assert(queued.map(_._1) == Seq(Rect(8, 9, 6, 1)))
      queued.foreach((target, content) => content.widget.render(target, buffer))
      assert(buffer.get(10, 9).symbol == "P")
      assert(host.tracker.areaOf(2).contains(Rect(8, 9, 6, 1)))
      assert(host.dispatchMouse(MouseEvent(Position(9, 9), MouseEventKind.Down, KeyModifiers.None)))
      assert(clicks == 1)
      assert(!host.dispatchMouse(MouseEvent(Position(1, 5), MouseEventKind.Down, KeyModifiers.None)))
      assert(clicks == 1)
    finally PortalQueue.end()

  test("ordinary nested scroll hit records remain clipped to every viewport"):
    val outer  = ScrollViewState()
    val inner  = ScrollViewState()
    outer.offset = 2
    inner.offset = 1
    val root   = positioned(5, 4, 20, 4)(
      scrollView(
        positioned(2, 3, 12, 3)(scrollView(button("LONG") {}.length(10), contentHeight = 10, inner)),
        contentHeight = 12,
        outer,
      )
    )
    val host   = ElementHost()
    val buffer = Buffer(Rect(0, 0, 40, 16))
    host.renderTree(root, Style.Default, tree => tree.widget.render(buffer.area, buffer))
    assert(host.tracker.areaOf(2).contains(Rect(7, 5, 11, 3)))
    assert(host.tracker.hitTest(Position(8, 9)).isEmpty)

  test("a real frame paints and clicks focusable and pointer-only portals outside nested scroll windows"):
    Seq(false, true).foreach { pointerOnly =>
      val outer   = ScrollViewState()
      val inner   = ScrollViewState()
      outer.offset = 2
      inner.offset = 1
      var clicks  = 0
      val content =
        if pointerOnly then
          text("POP").onMouseEvent { event =>
            if event.kind == MouseEventKind.Down then
              clicks += 1
              true
            else false
          }
        else button("POP") { clicks += 1 }
      val backend = HeadlessBackend(Size(40, 16))
      val app     = new TuiApp:
        def view(using ReactiveScope, Theme): Element = positioned(5, 4, 20, 4)(
          scrollView(
            positioned(2, 3, 12, 3)(scrollView(portal(1, 5, 6, 1)(content), contentHeight = 10, inner)),
            contentHeight = 12,
            outer,
          )
        )
      Pilot.using(backend)(app.runWith(backend)) { pilot =>
        pilot.waitForIdle()
        assert(pilot.screenLines(9).contains("POP"))
        pilot.click(9, 9).waitForIdle()
        assert(clicks == 1)
        pilot.click(1, 5).waitForIdle()
        assert(clicks == 1)
      }
    }

  test("a failed content render restores the enclosing coordinate scope"):
    val area  = Rect(1, 2, 3, 4)
    val outer = ViewportTransform(5, 6, Rect(0, 0, 20, 20))
    val inner = ViewportTransform(2, 3, Rect(0, 0, 10, 10))
    FrameCoordinates.during(outer) {
      intercept[IllegalStateException] {
        FrameCoordinates.during(inner)(throw IllegalStateException("render failed"))
      }
      assert(FrameCoordinates.translate(area) == area.offset(5, 6))
    }
    assert(FrameCoordinates.translate(area) == area)
