package io.worxbend.tui.widgets

import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.immutable.AbstractSeq

final class TreeCacheSpec extends AnyFunSuite:
  test("a painted branch marker is cached while collapsed children stay otherwise unread"):
    val children = new CountedNodes(Vector.tabulate(1000)(i => TreeNode(s"child$i")))
    val source   = Vector(TreeNode("branch", children))
    val state    = TreeState()
    assert(trimmedLines(rendered(Tree(source), state, 20, 1)) == Seq("▸ branch"))
    assert(children.reads > 0)
    children.reads = 0
    (1 to 10).foreach { _ =>
      assert(trimmedLines(rendered(Tree(source), state, 20, 1)) == Seq("▸ branch"))
      state.selectNext(source)
    }
    assert(children.reads == 0, "repainting reread children just to derive the branch marker")
    state.expanded += Seq(0)
    assert(trimmedLines(rendered(Tree(source), state, 20, 2)) == Seq("▾ branch", "    child0"))
    assert(children.reads >= 1000)
    state.expanded -= Seq(0)
    assert(trimmedLines(rendered(Tree(source), state, 20, 1)) == Seq("▸ branch"))
    children.reads = 0
    assert(trimmedLines(rendered(Tree(source), state, 20, 1)) == Seq("▸ branch"))
    assert(children.reads == 0)

  private final class CountedNodes(values: Vector[TreeNode]) extends AbstractSeq[TreeNode]:
    var reads: Int                            = 0
    override def isEmpty: Boolean             =
      reads += 1
      values.isEmpty
    override def length: Int                  =
      reads += 1
      values.length
    override def apply(index: Int): TreeNode  =
      reads += 1
      values(index)
    override def iterator: Iterator[TreeNode] = values.iterator.map { node =>
      reads += 1
      node
    }

  test("unchanged renders and navigation reuse caller-owned flattened rows across widget rebuilds"):
    val children = new CountedNodes(Vector.tabulate(2000)(i => TreeNode(s"leaf$i")))
    val source   = new CountedNodes(Vector(TreeNode("branch", children), TreeNode("last")))
    val state    = TreeState()
    state.expanded += Seq(0)
    assert(trimmedLines(rendered(Tree(source), state, 20, 2)) == Seq("▾ branch", "    leaf0"))
    assert(children.reads >= 2000)
    children.reads = 0
    source.reads = 0
    (0 until 10).foreach { _ =>
      state.selectNext(source)
      val _ = rendered(Tree(source), state, 20, 2)
    }
    state.selectPrevious(source)
    assert(state.selected.contains(Seq(0, 7)))
    assert(children.reads == 0, s"unchanged tree read ${children.reads} child nodes")
    assert(source.reads == 0, s"unchanged tree read ${source.reads} root nodes")

  test("offscreen collapsed branches do not force lazy children during flattening or navigation"):
    var forced   = 0
    val children = LazyList.from(0).map { index =>
      forced += 1
      TreeNode(s"child$index")
    }
    val source   = Vector(TreeNode("first"), TreeNode("lazy", children))
    val state    = TreeState()
    assert(trimmedLines(rendered(Tree(source), state, 20, 1)) == Seq("  first"))
    state.selectNext(source)
    assert(forced == 0)
    state.selectNext(source)
    assert(trimmedLines(rendered(Tree(source), state, 20, 1)) == Seq("▸ lazy"))
    assert(forced == 1)

  test("same-size immutable source replacement retires rows and navigation indices"):
    val old   = Vector(TreeNode("old", Vector(TreeNode("old child"))), TreeNode("last"))
    val fresh = Vector(TreeNode("new"), TreeNode("new branch", Vector(TreeNode("new child"))))
    val state = TreeState()
    state.expanded ++= Seq(Seq(0), Seq(1))
    assert(trimmedLines(rendered(Tree(old), state, 20, 3)) == Seq("▾ old", "    old child", "  last"))
    assert(trimmedLines(rendered(Tree(fresh), state, 20, 3)) == Seq("  new", "▾ new branch", "    new child"))
    state.selected = Some(Seq(1))
    state.selectNext(fresh)
    assert(state.selected.contains(Seq(1, 0)))

  test("every direct expansion set mutation invalidates warm rows while no-ops retain them"):
    val values                                         = Vector(
      TreeNode("left", Vector(TreeNode("left leaf"))),
      TreeNode("right", Vector(TreeNode("right leaf"))),
    )
    val source                                         = new CountedNodes(values)
    val state                                          = TreeState()
    val left                                           = Seq(0)
    val right                                          = Seq(1)
    def check(mutate: => Unit, changed: Boolean): Unit =
      val before   = state.view(source)
      source.reads = 0
      mutate
      val after    = state.view(source)
      assert((after eq before) == !changed)
      if !changed then assert(source.reads == 0)
      val expected = Tree.visiblePaths(values, state.expanded.toSet)
      assert(after.rows.map(_.path) == expected)
      state.selected = expected.lastOption
      state.selectPrevious(source)
      assert(state.selected == expected.lift(math.max(0, expected.size - 2)))
      val _        = rendered(Tree(source), state, 20, 4)
    check(state.expanded += left, true)
    check(state.expanded += left, false)
    check(state.expanded -= left, true)
    check(state.expanded -= left, false)
    check(state.expanded ++= Seq(left, right), true)
    check(state.expanded ++= Seq(left, right), false)
    check(state.expanded --= Seq(left, right), true)
    check(state.expanded --= Seq(left, right), false)
    check(state.expanded ++= Seq(left, right), true)
    check(state.expanded.filterInPlace(_ == right), true)
    check(state.expanded.filterInPlace(_ == right), false)
    check(state.expanded.clear(), true)
    check(state.expanded.clear(), false)
