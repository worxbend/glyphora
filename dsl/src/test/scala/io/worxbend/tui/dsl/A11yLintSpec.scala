package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, Size, Style}
import io.worxbend.tui.runtime.{A11yRule, ReactiveScope}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.TextInputState

import org.scalatest.funsuite.AnyFunSuite

/** `pilot.a11yReport(app)`: the accessibility lint over a running `TuiApp`'s last frame. Each rule is planted once. */
final class A11yLintSpec extends AnyFunSuite with PilotFixture:

  private final class App(body: (ReactiveScope, Theme) ?=> Element, modal: Option[Screen] = None) extends TuiApp:
    override def theme: Theme                     = Theme.Dark
    override def onStart(): Unit                  = modal.foreach(pushScreen)
    def view(using ReactiveScope, Theme): Element = body

  private def started(app: TuiApp): Pilot =
    val backend = HeadlessBackend(Size(40, 8))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()

  private def rules(pilot: Pilot, app: TuiApp): Seq[A11yRule] = pilot.a11yReport(app).map(_.rule)

  test("a clean form — a focused input and a button — has no findings"):
    val state = TextInputState()
    val app   = App(column(input(state).key("name"), button("Save")(()).key("save")))
    val pilot = started(app)
    assert(pilot.a11yReport(app).isEmpty, pilot.a11yReport(app))

  test("blue text on a grey background is flagged as low contrast, with where it is"):
    val app   = App(column(text("status: degraded").style(Style.fg(Color.Blue).withBg(Color.BrightBlack)), text("ok")))
    val pilot = started(app)
    val findings = pilot.a11yReport(app)
    assert(findings.map(_.rule) == Seq(A11yRule.LowContrast), findings)
    assert(findings.head.area.exists(_.y == 0), findings)
    assert(findings.head.message.contains("Blue"), findings)

  // a picture's half-block cells pair neighbouring pixels as foreground and background; they are not text to read
  test("the cells of a picture are not measured for contrast"):
    val muddy = RgbaImage.tabulate(8, 8)((x, y) => 0xff000000 | ((x * y * 3) << 8) | 0x606060)
    val app   = App(column(pixelImage(muddy), text("caption").style(Style.fg(Color.Blue).withBg(Color.BrightBlack))))
    val pilot = started(app)
    val found = pilot.a11yReport(app)
    assert(found.map(_.rule) == Seq(A11yRule.LowContrast), found)
    assert(found.head.message.contains("Blue"), found)

  test("a focusable that paints the same focused and unfocused is flagged"):
    val app   = App(column(text("plain").focusable.key("plain"), button("Save")(())))
    val pilot = started(app)
    assert(rules(pilot, app) == Seq(A11yRule.NoFocusCue), pilot.a11yReport(app))

  test("a focused input whose frame parked no caret is flagged"):
    val state    = TextInputState()
    val app      = App(column(input(state).key("name")))
    val pilot    = started(app)
    val frame    = pilot.lastFrame
    val findings = pilot.readOnRenderThread(A11yLint.audit.findings(app, frame, None))
    assert(findings.map(_.rule) == Seq(A11yRule.MissingCaret), findings)

  test("a modal with no Esc dismissal and no Esc binding is flagged; one that closes on Esc is not"):
    val stuck  = App(text("base"), Some(Screen(text("are you sure?"), label = "confirm")))
    val pilot  = started(stuck)
    val found  = pilot.a11yReport(stuck)
    assert(found.map(_.rule) == Seq(A11yRule.ModalWithoutEscape), found)
    assert(found.head.message.contains("confirm"), found)
    val closes = App(text("base"), Some(Screen(text("are you sure?"), dismissal = Dismissal.Escape)))
    val other  = started(closes)
    assert(other.a11yReport(closes).isEmpty)
    val ready  = App(text("base"), Some(Screen.confirm("Quit", "Really?")((), ())))
    val third  = started(ready)
    assert(third.a11yReport(ready).isEmpty, third.a11yReport(ready))
