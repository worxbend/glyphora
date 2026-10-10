package io.worxbend.tui.core

/** One horizontal line of styled text, a sequence of differently-styled [[Span]]s.
  *
  * `alignment` is this line's *own* horizontal placement. `None`, the default, means "whatever the widget drawing me
  * was told to use" — that is what every line did before this field existed. Setting it overrides the widget for this
  * one row, so a single block of text can hold a left-aligned heading above right-aligned numbers without being split
  * into several widgets stacked by the layout solver.
  *
  * `style` is the line's *own* base style, the middle layer of the three-layer cascade a renderer resolves for every
  * character: the widget's style first, then this line's style on top of it, then the [[Span]]'s style on top of that,
  * each layered with [[Style.patch]] so the more specific layer wins wherever it speaks and the outer layer shows
  * through wherever it stays silent. Before this field existed, "this whole row is dim" had to be repeated on every
  * span, and a line a widget had built from spans could not be tinted by the widget drawing it without rebuilding every
  * span. The default, [[Style.Default]], sets nothing, so a line that does not use it renders exactly as it did.
  */
final case class Line(spans: Seq[Span], alignment: Option[Alignment] = None, style: Style = Style.Default):
  def width: Int = widthIn(WidthMode.Narrow)

  /** This row's width under a given East Asian Ambiguous policy. Span boundaries never split a grapheme; see
    * [[WidthMode]] and [[styledGraphemes]]. `widthIn(WidthMode.Narrow)` is exactly [[width]].
    */
  def widthIn(mode: WidthMode): Int =
    if spans.lengthIs == 1 then spans.head.widthIn(mode)
    else if spans.forall(span => CharWidth.isPrintableAscii(span.content)) then spans.iterator.map(_.widthIn(mode)).sum
    else CharWidth.graphemeClusters(spans.iterator.map(_.content)).map(CharWidth.clusterWidth(_, mode)).sum

  /** This line with `style` as its base layer, replacing whatever base it had. The spans are untouched, so each one
    * keeps its own style and still wins over this one wherever the two disagree.
    *
    * This is an O(1) field replacement, unlike [[under]] and [[patchStyle]], which walk every span. Use it for "this
    * whole row is dim" and reach for the span-walking methods only when the spans themselves must change.
    */
  def withStyle(style: Style): Line = copy(style = style)

  /** This line's base style put through `transform`, e.g. `line.withStyleOf(_.bold)`. The spans are untouched. */
  def withStyleOf(transform: Style => Style): Line = copy(style = transform(style))

  /** This line placed the given way, overriding the widget that draws it. The spans are untouched. */
  def aligned(alignment: Alignment): Line = copy(alignment = Some(alignment))

  /** Pins this row to the left edge even inside a centred or right-aligned widget. */
  def leftAligned: Line = aligned(Alignment.Left)

  /** Centres this row in the columns it is drawn in, whatever the widget was told to do. */
  def centered: Line = aligned(Alignment.Center)

  /** Pins this row to the right edge, which is what a column of numbers wants. */
  def rightAligned: Line = aligned(Alignment.Right)

  /** The line's characters with every [[Style]] dropped — the string a user would copy out of the terminal.
    *
    * The spans are concatenated in order with nothing inserted between them. `plainText.length` counts UTF-16 code
    * units, which says nothing about how many terminal columns the line occupies: ask [[width]] for that. Intended for
    * logging, clipboard payloads and test assertions, never for layout arithmetic.
    */
  def plainText: String = spans.map(_.content).mkString

  /** Every span's [[Style]] put through `transform`, the spans and their content unchanged.
    *
    * This reaches into the *spans*, not into the line's own [[style]] — see [[withStyleOf]] for the base layer. The
    * calls compose exactly as two `Style` calls would: `line.styled(_.bold).styled(_.withFg(c))` gives every span a
    * style that is both bold and coloured. The line's [[alignment]] and [[style]] are carried through unchanged.
    */
  def styled(transform: Style => Style): Line = copy(spans = spans.map(_.styled(transform)))

  /** `base` laid underneath every span's own style — see [[Span.under]]. The way a theme colour reaches a line built by
    * a helper that already set a few attributes of its own, without overruling them.
    */
  def under(base: Style): Line = copy(spans = spans.map(_.under(base)))

  /** `style` layered on top of every span's own — see [[Span.patchStyle]]. The argument wins where it speaks, and each
    * span keeps whatever the argument says nothing about, so the spans stay different from one another.
    */
  def patchStyle(style: Style): Line = copy(spans = spans.map(_.patchStyle(style)))

  /** This line with `span` added after its existing spans, i.e. further to the right.
    *
    * A `Line` is an immutable value, so nothing is pushed anywhere: the receiver is unchanged and a new line is
    * returned. The name follows the Scala collections vocabulary (`appended`, not `push`) for exactly that reason.
    */
  def appended(span: Span): Line = copy(spans = spans :+ span)

  /** This line with every span of `other` added after its own, in order — the two rows laid end to end. */
  def appendedAll(other: Line): Line = copy(spans = spans ++ other.spans)

  /** [[appended]] as an operator, for building a row inline: `Span.raw("ok") + separator + count`. */
  infix def +(span: Span): Line = appended(span)

  /** [[appendedAll]] as an operator: the two rows laid end to end, every span keeping its own style.
    *
    * There is deliberately no operator for stacking two lines vertically. ratatui gives `+` both jobs and picks the
    * axis from the argument's type, so the same symbol means "further right" or "further down" depending on what is on
    * its right-hand side — which is not something a reader can see at a glance. Stacking is spelled out instead, as
    * `Text(Seq(first, second))` or `Text.Empty.appended(first).appended(second)`.
    */
  infix def ++(other: Line): Line = appendedAll(other)

  /** The whole row as one stream of cell-units, the spans walked in order — see [[Span.styledGraphemes]] for what a
    * cluster is and for the iterator's ownership rules, which this inherits unchanged.
    *
    * Each cluster arrives carrying the style it will really be drawn in: `base`, then this line's own [[style]] on top,
    * then the span's style on top of that — the same three-layer cascade a renderer applies. A cluster crossing spans
    * belongs to the span containing its first code unit (its base character), including when a surrogate pair is split.
    * Continuation spans cannot restyle it. Fragments are read lazily, with only the lookahead needed to finish a
    * cluster.
    */
  def styledGraphemes(base: Style): Iterator[StyledGrapheme] = graphemes(base.patch(style))

  /** Raw span styles for reflow: the new rows retain this line's base style and must resolve it only at painting. */
  private[tui] def spanGraphemes: Iterator[StyledGrapheme] = graphemes(Style.Default)

  private def graphemes(lineBase: Style): Iterator[StyledGrapheme] =
    if spans.lengthIs == 1 then spans.head.styledGraphemes(lineBase)
    else
      val owners   = spans.iterator
      var resolved = lineBase
      var end      = 0
      var offset   = 0
      CharWidth.graphemeClusters(spans.iterator.map(_.content)).map { cluster =>
        // UTF-16 offsets identify the source style only; CharWidth owns segmentation and all column arithmetic.
        while end <= offset && owners.hasNext do
          val owner = owners.next()
          end += owner.content.length
          resolved = lineBase.patch(owner.style)
        offset += cluster.length
        StyledGrapheme(cluster, resolved)
      }

