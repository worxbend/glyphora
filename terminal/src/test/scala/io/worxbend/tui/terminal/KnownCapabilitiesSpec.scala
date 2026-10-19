package io.worxbend.tui.terminal

import org.jline.terminal.Terminal
import org.jline.terminal.impl.LineDisciplineTerminal
import org.scalatest.funsuite.AnyFunSuite

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** A backend opened with capabilities an earlier backend already probed skips the probe round trip: it writes none of
  * the queries and reports what it was given. What a sequence of one-shot prompts in one process needs — one probe, not
  * one per prompt.
  */
final class KnownCapabilitiesSpec extends AnyFunSuite:

  private def wired(known: Option[TerminalCapabilities])(body: (JLine3Backend, ByteArrayOutputStream) => Unit): Unit =
    val sink     = ByteArrayOutputStream()
    val terminal =
      LineDisciplineTerminal("known-capabilities", "xterm-256color", sink, UTF_8, Terminal.SignalHandler.SIG_IGN)
    val backend  = JLine3Backend.wrapping(terminal, ColorDepth.TrueColor, Map("TERM" -> "xterm-256color"), known)
    try body(backend, sink)
    finally
      val _ = backend.close()

  test("without known capabilities, entering raw mode asks the terminal (DA1 is the probe's fence)"):
    wired(None) { (backend, sink) =>
      assert(backend.enableRawMode().isRight)
      assert(sink.toString(UTF_8).contains(AnsiSequences.QueryPrimaryDeviceAttributes))
    }

  test("with known capabilities, entering raw mode asks nothing and reports what it was given"):
    val known = TerminalCapabilities(kittyGraphics = Support.Yes, bracketedPaste = Support.No)
    wired(Some(known)) { (backend, sink) =>
      assert(backend.enableRawMode().isRight)
      val written = sink.toString(UTF_8)
      assert(!written.contains(AnsiSequences.QueryPrimaryDeviceAttributes), "the probe ran anyway")
      assert(!written.contains(AnsiSequences.EnableBracketedPaste), "a denied mode was enabled")
      assert(backend.capabilities == known)
    }
