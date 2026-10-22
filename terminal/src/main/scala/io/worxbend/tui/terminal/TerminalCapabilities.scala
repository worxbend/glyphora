package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Color, ColorScheme}

/** What is known about one terminal feature: it works, it does not, or nobody ever asked.
  *
  * Three states and not a `Boolean`, because "the terminal said no" and "the terminal said nothing" call for opposite
  * behaviour and a boolean can only carry one of them. Almost every terminal ignores a query it does not implement
  * rather than answering it, so silence has to mean "carry on" — see [[TerminalCapabilities]].
  */
enum Support:
  case Yes, No, Unknown

  /** Whether a feature in this state should be used. Everything except an explicit
    * [No](https://worxbend.github.io/glyphora/api/terminal/io/worxbend/tui/terminal/Support.html#No-0) is a yes — see
    * [[TerminalCapabilities]] for why silence means "carry on".
    *
    * It lives on the state rather than on [[TerminalCapabilities]] so that reading a field says what it means at the
    * point of use — `probed.bracketedPaste.usable` — and so that a capability field added later carries the rule
    * without anyone having to remember to give it one.
    */
  def usable: Boolean = this != Support.No

/** What the terminal said about itself when it was asked, once, at start-up.
  *
  * Every `Support` field starts at [[Support.Unknown]], and every caller must read `Unknown` as "go ahead and use the
  * feature" — with one exception, named on the field: `inBandResize` is acted on only when the terminal said yes,
  * because the signal-driven resize it would replace already works everywhere. That is the conservative reading even
  * though it looks like the optimistic one: a terminal that ignores the query usually still honours the mode, so
  * switching a feature off on silence would disable synchronised output and bracketed paste on the majority of
  * terminals that answer nothing. Only an explicit [[Support.No]] — a mode report that came back saying "not
  * recognised" — turns anything off.
  *
  * Immutable, and owned by the backend that probed: a `Backend` hands out the value it established and never mutates
  * one it gave away.
  *
  * The fields past the first four arrived with the modern OSC and mode set:
  *   - `backgroundColor` — the OSC 11 answer, the colour behind the text; `reportedColorScheme` — a mode 2031 report's
  *     light/dark verdict. [[colorScheme]] combines the two.
  *   - `colorSchemeUpdates` (mode 2031), `inBandResize` (mode 2048) and `unicodeWidth` (mode 2027) — what the DECRQM
  *     for each mode said. A `Yes` on `unicodeWidth` means the terminal measures with grapheme-cluster widths, as
  *     `CharWidth` does.
  *   - `pointerShapes` (OSC 22), `desktopNotifications` (OSC 9/777/99) and `taskbarProgress` (OSC 9;4) cannot be
  *     probed; they stay `Unknown` unless the environment rules them out — a multiplexer that swallows unknown OSC
  *     strings — in which case the backend marks them `No` and never writes the sequence.
  *   - `kittyGraphics` — the answer to the kitty graphics protocol's documented query; `inlineImages` — iTerm2's
  *     inline-image protocol, which cannot be probed and is set to `Yes` by the environment (`TERM_PROGRAM` naming
  *     iTerm2 or WezTerm, or `LC_TERMINAL=iTerm2`). These two are the second exception to "silence means go ahead":
  *     like `inBandResize` they are acted on only on `Yes`, because a picture sent to a terminal that does not draw it
  *     is not a mode quietly left off but a screenful of base64 — see [[ImageProtocol.select]]. A multiplexer marks
  *     both `No`.
  */
final case class TerminalCapabilities(
    synchronizedOutput: Support = Support.Unknown,
    kittyKeyboard: Support = Support.Unknown,
    bracketedPaste: Support = Support.Unknown,
    focusReporting: Support = Support.Unknown,
    backgroundColor: Option[Color] = None,
    reportedColorScheme: Option[ColorScheme] = None,
    colorSchemeUpdates: Support = Support.Unknown,
    inBandResize: Support = Support.Unknown,
    unicodeWidth: Support = Support.Unknown,
    pointerShapes: Support = Support.Unknown,
    desktopNotifications: Support = Support.Unknown,
    taskbarProgress: Support = Support.Unknown,
    terminalVersion: Option[String] = None,
    kittyGraphics: Support = Support.Unknown,
    inlineImages: Support = Support.Unknown,
):

  /** Whether the terminal is light or dark, as far as it said.
    *
    * Two sources, in order of authority. A mode 2031 colour-scheme report (`reportedColorScheme`) is the terminal's own
    * judgement and wins outright; failing that, the OSC 11 background colour (`backgroundColor`) is read through
    * [[io.worxbend.tui.core.ColorScheme.of]]. A terminal that answered neither is
    * [ColorScheme.Unknown](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/ColorScheme.html#Unknown-0),
    * which every caller reads as "dark" — what every terminal was assumed to be before any of them could be asked.
    */
  def colorScheme: ColorScheme =
    reportedColorScheme.orElse(backgroundColor.map(ColorScheme.of)).getOrElse(ColorScheme.Unknown)

  /** Whether a feature in state `support` should be used. Write `support.usable`, which asks the state itself rather
    * than routing the question through an unrelated capability value; this forwarder is kept for source compatibility
    * with 0.13.0, answers exactly the same question, and is deprecated so a caller is told to move before it goes.
    */
  @deprecated("use Support.usable", since = "0.19.0")
  def enabled(support: Support): Boolean = support.usable

