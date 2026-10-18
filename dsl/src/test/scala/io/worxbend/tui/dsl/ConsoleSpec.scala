package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** Covers [[Console]]: rendering a view, a widget or a `Text` once to styled text with no runner and no terminal, and
  * the environment seams print mode decides its width and colour depth by. Every detection test injects its environment
  * and its "is standard output a terminal" answer; nothing here reads the real process environment.
  */
final class ConsoleSpec extends AnyFunSuite:

  private val Esc = "\u001b["

  test("a panel renders its border glyphs and the theme's colour as SGR"):
    val out = Console.render(panel("B")(text("x")), width = 20, colors = ColorDepth.TrueColor)
    assert(out.contains("┌"), out)
    assert(out.contains("┘"), out)
    assert(out.contains("x"), out)
    assert(out.contains(Esc), out)
    // the border is `Color.Indexed(244)` in Theme.Dark: a 256-palette foreground, written as `38;5;244`
    assert(out.contains("38;5;244"), out)

  test("with colour off the same panel contains no escape sequence at all"):
    val out = Console.render(panel("B")(text("x")), width = 20, colors = ColorDepth.NoColor)
    assert(!out.contains(Esc), out)
    assert(out.contains("┌") && out.contains("x"), out)

  test("the height comes from the view's intrinsic measurement: a one-line panel is three rows"):
    val out = Console.render(panel("B")(text("x")), width = 20, colors = ColorDepth.NoColor)
    assert(out.linesIterator.size == 3, out)

  test("an explicit height overrides the measurement"):
    val out = Console.render(text("x"), width = 10, colors = ColorDepth.NoColor, height = Some(4))
    assert(out.split("\n", -1).length == 4, out)

  test("NO_COLOR injected through the environment seam disables colour even on a terminal"):
    val colors = Console.detectedColors(Map("NO_COLOR" -> "1", "COLORTERM" -> "truecolor"), terminal = true)
    assert(colors == ColorDepth.NoColor)
    val out    = Console.render(panel("B")(text("x")), width = 20, colors = colors)
    assert(!out.contains(Esc), out)

  test("output that is not a terminal gets no colour unless it is forced"):
    assert(Console.detectedColors(Map("COLORTERM" -> "truecolor"), terminal = false) == ColorDepth.NoColor)
    assert(
      Console.detectedColors(Map("COLORTERM" -> "truecolor", "FORCE_COLOR" -> "1"), terminal = false)
        == ColorDepth.Ansi16
    )
    assert(Console.detectedColors(Map("COLORTERM" -> "truecolor"), terminal = true) == ColorDepth.TrueColor)

  test("the width is the terminal's when there is one, else COLUMNS, else 80"):
    assert(Console.detectedWidth(Map("COLUMNS" -> "120"), terminalWidth = Some(66)) == 66)
    assert(Console.detectedWidth(Map("COLUMNS" -> "120"), terminalWidth = None) == 120)
    assert(Console.detectedWidth(Map("COLUMNS" -> "wide"), terminalWidth = None) == 80)
    assert(Console.detectedWidth(Map.empty, terminalWidth = None) == 80)

  test("the height is LINES, else 24"):
    assert(Console.detectedHeight(Map("LINES" -> "50")) == 50)
    assert(Console.detectedHeight(Map("LINES" -> "0")) == 24)
    assert(Console.detectedHeight(Map.empty) == 24)

  test("a run is non-interactive without a terminal or under CI"):
    assert(Console.isNonInteractive(Map.empty, terminal = false))
    assert(Console.isNonInteractive(Map("CI" -> "true"), terminal = true))
    assert(!Console.isNonInteractive(Map("CI" -> ""), terminal = true))
    assert(!Console.isNonInteractive(Map("CI" -> "false"), terminal = true))
    assert(!Console.isNonInteractive(Map.empty, terminal = true))

  test("a raw widget renders at the size it is given"):
    val widget: Widget = (area: Rect, buffer: Buffer) => buffer.setString(area.x, area.y, "raw", Style.Default)
    assert(Console.renderWidget(widget, Size(6, 2), ColorDepth.NoColor) == "raw\n")

  test("a Text renders one line per Line, styled"):
    val content = Text(Seq(Line.styled("one", Style.Default.bold), Line.styled("two", Style.Default)))
    val out     = Console.renderLines(content, ColorDepth.TrueColor)
    assert(out.linesIterator.toSeq.map(_.replaceAll("\u001b\\[[0-9;]*m", "")) == Seq("one", "two"), out)
    assert(out.contains(Esc + "1m"), out)

  test("print resolves for a view, with or without named arguments, and for a widget and a size"):
    // the calls live in `ConsolePrintOverloads`; that it compiled is the assertion, and none of them is invoked
    assert(ConsolePrintOverloads.calls.size == 4)

  test("render matches what a live app paints at the same size"):
    // print mode and a live frame must never disagree about the same view, so the text is compared against a Pilot run
    val shown: View = panel("Hello")(text("Welcome").bold, spacer, text("bye").dim)
    val backend     = HeadlessBackend(Size(30, 5))
    val app         = new TuiApp:
      override def theme: Theme                     = Theme.Dark
      def view(using ReactiveScope, Theme): Element = shown
    io.worxbend.tui.testsupport.Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      val live    = pilot.screenText
      val printed = Console.render(shown, width = 30, colors = ColorDepth.NoColor, height = Some(5))
      // NoColor keeps text attributes (bold, dim) by contract, so the comparison is over the glyphs
      val glyphs  = printed.linesIterator.toSeq.map(_.replaceAll("\u001b\\[[0-9;]*m", "").trim)
      assert(glyphs == live.linesIterator.toSeq.map(_.trim))
      pilot.interrupt()
      assert(pilot.awaitTermination())
    }
