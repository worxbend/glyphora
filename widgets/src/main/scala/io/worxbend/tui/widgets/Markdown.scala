package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Alignment, Buffer, CharWidth, Color, Line, Measured, Rect, Span, Style, Text, Widget}

/** Styles for each Markdown construct; override individual entries to theme the view. */
final case class MarkdownTheme(
    heading1: Style = Style.Default.bold.withFg(Color.Cyan),
    heading2: Style = Style.Default.bold,
    heading3: Style = Style.Default.underline,
    strong: Style = Style.Default.bold,
    emphasis: Style = Style.Default.italic,
    code: Style = Style.Default.withFg(Color.Yellow),
    quote: Style = Style.Default.dim.italic,
    bullet: Style = Style.Default.withFg(Color.Cyan),
    link: Style = Style.Default.withFg(Color.Blue).underline,
    syntax: SyntaxTheme = SyntaxTheme(),
    tableHeader: Style = Style.Default.bold,
    tableBorder: Style = Style.Default.dim,
    taskDone: Style = Style.Default.withFg(Color.Green),
)

/** What a GFM table does with a cell wider than the column it was given once the table has been shrunk to the area. */
enum TableOverflow:
  /** The cell wraps onto further rows of the same table row, at word boundaries where it has them. */
  case Wrap

  /** The cell is cut to the column and ends in `…`. */
  case Truncate

/** Behaviour and glyph knobs for [[Markdown]]; `MarkdownOptions()` is exactly the document the bare constructor draws.
  *
  * @param tableOverflow
  *   what a too-wide table cell does — see [[TableOverflow]]
  * @param lexers
  *   the lexers a fenced code block's info string is resolved against, first match wins; a fence no lexer claims
  *   renders verbatim in the `code` style. Append your own [[Lexer]] to [[Lexer.builtIn]] to teach fences a language.
  * @param listIndent
  *   columns of indentation per nesting level of a list
  * @param bullets
  *   the unordered-list marker per nesting level, cycling when a list nests deeper than the sequence is long
  * @param taskDone
  *   the glyph a checked task item (`- [x]`) draws in place of its bullet
  * @param taskOpen
  *   the glyph an unchecked task item (`- [ ]`) draws in place of its bullet
  */
final case class MarkdownOptions(
    tableOverflow: TableOverflow = TableOverflow.Wrap,
    lexers: Seq[Lexer] = Lexer.builtIn,
    listIndent: Int = 2,
    bullets: Seq[String] = Seq("•", "◦", "▪"),
    taskDone: String = "☑",
    taskOpen: String = "☐",
)

/** Renders a pragmatic Markdown subset: `#`/`##`/`###`+ headings, `-`/`*`/`+` bullets and `1.` numbered items nested by
  * indentation, `- [ ]`/`- [x]` task items, GFM tables, `>` blockquotes, fenced code blocks, and inline `**strong**` /
  * `*emphasis*` / `` `code` ``.
  *
  * Inline `[label](url)` links render underlined with an OSC 8 target. Deliberately excluded: images and raw HTML —
  * this is a document *viewer* for help screens, READMEs and LLM output, not a rendering-complete engine. Prose always
  * wraps at the area width (cluster-safe) — there is no clipping mode, because a document that silently lost the
  * right-hand half of its sentences would not be a document viewer. A table is laid out to the area width: columns are
  * measured through `CharWidth` (so CJK and emoji cells align), the widest shrink first when the table does not fit,
  * and a cell wider than its column wraps or truncates per [[MarkdownOptions.tableOverflow]]. Code blocks render
  * verbatim, except that a fence whose info string names a lexer in [[MarkdownOptions.lexers]] (` ```yaml `) is
  * coloured through it.
  *
  * For text that is still arriving — an LLM's reply, token by token — render a [[MarkdownStream]] instead, which closes
  * the syntax a half-written document leaves open.
  */
