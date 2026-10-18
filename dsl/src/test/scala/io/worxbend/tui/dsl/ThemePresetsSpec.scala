package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

/** The palette presets: a styled golden of the showcase's status bar under each, and the rules every preset keeps. */
final class ThemePresetsSpec extends AnyFunSuite:

  /** The showcase's own key bindings, so the bar is the one the showcase draws. */
  private val bindings = KeyBindings(
    binding("ctrl+t", "switch theme")(()),
    binding("ctrl+n", "show a toast")(()),
    binding("ctrl+o", "open modal")(()),
    binding("esc", "quit")(()),
  )

  private def statusBarUnder(theme: Theme): Buffer =
    val area            = Rect(0, 0, 80, 1)
    val buffer          = Buffer(area)
    given ReactiveScope = ReactiveScope.untracked
    ElementHost().render(area, buffer, theme, statusBar(bindings)(using theme))
    buffer

  // one literal fixture name per test, so the fixture-discipline check can see which test owns which file
  test("the showcase status bar under dark"):
    GoldenFrames.assertMatchesStyled("status-bar-dark", statusBarUnder(Theme.Dark))

  test("the showcase status bar under light"):
    GoldenFrames.assertMatchesStyled("status-bar-light", statusBarUnder(Theme.Light))

  test("the showcase status bar under high-contrast"):
    GoldenFrames.assertMatchesStyled("status-bar-high-contrast", statusBarUnder(Theme.HighContrast))

  test("the showcase status bar under dracula"):
    GoldenFrames.assertMatchesStyled("status-bar-dracula", statusBarUnder(Theme.Dracula))

  test("the showcase status bar under catppuccin"):
    GoldenFrames.assertMatchesStyled("status-bar-catppuccin", statusBarUnder(Theme.Catppuccin))

  test("the showcase status bar under nord"):
    GoldenFrames.assertMatchesStyled("status-bar-nord", statusBarUnder(Theme.Nord))

  test("the showcase status bar under gruvbox"):
    GoldenFrames.assertMatchesStyled("status-bar-gruvbox", statusBarUnder(Theme.Gruvbox))

  test("the showcase status bar under solarized"):
    GoldenFrames.assertMatchesStyled("status-bar-solarized", statusBarUnder(Theme.Solarized))

  test("the showcase status bar under ansi16"):
    GoldenFrames.assertMatchesStyled("status-bar-ansi16", statusBarUnder(Theme.Ansi16))

  test("the presets are the three originals plus the six palettes, each under a distinct name"):
    val names = Theme.presets.map(_.name)
    assert(
      names == Seq("dark", "light", "high-contrast", "dracula", "catppuccin", "nord", "gruvbox", "solarized", "ansi16")
    )

  test("every preset styles the primary and danger button variants"):
    Theme.presets.foreach { theme =>
      assert(!theme.component(ButtonRole.Primary).isEmpty, theme.name)
      assert(!theme.component(ButtonRole.Danger).isEmpty, theme.name)
    }
