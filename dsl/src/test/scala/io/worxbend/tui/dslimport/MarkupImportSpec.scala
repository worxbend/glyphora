package io.worxbend.tui.dslimport

import io.worxbend.tui.dsl.*

import org.scalatest.funsuite.AnyFunSuite

/** The two text-reading additions are part of the one-import promise: `markup"…"`, `Text.fromAnsi` and `AnsiParser` all
  * have to be reachable with `import io.worxbend.tui.dsl.*` and nothing else, and a `Text` built by either has to have
  * a factory that shows it. This suite lives outside package `dsl` so that an export missing from `dsl.scala` is a
  * compile error here rather than something the in-package suites cannot see.
  */
final class MarkupImportSpec extends AnyFunSuite:

  test("markup, Text.fromAnsi and AnsiParser are reachable from the one import, and text(...) takes the result"):
    val styled: Text = markup"[bold]ok[/]"
    val parsed: Text = Text.fromAnsi("\u001b[32mgreen\u001b[0m")
    val parser       = AnsiParser()
    val streamed     = Text(parser.feed("a\nb") :+ parser.finish())
    val tree         = column(text(styled), text(parsed), text(streamed))
    assert(tree.children.size == 3)
    assert(styled.lines.head.spans.head.style == Style.Default.bold)
    assert(parsed.lines.head.spans.head.style == Style.fg(Color.Green))
