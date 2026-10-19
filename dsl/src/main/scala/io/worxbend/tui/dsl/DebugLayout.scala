package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Cell, Color, Position, Rect, Style, Widget}

/** Debug borders (research idea B4): with `GLYPHORA_DEBUG_LAYOUT=1` every container on the frame is outlined dimly and
  * every child a `row` or `column` laid out to zero cells is marked in red where it would have started — the cell just
  * past the sibling before it, or the container's first cell when it is the first child. A blank cell there shows a
  * `•`; an occupied one keeps its glyph and takes the red background.
  *
  * The outline never hides content: on a blank cell it draws a thin box glyph, on a cell that already shows something
  * it only dims that cell. It reads the host's [[NodeBounds]] for the frame just painted, so `TuiApp` turns bounds
  * recording on while it is enabled. A child that was not painted under any other kind of container — an inactive tab,
  * a scroll view's off-screen content — is deliberately not marked: not painting it is that container's job.
  *
  * '''Threads.''' Render-thread only; painted by `TuiApp` at the end of the frame it describes.
  */
private[dsl] object DebugLayout:

  /** The environment variable that switches it on. */
  val EnvVar: String = "GLYPHORA_DEBUG_LAYOUT"

  private val OutlineStyle: Style = Style.Default.dim
  private val ZeroStyle: Style    = Style.bg(Color.Red).withFg(Color.White).bold

  /** Whether `env` asks for debug borders: `1`, `true` or `yes`, in any case. */
  def enabled(env: Map[String, String]): Boolean =
    env.get(EnvVar).map(_.trim.toLowerCase(java.util.Locale.ROOT)).exists(Set("1", "true", "yes"))

  /** The overlay for `host`'s last frame, as a widget over the whole frame. */
  def overlay(host: ElementHost): Widget =
    (area, buffer) =>
      val all      = Inspector.nodes(host)
      val withKids =
        host.decoratedTree.fold(Vector.empty[(InspectedNode, Vector[InspectedNode])])(tree => families(tree, all))
      withKids.foreach { case (parent, _) => parent.layout.foreach(rect => outline(buffer, rect.intersection(area))) }
      withKids.foreach { case (parent, kids) => markZeroSized(buffer, area, parent, kids) }

  /** Each painted container with its direct children, pairing the decorated tree with the inspector's flat walk. */
  private def families(
      tree: Element,
      all: Vector[InspectedNode],
  ): Vector[(InspectedNode, Vector[InspectedNode])] =
    var next                                                                                 = 0
    def walk(node: Element): (InspectedNode, Vector[(InspectedNode, Vector[InspectedNode])]) =
      node match
        case viewport: ScrollViewportElement => walk(viewport.inner)
        case _                               =>
          val self   = all(next)
          next += 1
          val walked = node.children.toVector.map(walk)
          val own    = Option.when(walked.nonEmpty && self.layout.isDefined)(self -> walked.map(_._1))
          (self, own.toVector ++ walked.flatMap(_._2))
    walk(tree)._2

  private def markZeroSized(buffer: Buffer, area: Rect, parent: InspectedNode, kids: Vector[InspectedNode]): Unit =
    val horizontal = parent.node match
      case _: RowElement    => Some(true)
      case _: ColumnElement => Some(false)
      case _                => None
    for
      across <- horizontal
      rect   <- parent.layout
    do
      kids.indices.filter(kids(_).layout.isEmpty).foreach { index =>
        val previous = kids.take(index).flatMap(_.layout).lastOption
        val start    = previous.fold(Position(rect.x, rect.y))(prev =>
          if across then Position(prev.right, prev.y) else Position(prev.x, prev.bottom)
        )
        val clamped  = Position(
          math.max(rect.x, math.min(start.x, rect.right - 1)),
          math.max(rect.y, math.min(start.y, rect.bottom - 1)),
        )
        if area.contains(clamped) && rect.contains(clamped) then
          val cell = buffer.get(clamped)
          buffer.set(clamped, if cell.isBlank then Cell("•", ZeroStyle) else cell.copy(style = ZeroStyle))
      }

  /** A thin box on `rect`'s edge cells that are blank; the occupied ones are only dimmed. */
  private def outline(buffer: Buffer, rect: Rect): Unit =
    if rect.width >= 2 && rect.height >= 2 then
      def edge(x: Int, y: Int, glyph: String): Unit =
        val cell = buffer.get(x, y)
        if cell.isBlank then buffer.set(x, y, Cell(glyph, cell.style.patch(OutlineStyle)))
        else buffer.set(x, y, cell.copy(style = cell.style.patch(OutlineStyle)))
      for x <- rect.x + 1 until rect.right - 1 do
        edge(x, rect.y, "─")
        edge(x, rect.bottom - 1, "─")
      for y <- rect.y + 1 until rect.bottom - 1 do
        edge(rect.x, y, "│")
        edge(rect.right - 1, y, "│")
      edge(rect.x, rect.y, "┌")
      edge(rect.right - 1, rect.y, "┐")
      edge(rect.x, rect.bottom - 1, "└")
      edge(rect.right - 1, rect.bottom - 1, "┘")
