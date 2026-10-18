package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Focus scopes: a `focusGroup` is one tab stop with arrows roving inside it, `.trapFocus` keeps Tab inside a subtree,
  * and Alt+arrows move focus spatially when the app opts in.
  */
final class FocusGroupSpec extends AnyFunSuite with PilotFixture:

  private abstract class KeyedApp extends TuiApp:
    def focused: Option[String] = focusedKey

  private def run(app: KeyedApp, size: Size = Size(40, 6))(body: Pilot => Unit): Unit =
    val backend = HeadlessBackend(size)
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    body(pilot)
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  private def focusAfter(pilot: Pilot, app: KeyedApp, key: String): Option[String] =
    pilot.press(key).waitForIdle()
    pilot.readOnRenderThread(app.focused)

  test("one Tab enters a focus group, Right and Left cycle inside it, and Tab leaves to the next stop"):
    val app = new KeyedApp:
      def view(using ReactiveScope, Theme): Element =
        column(
          button("before")(()).key("before"),
          focusGroup(button("1")(()).key("g1"), button("2")(()).key("g2"), button("3")(()).key("g3")),
          button("after")(()).key("after"),
        )
    run(app) { pilot =>
      assert(focusAfter(pilot, app, "tab") == Some("g1"))
      assert(focusAfter(pilot, app, "right") == Some("g2"))
      assert(focusAfter(pilot, app, "right") == Some("g3"))
      assert(focusAfter(pilot, app, "right") == Some("g1"))
      assert(focusAfter(pilot, app, "left") == Some("g3"))
      assert(focusAfter(pilot, app, "tab") == Some("after"))
      // back in: the group remembers the member it was left on
      assert(focusAfter(pilot, app, "shift+tab") == Some("g3"))
      assert(focusAfter(pilot, app, "shift+tab") == Some("before"))
    }

  test("trapFocus keeps Tab and Shift+Tab cycling inside the subtree that holds focus"):
    val app = new KeyedApp:
      def view(using ReactiveScope, Theme): Element =
        column(
          button("out")(()).key("out"),
          column(button("in1")(()).key("in1").autofocus, button("in2")(()).key("in2")).trapFocus,
        )
    run(app) { pilot =>
      assert(pilot.readOnRenderThread(app.focused) == Some("in1"))
      assert(focusAfter(pilot, app, "tab") == Some("in2"))
      assert(focusAfter(pilot, app, "tab") == Some("in1"))
      assert(focusAfter(pilot, app, "shift+tab") == Some("in2"))
    }

  private final class GridApp(spatial: Boolean) extends KeyedApp:
    override def spatialNavigation: Boolean       = spatial
    def view(using ReactiveScope, Theme): Element =
      column(
        row(button("tl")(()).key("tl"), button("tr")(()).key("tr")).length(1),
        row(button("bl")(()).key("bl"), button("br")(()).key("br")).length(1),
      )

  test("Alt+Right from the top-left of a 2x2 grid focuses the top-right; Alt+Down then the bottom-right"):
    val app = GridApp(spatial = true)
    run(app) { pilot =>
      assert(pilot.readOnRenderThread(app.focused) == Some("tl"))
      assert(focusAfter(pilot, app, "alt+right") == Some("tr"))
      assert(focusAfter(pilot, app, "alt+down") == Some("br"))
      assert(focusAfter(pilot, app, "alt+left") == Some("bl"))
      assert(focusAfter(pilot, app, "alt+up") == Some("tl"))
      // nothing further up: focus stays
      assert(focusAfter(pilot, app, "alt+up") == Some("tl"))
    }

  test("spatial navigation is off unless the app asks for it"):
    val app = GridApp(spatial = false)
    run(app) { pilot =>
      assert(focusAfter(pilot, app, "alt+right") == Some("tl"))
    }
