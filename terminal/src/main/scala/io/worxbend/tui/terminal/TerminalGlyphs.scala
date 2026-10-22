package io.worxbend.tui.terminal

import io.worxbend.tui.core.GlyphSupport

import java.util.Locale

/** Reads the environment for how far up [[io.worxbend.tui.core.GlyphSupport]] this terminal can be trusted to go.
  *
  * The terminal-facing half of the glyph floor, deliberately separate from the value itself: `GlyphSupport` lives in
  * `tui-core` so widgets can honour it, and nothing in `tui-core` is allowed to know that terminals exist.
  *
  * Pure with respect to its argument — it reads only the `env` map it is handed, which defaults to `sys.env` — so a
  * test names an environment instead of mutating the process's own.
  */
object TerminalGlyphs:

  /** The glyph floor this environment justifies.
    *
    * Precedence, highest first:
    *   1. `GLYPHORA_ASCII` set to any value other than empty or `0` forces
    *      [GlyphSupport.Ascii](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/GlyphSupport.html#Ascii-0).
    *      This is the escape hatch for a terminal that claims more than it can draw — a font with no braille coverage,
    *      say, where the spinner comes out as a row of replacement boxes.
    *   2. A character encoding that is not UTF-8 (or no locale variable at all) means the bytes cannot even survive the
    *      trip, so
    *      [GlyphSupport.Ascii](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/GlyphSupport.html#Ascii-0).
    *      `LC_ALL` wins over `LC_CTYPE`, which wins over `LANG`, which is the ordering POSIX defines for them.
    *   3. A Linux virtual console or a `vt`-class `TERM` gets
    *      [GlyphSupport.BoxDrawing](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/GlyphSupport.html#BoxDrawing-0):
    *      its built-in font has the box-drawing block and nothing beyond it.
    *   4. Everything else gets
    *      [GlyphSupport.Full](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/GlyphSupport.html#Full-0).
    *
    * There is deliberately no variable that forces the floor *up*.
    * [GlyphSupport.Full](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/GlyphSupport.html#Full-0) is
    * already what any UTF-8 environment resolves to, so an opt-in would only ever be used to overrule rule 2 or 3 — and
    * both of those describe terminals that genuinely cannot draw the glyphs.
    */
  def detect(env: Map[String, String] = sys.env): GlyphSupport =
    if forcedAscii(env) then GlyphSupport.Ascii
    else if !utf8Locale(env) then GlyphSupport.Ascii
    else if consoleFont(env) then GlyphSupport.BoxDrawing
    else GlyphSupport.Full

  /** Whether the user asked, in so many words, for ASCII only. `0` and the empty value mean "no", matching how
    * `CLICOLOR` is read a few files over, so that one convention covers every switch this library reads.
    */
  private def forcedAscii(env: Map[String, String]): Boolean =
    env.get("GLYPHORA_ASCII").exists(value => value.nonEmpty && value != "0")

  /** Whether the locale's character encoding is UTF-8.
    *
    * `Locale.ROOT`, not the default locale, for the same reason [[consoleFont]] below needs it: a lowercase compare
    * against a fixed ASCII literal must not depend on the platform's default locale, or a Turkish one would fold an `I`
    * in the literal being matched — such as the `"linux"` `consoleFont` compares `TERM` against — to a dotless `ı` that
    * the input can never match. Neither literal this method compares against contains an `I`, but the rule is applied
    * uniformly rather than re-litigated per call site; see `core.KeyEvent.keyCodeFor` for the case that does.
    */
  private def utf8Locale(env: Map[String, String]): Boolean =
    val charset = Seq("LC_ALL", "LC_CTYPE", "LANG").flatMap(env.get).find(_.nonEmpty).getOrElse("")
    val lower   = charset.toLowerCase(Locale.ROOT)
    lower.contains("utf-8") || lower.contains("utf8")

  /** Whether `TERM` names a terminal drawing with a built-in console font rather than one the user chose.
    *
    * `linux` is the Linux virtual console and `vt100`/`vt220` and friends are the hardware terminals and the emulators
    * that imitate them. All of them have the box-drawing block; none of them has braille or emoji. An empty or `dumb`
    * `TERM` never reaches here — it fails the locale test only by accident, so it is named explicitly.
    */
  private def consoleFont(env: Map[String, String]): Boolean =
    val term = env.getOrElse("TERM", "").toLowerCase(Locale.ROOT)
    term.isEmpty || term == "dumb" || term.startsWith("linux") || term.startsWith("vt")
