package io.worxbend.tui.widgets

import java.util.Locale

import io.worxbend.tui.core.{Color, Line, Style, Text}
import io.worxbend.tui.widgets.lexers.{DiffLexer, KeywordLexer, KeywordSpec, MarkdownLexer, YamlLexer}

/** Styles for each token kind a [[Lexer]] emits; override entries to re-theme.
  *
  * `inserted` and `deleted` are the added and removed lines of a patch, and only [[Lexer.Diff]] emits them.
  */
final case class SyntaxTheme(
    keyword: Style = Style.Default.withFg(Color.Magenta),
    string: Style = Style.Default.withFg(Color.Green),
    number: Style = Style.Default.withFg(Color.Yellow),
    comment: Style = Style.Default.dim.italic,
    function: Style = Style.Default.withFg(Color.Cyan),
    variable: Style = Style.Default.withFg(Color.Blue),
    default: Style = Style.Default,
    inserted: Style = Style.Default.withFg(Color.Green),
    deleted: Style = Style.Default.withFg(Color.Red),
)

/** Turns one line of source into styled [[io.worxbend.tui.core.Span]]s — the open extension point behind
  * [[SyntaxHighlighter]], a Markdown fence and `TextAreaOptions.lexer`.
  *
  * Highlighting is line-oriented, with a [[LexState]] carried from the end of one line to the start of the next: the
  * three-argument [[highlightLine]] takes the state the previous line ended in and answers the state this one ends in,
  * so a block comment, a triple-quoted string or a YAML block scalar that spans lines is highlighted on every line it
  * covers. [[highlight]] threads it through a whole text, and so do a Markdown fence and a `TextArea`. The two-argument
  * form lexes one line on its own, from [[LexState.Initial]]; a lexer that keeps no state implements only that one, and
  * the stateful form defaults to it. The spans of the returned [[Line]] must concatenate back to `line` exactly — an
  * editor maps them onto its grapheme clusters, and a lexer that drops or adds characters has its styling ignored
  * there.
  *
  * Implementations are immutable values and safe to share between threads; [[aliases]] are the lower-case info-string
  * names (```` ```yaml ````) a Markdown fence resolves through [[Lexer.among]].
  */
trait Lexer:

  /** A short lower-case name, for diagnostics and documentation. */
  def name: String

  /** Lower-case info-string names this lexer answers to. */
  def aliases: Set[String]

  /** Highlights a single line of code on its own, as if it were the first line of a document. */
  def highlightLine(line: String, theme: SyntaxTheme): Line

  /** Highlights one line that begins in `state` — what the line before it ended in — and answers the line with the
    * state it ends in. The default keeps no state: it lexes the line alone and always answers [[LexState.Initial]].
    */
  def highlightLine(line: String, theme: SyntaxTheme, state: LexState): (Line, LexState) =
    val _ = state // a stateless lexer starts every line afresh, whatever the line above ended in
    (highlightLine(line, theme), LexState.Initial)

  /** Highlights multi-line `code`, one [[Line]] per source line, carrying the [[LexState]] from line to line. */
  def highlight(code: String, theme: SyntaxTheme): Text =
    var state = LexState.Initial
    Text(Text.splitLines(code).toIndexedSeq.map { line =>
      val (highlighted, next) = highlightLine(line, theme, state)
      state = next
      highlighted
    })

/** Where a [[Lexer]] stands at the end of a line: inside nothing ([[LexState.Initial]]) or inside a construct that
  * continues onto the next line. An immutable value the lexer that produced it reads back; nothing else interprets it.
  *
  * @param open
  *   what the line ended inside, in the lexer's own words — the built-in lexers use the delimiter that closes it (the
  *   end of a block comment, a triple quote, a quote) or a mode name (`yaml-block`, `yaml-flow`); empty for nothing
  * @param indent
  *   an indentation the construct depends on — the column a YAML block scalar's key sits at
  * @param depth
  *   a nesting depth — how many YAML flow collections are open
  */
final case class LexState(open: String = "", indent: Int = 0, depth: Int = 0):

  /** Whether the line ended inside nothing. */
  def isInitial: Boolean = open.isEmpty && depth == 0

object LexState:

  /** The state at the top of a document, and after any line that closes everything it opened. */
  val Initial: LexState = LexState()

