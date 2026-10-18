package io.worxbend.tui.widgets.lexers

import java.util.Locale

import io.worxbend.tui.core.Line
import io.worxbend.tui.widgets.{Lexer, SyntaxTheme}

/** YAML, one line at a time: `key:` mapping keys as `variable`, quoted scalars as `string`, numbers, the literal words
  * (`true`, `null`, `~`, …) as `keyword`, `&anchor`/`*alias` as `function`, document markers and block-scalar
  * indicators as `keyword`, and `#` comments. Flow collections (`{a: 1}`) and multi-line scalars fall back to plain
  * text.
  */
private[widgets] object YamlLexer extends Lexer:
  import LexerSupport.*

  val name: String         = "yaml"
  val aliases: Set[String] = Set("yaml", "yml")

  private val Literals = Set("true", "false", "null", "yes", "no", "on", "off", "~")

  def highlightLine(line: String, theme: SyntaxTheme): Line =
    val sink = SpanSink(theme)
    val n    = line.length
    var i    = scanWhile(line, 0, _ == ' ')
    sink.plainText(line.take(i))
    if i == 0 && (line.startsWith("---") || line.startsWith("...")) && line.drop(3).isBlank then
      sink.emit(line, theme.keyword)
    else
      // sequence markers, possibly nested: `- - item`
      while i < n && line.charAt(i) == '-' && (i + 1 == n || line.charAt(i + 1) == ' ') do
        val end = scanWhile(line, i + 1, _ == ' ')
        sink.plainText(line.slice(i, end))
        i = end
      keyEnd(line, i) match
        case Some(colon) =>
          sink.emit(line.slice(i, colon), theme.variable)
          sink.plainChar(':')
          value(line, colon + 1, sink, theme)
        case None        => value(line, i, sink, theme)
    sink.result()

  /** The index of the `:` closing a mapping key that starts at `start`, if the line has one there. */
  private def keyEnd(line: String, start: Int): Option[Int] =
    if start >= line.length || line.charAt(start) == '#' then None
    else
      val afterKey =
        if line.charAt(start) == '"' || line.charAt(start) == '\'' then scanString(line, start, line.charAt(start))
        else scanWhile(line, start, c => c != ':' && c != '#')
      val isColon  = afterKey < line.length && line.charAt(afterKey) == ':'
      val isKey    = isColon && (afterKey + 1 == line.length || line.charAt(afterKey + 1) == ' ')
      Option.when(isKey && afterKey > start)(afterKey)

  private def value(line: String, start: Int, sink: SpanSink, theme: SyntaxTheme): Unit =
    val n = line.length
    var i = start
    while i < n do
      val c = line.charAt(i)
      if c == ' ' then
        sink.plainChar(c)
        i += 1
      else if c == '#' && prevChar(line, i) == ' ' then
        sink.emit(line.drop(i), theme.comment)
        i = n
      else if c == '"' || c == '\'' then
        val end = scanString(line, i, c)
        sink.emit(line.slice(i, end), theme.string)
        i = end
      else if (c == '&' || c == '*') && i + 1 < n && line.charAt(i + 1) != ' ' then
        val end = scanWhile(line, i, _ != ' ')
        sink.emit(line.slice(i, end), theme.function)
        i = end
      else if (c == '|' || c == '>') && line.drop(i + 1).trim.forall(ch => ch == '-' || ch == '+' || ch.isDigit) then
        sink.emit(line.slice(i, n), theme.keyword)
        i = n
      else
        val end    = scalarEnd(line, i)
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
        i = end

  /** A plain scalar runs to the end of the line or to a ` #` comment. */
  private def scalarEnd(line: String, start: Int): Int =
    val comment = line.indexOf(" #", start)
    if comment < 0 then line.length else comment + 1
