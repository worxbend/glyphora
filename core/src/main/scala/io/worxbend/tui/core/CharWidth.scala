package io.worxbend.tui.core

import java.util.Arrays

/** Terminal display-width arithmetic.
  *
  * `String.length` counts UTF-16 code units, not terminal columns: CJK characters occupy two columns, combining marks
  * occupy none, and one emoji ZWJ sequence can span many codepoints yet occupy two columns. All layout and rendering
  * math in this library must go through this object — no code outside it may use `String.length` or `String.substring`
  * for anything that affects layout.
  *
  * Widths are derived from the Unicode Character Database: East Asian Width `W`/`F` codepoints (see [[WidthTable]]) are
  * two columns; combining marks, format controls, and conjoining Hangul jamo are zero; everything else is one. East
  * Asian Width `A` (Ambiguous) codepoints — box drawing, Greek, Cyrillic, arrows, `±`, `×` — are one column, which is
  * what a Western-locale terminal draws; the overloads that take a [[WidthMode]] answer the CJK-locale question
  * instead, and that mode is the one thing here a caller may vary. Variation selectors override the base width — VS16
  * forces emoji presentation (two columns), VS15 text presentation (one) — but only after a base that has both
  * presentations; after a letter or an ideograph they are inert. A regional-indicator pair (flag emoji) is two.
  *
  * Everything here is a pure function of its arguments and holds no state, so it is safe to call from any thread. The
  * one exception is [[graphemeClusters]], which hands back a stateful iterator; its own documentation states who owns
  * that iterator.
  */
