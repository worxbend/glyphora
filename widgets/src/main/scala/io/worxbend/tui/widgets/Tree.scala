package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, StatefulWidget, Style}

import scala.collection.mutable

/** A node of a [[Tree]]: a label plus child nodes. */
final case class TreeNode(label: String, children: Seq[TreeNode] = Seq.empty)

/** Caller-owned tree state: which branch paths are expanded, the selected path, and the scroll offset. Paths address
  * nodes by child index at each level (`Seq(1, 0)` = second root's first child).
  *
  * Render-thread-only, and mutating it does not by itself schedule a frame. This is a plain mutable object, invisible
  * to the reactive layer: a background result written straight into it stays off screen until something unrelated
  * happens to repaint. Pair the mutation with a `Signal` write, or call `TuiApp.requestRedraw()` from the same
  * render-thread callback that made it.
  */
final class TreeState:
  private var cached: Option[(Seq[TreeNode], Tree.View)] = None
  val expanded: mutable.Set[Seq[Int]]                    = new TreeExpansion(() => cached = None)
  var selected: Option[Seq[Int]]                         = None
  var offset: Int                                        = 0

  def selectNext(nodes: Seq[TreeNode]): Unit =
    moveSelection(nodes, +1)

  def selectPrevious(nodes: Seq[TreeNode]): Unit =
    moveSelection(nodes, -1)

  /** Expands a collapsed selected branch, collapses an expanded one; leaves are untouched. */
  def toggle(nodes: Seq[TreeNode]): Unit =
    val _ = view(nodes)
    selected.foreach { path =>
      Tree.nodeAt(nodes, path).foreach { node =>
        if node.children.nonEmpty then if expanded.contains(path) then expanded -= path else expanded += path
      }
    }

  private def moveSelection(nodes: Seq[TreeNode], delta: Int): Unit =
    val visible = view(nodes)
    if visible.rows.nonEmpty then
      selected = Selection.by(selected.flatMap(visible.indices.get), visible.rows.size, delta).map(visible.rows(_).path)

  /** One current source identity and expansion generation, owned by the caller rather than the widget description. */
  private[widgets] def view(nodes: Seq[TreeNode]): Tree.View =
    val visible = cachedView(nodes)
    if visible.rows.isEmpty then
      selected = None
      offset = 0
    visible

  private def cachedView(nodes: Seq[TreeNode]): Tree.View =
    cached match
      case Some((source, visible)) if source eq nodes => visible
      case _                                          =>
        val rows                                                  = Vector.newBuilder[Tree.Row]
        def walk(siblings: Seq[TreeNode], prefix: Seq[Int]): Unit =
          siblings.iterator.zipWithIndex.foreach { (node, index) =>
            val path = prefix :+ index
            rows += Tree.Row(path, node)
            if expanded.contains(path) then walk(node.children, path)
          }
        walk(nodes, Seq.empty)
        val entries                                               = rows.result()
        val visible = Tree.View(entries, entries.iterator.zipWithIndex.map { (row, index) => row.path -> index }.toMap)
        cached = Some((nodes, visible))
        visible

/** A collapsible tree with keyboard-driven selection: branch markers (`▸` collapsed, `▾` expanded), two-column
  * indentation per depth, and ListView-style scroll-to-selection.
  */
final case class Tree(
    nodes: Seq[TreeNode],
    style: Style = Style.Default,
    highlightStyle: Style = Style.Default.reverse,
) extends StatefulWidget[TreeState]:

  def render(area: Rect, buffer: Buffer, state: TreeState): Unit =
    if !area.isEmpty then
      val visible       = state.view(nodes)
      val selectedIndex = state.selected.flatMap(visible.indices.get)
      state.offset = TreeRows.render(
        area,
        buffer,
        visible.rows,
        state.offset,
        selectedIndex,
        row => state.selected.contains(row.path),
        rowText(_, state),
        style,
        highlightStyle,
      )

  private def rowText(row: Tree.Row, state: TreeState): String =
    val marker =
      if !row.isBranch then "  "
      else if state.expanded.contains(row.path) then "▾ "
      else "▸ "
    val indent = "  ".repeat(row.path.size - 1)
    indent + marker + row.node.label

object Tree:

  private[widgets] final case class Row(path: Seq[Int], node: TreeNode):
    // Only painted rows need a marker; collapsed offscreen lazy children must stay unevaluated.
    lazy val isBranch: Boolean = node.children.nonEmpty
  private[widgets] final case class View(rows: Vector[Row], indices: Map[Seq[Int], Int])

  /** Depth-first paths of every node whose ancestors are all expanded. */
  def visiblePaths(nodes: Seq[TreeNode], expanded: Set[Seq[Int]]): Seq[Seq[Int]] =
    def walk(siblings: Seq[TreeNode], prefix: Seq[Int]): Seq[Seq[Int]] =
      siblings.zipWithIndex.flatMap { (node, index) =>
        val path  = prefix :+ index
        val below = if expanded.contains(path) then walk(node.children, path) else Seq.empty
        path +: below
      }
    walk(nodes, Seq.empty)

  def nodeAt(nodes: Seq[TreeNode], path: Seq[Int]): Option[TreeNode] =
    path match
      case Seq()            => None
      case Seq(head, tail*) =>
        nodes.lift(head).flatMap { node =>
          if tail.isEmpty then Some(node) else nodeAt(node.children, tail)
        }