object TerminalCapabilities:

  /** What a backend with no device to ask reports: nothing was established, so everything stays on. */
  val unknown: TerminalCapabilities = TerminalCapabilities()

/** Reads a terminal's replies to the capability queries into a [[TerminalCapabilities]].
  *
  * Pure, and separate from the decoder that receives those replies, so the wire formats can be read against their
  * specifications — and tested — without a terminal, a thread, or a timeout anywhere in sight.
  *
  * Five reply shapes matter. Four are CSI sequences whose parameters begin with `?`, the fifth is an OSC string:
  *   - **DECRPM**, `CSI ? mode ; state $ y` — the answer to "is this DEC private mode recognised?". `state` is 0 for
  *     "not recognised" and 1/2/3/4 for set, reset, permanently set and permanently reset. Only 0 is a [[Support.No]].
  *   - **Kitty keyboard**, `CSI ? flags u` — sent only by a terminal that implements the protocol, so its arrival is
  *     the answer and its contents do not matter.
  *   - **Colour-scheme report**, `CSI ? 997 ; state n` — the answer to `CSI ? 996 n`, and what a terminal with mode
  *     2031 on sends whenever the scheme changes. 1 is dark, 2 is light.
  *   - **OSC 11**, `ESC ] 11 ; rgb:RRRR/GGGG/BBBB ST` — the background colour, answered to `ESC ] 11 ; ? ST`.
  *   - **DA1**, `CSI ? … c` — the primary device attributes reply. Every terminal answers it, which is what makes it
  *     the fence: terminals answer queries in the order they arrived, so once DA1 is back, anything still unanswered
  *     was never going to be answered.
  */
