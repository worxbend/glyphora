package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Measured, Rect, StatefulWidget, Style, VersionedState, Widget}

/** Caller-owned state of a collapsible [[Inspect]]: which nodes are folded, and which row is selected.
  *
  * A node is named by its path — the child indices from the root, `Vector()` for the root itself, `Vector(3, 0)` for
  * the first child of the root's fourth — so the state stays valid while the inspected value is re-described each
  * frame, as long as its shape does not change. A path that names nothing (the value changed shape) is simply never
  * drawn. Nothing starts collapsed, and with no selection the root row counts as selected.
  *
  * Render-thread-only. Every mutation raises `version` (see [[io.worxbend.tui.core.Versioned]]), so a write made
  * outside the event path is painted too, with no `Signal` write.
  */
final class InspectState extends VersionedState:

  private var collapsedPaths: Set[Vector[Int]]  = Set.empty
  private var selectedPath: Option[Vector[Int]] = None

  /** The selected row's path; `None` until something is selected, which the inspector draws as the root. */
  def selected: Option[Vector[Int]] = selectedPath

  /** Selects the row at `path`. */
  def select(path: Vector[Int]): Unit =
    selectedPath = Some(path)
    touch()

  /** Whether the node at `path` is folded. */
  def isCollapsed(path: Vector[Int]): Boolean = collapsedPaths.contains(path)

  /** Folds the node at `path`. A selection inside it moves up to `path`, so the selected row is never hidden. */
  def collapse(path: Vector[Int]): Unit =
    collapsedPaths = collapsedPaths + path
    if selectedPath.exists(selected => selected.length > path.length && selected.startsWith(path)) then
      selectedPath = Some(path)
    touch()

  /** Unfolds the node at `path`. */
  def expand(path: Vector[Int]): Unit =
    collapsedPaths = collapsedPaths - path
    touch()

  /** Folds the node at `path` when it is open and unfolds it when it is folded. */
  def toggle(path: Vector[Int]): Unit = if isCollapsed(path) then expand(path) else collapse(path)

/** A tree view of any value, for debugging: the [[PrettyValue]] description of it drawn with `├─`/`└─` guides, one row
  * per node, field names and indices as labels.
  *
  * A node all of whose children are atoms is kept on one row — `Db(host = "localhost", pool = 4)`, `List(1, 2, 3)` — as
  * long as that row is at most `inlineWidth` columns; anything else is expanded, its header (`Db`, `List(3)`) on the
  * node's row and its children below. Atoms are always one row, however wide, and are cut at the area's edge like every
  * other line, by display width through `CharWidth`.
  *
  * The tree is laid out once, when the widget is built, so the inspector is [[io.worxbend.tui.core.Measured Measured]]:
  * one row per line and the widest line's width, at any area size.
  *
  * Rendered with an [[InspectState]] the tree is collapsible: a node the state folds keeps its header row, followed by
  * ` …`, and hides everything under it, and the selected row's label and text are drawn in `highlightStyle`. Only a
  * node drawn with children below it — not one kept on a single row — can fold ([[isExpandable]]); [[visiblePaths]]
  * lists the rows the state leaves showing, top to bottom, for the keys that walk them. The DSL's `inspect(value,
  * state)` drives this with the keyboard and the mouse.
  *
  * Immutable; safe to share between frames and threads. The state is render-thread-only.
  *
  * @param value
  *   what to draw
  * @param inlineWidth
  *   the widest a node's one-row form may be before it is expanded instead; 0 expands every node that has children
  * @param style
  *   values and headers
  * @param labelStyle
  *   layered over `style` on field names, indices and map keys
  * @param guideStyle
  *   the tree guides
  * @param highlightStyle
  *   layered over the selected row's label and text when the inspector is rendered with an [[InspectState]]
  */
