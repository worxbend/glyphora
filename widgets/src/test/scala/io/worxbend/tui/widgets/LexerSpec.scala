package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Line, Span}
import io.worxbend.tui.testsupport.BufferAssertions.rendered
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

final class LexerSpec extends AnyFunSuite:

  private val theme = SyntaxTheme()

  /** A user-defined lexer: shouts every line in the keyword style. */
  private object Shout extends Lexer:
    val name: String                                          = "shout"
    val aliases: Set[String]                                  = Set("shout", "loud")
    def highlightLine(line: String, theme: SyntaxTheme): Line = Line(Seq(Span(line, theme.keyword)))

  test("a user-defined Lexer plugs into SyntaxHighlighter"):
    val text = SyntaxHighlighter.highlight("a\nb", Shout, theme)
    assert(text.lines.map(_.spans) == Seq(Seq(Span("a", theme.keyword)), Seq(Span("b", theme.keyword))))

  test("the built-in Language values are Lexers and keep their names"):
    val lexers: Seq[Lexer] = Seq(Language.Scala, Language.Json, Language.Bash, Language.Generic)
    assert(lexers.map(_.name) == Seq("scala", "json", "bash", "generic"))

  test("Lexer.forName resolves the built-in lexers by alias and reports unknown names as None"):
    assert(Lexer.forName("YAML").contains(Lexer.Yaml))
    assert(Lexer.forName("yml").contains(Lexer.Yaml))
    assert(Lexer.forName("sql").contains(Lexer.Sql))
    assert(Lexer.forName("md").contains(Lexer.Markdown))
    assert(Lexer.forName("diff").contains(Lexer.Diff))
    assert(Lexer.forName("patch").contains(Lexer.Diff))
    assert(Lexer.forName("ts").contains(Lexer.TypeScript))
    assert(Lexer.forName("py").contains(Lexer.Python))
    assert(Lexer.forName("scala").contains(Language.Scala))
    assert(Lexer.forName("rust").isEmpty)
    assert(Language.of("yaml") == Lexer.Yaml)

  test("a Markdown fence picks a user lexer from MarkdownOptions.lexers by its info string"):
    val options = MarkdownOptions(lexers = Lexer.builtIn :+ Shout)
    val text    = MarkdownParser.parse("```loud\nhey\n```", MarkdownTheme(), options)
    assert(text.lines(1).spans == Seq(Span("hey", MarkdownTheme().syntax.keyword)))

  // ---- one styled golden per new lexer, over a representative snippet ----

  private val Snippets: Seq[(String, Lexer, String)] = Seq(
    (
      "lexer-yaml",
      Lexer.Yaml,
      """---
        |# deployment
        |name: "glyphora"
        |replicas: 3
        |enabled: true
        |tags:
        |  - &base tui
        |  - *base
        |script: |
        |  echo hi # not a key""".stripMargin,
    ),
    (
      "lexer-sql",
      Lexer.Sql,
      """-- active users
        |SELECT id, name FROM users
        |where active = true and age > 21
        |ORDER BY name LIMIT 'ten';""".stripMargin,
    ),
    (
      "lexer-markdown",
      Lexer.Markdown,
      """# Title
        |> quoted
        |- [x] done with `code`
        |1. **bold** and [link](https://x.io)
        |```scala""".stripMargin,
    ),
    (
      "lexer-diff",
      Lexer.Diff,
      """diff --git a/a.txt b/a.txt
        |--- a/a.txt
        |+++ b/a.txt
        |@@ -1,2 +1,2 @@
        | context
        |-old line
        |+new line
        |\ No newline at end of file""".stripMargin,
    ),
    (
      "lexer-typescript",
      Lexer.TypeScript,
      """// greet
        |export const greet = (name: string): void => {
        |  console.log(`hi ${name}`, 'x', 42)
        |}""".stripMargin,
    ),
    (
      "lexer-python",
      Lexer.Python,
      """# fib
        |def fib(n):
        |    return n if n < 2 else fib(n - 1) + fib(n - 2)
        |print(fib(10), "done", None)""".stripMargin,
    ),
  )

  Snippets.foreach { (name, lexer, snippet) =>
    test(s"golden: ${lexer.name} highlights a representative snippet"):
      val text = SyntaxHighlighter.highlight(snippet, lexer, theme)
      // the spans must concatenate back to the source, or an editor could not map them onto its clusters
      assert(text.plainText == snippet)
      GoldenFrames.assertMatchesStyled(name, rendered(Paragraph(text), 60, text.lines.size))
  }

  test("Diff colours + lines inserted and - lines deleted, and never mistakes a file header for either"):
    val lines =
      SyntaxHighlighter.highlight("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-gone\n+added\n kept", Lexer.Diff, theme).lines
    assert(
      lines.map(_.spans.map(_.style)) == Seq(
        Seq(theme.keyword),
        Seq(theme.keyword),
        Seq(theme.function),
        Seq(theme.deleted),
        Seq(theme.inserted),
        Seq(theme.default),
      )
    )
    assert(theme.inserted.fg.contains(io.worxbend.tui.core.Color.Green))
    assert(theme.deleted.fg.contains(io.worxbend.tui.core.Color.Red))

  test("SQL keywords match in any case; YAML keys, literals and comments are told apart"):
    val sql  = SyntaxHighlighter.highlightLine("select X from t", Lexer.Sql, theme)
    assert(sql.spans.filter(_.style == theme.keyword).map(_.content) == Seq("select", "from"))
    val yaml = SyntaxHighlighter.highlightLine("on: yes # flag", Lexer.Yaml, theme)
    assert(
      yaml.spans.map(span => (span.content, span.style)) == Seq(
        ("on", theme.variable),
        (": ", theme.default),
        ("yes", theme.keyword),
        (" ", theme.default),
        ("# flag", theme.comment),
      )
    )

  test("a fenced yaml block in Markdown is highlighted with the YAML lexer"):
    val markdown = MarkdownTheme()
    val text     = MarkdownParser.parse("```yaml\nport: 8080\n```", markdown)
    assert(text.lines(1) == Lexer.Yaml.highlightLine("port: 8080", markdown.syntax))
    assert(text.lines(1).spans.exists(span => span.content == "port" && span.style == markdown.syntax.variable))
    assert(text.lines(1).spans.exists(span => span.content == "8080" && span.style == markdown.syntax.number))
