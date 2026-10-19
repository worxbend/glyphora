package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.{DesktopNotification, HeadlessBackend, Support, TerminalCapabilities}

import org.scalatest.funsuite.AnyFunSuite

/** A helper outside any app class, reaching the desktop through the same [[Notifications]] value a toast goes through.
  */
private def announceFinished()(using notifications: Notifications): Unit =
  notifications.desktop("build", "all tests passed")

/** `Notifications.desktop`: a toast always, and a desktop notification only while the terminal window is unfocused. */
final class DesktopNotificationSpec extends AnyFunSuite with PilotFixture:

  private final class BuildApp extends TuiApp:
    override def bindings: KeyBindings            = KeyBindings(binding("n", "announce")(announceFinished()))
    def view(using ReactiveScope, Theme): Element = text("body")

  test("while the terminal is focused the message is a toast and nothing reaches the desktop"):
    val backend = HeadlessBackend(Size(40, 6))
    val pilot   = startPilot(backend)(BuildApp().runWith(backend))
    pilot.waitForIdle()
    pilot.press("n").waitUntil("the toast is on the frame")(pilot.screenText.contains("build: all tests passed"))
    assert(backend.desktopNotifications.isEmpty)
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("once the terminal reported losing focus the same call also raises a desktop notification"):
    val backend = HeadlessBackend(Size(40, 6))
    val pilot   = startPilot(backend)(BuildApp().runWith(backend))
    pilot.waitForIdle()
    pilot.focusLost().waitForIdle()
    pilot.press("n").waitUntil("the notification went out")(backend.desktopNotifications.nonEmpty)
    assert(backend.desktopNotifications == Seq(DesktopNotification("build", "all tests passed")))
    // the toast is still there for when the user comes back
    pilot.waitUntil("the toast is on the frame")(pilot.screenText.contains("build: all tests passed"))
    // and focus coming back switches the desktop half off again
    pilot.focusGained().waitForIdle()
    pilot.press("n").waitForIdle()
    assert(backend.desktopNotifications.sizeIs == 1)
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("a terminal whose capabilities deny notifications is never asked"):
    val backend = HeadlessBackend(Size(40, 6))
    backend.capabilitiesTo(TerminalCapabilities(desktopNotifications = Support.No))
    val pilot   = startPilot(backend)(BuildApp().runWith(backend))
    pilot.waitForIdle()
    pilot.focusLost().waitForIdle()
    pilot.press("n").waitUntil("the toast is on the frame")(pilot.screenText.contains("build: all tests passed"))
    assert(backend.desktopNotifications.isEmpty)
    pilot.interrupt()
    val _       = pilot.awaitTermination()
