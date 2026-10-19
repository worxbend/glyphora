package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, Size}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** `GLYPHORA_DEBUG_LAYOUT=1`: every container outlined dimly, every child laid out to zero cells marked in red. */
final class DebugLayoutSpec extends AnyFunSuite with PilotFixture:

  private final class Squeezed(debug: Boolean) extends TuiApp:
    override protected def debugLayout: Boolean   = debug
    def view(using ReactiveScope, Theme): Element =
      column(
        text("header"),
        row(text("left"), text("gone").length(0), text("right")).length(3),
        text("footer"),
      )

  test("the environment variable is the switch: 1, true or yes turn it on, anything else leaves it off"):
    assert(DebugLayout.enabled(Map("GLYPHORA_DEBUG_LAYOUT" -> "1")))
    assert(DebugLayout.enabled(Map("GLYPHORA_DEBUG_LAYOUT" -> "true")))
    assert(DebugLayout.enabled(Map("GLYPHORA_DEBUG_LAYOUT" -> "YES")))
    assert(!DebugLayout.enabled(Map("GLYPHORA_DEBUG_LAYOUT" -> "0")))
    assert(!DebugLayout.enabled(Map.empty))

  test("containers are outlined and the zero-width child is marked red where it would have been"):
    val backend = HeadlessBackend(Size(24, 6))
    val pilot   = startPilot(backend)(Squeezed(debug = true).runWith(backend))
    pilot.waitForIdle()
    pilot.assertGolden("debug-layout-zero-size").assertGoldenStyled("debug-layout-zero-size")
    val marks   = for
      y <- 0 until 6
      x <- 0 until 24
      if pilot.cellAt(x, y).style.bg.contains(Color.Red)
    yield (x, y)
    // each `text` claims the width it measures, so the row is `left`, then `gone` with nothing, then `right` at column
    // 4: the mark sits on the `r` of `right`, which keeps its glyph and takes the red style
    assert(marks == Seq((4, 1)), marks)
    assert(pilot.cellAt(4, 1).symbol == "r")

  test("off, the frame is exactly the app's"):
    val backend = HeadlessBackend(Size(24, 6))
    val pilot   = startPilot(backend)(Squeezed(debug = false).runWith(backend))
    pilot.waitForIdle()
    assert(pilot.screenLines.head.trim == "header")
    assert(!pilot.screenText.contains("┌"))
