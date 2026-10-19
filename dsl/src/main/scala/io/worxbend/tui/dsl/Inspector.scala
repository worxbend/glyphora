package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, CharWidth, Color, Line, Position, Rect, Style, Widget}
import io.worxbend.tui.widgets as w

/** What the F12 inspector shows: nothing, the node card, or the node card beside the element tree. Each press of the
  * `inspector` framework key moves to the next mode, and the last one wraps back to `Off`.
  */
private[dsl] enum InspectorMode:
  case Off, Node, NodeAndTree

  def next: InspectorMode =
    this match
      case Off         => Node
      case Node        => NodeAndTree
      case NodeAndTree => Off

/** One node of the last frame's decorated tree as the inspector sees it: the themed node with its focus-pass wrappers
  * peeled off, its tab index, and where it was laid out (`None` when it was not painted on that frame).
  */
private[dsl] final case class InspectedNode(
    node: Element,
    focusIndex: Option[Int],
    layout: Option[Rect],
    layoutOnly: Boolean,
):

  /** The node card: type and key, bounds, constraint, size claim, measured height and focus index. */
  def describe: Vector[String] =
    val props  = node.props
    val header = (Vector(node.debugName) ++ props.focusKey.map(key => s"key=$key")).mkString(" ")
    val height = layout.flatMap(rect => node.intrinsicHeight(rect.width)).fold("-")(_.toString)
    Vector(
      header,
      s"bounds ${layout.fold("-")(rect => s"${rect.x},${rect.y} ${rect.width}x${rect.height}")}",
      s"constraint ${props.constraint.fold("-")(_.toString)}",
      s"claim  ${node.claim.vertical} x ${node.claim.horizontal}",
      s"height $height",
      s"focus  ${focusIndex.fold("-")(_.toString)}",
    ) ++ Option.when(props.focused)("focused")

/** The F12 overlay: for the node under the pointer — or the focused one when the pointer is elsewhere or was never seen
  * — a card with its `debugName`, key, painted bounds, constraint, size claim, measured height and focus index; in the
  * second mode the element tree of [[ElementTreeSnapshot]] beside it, with the inspected node's line marked.
  *
  * It reads only what the host already recorded for the frame just painted — the decorated tree and the one
  * [[NodeBounds]] recorder — so it needs bounds recording on, which `TuiApp` switches on while the overlay is open. The
  * inspected node is outlined in place by restyling the cells it painted; its content is left as it was.
  *
  * '''Threads.''' Render-thread only, like the tree it reads: `TuiApp` paints it at the end of the frame it describes.
  */
private[dsl] object Inspector:

  private val CardStyle: Style      = Style.fg(Color.White).withBg(Color.Black)
  private val HighlightStyle: Style = Style.bg(Color.Magenta).withFg(Color.White)
  private val MarkerStyle: Style    = Style.fg(Color.Yellow).withBg(Color.Black).bold

  /** Every node of the host's last frame in the depth-first order [[ElementTreeSnapshot]] prints them. */
  def nodes(host: ElementHost): Vector[InspectedNode] =
    val frame                                      = host.bounds.current
    def walk(node: Element): Vector[InspectedNode] =
      node match
        case viewport: ScrollViewportElement => walk(viewport.inner)
        case _                               =>
          val unwrapped = ElementTreeSnapshot.Unwrapped.of(node)
          val entry     = unwrapped.boundsId.flatMap(frame.entry)
          InspectedNode(
            unwrapped.node,
            unwrapped.focusIndex,
            entry.map(_.layout),
            entry.exists(_.layoutOnly),
          ) +: node.children.toVector.flatMap(walk)
    host.decoratedTree.fold(Vector.empty)(walk)

  /** The index in `all` of the node the inspector describes: the deepest one painted under `pointer` that draws
    * something of its own (else the deepest container there), or the focused node when the pointer is over nothing.
    */
  def targetIndex(all: Vector[InspectedNode], pointer: Option[Position]): Option[Int] =
    val under   =
      pointer.toVector.flatMap(position => all.indices.filter(i => all(i).layout.exists(_.contains(position))))
    val drawing = under.filterNot(i => all(i).layoutOnly)
    drawing.lastOption.orElse(under.lastOption).orElse(Option(all.indexWhere(_.node.props.focused)).filter(_ >= 0))

  /** The overlay for `host`'s last frame in `mode`, as a widget over the whole frame. */
  def overlay(host: ElementHost, mode: InspectorMode, pointer: Option[Position]): Widget =
    (area, buffer) =>
      if mode != InspectorMode.Off && !area.isEmpty then
        val all    = nodes(host)
        val target = targetIndex(all, pointer)
        target.flatMap(all(_).layout).foreach(rect => buffer.setStyle(rect.intersection(area), HighlightStyle))
        val card   = target.fold(Vector(Messages.active.inspectorNothing))(all(_).describe)
        val focus  = target.flatMap(all(_).layout)
        val onTop  = focus.forall(rect => rect.y + rect.height / 2 >= area.y + area.height / 2)
        if mode == InspectorMode.NodeAndTree then
          val tree      = ElementTreeSnapshot.render(host).linesIterator.toVector
          val treeWidth = math.min(area.width / 2, tree.map(CharWidth.of).maxOption.getOrElse(0) + 4)
          val treeArea  = Rect(area.right - treeWidth, area.y, treeWidth, area.height)
          paintBox(buffer, treeArea, Messages.active.inspectorTreeTitle, window(tree, target, treeArea.height - 2))
          paintCard(buffer, Rect(area.x, area.y, area.width - treeWidth, area.height), card, onTop)
        else paintCard(buffer, area, card, onTop)

  /** The card in the right-hand corner of `area` away from the inspected node: the top when it sits low, the bottom
    * otherwise.
    */
  private def paintCard(buffer: Buffer, area: Rect, card: Vector[String], onTop: Boolean): Unit =
    val width  = math.min(area.width, card.map(CharWidth.of).maxOption.getOrElse(0) + 4)
    val height = math.min(area.height, card.size + 2)
    val y      = if onTop then area.y else area.bottom - height
    paintBox(
      buffer,
      Rect(area.right - width, y, width, height),
      Messages.active.inspectorTitle,
      card.map(line => (line, false)),
    )

  /** At most `rows` lines of the tree, scrolled so the inspected node's line is in view, each flagged when it is that
    * line.
    */
  private def window(tree: Vector[String], target: Option[Int], rows: Int): Vector[(String, Boolean)] =
    val marked = tree.zipWithIndex.map((line, index) => (line, target.contains(index)))
    val first  = target.fold(0)(index => math.max(0, math.min(index - rows / 2, tree.size - rows)))
    marked.slice(first, first + math.max(0, rows))

  private def paintBox(buffer: Buffer, box: Rect, title: String, lines: Vector[(String, Boolean)]): Unit =
    if !box.isEmpty then
      w.Clear(CardStyle).render(box, buffer)
      val block = w.Block(
        titles = Seq(w.BlockTitle.top(Line.raw(s" $title "))),
        style = CardStyle,
        borderStyle = CardStyle.dim,
        borderType = w.BorderType.Rounded,
      )
      block.render(box, buffer)
      val inner = block.inner(box)
      lines.take(inner.height).zipWithIndex.foreach { case ((line, marked), row) =>
        val _ = buffer.setString(inner.x, inner.y + row, line, if marked then MarkerStyle else CardStyle, inner.width)
      }
