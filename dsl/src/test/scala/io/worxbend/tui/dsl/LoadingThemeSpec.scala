package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets as w

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** The loading elements resolve their colors from the ambient [[Theme]] at construction, the way `statusBar` does, so a
  * re-themed app re-themes its spinners and bars without touching a call site.
  */
final class LoadingThemeSpec extends AnyFunSuite with PilotFixture:

  /** The view is a `Theme ?=>` context function on purpose: these elements resolve their palette where the element is
    * *written*, so a given installed anywhere else would not reach them — which is exactly the property under test.
    */
  private def renderWith(chosen: Theme)(view0: Theme ?=> ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(Size(24, 3))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0(using chosen)
    startPilot(backend) { app.runWith(backend) }.waitForIdle()

  private def close(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("every built-in theme carries a loading palette"):
    Seq(Theme.Dark, Theme.Light, Theme.HighContrast).foreach: theme =>
      val loading = theme.loading
      assert(loading.spinner != Style.Default, s"${theme.name} has no spinner style")
      assert(loading.fill != loading.track, s"${theme.name} cannot tell a filled cell from an empty one")

  /** The whole point of theming: the same call site renders different colors under a different theme. */
  test("a spinner takes its color from the ambient theme"):
    val dark  = renderWith(Theme.Dark)(spinner("loading"))
    val glyph = dark.cellAt(0, 0)
    assert(glyph.style.fg == Theme.Dark.loading.spinner.fg)
    close(dark)

    val light = renderWith(Theme.Light)(spinner("loading"))
    assert(light.cellAt(0, 0).style.fg == Theme.Light.loading.spinner.fg)
    assert(light.cellAt(0, 0).style.fg != Theme.Dark.loading.spinner.fg, "the themes must actually differ")
    close(light)

  test("a spinner styles its glyph and its label separately"):
    val pilot = renderWith(Theme.Dark)(spinner("x"))
    assert(pilot.cellAt(0, 0).style.fg == Theme.Dark.loading.spinner.fg, "the glyph takes the accent")
    assert(pilot.cellAt(2, 0).style.fg == Theme.Dark.loading.label.fg, "the label recedes")
    close(pilot)

  /** An explicit style at the call site has to win over the theme, or `.fg(...)` would silently do nothing. */
  test("an explicit color overrides the theme on the glyph only"):
    val pilot = renderWith(Theme.Dark)(spinner("x").fg(Color.Magenta))
    assert(pilot.cellAt(0, 0).style.fg.contains(Color.Magenta))
    assert(pilot.cellAt(2, 0).style.fg == Theme.Dark.loading.label.fg, "the label keeps the theme's style")
    close(pilot)

  /** `labelStyle` takes a transform, not a replacement, because these elements are already themed by the time the
    * builder runs: `.labelStyle(Style.Default.dim)` — the only thing a replacing builder accepted — threw the theme's
    * caption colour away and reverted the caption to the terminal default under `Light` and `HighContrast`.
    */
  test("labelStyle derives from the theme's caption style rather than replacing it"):
    val pilot = renderWith(Theme.Light)(spinner("x").labelStyle(_.bold))
    val label = pilot.cellAt(2, 0).style
    assert(label.fg == Theme.Light.loading.label.fg, "the theme's caption colour survives the builder")
    assert(label.modifiers.hasAny(Modifiers.Bold), "and the builder's own modifier is applied on top")
    close(pilot)

  test("labelStyle can still replace the caption style outright"):
    val pilot = renderWith(Theme.Light)(spinner("x").labelStyle(_ => Style.Default.withFg(Color.Magenta)))
    assert(pilot.cellAt(2, 0).style.fg.contains(Color.Magenta))
    close(pilot)

  test("a progress bar takes its track and fill from the theme"):
    val pilot = renderWith(Theme.Dark)(progressBar(0.5).bare)
    assert(pilot.cellAt(0, 0).style.fg == Theme.Dark.loading.fill.fg)
    assert(pilot.cellAt(23, 0).style.fg == Theme.Dark.loading.track.fg)
    assert(pilot.cellAt(0, 0).style.fg != pilot.cellAt(23, 0).style.fg)
    close(pilot)

  /** The two progress meters are the same widget family to a reader of a view, so they have to be the same two colours
    * on screen. `gauge` used to be the one that skipped [[LoadingTheme]] and drew the widget-level reverse-video
    * default, which under `Light` put white-on-black next to a blue-on-grey `progressBar`.
    */
  test("a gauge takes the same track and fill from the theme as the progress bar beside it"):
    val pilot = renderWith(Theme.Light)(column(progressBar(0.5).bare, gauge(0.5).bare))
    assert(pilot.cellAt(0, 0).style.fg == Theme.Light.loading.fill.fg, "the bar's filled half")
    assert(pilot.cellAt(23, 0).style.fg == Theme.Light.loading.track.fg, "the bar's empty half")
    assert(pilot.cellAt(0, 1).style.fg == pilot.cellAt(0, 0).style.fg, "the gauge's filled half must match")
    assert(pilot.cellAt(23, 1).style.fg == pilot.cellAt(23, 0).style.fg, "the gauge's empty half must match")
    close(pilot)

  test("progressBar accepts a fraction or a pair of counts"):
    val pilot = renderWith(Theme.Dark)(column(progressBar(0.3), progressBar(3, 10), progressBar(1, 0)))
    assert(pilot.screenLines.head.startsWith("30%"))
    assert(pilot.screenLines(1).startsWith("30%"), "counts and the equivalent fraction must agree")
    assert(pilot.screenLines(2).startsWith("0%"), "a zero total must not produce NaN")
    close(pilot)

  /** Driven from an explicit clock rather than the ambient one. These elements are rendered with no runner behind them,
    * so the ambient [[AnimationClock]] they would read is the one every runner-less caller in this JVM shares: anything
    * else that pins it decides which frame this spinner is showing — which is exactly how this assertion failed in CI
    * while passing locally.
    */
  test("the fluent methods swap presets without losing the theme"):
    val pilot = renderWith(Theme.Dark)(
      column(
        spinnerAt(0.millis).preset(w.SpinnerPreset.Line).label("busy"),
        progressBar(0.5).preset(w.ProgressPreset.Ascii).bare,
        indeterminateBarAt(0.millis).motion(w.IndeterminateMotion.Sweep).preset(w.ProgressPreset.Ascii),
      )
    )
    assert(pilot.screenLines.head.startsWith("| busy"))
    assert(pilot.screenLines(1).startsWith("############"))
    assert(pilot.screenLines(2).forall(c => c == '#' || c == '-'))
    assert(pilot.cellAt(0, 0).style.fg == Theme.Dark.loading.spinner.fg, "a swapped preset keeps the theme")
    close(pilot)

  test("a bar can ramp its fill color across progress"):
    val low     = renderWith(Theme.Dark)(progressBar(0.0).bare.ramp(Color.Red, Color.Green))
    val lowFill = low.cellAt(0, 0).style.fg
    close(low)
    val high    = renderWith(Theme.Dark)(progressBar(1.0).bare.ramp(Color.Red, Color.Green))
    assert(high.cellAt(0, 0).style.fg != lowFill, "the ramp must move with progress")
    assert(high.cellAt(0, 0).style.fg.contains(w.ColorRamp(Color.Red, Color.Green).at(1.0)))
    close(high)

  test("a custom theme gets a coherent loading palette from LoadingTheme.from"):
    val derived = LoadingTheme.from(
      accent = Style.Default.withFg(Color.Magenta),
      muted = Style.Default.dim,
      surface = Style.Default.withFg(Color.White),
    )
    val custom  = Theme.Dark.copy(name = "custom", loading = derived)
    val pilot   = renderWith(custom)(spinner("x"))
    assert(pilot.cellAt(0, 0).style.fg.contains(Color.Magenta))
    close(pilot)
