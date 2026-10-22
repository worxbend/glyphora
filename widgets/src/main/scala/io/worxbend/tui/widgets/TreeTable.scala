package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Constraint, Direction, Flex, Layout, Rect, StatefulWidget, Style}

/** A tree whose rows carry columns: [[Tree]]'s expandable hierarchy in the first column, and a [[DataTable]]-style grid
  * of cells beside it — a file browser with sizes, a process tree with CPU and memory, a dependency tree with versions.
  *
  * Expansion, selection and scrolling are a [[TreeState]], exactly as for a [[Tree]], so the same keyed state can drive
  * either widget and a refresh that reorders the nodes keeps the reader's place. The cells of a row come from `cells`,
  * called with the row's *key* in that state — a node path for the positional `TreeState()`, whatever `TreeState.keyed`
  * extracts otherwise — so an application keeps its records in a `Map` by that key and looks them up, rather than
  * smuggling them into node labels. It is called only for the rows on screen.
  *
  * The header row is pinned; the body scrolls to keep the selection visible. Columns are laid out by the
  * [[io.worxbend.tui.core.Layout Layout]] solver from `widths`, and every cell is cut to its column through
  * `CharWidth`, so a CJK or emoji cell never spills a half character into its neighbour. A row with fewer cells than
  * columns leaves the rest blank; extra cells are ignored.
  *
  * @tparam K
  *   the state's key type
  * @param nodes
  *   the roots
  * @param columns
  *   the header titles; the first is the tree column's
  * @param cells
  *   the cells after the tree column, by row key
  * @param widths
  *   one constraint per column, the tree column first; empty means equal shares
  * @param columnSpacing
  *   blank columns between two columns
  * @param style
  *   the body rows
  * @param headerStyle
  *   the header row
  * @param highlightStyle
  *   layered over the selected row, every column of it
  */
final case class TreeTable[K](
    nodes: Seq[TreeNode],
    columns: Seq[String],
    cells: K => Seq[String],
    widths: Seq[Constraint],
    columnSpacing: Int = 1,
    style: Style = Style.Default,
    headerStyle: Style = Style.Default.bold,
    highlightStyle: Style = Style.Default.reverse,
) extends StatefulWidget[TreeState[K]]:

  def render(area: Rect, buffer: Buffer, state: TreeState[K]): Unit =
    val visible    = state.view(nodes)
    val selected   = state.selected.flatMap(visible.indices.get)
    val bodyHeight = math.max(0, area.height - 1)
    state.offset = ScrollWindow.offsetFor(state.offset, selected, visible.rows.size, math.max(1, bodyHeight))
    if !area.isEmpty then
      val constraints = TableColumns.resolve(widths, Iterator(columns.size), area.width)
      val layout      = Layout(Direction.Horizontal, constraints, columnSpacing, Flex.Start)
      drawRow(buffer, layout.split(area.copy(height = 1)), columns, headerStyle)
      visible.rows.slice(state.offset, state.offset + bodyHeight).zipWithIndex.foreach { (row, line) =>
        val rowArea  = Rect(area.x, area.y + 1 + line, area.width, 1)
        val rowStyle = if state.selected.contains(row.key) then style.patch(highlightStyle) else style
        buffer.setStyle(rowArea, rowStyle)
        drawRow(buffer, layout.split(rowArea), treeCell(row, state) +: cells(row.key), rowStyle)
      }

  private def drawRow(buffer: Buffer, segments: Seq[Rect], texts: Seq[String], rowStyle: Style): Unit =
    segments.zip(texts).foreach { (segment, text) =>
      if !segment.isEmpty then
        buffer.setString(segment.x, segment.y, CharWidth.substringByWidth(text, segment.width), rowStyle)
    }

  private def treeCell(row: Tree.Row[K], state: TreeState[K]): String =
    val marker =
      if !row.isBranch then "  "
      else if state.expanded.contains(row.key) then "▾ "
      else "▸ "
    "  ".repeat(row.path.size - 1) + marker + row.node.label
