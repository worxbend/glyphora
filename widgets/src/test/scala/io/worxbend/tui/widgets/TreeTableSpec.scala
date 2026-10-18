package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Constraint, Modifiers, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.{lines, rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class TreeTableSpec extends AnyFunSuite:

  private val nodes = Seq(
    TreeNode("src", Seq(TreeNode("Main.scala"), TreeNode("Util.scala"))),
    TreeNode("README.md"),
  )

  private val sizes = Map(
    "src"        -> Seq("dir", "-"),
    "Main.scala" -> Seq("file", "1200"),
    "Util.scala" -> Seq("file", "300"),
    "README.md"  -> Seq("file", "80"),
  )

  private val table: TreeTable[String] =
    TreeTable(
      nodes,
      Seq("Name", "Kind", "Bytes"),
      key => sizes(key),
      Seq(Constraint.Length(16), Constraint.Length(5), Constraint.Length(5)),
    )

  test("collapsed, the table shows the roots under a header, with branch markers in the first column"):
    val state = TreeState.keyed(_.label)
    assert(
      trimmedLines(rendered(table, state, 28, 4)) == Seq(
        "Name             Kind  Bytes",
        "▸ src            dir   -",
        "  README.md      file  80",
        "",
      )
    )

  test("expanding a branch re-renders its children as indented rows with their own columns"):
    val state  = TreeState.keyed(_.label)
    state.selected = Some("src")
    state.toggle(nodes)
    val buffer = rendered(table, state, 28, 5)
    assert(
      trimmedLines(buffer) == Seq(
        "Name             Kind  Bytes",
        "▾ src            dir   -",
        "    Main.scala   file  1200",
        "    Util.scala   file  300",
        "  README.md      file  80",
      )
    )
    assert(buffer.get(0, 1).style.modifiers.hasAny(Modifiers.Reverse))
    assert(buffer.get(20, 1).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(0, 2).style.modifiers.hasAny(Modifiers.Reverse))
    state.toggle(nodes)
    assert(trimmedLines(rendered(table, state, 28, 5)).count(_.nonEmpty) == 3)

  test("cells are cut at their column's display width, so a wide character never straddles a boundary"):
    val wide  = Seq(TreeNode("日本語のファイル"))
    val state = TreeState()
    val view  =
      TreeTable[Seq[Int]](wide, Seq("名前", "K"), _ => Seq("種類"), Seq(Constraint.Length(7), Constraint.Length(3)))
    assert(lines(rendered(view, state, 11, 2)) == Seq("名前    K  ", "  日本  種 "))

  test("a missing cell draws blank and an extra one is ignored"):
    val state = TreeState()
    val view  = TreeTable[Seq[Int]](
      Seq(TreeNode("a"), TreeNode("b")),
      Seq("N", "V"),
      path => if path == Seq(0) then Seq() else Seq("x", "y"),
      Seq(Constraint.Length(4), Constraint.Length(2)),
    )
    assert(trimmedLines(rendered(view, state, 7, 3)) == Seq("N    V", "  a", "  b  x"))

  test("the body scrolls to keep the selection visible below the pinned header"):
    val many  = (1 to 20).map(i => TreeNode(s"n$i"))
    val state = TreeState()
    state.selected = Some(Seq(14))
    val shown = trimmedLines(
      rendered(TreeTable[Seq[Int]](many, Seq("Name"), _ => Seq.empty, Seq(Constraint.Fill(1))), state, 10, 4)
    )
    assert(shown.head == "Name")
    assert(shown.last == "  n15")

  test("a tiny or empty area writes nothing outside itself"):
    val state    = TreeState.keyed(_.label)
    val buffer   = Buffer(Rect(0, 0, 12, 5))
    val sentinel = Cell("~", Style.Default)
    buffer.fill(buffer.area, sentinel)
    val area     = Rect(1, 1, 4, 1)
    table.render(area, buffer, state)
    for
      y <- 0 until 5
      x <- 0 until 12
      if !area.contains(x, y)
    do assert(buffer.get(x, y) == sentinel)
    table.render(Rect(0, 0, 0, 0), buffer, state)