object CharWidth:

  /** Display width of `text` in terminal columns. Control characters count as zero.
    *
    * Ambiguous-width codepoints count as one column, which is what a Western-locale terminal draws and what every
    * widget in this library lays out with. Use the [[WidthMode]] overload to ask the CJK-locale question.
    */
  def of(text: String): Int = of(text, WidthMode.Narrow)

  /** Display width of `text` in terminal columns, measuring East Asian Ambiguous codepoints according to `mode`.
    *
    * `WidthMode.Narrow` gives exactly the number the single-argument [[of]] gives, and is what the renderer uses.
    * `WidthMode.Wide` gives the number a terminal running under a Chinese, Japanese or Korean locale would use — see
    * [[WidthMode]] for why the two differ and why an application might want to know both.
    */
  def of(text: String, mode: WidthMode): Int =
    // the ASCII fast path holds under either mode: no codepoint in 0x20-0x7E is East Asian Ambiguous, so there is
    // nothing for the mode to change. Do not "fix" this by taking the fast path away from the Wide branch.
    if isPrintableAscii(text) then text.length
    else
      var total    = 0
      val clusters = graphemeClusters(text)
      while clusters.hasNext do total += clusterWidth(clusters.next(), mode)
      total

  /** How many grapheme clusters `text` contains — the number of characters a reader would say it has, which is not
    * `text.length` (UTF-16 code units) and not [[of]] (terminal columns): an emoji is one cluster, two code units and
    * two columns.
    */
  def clusterCount(text: String): Int =
    if isPrintableAscii(text) then text.length
    else
      var total    = 0
      val clusters = graphemeClusters(text)
      while clusters.hasNext do
        val _ = clusters.next()
        total += 1
      total

  /** Whether every char of `text` is printable US-ASCII (`0x20`–`0x7E`), i.e. exactly one column each.
    *
    * The fast path that keeps the overwhelmingly common case allocation-free: no iterator, no per-cluster `String`.
    * Deliberately excludes control characters (width 0) and DEL, so it can never disagree with the general path.
    */
  def isPrintableAscii(text: String): Boolean =
    var index = 0
    var plain = true
    while plain && index < text.length do
      val c = text.charAt(index)
      if c < FirstPrintableAscii || c > LastPrintableAscii then plain = false
      index += 1
    plain

  /** A shared single-character `String` for a printable-ASCII char, so filling a buffer with ASCII allocates nothing.
    *
    * Returns `null` for anything outside `0x20`–`0x7E`; callers on the fast path have already checked. That sentinel is
    * why this is `private[core]` and not part of what `tui-core` publishes: an `Option` would allocate on every ASCII
    * character written, and a `null` nobody outside this module can reach is a local trade rather than a contract.
    */
  private[core] def asciiSymbol(c: Char): String =
    if c >= FirstPrintableAscii && c <= LastPrintableAscii then AsciiSymbols(c - FirstPrintableAscii)
    else null // scalafix:ok DisableSyntax; hot-path sentinel: an Option here allocates on every ASCII character

  /** The longest prefix of `text` that fits in `maxWidth` columns; never splits a grapheme cluster. */
  def substringByWidth(text: String, maxWidth: Int): String = substringByWidth(text, maxWidth, WidthMode.Narrow)

  /** [[substringByWidth]] measuring East Asian Ambiguous codepoints according to `mode` — the prefix that would fit
    * `maxWidth` columns of a terminal running that locale's width rules.
    */
  def substringByWidth(text: String, maxWidth: Int, mode: WidthMode): String =
    if isPrintableAscii(text) then
      return text.substring(
        0,
        math.max(0, math.min(text.length, maxWidth)),
      ) // scalafix:ok DisableSyntax; hot-path early exit: the ASCII fast path is the most-called branch in the toolkit
    val clusters  = graphemeClusters(text)
    val prefix    = StringBuilder()
    var used      = 0
    var truncated = false
    while !truncated && clusters.hasNext do
      val cluster = clusters.next()
      val width   = clusterWidth(cluster, mode)
      if used + width > maxWidth then truncated = true
      else
        prefix.append(cluster)
        used += width
    prefix.result()

  /** The suffix of `text` that remains after the first `skipWidth` display columns; never splits a grapheme cluster.
    *
    * The mirror image of [[substringByWidth]]: that method answers "what fits in N columns", this one answers "what is
    * left once N columns have scrolled off the left edge". It is the primitive a horizontally scrolled view needs —
    * without it, code outside this file would have to reach for `String.substring`, which counts UTF-16 code units
    * rather than columns and which this library forbids everywhere else.
    *
    * A cluster that straddles the boundary is dropped whole rather than half-drawn. For example a two-column CJK
    * character whose first column falls before `skipWidth` and whose second falls after is discarded: half of a wide
    * glyph is not a character a terminal can print. One consequence worth knowing before relying on the result:
    * `of(substringByWidth(t, n)) + of(dropByWidth(t, n))` can be one column less than `of(t)`, and exactly one, because
    * at most one cluster can straddle a single boundary.
    *
    * A `skipWidth` of zero or less returns `text` itself; a `skipWidth` at or beyond the text's width returns `""`.
    */
  def dropByWidth(text: String, skipWidth: Int): String = dropByWidth(text, skipWidth, WidthMode.Narrow)

  /** [[dropByWidth]] measuring East Asian Ambiguous codepoints according to `mode` — the suffix that would remain once
    * `skipWidth` columns of a terminal running that locale's width rules have scrolled off.
    */
  def dropByWidth(text: String, skipWidth: Int, mode: WidthMode): String =
    if skipWidth <= 0 then text
    else if isPrintableAscii(text) then
      // one column per character on this path, so the column index is also the char index; and, as in [[of]], the
      // fast path is valid under either mode because nothing printable-ASCII is East Asian Ambiguous
      text.substring(
        math.min(text.length, skipWidth)
      ) // scalafix:ok DisableSyntax; ASCII fast path: exactly one column per char, so columns and chars coincide
    else
      val clusters  = graphemeClusters(text)
      val remainder = StringBuilder()
      var skipped   = 0
      while clusters.hasNext do
        val cluster = clusters.next()
        if skipped >= skipWidth then remainder ++= cluster
        else skipped += clusterWidth(cluster, mode)
      remainder.result()

  /** `text` with every grapheme cluster whose base code point is a control character (C0, DEL, C1) removed.
    *
    * The input-side counterpart of the `width > 0` guard in [[Buffer.setString]], for the editable-text models that
    * write cells directly instead of going through it. A control occupies zero terminal columns but one `Cell`, so a
    * TAB or an `ESC` stored in a text model is drawn as a one-column cell and emitted raw: the terminal moves its
    * cursor somewhere the frame diff does not expect, and every later cell in that row lands in the wrong column.
    * Combining marks are *not* controls and are deliberately kept — they are zero-width but they belong to the cluster
    * they follow.
    */
  def withoutControls(text: String): String =
    if isPrintableAscii(text) then text
    else
      val kept     = StringBuilder()
      val clusters = graphemeClusters(text)
      while clusters.hasNext do
        val cluster = clusters.next()
        if !isControlCluster(cluster) then kept ++= cluster
      kept.result()

  /** Whether `codePoint` has East Asian Width `W` (Wide) or `F` (Fullwidth), i.e. occupies two columns in every
    * terminal, whatever locale it runs under.
    */
  def isWideCodePoint(codePoint: Int): Boolean = inRanges(WidthTable.WideRanges, codePoint)

  /** Whether `codePoint` has East Asian Width `A` (Ambiguous): one column under a Western locale, two under a Chinese,
    * Japanese or Korean one. See [[WidthMode]].
    *
    * Worth knowing before reaching for it: the box-drawing characters this library's own borders are made of are all
    * ambiguous, which is why a border drawn under a CJK locale can come out twice as wide as it was measured.
    */
  def isAmbiguousCodePoint(codePoint: Int): Boolean = inRanges(WidthTable.AmbiguousRanges, codePoint)

  /** Whether `codePoint` falls inside one of `table`'s inclusive ranges.
    *
    * `table` is a flat sorted array of (start, end) pairs, so a plain binary search over it lands either exactly on a
    * boundary — the codepoint is a range's own start or end, so it is inside — or between two entries.
    */
  private def inRanges(table: Array[Int], codePoint: Int): Boolean =
    val position = Arrays.binarySearch(table, codePoint)
    if position >= 0 then true
    else
      // insertion point is the index of the first element > codePoint; the codepoint is inside a range
      // when that index is odd (start already passed, end not yet reached)
      val insertion = -position - 1
      insertion % 2 == 1

  /** Splits `text` into the units that occupy terminal cells: a base codepoint plus its combining marks, variation
    * selectors, ZWJ-joined continuations, or regional-indicator partner. This is the only sanctioned way to step
    * through text one cell-unit at a time (wrapping, cursor movement, truncation).
    *
    * '''The returned iterator is stateful, single-use, and owned by the calling thread.''' It carries a cursor into
    * `text` that advances on every `next()`, so it can be walked exactly once: to traverse the same text again, call
    * this again — the call is cheap and `text` itself is immutable. Never store one across frames and never share one
    * between threads; two threads pulling from the same instance interleave their cursor updates and each receives a
    * mixture of the other's clusters, with no error to say so. Unlike this iterator, the object around it is pure.
    */
  def graphemeClusters(text: String): Iterator[String] = graphemeClusters(Iterator.single(text))

  /** The same segmentation over fragments, with bounded lookahead across even split UTF-16 pairs. A viewport need not
    * concatenate or visit an invisible tail just to finish the cluster at a span boundary.
    */
  private[core] def graphemeClusters(parts: Iterator[String]): Iterator[String] =
    val chars   = parts.flatMap(_.iterator).buffered
    val points  = new Iterator[Int]:
      def hasNext: Boolean = chars.hasNext
      def next(): Int      =
        val first = chars.next()
        if Character.isHighSurrogate(first) && chars.hasNext && Character.isLowSurrogate(chars.head) then
          Character.toCodePoint(first, chars.next())
        else first.toInt
    val pending = points.buffered
    new Iterator[String]:
      def hasNext: Boolean = pending.hasNext

      def next(): String =
        val first   = pending.next()
        val cluster = java.lang.StringBuilder()
        cluster.appendCodePoint(first)
        if isRegionalIndicator(first) && pending.hasNext && isRegionalIndicator(pending.head) then
          cluster.appendCodePoint(pending.next())
        else
          var done = false
          while !done && pending.hasNext do
            val cp = pending.head
            // Consume the joiner before peeking across the next fragment. Only emoji join; a stray ZWJ still
            // attaches as a format continuation, but cannot pull an ordinary following character into this cell.
            if cp == ZeroWidthJoiner then
              cluster.appendCodePoint(pending.next())
              if isEmojiCapable(first) && pending.hasNext && isEmojiCapable(pending.head) then
                cluster.appendCodePoint(pending.next())
            else if isClusterContinuation(cp) then cluster.appendCodePoint(pending.next())
            else done = true
        cluster.toString

  /** [[of]] specialised for a string that is already exactly one grapheme cluster.
    *
    * [[Buffer]] calls this on every cell it writes, so unlike [[of]] it must never build a cluster iterator or a
    * substring: the single-printable-ASCII early-out covers the overwhelmingly common cell, and everything else goes
    * straight to the allocation-free cluster measurement. Behaviour on multi-cluster input is unspecified — it measures
    * the first cluster's rules against the whole string. Callers that hold arbitrary text must use [[of]].
    */
  private[core] def ofCluster(cluster: String): Int =
    // 0x20-0x7E is exactly one column and covers no combining mark, no East Asian W/F, and no control character
    if cluster.length == 1 && cluster.charAt(0) >= FirstPrintableAscii && cluster.charAt(0) <= LastPrintableAscii then 1
    else clusterWidth(cluster, WidthMode.Narrow)

  private def isControlCluster(cluster: String): Boolean =
    cluster.nonEmpty && Character.isISOControl(cluster.codePointAt(0))

  private[core] def clusterWidth(cluster: String, mode: WidthMode): Int =
    if cluster.isEmpty then 0
    else
      val base = cluster.codePointAt(0)
      // a flag is exactly two regional indicators; an RI followed by anything else (a combining mark) is not one
      if isRegionalIndicator(base) && secondCodePointIsRegionalIndicator(cluster, base) then 2
      // a variation selector only speaks for a base that has both presentations; after a letter or an ideograph
      // it is inert decoration, and a cluster that is nothing but selectors has no base at all
      else if isEmojiCapable(base) then presentationWidth(cluster, base, mode)
      // zero-width is asked first, so a codepoint that is both zero-width and ambiguous — the combining marks in the
      // ambiguous set are — keeps its zero rather than falling through to `baseWidth`
      else if isZeroWidth(base) || Character.isISOControl(base) then 0
      else baseWidth(base, mode)

  /** Whether `cluster` contains U+FE0F, the variation selector that asks for a character's emoji presentation.
    *
    * This says nothing about how wide the cluster is — [[ofCluster]] answers that — only that the terminal was asked to
    * draw a colourful two-column glyph where a one-column text glyph also exists. Terminals disagree about whether they
    * honour that request, and a backend uses this to decide whether the column to the right of such a cluster is one it
    * had better repaint rather than trust.
    */
  def hasEmojiPresentationSelector(cluster: String): Boolean =
    containsCodePoint(cluster, EmojiPresentationSelector)

  /** The width of an emoji-capable `base` once its variation selector has had its say: VS15 forces text presentation
    * (one column), VS16 forces emoji presentation (two). With neither, the base's own width stands.
    *
    * "The base's own width" is where `mode` gets a say. Many of the characters that reach here are symbols rather than
    * emoji — the box-drawing set, the arrows, `±` and `×` are all category `Sm` or `So` and so are treated as
    * emoji-capable — and those are exactly the East Asian Ambiguous characters a CJK-locale terminal draws two columns
    * wide. An explicit variation selector still outranks the locale, because a selector is the author saying which
    * presentation they meant.
    */
  private def presentationWidth(cluster: String, base: Int, mode: WidthMode): Int =
    if containsCodePoint(cluster, TextPresentationSelector) then 1
    else if containsCodePoint(cluster, EmojiPresentationSelector) then 2
    else baseWidth(base, mode)

  /** The width of `cp` on its own, before any selector, flag or combining rule has had a say: East Asian Wide and
    * Fullwidth are two columns, and East Asian Ambiguous is two only when the caller asked for [[WidthMode.Wide]]. This
    * is the single owner of the ambiguous-width policy, so a caller changing that knob changes one place.
    */
  private def baseWidth(cp: Int, mode: WidthMode): Int =
    if isWideCodePoint(cp) then 2
    else if mode == WidthMode.Wide && isAmbiguousCodePoint(cp) then 2
    else 1

  /** Whether `cp` is a character that has both a text and an emoji presentation, so that a variation selector or a ZWJ
    * after it means something.
    *
    * An approximation of the Unicode `Emoji` property rather than a generated table: the emoji planes, the symbol
    * categories that hold the dingbats and pictographs, the keycap bases, and the handful of legacy characters (`(c)`,
    * `(r)`, `!!`, `!?`, and the two CJK wave marks) that carry the property outside them. Letters, digits read as text,
    * and CJK ideographs are deliberately excluded — a selector after them changes nothing a terminal draws, so it must
    * not change the column count either.
    */
  private def isEmojiCapable(cp: Int): Boolean =
    if cp >= 0x1f000 && cp <= 0x1faff then true
    else if isKeycapBase(cp) then true
    else if cp == 0x00a9 || cp == 0x00ae || cp == 0x203c || cp == 0x2049 || cp == 0x3030 || cp == 0x303d then true
    else if cp < 0x2000 then false
    else
      val category = Character.getType(cp)
      category == Character.MATH_SYMBOL.toInt || category == Character.OTHER_SYMBOL.toInt

  /** The digits, `#`, and `*` that a keycap sequence (`1` VS16 U+20E3) is built on. */
  private def isKeycapBase(cp: Int): Boolean =
    (cp >= 0x30 && cp <= 0x39) || cp == 0x23 || cp == 0x2a

  private def secondCodePointIsRegionalIndicator(cluster: String, base: Int): Boolean =
    val next = Character.charCount(base)
    next < cluster.length && isRegionalIndicator(cluster.codePointAt(next))

  private def isClusterContinuation(cp: Int): Boolean =
    isZeroWidth(cp) || isEmojiModifier(cp)

  private def isZeroWidth(cp: Int): Boolean =
    val category = Character.getType(cp)
    category == Character.NON_SPACING_MARK.toInt ||
    category == Character.ENCLOSING_MARK.toInt ||
    category == Character.FORMAT.toInt ||
    isConjoiningJamoContinuation(cp)

  /** Medial vowels and trailing consonants of decomposed Hangul syllables render inside the leading consonant's two
    * columns rather than adding their own.
    */
  private def isConjoiningJamoContinuation(cp: Int): Boolean =
    (cp >= 0x1160 && cp <= 0x11ff) || (cp >= 0xd7b0 && cp <= 0xd7ff)

  private def isEmojiModifier(cp: Int): Boolean =
    cp >= 0x1f3fb && cp <= 0x1f3ff

  private def isRegionalIndicator(cp: Int): Boolean =
    cp >= 0x1f1e6 && cp <= 0x1f1ff

  private def containsCodePoint(text: String, target: Int): Boolean =
    var index = 0
    var found = false
    while !found && index < text.length do
      val cp = text.codePointAt(index)
      found = cp == target
      index += Character.charCount(cp)
    found

  /** The first and last code point of printable US-ASCII: space and tilde. Every fast path in this file tests this same
    * closed range, and [[AsciiSymbols]] is sized and indexed from it.
    */
  private val FirstPrintableAscii = 0x20
  private val LastPrintableAscii  = 0x7e

  private val ZeroWidthJoiner           = 0x200d
  private val TextPresentationSelector  = 0xfe0e
  private val EmojiPresentationSelector = 0xfe0f

  /** Interned one-char strings for the printable-ASCII range, indexed by `codePoint - FirstPrintableAscii`. */
  private val AsciiSymbols: Array[String] =
    Array.tabulate(LastPrintableAscii - FirstPrintableAscii + 1)(offset =>
      (FirstPrintableAscii + offset).toChar.toString
    )
