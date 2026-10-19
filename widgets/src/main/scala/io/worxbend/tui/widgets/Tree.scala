package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, StatefulWidget, Style, VersionedState}

import scala.collection.mutable

/** A node of a [[Tree]]: a label plus child nodes, and optionally a stable `id`.
  *
  * The `id` is what [[TreeState.byId]] names the node by — the identity a tree needs when its labels repeat (two
  * `config` directories, two `README.md` files) and must still expand, collapse and highlight one at a time. It never
  * reaches the screen; a node without one is named by its label.
  */
final case class TreeNode(label: String, children: Seq[TreeNode] = Seq.empty, id: Option[String] = None):

  /** The name [[TreeState.byId]] keys this node by: its `id` when it has one, otherwise its label. */
  def stableKey: String = id.getOrElse(label)

/** Caller-owned tree state: which branches are expanded, the selected node, and the scroll offset.
  *
  * `K` is how the state names a node. The positional state `TreeState()` names it by *path* — the child index at each
  * level, `Seq(1, 0)` for the second root's first child — which is right for a tree that never changes shape and wrong
  * for one that does: refresh the nodes with a reordered copy and the same path points at a different node, so the
  * reader's expansion and highlight land on strangers. A keyed state, `TreeState.keyed(_.label)` or
  * [[TreeState.byLabelPath]], names a node by something the node itself carries, so expansion and selection follow the
  * node across a refresh the way a [[DataTableState]] selection follows its [[KeyedRow]] key. Keys are compared with
  * `==` and must be unique within the tree: when two nodes share one, the first in view order is the one navigation
  * treats as selected, and both wear the highlight.
  *
  * Render-thread-only. Every mutation raises `version` (see [[io.worxbend.tui.core.Versioned]]), so a DSL node that
  * paints this state notices a write made outside the event path — a background result applied on the render thread —
  * and schedules the frame that shows it, with no `Signal` write and no `requestRedraw()`.
  *
  * @tparam K
  *   the key type; `Seq[Int]` — the node's path — for the positional `TreeState()`
  */
final class TreeState[K] private (keyOf: (Seq[TreeNode], Seq[Int]) => K) extends VersionedState:
  private var cached: Option[(Seq[TreeNode], Tree.View[K])] = None
  val expanded: mutable.Set[K]                              = new TreeExpansion(() => { cached = None; touch() })
  def selected: Option[K]                                   = selectedValue

  def selected_=(value: Option[K]): Unit =
    selectedValue = value
    touch()

  private var selectedValue: Option[K] = None
  def offset: Int                      = offsetValue

  def offset_=(value: Int): Unit =
    offsetValue = value
    touch()

  private var offsetValue: Int = 0

  def selectNext(nodes: Seq[TreeNode]): Unit =
    moveSelection(nodes, +1)

  def selectPrevious(nodes: Seq[TreeNode]): Unit =
    moveSelection(nodes, -1)

  /** Expands a collapsed selected branch, collapses an expanded one; leaves are untouched, and so is a selection that
    * names no visible row.
    */
  def toggle(nodes: Seq[TreeNode]): Unit =
    val visible = view(nodes)
    selected.flatMap(visible.indices.get).map(visible.rows(_)).foreach { row =>
      if row.isBranch then if expanded.contains(row.key) then expanded -= row.key else expanded += row.key
    }

  private def moveSelection(nodes: Seq[TreeNode], delta: Int): Unit =
    val visible = view(nodes)
    if visible.rows.nonEmpty then
      selected = Selection.by(selected.flatMap(visible.indices.get), visible.rows.size, delta).map(visible.rows(_).key)

  /** One current source identity and expansion generation, owned by the caller rather than the widget description. */
  private[widgets] def view(nodes: Seq[TreeNode]): Tree.View[K] =
    val visible = cachedView(nodes)
    if visible.rows.isEmpty then
      selected = None
      offset = 0
    visible

  private def cachedView(nodes: Seq[TreeNode]): Tree.View[K] =
    cached match
      case Some((source, visible)) if source eq nodes => visible
      case _                                          =>
        val rows = Vector.newBuilder[Tree.Row[K]]
        def walk(siblings: Seq[TreeNode], ancestors: Vector[TreeNode], prefix: Seq[Int]): Unit =
          siblings.iterator.zipWithIndex.foreach { (node, index) =>
            val path  = prefix :+ index
            val chain = ancestors :+ node
            val key   = keyOf(chain, path)
            rows += Tree.Row(path, key, node)
            if expanded.contains(key) then walk(node.children, chain, path)
          }
        walk(nodes, Vector.empty, Seq.empty)
        val entries                                                                            = rows.result()
        // the first row carrying a key is the one it resolves to, so a duplicated key cannot make navigation jump
        val indices                                                                            =
          entries.iterator.zipWithIndex.foldLeft(Map.empty[K, Int]) { case (acc, (row, index)) =>
            if acc.contains(row.key) then acc else acc.updated(row.key, index)
          }
        val visible = Tree.View(entries, indices)
        cached = Some((nodes, visible))
        visible

