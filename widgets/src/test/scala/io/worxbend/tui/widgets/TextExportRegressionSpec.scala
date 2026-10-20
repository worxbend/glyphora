package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.testsupport.BufferAssertions.rendered
import org.scalatest.funsuite.AnyFunSuite

final class TextExportRegressionSpec extends AnyFunSuite:

  test("narrow GFM tables keep every vertical separator in its allocated column"):
    val text = "| A | B | C |\n|---|---|---|\n| 日本 | 👩‍💻 | x |\n| x | y | z |"
    for width <- 7 to 16 do
      val buffer    = rendered(Markdown(text), width, 8)
      val positions = (0 until 8)
        .map { y =>
          (0 until width).filter(x => buffer.get(x, y).symbol == "│")
        }
        .filter(_.nonEmpty)
      assert(positions.nonEmpty)
      assert(positions.distinct.size == 1, s"width $width: $positions")

  test("clearing a search view forgets only its matches and leaves its sibling query and matches intact"):
    val root                           = SearchState()
    root.query = "x"
    val left                           = root.view("left")
    val right                          = root.view("right")
    val content                        = Searchable(Paragraph(io.worxbend.tui.core.Text.raw("x")))
    def draw(state: SearchState): Unit =
      val buffer = Buffer(Rect(0, 0, 5, 1))
      content.render(buffer.area, buffer, state)
    draw(left)
    draw(right)
    val sibling                        = right.matches
    left.clear()
    assert(root.query == "x" && right.query == "x")
    assert(left.matches.isEmpty && left.current.isEmpty)
    assert(right.matches == sibling && sibling.nonEmpty)
    draw(left)
    assert(left.matches.nonEmpty)
    root.clear()
    assert(root.query.isEmpty && left.matches.isEmpty && right.matches.isEmpty)

  test("git quoted patch paths decode C escapes and octal UTF-8 before removing side prefixes"):
    val patch   = Patch
      .parse("""--- "a/sp ace.scala"
+++ "b/\346\227\245.scala"
@@ -1 +1 @@
-old
+new
""").toOption
      .get
    assert(patch.files.head.oldPath.contains("sp ace.scala"))
    assert(patch.files.head.newPath.contains("日.scala"))
    val escaped = Patch
      .parse("""--- "a/a\tb\"c\\d.scala"
+++ /dev/null
@@ -1 +0,0 @@
-old
""").toOption
      .get
    assert(escaped.files.head.oldPath.contains("a\tb\"c\\d.scala"))
    assert(escaped.files.head.newPath.isEmpty)
    assert(Patch.parse(escaped.unified) == Right(escaped))
    assert(Patch.parse(patch.unified) == Right(patch))
