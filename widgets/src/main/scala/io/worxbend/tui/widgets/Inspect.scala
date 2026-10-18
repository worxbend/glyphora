package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, CharWidth, Measured, Rect, Style, Widget}

/** A tree view of any value, for debugging: the [[PrettyValue]] description of it drawn with `├─`/`└─` guides, one row
  * per node, field names and indices as labels.
  *
  * A node all of whose children are atoms is kept on one row — `Db(host = "localhost", pool = 4)`, `List(1, 2, 3)` — as
  * long as that row is at most `inlineWidth` columns; anything else is expanded, its header (`Db`, `List(3)`) on the
  * node's row and its children below. Atoms are always one row, however wide, and are cut at the area's edge like every
  * other line, by display width through `CharWidth`.
  *
  * The tree is laid out once, when the widget is built, so the inspector is [[Measured]]: one row per line and the
  * widest line's width, at any area size. Nothing is interactive — for a collapsible view of a large value, convert it
  * to `TreeNode`s and use a [[Tree]].
  *
  * Stateless and immutable; safe to share between frames and threads.
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
  */
final case class Inspect(
    value: PrettyValue,
    inlineWidth: Int = 60,
    style: Style = Style.Default,
    labelStyle: Style = Style.Default,
    guideStyle: Style = Style.Default,
) extends Widget
    with Measured:

  private val rows: Vector[Inspect.Row] =
    val out                                                                         = Vector.newBuilder[Inspect.Row]
    def emit(indent: String, guide: String, label: String, node: PrettyValue): Unit =
      inlineForm(node) match
        case Some(text) => out += Inspect.Row(indent + guide, label, text)
        case None       =>
          out += Inspect.Row(indent + guide, label, Inspect.header(node))
          val children = Inspect.children(node)
          val below    = indent + (if guide.isEmpty then "" else if guide == Inspect.Last then "   " else "│  ")
          children.zipWithIndex.foreach { case ((childLabel, child), index) =>
            emit(below, if index == children.size - 1 then Inspect.Last else Inspect.Middle, childLabel, child)
          }
    emit("", "", "", value)
    out.result()

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

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      rows.take(area.height).zipWithIndex.foreach { (row, line) =>
        val y      = area.y + line
        var x      = area.x
        val pieces = Seq(row.guide -> guideStyle, row.label -> style.patch(labelStyle), row.text -> style)
        pieces.foreach { (text, pieceStyle) =>
          if text.nonEmpty && x < area.right then x += buffer.setString(x, y, text, pieceStyle, area.right - x)
        }
      }

object Inspect:

  private[widgets] final case class Row(guide: String, label: String, text: String)

  private val Middle = "├─ "
  private val Last   = "└─ "

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
