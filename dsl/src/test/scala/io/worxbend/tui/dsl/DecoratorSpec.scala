package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.{BufferAssertions, GoldenFrames, Pilot}

import org.scalatest.funsuite.AnyFunSuite

/** `.border`, `.padding`, `.background`, `.shadow` and `.clip` on any element. */
final class DecoratorSpec extends AnyFunSuite with PilotFixture:

  private def frame(element: Element, width: Int, height: Int): Buffer =
    BufferAssertions.rendered(element.widget, width, height)

  private def lines(element: Element, width: Int, height: Int): Seq[String] =
    BufferAssertions.lines(frame(element, width, height))

  // ---- padding ----

  test("padding a text draws exactly what a borderless panel with the same padding draws"):
    val padded = text("hi").padding(Padding.symmetric(2, 1))
    GoldenFrames.assertMatches("decorator-padding", frame(padded, 8, 4))
    assert(lines(padded, 8, 4) == Seq("        ", "  hi    ", "        ", "        "))
    BufferAssertions.assertEquals(
      frame(padded, 8, 4),
      frame(panel(text("hi")).borderless.padded(Padding.symmetric(2, 1)), 8, 4),
    )

  test("padding(vertical, horizontal) reads rows first, the way the CSS shorthand does"):
    BufferAssertions.assertEquals(
      frame(text("hi").padding(1, 2), 8, 4),
      frame(text("hi").padding(Padding.symmetric(2, 1)), 8, 4),
    )

  test("padding adds its cells to the measurement"):
    val padded = text("hi").padding(Padding.symmetric(2, 1))
    assert(padded.intrinsicWidth(1).contains(6))
    assert(padded.intrinsicHeight(10).contains(3))

  // ---- border ----

  test("a rounded border around a text, in the frame style it was given"):
    val boxed = text("hi").border(BorderType.Rounded, Style.fg(Color.Red))
    GoldenFrames.assertMatches("decorator-border-rounded", frame(boxed, 4, 3))
    assert(lines(boxed, 4, 3) == Seq("╭──╮", "│hi│", "╰──╯"))
    val out   = frame(boxed, 4, 3)
    assert(out.get(0, 0).style.fg == Some(Color.Red))
    assert(out.get(1, 1).style.fg.isEmpty, "the frame style is the border's, not the content's")

  test("a border costs two cells on each axis of the measurement"):
    val boxed = text("hello").border()
    assert(boxed.intrinsicWidth(3).contains(7))
    assert(boxed.intrinsicHeight(7).contains(3))

  test("a constraint written before the decorator sizes the decorated box"):
    val node = row(text("ab").length(4).border(), text("x"))
    assert(lines(node, 6, 3) == Seq("┌──┐x ", "│ab│  ", "└──┘  "))

  test("the decorated node's style is the content style, layered under what the content drew"):
    val boxed = text("hi").border().bg(Color.Blue)
    val out   = frame(boxed, 4, 3)
    assert(out.get(1, 1).style.bg == Some(Color.Blue))
    assert(out.get(0, 0).style.bg.isEmpty)

  // ---- background ----

  test("background fills the whole area under the content"):
    val node = text("hi").background(Style.bg(Color.Blue))
    val out  = frame(node, 4, 2)
    assert(BufferAssertions.lines(out) == Seq("hi  ", "    "))
    assert((0 until 4).forall(x => out.get(x, 1).style.bg == Some(Color.Blue)))
    assert(out.get(0, 0).style.bg == Some(Color.Blue))

  // ---- shadow ----

  test("a shadow is cast one cell down and right, inside the area, over what was already there"):
    val node = layers(text("........\n........\n........\n........"), text("box").border().shadow)
    GoldenFrames.assertMatches("decorator-shadow", frame(node, 6, 4))
    val out  = frame(node, 6, 4)
    assert(BufferAssertions.lines(out) == Seq("┌───┐.", "│box│.", "└───┘.", "......"))
    assert(out.get(5, 1).style.modifiers.hasAny(Modifiers.Dim))
    assert(out.get(0, 3).style.modifiers.isEmpty, "the shadow starts one column in")
    assert(out.get(1, 3).style.modifiers.hasAny(Modifiers.Dim))

  test("a shadow adds its offset to the measurement"):
    assert(text("box").shadow.intrinsicWidth(1).contains(4))
    assert(text("box").shadow.intrinsicHeight(4).contains(2))

  // ---- clip ----

  test("clip keeps a widget that paints outside its rect inside it"):
    val sprawling: Widget = (area, buffer) => buffer.setString(area.x - 2, area.y, "XXXXXXXXXX", Style.Default)
    def scene(clipped: Boolean): Element =
      val inner = widget(sprawling)
      row(text("AA").length(3), (if clipped then inner.clip else inner).length(2), text("BB").length(3))
    GoldenFrames.assertMatches("decorator-clip", frame(scene(clipped = true), 8, 1))
    assert(lines(scene(clipped = false), 8, 1) == Seq("AXXXXBBX"), "unclipped, it overwrites both neighbours")
    assert(lines(scene(clipped = true), 8, 1) == Seq("AA XXBB "))

  // ---- interaction ----

  private final class BoxedButtonApp extends TuiApp:
    var presses: Int = 0

    def view(using ReactiveScope, Theme): Element =
      column(
        button("go")(presses += 1).key("go").border().padding(1, 1).shadow,
        button("other")(()).key("other"),
      )

  test("a decorated button keeps one tab stop and still takes clicks"):
    val backend = HeadlessBackend(Size(20, 10))
    val app     = BoxedButtonApp()
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(app.presses == 1, "the decorated button is the first stop and starts focused")
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(app.presses == 2, "two Tabs from the button come back to it: there are exactly two stops")
    pilot.click(app.boundsOf("go").get).waitForIdle()
    assert(app.presses == 3)
    quit(pilot)

  private def quit(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