object Line:
  /** A line with no spans: zero columns wide, and the identity for [[Line.appendedAll]], so a fold that accumulates
    * spans has somewhere to start.
    */
  val Empty: Line = Line(Seq.empty)

  def raw(content: String): Line = Line(Seq(Span.raw(content)))

  def styled(content: String, style: Style): Line = Line(Seq(Span(content, style)))

  /** A line of already-built spans sitting on `style` as their shared base layer — the companion spelling of
    * [[Line.withStyle]], for a caller that has the spans and the base style in hand at the same moment.
    *
    * It does not touch the spans: each keeps its own style and still overrules this base wherever the two disagree.
    * This is the difference from the single-string [[styled]] above, which has no spans yet and puts the style straight
    * onto the one span it makes.
    */
  def styled(spans: Seq[Span], style: Style): Line = Line(spans, None, style)

  /** `Line("hi")` — an unstyled single-span line.
    *
    * An overload of the case class's own `apply`, so the obvious spelling works: without it `Line("hi")` does not
    * compile at all, because the generated `apply` takes a `Seq[Span]` and a bare `String` is not one. It is exactly
    * [[raw]] under a shorter name; `raw` stays for call sites that pass the function itself (`titles.map(Line.raw)`).
    */
  def apply(content: String): Line = raw(content)

  /** `Line.of("Name: ", "Remy".styled(_.bold))` — one row built from a mixture of plain strings and already-styled
    * [[Span]]s, in the order given.
    *
    * A plain `String` becomes an unstyled span; a `Span` is taken as it is. This is for the common shape of a status
    * row, where most of the text is a fixed label and only a word or two carries a colour, and where the alternative is
    * `Line(Seq(Span.raw("Name: "), someSpan))` — the same row with two extra wrappers to read past.
    *
    * The name is `of` rather than another `apply` overload on purpose: after erasure a varargs `apply` would collide
    * with the published `apply(Seq[Span])`, and the two would be ambiguous at the call site rather than at this
    * definition, which is the worse place to find out.
    *
    * The resulting line carries no alignment and no base style, as any other freshly built line does; add them with
    * [[Line.centered]] and [[Line.withStyle]].
    */
  def of(parts: (String | Span)*): Line =
    Line(parts.map {
      case content: String => Span.raw(content)
      case span: Span      => span
    })
