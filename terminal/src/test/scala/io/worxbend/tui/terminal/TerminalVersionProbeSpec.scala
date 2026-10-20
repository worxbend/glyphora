package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Event, KeyEvent}

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

import io.worxbend.tui.terminal.ScriptedInput.{Esc, csi, decoder}

/** The XTVERSION reply (`DCS > | name ST`) reaching the capability probe as the terminal's self-reported name. */
final class TerminalVersionProbeSpec extends AnyFunSuite:

  private val Da1 = csi("?62;1;4c")

  private def dcs(payload: String): Seq[Int] = Seq(Esc, 'P'.toInt) ++ payload.map(_.toInt) ++ Seq(Esc, '\\'.toInt)

  test("an XTVERSION reply during the probe is read as the terminal's name and version"):
    val capabilities = decoder((dcs(">|kitty(0.35.2)") ++ Da1)*).readCapabilityReport(2.seconds)
    assert(capabilities.terminalVersion.contains("kitty(0.35.2)"))

  test("a terminal that does not answer leaves the version unknown"):
    assert(decoder(Da1*).readCapabilityReport(2.seconds).terminalVersion.isEmpty)

  test("the reply types nothing into the app"):
    val input = decoder((dcs(">|WezTerm 20240203") ++ Da1 ++ Seq('k'.toInt))*)
    assert(input.readCapabilityReport(2.seconds).terminalVersion.contains("WezTerm 20240203"))
    assert(input.decode(10).contains(Event.Key(KeyEvent.char('k'))))

  test("the query is CSI > 0 q"):
    assert(AnsiSequences.QueryTerminalVersion == s"${Esc.toChar}[>0q")
