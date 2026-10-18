package io.worxbend.tui.widgets.lexers

import java.util.Locale

import io.worxbend.tui.core.{Line, Span, Style}
import io.worxbend.tui.widgets.SyntaxTheme

/** Scanning helpers shared by the built-in lexers. Pure functions over the line passed in. */
private[widgets] object LexerSupport:

  def prevChar(line: String, i: Int): Char = if i > 0 then line.charAt(i - 1) else ' '

  def isIdentStart(c: Char): Boolean = c.isLetter || c == '_'
  def isIdentChar(c: Char): Boolean  = c.isLetterOrDigit || c == '_'

  def scanWhile(line: String, start: Int, pred: Char => Boolean): Int =
    var i = start
    while i < line.length && pred(line.charAt(i)) do i += 1
    i

  /** Consumes a string literal opened by `delim` at `start`, honouring backslash escapes; returns the index just past
    * the closing delimiter (or end of line if unterminated).
    */
  def scanString(line: String, start: Int, delim: Char): Int =
    var i     = start + 1
    var close = -1
    while close < 0 && i < line.length do
      val c = line.charAt(i)
      if c == '\\' then i += 2 // the escaped char can never close the literal
      else if c == delim then close = i + 1
      else i += 1
    if close < 0 then line.length else math.min(close, line.length)

  /** The end of a number literal starting at `start`. */
  def scanNumber(line: String, start: Int): Int =
    scanWhile(line, start, ch => ch.isDigit || ch == '.' || ch == 'x' || ch == 'e' || ch == '_')

  /** A whole line in one style — the shape of a diff line or a comment line. */
  def whole(line: String, style: Style): Line = if line.isEmpty then Line(Seq.empty) else Line(Seq(Span(line, style)))

/** Accumulates the spans of one line: plain characters coalesce into one default-styled span until a token is emitted.
  * One instance per highlighted line, never shared.
  */
private[widgets] final class SpanSink(theme: SyntaxTheme):
  private val spans = Seq.newBuilder[Span]
  private val plain = StringBuilder()

  def plainChar(c: Char): Unit      = plain += c
  def plainText(text: String): Unit = plain ++= text

  def emit(content: String, style: Style): Unit =
    if content.nonEmpty then
      flush()
      spans += Span(content, style)

  def result(): Line =
    flush()
    Line(spans.result())

  private def flush(): Unit =
    if plain.nonEmpty then
      spans += Span(plain.result(), theme.default)
      plain.clear()

/** What a keyword-and-literal language looks like to [[KeywordLexer]]. */
private[widgets] final case class KeywordSpec(
    keywords: Set[String],
    lineComment: Option[String],
    stringDelims: Set[Char],
    shellVariables: Boolean = false,
    caseInsensitive: Boolean = false,
)

/** The scanner most languages share: line comments, single-line strings, numbers at token boundaries, keywords, `name(`
  * call sites and (for shells) `$variables`.
  */
private[widgets] final class KeywordLexer(val name: String, val aliases: Set[String], spec: KeywordSpec)
    extends io.worxbend.tui.widgets.Lexer:
  import LexerSupport.*

  def highlightLine(line: String, theme: SyntaxTheme): Line =
    val sink = SpanSink(theme)
    var i    = 0
    val n    = line.length

    /** Emits `line.slice(i, end)` with `style` and advances the scan past it — the shape every multi-char token takes.
      */
    def consume(end: Int, style: Style): Unit =
      sink.emit(line.slice(i, end), style)
      i = end

    while i < n do
      val c  = line.charAt(i)
      // bound the scan index to a local: a lambda capturing `i` directly would box it into a
      // scala.runtime.IntRef for the whole loop, and this runs per character, per line, per frame
      val at = i
      if spec.lineComment.exists(marker => line.startsWith(marker, at)) then consume(n, theme.comment)
      else if spec.stringDelims.contains(c) then consume(scanString(line, i, c), theme.string)
      else if c == '$' && spec.shellVariables && i + 1 < n && isIdentChar(line.charAt(i + 1)) then
        consume(scanWhile(line, i + 1, isIdentChar), theme.variable)
      else if c.isDigit && !isIdentChar(prevChar(line, i)) then consume(scanNumber(line, i), theme.number)
      else if isIdentStart(c) then
        val end    = scanWhile(line, i, isIdentChar)
        val word   = line.slice(i, end)
        val isCall = end < n && line.charAt(end) == '('
        if isKeyword(word) then sink.emit(word, theme.keyword)
        else if isCall then sink.emit(word, theme.function)
        else sink.plainText(word)
        i = end
      else
        sink.plainChar(c)
        i += 1
    sink.result()

  private def isKeyword(word: String): Boolean =
    if spec.caseInsensitive then spec.keywords.contains(word.toLowerCase(Locale.ROOT))
    else spec.keywords.contains(word)
