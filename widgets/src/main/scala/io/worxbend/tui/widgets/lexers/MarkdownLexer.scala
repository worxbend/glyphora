package io.worxbend.tui.widgets.lexers

import io.worxbend.tui.core.Line
import io.worxbend.tui.widgets.{Lexer, SyntaxTheme}

/** Markdown *source*, for showing a `.md` file as code rather than rendering it (that is the `Markdown` widget's job):
  * headings as `keyword`, quotes and fence lines as `comment`, list and task markers as `function`, inline code as
  * `string`, `**strong**`/`*emphasis*` as `keyword`, and `[label](url)` links as `variable`.
  */
private[widgets] object MarkdownLexer extends Lexer:
  import LexerSupport.*

  val name: String         = "markdown"
  val aliases: Set[String] = Set("markdown", "md", "gfm")

  def highlightLine(line: String, theme: SyntaxTheme): Line =
    val sink   = SpanSink(theme)
    val indent = scanWhile(line, 0, c => c == ' ' || c == '\t')
    val body   = line.drop(indent)
    sink.plainText(line.take(indent))
    if body.startsWith("```") || body.startsWith("~~~") || body.startsWith(">") then sink.emit(body, theme.comment)
    else if body.startsWith("#") then sink.emit(body, theme.keyword)
    else
      val marker = listMarker(body)
      sink.emit(body.take(marker), theme.function)
      inlineTokens(body, marker, sink, theme)
    sink.result()

  /** The length of a leading list marker (`- `, `* `, `+ `, `12. `), including a task box (`[ ] `, `[x] `), or 0. */
  private def listMarker(body: String): Int =
    val digits = scanWhile(body, 0, _.isDigit)
    val bullet =
      if Seq("- ", "* ", "+ ").exists(body.startsWith) then 2
      else if digits > 0 && body.startsWith(". ", digits) then digits + 2
      else 0
    val task   = Seq("[ ] ", "[x] ", "[X] ").exists(box => body.startsWith(box, bullet))
    if bullet > 0 && task then bullet + 4 else bullet

  private def inlineTokens(body: String, start: Int, sink: SpanSink, theme: SyntaxTheme): Unit =
    val n = body.length
    var i = start
    while i < n do
      val c   = body.charAt(i)
      val end =
        if c == '`' then closing(body, i, "`")
        else if body.startsWith("**", i) then closing(body, i, "**")
        else if c == '*' then closing(body, i, "*")
        else if c == '[' then link(body, i)
        else -1
      if end > 0 then
        sink.emit(
          body.slice(i, end),
          if c == '`' then theme.string else if c == '[' then theme.variable else theme.keyword,
        )
        i = end
      else
        sink.plainChar(c)
        i += 1

  /** The index past the `marker` closing the one at `start`, or -1 when it is unclosed or empty. */
  private def closing(body: String, start: Int, marker: String): Int =
    val close = body.indexOf(marker, start + marker.length)
    if close <= start + marker.length then -1 else close + marker.length

  private def link(body: String, start: Int): Int =
    val middle = body.indexOf("](", start + 1)
    val close  = if middle < 0 then -1 else body.indexOf(')', middle + 2)
    if close < 0 then -1 else close + 1
