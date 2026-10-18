package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Color, Modifiers}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

/** GFM tables, nested lists and task lists — the 0.20 additions to [[Markdown]]. */
final class MarkdownGfmSpec extends AnyFunSuite:

  private val Table =
    """| Name | City | Qty |
      #|:-----|:----:|----:|
      #| Ann  | 東京 | 1 |
      #| Bartholomew | Rome | 200 |""".stripMargin('#')

  test("golden: a GFM table with a CJK cell aligns its columns"):
    val markdown   = Markdown(Table)
    GoldenFrames.assertMatches("markdown-table-cjk", rendered(markdown, 40, 4))
    // the CJK cell is four columns wide, so the separators after it line up with the ASCII rows'
    val rows       = trimmedLines(rendered(markdown, 40, 4))
    val separators = rows.filterNot(_.startsWith("─")).map(row => row.indexOf('│'))
    assert(separators.distinct.size == 1)

  test("a table too wide for the area wraps its widest cells by default"):
    val buffer = rendered(Markdown("| a | b |\n|---|---|\n| one two three | x |"), 9, 4)
    assert(trimmedLines(buffer) == Seq("a     │ b", "──────┼──", "one   │ x", "two   │"))
    assert(Markdown("| a | b |\n|---|---|\n| one two three | x |").heightAt(9).contains(5))

  test("TableOverflow.Truncate cuts a too-wide cell with an ellipsis instead"):
    val options = MarkdownOptions(tableOverflow = TableOverflow.Truncate)
    val buffer  = rendered(Markdown("| a | b |\n|---|---|\n| one two three | x |", options = options), 9, 3)
    assert(trimmedLines(buffer) == Seq("a     │ b", "──────┼──", "one…  │ x"))

  test("table header and border take their theme styles"):
    val buffer = rendered(Markdown("| h |\n|---|\n| v |"), 5, 3)
    assert(buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Bold))
    assert(buffer.get(0, 1).style.modifiers.hasAny(Modifiers.Dim))
    assert(!buffer.get(0, 2).style.modifiers.hasAny(Modifiers.Bold))

  test("a line with pipes but no alignment row is prose, not a table"):
    assert(trimmedLines(rendered(Markdown("a | b\nplain"), 20, 2)) == Seq("a | b", "plain"))

  test("golden: a nested list of three levels indents by level and varies its bullet"):
    val source =
      """- fruit
        |  - apple
        |    - braeburn
        |    - gala
        |  - pear
        |- veg
        |1. first
        |   1. nested ordinal""".stripMargin
    GoldenFrames.assertMatches("markdown-nested-list", rendered(Markdown(source), 30, 8))
    assert(
      trimmedLines(rendered(Markdown(source), 30, 8)) == Seq(
        "• fruit",
        "  ◦ apple",
        "    ▪ braeburn",
        "    ▪ gala",
        "  ◦ pear",
        "• veg",
        "1. first",
        "  1. nested ordinal",
      )
    )

  test("golden: task list items draw their check glyphs"):
    val source = "- [x] write the parser\n- [ ] ship it\n  - [X] nested done\n1. [ ] numbered task"
    val buffer = rendered(Markdown(source), 30, 4)
    GoldenFrames.assertMatches("markdown-task-list", buffer)
    assert(trimmedLines(buffer) == Seq("☑ write the parser", "☐ ship it", "  ☑ nested done", "1. ☐ numbered task"))
    assert(buffer.get(0, 0).style.fg.contains(Color.Green))
    assert(buffer.get(0, 1).style.fg.contains(Color.Cyan))

  test("bullet and task glyphs come from MarkdownOptions"):
    val options = MarkdownOptions(bullets = Seq("-"), taskDone = "[x]", taskOpen = "[ ]", listIndent = 4)
    val buffer  = rendered(Markdown("- a\n  - b\n- [x] c", options = options), 20, 3)
    assert(trimmedLines(buffer) == Seq("- a", "    - b", "[x] c"))
