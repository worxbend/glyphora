package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, ColorScheme, Size}
import io.worxbend.tui.terminal.{HeadlessBackend, TerminalCapabilities}
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** `Theme.auto` following the terminal's colour scheme, and `lightDark` picking a colour by it. */
final class ColorSchemeThemeSpec extends AnyFunSuite with PilotFixture:

  /** An app on the default theme that prints the name of the theme it was handed and which colour `lightDark` chose. */
  private final class AutoApp extends TuiApp:
    def view(using ReactiveScope, Theme): Element =
      val pick = if lightDark(Color.Black, Color.White) == Color.Black then "black-text" else "white-text"
      column(text(s"theme=${summon[Theme].name}"), text(s"scheme=$colorScheme"), text(pick))

  private final class PinnedApp extends TuiApp:
    override def theme: Theme                     = Theme.HighContrast
    def view(using ReactiveScope, Theme): Element = text(s"theme=${summon[Theme].name}")

  private def lightTerminal(): HeadlessBackend =
    val backend = HeadlessBackend(Size(30, 5))
    backend.capabilitiesTo(TerminalCapabilities(backgroundColor = Some(Color.Rgb(0xff, 0xff, 0xff))))
    backend

  private def running(backend: HeadlessBackend, app: TuiApp)(body: Pilot => Unit): Unit =
    val pilot = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    body(pilot)
    pilot.interrupt()
    val _     = pilot.awaitTermination()

  test("Theme.auto resolves to Theme.Light under a headless backend reporting a light background"):
    running(lightTerminal(), AutoApp()) { pilot =>
      assert(pilot.screenLines.head == "theme=light")
      assert(pilot.screenLines(1) == "scheme=Light")
    }

  test("a terminal that reported nothing gets Theme.Dark, exactly as before the terminal could be asked"):
    running(HeadlessBackend(Size(30, 5)), AutoApp()) { pilot =>
      assert(pilot.screenLines.head == "theme=dark")
      assert(pilot.screenLines(1) == "scheme=Unknown")
    }

  test("a mode 2031 report flips the signal, and the auto theme follows on the next frame"):
    running(lightTerminal(), AutoApp()) { pilot =>
      assert(pilot.screenLines.head == "theme=light")
      // the bytes a mode 2031 terminal sends when the user switches to a dark system theme
      pilot
        .sendBytes("\u001b[?997;1n".map(_.toInt)*)
        .waitUntil("the theme followed")(
          pilot.screenLines.headOption.contains("theme=dark")
        )
      assert(pilot.screenLines(1) == "scheme=Dark")
      pilot
        .sendBytes("\u001b[?997;2n".map(_.toInt)*)
        .waitUntil("and back")(
          pilot.screenLines.headOption.contains("theme=light")
        )
    }

  test("lightDark picks the light colour on a light terminal and the dark one otherwise"):
    running(lightTerminal(), AutoApp())(pilot => assert(pilot.screenLines(2) == "black-text"))
    running(HeadlessBackend(Size(30, 5)), AutoApp())(pilot => assert(pilot.screenLines(2) == "white-text"))

  test("an app that pins its theme is left alone whatever the terminal reports"):
    running(lightTerminal(), PinnedApp())(pilot => assert(pilot.screenLines.head == "theme=high-contrast"))

  test("Theme.auto outside a run is Dark under another name, and forScheme maps the three states"):
    assert(Theme.auto.primary == Theme.Dark.primary)
    assert(Theme.auto.name == "auto")
    assert(Theme.forScheme(ColorScheme.Light) eq Theme.Light)
    assert(Theme.forScheme(ColorScheme.Dark) eq Theme.Dark)
    assert(Theme.forScheme(ColorScheme.Unknown) eq Theme.Dark)
    // the NoOp services report an unknown scheme, so a helper built outside an app takes the dark colour
    given AppServices   = AppServices.NoOp
    given ReactiveScope = ReactiveScope.untracked
    assert(lightDark(Color.Black, Color.White) == Color.White)
