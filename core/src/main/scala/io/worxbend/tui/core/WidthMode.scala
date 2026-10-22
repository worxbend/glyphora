package io.worxbend.tui.core

/** How to measure Unicode East Asian *Ambiguous* codepoints — box drawing, Greek and Cyrillic letters, the arrow block,
  * `±`, `×` and a few hundred more.
  *
  * Unicode gives these characters no single width. A terminal configured for a Western locale draws them one column
  * wide; one configured for a Chinese, Japanese or Korean locale draws the same characters two columns wide, because
  * the CJK fonts they use have full-width glyphs for them. Nothing an application sends can tell the emulator which to
  * do, and nothing it receives says which was chosen — the choice lives in the user's terminal settings and font.
  *
  * This is a *measurement* policy, not a rendering policy. Everything glyphora lays out and draws uses
  * [Narrow](WidthMode.html#Narrow-0), and that does not change: a widget and the buffer it clips against have to agree
  * on every column, so the answer the renderer uses has exactly one source. Passing [Wide](WidthMode.html#Wide-0) to
  * the width functions that accept a mode answers a different question — "how many columns would a CJK-locale terminal
  * give this text?" — which is what an application needs to detect the mismatch and compensate, for example by choosing
  * ASCII borders or by leaving a margin.
  *
  * The values are a sealed enumeration rather than a boolean flag so that a call site says which policy it means:
  * `CharWidth.of(text, WidthMode.Wide)` reads as an answer about a locale, where `CharWidth.of(text, true)` would read
  * as an answer about nothing in particular.
  */
enum WidthMode:

  /** Ambiguous codepoints are one column — the Western-locale answer, and the one every glyphora widget lays out with.
    */
  case Narrow

  /** Ambiguous codepoints are two columns — the answer a terminal running under a Chinese, Japanese or Korean locale
    * will give.
    */
  case Wide
