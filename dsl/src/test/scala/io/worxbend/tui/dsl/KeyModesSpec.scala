package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.{BufferAssertions, Pilot}

import org.scalatest.funsuite.AnyFunSuite

/** Named key modes: only the current mode's bindings (and the mode-less ones) fire, a binding switches the mode through
  * `AppServices.keyMode`, and the chrome shows which mode is live.
  */
final class KeyModesSpec extends AnyFunSuite with PilotFixture:

  private final class ModalApp extends TuiApp:
    var typed: Int = 0

    override def bindings: KeyBindings =
      KeyBindings.modes(
        Mode("normal", binding("i", "insert mode")(services.keyMode.set(ModeId("insert")))),
        Mode(
          "insert",
          binding("esc", "normal mode")(keyMode.set(ModeId("normal"))),
          binding("x", "type an x")(typed += 1),
        ),
      ) ++ KeyBindings(binding("ctrl+q", "quit")(quit()))

    def view(using ReactiveScope, Theme): Element =
      column(text("body").fill, statusBar(activeBindings))

  private def start(): (ModalApp, Pilot) =
    val backend = HeadlessBackend(Size(60, 3))
    val app     = ModalApp()
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    (app, pilot)

  test("i enters insert mode, esc leaves it, and an insert-only binding is inert in normal mode"):
    val (app, pilot) = start()
    pilot.press("x").waitForIdle()
    assert(app.typed == 0, "x belongs to insert mode and must not fire in normal mode")
    pilot.press("i", "x").waitForIdle()
    assert(app.typed == 1)
    pilot.press("esc", "x").waitForIdle()
    assert(app.typed == 1)
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())

  test("the status bar names the current mode and advertises only its keys"):
    val (_, pilot) = start()
    assert(pilot.screenText.contains("[normal]"))
    assert(pilot.screenText.contains("i insert mode"))
    assert(!pilot.screenText.contains("type an x"))
    pilot.press("i").waitForIdle()
    assert(pilot.screenText.contains("[insert]"))
    assert(pilot.screenText.contains("x type an x"))
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())

  test("the help overlay lists the bindings of every mode under its name"):
    given Theme  = Theme.Dark
    val bindings = ModalApp().bindings
    val text     = BufferAssertions.text(BufferAssertions.rendered(helpOverlay(bindings).widget, 40, 12))
    assert(text.contains("normal:"))
    assert(text.contains("insert:"))
    assert(text.contains("type an x"))
    assert(text.contains("insert mode"))
