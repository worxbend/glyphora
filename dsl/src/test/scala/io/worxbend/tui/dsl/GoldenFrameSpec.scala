package io.worxbend.tui.dsl

import io.worxbend.tui.core.{CharWidth, Modifiers}
import io.worxbend.tui.testsupport.{BufferAssertions, GoldenFrames}

import org.scalatest.funsuite.AnyFunSuite

/** Full-frame snapshot of a deterministic chrome composition.
  *
  * The fixture records **layout and glyphs only** — which symbol landed in which cell — because that is all
  * `BufferAssertions.text` serialises. Styling is invisible to it: strip every colour and modifier from this scene and
  * the fixture still matches byte for byte. So the styling of the same scene is pinned separately, by the direct cell
  * assertions below. Regenerate the fixture with `GLYPHORA_GOLDEN_UPDATE=$PWD/dsl/src/test/resources ./mill dsl.test`.
  */
final class GoldenFrameSpec extends AnyFunSuite:

  private val theme = summon[Theme]

  private def appShell: Element =
    scaffold(
      topBar = Some(topBar("golden", tabs = Seq("One", "Two"), right = "v1")),
      sidebar = Some(sidebar(panel("Menu")(text("alpha"), text("beta")), width = 14)),
      statusBar = Some(statusBar(Seq("q" -> "quit", "tab" -> "next"))),
    ) {
      column(
        rule("content"),
        text("static body line").bold,
        gauge(0.4),
        sparkline(Seq(1L, 4L, 2L, 8L, 5L)),
      )
    }

  test("the app-shell composition renders exactly as recorded"):
    GoldenFrames.assertMatches("app-shell", BufferAssertions.rendered(appShell.widget, 52, 10))

  test("the top bar paints the theme surface across its whole row, with a bold title"):
    val frame = BufferAssertions.rendered(appShell.widget, 52, 10)
    assert(
      (0 until 52).forall(x => frame.get(x, 0).style.bg == theme.surface.bg),
      "the top bar's surface does not reach the full row width",
    )
    // "golden" starts one column in, after the leading spacer
    val title = 1 until (1 + CharWidth.of("golden"))
    assert(title.map(x => frame.get(x, 0).symbol).mkString == "golden")
    assert(title.forall(x => frame.get(x, 0).style == theme.surface.bold))

  test("a `.bold` line in the body keeps its modifier all the way into the composed frame"):
    val frame = BufferAssertions.rendered(appShell.widget, 52, 10)
    // the sidebar is 14 columns wide including its right border, so the body starts at column 14 on row 2
    val body  = 14 until (14 + CharWidth.of("static body line"))
    assert(body.map(x => frame.get(x, 2).symbol).mkString == "static body line")
    assert(body.forall(x => frame.get(x, 2).style.modifiers.hasAny(Modifiers.Bold)))
    assert(frame.get(13, 2).style == theme.border, "the sidebar divider lost the theme's border style")
