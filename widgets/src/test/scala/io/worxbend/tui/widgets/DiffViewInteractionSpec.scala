package io.worxbend.tui.widgets

import io.worxbend.tui.core.Color
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** The interactive half of [[DiffView]]: expanding a folded context run, horizontal scroll, the lexer picked from the
  * file's extension, and the notice an approximate patch carries. Every expected frame is written out by hand.
  */
final class DiffViewInteractionSpec extends AnyFunSuite:

  private val numbered = (1 to 10).map(_.toString)
  private val edited   = numbered.updated(0, "one").updated(9, "ten")
  private val folding  = Patch.of(numbered.mkString("\n"), edited.mkString("\n"), context = 10)
  private val plain    = DiffViewOptions(lineNumbers = false, contextLines = Some(1))

  private val folded = Seq("@@ -1,10 +1,10 @@", "-1", "+one", " 2", "… 6 unchanged lines", " 9", "-10", "+ten")

  test("a folded context run expands in place when its row is expanded"):
    val state = DiffViewState()
    assert(trimmedLines(rendered(DiffView(folding, plain), state, 20, 8)) == folded)
    assert(!state.expandAt(3), "a row that is not a fold expands nothing")
    assert(state.expandAt(4))
    assert(
      trimmedLines(rendered(DiffView(folding, plain), state, 20, 13)) ==
        Seq("@@ -1,10 +1,10 @@", "-1", "+one", " 2", " 3", " 4", " 5", " 6", " 7", " 8", " 9", "-10", "+ten")
    )
    state.collapseAll()
    assert(trimmedLines(rendered(DiffView(folding, plain), state, 20, 8)) == folded)

  test("expandFirstVisible expands the first fold on screen, and moves the version"):
    val state  = DiffViewState()
    val _      = rendered(DiffView(folding, plain), state, 20, 8)
    val before = state.version
    assert(state.expandFirstVisible())
    assert(state.version > before)
    assert(trimmedLines(rendered(DiffView(folding, plain), state, 20, 13)).count(_.startsWith(" ")) == 8)
    assert(!state.expandFirstVisible(), "nothing folded is left on screen")

  test("the rowAt of a fold is what a click expands: the row under the pointer, offset included"):
    val state = DiffViewState()
    val _     = rendered(DiffView(folding, plain), state, 20, 4)
    state.scrollDown(2)
    val _     = rendered(DiffView(folding, plain), state, 20, 4)
    // the fold is laid-out row 4; scrolled down two rows it is on screen row 2
    assert(state.expandAt(state.offset + 2))

  private val wide = Patch.of("abcdefghij", "abcdefghiX")

  test("horizontal scroll moves the line text and keeps the marker and the headers in place"):
    val options = DiffViewOptions(lineNumbers = false, wordHighlight = false)
    val state   = DiffViewState()
    assert(trimmedLines(rendered(DiffView(wide, options), state, 6, 3)) == Seq("@@ -1", "-abcde", "+abcde"))
    state.scrollRight(3)
    assert(trimmedLines(rendered(DiffView(wide, options), state, 6, 3)) == Seq("@@ -1", "-defgh", "+defgh"))
    state.scrollRight(100)
    assert(trimmedLines(rendered(DiffView(wide, options), state, 6, 3)) == Seq("@@ -1", "-j", "+X"))
    state.scrollLeft(100)
    assert(state.column == 0)

  private val scalaPatch = Patch.parse("--- a/x.scala\n+++ b/x.scala\n@@ -1 +1 @@\n-val a\n+val b\n").toOption.get

  test("the lexer comes from the file's extension when the options name none"):
    val options = DiffViewOptions(lineNumbers = false, wordHighlight = false)
    val buffer  = rendered(DiffView(scalaPatch, options), DiffViewState(), 20, 4)
    assert(trimmedLines(buffer) == Seq("x.scala", "@@ -1 +1 @@", "-val a", "+val b"))
    assert(buffer.get(1, 2).style.fg.contains(Color.Magenta), "`val` is a Scala keyword")
    assert(buffer.get(5, 2).style.fg.contains(Color.Red), "`a` keeps the deleted colour")
    val off     = rendered(DiffView(scalaPatch, options.copy(lexerFromPath = false)), DiffViewState(), 20, 4)
    assert(off.get(1, 2).style.fg.contains(Color.Red))
    val unknown = Patch.parse("--- a/x.txt\n+++ b/x.txt\n@@ -1 +1 @@\n-val a\n+val b\n").toOption.get
    assert(rendered(DiffView(unknown, options), DiffViewState(), 20, 4).get(1, 2).style.fg.contains(Color.Red))

  test("an approximate patch says so on its first row"):
    val patch   = Patch.of("a\nb", "x\ny", maxEditDistance = 1)
    val options = DiffViewOptions(lineNumbers = false)
    val lines   = trimmedLines(rendered(DiffView(patch, options), DiffViewState(), 60, 6))
    assert(lines.head == options.approximateNotice)
    assert(lines.tail == Seq("@@ -1,2 +1,2 @@", "-a", "-b", "+x", "+y"))
