package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, Style}

import org.scalatest.funsuite.AnyFunSuite

/** Multi-line lexing: the [[LexState]] a lexer carries from one line into the next — block comments, triple-quoted
  * strings, YAML flow collections, block scalars and quoted scalars that span lines. Every expected span below is
  * written out by hand from the source text.
  */
final class StatefulLexerSpec extends AnyFunSuite:

  private val theme = SyntaxTheme()

  private def spans(code: String, lexer: Lexer): Seq[Seq[(String, Style)]] =
    SyntaxHighlighter.highlight(code, lexer, theme).lines.map(_.spans.map(span => (span.content, span.style)))

  test("a Scala block comment carries across lines and ends where */ closes it"):
    assert(
      spans("val a = 1 /* start\nstill comment\nend */ val b", Lexer.Scala) == Seq(
        Seq(
          ("val", theme.keyword),
          (" a = ", theme.default),
          ("1", theme.number),
          (" ", theme.default),
          ("/* start", theme.comment),
        ),
        Seq(("still comment", theme.comment)),
        Seq(("end */", theme.comment), (" ", theme.default), ("val", theme.keyword), (" b", theme.default)),
      )
    )

  test("a block comment opened and closed on one line leaves the next line alone"):
    assert(
      spans("/* a */ x\nval", Lexer.Scala) ==
        Seq(Seq(("/* a */", theme.comment), (" x", theme.default)), Seq(("val", theme.keyword)))
    )

  test("a triple-quoted Scala string spans lines, and a lone quote inside it does not close it"):
    assert(
      spans("val s = \"\"\"one \"\ntwo\"\"\" + x", Lexer.Scala) == Seq(
        Seq(("val", theme.keyword), (" s = ", theme.default), ("\"\"\"one \"", theme.string)),
        Seq(("two\"\"\"", theme.string), (" + x", theme.default)),
      )
    )

  test("a Python docstring in single quotes spans lines"):
    assert(
      spans("def f():\n    '''doc\n    more'''\n    pass", Lexer.Python) == Seq(
        Seq(("def", theme.keyword), (" ", theme.default), ("f", theme.function), ("():", theme.default)),
        Seq(("    ", theme.default), ("'''doc", theme.string)),
        Seq(("    more'''", theme.string)),
        Seq(("    ", theme.default), ("pass", theme.keyword)),
      )
    )

  test("a TypeScript template literal spans lines"):
    assert(
      spans("const t = `a\nb` + 1", Lexer.TypeScript) == Seq(
        Seq(("const", theme.keyword), (" t = ", theme.default), ("`a", theme.string)),
        Seq(("b`", theme.string), (" + ", theme.default), ("1", theme.number)),
      )
    )

  test("the one-line form keeps no state: a line inside a block comment, lexed alone, is plain code"):
    assert(Lexer.Scala.highlightLine("still val", theme).spans.exists(_.style == theme.keyword))

  test("YAML flow collections highlight their keys, numbers and literals"):
    assert(
      spans("point: {x: 1, y: [a, true]}", Lexer.Yaml) == Seq(
        Seq(
          ("point", theme.variable),
          (": {", theme.default),
          ("x", theme.variable),
          (": ", theme.default),
          ("1", theme.number),
          (", ", theme.default),
          ("y", theme.variable),
          (": [a, ", theme.default),
          ("true", theme.keyword),
          ("]}", theme.default),
        )
      )
    )

  test("a YAML flow collection that spans lines keeps highlighting inside it"):
    assert(
      spans("list: [1,\n  \"two\"]\nnext: 3", Lexer.Yaml) == Seq(
        Seq(("list", theme.variable), (": [", theme.default), ("1", theme.number), (",", theme.default)),
        Seq(("  ", theme.default), ("\"two\"", theme.string), ("]", theme.default)),
        Seq(("next", theme.variable), (": ", theme.default), ("3", theme.number)),
      )
    )

  test("a YAML block scalar is one string for every line indented under its key, comments and colons included"):
    assert(
      spans("script: |\n  echo hi # not a comment\n\n  two: 3\nnext: 4", Lexer.Yaml) == Seq(
        Seq(("script", theme.variable), (": ", theme.default), ("|", theme.keyword)),
        Seq(("  ", theme.default), ("echo hi # not a comment", theme.string)),
        Seq.empty,
        Seq(("  ", theme.default), ("two: 3", theme.string)),
        Seq(("next", theme.variable), (": ", theme.default), ("4", theme.number)),
      )
    )

  test("a YAML double-quoted scalar continues onto the next line until its closing quote"):
    assert(
      spans("msg: \"hello\n  world\" # c", Lexer.Yaml) == Seq(
        Seq(("msg", theme.variable), (": ", theme.default), ("\"hello", theme.string)),
        Seq(("  world\"", theme.string), (" ", theme.default), ("# c", theme.comment)),
      )
    )

  test("a text area carries the lexer state down to the rows it draws"):
    val state  = TextAreaState("a /* x\nyy\n*/ b")
    val buffer = Buffer(Rect(0, 0, 10, 3))
    TextArea(showCursor = false, options = TextAreaOptions(lexer = Some(Lexer.Scala)))
      .render(buffer.area, buffer, state)
    assert(buffer.get(0, 1).style == theme.comment, "the middle line is inside the comment")
    assert(buffer.get(3, 2).style == theme.default, "after */ the code is plain again")

  test("a Markdown fence carries the lexer state from line to line"):
    val markdown = MarkdownTheme()
    val text     = MarkdownParser.parse("```scala\n/* one\ntwo */\n```", markdown)
    assert(text.lines(2).spans.map(span => (span.content, span.style)) == Seq(("two */", markdown.syntax.comment)))
