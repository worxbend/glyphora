package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions

import org.scalatest.funsuite.AnyFunSuite

/** Helpers written outside every app class, which is what [[AppServices]] exists for. */
private def openSettings(using services: AppServices): Unit =
  services.pushScreen(Screen(text("settings pane")))

private def leaveSettings(using services: AppServices): Unit =
  services.popScreen()

/** A view helper that takes the services it needs, so it can be built both inside an app and outside one. */
private def quitFooter(using AppServices): Element =
  button("Quit")(summon[AppServices].quit())

final class AppServicesHandleSpec extends AnyFunSuite with PilotFixture:

  private final class NavApp extends TuiApp:
    // nothing passes `services` explicitly: the app's own `given` resolves it for both helpers
    override def bindings: KeyBindings            = KeyBindings(
      binding("o", "open settings")(openSettings),
      binding("c", "close settings")(leaveSettings),
    )
    def view(using ReactiveScope, Theme): Element = column(text("main pane"), quitFooter)

  test("an outside helper pushes and pops a screen through AppServices"):
    val backend = HeadlessBackend(Size(30, 6))
    val pilot   = startPilot(backend)(NavApp().runWith(backend))
    pilot.waitForIdle()
    assert(!pilot.screenText.contains("settings pane"))
    pilot.press("o").waitForIdle()
    assert(pilot.screenText.contains("settings pane"))
    pilot.press("c").waitForIdle()
    assert(!pilot.screenText.contains("settings pane"))
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("quit reaches the runner from a helper that only has AppServices"):
    val backend = HeadlessBackend(Size(30, 6))
    val pilot   = startPilot(backend)(NavApp().runWith(backend))
    pilot.waitForIdle()
    pilot.press("tab").waitForIdle() // focus the footer button
    pilot.press("enter")
    assert(pilot.awaitTermination())

  test("AppServices.NoOp lets the same helper build and render with no app running"):
    given AppServices = AppServices.NoOp
    val area          = Rect(0, 0, 10, 1)
    val buffer        = Buffer(area)
    quitFooter.widget.render(area, buffer)
    assert(BufferAssertions.text(buffer).contains("Quit"))
    // and every action is inert rather than throwing, which is what makes the value safe to construct against
    AppServices.NoOp.quit()
    AppServices.NoOp.requestRedraw()
    AppServices.NoOp.info("nobody sees this")
