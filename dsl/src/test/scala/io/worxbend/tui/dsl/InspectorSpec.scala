package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent, Size}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** The F12 inspector overlay: what node is under the pointer (or focused), where it was laid out and why. */
final class InspectorSpec extends AnyFunSuite with PilotFixture:

  private final class TwoPanels(keys: FrameworkKeys = FrameworkKeys.default) extends TuiApp:
    override def frameworkKeys: FrameworkKeys     = keys
    def view(using ReactiveScope, Theme): Element =
      row(
        panel("Left")(text("notes"), button("Open")(()).key("open").length(1)),
        panel("Right")(text("draft"), button("Save")(()).key("save").length(1)),
      )

  private def started(app: TuiApp): (HeadlessBackend, io.worxbend.tui.testsupport.Pilot) =
    val backend = HeadlessBackend(Size(48, 12))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    (backend, pilot)

  test("F12 with the pointer on a button shows the button's type, key, bounds, constraint and measurement"):
    val app        = TwoPanels()
    val (_, pilot) = started(app)
    val save       = pilot.readOnRenderThread(app.boundsOf("save"))
    val saveBounds = save.getOrElse(fail("save was not painted"))
    pilot.press("f12").hover(saveBounds).waitForIdle()
    val screen     = pilot.screenText
    assert(screen.contains("button key=save"), screen)
    assert(screen.contains(s"${saveBounds.x},${saveBounds.y} ${saveBounds.width}x${saveBounds.height}"), screen)
    assert(screen.contains("Length(1)"), screen)
    pilot.assertGolden("inspector-two-panel")

  test("with no pointer the inspector describes the focused node"):
    val (_, pilot) = started(TwoPanels())
    pilot.press("f12").waitForIdle()
    assert(pilot.screenText.contains("button key=open"), pilot.screenText)
    assert(pilot.screenText.contains("focus  0"), pilot.screenText)

  test("a second F12 adds the element tree, a third closes the overlay"):
    val (_, pilot) = started(TwoPanels())
    val before     = pilot.screenText
    pilot.press("f12").waitForIdle()
    assert(!pilot.screenText.contains("panel @"), pilot.screenText)
    pilot.press("f12").waitForIdle()
    assert(pilot.screenText.contains("row @ 0,0 48x12"), pilot.screenText)
    pilot.press("f12").waitForIdle()
    assert(pilot.screenText == before)

  test("the inspector key is a rebindable framework key"):
    val keys       = FrameworkKeys.default.rebind(Map(FrameworkKeys.Inspector -> KeySequence.parse("f11").toOption.get))
    assert(keys.inspector == KeyEvent.of(KeyCode.F(11)))
    val (_, pilot) = started(TwoPanels(keys))
    val before     = pilot.screenText
    pilot.press("f12").waitForIdle()
    assert(pilot.screenText == before)
    pilot.press("f11").waitForIdle()
    assert(pilot.screenText.contains("inspector"), pilot.screenText)
