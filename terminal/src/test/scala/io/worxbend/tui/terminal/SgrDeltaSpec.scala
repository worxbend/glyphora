package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Color, Modifiers, Style, UnderlineStyle}

import org.scalatest.funsuite.AnyFunSuite

/** Pins [[Sgr.sgrDelta]], the form that writes only the attributes that changed between two styles.
  *
  * The absolute form (`sgr`) opens with a reset, so no attribute of whatever came before it can survive into the run it
  * introduces. The delta form has no such safety net: a forgotten "off" code leaves, say, bold switched on for the rest
  * of the frame. That is the failure this suite exists to catch, and it is why the centrepiece here is not a list of
  * hand-written cases but a small model of the terminal's own graphic-rendition state. Every ordered pair of a
  * representative set of styles is driven through both forms against that model, and the two must agree.
  */
final class SgrDeltaSpec extends AnyFunSuite:

  private val Esc = ""

  private def delta(from: Style, to: Style, depth: ColorDepth = ColorDepth.TrueColor): String =
    Sgr.sgrDelta(from, to, depth)

  private val orange = Color.Rgb(220, 160, 40)
  private val navy   = Color.Rgb(10, 20, 60)

  test("a style that has not changed emits nothing"):
    assert(delta(Style.Default, Style.Default) == "")
    assert(delta(Style.Default.withFg(orange).bold, Style.Default.withFg(orange).bold) == "")

  test("a difference SGR does not carry, such as the hyperlink, emits nothing"):
    // hyperlinks travel as OSC 8, not SGR, so two styles differing only there need no sequence at all
    val plain = Style.Default.withFg(orange)
    assert(delta(plain, plain.withLink("https://example.invalid")) == "")

  test("adding bold over unchanged colours writes the one flag and repeats no colour"):
    val before = Style.Default.withFg(orange).withBg(navy)
    val after  = before.bold
    assert(delta(before, after) == s"$Esc[1m")
    // the headline claim of the whole change: the truecolour selector appears nowhere in the sequence
    assert(!delta(before, after).contains("38;2"))
    assert(Sgr.sgr(after, ColorDepth.TrueColor).contains("38;2"))

  test("removing bold while dim survives re-asserts the dim, because SGR 22 clears both"):
    assert(delta(Style.Default.bold.dim, Style.Default.dim) == s"$Esc[22;2m")

  test("removing bold and dim together writes a single SGR 22, not one per flag"):
    assert(delta(Style.Default.bold.dim, Style.Default) == s"$Esc[22m")

  test("the two blink rates share SGR 25 the same way bold and dim share SGR 22"):
    assert(delta(Style.Default.blink.rapidBlink, Style.Default) == s"$Esc[25m")
    assert(delta(Style.Default.blink.rapidBlink, Style.Default.blink) == s"$Esc[25;5m")

  test("removals are written before additions, each with its own reset code"):
    assert(delta(Style.Default.italic.underline, Style.Default.crossedOut) == s"$Esc[23;24;9m")

  test("a foreground change writes only the foreground"):
    assert(delta(Style.Default.withFg(navy), Style.Default.withFg(Color.Rgb(1, 2, 3))) == s"$Esc[38;2;1;2;3m")

  test("clearing a colour falls out as the default-colour code, 39 or 49"):
    // `Color.Reset` already encodes as SGR 39/49, so a colour going from `Some` to `None` needs no case of its own
    assert(delta(Style.Default.withFg(orange), Style.Default) == s"$Esc[39m")
    assert(delta(Style.Default.withBg(orange), Style.Default) == s"$Esc[49m")

  test("colours are downsampled on the delta path exactly as they are on the absolute one"):
    val before  = Style.Default.withFg(navy)
    val after   = Style.Default.withFg(Color.Rgb(200, 30, 30))
    // the absolute form for `after` at this depth is a reset plus the one colour; the delta is that colour alone
    val colours = Sgr.sgr(after, ColorDepth.Ansi16).stripPrefix(s"$Esc[0;")
    assert(delta(before, after, ColorDepth.Ansi16) == s"$Esc[$colours")

  test("under NoColor a pure colour change is not a change at all, but a flag change still is"):
    val before = Style.Default.withFg(orange)
    assert(delta(before, Style.Default.withFg(navy), ColorDepth.NoColor) == "")
    assert(delta(before, before.bold, ColorDepth.NoColor) == s"$Esc[1m")

  test("an underline change the delta cannot express safely falls back to the absolute sequence"):
    // SGR 4:0 (back to a plain underline) and SGR 59 (back to the default underline colour) are missing from several
    // terminal emulators, so any step that would need one is written the long way instead
    val curly = Style.Default.curlyUnderline
    assert(delta(curly, Style.Default) == Sgr.sgr(Style.Default, ColorDepth.TrueColor))
    assert(delta(Style.Default.withUnderlineColor(orange), Style.Default) == Sgr.sgr(Style.Default))
    // toggling the plain underline flag under a styled underline would take the style with it: SGR 24 clears both
    assert(delta(curly, curly.underline) == Sgr.sgr(curly.underline, ColorDepth.TrueColor))

  // ---------------------------------------------------------------- the model check

  /** Everything a terminal remembers about the current graphic rendition — the state SGR sets.
    *
    * Colours are held as the text of the parameters that set them rather than as a [[Color]], because that is what the
    * question here is: did the two forms write the same thing, whatever it decoded to.
    */
  private final case class TerminalState(
      fg: Option[String] = None,
      bg: Option[String] = None,
      underlineColor: Option[String] = None,
      underlineStyle: Option[String] = None,
      modifiers: Set[String] = Set.empty,
  )

  /** The flag each "turn it on" SGR code sets, named rather than numbered so a failure reads as English. */
  private val SetCodes: Map[String, String] =
    Map(
      "1" -> "bold",
      "2" -> "dim",
      "3" -> "italic",
      "4" -> "underline",
      "5" -> "blink",
      "6" -> "rapidBlink",
      "7" -> "reverse",
      "8" -> "hidden",
      "9" -> "crossedOut",
    )

  /** The flags each "turn it off" SGR code clears. Two of them clear a pair, which is the trap the delta has to dodge.
    */
  private val ResetCodes: Map[String, Set[String]] =
    Map(
      "22" -> Set("bold", "dim"),
      "23" -> Set("italic"),
      "24" -> Set("underline"),
      "25" -> Set("blink", "rapidBlink"),
      "27" -> Set("reverse"),
      "28" -> Set("hidden"),
      "29" -> Set("crossedOut"),
    )

  /** Applies one SGR sequence to `state` the way a terminal would; the empty string changes nothing. */
  private def applySgr(state: TerminalState, sequence: String): TerminalState =
    if sequence.isEmpty then state
    else consume(state, sequence.stripPrefix(s"$Esc[").stripSuffix("m").split(';').toList.filter(_.nonEmpty))

  /** Walks the parameter list. The extended-colour selectors spend the parameters that follow them, so they are matched
    * as whole groups rather than seen as attributes of their own.
    */
  @annotation.tailrec
  private def consume(state: TerminalState, parameters: List[String]): TerminalState =
    parameters match
      case Nil                                => state
      case "38" :: "5" :: index :: rest       => consume(state.copy(fg = Some(s"indexed $index")), rest)
      case "38" :: "2" :: r :: g :: b :: rest => consume(state.copy(fg = Some(s"rgb $r $g $b")), rest)
      case "48" :: "5" :: index :: rest       => consume(state.copy(bg = Some(s"indexed $index")), rest)
      case "48" :: "2" :: r :: g :: b :: rest => consume(state.copy(bg = Some(s"rgb $r $g $b")), rest)
      case parameter :: rest                  => consume(single(state, parameter), rest)

  /** Applies a parameter that stands on its own. */
  private def single(state: TerminalState, parameter: String): TerminalState =
    parameter match
      case "0"                           => TerminalState()
      case "39"                          => state.copy(fg = None)
      case "49"                          => state.copy(bg = None)
      case set if SetCodes.contains(set) => state.copy(modifiers = state.modifiers + SetCodes(set))
      // SGR 24 takes the styled underline with it, which is exactly why `deltaIsSafe` refuses to emit it under one
      case "24"                          => state.copy(modifiers = state.modifiers - "underline", underlineStyle = None)
      case off if ResetCodes.contains(off)    => state.copy(modifiers = state.modifiers -- ResetCodes(off))
      case styled if styled.startsWith("4:")  => state.copy(underlineStyle = Some(styled))
      case colour if colour.startsWith("58:") => state.copy(underlineColor = Some(colour))
      // what is left is a named colour: 30-37 and 90-97 are foregrounds, 40-47 and 100-107 backgrounds
      case named                              =>
        val code = named.toInt
        if (code >= 30 && code <= 37) || (code >= 90 && code <= 97) then state.copy(fg = Some(s"named $named"))
        else state.copy(bg = Some(s"named $named"))

  private def absolute(style: Style, depth: ColorDepth): TerminalState =
    applySgr(TerminalState(), Sgr.sgr(style, depth))

  private def applied(from: Style, to: Style, depth: ColorDepth): TerminalState =
    applySgr(absolute(from, depth), Sgr.sgrDelta(from, to, depth))

  private val representative: List[Style] =
    List(
      Style.Default,
      Style.Default.bold,
      Style.Default.dim,
      Style.Default.bold.dim,
      Style.Default.italic.underline,
      Style.Default.blink,
      Style.Default.blink.rapidBlink,
      Style.Default.reverse.hidden.crossedOut,
      Style.mods(Modifiers.All),
      Style.Default.withFg(orange),
      Style.Default.withBg(navy),
      Style.Default.withFg(orange).withBg(navy).bold,
      Style.Default.withFg(Color.Cyan).withBg(Color.Black).italic,
      Style.Default.withFg(Color.Indexed(208)).withBg(Color.BrightWhite),
      Style.Default.withUnderlineColor(orange),
      Style.Default.curlyUnderline,
      Style.Default.curlyUnderline.withUnderlineColor(navy),
      Style.Default.withUnderlineStyle(UnderlineStyle.Double).bold,
    )

  test("every ordered pair of representative styles lands where the absolute sequence would"):
    // The check the hand-written cases above cannot give. For each pair: put the model terminal into `from` with the
    // absolute sequence, apply the delta, and require the result to be indistinguishable from having written the
    // absolute sequence for `to`. A missing reset code shows up here as an attribute that outlived its style.
    for
      depth <- List(
        ColorDepth.TrueColor,
        ColorDepth.Ansi256,
        ColorDepth.Ansi16,
        ColorDepth.Monochrome,
        ColorDepth.NoColor,
      )
      from  <- representative
      to    <- representative
    do
      assert(
        applied(from, to, depth) == absolute(to, depth),
        s"delta at $depth from $from to $to left the terminal in a different state",
      )

  test("on the change that dominates a real frame the delta is a fraction of the absolute form"):
    // Not a correctness property, but the whole reason the delta exists. The pairs measured here are the ones a themed
    // application actually produces: neighbouring runs that keep the same two truecolour colours and differ only in
    // their text attributes. Going the other way — dropping every attribute back to nothing — the absolute reset is
    // the shorter of the two and the encoder gains nothing, which is fine; it loses nothing either.
    val themed =
      List(Style.Default, Style.Default.bold, Style.Default.italic, Style.Default.bold.reverse)
        .map(_.withFg(orange).withBg(navy))
    for from <- themed; to <- themed if from != to do
      val short = Sgr.sgrDelta(from, to, ColorDepth.TrueColor)
      val long  = Sgr.sgr(to, ColorDepth.TrueColor)
      assert(short.length * 3 < long.length, s"delta from $from to $to was $short, barely shorter than $long")
