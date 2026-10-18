package io.worxbend.tui.terminal

/** What is known about one terminal feature: it works, it does not, or nobody ever asked.
  *
  * Three states and not a `Boolean`, because "the terminal said no" and "the terminal said nothing" call for opposite
  * behaviour and a boolean can only carry one of them. Almost every terminal ignores a query it does not implement
  * rather than answering it, so silence has to mean "carry on" — see [[TerminalCapabilities]].
  */
enum Support:
  case Yes, No, Unknown

  /** Whether a feature in this state should be used. Everything except an explicit [[No]] is a yes — see
    * [[TerminalCapabilities]] for why silence means "carry on".
    *
    * It lives on the state rather than on [[TerminalCapabilities]] so that reading a field says what it means at the
    * point of use — `probed.bracketedPaste.usable` — and so that a capability field added later carries the rule
    * without anyone having to remember to give it one.
    */
  def usable: Boolean = this != Support.No

/** What the terminal said about itself when it was asked, once, at start-up.
  *
  * Every field starts at [[Support.Unknown]], and every caller must read `Unknown` as "go ahead and use the feature".
  * That is the conservative reading even though it looks like the optimistic one: a terminal that ignores the query
  * usually still honours the mode, so switching a feature off on silence would disable synchronised output and
  * bracketed paste on the majority of terminals that answer nothing. Only an explicit [[Support.No]] — a mode report
  * that came back saying "not recognised" — turns anything off.
  *
  * Immutable, and owned by the backend that probed: a `Backend` hands out the value it established and never mutates
  * one it gave away.
  */
final case class TerminalCapabilities(
    synchronizedOutput: Support = Support.Unknown,
    kittyKeyboard: Support = Support.Unknown,
    bracketedPaste: Support = Support.Unknown,
    focusReporting: Support = Support.Unknown,
):

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
  * Three reply shapes matter, all of them CSI sequences whose parameters begin with `?`:
  *   - **DECRPM**, `CSI ? mode ; state $ y` — the answer to "is this DEC private mode recognised?". `state` is 0 for
  *     "not recognised" and 1/2/3/4 for set, reset, permanently set and permanently reset. Only 0 is a [[Support.No]].
  *   - **Kitty keyboard**, `CSI ? flags u` — sent only by a terminal that implements the protocol, so its arrival is
  *     the answer and its contents do not matter.
  *   - **DA1**, `CSI ? … c` — the primary device attributes reply. Every terminal answers it, which is what makes it
  *     the fence: terminals answer queries in the order they arrived, so once DA1 is back, anything still unanswered
  *     was never going to be answered.
  */
private[terminal] object CapabilityReplies:

  /** The DEC private modes glyphora asks about, by the sequence each one gates. */
  val SynchronizedOutputMode = 2026
  val BracketedPasteMode     = 2004
  val FocusReportingMode     = 1004

  /** `capabilities` updated with whatever this reply said, or unchanged when it said nothing this cares about. */
  def fold(capabilities: TerminalCapabilities, params: String, finalByte: Int): TerminalCapabilities =
    finalByte match
      case 'u' if params.startsWith("?") => capabilities.copy(kittyKeyboard = Support.Yes)
      case 'y'                           => foldModeReport(capabilities, params)
      case _                             => capabilities

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
