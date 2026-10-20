package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Color, ColorScheme, Event, KeyEvent, Size}

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

import io.worxbend.tui.terminal.ScriptedInput.{Esc, csi, decoder}

/** The OSC 11 background-colour reply reaching the capability probe, and the mode 2031 colour-scheme notifications that
  * follow it — without re-opening the trade-off that made Alt+`]` unbindable.
  */
final class ColorSchemeProbeSpec extends AnyFunSuite:

  private val Da1 = csi("?62;1;4c")

  /** `ESC ] payload ESC \` — an OSC string with the ST terminator. */
  private def osc(payload: String): Seq[Int] = Seq(Esc, ']'.toInt) ++ payload.map(_.toInt) ++ Seq(Esc, '\\'.toInt)

  /** `ESC ] payload BEL` — the same string with the BEL terminator xterm also accepts. */
  private def oscBel(payload: String): Seq[Int] = Seq(Esc, ']'.toInt) ++ payload.map(_.toInt) :+ 7

  private def probed(replies: Seq[Int]*): TerminalCapabilities =
    decoder((replies.flatten ++ Da1)*).readCapabilityReport(2.seconds)

  // ------------------------------------------------------------------ OSC 11

  test("an OSC 11 reply during the probe is read as the background colour, not dropped"):
    val capabilities = probed(osc("11;rgb:1e1e/1e1e/2e2e"))
    assert(capabilities.backgroundColor.contains(Color.Rgb(0x1e, 0x1e, 0x2e)))
    assert(capabilities.colorScheme == ColorScheme.Dark)

  test("a white background reads as a light scheme"):
    assert(probed(osc("11;rgb:ffff/ffff/ffff")).colorScheme == ColorScheme.Light)

  test("the BEL-terminated form of the reply is read too"):
    assert(probed(oscBel("11;rgb:ffff/ffff/ffff")).backgroundColor.contains(Color.Rgb(255, 255, 255)))

  test("the reply produces no key event, before or after the probe"):
    val input = decoder((osc("11;rgb:1e1e/1e1e/2e2e") ++ Da1 ++ Seq('k'.toInt))*)
    assert(input.readCapabilityReport(2.seconds).backgroundColor.nonEmpty)
    // the only thing left on the stream is the key typed after the reply
    assert(input.decode(10).contains(Event.Key(KeyEvent.char('k'))))
    assert(input.decode(10).isEmpty)

  /** Outside a probe the reply is still dropped unread, exactly as before: something else sharing the tty asked, and
    * its answer must not rewrite what start-up established.
    */
  test("an OSC 11 reply arriving outside a probe is dropped and establishes nothing"):
    val input = decoder((osc("11;rgb:ffff/ffff/ffff") ++ Seq('k'.toInt))*)
    assert(input.decode(10).isEmpty) // the string is consumed whole and yields nothing
    assert(input.decode(10).contains(Event.Key(KeyEvent.char('k'))))
    assert(input.readCapabilityReport(5.millis) == TerminalCapabilities.unknown)

  /** The trade-off the control-string skip has always made, pinned alongside the feature that depends on it: `ESC ]` is
    * byte-identical to Alt+`]`, and one byte of lookahead cannot tell the two apart. A reply typing itself into the
    * focused widget is the worse outcome, so `ESC ]` opens a control string and Alt+`]` stays unbindable.
    */
  test("Alt+] is still never reported as a key, because ESC ] always opens a control string"):
    assert(decoder(Esc, ']'.toInt).decode(10).isEmpty)
    // and the swallowed introducer does not turn the next key into part of the string: a BEL ends it
    val input = decoder(Esc, ']'.toInt, 7, 'k'.toInt)
    assert(input.decode(10).isEmpty)
    assert(input.decode(10).contains(Event.Key(KeyEvent.char('k'))))

  test("the reply parser reads the colour formats a terminal may answer in"):
    assert(CapabilityReplies.backgroundColor("11;rgb:1e1e/1e1e/2e2e").contains(Color.Rgb(0x1e, 0x1e, 0x2e)))
    assert(CapabilityReplies.backgroundColor("11;rgb:ff/80/00").contains(Color.Rgb(0xff, 0x80, 0x00)))
    assert(CapabilityReplies.backgroundColor("11;rgb:f/8/0").contains(Color.Rgb(0xff, 0x88, 0x00)))
    assert(CapabilityReplies.backgroundColor("11;#1e1e2e").contains(Color.Rgb(0x1e, 0x1e, 0x2e)))
    assert(CapabilityReplies.backgroundColor("11;#1e1e1e1e2e2e").contains(Color.Rgb(0x1e, 0x1e, 0x2e)))

  test("a reply that is not about the background, or is malformed, reads as nothing"):
    assert(CapabilityReplies.backgroundColor("10;rgb:ffff/ffff/ffff").isEmpty)
    assert(CapabilityReplies.backgroundColor("11;rgb:ffff/ffff").isEmpty)
    assert(CapabilityReplies.backgroundColor("11;rgb:zz/zz/zz").isEmpty)
    assert(CapabilityReplies.backgroundColor("11;").isEmpty)
    assert(CapabilityReplies.backgroundColor("").isEmpty)

  // ------------------------------------------------------------------ mode 2031

  test("a colour-scheme report during the probe is the terminal's own answer and wins over the background colour"):
    // the background is light, but the terminal says it is in dark mode: the terminal knows
    val capabilities = probed(osc("11;rgb:ffff/ffff/ffff"), csi("?997;1n"))
    assert(capabilities.colorScheme == ColorScheme.Dark)
    assert(probed(csi("?997;2n")).colorScheme == ColorScheme.Light)

  test("a colour-scheme report after the probe is an event, so the theme can follow a live switch"):
    val input = decoder(csi("?997;2n")*)
    assert(input.decode(10).contains(Event.ColorSchemeChanged(ColorScheme.Light)))
    assert(decoder(csi("?997;1n")*).decode(10).contains(Event.ColorSchemeChanged(ColorScheme.Dark)))

  test("a colour-scheme report with a state the protocol does not define is dropped"):
    assert(decoder(csi("?997;7n")*).decode(10).isEmpty)
    assert(decoder(csi("?997n")*).decode(10).isEmpty)

  test("the probe reads whether the terminal recognises the three new modes"):
    val capabilities = probed(csi("?2031;2$y"), csi("?2048;2$y"), csi("?2027;0$y"))
    assert(capabilities.colorSchemeUpdates == Support.Yes)
    assert(capabilities.inBandResize == Support.Yes)
    assert(capabilities.unicodeWidth == Support.No)
    assert(TerminalCapabilities.unknown.inBandResize == Support.Unknown)

  // ------------------------------------------------------------------ mode 2048

  test("an in-band resize report decodes to a resize event with the size in cells"):
    // `CSI 48 ; rows ; columns ; height-px ; width-px t` — rows first, like every other XTWINOPS report
    assert(decoder(csi("48;24;80;480;1280t")*).decode(10).contains(Event.Resize(Size(80, 24))))
    // the pixel fields are optional in the specification
    assert(decoder(csi("48;50;132t")*).decode(10).contains(Event.Resize(Size(132, 50))))

  test("an in-band resize report with a zero or missing dimension is dropped rather than resizing to nothing"):
    assert(decoder(csi("48;0;80t")*).decode(10).isEmpty)
    assert(decoder(csi("48;24t")*).decode(10).isEmpty)
