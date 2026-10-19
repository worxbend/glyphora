package io.worxbend.tui.examples.doctor

import io.worxbend.tui.core.{Color, KeyCode, KeyModifiers, Position, Size}
import io.worxbend.tui.terminal.{HeadlessBackend, Support, TerminalCapabilities}
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

final class DoctorAppSpec extends AnyFunSuite:

  private val env = Map(
    "TERM"         -> "xterm-kitty",
    "TERM_PROGRAM" -> "kitty",
    "LANG"         -> "en_US.UTF-8",
    "COLORTERM"    -> "truecolor",
  )

  private def withDoctor[A](body: (DoctorApp, Pilot) => A): A =
    val backend = HeadlessBackend(Size(90, 40))
    backend.capabilitiesTo(
      TerminalCapabilities(
        kittyKeyboard = Support.Yes,
        bracketedPaste = Support.Yes,
        backgroundColor = Some(Color.Rgb(0x1e, 0x1e, 0x2e)),
        terminalVersion = Some("kitty(0.35.2)"),
      )
    )
    val app     = DoctorApp(env)
    app.attach(backend)
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      body(app, pilot)
    }

  test("the report shows the terminal's identity, colour depth, glyph tier, background and every capability"):
    withDoctor { (app, pilot) =>
      val screen = pilot.screenText
      assert(screen.contains("kitty(0.35.2)"), screen)
      assert(screen.contains("TrueColor"), screen)
      assert(screen.contains("Full"), screen)
      assert(screen.contains("#1e1e2e"), screen)
      assert(screen.contains("Dark"), screen)
      assert(screen.contains("kitty keyboard") && screen.contains("Yes"), screen)
      assert(screen.contains("GLYPHORA_DEBUG_LAYOUT"), screen)
      assert(pilot.a11yReport(app).isEmpty, pilot.a11yReport(app))
      pilot.press("q")
      assert(pilot.awaitTermination())
    }

  test("decoded keys, mouse events and pastes are echoed live, newest first"):
    withDoctor { (_, pilot) =>
      pilot.pressKey(KeyCode.Char('x'), KeyModifiers.Ctrl).waitForIdle()
      assert(pilot.screenText.contains("key    ctrl+x"), pilot.screenText)
      pilot.press("f5").waitForIdle()
      assert(pilot.screenText.contains("key    f5"), pilot.screenText)
      pilot.click(Position(10, 30)).waitForIdle()
      assert(pilot.screenText.contains("mouse  Down Left at 10,30"), pilot.screenText)
      pilot.paste("hello").waitForIdle()
      assert(pilot.screenText.contains("paste  \"hello\""), pilot.screenText)
      val lines = pilot.screenLines
      assert(lines.indexWhere(_.contains("paste")) < lines.indexWhere(_.contains("ctrl+x")))
      pilot.press("q")
      assert(pilot.awaitTermination())
    }
