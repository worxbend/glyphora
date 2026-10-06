package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.NoticeLevel

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** A helper written outside any app class, which is the whole point of [[Notifications]]: it can say something to the
  * user without the app handing it a callback.
  */
private def announceSaved(name: String)(using notifications: Notifications): Unit =
  notifications.success(s"saved $name")

private def clearEverything()(using notifications: Notifications): Unit =
  notifications.dismissToasts()

final class NotificationsSpec extends AnyFunSuite with PilotFixture:

  private final class NotifyingApp extends TuiApp:
    // no argument is passed for `Notifications` anywhere below: the app's own `given` resolves it, which is the
    // compile-level half of what this suite asserts
    override def bindings: KeyBindings            = KeyBindings(
      binding("s", "save")(announceSaved("report.csv")),
      binding("c", "clear")(clearEverything()),
      binding("e", "fail")(notifications.error("disk full")),
      binding("d", "fail the long way")(notifications.notify("disk full", NoticeLevel.Error, 3.seconds)),
    )
    def view(using ReactiveScope, Theme): Element = text("body")

  private def driving(body: Pilot => Unit): Unit =
    val backend = HeadlessBackend(Size(30, 6))
    val pilot   = startPilot(backend)(NotifyingApp().runWith(backend))
    pilot.waitForIdle()
    body(pilot)
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("a helper outside the app class raises a toast through Notifications"):
    driving { pilot =>
      assert(!pilot.screenText.contains("saved"))
      // a live toast keeps the ambient ticker armed, so the app never goes idle while one is showing
      pilot.press("s").waitUntil("the toast is on the frame")(pilot.screenText.contains("saved report.csv"))
    }

  test("dismissToasts from the same outside helper clears the overlay"):
    driving { pilot =>
      pilot.press("s").waitUntil("the toast is on the frame")(pilot.screenText.contains("saved report.csv"))
      pilot.press("c").waitUntil("the toast is gone")(!pilot.screenText.contains("saved report.csv"))
    }

  test("the error shorthand renders the same frame as the full notify call"):
    val shorthand = frameAfter("e")
    val explicit  = frameAfter("d")
    assert(shorthand == explicit)
    assert(shorthand.exists(_.contains("disk full")))

  private def frameAfter(key: String): Seq[String] =
    val backend = HeadlessBackend(Size(30, 6))
    val pilot   = startPilot(backend)(NotifyingApp().runWith(backend))
    pilot.waitForIdle()
    pilot.press(key).waitUntil("the toast is on the frame")(pilot.screenText.contains("disk full"))
    val lines   = pilot.screenLines
    pilot.interrupt()
    val _       = pilot.awaitTermination()
    lines
