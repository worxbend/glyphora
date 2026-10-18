package io.worxbend.tui.widgets

import io.worxbend.tui.core.Modifiers
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** A [[TreeState]] keyed by item identity keeps the reader's place when the data is refreshed: expansion and selection
  * name the node, not the row it happened to occupy.
  */
final class TreeKeyedStateSpec extends AnyFunSuite:

  private val original = Seq(
    TreeNode("a"),
    TreeNode("b", Seq(TreeNode("b1"), TreeNode("b2"))),
    TreeNode("c", Seq(TreeNode("c1"))),
  )

  /** The same nodes, reordered and with a sibling inserted — what a refresh from the source typically looks like. */
  private val refreshed = Seq(
    TreeNode("c", Seq(TreeNode("c1"))),
    TreeNode("new"),
    TreeNode("a"),
    TreeNode("b", Seq(TreeNode("b1"), TreeNode("b2"))),
  )

  test("expanding and selecting a node by key survives a reordered copy of the data"):
    val state  = TreeState.keyed(_.label)
    state.expanded += "b"
    state.selected = Some("b")
    assert(trimmedLines(rendered(Tree(original), state, 20, 5)) == Seq("  a", "▾ b", "    b1", "    b2", "▸ c"))
    val buffer = rendered(Tree(refreshed), state, 20, 6)
    assert(trimmedLines(buffer) == Seq("▸ c", "  new", "  a", "▾ b", "    b1", "    b2"))
    assert(state.selected.contains("b"))
    assert(state.expanded.contains("b"))
    assert(buffer.get(0, 3).style.modifiers.hasAny(Modifiers.Reverse), "the highlight followed b to its new row")
    assert(!buffer.get(0, 1).style.modifiers.hasAny(Modifiers.Reverse), "the row b used to sit on is not highlighted")

  test("navigation moves the selection through the visible rows by key"):
    val state = TreeState.keyed(_.label)
    state.expanded += "b"
    state.selectNext(original)
    assert(state.selected.contains("a"))
    state.selectNext(original)
    state.selectNext(original)
    assert(state.selected.contains("b1"))
    state.selectPrevious(original)
    assert(state.selected.contains("b"))

  test("toggle expands and collapses the selected branch by key, and leaves a leaf alone"):
    val state = TreeState.keyed(_.label)
    state.selected = Some("c")
    state.toggle(original)
    assert(state.expanded.contains("c"))
    assert(trimmedLines(rendered(Tree(original), state, 20, 4)) == Seq("  a", "▸ b", "▾ c", "    c1"))
    state.toggle(original)
    assert(!state.expanded.contains("c"))
    state.selected = Some("a")
    state.toggle(original)
    assert(state.expanded.isEmpty)

  test("a selection scrolled out of view scrolls back to the node after a refresh moved it"):
    val many    = Vector.tabulate(30)(index => TreeNode(s"node$index"))
    val state   = TreeState.keyed(_.label)
    state.selected = Some("node29")
    val _       = rendered(Tree(many), state, 20, 3)
    assert(state.offset == 27)
    val rotated = many.drop(15) ++ many.take(15) // node29 is now row 14
    val buffer  = rendered(Tree(rotated), state, 20, 3)
    assert(trimmedLines(buffer).contains("  node29"))
    assert(state.selected.contains("node29"))

  test("the positional state is still a tree state keyed by path"):
    val state: TreeState[Seq[Int]] = TreeState()
    state.expanded += Seq(1)
    state.selected = Some(Seq(1, 0))
    assert(trimmedLines(rendered(Tree(original), state, 20, 5)) == Seq("  a", "▾ b", "    b1", "    b2", "▸ c"))
    // positional on purpose: after the reorder the same path names a different node
    val _                          = rendered(Tree(refreshed), state, 20, 6)
    assert(state.expanded.contains(Seq(1)))
    assert(state.selected.contains(Seq(1, 0)))

  test("byLabelPath tells two leaves with the same label apart by their ancestry"):
    val nodes  = Seq(
      TreeNode("src", Seq(TreeNode("README.md"))),
      TreeNode("docs", Seq(TreeNode("README.md"))),
    )
    val state  = TreeState.byLabelPath
    state.expanded ++= Seq(Seq("src"), Seq("docs"))
    state.selected = Some(Seq("docs", "README.md"))
    val buffer = rendered(Tree(nodes), state, 20, 4)
    assert(trimmedLines(buffer) == Seq("▾ src", "    README.md", "▾ docs", "    README.md"))
    assert(buffer.get(4, 3).style.modifiers.hasAny(Modifiers.Reverse))
    assert(!buffer.get(4, 1).style.modifiers.hasAny(Modifiers.Reverse))
