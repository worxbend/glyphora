package io.worxbend.tui.core

/** Multi-line styled text.
  *
  * `alignment` is the horizontal placement this text asks for, and `None`, the default, means "whatever the widget
  * drawing me was told to use" — which is what every text did before this field existed. A [[Line]] that carries an
  * alignment of its own still overrules it for that one row, so the resolution order a renderer follows is: the line's
  * alignment first, then the text's, then the widget's own argument.
  *
  * `style` is this text's base style, the outermost of the three layers that decide how a character is drawn — the
  * text's style, then the [[Line]]'s, then the [[Span]]'s, each laid over the last with [[Style.patch]] so the more
  * specific layer wins wherever it speaks and the outer one shows through wherever it stays silent. The widget's own
  * style sits underneath all three. The default, [[Style.Default]], sets nothing, so a text that does not use it
  * renders exactly as it did before the layer existed.
  */
final case class Text(lines: Seq[Line], alignment: Option[Alignment] = None, style: Style = Style.Default):
  def height: Int = lines.size

  def width: Int = widthIn(WidthMode.Narrow)

  /** This block's width under a given East Asian Ambiguous policy — the widest of its lines' [[Line.widthIn]], and zero
    * when there are no lines. See [[WidthMode]]; `widthIn(WidthMode.Narrow)` is exactly [[width]].
    *
    * The widest line under one policy need not be the widest under the other: a long ASCII line can lose to a shorter
    * one full of box drawing once the ambiguous characters count double.
    */
  def widthIn(mode: WidthMode): Int = if lines.isEmpty then 0 else lines.map(_.widthIn(mode)).max

  /** This text with `style` as its base layer, replacing whatever base it had. The lines and their spans are untouched,
    * so each keeps its own style and still wins over this one wherever the two disagree.
    *
    * This is an O(1) field replacement, unlike [[under]] and [[patchStyle]], which walk every span of every line.
    */
  def withStyle(style: Style): Text = copy(style = style)

  /** This text's base style put through `transform`, e.g. `text.withStyleOf(_.dim)`. The lines are untouched. */
  def withStyleOf(transform: Style => Style): Text = copy(style = transform(style))

  /** This text placed the given way, overriding the widget that draws it. A line that carries an alignment of its own
    * still wins over this for that one row; the lines are otherwise untouched.
    */
  def aligned(alignment: Alignment): Text = copy(alignment = Some(alignment))

  /** Hands the placement decision back to the widget drawing this text — the state a text starts in. */
  def inheritAlignment: Text = copy(alignment = None)

  /** Pins this block to the left edge even inside a centred or right-aligned widget. */
  def leftAligned: Text = aligned(Alignment.Left)

  /** Centres every row of this block in the columns it is drawn in, whatever the widget was told to do. */
  def centered: Text = aligned(Alignment.Center)

  /** Pins this block to the right edge, which is what a column of numbers wants. */
  def rightAligned: Text = aligned(Alignment.Right)

  /** Every line's [[Line.plainText]] joined with `\n`, with no trailing newline added.
    *
    * The inverse of [[Text.raw]] up to styling: `Text.raw(s).plainText == s` for any `s` that contains no `\r`, because
    * `raw` splits on `\n` keeping empty trailing lines and this joins them back the same way. As with
    * [[Line.plainText]], the result is for logging, clipboard payloads and test assertions — not for measuring, which
    * is what [[width]] and [[height]] are for.
    */
  def plainText: String = lines.map(_.plainText).mkString("\n")

  /** Every span of every line put through `transform` — see [[Line.styled]] for why this is a fold over the spans
    * rather than a style stored on the text.
    */
  def styled(transform: Style => Style): Text = copy(lines = lines.map(_.styled(transform)))

  /** `base` laid underneath every span's own style, so a span that chose a setting keeps it — see [[Span.under]]. */
  def under(base: Style): Text = copy(lines = lines.map(_.under(base)))

  /** `style` layered on top of every span of every line — see [[Span.patchStyle]]. */
  def patchStyle(style: Style): Text = copy(lines = lines.map(_.patchStyle(style)))

  /** This text with `line` added below its existing lines. The receiver is unchanged; a new value is returned. */
  def appended(line: Line): Text = copy(lines = lines :+ line)

  /** This text with every line of `other` added below its own, in order. */
  def appendedAll(other: Text): Text = copy(lines = lines ++ other.lines)

  /** This text with `span` added to the right-hand end of its last line.
    *
    * When the text has no lines at all there is no last line to extend, so one is started holding just `span` and the
    * result is a one-line text. That empty case is the whole reason this method exists: code that accumulates a text
    * span by span otherwise has to test `lines.isEmpty` itself at every call site, and the two branches are easy to get
    * the wrong way round.
    *
    * A text whose last line is present but has no spans is *not* the empty case — `span` becomes that line's only span,
    * and the line count does not change.
    */
  def appendedToLast(span: Span): Text =
    if lines.isEmpty then copy(lines = Seq(Line(Seq(span))))
    else copy(lines = lines.init :+ lines.last.appended(span))

  /** [[appended]] as an operator: `header + body + footer` stacks three rows into one block. */
  infix def +(line: Line): Text = appended(line)

  /** [[appendedAll]] as an operator: every row of `other` below this text's own. */
  infix def ++(other: Text): Text = appendedAll(other)