object Lexer:

  val Scala: Lexer = KeywordLexer(
    "scala",
    Set("scala", "sc", "sbt"),
    KeywordSpec(
      keywords = words(
        "def val var class object trait extends with given using import package if else match case for while do " +
          "yield new type sealed final override private protected implicit lazy return true false null this super " +
          "enum then abstract inline throw try catch finally"
      ),
      lineComment = Some("//"),
      stringDelims = Set('"'),
      blockComment = Some(("/*", "*/")),
      multilineStrings = Seq("\"\"\""),
    ),
  )

  val Json: Lexer = KeywordLexer(
    "json",
    Set("json"),
    KeywordSpec(keywords = words("true false null"), lineComment = None, stringDelims = Set('"')),
  )

  val Bash: Lexer = KeywordLexer(
    "bash",
    Set("bash", "sh", "shell", "zsh", "console"),
    KeywordSpec(
      keywords =
        words("if then fi else elif for while do done case esac function in return export local echo cd source"),
      lineComment = Some("#"),
      stringDelims = Set('"', '\''),
      shellVariables = true,
    ),
  )

  /** Strings and numbers only — the fallback [[Language.of]] answers for a name no lexer claims. */
  val Generic: Lexer =
    KeywordLexer(
      "generic",
      Set.empty,
      KeywordSpec(keywords = Set.empty, lineComment = None, stringDelims = Set('"', '\'')),
    )

  val Python: Lexer = KeywordLexer(
    "python",
    Set("python", "py", "python3"),
    KeywordSpec(
      keywords = words(
        "def class if elif else for while return import from as with try except finally raise lambda yield pass " +
          "break continue in is not and or None True False global nonlocal async await assert del match case"
      ),
      lineComment = Some("#"),
      stringDelims = Set('"', '\''),
      multilineStrings = Seq("\"\"\"", "'''"),
    ),
  )

  val TypeScript: Lexer = KeywordLexer(
    "typescript",
    Set("typescript", "ts", "tsx", "javascript", "js", "jsx", "mjs"),
    KeywordSpec(
      keywords = words(
        "const let var function return if else for while do switch case break continue new class extends " +
          "implements interface type enum import export from as default async await try catch finally throw typeof " +
          "instanceof in of true false null undefined this super public private protected readonly static void any " +
          "number string boolean never unknown keyof"
      ),
      lineComment = Some("//"),
      stringDelims = Set('"', '\'', '`'),
      blockComment = Some(("/*", "*/")),
      multilineStrings = Seq("`"),
    ),
  )

  /** SQL keywords match in any case — `select` and `SELECT` are the same word to the database. */
  val Sql: Lexer = KeywordLexer(
    "sql",
    Set("sql", "postgres", "postgresql", "psql", "mysql", "sqlite"),
    KeywordSpec(
      keywords = words(
        "select from where insert into values update set delete create table drop alter index join left right " +
          "inner outer full cross on group by order having limit offset as and or not null is in like between " +
          "distinct union all primary key foreign references default exists case when then else end asc desc with " +
          "returning true false"
      ),
      lineComment = Some("--"),
      stringDelims = Set('\''),
      caseInsensitive = true,
      blockComment = Some(("/*", "*/")),
    ),
  )

  val Yaml: Lexer = YamlLexer

  val Markdown: Lexer = MarkdownLexer

  /** Unified diffs: `+` lines in `inserted`, `-` lines in `deleted`, hunk headers as `function`, file headers as
    * `keyword`.
    */
  val Diff: Lexer = DiffLexer

  /** Every lexer that ships with the library and claims a name — [[Generic]] is the fallback, not a member. */
  val builtIn: Seq[Lexer] = Seq(Scala, Json, Bash, Python, TypeScript, Sql, Yaml, Markdown, Diff)

  /** The built-in lexer answering to `name` (a fence info string; only its first word counts), or `None`. */
  def forName(name: String): Option[Lexer] = among(builtIn, name)

  /** The first of `lexers` answering to `name`, matched case-insensitively on the info string's first word. */
  def among(lexers: Seq[Lexer], name: String): Option[Lexer] =
    val key = infoName(name)
    lexers.find(_.aliases.contains(key))

  /** The lookup key of an info string: its first word, lower-cased through `Locale.ROOT` — see core.KeyEvent.keyCodeFor
    * for why matching against a lowercased user string must not depend on the platform's default locale.
    */
  private[widgets] def infoName(info: String): String =
    info.trim.takeWhile(c => !c.isWhitespace).toLowerCase(Locale.ROOT)

  private def words(list: String): Set[String] = list.split(' ').iterator.filter(_.nonEmpty).toSet

/** The lexer type under its 0.19 name. `Language` was a closed enum; it is now the open [[Lexer]] trait, and its four
  * former cases are the [[Lexer]] values of the same names.
  */
type Language = Lexer

/** The four lexers `Language` used to enumerate, plus the info-string resolver. */
object Language:
  val Scala: Lexer   = Lexer.Scala
  val Json: Lexer    = Lexer.Json
  val Bash: Lexer    = Lexer.Bash
  val Generic: Lexer = Lexer.Generic

  /** Resolves a fence info-string / name (case-insensitive) to a built-in [[Lexer]]; unknown names map to [[Generic]].
    */
  def of(name: String): Lexer = Lexer.forName(name).getOrElse(Generic)

/** A pragmatic, dependency-free syntax highlighter over any [[Lexer]]. Backend-agnostic and unit-tested against the
  * produced spans.
  */
object SyntaxHighlighter:

  /** Highlights multi-line `code` as a [[Text]] (one [[Line]] per source line). */
  def highlight(code: String, lexer: Lexer, theme: SyntaxTheme = SyntaxTheme()): Text =
    lexer.highlight(code, theme)

  /** Highlights a single line of `code`. */
  def highlightLine(line: String, lexer: Lexer, theme: SyntaxTheme = SyntaxTheme()): Line =
    lexer.highlightLine(line, theme)