final case class Inspect(
    value: PrettyValue,
    inlineWidth: Int = 60,
    style: Style = Style.Default,
    labelStyle: Style = Style.Default,
    guideStyle: Style = Style.Default,
    highlightStyle: Style = Style.Default.reverse,
) extends Widget
    with StatefulWidget[InspectState]
    with Measured:

  private val rows: Vector[Inspect.Row] = layout(_ => false)

  private val expandable: Set[Vector[Int]] = rows.iterator.filter(_.expandable).map(_.path).toSet

  /** The rows drawn when every node `folded` names is collapsed. */
  private def layout(folded: Vector[Int] => Boolean): Vector[Inspect.Row] =
    val out = Vector.newBuilder[Inspect.Row]
    def emit(indent: String, guide: String, label: String, node: PrettyValue, path: Vector[Int]): Unit =
      inlineForm(node) match
        case Some(text)           => out += Inspect.Row(indent + guide, label, text, path, expandable = false)
        case None if folded(path) =>
          out += Inspect.Row(indent + guide, label, Inspect.header(node) + Inspect.Folded, path, expandable = true)
        case None                 =>
          out += Inspect.Row(indent + guide, label, Inspect.header(node), path, expandable = true)
          val children = Inspect.children(node)
          val below    = indent + (if guide.isEmpty then "" else if guide == Inspect.Last then "   " else "│  ")
          children.zipWithIndex.foreach { case ((childLabel, child), index) =>
            val childGuide = if index == children.size - 1 then Inspect.Last else Inspect.Middle
            emit(below, childGuide, childLabel, child, path :+ index)
          }
    emit("", "", "", value, Vector.empty)
    out.result()

  /** Whether the node at `path` is drawn with children below it — the only kind of node that can fold. */
  def isExpandable(path: Vector[Int]): Boolean = expandable.contains(path)

  /** The paths of the rows `state` leaves showing, top to bottom. */
  def visiblePaths(state: InspectState): Vector[Vector[Int]] = layout(state.isCollapsed).map(_.path)

  /** Rows the tree takes with `state`'s nodes folded. */
  def heightFor(state: InspectState): Int = layout(state.isCollapsed).size

  /** The node's one-row form, when it has one that fits: always for an atom, for a node of atoms within the width. */
  private def inlineForm(node: PrettyValue): Option[String] =
    node match
      case PrettyValue.Atom(text) => Some(text)
      case _                      =>
        val atomsOnly = Inspect.children(node).forall {
          case (_, PrettyValue.Atom(_)) => true
          case _                        => false
        }
        Option.when(atomsOnly)(Inspect.flat(node)).filter(CharWidth.of(_) <= math.max(0, inlineWidth))

  override def heightAt(width: Int): Option[Int] = Some(rows.size)
  override def widthAt(height: Int): Option[Int] =
    Some(rows.iterator.map(row => CharWidth.of(row.guide + row.label + row.text)).maxOption.getOrElse(0))

  def render(area: Rect, buffer: Buffer): Unit = draw(area, buffer, rows, None)

  def render(area: Rect, buffer: Buffer, state: InspectState): Unit =
    draw(area, buffer, layout(state.isCollapsed), Some(state.selected.getOrElse(Vector.empty)))

  private def draw(area: Rect, buffer: Buffer, shown: Vector[Inspect.Row], selected: Option[Vector[Int]]): Unit =
    if !area.isEmpty then
      shown.take(area.height).zipWithIndex.foreach { (row, line) =>
        val y         = area.y + line
        var x         = area.x
        val highlight = if selected.contains(row.path) then highlightStyle else Style.Default
        val pieces    = Seq(
          row.guide -> guideStyle,
          row.label -> style.patch(labelStyle).patch(highlight),
          row.text  -> style.patch(highlight),
        )
        pieces.foreach { (text, pieceStyle) =>
          if text.nonEmpty && x < area.right then x += buffer.setString(x, y, text, pieceStyle, area.right - x)
        }
      }

object Inspect:

  private[widgets] final case class Row(
      guide: String,
      label: String,
      text: String,
      path: Vector[Int],
      expandable: Boolean,
  )

  private val Middle = "├─ "
  private val Last   = "└─ "
  private val Folded = " …"

  /** An expanded node's own row: a record's name, a collection's name and size. */
  private def header(node: PrettyValue): String =
    node match
      case PrettyValue.Atom(text)         => text
      case PrettyValue.Seq(name, items)   => s"$name(${items.size})"
      case PrettyValue.Map(name, entries) => s"$name(${entries.size})"
      case PrettyValue.Product(name, _)   => name

  /** An expanded node's children with the label each is introduced by. */
  private def children(node: PrettyValue): Seq[(String, PrettyValue)] =
    node match
      case PrettyValue.Atom(_)            => Seq.empty
      case PrettyValue.Seq(_, items)      => items.zipWithIndex.map((item, index) => s"[$index] " -> item)
      case PrettyValue.Map(_, entries)    => entries.map((key, entry) => s"${flat(key)} -> " -> entry)
      case PrettyValue.Product(_, fields) => fields.map((name, field) => s"$name: " -> field)

  /** The whole node on one row, however deep — the form a map key always takes. */
  private def flat(node: PrettyValue): String =
    node match
      case PrettyValue.Atom(text)            => text
      case PrettyValue.Seq(name, items)      => items.map(flat).mkString(s"$name(", ", ", ")")
      case PrettyValue.Map(name, entries)    =>
        entries.map((key, entry) => s"${flat(key)} -> ${flat(entry)}").mkString(s"$name(", ", ", ")")
      case PrettyValue.Product(name, Seq())  => name
      case PrettyValue.Product(name, fields) =>
        fields.map((label, field) => s"$label = ${flat(field)}").mkString(s"$name(", ", ", ")")
