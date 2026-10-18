package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** A progress signal mirrored on the terminal's taskbar through `AppServices.taskbarProgress`. */
final class TaskbarProgressSpec extends AnyFunSuite with PilotFixture:

  private final class DownloadApp extends TuiApp:
    val progress: Signal[Option[Double]] = Signal(Option.empty[Double])

    override def onStart(): Unit                  = services.taskbarProgress(progress)
    override def bindings: KeyBindings            = KeyBindings(
      binding("h", "half")(progress.set(Some(0.5))),
      binding("d", "done")(progress.set(Some(1.0))),
      binding("c", "clear")(services.clearTaskbarProgress()),
    )
    def view(using ReactiveScope, Theme): Element = text("downloading")

  test("every change to the bound signal reaches the taskbar once, and clearing unbinds it"):
    val backend = HeadlessBackend(Size(30, 4))
    val app     = DownloadApp()
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    // nothing to show yet: an empty source is not pushed as a clear on a taskbar that shows nothing
    assert(backend.taskbarProgressRequests.isEmpty)
    pilot.press("h").waitUntil("half reached the taskbar")(backend.currentTaskbarProgress.contains(0.5))
    pilot.press("h").waitForIdle() // the same value again costs nothing
    pilot.press("d").waitUntil("done reached the taskbar")(backend.currentTaskbarProgress.contains(1.0))
    assert(backend.taskbarProgressRequests == Seq(Some(0.5), Some(1.0)))
    pilot.press("c").waitUntil("the indicator was cleared")(backend.currentTaskbarProgress.isEmpty)
    assert(backend.taskbarProgressRequests == Seq(Some(0.5), Some(1.0), None))
    // unbound: a later change to the signal no longer reaches the terminal
    pilot.press("h").waitForIdle()
    assert(backend.taskbarProgressRequests.sizeIs == 3)
    pilot.interrupt()
    val _ = pilot.awaitTermination()

  test("a run that exits with progress showing hands the taskbar back cleared"):
    val backend = HeadlessBackend(Size(30, 4))
    val pilot   = startPilot(backend)(DownloadApp().runWith(backend))
    pilot.waitForIdle()
    pilot.press("h").waitUntil("half reached the taskbar")(backend.currentTaskbarProgress.contains(0.5))
    pilot.interrupt()
    assert(pilot.awaitTermination())
    assert(backend.currentTaskbarProgress.isEmpty)