object TreeState:

  /** The positional state: a node is named by its path, the child index at each level, so `state.expanded += Seq(0)`
    * expands the first root. The right choice for a tree whose shape is fixed; see [[keyed]] for one that is refreshed.
    */
  def apply(): TreeState[Seq[Int]] = new TreeState((_, path) => path)

  /** A state that names a node by `key(node)` — `TreeState.keyed(_.label)` for a tree whose labels are unique — so that
    * expansion and selection survive a refresh that reorders or inserts nodes. Mirrors [[DataTableState.keyed]]. Starts
    * out exactly as [[apply]] does: nothing expanded, nothing selected, scrolled to the top.
    */
  def keyed[K](key: TreeNode => K): TreeState[K] = new TreeState((chain, _) => key(chain.last))

  /** A state keyed by the labels from the root down to the node — `Seq("src", "README.md")` — for the file-like tree
    * where labels repeat across branches but are unique among siblings. The usual answer when [[keyed]] by label alone
    * would collide.
    */
  def byLabelPath: TreeState[Seq[String]] = new TreeState((chain, _) => chain.map(_.label))

  /** A state keyed by each node's [[TreeNode.stableKey]] — its `id` when it carries one, its label otherwise — for a
    * tree whose labels repeat and that cannot lean on sibling-unique labels either: give the nodes ids
    * (`TreeNode("config", id = Some("app/config"))`) and expansion and selection name exactly one node each.
    */
  def byId: TreeState[String] = new TreeState((chain, _) => chain.last.stableKey)

/** A collapsible tree with keyboard-driven selection: branch markers (`▸` collapsed, `▾` expanded), two-column
  * indentation per depth, and ListView-style scroll-to-selection.
  *
  * Works with any [[TreeState]] whatever its key type: the widget only ever asks the state which of its own rows are
  * expanded and selected.
  */
final case class Tree(
    nodes: Seq[TreeNode],
    style: Style = Style.Default,
    highlightStyle: Style = Style.Default.reverse,
) extends StatefulWidget[TreeState[?]]:

  def render(area: Rect, buffer: Buffer, state: TreeState[?]): Unit =
    if !area.isEmpty then paint(area, buffer, state)

  /** The render body, with the state's key type captured once so rows and selection are compared as the same `K`. */
  private def paint[K](area: Rect, buffer: Buffer, state: TreeState[K]): Unit =
    val visible       = state.view(nodes)
    val selectedIndex = state.selected.flatMap(visible.indices.get)
    state.offset = TreeRows.render(
      area,
      buffer,
      visible.rows,
      state.offset,
      selectedIndex,
      row => state.selected.contains(row.key),
      rowText(_, state),
      style,
      highlightStyle,
    )

  private def rowText[K](row: Tree.Row[K], state: TreeState[K]): String =
    val marker =
      if !row.isBranch then "  "
      else if state.expanded.contains(row.key) then "▾ "
      else "▸ "
    val indent = "  ".repeat(row.path.size - 1)
    indent + marker + row.node.label

object Tree:

  private[widgets] final case class Row[K](path: Seq[Int], key: K, node: TreeNode):
    // Only painted rows need a marker; collapsed offscreen lazy children must stay unevaluated.
    lazy val isBranch: Boolean = node.children.nonEmpty
  private[widgets] final case class View[K](rows: Vector[Row[K]], indices: Map[K, Int])

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
