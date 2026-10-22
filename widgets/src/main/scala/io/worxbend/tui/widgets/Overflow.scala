package io.worxbend.tui.widgets

/** What a widget does with content that does not fit the width it is given — used by [[Paragraph]] for its text and by
  * [[Notice]] for its message.
  *
  * This is a named choice rather than a `wrap: Boolean` because the two spellings read the same at a call site
  * (`Paragraph(text, wrap = false)` and `Paragraph(text, Overflow.Clip)` are the same picture) but only one of them
  * survives a reader who has never seen the parameter list: `true` says nothing about which behaviour is on.
  *
  * Three of the four cases wrap, and they differ in one decision only: what happens to the blanks at the head of a row.
  * That decision has no right answer, because it depends on whether the caller's indentation carries meaning. An
  * indented bullet list wrapped at width 12 shows all three:
  *
  * {{{
  * source text:   "  * a long bullet"
  *
  * Wrap           "  * a long"     the line's own indent is kept, the blank the break landed on is dropped
  *                "bullet"
  *
  * WrapTrimmed    "* a long"       every row starts flush against the left edge, indent included
  *                "bullet"
  *
  * WrapPreserved  "  * a long"     nothing is ever dropped, so the break blank re-indents the next row
  *                " bullet"
  * }}}
  *
  * Pick [Wrap](Overflow.html#Wrap-0) for a document whose indentation is structure and whose breaks are incidental —
  * which is nearly always. Pick [WrapTrimmed](Overflow.html#WrapTrimmed-0) for prose pasted in with an indent nobody
  * wants on screen. Pick [WrapPreserved](Overflow.html#WrapPreserved-0) when the text is a rendering of something
  * whitespace-significant and dropping a blank would misrepresent it.
  */
enum Overflow:

  /** Draw one row per line of content and cut anything past the right edge. The choice for a status line, a table cell,
    * a badge — anywhere a growing block of text would push the rest of the layout around.
    */
  case Clip

  /** Break content onto further rows so nothing is lost. The break lands between words: a word that does not fit moves
    * to the next row whole, and the blanks that sat at the break are dropped rather than becoming a leading indent.
    * Blanks at the start of a line are content and are kept, so indentation survives. Only a word longer than the whole
    * width is broken inside, and then between grapheme clusters, so a wide character or emoji is never split.
    *
    * The widget then needs more rows than it has lines of content, which is what
    * [[io.worxbend.tui.core.Measured#heightAt Measured.heightAt]] reports.
    */
  case Wrap

  /** Like [Wrap](Overflow.html#Wrap-0), but the line's own leading blanks are dropped too, so every row — the first one
    * included — starts flush against the left edge of the area. The choice for prose that arrived with an indentation
    * the layout, not the text, is supposed to decide.
    */
  case WrapTrimmed

  /** Like [Wrap](Overflow.html#Wrap-0), but no blank is ever dropped: the run of blanks a break landed on is carried
    * onto the next row and indents it. The choice for text where a blank is data rather than spacing.
    *
    * A run of blanks that would not leave room for the word after it on a row of its own is still dropped — keeping it
    * would push the word off the row entirely and cost a row that shows nothing.
    */
  case WrapPreserved

  /** Whether this mode breaks content onto further rows. True for every case but [Clip](Overflow.html#Clip-0).
    *
    * Callers that only need to know "does the height depend on the width" ask this instead of matching every wrapping
    * case, so adding a fourth wrapping mode does not reopen every one of those matches.
    *
    * Consistent with `wrapBlanks` by construction: every case but [Clip](Overflow.html#Clip-0) wraps, and every case
    * but [Clip](Overflow.html#Clip-0) names a blank rule. Deliberately not defined as `wrapBlanks.isDefined` — `wraps`
    * is asked once per frame from several render paths, and routing it through an `Option` would allocate there for no
    * correctness gain.
    */
  def wraps: Boolean = this != Overflow.Clip

  /** The rule this mode wraps blanks by, or `None` when it clips and there is no wrapping to rule on. */
  private[widgets] def wrapBlanks: Option[WrapBlanks] =
    this match
      case Overflow.Clip          => None
      case Overflow.Wrap          => Some(WrapBlanks.KeepIndent)
      case Overflow.WrapTrimmed   => Some(WrapBlanks.DropAll)
      case Overflow.WrapPreserved => Some(WrapBlanks.KeepAll)

/** What one wrapping mode does with the blanks at the head of a row — the single decision [[Overflow]]'s three wrapping
  * cases differ by, named on its own so the wrapping walk takes one argument rather than re-deriving the mode.
  */
private[widgets] enum WrapBlanks:

  /** Keep the blanks the caller wrote at the start of the source line, drop the ones a break landed on —
    * [[Overflow.Wrap]]. Kept blanks are counted against the row's width like any other column, so an indent too wide to
    * leave the first word room on the row is dropped rather than pushing the row past the width.
    */
  case KeepIndent

  /** Drop every blank that would begin a row — [[Overflow.WrapTrimmed]]. */
  case DropAll

  /** Keep every blank, so a break indents the row after it — [[Overflow.WrapPreserved]]. */
  case KeepAll
