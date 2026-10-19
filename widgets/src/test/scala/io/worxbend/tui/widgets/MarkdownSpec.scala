package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Color, Modifiers}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class MarkdownSpec extends AnyFunSuite:

  test("headings strip their hashes and take the heading styles"):
    val buffer = rendered(Markdown("# Title\n## Section\n### Sub"), 20, 3)
    assert(trimmedLines(buffer) == Seq("Title", "Section", "Sub"))
    assert(buffer.get(0, 0).style.fg.contains(Color.Cyan))
    assert(buffer.get(0, 1).style.modifiers.hasAny(Modifiers.Bold))
    assert(buffer.get(0, 2).style.modifiers.hasAny(Modifiers.Underline))

  test("bullets and numbered items get their markers"):
    val buffer = rendered(Markdown("- alpha\n* beta\n2. gamma"), 20, 3)
    assert(trimmedLines(buffer) == Seq("• alpha", "• beta", "2. gamma"))

  test("blockquotes are prefixed and quoted-styled"):
    val buffer = rendered(Markdown("> wise words"), 20, 1)
    assert(trimmedLines(buffer) == Seq("▎ wise words"))
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Dim))

  test("inline strong, emphasis, and code style their runs only"):
    val buffer = rendered(Markdown("a **b** *c* `d`"), 20, 1)
    assert(trimmedLines(buffer) == Seq("a b c d"))
    assert(!buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Bold))
    assert(buffer.get(2, 0).style.modifiers.hasAny(Modifiers.Bold))
    assert(buffer.get(4, 0).style.modifiers.hasAny(Modifiers.Italic))
    assert(buffer.get(6, 0).style.fg.contains(Color.Yellow))

  test("unclosed markers render literally"):
    val buffer = rendered(Markdown("2 * 3 * 4 and a ** dangling"), 30, 1)
    assert(trimmedLines(buffer).head.startsWith("2 "))
    // the space-delimited stars are not emphasis: '* 3 ' has content, so check text survived
    assert(trimmedLines(buffer).head.contains("dangling"))

  test("fenced code blocks render verbatim with the code style and no fence lines"):
    val buffer = rendered(Markdown("```\nval x = **not bold**\n```"), 30, 3)
    assert(trimmedLines(buffer)(1) == "val x = **not bold**")
    assert(buffer.get(0, 1).style.fg.contains(Color.Yellow))

  test("prose wraps between words without splitting clusters"):
    // Every pair of words together is wider than eight columns, so each word takes a row of its own; the wide
    // characters are never split, and the area only shows the first three of the four rows.
    val buffer = rendered(Markdown("hello wide 你好 world"), 8, 3)
    assert(trimmedLines(buffer) == Seq("hello", "wide", "你好"))
    assert(Markdown("hello wide 你好 world").heightAt(8).contains(4))

  test("inline links render their label with the url attached"):
    val buffer = rendered(Markdown("see [the docs](https://example.com) here"), 40, 1)
    assert(trimmedLines(buffer).head == "see the docs here")
    assert(buffer.get(4, 0).style.link.contains("https://example.com"))
    assert(buffer.get(4, 0).style.modifiers.hasAny(Modifiers.Underline))
    assert(buffer.get(0, 0).style.link.isEmpty)

  test("a malformed link renders literally"):
    val buffer = rendered(Markdown("just [brackets] here"), 30, 1)
    assert(trimmedLines(buffer).head == "just [brackets] here")

  test("heightAt measures wrapped markdown"):
    assert(Markdown("1234567890 1234567890").heightAt(30).contains(1))
    assert(Markdown("1234567890 1234567890").heightAt(10).exists(_ >= 2))

  test("a language-tagged code fence is syntax-highlighted"):
    val theme = MarkdownTheme()
    val text  = MarkdownParser.parse("```scala\nval x = 1\n```", theme)
    val code  = text.lines(1) // the `val x = 1` row
    val kw    = code.spans.find(_.content == "val").getOrElse(fail("no keyword span"))
    assert(kw.style == theme.syntax.keyword)
    assert(code.spans.exists(s => s.content == "1" && s.style == theme.syntax.number))

  test("an untagged code fence still renders verbatim in the code style"):
    val theme = MarkdownTheme()
    val text  = MarkdownParser.parse("```\nplain text\n```", theme)
    assert(text.lines(1) == io.worxbend.tui.core.Line.styled("plain text", theme.code))

  test("a wrapped list item hangs its continuation rows under the item's text, not under its marker"):
    assert(
      trimmedLines(rendered(Markdown("- alpha beta gamma delta"), 12, 3)) == Seq("• alpha beta", "  gamma", "  delta")
    )
    assert(trimmedLines(rendered(Markdown("1. alpha beta gamma"), 10, 3)) == Seq("1. alpha", "   beta", "   gamma"))
    assert(Markdown("- alpha beta gamma delta").heightAt(12).contains(3))

  test("a nested wrapped item hangs under its own text, task box included"):
    val buffer = rendered(Markdown("- top\n  - [ ] one two three"), 12, 3)
    assert(trimmedLines(buffer) == Seq("• top", "  ☐ one two", "    three"))
