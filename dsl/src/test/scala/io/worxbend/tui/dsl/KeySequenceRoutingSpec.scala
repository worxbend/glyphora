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

/** The which-key popup `TuiApp` lays over the view while a prefix is pending. */
final class WhichKeyPopupSpec extends AnyFunSuite with PilotFixture:

  private final class EditorApp(popup: Boolean) extends TuiApp:
    override def whichKey: Boolean = popup

    override def bindings: KeyBindings = KeyBindings(
      binding("ctrl+k ctrl+s", "save all")(()),
      binding("ctrl+k ctrl+w", "close all")(()),
      binding("ctrl+k z", "zen mode")(()),
      binding("ctrl+q", "quit")(quit()),
    )

    def view(using ReactiveScope, Theme): Element =
      column(text("editor body").fill, statusBar(activeBindings))

  private def start(popup: Boolean = true): Pilot =
    val backend = HeadlessBackend(Size(44, 9))
    val pilot   = startPilot(backend)(EditorApp(popup).runWith(backend))
    pilot.waitForIdle()

  test("after ctrl+k the popup lists the continuations and the status bar shows the prefix"):
    val pilot = start()
    pilot.press("ctrl+k").waitForIdle()
    pilot.assertGolden("which-key-ctrl-k")
    pilot.press("esc").waitForIdle()
    assert(!pilot.screenText.contains("zen mode"), "cancelling the prefix closes the popup and restores the hints")
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())

  test("an app with no tickRate borrows ambient ticks to time a pending prefix out"):
    val backend = HeadlessBackend(Size(44, 9))
    val clock   = ManualClock()
    val pilot   = startPilot(backend)(EditorApp(popup = true).runWith(backend, clock.reading))
    pilot.waitForIdle()
    pilot.press("ctrl+k").waitForIdle()
    assert(pilot.screenText.contains("zen mode"))
    val _       = clock.advance(2.seconds)
    pilot.waitUntil("the prefix to time out")(!pilot.screenText.contains("zen mode"))
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())

  test("whichKey = false keeps the popup off while still showing the prefix in the status bar"):
    val pilot = start(popup = false)
    pilot.press("ctrl+k").waitForIdle()
    assert(pilot.screenLines.count(_.contains("save all")) == 1, "only the status bar lists the continuations")
    assert(pilot.screenText.contains("ctrl+k …   │  ctrl+s save all"))
    pilot.press("esc", "ctrl+q")
    assert(pilot.awaitTermination())

/** A pending prefix and a focused text input: a key that continues no sequence is typed, not swallowed. */
final class PendingPrefixTextInputSpec extends AnyFunSuite with PilotFixture:

  private final class FormApp extends TuiApp:
    val field: TextInputState = TextInputState()
    var zens: Int             = 0

    override def bindings: KeyBindings = KeyBindings(
      binding("f2 z", "zen mode")(zens += 1),
      binding("ctrl+q", "quit")(quit()),
    )

    def view(using ReactiveScope, Theme): Element = column(input(field).key("name").autofocus)

  test("with a prefix pending, a key no sequence continues reaches the focused input"):
    val backend = HeadlessBackend(Size(40, 6))
    val app     = FormApp()
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.press("f2").waitForIdle()
    pilot.press("a").waitForIdle()
    assert(app.field.value == "a")
    pilot.press("f2", "z").waitForIdle()
    assert(app.zens == 1)
    assert(app.field.value == "a")
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())
