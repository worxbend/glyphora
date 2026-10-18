package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Color, Style}

import org.scalatest.funsuite.AnyFunSuite

final class ColorDepthSpec extends AnyFunSuite:

  test("detection prefers COLORTERM, falls back to TERM, then 16 colors"):
    assert(ColorDepth.detect(Map("COLORTERM" -> "truecolor")) == ColorDepth.TrueColor)
    assert(ColorDepth.detect(Map("COLORTERM" -> "24bit", "TERM" -> "xterm")) == ColorDepth.TrueColor)
    assert(ColorDepth.detect(Map("TERM" -> "xterm-256color")) == ColorDepth.Ansi256)
    assert(ColorDepth.detect(Map("TERM" -> "vt100")) == ColorDepth.Ansi16)

  /** Case folding uses `Locale.ROOT`, not the JVM's default locale. Under a Turkish locale the default folding turns
    * `"24BIT"` into `"24bıt"` (a dotless i), which matches nothing — so a true-colour terminal used to be downgraded to
    * sixteen colours purely because of the user's language setting.
    */
  test("capability detection is case-insensitive in every locale"):
    assert(ColorDepth.detect(Map("COLORTERM" -> "24BIT")) == ColorDepth.TrueColor)
    assert(ColorDepth.detect(Map("COLORTERM" -> "TrueColor")) == ColorDepth.TrueColor)
    assert(ColorDepth.detect(Map("TERM" -> "XTERM-256COLOR")) == ColorDepth.Ansi256)

  test("NO_COLOR disables color regardless of terminal capability"):
    assert(ColorDepth.detect(Map("NO_COLOR" -> "1", "COLORTERM" -> "truecolor")) == ColorDepth.NoColor)
    assert(ColorDepth.detect(Map("NO_COLOR" -> "anything")) == ColorDepth.NoColor)

  test("an empty NO_COLOR does not disable color (per the no-color.org convention)"):
    assert(ColorDepth.detect(Map("NO_COLOR" -> "", "COLORTERM" -> "truecolor")) == ColorDepth.TrueColor)

  test("CLICOLOR_FORCE overrides NO_COLOR"):
    assert(
      ColorDepth.detect(
        Map("NO_COLOR" -> "1", "CLICOLOR_FORCE" -> "1", "COLORTERM" -> "truecolor")
      ) == ColorDepth.TrueColor
    )
    assert(ColorDepth.detect(Map("NO_COLOR" -> "1", "CLICOLOR_FORCE" -> "0")) == ColorDepth.NoColor)

  test("TERM=dumb disables color even when COLORTERM advertises true color"):
    assert(ColorDepth.detect(Map("TERM" -> "dumb", "COLORTERM" -> "truecolor")) == ColorDepth.NoColor)
    assert(ColorDepth.detect(Map("TERM" -> "DUMB")) == ColorDepth.NoColor)
    // exact equality, not a substring match: a terminfo name that merely contains "dumb" is a real terminal
    assert(ColorDepth.detect(Map("TERM" -> "xterm-dumbterm")) == ColorDepth.Ansi16)

  test("CLICOLOR=0 disables color, and CLICOLOR_FORCE still overrides it"):
    assert(ColorDepth.detect(Map("CLICOLOR" -> "0", "TERM" -> "xterm-256color")) == ColorDepth.NoColor)
    assert(ColorDepth.detect(Map("CLICOLOR" -> "1", "TERM" -> "xterm-256color")) == ColorDepth.Ansi256)
    assert(ColorDepth.detect(Map("CLICOLOR" -> "0", "CLICOLOR_FORCE" -> "1", "TERM" -> "vt100")) == ColorDepth.Ansi16)

  /** An unset or empty `TERM` is what a process sees when its output is a file rather than a terminal. The two
    * assertions at the end guard the ordering: an explicit `COLORTERM`, or a forced-on `CLICOLOR_FORCE`, still wins.
    */
  test("an absent TERM means output is not going to a terminal"):
    assert(ColorDepth.detect(Map.empty) == ColorDepth.NoColor)
    assert(ColorDepth.detect(Map("TERM" -> "")) == ColorDepth.NoColor)
    assert(ColorDepth.detect(Map("COLORTERM" -> "truecolor")) == ColorDepth.TrueColor)
    assert(ColorDepth.detect(Map("CLICOLOR_FORCE" -> "1")) == ColorDepth.Ansi16)

  test("an old Apple Terminal.app is capped at 256 colors despite its COLORTERM"):
    def apple(version: Option[String]): ColorDepth =
      ColorDepth.detect(
        Map("TERM" -> "xterm-256color", "COLORTERM" -> "truecolor", "TERM_PROGRAM" -> "Apple_Terminal")
          ++ version.map("TERM_PROGRAM_VERSION" -> _)
      )
    assert(apple(Some("440")) == ColorDepth.Ansi256)
    assert(apple(Some("465")) == ColorDepth.TrueColor)
    assert(apple(Some("465.1")) == ColorDepth.TrueColor)
    assert(apple(None) == ColorDepth.Ansi256)
    assert(apple(Some("abc")) == ColorDepth.Ansi256)
    // the vendor token is matched case-insensitively
    assert(
      ColorDepth.detect(
        Map(
          "TERM"                 -> "xterm-256color",
          "COLORTERM"            -> "truecolor",
          "TERM_PROGRAM"         -> "apple_terminal",
          "TERM_PROGRAM_VERSION" -> "440",
        )
      ) == ColorDepth.Ansi256
    )

  /** `screen` and `tmux` hand the outer terminal's `COLORTERM` to the program without necessarily being able to honor
    * it, so the claim is not believed unless the multiplexer's own `TERM` says direct color was passed through.
    */
  test("a multiplexer TERM caps a COLORTERM claim at 256 colors unless it advertises direct color"):
    assert(ColorDepth.detect(Map("TERM" -> "screen-256color", "COLORTERM" -> "truecolor")) == ColorDepth.Ansi256)
    assert(ColorDepth.detect(Map("TERM" -> "tmux-256color", "COLORTERM" -> "truecolor")) == ColorDepth.Ansi256)
    assert(ColorDepth.detect(Map("TERM" -> "tmux-direct", "COLORTERM" -> "truecolor")) == ColorDepth.TrueColor)
    assert(ColorDepth.detect(Map("TERM" -> "screen-truecolor", "COLORTERM" -> "24bit")) == ColorDepth.TrueColor)

  test("the corrections leave every other terminal alone"):
    assert(ColorDepth.detect(Map("TERM" -> "xterm-256color", "COLORTERM" -> "24bit")) == ColorDepth.TrueColor)
    assert(
      ColorDepth.detect(Map("TERM" -> "xterm-256color", "COLORTERM" -> "truecolor", "TERM_PROGRAM" -> "iTerm.app"))
        == ColorDepth.TrueColor
    )
    val oldApple =
      Map("COLORTERM" -> "truecolor", "TERM_PROGRAM" -> "Apple_Terminal", "TERM_PROGRAM_VERSION" -> "440")
    assert(ColorDepth.detect(oldApple + ("NO_COLOR" -> "1")) == ColorDepth.NoColor)
    // forced back on, but still corrected: the correction is a capability question, not an opt-out
    assert(ColorDepth.detect(oldApple + ("NO_COLOR" -> "1") + ("CLICOLOR_FORCE" -> "1")) == ColorDepth.Ansi256)

  /** Monochrome is opt-in, by overriding the application's `colorDepth`; no environment resolves to it. */
  test("detection never resolves to Monochrome"):
    val environments = Seq(
      Map.empty[String, String],
      Map("TERM"     -> "dumb"),
      Map("NO_COLOR" -> "1"),
      Map("TERM"     -> "xterm-256color", "COLORTERM" -> "truecolor"),
    )
    assert(environments.forall(env => ColorDepth.detect(env) != ColorDepth.Monochrome))

  /** Monochrome maps color to contrast instead of discarding it: bright colors become white, dark ones black, judged by
    * Rec.709 luminance rather than a channel average — which is why blue is dark and yellow is light.
    */
  test("Monochrome thresholds every color to black or white by luminance"):
    def mono(color: Color): Color = ColorDepth.downsample(color, ColorDepth.Monochrome)
    assert(mono(Color.Rgb(0, 0, 0)) == Color.Black)
    assert(mono(Color.Rgb(255, 255, 255)) == Color.White)
    assert(mono(Color.Rgb(128, 128, 128)) == Color.White)
    assert(mono(Color.Rgb(127, 127, 127)) == Color.Black)
    assert(mono(Color.Blue) == Color.Black)
    assert(mono(Color.BrightYellow) == Color.White)
    assert(mono(Color.Indexed(196)) == Color.Black)

  /** Contrast with the NoColor assertions above, which emit no color codes at all: a selection drawn only as a
    * background color survives this rung.
    */
  test("Monochrome still emits color codes, unlike NoColor"):
    val style = Style.Default.withFg(Color.Rgb(200, 0, 0)).withBg(Color.Rgb(20, 20, 20))
    val codes = Sgr.sgr(style, ColorDepth.Monochrome)
    assert(codes.contains("37")) // white foreground
    assert(codes.contains("40")) // black background
    assert(Sgr.sgr(style, ColorDepth.NoColor) == "\u001b[0m")

  test("Monochrome flips a foreground that would land on the same tone as its background"):
    val bothLight = Style.Default.withFg(Color.White).withBg(Color.BrightYellow)
    assert(Sgr.sgr(bothLight, ColorDepth.Monochrome) == "\u001b[0;30;47m")
    val bothDark  = Style.Default.withFg(Color.Blue).withBg(Color.Black)
    assert(Sgr.sgr(bothDark, ColorDepth.Monochrome) == "\u001b[0;37;40m")
    // a style with no background is left alone: the terminal's own background is unknowable here
    assert(Sgr.sgr(Style.Default.withFg(Color.Blue), ColorDepth.Monochrome) == "\u001b[0;30m")

  test("Monochrome names a contrasting foreground for a background-only style"):
    // the ordinary selection highlight sets a background and lets the text inherit the terminal's foreground. That
    // foreground can threshold to the background's own tone, and the selected row would then be a solid block.
    val lightSelection = Style.Default.withBg(Color.BrightWhite)
    assert(Sgr.sgr(lightSelection, ColorDepth.Monochrome) == "[0;30;47m")
    val darkSelection  = Style.Default.withBg(Color.Black)
    assert(Sgr.sgr(darkSelection, ColorDepth.Monochrome) == "[0;37;40m")

  test("Monochrome leaves Color.Reset alone, because it names no colour to threshold"):
    // `Color.Reset` is not a colour but an instruction: "use whatever the terminal's own colour is". Thresholding it
    // by luminance turned that into an explicit white block, under a foreground the application never chose either.
    assert(ColorDepth.downsample(Color.Reset, ColorDepth.Monochrome) == Color.Reset)
    val defaultBg = Style.Default.withBg(Color.Reset)
    assert(Sgr.sgr(defaultBg, ColorDepth.Monochrome) == s"${AnsiSequences.Esc}[0;49m")
    assert(Sgr.sgr(defaultBg, ColorDepth.Monochrome) == Sgr.sgr(defaultBg, ColorDepth.Ansi16))
    // 49 is the background going back to the terminal's own; 39 is the foreground doing the same, because the red
    // background it is leaving had been given an explicit contrasting foreground to stay readable.
    val fromRed   = Sgr.sgrDelta(Style.Default.withBg(Color.Red), defaultBg, ColorDepth.Monochrome)
    assert(fromRed == s"${AnsiSequences.Esc}[39;49m")

  test("Monochrome lifts an underline color off the background tone"):
    val onDark  = Style.Default.withBg(Color.Black).curlyUnderline.withUnderlineColor(Color.Rgb(10, 10, 10))
    // the underline thresholds dark, exactly like the background, so it is flipped to the contrasting tone
    assert(Sgr.sgr(onDark, ColorDepth.Monochrome).contains("58:2::229:229:229"))
    val onLight = Style.Default.withBg(Color.BrightWhite).curlyUnderline.withUnderlineColor(Color.Rgb(240, 240, 240))
    // same collision on a light background, flipped the other way
    assert(Sgr.sgr(onLight, ColorDepth.Monochrome).contains("58:2::0:0:0"))

  test("NoColor drops color codes from SGR but keeps text attributes"):
    val style = Style.Default.withFg(Color.Rgb(255, 0, 0)).withBg(Color.Blue).bold.underline
    assert(Sgr.sgr(style, ColorDepth.NoColor) == "[0;1;4m")

  test("NoColor keeps the styled-underline attribute but drops the underline color"):
    val style = Style.Default.curlyUnderline.withUnderlineColor(Color.Rgb(255, 0, 0))
    assert(Sgr.sgr(style, ColorDepth.NoColor) == "[0;4:3m") // 4:3 attr kept, 58 color gone
    assert(Sgr.sgr(style, ColorDepth.TrueColor) == "[0;4:3;58:2::255:0:0m")

  test("truecolor passes rgb through; 256 maps rgb into the palette"):
    val red = Color.Rgb(255, 0, 0)
    assert(ColorDepth.downsample(red, ColorDepth.TrueColor) == red)
    assert(ColorDepth.downsample(red, ColorDepth.Ansi256) == Color.Indexed(196))
    assert(ColorDepth.downsample(Color.Rgb(128, 128, 128), ColorDepth.Ansi256) == Color.Indexed(244))

  test("16-color terminals get the nearest named color"):
    assert(ColorDepth.downsample(Color.Rgb(0, 0, 0), ColorDepth.Ansi16) == Color.Black)
    assert(ColorDepth.downsample(Color.Rgb(200, 50, 50), ColorDepth.Ansi16) == Color.Red)
    assert(ColorDepth.downsample(Color.Cyan, ColorDepth.Ansi16) == Color.Cyan)

  /** The bright half of the palette is searched too, so a saturated color reduces to its exact entry instead of the
    * muted base one. `Color.Red` is (205, 49, 49), a visibly different red from (255, 0, 0).
    */
  test("16-color downsampling reaches the bright half of the palette"):
    assert(ColorDepth.downsample(Color.Rgb(255, 0, 0), ColorDepth.Ansi16) == Color.BrightRed)
    assert(ColorDepth.downsample(Color.Rgb(255, 255, 255), ColorDepth.Ansi16) == Color.BrightWhite)
    assert(ColorDepth.downsample(Color.Indexed(196), ColorDepth.Ansi16) == Color.BrightRed)
    // and the bright codes really are what a 16-color terminal receives
    val bright = Style.Default.withFg(Color.Rgb(255, 0, 0))
    assert(Sgr.sgr(bright, ColorDepth.Ansi16).contains("91"))

  test("sgr downsampling changes the emitted codes"):
    val style = Style.Default.withFg(Color.Rgb(255, 0, 0))
    assert(Sgr.sgr(style, ColorDepth.TrueColor).contains("38;2;255;0;0"))
    assert(Sgr.sgr(style, ColorDepth.Ansi256).contains("38;5;196"))
    assert(Sgr.sgr(style, ColorDepth.Ansi16).contains("91"))

  /** `FORCE_COLOR` is the Node/chalk convention print mode has to honour alongside `CLICOLOR_FORCE`: set, it forces
    * colour on over `NO_COLOR` and over an absent `TERM`; `1`/`2`/`3` pick the depth outright; `0` is an opt-out.
    */
  test("FORCE_COLOR forces color on, picks a depth by level, and 0 opts out"):
    assert(
      ColorDepth.detect(Map("FORCE_COLOR" -> "1", "NO_COLOR" -> "1", "COLORTERM" -> "truecolor")) == ColorDepth.Ansi16
    )
    assert(ColorDepth.detect(Map("FORCE_COLOR" -> "2")) == ColorDepth.Ansi256)
    assert(ColorDepth.detect(Map("FORCE_COLOR" -> "3", "TERM" -> "dumb")) == ColorDepth.TrueColor)
    // any other value — `true`, or the empty string Node treats as "on" — forces the capability the terminal reports,
    // lifted to sixteen colours when that capability was "none"
    assert(ColorDepth.detect(Map("FORCE_COLOR" -> "true", "COLORTERM" -> "truecolor")) == ColorDepth.TrueColor)
    assert(ColorDepth.detect(Map("FORCE_COLOR" -> "")) == ColorDepth.Ansi16)
    assert(ColorDepth.detect(Map("FORCE_COLOR" -> "0", "COLORTERM" -> "truecolor")) == ColorDepth.NoColor)
