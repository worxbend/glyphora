package io.worxbend.tui.core

/** Text that must not be shown, paired with the glyph to show in its place — a password, a token, an account number.
  *
  * This hides characters on a screen. It is not encryption and not redaction: the original text is still held in
  * memory, in this very value, and anything that can read the object can read [[content]]. It exists so that the
  * *display* of a secret is correct and done in one place.
  *
  * "Correct" is the whole point. The obvious hand-rolled version, `"*" * secret.length`, counts UTF-16 code units, so a
  * secret containing an emoji or an accented letter written as a base plus a combining mark produces more mask
  * characters than there are characters to hide — which leaks the shape of the secret and misaligns the row it is drawn
  * in. [[value]] emits exactly one mask per grapheme cluster, the unit a terminal actually draws.
  *
  * Use it wherever a secret is merely displayed: a paragraph, a table cell, a list row, a log line.
  *
  * @param content
  *   the text being hidden. Never rendered by anything on this type, `toString` included.
  * @param maskChar
  *   the glyph shown in place of each grapheme cluster of `content`. Only its first grapheme cluster is used, so a
  *   multi-character argument cannot silently widen every masked position; an argument with no cluster at all — the
  *   empty string — or one whose first cluster occupies no column — a lone combining mark, a zero-width space or
  *   joiner, a control character — would draw a zero-width secret, so [[Masked.DefaultMaskChar]] stands in for it.
  */
final case class Masked(content: String, maskChar: String):

  /** The one glyph every masked position is drawn with: the first grapheme cluster of [[maskChar]], so a
    * multi-character argument cannot silently widen every position, and [[Masked.DefaultMaskChar]] when the argument
    * holds no cluster at all or its first cluster is zero-width — either mask would otherwise draw a zero-width secret.
    */
  private lazy val glyph: String =
    CharWidth
      .graphemeClusters(maskChar)
      .nextOption()
      .filter(cluster => CharWidth.ofCluster(cluster) > 0)
      .getOrElse(Masked.DefaultMaskChar)

  /** The display form: one [[maskChar]] per grapheme cluster of [[content]], so an accented letter or a
    * multi-code-point emoji is hidden by exactly one mask glyph rather than by two or four.
    */
  lazy val value: String = glyph * CharWidth.clusterCount(content)

  /** Terminal columns the masked form occupies — which is the mask's width, not the secret's. */
  def width: Int = CharWidth.of(value)

  /** The masked form as an unstyled [[Span]]. */
  def toSpan: Span = Span.raw(value)

  /** The masked form as a one-row [[Line]]. */
  def toLine: Line = Line.raw(value)

  /** The masked form as a [[Text]]. */
  def toText: Text = Text.raw(value)

  /** The masked form as a [[Span]] drawn in `style`. */
  def styled(style: Style): Span = Span(value, style)

  /** The mask, never the secret.
    *
    * `toString` is what ends up in log lines and failed-assertion messages, which is precisely where a secret must not
    * appear, so the generated case-class `toString` — which would print `content` verbatim — is deliberately replaced.
    * Read [[content]] explicitly when the plaintext is genuinely wanted.
    */
  override def toString: String = value

object Masked:

  /** The glyph used when no other is given, and the stand-in for a mask argument that holds no grapheme cluster. */
  val DefaultMaskChar: String = "•"

  /** `Masked(secret)` — hidden behind [[DefaultMaskChar]]. */
  def apply(content: String): Masked = Masked(content, DefaultMaskChar)
