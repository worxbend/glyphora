package io.worxbend.tui.terminal

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

import io.worxbend.tui.terminal.ScriptedInput.{Esc, csi, decoder}

/** Which image protocol a terminal gets: kitty graphics only when the terminal answered the documented query with `OK`,
  * iTerm2 inline images only when the environment names a terminal that implements them, and neither otherwise — unlike
  * the modes, silence here means "do not", because an unanswered picture is garbage on the screen rather than a mode
  * quietly left off.
  */
final class ImageProtocolProbeSpec extends AnyFunSuite:

  private val Da1 = csi("?62;1;4c")

  private def apc(payload: String): Seq[Int] = Seq(Esc, '_'.toInt) ++ payload.map(_.toInt) ++ Seq(Esc, '\\'.toInt)

  private def probed(replies: Seq[Int]*): TerminalCapabilities =
    decoder((replies.flatten ++ Da1)*).readCapabilityReport(2.seconds)

  test("an OK reply to the kitty graphics query is a yes"):
    assert(probed(apc("Gi=31;OK")).kittyGraphics == Support.Yes)

  test("an error reply to the kitty graphics query is a no"):
    assert(probed(apc("Gi=31;ENOTSUPPORTED:no")).kittyGraphics == Support.No)

  test("a reply about another image id establishes nothing"):
    assert(probed(apc("Gi=310;OK")).kittyGraphics == Support.Unknown)

  test("silence selects no protocol at all"):
    assert(ImageProtocol.select(probed()).isEmpty)

  test("kitty wins over iTerm2 when both are available"):
    val both = TerminalCapabilities(kittyGraphics = Support.Yes, inlineImages = Support.Yes)
    assert(ImageProtocol.select(both).contains(ImageProtocol.Kitty))
    assert(ImageProtocol.select(both.copy(kittyGraphics = Support.No)).contains(ImageProtocol.ITerm2))

  test("iTerm2 and WezTerm are recognised by their environment"):
    val unknown = TerminalCapabilities.unknown
    assert(JLine3Backend.gatedByEnvironment(unknown, Map("TERM_PROGRAM" -> "iTerm.app")).inlineImages == Support.Yes)
    assert(JLine3Backend.gatedByEnvironment(unknown, Map("LC_TERMINAL" -> "iTerm2")).inlineImages == Support.Yes)
    assert(JLine3Backend.gatedByEnvironment(unknown, Map("TERM_PROGRAM" -> "WezTerm")).inlineImages == Support.Yes)
    assert(JLine3Backend.gatedByEnvironment(unknown, Map("TERM" -> "xterm-256color")).inlineImages == Support.Unknown)

  test("inside a multiplexer neither protocol is used, whatever the terminal said"):
    val said  = TerminalCapabilities(kittyGraphics = Support.Yes)
    val gated = JLine3Backend.gatedByEnvironment(said, Map("TMUX" -> "/tmp/tmux", "TERM_PROGRAM" -> "iTerm.app"))
    assert(gated.kittyGraphics == Support.No)
    assert(gated.inlineImages == Support.No)

  test("the probe asks the kitty graphics question before the DA1 fence"):
    val queries = scala.collection.mutable.ArrayBuffer.empty[String]
    val _ = ReplyQueries(Object(), query => { val _ = queries += query }, decoder(Da1*)).probeCapabilities(2.seconds)
    assert(queries.contains(KittyGraphics.Query))
    assert(queries.indexOf(KittyGraphics.Query) < queries.indexOf(AnsiSequences.QueryPrimaryDeviceAttributes))
