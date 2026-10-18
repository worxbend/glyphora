package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Modifiers, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** The framework-written interaction states: `.disabled` takes a control out of the tab order and out of event delivery
  * while leaving it drawn in the theme's disabled style.
  */
final class InteractionStateSpec extends AnyFunSuite with PilotFixture:

  /** Three one-row buttons on a 30x4 frame; `B`, on row 1, is disabled. Each label is centred, so `[ B ]` starts at
    * column 12.
    */
  private final class DisabledApp extends TuiApp:
    var presses: Map[String, Int]                 = Map.empty.withDefaultValue(0)
    private def pressed(name: String): Unit       = presses = presses.updated(name, presses(name) + 1)
    def view(using ReactiveScope, Theme): Element =
      column(
        button("A")(pressed("a")).key("a"),
        button("B")(pressed("b")).key("b").disabled(true),
        button("c")(pressed("c")).key("c"),
      )
    def focused: Option[String]                   = focusedKey
    def focusB(): Boolean                         = focusTo("b")

  private def started(): (DisabledApp, Pilot) =
    val backend = HeadlessBackend(Size(30, 4))
    val app     = DisabledApp()
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    (app, pilot)

  private def stop(pilot: Pilot): Unit =
    pilot.interrupt()
    val _ = pilot.awaitTermination()

  test("Tab skips a disabled button and Enter lands on the one after it"):
    val (app, pilot) = started()
    assert(pilot.readOnRenderThread(app.focused) == Some("a"))
    pilot.press("tab").waitForIdle()
    assert(pilot.readOnRenderThread(app.focused) == Some("c"))
    pilot.press("enter").waitForIdle()
    assert(app.presses("c") == 1 && app.presses("b") == 0)
    stop(pilot)

  test("a click on a disabled button neither activates it nor moves focus, and focusTo refuses it"):
    val (app, pilot) = started()
    pilot.click(app.boundsOf("b").get).waitForIdle()
    assert(app.presses("b") == 0)
    assert(pilot.readOnRenderThread(app.focused) == Some("a"))
    assert(!pilot.readOnRenderThread(app.focusB()))
    stop(pilot)

  test("a disabled button is drawn in the disabled style and the tree snapshot says so"):
    val (app, pilot) = started()
    assert(pilot.screenLines(1).trim == "[ B ]")
    assert(pilot.cellAt(12, 1).style.modifiers.hasAny(Modifiers.Dim), pilot.cellAt(12, 1).toString)
    assert(!pilot.cellAt(12, 2).style.modifiers.hasAny(Modifiers.Dim))
    val snapshot     = pilot.readOnRenderThread(ElementTreeSnapshot.render(app))
    assert(snapshot.linesIterator.exists(line => line.contains("key=b") && line.contains("disabled")), snapshot)
    stop(pilot)

  test("the accessible label of a disabled button announces it, and an enabled one says nothing extra"):
    val (app, pilot) = started()
    assert(pilot.readOnRenderThread(app.accessibleLabel("b")) == Some("button B, disabled"))
    assert(pilot.readOnRenderThread(app.accessibleLabel("a")) == Some("button A, focused"))
    assert(pilot.readOnRenderThread(app.accessibleLabel("c")) == Some("button c"))
    assert(pilot.readOnRenderThread(app.accessibleLabel("nope")).isEmpty)
    stop(pilot)
