package io.worxbend.tui.widgets

import io.worxbend.tui.core.Modifiers
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class MarkdownStreamSpec extends AnyFunSuite:

  private def streamed(text: String): MarkdownStream =
    val stream = MarkdownStream()
    stream.append(text)
    stream

  test("an unclosed ** renders its text bold, not the literal markers"):
    val buffer = rendered(streamed("some **bold").markdown(), 20, 1)
    assert(trimmedLines(buffer) == Seq("some bold"))
    assert(buffer.get(5, 0).style.modifiers.hasAny(Modifiers.Bold))
    assert(!buffer.get(0, 0).style.modifiers.hasAny(Modifiers.Bold))

  test("an unclosed backtick and emphasis are closed too"):
    assert(MarkdownSanitizer.sanitize("run `ls -l") == "run `ls -l`")
    assert(MarkdownSanitizer.sanitize("so *very") == "so *very*")

  test("a half-written link renders its label"):
    val buffer = rendered(streamed("see [link](ht").markdown(), 20, 1)
    assert(trimmedLines(buffer) == Seq("see link"))
    assert(MarkdownSanitizer.sanitize("see [lab") == "see lab")

  test("an open fence is closed, so the code renders as code rather than as prose"):
    assert(MarkdownSanitizer.sanitize("```scala\nval x = **1") == "```scala\nval x = **1\n```")
    val buffer = rendered(streamed("```scala\nval x = 1").markdown(), 20, 2)
    assert(trimmedLines(buffer) == Seq("", "val x = 1"))

  test("a bare heading marker at the end is held back until its text arrives"):
    assert(MarkdownSanitizer.sanitize("intro\n##") == "intro\n")
    assert(MarkdownSanitizer.sanitize("intro\n# ") == "intro\n")

  test("well-formed text and arithmetic stars pass through unchanged"):
    Seq("a **b** c", "2 * 3", "just [brackets] here", "```\ncode\n```", "- [x] done", "| a |\n|---|").foreach { text =>
      assert(MarkdownSanitizer.sanitize(text) == text, text)
    }

  test("appending token by token yields the same final frame as parsing the whole text once"):
    val full   =
      """# Answer
        |
        |Here is **the plan** with `code` and a [link](https://example.com):
        |
        |- step one
        |  - [x] nested done
        |
        || Name | Qty |
        ||:-----|----:|
        || 東京 | 3 |
        |
        |```yaml
        |port: 8080
        |```
        |Done.""".stripMargin
    val tokens = full.grouped(3).toSeq
    val stream = MarkdownStream()
    tokens.foreach { token =>
      stream.append(token)
      rendered(stream.markdown(), 40, 16) // every intermediate frame renders without throwing
    }
    assert(stream.text == full)
    assert(trimmedLines(rendered(stream.markdown(), 40, 16)) == trimmedLines(rendered(Markdown(full), 40, 16)))
    assert(rendered(stream.markdown(), 40, 16) == rendered(Markdown(full), 40, 16))

  test("finish stops the sanitizer, so the final frame is exactly the document's own"):
    val stream = streamed("ends with a dangling **")
    assert(trimmedLines(rendered(stream.markdown(), 30, 1)) == Seq("ends with a dangling"))
    stream.finish()
    assert(trimmedLines(rendered(stream.markdown(), 30, 1)) == Seq("ends with a dangling **"))

  test("revision advances on every change and the widget is reused between changes"):
    val stream = MarkdownStream()
    val before = stream.revision
    stream.append("a")
    assert(stream.revision > before)
    assert(stream.markdown() eq stream.markdown())
    stream.clear()
    assert(stream.text.isEmpty)
