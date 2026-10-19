package io.worxbend.tui.core

/** Which colours an SGR sequence may carry, and how a [[Style]]'s colours are reduced to them — the colour policy every
  * ANSI encoder in glyphora writes through.
  *
  * `emitsColor` false drops every foreground, background and underline colour code while keeping text attributes (the
  * `NO_COLOR` rung). `legible` adjusts a whole style before encoding (a two-tone terminal flips a foreground that would
  * vanish into its background), and `downsample` reduces one colour to what the device can show.
  * [[AnsiColors.TrueColor]] is the identity policy: every colour in its own spelling. `tui-terminal`'s
  * `ColorDepth.ansiColors` answers the policy for each rung of its ladder; that is where a caller who has a colour
  * depth gets one.
  *
  * Immutable and pure; safe to share between threads.
  */
final class AnsiColors(
    val emitsColor: Boolean,
    legibleStyle: Style => Style,
    downsampleColor: Color => Color,
):

  /** `style` adjusted so its colours stay distinguishable under this policy. */
  def legible(style: Style): Style = legibleStyle(style)

  /** `color` reduced to what this policy can write. */
  def downsample(color: Color): Color = downsampleColor(color)

object AnsiColors:

  /** Every colour written in its own natural spelling: named, indexed and RGB colours pass through untouched. */
  val TrueColor: AnsiColors = AnsiColors(emitsColor = true, identity, identity)

/** The SGR (Select Graphic Rendition) grammar: a [[Style]] in, the escape sequence that paints it out, under an
  * [[AnsiColors]] policy.
  *
  * The one copy of that grammar. `terminal.Sgr` (the frame encoder's codec), [[BufferExport.ansi]] and
  * [[BufferExport.ansiLines]] all write through it, so a new [[Style]] field or a corrected code is added once and
  * reaches the screen, print mode and every export alike. It lives in `tui-core` because the exports do, and the module
  * tiers only point downward. Pure; callable from any thread.
  */