private[terminal] object CapabilityReplies:

  /** The DEC private modes glyphora asks about, by the sequence each one gates. */
  val SynchronizedOutputMode = 2026
  val BracketedPasteMode     = 2004
  val FocusReportingMode     = 1004
  val ColorSchemeUpdatesMode = 2031
  val InBandResizeMode       = 2048
  val UnicodeWidthMode       = 2027

  /** The DSR parameter a terminal reports a colour-scheme change with — `CSI ? 997 ; 1 n` for dark, `; 2 n` for light —
    * whether as the answer to the `CSI ? 996 n` query or as a mode 2031 notification.
    */
  val ColorSchemeReport = 997

  /** The OSC number of the background-colour query and its reply. */
  val BackgroundColorOsc = "11"

  /** `capabilities` updated with whatever this reply said, or unchanged when it said nothing this cares about. */
  def fold(capabilities: TerminalCapabilities, params: String, finalByte: Int): TerminalCapabilities =
    finalByte match
      case 'u' if params.startsWith("?") => capabilities.copy(kittyKeyboard = Support.Yes)
      case 'y'                           => foldModeReport(capabilities, params)
      case 'n'                           =>
        colorSchemeReport(params).fold(capabilities)(scheme => capabilities.copy(reportedColorScheme = Some(scheme)))
      case _                             => capabilities

  /** `capabilities` with the background colour an OSC reply carried, or unchanged when the reply was about something
    * else. `payload` is everything between `ESC ]` and the terminator.
    */
  def foldOsc(capabilities: TerminalCapabilities, payload: String): TerminalCapabilities =
    terminalVersion(payload) match
      case Some(version) => capabilities.copy(terminalVersion = Some(version))
      case None          =>
        backgroundColor(payload).fold(capabilities)(color => capabilities.copy(backgroundColor = Some(color)))

  /** The name and version an XTVERSION reply carries — the DCS payload `>|kitty(0.35.2)` — or `None` for any other
    * control string. The text is the terminal's own, free-form, and only ever shown, never parsed for features.
    */
  def terminalVersion(payload: String): Option[String] =
    Option.when(payload.startsWith(">|"))(payload.drop(2).trim).filter(_.nonEmpty)

  /** `capabilities` updated with whatever a completed OSC, DCS or APC reply said: the kitty graphics query's answer, or
    * whatever [[foldOsc]] reads (the XTVERSION name, the OSC 11 background colour). They cannot be confused — a kitty
    * graphics reply starts with `G`, an XTVERSION one with `>|`, an OSC 11 one with `11;`.
    */
  def foldControlString(capabilities: TerminalCapabilities, payload: String): TerminalCapabilities =
    KittyGraphics.queryReply(payload) match
      case Some(support) => capabilities.copy(kittyGraphics = support)
      case None          => foldOsc(capabilities, payload)

  /** The scheme a `CSI ? 997 ; state n` report names, or `None` for any other DSR.
    *
    * The states are the ones the mode 2031 specification defines: 1 is dark, 2 is light. Anything else is a report this
    * library does not understand and is dropped rather than guessed at.
    */
  def colorSchemeReport(params: String): Option[ColorScheme] =
    if !params.startsWith("?") then None
    else
      params.drop(1).split(';').toSeq match
        case Seq(report, state) if report.toIntOption.contains(ColorSchemeReport) =>
          state.toIntOption.collect {
            case 1 => ColorScheme.Dark
            case 2 => ColorScheme.Light
          }
        case _                                                                    => None

  /** The colour an OSC 11 reply carries, or `None` when the payload is not a readable background-colour answer.
    *
    * xterm answers `11;rgb:RRRR/GGGG/BBBB` with one to four hex digits per channel (XTerm `ctlseqs.ms`, "Operating
    * System Commands", via `XParseColor`); a few terminals answer with a `#` form of three, six, nine or twelve hex
    * digits. Each channel is scaled to eight bits by its own width — `f` is `ff`, `1e1e` is `1e` — so every form lands
    * on the same [[io.worxbend.tui.core.Color.Rgb]].
    */
  def backgroundColor(payload: String): Option[Color] =
    val prefix = BackgroundColorOsc + ";"
    if !payload.startsWith(prefix) then None
    else
      val spec = payload.drop(prefix.length)
      if spec.startsWith("rgb:") then
        spec.drop(4).split('/').toSeq match
          case Seq(r, g, b) => rgb(r, g, b)
          case _            => None
      else if spec.startsWith("#") then
        val digits = spec.drop(1)
        val width  = digits.length / 3
        if width >= 1 && width <= 4 && digits.length == width * 3 then
          rgb(digits.take(width), digits.slice(width, width * 2), digits.drop(width * 2))
        else None
      else None

  /** Three hex channels of one to four digits each, scaled to `0..255`. */
  private def rgb(r: String, g: String, b: String): Option[Color] =
    for
      red   <- channel(r)
      green <- channel(g)
      blue  <- channel(b)
    yield Color.Rgb(red, green, blue)

  /** One hex channel scaled to eight bits by its own width, so `f`, `ff`, `fff` and `ffff` all read as 255. */
  private def channel(digits: String): Option[Int] =
    if digits.isEmpty || digits.length > 4 || !digits.forall(c => Character.digit(c, 16) >= 0) then None
    else
      val value = Integer.parseInt(digits, 16)
      val max   = (1 << (4 * digits.length)) - 1
      Some((value * 255 + max / 2) / max)

  /** Whether this reply is the DA1 fence that ends the probe.
    *
    * The final byte alone decides it. A `c` on a private-parameter CSI is a device-attributes answer and nothing else,
    * so there is no parameter text to inspect — primary (`?…c`) and secondary (`>…c`) attributes both close the probe,
    * which is what a terminal answering only one of the two needs.
    */
  def endsProbe(finalByte: Int): Boolean = finalByte == 'c'

  /** One DECRPM answer, applied to whichever field its mode number names.
    *
    * A mode glyphora did not ask about is ignored rather than guessed at: a terminal may answer a query something else
    * in the process sent, and folding that into a field would report a capability nobody established.
    */
  private def foldModeReport(capabilities: TerminalCapabilities, params: String): TerminalCapabilities =
    modeReport(params) match
      case Some((SynchronizedOutputMode, support)) => capabilities.copy(synchronizedOutput = support)
      case Some((BracketedPasteMode, support))     => capabilities.copy(bracketedPaste = support)
      case Some((FocusReportingMode, support))     => capabilities.copy(focusReporting = support)
      case Some((ColorSchemeUpdatesMode, support)) => capabilities.copy(colorSchemeUpdates = support)
      case Some((InBandResizeMode, support))       => capabilities.copy(inBandResize = support)
      case Some((UnicodeWidthMode, support))       => capabilities.copy(unicodeWidth = support)
      case _                                       => capabilities

  /** The mode a DECRPM reply is about and what it said, or `None` when the reply is not a readable one.
    *
    * The parameter text of `CSI ? 2026 ; 2 $ y` is `?2026;2$`: a leading `?`, the two numbers, and the `$` intermediate
    * byte the scanner keeps with the parameters. Both are stripped by taking digits only, which is also what keeps a
    * malformed reply from being read as a mode number.
    *
    * This deliberately does not use the decoder's parameter reading: a DECRPM reply carries the `$` intermediate byte
    * inside its last field, which that reading would drop as unparseable, and the two fields here are matched by
    * position rather than skipped when empty.
    */
  private[terminal] def modeReport(params: String): Option[(Int, Support)] =
    if !params.startsWith("?") then None
    else
      params.drop(1).split(';').toSeq.map(_.takeWhile(_.isDigit)) match
        case Seq(mode, state) =>
          for
            modeNumber  <- mode.toIntOption
            stateNumber <- state.toIntOption
          yield (modeNumber, if stateNumber == 0 then Support.No else Support.Yes)
        case _                => None
