package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.{BufferAssertions, GoldenFrames}

import org.scalatest.funsuite.AnyFunSuite

/** `panel(title).header(e).footer(e)`: elements written into the border rows. */
final class PanelSlotsSpec extends AnyFunSuite with PilotFixture:

  private def frame(element: Element, width: Int, height: Int): Buffer =
    BufferAssertions.rendered(element.widget, width, height)

  test("a badge in the footer sits in the bottom border at the right"):
    val node = panel("t")(text("body")).footer(badge("3"))
    GoldenFrames.assertMatches("panel-footer-badge", frame(node, 10, 3))
    assert(BufferAssertions.lines(frame(node, 10, 3)) == Seq("┌t───────┐", "│body    │", "└───── 3 ┘"))

  test("a header is written into the top border, opposite the title"):
    val node = panel("t")(text("body")).header(text("[2]"))
    assert(BufferAssertions.line(frame(node, 10, 3), 0) == "┌t────[2]┐")

  test("an alignment moves a slot along its border"):
    val node = panel("t")(text("body")).footer(text("ok"), Alignment.Left)
    assert(BufferAssertions.line(frame(node, 10, 3), 2) == "└ok──────┘")

  test("on a narrow panel a wide footer is cut by display width and leaves both corners alone"):
    // " 漢字漢字 " is ten columns; the run between the corners is four, which fits the leading space and one ideograph
    val node = panel(text("x")).footer(badge("漢字漢字"))
    val out  = frame(node, 6, 3)
    assert(out.get(0, 2).symbol == "└")
    assert(out.get(5, 2).symbol == "┘")
    assert(out.get(2, 2).symbol == "漢")
    assert(out.get(4, 2).symbol != "字", "half an ideograph is never drawn")

  test("slots cost no content row"):
    val plain = panel("t")(text("body"))
    assert(plain.footer(badge("3")).header(badge("x")).intrinsicHeight(10) == plain.intrinsicHeight(10))

  test("a slot is a child: the focus pass sees it and clicks on it reach it"):
    final class SlotApp extends TuiApp:
      var refreshed: Int                            = 0
      def view(using ReactiveScope, Theme): Element =
        panel("logs")(text("line")).footer(button("refresh")(refreshed += 1).key("refresh"))

    val backend = HeadlessBackend(Size(24, 4))
    val app     = SlotApp()
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val slot    = app.boundsOf("refresh")
    assert(slot.exists(_.y == 3), s"the footer is painted on the bottom border row, got $slot")
    pilot.click(slot.get).waitForIdle()
    assert(app.refreshed == 1)
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(app.refreshed == 2, "the footer button is the panel's one focusable, so it holds focus")
    pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
