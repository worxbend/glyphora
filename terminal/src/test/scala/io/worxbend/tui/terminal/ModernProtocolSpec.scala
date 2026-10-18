package io.worxbend.tui.terminal

import io.worxbend.tui.core.{ColorScheme, Event, Size}

import org.jline.terminal.Terminal
import org.jline.terminal.impl.LineDisciplineTerminal
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** The modern OSC and mode set on the wire: what the JLine backend writes for pointer shapes, desktop notifications,
  * taskbar progress and the three new modes, and what it refuses to write when the environment says nobody is
  * listening. The headless backend records the same requests under the same gate, so an app can be tested against
  * either answer.
  */
final class ModernProtocolSpec extends AnyFunSuite:

  private val Osc = "\u001b]"
  private val St  = "\u001b\\"

  /** A real backend over a line-discipline terminal whose output is captured, with `env` standing in for the process
    * environment. The terminal answers nothing, so every probe times out and establishes nothing; the tests are about
    * what is written.
    */
  private def wired(env: Map[String, String])(body: (JLine3Backend, ByteArrayOutputStream) => Unit): Unit =
    val sink     = ByteArrayOutputStream()
    val terminal =
      LineDisciplineTerminal("modern-protocols", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor, env)
    try body(backend, sink)
    finally backend.close()

  private val plainEnv = Map("TERM" -> "xterm-256color")
  private val tmuxEnv  = plainEnv + ("TMUX" -> "/tmp/tmux-1000/default,1,0")

  // ------------------------------------------------------------------ pointer shapes

  test("a pointer shape goes out as OSC 22 with its CSS name"):
    wired(plainEnv) { (backend, sink) =>
      assert(backend.enableRawMode().isRight)
      sink.reset()
      assert(backend.setPointerShape(PointerShape.Pointer).isRight)
      assert(sink.toString(UTF_8) == s"${Osc}22;pointer$St")
      assert(backend.setPointerShape(PointerShape.ResizeHorizontal).isRight)
      assert(sink.toString(UTF_8).endsWith(s"${Osc}22;ew-resize$St"))
    }

  test("closing with a non-default pointer hands the user's own pointer back"):
    wired(plainEnv) { (backend, sink) =>
      assert(backend.enableRawMode().isRight)
      assert(backend.setPointerShape(PointerShape.Text).isRight)
      sink.reset()
      assert(backend.close().isRight)
      assert(sink.toString(UTF_8).contains(AnsiSequences.ResetPointerShape))
    }

  test("a handover resets the pointer and the reacquire restores it"):
    wired(plainEnv) { (backend, sink) =>
      assert(backend.enableRawMode().isRight)
      assert(backend.setPointerShape(PointerShape.Text).isRight)
      sink.reset()
      assert(backend.suspend(()).isRight)
      val bytes = sink.toString(UTF_8)
      assert(bytes.indexOf(AnsiSequences.ResetPointerShape) < bytes.indexOf(s"${Osc}22;text$St"))
      assert(backend.pointerShape == PointerShape.Text)
    }

  // ------------------------------------------------------------------ notifications

  test("a desktop notification goes out as OSC 777 by default, with controls and field separators neutralised"):
    wired(plainEnv) { (backend, sink) =>
      assert(backend.enableRawMode().isRight)
      sink.reset()
      assert(backend.notifyDesktop("build; done", "all 12 tests\u001b\\ passed").isRight)
      assert(sink.toString(UTF_8) == s"${Osc}777;notify;build, done;all 12 tests\\ passed$St")
    }

  test("iTerm2 gets OSC 9 and kitty gets OSC 99, so no terminal hears the same message twice"):
    assert(
      DesktopNotificationProtocol
        .detect(plainEnv + ("TERM_PROGRAM" -> "iTerm.app"))
        .contains(
          DesktopNotificationProtocol.Osc9
        )
    )
    assert(
      DesktopNotificationProtocol
        .detect(plainEnv + ("TERM" -> "xterm-kitty"))
        .contains(
          DesktopNotificationProtocol.Osc99
        )
    )
    assert(
      DesktopNotificationProtocol
        .detect(plainEnv + ("KITTY_WINDOW_ID" -> "1"))
        .contains(
          DesktopNotificationProtocol.Osc99
        )
    )
    assert(DesktopNotificationProtocol.detect(plainEnv).contains(DesktopNotificationProtocol.Osc777))
    assert(
      AnsiSequences.desktopNotification(DesktopNotificationProtocol.Osc9, "title", "body") == s"${Osc}9;title: body$St"
    )
    assert(
      AnsiSequences.desktopNotification(DesktopNotificationProtocol.Osc99, "title", "body") ==
        s"${Osc}99;i=1:d=0;title$St${Osc}99;i=1:d=1:p=body;body$St"
    )

  // ------------------------------------------------------------------ taskbar progress

  test("taskbar progress goes out as OSC 9;4 in whole percents, and None clears it"):
    wired(plainEnv) { (backend, sink) =>
      assert(backend.enableRawMode().isRight)
      sink.reset()
      assert(backend.setTaskbarProgress(Some(0.5)).isRight)
      assert(sink.toString(UTF_8) == s"${Osc}9;4;1;50$St")
      sink.reset()
      assert(backend.setTaskbarProgress(None).isRight)
      assert(sink.toString(UTF_8) == s"${Osc}9;4;0$St")
    }

  test("a fraction outside the unit range is clamped and NaN reads as empty"):
    assert(AnsiSequences.taskbarProgress(Some(1.7)) == s"${Osc}9;4;1;100$St")
    assert(AnsiSequences.taskbarProgress(Some(-0.2)) == s"${Osc}9;4;1;0$St")
    assert(AnsiSequences.taskbarProgress(Some(Double.NaN)) == s"${Osc}9;4;1;0$St")

  test("closing with progress showing clears the indicator"):
    wired(plainEnv) { (backend, sink) =>
      assert(backend.enableRawMode().isRight)
      assert(backend.setTaskbarProgress(Some(0.25)).isRight)
      sink.reset()
      assert(backend.close().isRight)
      assert(sink.toString(UTF_8).contains(s"${Osc}9;4;0$St"))
    }

  // ------------------------------------------------------------------ the gate

  test("inside a multiplexer the three unprobeable features are denied and nothing is written for them"):
    wired(tmuxEnv) { (backend, sink) =>
      assert(backend.enableRawMode().isRight)
      assert(backend.capabilities.pointerShapes == Support.No)
      assert(backend.capabilities.desktopNotifications == Support.No)
      assert(backend.capabilities.taskbarProgress == Support.No)
      sink.reset()
      assert(backend.setPointerShape(PointerShape.Pointer).isRight)
      assert(backend.notifyDesktop("t", "b").isRight)
      assert(backend.setTaskbarProgress(Some(0.5)).isRight)
      assert(sink.toString(UTF_8).isEmpty)
    }

  test("the headless backend records the same requests under the same gate"):
    val backend = HeadlessBackend(Size(10, 2))
    assert(backend.setPointerShape(PointerShape.Text).isRight)
    assert(backend.notifyDesktop("t", "b").isRight)
    assert(backend.setTaskbarProgress(Some(0.5)).isRight)
    assert(backend.currentPointerShape == PointerShape.Text)
    assert(backend.desktopNotifications == Seq(("t", "b")))
    assert(backend.taskbarProgressRequests == Seq(Some(0.5)))
    assert(backend.currentTaskbarProgress.contains(0.5))

    val denied = HeadlessBackend(Size(10, 2))
    denied.capabilitiesTo(
      TerminalCapabilities(pointerShapes = Support.No, desktopNotifications = Support.No, taskbarProgress = Support.No)
    )
    assert(denied.setPointerShape(PointerShape.Text).isRight)
    assert(denied.notifyDesktop("t", "b").isRight)
    assert(denied.setTaskbarProgress(Some(0.5)).isRight)
    assert(denied.currentPointerShape == PointerShape.Default)
    assert(denied.desktopNotifications.isEmpty)
    assert(denied.taskbarProgressRequests.isEmpty)

  // ------------------------------------------------------------------ the modes

  test("raw mode asks for colour-scheme updates and grapheme widths, but for in-band resize only on an explicit yes"):
    wired(plainEnv) { (backend, sink) =>
      sink.reset()
      assert(backend.enableRawMode().isRight)
      val bytes   = sink.toString(UTF_8)
      assert(bytes.contains(AnsiSequences.EnableColorSchemeUpdates))
      assert(bytes.contains(AnsiSequences.EnableUnicodeWidth))
      assert(!bytes.contains(AnsiSequences.EnableInBandResize))
      sink.reset()
      assert(backend.disableRawMode().isRight)
      val undress = sink.toString(UTF_8)
      assert(undress.contains(AnsiSequences.DisableColorSchemeUpdates))
      assert(undress.contains(AnsiSequences.DisableUnicodeWidth))
      assert(undress.contains(AnsiSequences.DisableInBandResize))
    }

  test("the shutdown restore resets the three modes and the pointer"):
    assert(AnsiSequences.RestoreAll.contains(AnsiSequences.DisableColorSchemeUpdates))
    assert(AnsiSequences.RestoreAll.contains(AnsiSequences.DisableInBandResize))
    assert(AnsiSequences.RestoreAll.contains(AnsiSequences.DisableUnicodeWidth))
    assert(AnsiSequences.RestoreAll.contains(AnsiSequences.ResetPointerShape))
    assert(!AnsiSequences.RestoreAll.contains("null"))

  test("the probe asks the colour questions before the DA1 fence"):
    val probe = Seq(
      AnsiSequences.queryPrivateMode(CapabilityReplies.ColorSchemeUpdatesMode),
      AnsiSequences.QueryBackgroundColor,
      AnsiSequences.QueryColorScheme,
      AnsiSequences.QueryPrimaryDeviceAttributes,
    )
    assert(probe.distinct == probe)
    assert(AnsiSequences.QueryBackgroundColor == s"${Osc}11;?$St")
    assert(AnsiSequences.QueryColorScheme == "\u001b[?996n")

  test("a headless backend reporting a light background says so through capabilities, and 2031 reports decode"):
    val backend = HeadlessBackend(Size(10, 2))
    backend.capabilitiesTo(TerminalCapabilities(backgroundColor = Some(io.worxbend.tui.core.Color.Rgb(250, 250, 250))))
    assert(backend.capabilities.colorScheme == ColorScheme.Light)
    backend.postInput("\u001b[?997;1n".map(_.toInt))
    assert(
      backend.readEvent(scala.concurrent.duration.Duration.Inf) == Right(
        Some(Event.ColorSchemeChanged(ColorScheme.Dark))
      )
    )
    val _       = backend.close()
