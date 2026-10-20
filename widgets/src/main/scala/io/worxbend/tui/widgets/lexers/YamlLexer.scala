package io.worxbend.tui.widgets.lexers

import java.util.Locale

import io.worxbend.tui.core.Line
import io.worxbend.tui.widgets.{LexedLine, LexState, Lexer, SyntaxTheme}

/** YAML, line by line: `key:` mapping keys as `variable`, quoted scalars as `string`, numbers, the literal words
  * (`true`, `null`, `~`, …) as `keyword`, `&anchor`/`*alias` as `function`, document markers and block-scalar
  * indicators as `keyword`, and `#` comments.
  *
  * Three constructs carry across lines through the [[LexState]]: a '''block scalar''' (`key: |` or `>`), whose lines —
  * every one indented deeper than its key, blank ones included — are one `string`, a `#` or a `:` in them included; a
  * '''quoted scalar''' whose closing quote is on a later line; and a '''flow collection''' (`{a: 1, b: [x, y]}`),
  * inside which keys, numbers, literals and strings are highlighted as they are outside it, across as many lines as it
  * spans.
  */
private[widgets] object YamlLexer extends Lexer:
  import LexerSupport.*

  val name: String         = "yaml"
  val aliases: Set[String] = Set("yaml", "yml")

  private val Literals = Set("true", "false", "null", "yes", "no", "on", "off", "~")

  private val Block = "yaml-block"
  private val Flow  = "yaml-flow"

  def highlightLine(line: String, theme: SyntaxTheme): Line = highlightLine(line, theme, LexState.Initial).line

  override def highlightLine(line: String, theme: SyntaxTheme, state: LexState): LexedLine =
    val sink   = SpanSink(theme)
    val indent = scanWhile(line, 0, _ == ' ')
    val next   = state.open match
      case Block if line.isBlank || indent > state.indent =>
        sink.plainText(line.take(indent))
        sink.emit(line.drop(indent), theme.string)
        state
      case "\"" | "'"                                     =>
        val quote = state.open.charAt(0)
        val close = closingQuote(line, 0, quote)
        if close < 0 then
          sink.emit(line, theme.string)
          state
        else
          sink.emit(line.take(close + 1), theme.string)
          value(line, close + 1, indent, state.depth, sink, theme)
      case Flow                                           => value(line, 0, indent, state.depth, sink, theme)
      case _                                              => plainLine(line, indent, sink, theme)
    LexedLine(sink.result(), next)

  /** A line that starts outside every multi-line construct. */
  private def plainLine(line: String, indent: Int, sink: SpanSink, theme: SyntaxTheme): LexState =
    val n = line.length
    var i = indent
    sink.plainText(line.take(indent))
    if indent == 0 && (line.startsWith("---") || line.startsWith("...")) && line.drop(3).isBlank then
      sink.emit(line, theme.keyword)
      LexState.Initial
    else
      // sequence markers, possibly nested: `- - item`
      while i < n && line.charAt(i) == '-' && (i + 1 == n || line.charAt(i + 1) == ' ') do
        val end = scanWhile(line, i + 1, _ == ' ')
        sink.plainText(line.slice(i, end))
        i = end
      keyEnd(line, i, inFlow = false) match
        case Some(colon) =>
          sink.emit(line.slice(i, colon), theme.variable)
          sink.plainChar(':')
          value(line, colon + 1, indent, 0, sink, theme)
        case None        => value(line, i, indent, 0, sink, theme)

  /** The index of the `:` closing a mapping key that starts at `start`, if the line has one there. Inside a flow
    * collection a key also ends before `,`, `]` or `}` can, so `[a, b: 1]` keys only `b`.
    */
  private def keyEnd(line: String, start: Int, inFlow: Boolean): Option[Int] =
    if start >= line.length || line.charAt(start) == '#' then None
    else
      val afterKey =
        if line.charAt(start) == '"' || line.charAt(start) == '\'' then scanString(line, start, line.charAt(start))
        else scanWhile(line, start, c => c != ':' && c != '#' && !(inFlow && isFlowPunctuation(c)))
      val isColon  = afterKey < line.length && line.charAt(afterKey) == ':'
      val isKey    = isColon && (afterKey + 1 == line.length || line.charAt(afterKey + 1) == ' ')
      Option.when(isKey && afterKey > start)(afterKey)

  private def isFlowPunctuation(c: Char): Boolean = c == ',' || c == '[' || c == ']' || c == '{' || c == '}'

  /** Lexes the rest of a line from `start` as a value, `depth` flow collections deep; answers the state it ends in.
    * `indent` is the line's own indentation, which a block scalar opened here remembers.
    */
  private def value(line: String, start: Int, indent: Int, depth: Int, sink: SpanSink, theme: SyntaxTheme): LexState =
    val n      = line.length
    var i      = start
    var nested = depth
    var state  = Option.empty[LexState]
    while i < n && state.isEmpty do
      val c = line.charAt(i)
      if c == ' ' then
        sink.plainChar(c)
        i += 1
      else if c == '#' && (i == 0 || prevChar(line, i) == ' ') then
        sink.emit(line.drop(i), theme.comment)
        i = n
      else if c == '{' || c == '[' then
        nested += 1
        sink.plainChar(c)
        i += 1
      else if (c == '}' || c == ']') && nested > 0 then
        nested -= 1
        sink.plainChar(c)
        i += 1
      else if c == ',' && nested > 0 then
        sink.plainChar(c)
        i += 1
      else if c == '"' || c == '\'' then
        val close = closingQuote(line, i + 1, c)
        if close < 0 then
          sink.emit(line.drop(i), theme.string)
          state = Some(LexState(c.toString, depth = nested))
          i = n
        else
          sink.emit(line.slice(i, close + 1), theme.string)
          i = close + 1
      else if (c == '&' || c == '*') && i + 1 < n && line.charAt(i + 1) != ' ' then
        val end = scanWhile(line, i, ch => ch != ' ' && !(nested > 0 && isFlowPunctuation(ch)))
        sink.emit(line.slice(i, end), theme.function)
        i = end
      else if nested == 0 && (c == '|' || c == '>') &&
        line.drop(i + 1).trim.forall(ch => ch == '-' || ch == '+' || ch.isDigit)
      then
        sink.emit(line.slice(i, n), theme.keyword)
        state = Some(LexState(Block, indent = indent))
        i = n
      else
        keyEnd(line, i, inFlow = nested > 0) match
          case Some(colon) if nested > 0 =>
            sink.emit(line.slice(i, colon), theme.variable)
            sink.plainChar(':')
            i = colon + 1
          case _                         =>
            val end    = scalarEnd(line, i, inFlow = nested > 0)
            val scalar = line.slice(i, end)
            val word   = scalar.stripTrailing
            val style  =
              if Literals.contains(word.toLowerCase(Locale.ROOT)) then Some(theme.keyword)
              else if word.toDoubleOption.isDefined then Some(theme.number)
              else None
            style match
              case Some(tokenStyle) =>
                sink.emit(word, tokenStyle)
                sink.plainText(scalar.drop(word.length))
              case None             => sink.plainText(scalar)
            i = math.max(end, i + 1)
    state.getOrElse(if nested > 0 then LexState(Flow, depth = nested) else LexState.Initial)

  /** A plain scalar runs to the end of the line or to a ` #` comment — and, inside a flow collection, to the `,`, `]`
    * or `}` that ends it.
    */
  private def scalarEnd(line: String, start: Int, inFlow: Boolean): Int =
    val comment = line.indexOf(" #", start)
    val limit   = if comment < 0 then line.length else comment + 1
    if !inFlow then limit else math.min(limit, scanWhile(line, start, c => !isFlowPunctuation(c)))

  /** The index of the quote closing a `quote`-quoted scalar, scanning from `from`, or `-1` when the line does not close
    * it. A double-quoted scalar escapes with a backslash; a single-quoted one by doubling the quote.
    */
  private def closingQuote(line: String, from: Int, quote: Char): Int =
    var i     = from
    var close = -1
    while close < 0 && i < line.length do
      val c = line.charAt(i)
      if quote == '"' && c == '\\' then i += 2
      else if c == quote && quote == '\'' && i + 1 < line.length && line.charAt(i + 1) == '\'' then i += 2
      else if c == quote then close = i
      else i += 1
    close
