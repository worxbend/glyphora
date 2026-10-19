package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Color, Style}

import java.util.Locale

/** How many colors the terminal can actually show; RGB output is downsampled to fit.
  *
  * The rungs run from most to least capable. [[Monochrome]] and [[NoColor]] are the two ways of coping with a terminal
  * that cannot show a palette, and they are not the same thing:
  *   - [[Monochrome]] still emits color codes, but every color is thresholded to black or white by how bright it is. A
  *     selection that is conveyed only by a background color stays visible, which is what a two-tone terminal — or a
  *     black-and-white screen capture of a colorful app — needs.
  *   - [[NoColor]] is not a device capability but an explicit opt-out: when it is in effect the backend emits text
  *     attributes (bold, underline, …) but no foreground/background color at all. It is what honoring the `NO_COLOR`
  *     convention resolves to.
  *
  * Nothing in the environment ever resolves to [[Monochrome]]; it is opt-in, by overriding the application's
  * `colorDepth`.
  */
enum ColorDepth:
  case TrueColor, Ansi256, Ansi16, Monochrome, NoColor

object ColorDepth:

  /** Resolves the effective color depth from the environment.
    *
    * `GLYPHORA_COLOR_DEPTH` (`truecolor`, `256`, `16`, `mono`, `none`) pins the answer outright, ahead of everything
    * below — the switch for a terminal that misreports itself; see [[EnvironmentOverrides]]. Without it, precedence
    * follows the widely-adopted conventions (https://no-color.org and https://bixense.com/clicolors/):
    *   1. `NO_COLOR` set to any non-empty value, `CLICOLOR` set to exactly `0`, or `FORCE_COLOR=0` disables color
    *      entirely — unless
    *   2. `CLICOLOR_FORCE` is set to a non-zero value, or `FORCE_COLOR` to anything but `0`, which forces color on even
    *      when the line above asks for none or the output is not a TTY. A `FORCE_COLOR` of `1`, `2` or `3` also picks
    *      the depth — sixteen colours, the 256 palette, true colour — the way Node's `supports-color` reads it.
    *   3. `TERM=dumb` resolves to [[NoColor]]: by convention such a terminal understands no escape sequences at all, so
    *      a `COLORTERM` inherited from an outer terminal must not resurrect color.
    *   4. Otherwise `COLORTERM=truecolor|24bit` wins (subject to the corrections in [[capability]]), a `256color`
    *      `TERM` falls back to the 256 palette, and everything else to the classic 16.
    *   5. An unset or empty `TERM` with no `COLORTERM` signal resolves to [[NoColor]]: that is what output redirected
    *      into a file looks like from inside the process.
    *
    * A `CLICOLOR_FORCE` lifts steps 3 and 5 too: if the environment would otherwise say "no color at all", forcing
    * color on yields the classic sixteen.
    */
  def detect(env: Map[String, String] = sys.env): ColorDepth =
    EnvironmentOverrides.colorDepth(env).getOrElse(detectUnpinned(env))

  /** Whether `env` forces colour on — `CLICOLOR_FORCE` set to a non-empty value other than `0`, or `FORCE_COLOR` set to
    * anything but `0` (step 2 of [[detect]]). The one reading of the rule, for a caller that must decide "colour even
    * though this is not a terminal" before it asks for a depth.
    */
  def colorForced(env: Map[String, String]): Boolean =
    env.get("CLICOLOR_FORCE").exists(value => value.nonEmpty && value != "0") ||
      env.get("FORCE_COLOR").exists(_ != "0")

  /** [[detect]] without the `GLYPHORA_COLOR_DEPTH` pin. */
  private def detectUnpinned(env: Map[String, String]): ColorDepth =
    val forceColor = env.get("FORCE_COLOR")
    val forced     = colorForced(env)
    val disabled   = env.get("NO_COLOR").exists(_.nonEmpty) || env.get("CLICOLOR").contains("0") ||
      forceColor.contains("0")
    if disabled && !forced then NoColor
    else
      forceColor.flatMap(forcedLevel) match
        case Some(level) => level
        case None        =>
          val detected = capability(env)
          // `CLICOLOR_FORCE` means "emit color anyway", so it also lifts the two capability answers of NoColor — a
          // `dumb` or absent TERM — back to the classic sixteen colors, which is the safest thing to force on.
          if forced && detected == NoColor then Ansi16 else detected

  /** The depth a numeric `FORCE_COLOR` level names outright (the Node convention: `1` sixteen colours, `2` the 256
    * palette, `3` true colour); any other value forces colour on without choosing a depth.
    */
  private def forcedLevel(value: String): Option[ColorDepth] =
    value match
      case "1" => Some(Ansi16)
      case "2" => Some(Ansi256)
      case "3" => Some(TrueColor)
      case _   => None

  /** The capability half of [[detect]], on the environment variables the conventions above name.
    *
    * Two corrections apply to a `COLORTERM` claim, because some terminals advertise 24-bit color they cannot render and
    * then mangle every RGB escape instead of showing an approximation:
    *   - macOS `Terminal.app` (`TERM_PROGRAM=Apple_Terminal`) only gained 24-bit SGR support in build 465. An older
    *     build, or a build number this code cannot read, is capped at the 256 palette.
    *   - Inside `screen` or `tmux` the multiplexer passes the outer terminal's `COLORTERM` through without necessarily
    *     being able to honor it. Such a session is capped at the 256 palette unless its own `TERM` says otherwise
    *     (`tmux-direct`, `screen-truecolor`, …), which is the documented way of saying the multiplexer was configured
    *     to pass 24-bit color through.
    *
    * All comparisons lower-case with `Locale.ROOT` rather than the default locale. Under a Turkish locale
    * `"24BIT".toLowerCase` is `"24bıt"` with a dotless i, so `contains("24bit")` fails and a true-colour terminal is
    * silently downgraded to sixteen colours — every RGB style in the app flattens to the nearest named colour because
    * of the user's language setting.
    */
  private def capability(env: Map[String, String]): ColorDepth =
    val colorterm       = env.getOrElse("COLORTERM", "").toLowerCase(Locale.ROOT)
    val term            = env.getOrElse("TERM", "").toLowerCase(Locale.ROOT)
    val claimsTrueColor = colorterm.contains("truecolor") || colorterm.contains("24bit")
    if term == "dumb" then NoColor
    else if claimsTrueColor && canRenderTrueColor(env, term) then TrueColor
    else if claimsTrueColor || term.contains("256") then Ansi256
    else if term.isEmpty then NoColor
    else Ansi16

  /** Whether a terminal that *claims* 24-bit color can actually show it; see [[capability]] for the two exceptions.
    *
    * Anything not recognised as one of those exceptions is believed, so the common case (a modern terminal setting
    * `COLORTERM=truecolor`) is unaffected.
    */
  private def canRenderTrueColor(env: Map[String, String], term: String): Boolean =
    val program = env.getOrElse("TERM_PROGRAM", "")
    if program.equalsIgnoreCase("Apple_Terminal") then appleTerminalHasTrueColor(env)
    else if term.startsWith("screen") || term.startsWith("tmux") then
      term.contains("truecolor") || term.contains("direct")
    else true

  /** `Terminal.app` build 465 is the first that renders 24-bit SGR; `TERM_PROGRAM_VERSION` carries the build number,
    * sometimes with a suffix such as `465.1`, so only the leading digits are read.
    *
    * A missing or unreadable version counts as *not* capable on purpose: guessing too low costs a slightly duller
    * frame, guessing too high costs unreadable escape sequences on screen.
    */
  private def appleTerminalHasTrueColor(env: Map[String, String]): Boolean =
    env.get("TERM_PROGRAM_VERSION").flatMap(_.takeWhile(_.isDigit).toIntOption).exists(_ >= 465)

  /** Reduces `color` to something `depth` can represent (identity for capable terminals). [[NoColor]] is handled by the
    * SGR encoder dropping color codes, so this returns the color unchanged for it.
    *
    * [[Color.Reset]] survives every rung untouched, because it is not a colour at all: it is the instruction "use the
    * terminal's own foreground/background", SGR 39/49. Under [[Monochrome]] it used to go through the luminance
    * threshold like any other value — `Color.approximateRgb(Reset)` is a light grey — and an application that asked for
    * the terminal's default background got an explicit white block painted instead.
    */
  def downsample(color: Color, depth: ColorDepth): Color =
    if color == Color.Reset then color
    else
      depth match
        case TrueColor  => color
        case NoColor    => color
        case Monochrome => if isLight(color) then Color.White else Color.Black
        case Ansi256    =>
          color match
            case rgb: Color.Rgb => Color.Indexed(nearestIndexed(rgb))
            case other          => other
        case Ansi16     =>
          color match
            case Color.Rgb(r, g, b)                  => nearestNamed(r, g, b)
            case Color.Indexed(index) if index >= 16 =>
              val (r, g, b) = Color.approximateRgb(Color.Indexed(index))
              nearestNamed(r, g, b)
            case other                               => other

  /** Rec.709 relative luminance of `color`, on the same 0-255 scale as its channels.
    *
    * Green counts for far more than blue because the eye is far more sensitive to it: naively averaging the channels
    * would call pure blue and pure yellow equally bright, and thresholding both the same way would make yellow text on
    * a blue background disappear. Goes through [[Color.approximateRgb]] so a named or indexed color answers the same
    * question as an RGB one. Integer arithmetic on purpose, so there is no floating-point rounding to argue about.
    */
  private def luminance(color: Color): Int =
    val (r, g, b) = Color.approximateRgb(color)
    (2126 * r + 7152 * g + 722 * b) / 10000

  /** Which of the two tones [[Monochrome]] maps `color` to: `true` is the light one (white), `false` the dark one. */
  private def isLight(color: Color): Boolean = luminance(color) >= 128

  /** Keeps a style legible under [[Monochrome]], where every color collapses onto one of two tones.
    *
    * Two colors that differ on a full palette can land on the same tone — red text on a blue background both threshold
    * to dark — and the text would then be invisible. When that happens the background keeps the tone it thresholds to
    * and the foreground is flipped to the opposite one.
    *
    * A style that sets a background but no foreground gets the same treatment: its text is drawn in the terminal's
    * default foreground, which may itself threshold to the background's tone, so an explicit contrasting foreground is
    * named rather than gambling on it. This is the ordinary selection highlight, which is exactly the row a user cannot
    * afford to lose. The underline color is corrected the same way, because it thresholds independently of both and can
    * otherwise sink into the background once the text is legible.
    *
    * A style that sets no background at all is left alone: it draws on whatever the terminal's own background is, which
    * this code cannot know.
    *
    * Modifiers are not touched by color depth, so `Modifiers.Reverse` remains the way to express a highlight that
    * survives every rung of the ladder.
    *
    * Every depth but [[Monochrome]] answers `style` untouched, so a caller may hand every style through this without
    * checking the depth first — the unconditional call is doing no hidden work at the other rungs.
    */
  private[terminal] def legible(style: Style, depth: ColorDepth): Style =
    if depth != Monochrome then style
    else
      style.bg match
        // No background — and `Color.Reset`, which asks for the terminal's own background by name — means the cell
        // draws on a colour this code cannot know, so there is no collision to detect and nothing to correct.
        case None              => style
        case Some(Color.Reset) => style
        case Some(bg)          =>
          val contrast  = if isLight(bg) then Color.Black else Color.White
          // A style that sets *only* a background is the common selection highlight. Its text is drawn in the
          // terminal's default foreground, which may well threshold to the same tone as the background and vanish —
          // so name a foreground that contrasts rather than leaving the row unreadable. A foreground of `Color.Reset`
          // is that same unknowable default under another name, so it is corrected the same way.
          val fixedFg   = style.fg match
            case Some(fg) if fg != Color.Reset && isLight(fg) != isLight(bg) => fg
            case _                                                           => contrast
          // The underline color thresholds independently of the two above, so a curly underline can land on the
          // background tone and disappear even once the text is legible.
          val recolored = style.withFg(fixedFg)
          recolored.underlineColor match
            case Some(underline) if isLight(underline) == isLight(bg) =>
              recolored.withUnderlineColor(contrast)
            case _                                                    => recolored

  /** Nearest xterm-256 palette entry: the grayscale ramp for near-gray values, else the 6x6x6 color cube. */
  private def nearestIndexed(rgb: Color.Rgb): Int =
    // through `approximateRgb`, which clamps: reading the channels raw let an out-of-range literal such as
    // `Rgb(999, -1, 0)` push the cube arithmetic past the palette and produce index 880, which is not a colour
    val (r, g, b) = Color.approximateRgb(rgb)
    val isGrayish = math.abs(r - g) < 10 && math.abs(g - b) < 10
    if isGrayish && r >= 4 && r <= 243 then 232 + math.min(23, math.max(0, (r - 8) / 10))
    else 16 + 36 * cubeStep(r) + 6 * cubeStep(g) + cubeStep(b)

  private def cubeStep(value: Int): Int =
    if value < 48 then 0 else if value < 115 then 1 else (value - 35) / 40

  /** The sixteen colors [[Ansi16]] can name: the eight base ones (SGR 30-37) and their bright counterparts (SGR 90-97).
    *
    * The bright half belongs here because this depth already emits it — a style naming [[Color.BrightRed]] passes
    * through [[downsample]] untouched and reaches the encoder as `91`. Leaving those eight out of the *search* only
    * meant that a color arriving as RGB could never reach them, so pure red downsampled to the muted (205, 49, 49) of
    * `Color.Red` while an exact match sat unused one entry away.
    */
  private val Ansi16Palette: Seq[Color] = Seq(
    Color.Black,
    Color.Red,
    Color.Green,
    Color.Yellow,
    Color.Blue,
    Color.Magenta,
    Color.Cyan,
    Color.White,
    Color.BrightBlack,
    Color.BrightRed,
    Color.BrightGreen,
    Color.BrightYellow,
    Color.BrightBlue,
    Color.BrightMagenta,
    Color.BrightCyan,
    Color.BrightWhite,
  )

  private def nearestNamed(r: Int, g: Int, b: Int): Color =
    Ansi16Palette.minBy { candidate =>
      val (cr, cg, cb) = Color.approximateRgb(candidate)
      val (dr, dg, db) = (cr - r, cg - g, cb - b)
      dr * dr + dg * dg + db * db
    }
