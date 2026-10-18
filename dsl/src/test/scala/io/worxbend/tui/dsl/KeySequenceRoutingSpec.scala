package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.RunnerConfig
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.{ManualClock, Pilot}

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** Key sequences through a running app: the pending prefix `TuiApp` keeps between key presses, its timeout on the tick,
  * and the fall-back that hands a key which continues no sequence to the ordinary single-key bindings.
  */
final class KeySequenceRoutingSpec extends AnyFunSuite with PilotFixture:

  private final class VimApp extends TuiApp:
    var tops: Int  = 0
    var cuts: Int  = 0
    var saves: Int = 0

    override def config: RunnerConfig = RunnerConfig(tickRate = Some(20.millis))

    override def bindings: KeyBindings = KeyBindings(
      binding("g g", "go to top")(tops += 1),
      binding("x", "cut")(cuts += 1),
      binding("ctrl+k ctrl+s", "save all")(saves += 1),
      binding("ctrl+q", "quit")(quit()),
    )

    def view(using ReactiveScope, Theme): Element = text("vim")

  private def start(): (VimApp, Pilot, ManualClock) =
    val backend = HeadlessBackend(Size(40, 3))
    val clock   = ManualClock()
    val app     = VimApp()
    val pilot   = startPilot(backend)(app.runWith(backend, clock.reading))
    pilot.waitForIdle()
    (app, pilot, clock)

  private def quitApp(pilot: Pilot): Unit =
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())

  test("g g fires its binding exactly once"):
    val (app, pilot, _) = start()
    pilot.press("g").waitForIdle()
    assert(app.tops == 0)
    pilot.press("g").waitForIdle()
    assert(app.tops == 1)
    pilot.press("g").waitForIdle() // a third g only starts the next sequence
    assert(app.tops == 1)
    quitApp(pilot)

  test("a prefix left waiting past the sequence timeout is dropped on the tick"):
    val (app, pilot, clock) = start()
    pilot.press("g").waitForIdle()
    pilot.advanceClock(clock, 1100.millis, draws = 0).waitForIdle()
    pilot.press("g").waitForIdle()
    assert(app.tops == 0, "the second g started a new prefix rather than completing the timed-out one")
    quitApp(pilot)

  test("a key that continues no sequence drops the prefix and reaches the single-key bindings"):
    val (app, pilot, _) = start()
    pilot.press("g", "x").waitForIdle()
    assert(app.cuts == 1)
    assert(app.tops == 0)
    pilot.press("ctrl+k", "ctrl+s").waitForIdle()
    assert(app.saves == 1)
    quitApp(pilot)

  test("esc cancels a pending prefix"):
    val (app, pilot, _) = start()
    pilot.press("g", "esc", "g").waitForIdle()
    assert(app.tops == 0)
    quitApp(pilot)
