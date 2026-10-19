package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Color, Modifiers}
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

final class DiffViewSpec extends AnyFunSuite:

  private val small = Patch.of("a\nb\nc", "a\nc\nd")

  private def draw(patch: Patch, options: DiffViewOptions, width: Int, height: Int, state: DiffViewState) =
    rendered(DiffView(patch, options), state, width, height)

  test("unified: a hunk header, then old and new line numbers, the marker and the text"):
    val buffer = draw(small, DiffViewOptions(), 20, 5, DiffViewState())
    assert(
      trimmedLines(buffer) == Seq(
        "@@ -1,3 +1,3 @@",
        "1 1  a",
        "2   -b",
        "3 2  c",
        "  3 +d",
      )
    )

  test("unified: removed lines draw red, added lines green, numbers dim, the hunk header cyan"):
    val buffer = draw(small, DiffViewOptions(), 20, 5, DiffViewState())
    assert(buffer.get(0, 0).style.fg == Some(Color.Cyan))
    assert(buffer.get(5, 2).style.fg == Some(Color.Red))
    assert(buffer.get(4, 2).style.fg == Some(Color.Red))
    assert(buffer.get(5, 4).style.fg == Some(Color.Green))
    assert(buffer.get(0, 1).style.modifiers.hasAny(Modifiers.Dim))
    assert(buffer.get(5, 1).style.fg.isEmpty)
    GoldenFrames.assertMatchesStyled("diff-unified", buffer)

  test("a patch with file paths heads each file with its path"):
    val patch   = Patch(Seq(FilePatch(Some("src/a.txt"), Some("src/a.txt"), small.hunks)))
    assert(trimmedLines(draw(patch, DiffViewOptions(), 20, 2, DiffViewState())) == Seq("src/a.txt", "@@ -1,3 +1,3 @@"))
    val renamed = Patch(Seq(FilePatch(Some("old.txt"), Some("new.txt"), small.hunks)))
    assert(trimmedLines(draw(renamed, DiffViewOptions(), 20, 1, DiffViewState())) == Seq("old.txt → new.txt"))

  test("line numbers can be turned off"):
    val buffer = draw(small, DiffViewOptions(lineNumbers = false), 20, 3, DiffViewState())
    assert(trimmedLines(buffer) == Seq("@@ -1,3 +1,3 @@", " a", "-b"))

  test("an empty area or an empty patch draws nothing and does not throw"):
    assert(trimmedLines(draw(small, DiffViewOptions(), 0, 0, DiffViewState())).isEmpty)
    assert(trimmedLines(draw(Patch.empty, DiffViewOptions(), 10, 2, DiffViewState())).forall(_.isEmpty))
    assert(trimmedLines(draw(small, DiffViewOptions(), 3, 2, DiffViewState())) == Seq("@@", "1 1"))

  test("measures one row per header and line"):
    assert(DiffView(small).heightAt(40) == Some(5))

  private val unevenChange = Patch.of("keep\nold one\nold two\nend", "keep\nnew\nend")

  test("split: the old side left of the divider, the new right, a 2-line change aligned against 1 line"):
    val buffer = draw(unevenChange, DiffViewOptions(layout = DiffLayout.Split), 31, 5, DiffViewState())
    assert(
      trimmedLines(buffer) == Seq(
        "@@ -1,4 +1,3 @@",
        "1  keep        │1  keep",
        "2 -old one     │2 +new",
        "3 -old two     │",
        "4  end         │3  end",
      )
    )
    GoldenFrames.assertMatches("diff-split", buffer)

  test("split: CJK text is clipped by display width, so the divider and the right side stay in their columns"):
    val patch  = Patch.of("中文\n日本語のテキスト", "中文\n한국어")
    val buffer = draw(patch, DiffViewOptions(layout = DiffLayout.Split), 21, 3, DiffViewState())
    (1 to 2).foreach { row =>
      assert(buffer.get(10, row).symbol == "│", s"divider on row $row")
      assert(buffer.get(11, row).symbol == (row match
        case 1 => "1"
        case _ => "2"))
    }
    assert(buffer.get(3, 1).symbol == "中" && buffer.get(14, 1).symbol == "中")
    // "1 -" then the widest prefix of 日本語のテキスト that fits 7 columns: three wide clusters, one column left blank
    assert(buffer.get(3, 2).symbol == "日" && buffer.get(7, 2).symbol == "語" && buffer.get(9, 2).symbol == " ")
    assert(buffer.get(14, 2).symbol == "한")

  test("word highlight: only `bar` and `baz` are emphasised when `foo bar` becomes `foo baz`"):
    val buffer = draw(Patch.of("foo bar", "foo baz"), DiffViewOptions(), 20, 3, DiffViewState())
    assert(trimmedLines(buffer) == Seq("@@ -1 +1 @@", "1   -foo bar", "  1 +foo baz"))
    def emphasised(x: Int, y: Int): Boolean =
      buffer.get(x, y).style.modifiers.hasAll(Modifiers.Bold | Modifiers.Reverse)
    (1 to 2).foreach { row =>
      assert((5 to 8).forall(x => !emphasised(x, row)), s"`foo ` stays plain on row $row")
      assert((9 to 11).forall(x => emphasised(x, row)), s"the changed word is emphasised on row $row")
    }
    assert(buffer.get(9, 1).style.fg == Some(Color.Red) && buffer.get(9, 2).style.fg == Some(Color.Green))
    val plain = draw(Patch.of("foo bar", "foo baz"), DiffViewOptions(wordHighlight = false), 20, 3, DiffViewState())
    assert(!plain.get(9, 1).style.modifiers.hasAny(Modifiers.Bold))

  test("a lexer colours tokens over the inserted style"):
    val options = DiffViewOptions(lexer = Some(Lexer.Scala), wordHighlight = false)
    val buffer  = draw(Patch.of("", "val x = 1"), options, 20, 2, DiffViewState())
    assert(trimmedLines(buffer) == Seq("@@ -0,0 +1 @@", "  1 +val x = 1"))
    assert(buffer.get(5, 1).style.fg == Some(Color.Magenta))
    assert(buffer.get(9, 1).style.fg == Some(Color.Green))
    assert(buffer.get(13, 1).style.fg == Some(Color.Yellow))

  private val twoHunks = Patch(
    Seq(
      FilePatch(
        Some("app.txt"),
        Some("app.txt"),
        Seq(
          Hunk(
            1,
            1,
            Seq(DiffLine.Context("one"), DiffLine.Removed("two"), DiffLine.Added("TWO"), DiffLine.Context("x")),
          ),
          Hunk(10, 10, Seq(DiffLine.Context("ten"), DiffLine.Added("ten and a half"), DiffLine.Context("eleven"))),
        ),
      )
    )
  )

  test("`nextHunk` scrolls the next hunk's header to the top row; `previousHunk` goes back"):
    val state = DiffViewState()
    val view  = DiffView(twoHunks)
    def top   = trimmedLines(rendered(view, state, 30, 4)).head
    assert(top == "app.txt")
    state.nextHunk()
    assert(top == "@@ -1,3 +1,3 @@" && state.currentHunk == Some(0))
    state.nextHunk()
    assert(top == "@@ -10,2 +10,3 @@" && state.offset == 6 && state.currentHunk == Some(1))
    state.nextHunk()
    assert(state.offset == 6, "past the last hunk nothing moves")
    state.previousHunk()
    assert(top == "@@ -1,3 +1,3 @@")
    state.previousHunk()
    assert(state.offset == 1, "above the first hunk nothing moves")

  test("scrolling stops with the last row at the bottom; first and last jump to the ends"):
    val state = DiffViewState()
    val view  = DiffView(twoHunks)
    val _     = rendered(view, state, 30, 4)
    state.scrollDown(100)
    assert(state.offset == 6)
    state.scrollUp(2)
    assert(state.offset == 4)
    state.first()
    assert(state.offset == 0)
    state.last()
    assert(state.offset == 6)

  test("pageDown and pageUp move a viewport at a time, clamped like every other scroll"):
    val state = DiffViewState()
    val _     = rendered(DiffView(twoHunks), state, 30, 4)
    state.pageDown()
    assert(state.offset == 4)
    state.pageDown()
    assert(state.offset == 6)
    state.pageUp()
    assert(state.offset == 2)

  test("an offset a hunk jump left below the scrollable range stays put on scrollDown, and every move is versioned"):
    val state  = DiffViewState()
    val _      = rendered(DiffView(twoHunks), state, 30, 2)
    state.offset = 9
    val before = state.version
    state.scrollDown()
    assert(state.offset == 9)
    state.scrollUp()
    assert(state.offset == 8 && state.version > before)

  test("contextLines folds long runs of unchanged lines into one row and keeps numbering the lines after it"):
    val before = (1 to 30).map(n => s"l$n").mkString("\n")
    val after  = before.replace("l15\n", "L15\n")
    val patch  = Patch.of(before, after, context = 100)
    val buffer = draw(patch, DiffViewOptions(contextLines = Some(2)), 30, 9, DiffViewState())
    assert(
      trimmedLines(buffer) == Seq(
        "@@ -1,30 +1,30 @@",
        "… 12 unchanged lines",
        "13 13  l13",
        "14 14  l14",
        "15    -l15",
        "   15 +L15",
        "16 16  l16",
        "17 17  l17",
        "… 13 unchanged lines",
      )
    )
    assert(DiffView(patch, DiffViewOptions(contextLines = Some(2))).heightAt(30) == Some(9))