final case class Markdown(
    source: String,
    theme: MarkdownTheme = MarkdownTheme(),
    options: MarkdownOptions = MarkdownOptions(),
) extends Widget
    with Measured:

  def render(area: Rect, buffer: Buffer): Unit =
    paragraphAt(area.width).render(area, buffer)

  /** The rows the rendered document occupies at `width`, wrapping included — measured off the very [[Paragraph]] that
    * `render` draws, so parsing and wrapping can only ever be counted the same way they are painted.
    */
  override def heightAt(width: Int): Option[Int] = paragraphAt(width).heightAt(width)

  private lazy val blocks: Vector[MarkdownBlock] = MarkdownParser.blocks(source, theme, options)

  private lazy val widthDependent: Boolean = blocks.exists:
    case _: MarkdownBlock.Table | _: MarkdownBlock.Item => true
    case _                                              => false

  // A document without tables or list items lays out the same at every width, so it keeps one paragraph; one with them
  // keeps the latest width's, the same one-entry memo `Paragraph` keeps for its own height.
  private var laidOut = Option.empty[(Int, Paragraph)]

  private def paragraphAt(width: Int): Paragraph = synchronized {
    val key = if widthDependent then width else -1
    laidOut match
      case Some((cachedWidth, paragraph)) if cachedWidth == key => paragraph
      case _                                                    =>
        val paragraph = Paragraph(MarkdownParser.layout(blocks, width, theme, options), overflow = Overflow.Wrap)
        laidOut = Some((key, paragraph))
        paragraph
  }

/** A parsed document: prose lines are final; tables and list items are laid out once the width is known — an item so
  * that its wrapped rows can hang under its text, `lead` being the indentation and markers they hang under.
  */
private[widgets] enum MarkdownBlock:
  case Prose(line: Line)
  case Item(lead: Seq[Span], body: Seq[Span])
  case Table(header: Seq[Line], alignments: Seq[Alignment], rows: Seq[Seq[Line]])

