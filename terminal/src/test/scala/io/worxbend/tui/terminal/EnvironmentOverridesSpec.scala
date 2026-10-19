package io.worxbend.tui.terminal

import org.scalatest.funsuite.AnyFunSuite

/** The `GLYPHORA_*` switches a user sets when a terminal misreports itself: colour depth, keyboard protocol, mouse mode
  * and escape delay. Each one only ever narrows what the library would otherwise do.
  */
final class EnvironmentOverridesSpec extends AnyFunSuite:

  test("GLYPHORA_COLOR_DEPTH pins the palette over every other variable"):
    val kitty = Map("TERM" -> "xterm-kitty", "COLORTERM" -> "truecolor")
    assert(ColorDepth.detect(kitty + ("GLYPHORA_COLOR_DEPTH" -> "16")) == ColorDepth.Ansi16)
    assert(ColorDepth.detect(kitty + ("GLYPHORA_COLOR_DEPTH" -> "256")) == ColorDepth.Ansi256)
    assert(ColorDepth.detect(Map("TERM" -> "vt100", "GLYPHORA_COLOR_DEPTH" -> "truecolor")) == ColorDepth.TrueColor)
    assert(ColorDepth.detect(kitty + ("GLYPHORA_COLOR_DEPTH" -> "mono")) == ColorDepth.Monochrome)
    assert(ColorDepth.detect(kitty + ("GLYPHORA_COLOR_DEPTH" -> "none")) == ColorDepth.NoColor)
    assert(ColorDepth.detect(kitty + ("GLYPHORA_COLOR_DEPTH" -> "lots")) == ColorDepth.TrueColor, "unknown is ignored")

  test("GLYPHORA_KEYBOARD=legacy keeps the kitty keyboard protocol off even when the terminal offers it"):
    val offered = TerminalCapabilities(kittyKeyboard = Support.Yes)
    assert(JLine3Backend.gatedByEnvironment(offered, Map("GLYPHORA_KEYBOARD" -> "legacy")).kittyKeyboard == Support.No)
    assert(JLine3Backend.gatedByEnvironment(offered, Map.empty).kittyKeyboard == Support.Yes)

  test("GLYPHORA_MOUSE turns capture off, or holds it to buttons when an app asks for all motion"):
    assert(EnvironmentOverrides.mouseMode(MouseCaptureMode.AllMotion, Map("GLYPHORA_MOUSE" -> "off")).isEmpty)
    assert(
      EnvironmentOverrides.mouseMode(MouseCaptureMode.AllMotion, Map("GLYPHORA_MOUSE" -> "buttons"))
        .contains(MouseCaptureMode.Buttons)
    )
    assert(
      EnvironmentOverrides.mouseMode(MouseCaptureMode.AllMotion, Map.empty).contains(MouseCaptureMode.AllMotion)
    )
    assert(
      EnvironmentOverrides.mouseMode(MouseCaptureMode.Buttons, Map("GLYPHORA_MOUSE" -> "buttons"))
        .contains(MouseCaptureMode.Buttons)
    )

  test("GLYPHORA_ESCAPE_DELAY sets how long a lone Esc waits, in milliseconds, within bounds"):
    assert(EnvironmentOverrides.escapeDelayMillis(Map.empty) == InputDecoder.DefaultEscapeTimeoutMillis)
    assert(EnvironmentOverrides.escapeDelayMillis(Map("GLYPHORA_ESCAPE_DELAY" -> "200")) == 200L)
    assert(EnvironmentOverrides.escapeDelayMillis(Map("GLYPHORA_ESCAPE_DELAY" -> "0")) == 1L)
    assert(EnvironmentOverrides.escapeDelayMillis(Map("GLYPHORA_ESCAPE_DELAY" -> "99999")) == 2000L)
    assert(
      EnvironmentOverrides.escapeDelayMillis(Map("GLYPHORA_ESCAPE_DELAY" -> "soon")) ==
        InputDecoder.DefaultEscapeTimeoutMillis
    )
