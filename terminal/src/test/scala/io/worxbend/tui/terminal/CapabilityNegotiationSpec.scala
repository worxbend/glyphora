package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Event, KeyEvent, Size}

import scala.annotation.nowarn
import scala.concurrent.duration.DurationInt

import org.scalatest.funsuite.AnyFunSuite

import io.worxbend.tui.terminal.ScriptedInput.{csi, decoder}

/** Asking the terminal what it supports, and — the part that matters — what is done with silence. */
final class CapabilityNegotiationSpec extends AnyFunSuite:

  private val Da1 = csi("?62;1;4c")

  private def probed(replies: Seq[Int]*): TerminalCapabilities =
    decoder((replies.flatten ++ Da1)*).readCapabilityReport(2.seconds)

  // ------------------------------------------------------------------ the value

  /** The whole design in one assertion: silence must not switch a feature off, because almost every terminal answers a
    * query it does not implement with nothing at all.
    */
  test("everything except an explicit denial counts as enabled"):
    // the rule read off the state itself, which is the spelling the backend uses
    assert(Support.Unknown.usable)
    assert(Support.Yes.usable)
    assert(!Support.No.usable)
    assert(TerminalCapabilities.unknown.synchronizedOutput.usable)

  /** The 0.13.0 forwarder, called on purpose. This is the first `@nowarn` in the repository: `-deprecation -Werror`
    * would otherwise fail the build on the very call this exists to make, and a test that cannot call the deprecated
    * shim cannot prove it still delegates. It is scoped to this one method so nothing else can call the shim unnoticed.
    */
  @nowarn("cat=deprecation")
  private def shimAnswers(support: Support): Boolean = TerminalCapabilities.unknown.enabled(support)

  test("the deprecated TerminalCapabilities.enabled forwarder still answers what usable answers"):
    assert(shimAnswers(Support.Unknown) == Support.Unknown.usable)
    assert(shimAnswers(Support.Yes) == Support.Yes.usable)
    assert(shimAnswers(Support.No) == Support.No.usable)

  test("a fresh value has established nothing"):
    assert(TerminalCapabilities.unknown == TerminalCapabilities())
    assert(TerminalCapabilities.unknown.synchronizedOutput == Support.Unknown)

  // ------------------------------------------------------------------ reading replies

  test("a mode report names its mode and whether the terminal recognised it"):
    assert(CapabilityReplies.modeReport("?2026;2$").contains((2026, Support.Yes)))
    assert(CapabilityReplies.modeReport("?2026;0$").contains((2026, Support.No)))
    // 3 and 4 are "permanently set" and "permanently reset"; both mean the mode exists
    assert(CapabilityReplies.modeReport("?2004;3$").contains((2004, Support.Yes)))
    assert(CapabilityReplies.modeReport("?2004;4$").contains((2004, Support.Yes)))

  test("a malformed mode report reads as nothing rather than as a mode number"):
    assert(CapabilityReplies.modeReport("2026;2$").isEmpty)
    assert(CapabilityReplies.modeReport("?2026$").isEmpty)
    assert(CapabilityReplies.modeReport("?;$").isEmpty)
    assert(CapabilityReplies.modeReport("").isEmpty)

  test("only a device-attributes reply ends the probe"):
    assert(CapabilityReplies.endsProbe('c'))
    assert(!CapabilityReplies.endsProbe('y'))
    assert(!CapabilityReplies.endsProbe('u'))

  // ------------------------------------------------------------------ the round trip

  test("a terminal that answers every query is read correctly"):
    val capabilities = probed(csi("?2026;2$y"), csi("?2004;1$y"), csi("?1004;1$y"), csi("?1u"))
    assert(capabilities.synchronizedOutput == Support.Yes)
    assert(capabilities.bracketedPaste == Support.Yes)
    assert(capabilities.focusReporting == Support.Yes)
    assert(capabilities.kittyKeyboard == Support.Yes)

  test("a mode the terminal does not recognise comes back as an explicit denial"):
    assert(probed(csi("?2026;0$y")).synchronizedOutput == Support.No)

  /** The common case by a wide margin: a terminal answers DA1 and nothing else. Every field has to stay unknown, and
    * unknown has to keep meaning "use it".
    */
  test("a terminal that answers only DA1 establishes nothing and denies nothing"):
    assert(probed() == TerminalCapabilities.unknown)

  test("a terminal that answers nothing at all times out and establishes nothing"):
    assert(decoder().readCapabilityReport(5.millis) == TerminalCapabilities.unknown)

  test("a kitty reply is the answer whatever flags it carries"):
    assert(probed(csi("?0u")).kittyKeyboard == Support.Yes)
    assert(probed(csi("?15u")).kittyKeyboard == Support.Yes)

  /** A terminal may answer a query something else in the process sent. Folding that into a field would report a
    * capability nobody here established.
    */
  test("a report about a mode glyphora never asked about is ignored"):
    assert(probed(csi("?1049;2$y")) == TerminalCapabilities.unknown)

  test("a key typed during the probe is delivered afterwards rather than eaten"):
    val input = decoder(('a'.toInt +: (csi("?2026;2$y") ++ Da1))*)
    assert(input.readCapabilityReport(2.seconds).synchronizedOutput == Support.Yes)
    assert(input.decode(10).contains(Event.Key(KeyEvent.char('a'))))

  /** Outside a probe a stray reply is still dropped unread. A device-attributes answer arriving mid-session — because
    * something else in the process asked — must not rewrite what was established at start-up.
    */
  test("a reply arriving outside a probe is dropped and changes nothing"):
    val input = decoder((csi("?2026;0$y") ++ csi("?62;1;4c"))*)
    assert(input.decode(10).isEmpty)
    assert(input.readCapabilityReport(5.millis) == TerminalCapabilities.unknown)

  // ------------------------------------------------------------------ acting on the answer

  test("a frame is wrapped for synchronised output unless the terminal denied the mode"):
    assert(AnsiSequences.frame("body", synchronizedOutput = true).startsWith(AnsiSequences.BeginSynchronized))
    assert(AnsiSequences.frame("body", synchronizedOutput = true).endsWith(AnsiSequences.EndSynchronized))
    assert(!AnsiSequences.frame("body", synchronizedOutput = false).contains(AnsiSequences.BeginSynchronized))

  /** The style reset has to survive either way: without it the last cell's colours leak into whatever the shell prints
    * next.
    */
  test("a frame resets the style whether or not it is wrapped"):
    assert(AnsiSequences.frame("body", synchronizedOutput = true).contains(AnsiSequences.ResetStyle))
    assert(AnsiSequences.frame("body", synchronizedOutput = false) == "body" + AnsiSequences.ResetStyle)

  test("the queries are the sequences their specifications name"):
    assert(AnsiSequences.queryPrivateMode(2026) == "\u001b[?2026$p")
    assert(AnsiSequences.QueryKittyKeyboard == "\u001b[?u")
    assert(AnsiSequences.QueryPrimaryDeviceAttributes == "\u001b[c")

  test("a backend with no terminal to ask establishes nothing"):
    assert(HeadlessBackend(Size(10, 3)).capabilities == TerminalCapabilities.unknown)