private[tui] object SgrCodec:

  private val Esc = "\u001b"

  /** Full SGR sequence for `style`, starting from a reset so no previous attribute leaks through. */
  def absolute(style: Style, colors: AnsiColors): String =
    // appended to directly rather than collected into a List and joined: a full-change frame asks for one sequence per
    // run of same-styled cells, and the intermediate list was the bulk of what that allocated
    val codes     = StringBuilder(Esc).append("[0")
    val effective = colors.legible(style)
    if colors.emitsColor then
      effective.fg.foreach(color => appendParam(codes, foregroundCode(colors.downsample(color))))
      effective.bg.foreach(color => appendParam(codes, backgroundCode(colors.downsample(color))))
    ModifierCodes.foreach((flag, code) => if style.modifiers.hasAny(flag) then appendParam(codes, code))
    // the styled-underline selector is a text attribute (kept without colour); the underline color is a color (dropped)
    underlineStyleCode(style.underlineStyle).foreach(code => appendParam(codes, code))
    if colors.emitsColor then
      effective.underlineColor.foreach(color => appendParam(codes, underlineColorCode(colors.downsample(color))))
    codes.append('m').result()

  /** The SGR that moves a terminal already showing `from` to showing `to`, writing only what actually changed; the
    * empty string when the two render identically. Falls back to `absolute(to, colors)` byte for byte whenever the
    * difference cannot be expressed safely as a delta (see `deltaIsSafe`). The caller must know the terminal is in
    * exactly `from`.
    */
  def delta(from: Style, to: Style, colors: AnsiColors): String =
    if from == to then ""
    else
      val before = colors.legible(from)
      val after  = colors.legible(to)
      if !deltaIsSafe(before, after, colors) then absolute(to, colors) else buildDelta(before, after, colors)

  /** Whether the step from `from` to `to` can be written as a delta at all.
    *
    * Turning an attribute *off* needs a reset code, and three of them are either not universally implemented or too
    * blunt to use here:
    *
    *   - SGR 59 ("default underline colour") is missing from several emulators, so dropping an underline colour back to
    *     the terminal default cannot be done by delta. When colours are not emitted the underline colour never is
    *     either, so a change to it is not a difference at all.
    *   - The `4:0` styled-underline selector has the same patchy support, so any change of [[UnderlineStyle]] — into,
    *     out of, or between the styled forms — takes the absolute path.
    *   - SGR 24 ("no underline") clears the styled underline along with the plain one. So switching the plain
    *     [[Modifiers.Underline]] flag on or off while a styled underline is in force would silently take the style with
    *     it; that combination also takes the absolute path.
    */
  private def deltaIsSafe(from: Style, to: Style, colors: AnsiColors): Boolean =
    val underlineStyleUnchanged = from.underlineStyle == to.underlineStyle
    val underlineFlagUnchanged  = from.modifiers.hasAny(Modifiers.Underline) == to.modifiers.hasAny(Modifiers.Underline)
    val underlineColorKept      =
      !colors.emitsColor || from.underlineColor == to.underlineColor || to.underlineColor.isDefined
    underlineStyleUnchanged &&
    (underlineFlagUnchanged || to.underlineStyle == UnderlineStyle.None) &&
    underlineColorKept

  /** Builds the delta itself. Only called once `deltaIsSafe` has agreed the step is expressible. */
  private def buildDelta(from: Style, to: Style, colors: AnsiColors): String =
    val codes    = StringBuilder()
    val removed  = from.modifiers.without(to.modifiers)
    // Flags that have to be stated again because the reset code for one of them also cleared a sibling that survives:
    // SGR 22 clears bold *and* dim, and SGR 25 clears both blink rates. Dropping bold from a bold+dim style therefore
    // has to write `22;2`, not a bare `22`, or the dim goes with it. Collected first so each reset is written once.
    var reassert = Modifiers.None
    ResetCodes.foreach: (group, code) =>
      if removed.hasAny(group) then
        appendParam(codes, code)
        reassert = reassert | (to.modifiers & group)
    val added    = to.modifiers.without(from.modifiers) | reassert
    ModifierCodes.foreach((flag, code) => if added.hasAny(flag) then appendParam(codes, code))
    if colors.emitsColor then
      // `Color.Reset` is SGR 39/49, "back to the terminal's own colour", which is exactly what a colour going from
      // `Some` to `None` means. A policy passes `Reset` through untouched at every rung rather than thresholding it —
      // otherwise "let the terminal choose" would come back out of a two-tone policy as "paint it white".
      if from.fg != to.fg then appendParam(codes, foregroundCode(to.fg.fold(Color.Reset)(colors.downsample)))
      if from.bg != to.bg then appendParam(codes, backgroundCode(to.bg.fold(Color.Reset)(colors.downsample)))
      if from.underlineColor != to.underlineColor then
        to.underlineColor.foreach(color => appendParam(codes, underlineColorCode(colors.downsample(color))))
    if codes.isEmpty then "" else s"$Esc[${codes.result()}m"

  /** Adds one SGR parameter, writing the `;` separator only when the builder already holds something.
    *
    * That covers both callers: `absolute` seeds its builder with the leading `ESC[0` reset, so the separator is always
    * needed, while `buildDelta` starts empty and the first parameter must not be preceded by one.
    */
  private def appendParam(codes: StringBuilder, code: String): Unit =
    val _ = if codes.isEmpty then codes.append(code) else codes.append(';').append(code)

  /** SGR 4:n styled-underline selectors; `None`/`Straight` defer to the plain `4` from `ModifierCodes`. */
  private def underlineStyleCode(style: UnderlineStyle): Option[String] =
    style match
      case UnderlineStyle.None | UnderlineStyle.Straight => None
      case UnderlineStyle.Double                         => Some("4:2")
      case UnderlineStyle.Curly                          => Some("4:3")
      case UnderlineStyle.Dotted                         => Some("4:4")
      case UnderlineStyle.Dashed                         => Some("4:5")

  /** SGR 58 sets the underline color independently of the foreground; 256/truecolor forms mirror SGR 38. */
  private def underlineColorCode(color: Color): String =
    color match
      case Color.Indexed(index) => s"58:5:${Color.clampIndex(index)}"
      case other                =>
        // `Color.approximateRgb` clamps, which is the point of routing an `Rgb` through it as well as a named colour:
        // the channels of an `Rgb` are whatever the caller passed, and an out-of-range one must not reach the terminal.
        val (r, g, b) = Color.approximateRgb(other)
        s"58:2::$r:$g:$b"

  /** The base SGR code of the foreground colour group; every other foreground code is an offset from it. */
  private val ForegroundBase = 30

  /** The background group sits ten codes above the foreground one, and mirrors it entry for entry. */
  private val BackgroundBase = 40

  /** SGR 30-37 / 90-97 for the foreground, plus 38 for the 256-colour and truecolour forms. */
  private def foregroundCode(color: Color): String = colorCode(color, ForegroundBase)

  /** SGR 40-47 / 100-107 for the background, plus 48 for the 256-colour and truecolour forms. */
  private def backgroundCode(color: Color): String = colorCode(color, BackgroundBase)

  /** One SGR colour code, offset from `base` — 30 for the foreground, 40 for the background.
    *
    * Foreground and background differ by nothing but that constant (ECMA-48 §8.3.117-118): the eight named colours are
    * `base + 0` to `base + 7`, their bright variants sit 60 codes higher, `Reset` is `base + 9`, and the 256-colour and
    * truecolour extensions (ISO 8613-6) share the selector `base + 8`. Encoding that once means a new [[Color]] case or
    * a corrected SGR form cannot be added to one half and forgotten in the other.
    *
    * The match stays explicit rather than deriving the offset from `Color.ordinal`, so adding an enum case still fails
    * compilation instead of silently emitting a wrong code.
    */
  private def colorCode(color: Color, base: Int): String =
    color match
      case Color.Reset          => s"${base + 9}"
      case Color.Black          => s"${base + 0}"
      case Color.Red            => s"${base + 1}"
      case Color.Green          => s"${base + 2}"
      case Color.Yellow         => s"${base + 3}"
      case Color.Blue           => s"${base + 4}"
      case Color.Magenta        => s"${base + 5}"
      case Color.Cyan           => s"${base + 6}"
      case Color.White          => s"${base + 7}"
      case Color.BrightBlack    => s"${base + 60}"
      case Color.BrightRed      => s"${base + 61}"
      case Color.BrightGreen    => s"${base + 62}"
      case Color.BrightYellow   => s"${base + 63}"
      case Color.BrightBlue     => s"${base + 64}"
      case Color.BrightMagenta  => s"${base + 65}"
      case Color.BrightCyan     => s"${base + 66}"
      case Color.BrightWhite    => s"${base + 67}"
      // Both extended forms clamp: an index or a channel outside `0..255` is not a valid CSI parameter, and a negative
      // one is not even syntactically SGR — a terminal that gives up on the sequence prints the remainder on screen.
      case Color.Indexed(index) => s"${base + 8};5;${Color.clampIndex(index)}"
      case rgb: Color.Rgb       =>
        val (r, g, b) = Color.approximateRgb(rgb)
        s"${base + 8};2;$r;$g;$b"

  /** Every text attribute with its SGR code, in the order they are emitted.
    *
    * A `val`, not a table rebuilt inside `absolute`: `sgr` runs once per style change on a frame, and allocating this
    * nine-entry list on each of those calls cost more than everything else the method does.
    */
  private val ModifierCodes: List[(Modifiers, String)] =
    List(
      Modifiers.Bold       -> "1",
      Modifiers.Dim        -> "2",
      Modifiers.Italic     -> "3",
      Modifiers.Underline  -> "4",
      Modifiers.Blink      -> "5",
      Modifiers.RapidBlink -> "6",
      Modifiers.Reverse    -> "7",
      Modifiers.Hidden     -> "8",
      Modifiers.CrossedOut -> "9",
    )

  /** The reset code for each group of text attributes, in the order they are emitted.
    *
    * Grouped rather than one entry per flag because ECMA-48 does not give every attribute its own "off" code: SGR 22
    * turns off bold *and* dim, and SGR 25 turns off both blink rates. `buildDelta` therefore works group by group,
    * writing each reset at most once and restating whichever members of the group survive.
    *
    * A `val` for the same reason `ModifierCodes` is one: this is walked once per style change on a frame, and
    * rebuilding the list on each of those calls cost more than the walk.
    */
  private val ResetCodes: List[(Modifiers, String)] =
    List(
      (Modifiers.Bold | Modifiers.Dim)         -> "22",
      Modifiers.Italic                         -> "23",
      Modifiers.Underline                      -> "24",
      (Modifiers.Blink | Modifiers.RapidBlink) -> "25",
      Modifiers.Reverse                        -> "27",
      Modifiers.Hidden                         -> "28",
      Modifiers.CrossedOut                     -> "29",
    )
