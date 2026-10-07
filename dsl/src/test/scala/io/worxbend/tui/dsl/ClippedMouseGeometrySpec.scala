package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, MouseEvent, MouseEventKind, Position, Rect, Size, Style}
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.{MenuEntry, MenuState, ScrollViewState}

import org.scalatest.funsuite.AnyFunSuite

final class ClippedMouseGeometrySpec extends AnyFunSuite:

  Seq(false, true).foreach { nested =>
    test(s"a clipped menu selects the painted row through ${if nested then "nested" else "one"} scroll viewport"):
      val outer     = ScrollViewState()
      val inner     = ScrollViewState()
      outer.offset = 2
      inner.offset = 2
      val selection = MenuState()
      var chosen    = Vector.empty[Int]
      val entries   = Seq("ZERO", "ONE", "TWO", "THREE", "FOUR", "FIVE").map(MenuEntry.Item(_))
      val app       = new TuiApp:
        def view(using ReactiveScope, Theme): Element =
          val choices = menu(entries, selection)(index => chosen :+= index)
          if nested then
            positioned(5, 4, 20, 4)(
              scrollView(
                positioned(2, 1, 12, 4)(scrollView(choices, contentHeight = 8, inner)),
                contentHeight = 12,
                outer,
              )
            )
          else positioned(2, 2, 20, 4)(scrollView(choices, contentHeight = 8, outer))
      Pilot.using(Size(40, 14))(backend => app.runWith(backend)) { pilot =>
        pilot.waitForIdle()
        val x = if nested then 10 else 5
        val y = if nested then 4 else 3
        assert(pilot.screenLines(y).contains("TWO"))
        pilot.click(x, y).waitForIdle()
        assert(chosen == Vector(2))
        // These positions are inside the translated menu, but outside its enclosing viewport(s).
        pilot.click(x, if nested then 3 else 1).waitForIdle()
        pilot.click(x, if nested then 7 else 6).waitForIdle()
        assert(chosen == Vector(2))
      }
  }

  test("a scrolled vertical split uses its full layout height and translated origin for dragging"):
    val scrolling = ScrollViewState()
    scrolling.offset = 3
    val percent   = Signal(50)
    val app       = new TuiApp:
      def view(using ReactiveScope, Theme): Element = positioned(2, 2, 20, 4)(
        scrollView(splitPane(text("first"), text("second"), percent, Direction.Vertical), 10, scrolling)
      )
    Pilot.using(Size(30, 12))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle().drag(5, 4, 5, 3).waitForIdle()
      // The pointer is four rows into the ten-row layout, not one row into the four-row visible window.
      assert(percent.peek == 40)
      pilot.drag(5, 3, 5, 1).waitForIdle()
      assert(percent.peek == 40)
    }

  test("a horizontally clipped slider uses the original track but rejects hidden track positions"):
    var changed   = Vector.empty[Int]
    val host      = ElementHost()
    val layout    = Rect(0, 0, 13, 1)
    val buffer    = Buffer(layout)
    val transform = ViewportTransform(7, 4, Rect(10, 4, 6, 1))
    host.renderTree(
      SliderElement(0, value => changed :+= value),
      Style.Default,
      tree => FrameCoordinates.during(transform)(tree.widget.render(layout, buffer)),
    )
    assert(host.dispatchMouse(MouseEvent(Position(13, 4), MouseEventKind.Down, KeyModifiers.None)))
    assert(changed == Vector(50))
    assert(!host.dispatchMouse(MouseEvent(Position(8, 4), MouseEventKind.Down, KeyModifiers.None)))
    assert(!host.dispatchMouse(MouseEvent(Position(18, 4), MouseEventKind.Drag, KeyModifiers.None)))
    assert(changed == Vector(50))
