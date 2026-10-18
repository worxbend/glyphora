package io.worxbend.tui.dsl

import io.worxbend.tui.core.GlyphSupport
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered}
import io.worxbend.tui.widgets as w

import scala.concurrent.duration.DurationInt

import org.scalatest.funsuite.AnyFunSuite

/** The whole point of the glyph floor, in one pair of assertions: the same element tree, rendered under two themes,
  * either uses the Unicode glyphs or does not use anything above ASCII.
  */
final class GlyphFloorSpec extends AnyFunSuite:

  private val AsciiTheme: Theme = Theme.Dark.withGlyphs(GlyphSupport.Ascii)

  private def frameOf(theme: Theme): Seq[String] =
    given Theme = theme
    val tree    = panel("Loading")(spinnerAt(0.millis, "working"), progressBar(0.5))
    lines(rendered(tree.widget, 24, 6))

  test("an ASCII theme renders a panel, a spinner and a progress bar with no codepoint above U+007E"):
    val frame = frameOf(AsciiTheme)
    val stray = frame.flatMap(_.toSeq).filter(c => c > '~')
    assert(stray.isEmpty, s"non-ASCII glyphs leaked through: ${stray.distinct.mkString(", ")}")
    assert(frame.head.startsWith("+Loading") && frame.head.endsWith("-+"))

  /** The inverse assertion, so the test above cannot pass by rendering nothing at all. */
  test("the same tree under the default theme does use box drawing"):
    assert(frameOf(Theme.Dark).head.startsWith("┌Loading") && frameOf(Theme.Dark).head.endsWith("─┐"))

  /** The ceiling belongs to the terminal, not to the border the author picked, so an explicit `.thick` must degrade
    * too. Getting this wrong is how an app that styled one panel by hand ends up with one broken frame.
    */
  test("an explicitly chosen border still degrades under an ASCII theme"):
    given Theme = AsciiTheme
    assert(lines(rendered(panel(text("x")).thick.widget, 6, 3)).head == "+----+")

  test("an explicitly chosen spinner preset still degrades under an ASCII theme"):
    given Theme  = AsciiTheme
    val animated = spinnerAt(0.millis).preset(w.SpinnerPreset.DotsRing)
    assert(lines(rendered(animated.widget, 6, 1)).head.forall(c => c <= '~'))

  test("an element built with no theme in sight keeps every glyph it was given"):
    assert(ElementProps().glyphs == GlyphSupport.Full)

  test("Theme.detected lowers only the ceiling and keeps the palette"):
    val detected = Theme.detected(Theme.Light, Map("TERM" -> "xterm", "LANG" -> "C"))
    assert(detected.glyphs == GlyphSupport.Ascii)
    assert(detected.withGlyphs(GlyphSupport.Full) == Theme.Light)