private[widgets] object MarkdownParser:

  /** The whole document as [[Text]] at `width` — tables laid out to it, prose left for the paragraph to wrap. */
  def parse(
      source: String,
      theme: MarkdownTheme,
      options: MarkdownOptions = MarkdownOptions(),
      width: Int = Int.MaxValue,
  ): Text =
    layout(blocks(source, theme, options), width, theme, options)

  def layout(blocks: Seq[MarkdownBlock], width: Int, theme: MarkdownTheme, options: MarkdownOptions): Text =
    Text(blocks.flatMap {
      case MarkdownBlock.Prose(line)                     => Seq(line)
      case MarkdownBlock.Item(lead, body)                => hanging(lead, body, width)
      case MarkdownBlock.Table(header, alignments, rows) =>
        MarkdownTable.lines(header, alignments, rows, width, theme, options.tableOverflow)
    })

  /** A list item wrapped to `width` with a hanging indent: the first row starts with the markers, every further row
    * with as many blank columns, so wrapped text lines up under the item's first word. An item whose markers leave no
    * room at all is left whole for the paragraph to wrap.
    */
  private def hanging(lead: Seq[Span], body: Seq[Span], width: Int): Seq[Line] =
    val leadWidth = lead.iterator.map(span => CharWidth.of(span.content)).sum
    val room      = width - leadWidth
    if width == Int.MaxValue || room <= 0 then Seq(Line(lead ++ body))
    else
      val rows = Paragraph.wrapLine(Line(body), room, WrapBlanks.DropAll)
      rows.zipWithIndex.map { (row, index) =>
        Line((if index == 0 then lead else Seq(Span.raw(" " * leadWidth))) ++ row.spans)
      }

  def blocks(source: String, theme: MarkdownTheme, options: MarkdownOptions): Vector[MarkdownBlock] =
    val raw   = Text.splitLines(source).toIndexedSeq
    val out   = Vector.newBuilder[MarkdownBlock]
    // Some(None) is an open fence no lexer claimed; Some(Some(lexer)) one that is highlighted
    var fence = Option.empty[Option[Lexer]]
    var lists = List.empty[Int]  // indents of the open list levels, innermost first
    var lexed = LexState.Initial // where the fence's lexer stands, carried from one fenced line to the next
    var index = 0
    while index < raw.size do
      val line = raw(index)
      if line.trim.startsWith("```") then
        fence = if fence.isDefined then None else Some(Lexer.among(options.lexers, line.trim.drop(3)))
        lexed = LexState.Initial
        lists = Nil
        out += MarkdownBlock.Prose(Line(Seq.empty))
        index += 1
      else
        fence match
          case Some(Some(lexer)) =>
            val result = lexer.highlightLine(line, theme.syntax, lexed)
            lexed = result.next
            out += MarkdownBlock.Prose(result.line)
            index += 1
          case Some(None)        =>
            out += MarkdownBlock.Prose(Line.styled(line, theme.code))
            index += 1
          case None              =>
            tableAt(raw, index, theme) match
              case Some((table, consumed)) =>
                out += table
                lists = Nil
                index += consumed
              case None                    =>
                val (rendered, open) = blockLine(line, lists, theme, options)
                out += rendered
                lists = open
                index += 1
    out.result()

  private def blockLine(
      raw: String,
      lists: List[Int],
      theme: MarkdownTheme,
      options: MarkdownOptions,
  ): (MarkdownBlock, List[Int]) =
    def prose(line: Line): MarkdownBlock = MarkdownBlock.Prose(line)
    val trimmed                          = raw.trim
    if trimmed.isEmpty then (prose(Line(Seq.empty)), lists) // a blank line between items keeps the list open
    else if trimmed.startsWith("###") then (prose(Line.styled(dropMarker(trimmed, '#'), theme.heading3)), Nil)
    else if trimmed.startsWith("##") then (prose(Line.styled(dropMarker(trimmed, '#'), theme.heading2)), Nil)
    else if trimmed.startsWith("#") then (prose(Line.styled(dropMarker(trimmed, '#'), theme.heading1)), Nil)
    else if trimmed.startsWith("> ") then (prose(Line.styled("▎ " + trimmed.drop(2), theme.quote)), Nil)
    else
      val indent = indentOf(raw)
      listItem(trimmed) match
        case Some(item) =>
          // pop the levels deeper than this item; an indent no open level has opens a new one
          val outer = lists.dropWhile(_ > indent)
          val open  = if outer.headOption.contains(indent) then outer else indent :: outer
          (itemLine(item, open.size - 1, theme, options), open)
        case None       => (prose(Line(inlineSpans(raw, theme))), if indent == 0 then Nil else lists)

  /** One list item, before nesting: its ordinal (`None` for a bullet), task state, and the text after the markers. */
  private final case class ListItem(ordinal: Option[String], task: Option[Boolean], rest: String)

  private def listItem(trimmed: String): Option[ListItem] =
    val digits                                = trimmed.takeWhile(_.isDigit)
    val marker: Option[(Option[String], Int)] =
      if trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") then Some((None, 2))
      else if digits.nonEmpty && trimmed.startsWith(digits + ". ") then Some((Some(digits + "."), digits.length + 2))
      else None
    marker.map { (ordinal, length) =>
      val body = trimmed.drop(length)
      val task =
        if body.startsWith("[ ] ") || body == "[ ]" then Some(false)
        else if body.startsWith("[x] ") || body.startsWith("[X] ") || body == "[x]" || body == "[X]" then Some(true)
        else None
      ListItem(ordinal, task, if task.isDefined then body.drop(4) else body)
    }

  private def itemLine(item: ListItem, level: Int, theme: MarkdownTheme, options: MarkdownOptions): MarkdownBlock =
    val indent  = " " * (math.max(0, options.listIndent) * level)
    val bullet  =
      if options.bullets.isEmpty then "•" else options.bullets(level % options.bullets.size)
    val markers = (item.ordinal, item.task) match
      case (Some(ordinal), Some(done)) => Seq(Span(ordinal + " ", theme.bullet), taskSpan(done, theme, options))
      case (Some(ordinal), None)       => Seq(Span(ordinal + " ", theme.bullet))
      case (None, Some(done))          => Seq(taskSpan(done, theme, options))
      case (None, None)                => Seq(Span(bullet + " ", theme.bullet))
    val lead    = if indent.isEmpty then markers else Span.raw(indent) +: markers
    MarkdownBlock.Item(lead, inlineSpans(item.rest, theme))

  private def taskSpan(done: Boolean, theme: MarkdownTheme, options: MarkdownOptions): Span =
    if done then Span(options.taskDone + " ", theme.taskDone) else Span(options.taskOpen + " ", theme.bullet)

  /** Leading indentation in columns, a tab counting as four. */
  private def indentOf(raw: String): Int =
    raw.iterator.takeWhile(c => c == ' ' || c == '\t').map(c => if c == '\t' then 4 else 1).sum

  private def dropMarker(text: String, marker: Char): String =
    text.dropWhile(_ == marker).stripLeading

  // ---- GFM tables ----

  private val AlignmentCell = """:?-+:?""".r

  /** A table whose header row is at `index`, with how many source lines it spans, or `None` when there is none. */
  private def tableAt(raw: IndexedSeq[String], index: Int, theme: MarkdownTheme): Option[(MarkdownBlock, Int)] =
    if index + 1 >= raw.size || !raw(index).contains('|') then None
    else
      val header     = cells(raw(index))
      val alignments = cells(raw(index + 1))
      val isTable    = alignments.size == header.size && alignments.forall(AlignmentCell.matches)
      if !isTable then None
      else
        val body    = raw.drop(index + 2).takeWhile(line => line.contains('|') && line.trim.nonEmpty)
        val columns = header.size
        def row(source: Seq[String]): Seq[Line] =
          source.padTo(columns, "").take(columns).map(cell => Line(inlineSpans(cell, theme)))
        val table = MarkdownBlock.Table(row(header), alignments.map(alignmentOf), body.map(line => row(cells(line))))
        Some((table, 2 + body.size))

  private def alignmentOf(cell: String): Alignment =
    if cell.startsWith(":") && cell.endsWith(":") && cell.length > 1 then Alignment.Center
    else if cell.endsWith(":") then Alignment.Right
    else Alignment.Left

  /** The cells of a table row: outer pipes are optional, `\|` is a literal pipe, every cell is trimmed. */
  private def cells(line: String): Seq[String] =
    val trimmed = line.trim
    val inner   = trimmed.stripPrefix("|")
    val body    = if inner.endsWith("|") && !inner.endsWith("\\|") then inner.dropRight(1) else inner
    val out     = Seq.newBuilder[String]
    val cell    = StringBuilder()
    var index   = 0
    while index < body.length do
      val c = body.charAt(index)
      if c == '\\' && index + 1 < body.length && body.charAt(index + 1) == '|' then
        cell += '|'
        index += 2
      else if c == '|' then
        out += cell.result().trim
        cell.clear()
        index += 1
      else
        cell += c
        index += 1
    out += cell.result().trim
    out.result()

  /** Inline scanner for `**strong**`, `*emphasis*`, and `` `code` ``; unclosed markers render literally. */
  private def inlineSpans(text: String, theme: MarkdownTheme): Seq[Span] =
    val spans = Seq.newBuilder[Span]
    val plain = StringBuilder()

    def flushPlain(): Unit =
      if plain.nonEmpty then
        spans += Span.raw(plain.result())
        plain.clear()

    var index = 0
    while index < text.length do
      styledRun(text, index, theme) match
        case Some((span, consumed)) =>
          flushPlain()
          spans += span
          index += consumed
        case None                   =>
          plain += text.charAt(index)
          index += 1
    flushPlain()
    spans.result()

  /** How many chars the complete styled run at `index` spans, or `None` when there is none — the parser's own answer,
    * which is what [[MarkdownSanitizer]] needs to tell an unclosed marker from one the parser already accepts.
    */
  def runLengthAt(text: String, index: Int): Option[Int] = styledRun(text, index, MarkdownTheme()).map(_._2)

  /** A styled run starting exactly at `index`, with how many chars it consumed, or `None`. */
  private def styledRun(text: String, index: Int, theme: MarkdownTheme): Option[(Span, Int)] =
    linkRun(text, index, theme)
      .orElse(styled(text, index, "**", theme.strong))
      .orElse(styled(text, index, "*", theme.emphasis))
      .orElse(styled(text, index, "`", theme.code))

  /** `delimiter`-wrapped text at `index` as one span in `style` — the shared shape of the strong/emphasis/code runs.
    */
  private def styled(text: String, index: Int, delimiter: String, style: Style): Option[(Span, Int)] =
    delimited(text, index, delimiter).map((content, consumed) => (Span(content, style), consumed))

  /** `[label](url)`: the label renders link-styled with the OSC 8 target attached. */
  private def linkRun(text: String, index: Int, theme: MarkdownTheme): Option[(Span, Int)] =
    if text.charAt(index) != '[' then None
    else
      val close = text.indexOf("](", index + 1)
      val end   = if close < 0 then -1 else text.indexOf(')', close + 2)
      if close < 0 || end < 0 then None
      else
        val label = text.slice(index + 1, close)
        val url   = text.slice(close + 2, end)
        if label.isEmpty || url.isEmpty then None
        else Some((Span(label, theme.link.withLink(url)), end + 1 - index))

  /** Non-empty text between `marker` at `index` and its next occurrence. */
  private def delimited(text: String, index: Int, marker: String): Option[(String, Int)] =
    if !text.startsWith(marker, index) then None
    else
      val contentStart = index + marker.length
      val end          = text.indexOf(marker, contentStart)
      if end <= contentStart then None
      else Some((text.slice(contentStart, end), end + marker.length - index))