object Text:
  /** A text with no lines: zero rows, zero columns, and the identity for [[Text.appendedAll]], so a fold that
    * accumulates lines has somewhere to start.
    */
  val Empty: Text = Text(Seq.empty)

  /** Splits `content` into rows the way every text-shaped widget in this toolkit does: on `\n` alone, with a limit of
    * `-1` so trailing empty lines survive — "a\n" is two rows, the second blank, not one.
    *
    * The split is on `\n` alone: the caller owns newline normalisation. CRLF (`\r\n`) input therefore leaves a carriage
    * return at the end of every line, and a `\r` occupies zero terminal columns but one [[Cell]], so the rest of that
    * row renders one column off. Route text of unknown provenance — a file, an HTTP body, a Windows-produced source —
    * through [[CharWidth.withoutControls]] first, or strip the `\r` yourself.
    */
  def splitLines(content: String): Seq[String] = content.split("\n", -1).toSeq

  /** Splits `content` with [[Text.splitLines]]; each resulting row carries `style`. */
  def styled(content: String, style: Style): Text =
    Text(splitLines(content).map(line => Line.styled(line, style)))

  /** [[styled]] with [[Style.Default]]: splits `content` on newlines, each resulting line unstyled. */
  def raw(content: String): Text =
    styled(content, Style.Default)

  /** Reads terminal output — text carrying SGR colour and attribute sequences and OSC 8 hyperlinks — into the styled
    * text it describes, so the coloured output of a compiler, a test runner or `git` can be shown inside a widget.
    *
    * `Text.fromAnsi("\u001b[1;31mred\u001b[0m plain")` is a one-line text of two spans: `red` in bold red, then
    * ` plain` in [[Style.Default]]. Every escape sequence that is not a style — cursor movement, screen clearing, other
    * OSC strings — is dropped whole, as are control characters other than `\n`, so CRLF output splits into clean rows.
    * Plain text with no escape in it comes out exactly as [[raw]] reads it. The full list of what is recognised, and
    * the chunk-at-a-time form for a stream that is still arriving, is [[AnsiParser]]; this is that parser run once over
    * the whole string.
    */
  def fromAnsi(content: String): Text =
    val parser = AnsiParser()
    Text(parser.feed(content) :+ parser.finish())

  /** A block of already-built lines sitting on `style` as their shared base layer — the companion spelling of
    * [[Text.withStyle]], for a caller that has the lines and the base style in hand at the same moment.
    *
    * It does not touch the lines: each keeps its own style, and each span keeps its own, and both still overrule this
    * base wherever they disagree with it. That is the difference from the single-string [[styled]] above, which has no
    * lines yet and puts the style straight onto the spans it makes.
    */
  def styled(lines: Seq[Line], style: Style): Text = Text(lines, None, style)

  /** `Text.of("header", Line.of("body ", highlighted))` — one row per argument, built from a mixture of plain strings
    * and already-built [[Line]]s.
    *
    * Unlike [[Text.raw]] and [[Text.styled]], this does **not** split anything on newlines: each argument is exactly
    * one row, and a `\n` inside one of the strings would end up in a row as a character that occupies no column,
    * pushing the rest of that row one column out of place. Pass such a string to `Text.raw` instead, or split it
    * yourself.
    *
    * The name is `of` rather than another `apply` overload for the same reason as [[Line.of]]: a varargs `apply` would
    * erase into a collision with the published `apply(Seq[Line])`.
    */
  def of(parts: (String | Line)*): Text =
    Text(parts.map {
      case content: String => Line.raw(content)
      case line: Line      => line
    })
