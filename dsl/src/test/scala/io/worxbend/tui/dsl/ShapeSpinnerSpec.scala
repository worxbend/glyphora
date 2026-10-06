package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, Size}
import io.worxbend.tui.runtime.RunnerConfig
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets as w

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** The area-filling spinners at the DSL layer: themed at construction, driven by the ambient clock, and sizing
  * themselves honestly.
  */
final class ShapeSpinnerSpec extends AnyFunSuite with PilotFixture:

  private def renderWith(chosen: Theme, size: Size = Size(24, 8))(view0: Theme ?=> ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(size)
    val app     = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(tickRate = Some(20.millis))
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0(using chosen)
    startPilot(backend) { app.runWith(backend) }.waitForIdle()

  private def close(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("an orbit spinner takes its path and arc colours from the theme"):
    AnimationClockLock.frozenAt(0.millis):
      val pilot  = renderWith(Theme.Dark)(orbitSpinner().radius(3))
      val colors = (for
        y <- 0 until 8
        x <- 0 until 24
      yield pilot.cellAt(x, y)).filter(_.symbol.trim.nonEmpty).map(_.style.fg).distinct
      assert(colors.contains(Theme.Dark.loading.track.fg), "the resting path should take the theme's track colour")
      assert(colors.contains(Theme.Dark.loading.spinner.fg), "the arc should take the theme's spinner colour")
      close(pilot)

  /** `.fg(...)` recolors the moving part and leaves the resting path themed, the rule the one-glyph spinner already
    * follows for its glyph and its label.
    */
  test("an explicit colour patches the arc and not the path"):
    AnimationClockLock.frozenAt(0.millis):
      val pilot = renderWith(Theme.Dark)(orbitSpinner().radius(3).fg(Color.Magenta))
      val fgs   = (for
        y <- 0 until 8
        x <- 0 until 24
      yield pilot.cellAt(x, y)).filter(_.symbol.trim.nonEmpty).map(_.style.fg).distinct
      assert(fgs.contains(Some(Color.Magenta)), "the arc should take the explicit colour")
      assert(fgs.contains(Theme.Dark.loading.track.fg), "the path should keep the theme")
      close(pilot)

  /** A fixed radius claims an exact box, so a row of them lays out predictably; a fitted one takes what it is given. */
  test("a fixed radius claims its exact box and a fitted one fills"):
    AnimationClockLock.frozenAt(0.millis):
      val pilot  = renderWith(Theme.Dark)(row(orbitSpinner().radius(2), orbitSpinner()))
      val expect =
        w.OrbitSpinner(0.millis, w.OrbitSpinnerOptions(radius = Some(2)))
          .preferredSize
          .getOrElse(fail("a radius must claim a size"))
      assert(expect.width > 0 && expect.height > 0)
      assert(pilot.screenText.trim.nonEmpty)
      close(pilot)

  /** Rendered tall on purpose: at `CanvasResolution.Cell` one dot is one cell, so an ASCII orbit of radius 2 is five
    * rows rather than two — the figure is the same size in dots and therefore larger in cells.
    */
  test("the fluent methods reach every widget knob without losing the theme"):
    AnimationClockLock.frozenAt(300.millis):
      val pilot = renderWith(Theme.Dark, Size(30, 16))(
        column(
          orbitSpinner().path(OrbitPath.Square).radius(2).solid.reversed.sweep(0.5),
          orbitSpinner().radius(2).markers("*"),
          linearSpinner().bouncing.reversed.solid,
          spinnerGrid().preset(SpinnerPreset.Line).uniform,
        )
      )
      assert(pilot.screenText.contains("*"), "the ascii marker should reach the widget")
      assert(pilot.screenText.exists(c => "|/-\\".contains(c)), "the ascii preset should reach the grid")
      close(pilot)

  test("a linear spinner claims one row across and one column down"):
    AnimationClockLock.frozenAt(0.millis):
      val pilot = renderWith(Theme.Dark)(column(linearSpinner(), text("below")))
      assert(pilot.screenLines(1).contains("below"), "a horizontal track should take exactly one row")
      close(pilot)

  /** The whole point of the ambient clock: these animate with no counter at the call site. */
  test("the shape spinners animate on the ambient clock"):
    AnimationClockLock.frozenAt(0.millis):
      val backend = HeadlessBackend(Size(24, 8))
      val app     = new TuiApp:
        override def config: RunnerConfig             = RunnerConfig(tickRate = Some(10.millis))
        override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
        def view(using ReactiveScope, Theme): Element = orbitSpinner().radius(3)
      val pilot   = startPilot(backend) { app.runWith(backend) }.waitForIdle()
      val before  = backend.drawCount
      val until   = System.nanoTime() + 400_000_000L
      while System.nanoTime() < until do pilot.waitForIdle()
      assert(backend.drawCount > before, "an orbit spinner should repaint on the ticks")
      close(pilot)
