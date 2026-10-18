package io.worxbend.tui.dsl

/** The decorated element tree of the last frame as text — one line per node with its type, key, focus index, the
  * `focused`/`inert` marks, its constraint and the rect it was painted into — for the question a frame dump cannot
  * answer: *why* did that element not get focus, not get the click, not get drawn where it should.
  *
  * {{{
  * column @ 0,0 20x4
  *   text @ 0,0 20x1
  *   row Length(1) @ 0,1 20x1
  *     spacer @ 0,1 10x1
  *     button key=save focus=0 focused Length(10) @ 10,1 10x1
  *   spacer @ 0,2 20x2
  * }}}
  *
  * Each node names itself through [[Element.debugName]] — no reflection anywhere, which is what keeps native images
  * reflect-config-free. The bounds column comes from the host's `NodeBounds` recorder and reads `-` for a node that was
  * not recorded: recording was off for that frame, or the node was scrolled entirely out of view. A node inside a
  * scroll view keeps frame coordinates, so a partly visible one can show a negative `y`.
  *
  * The focus-pass wrappers (`TrackedElement`, `PointerElement`, `BoundsElement`, the scroll viewport) are folded into
  * the node they wrap: the wrapper's bookkeeping becomes the line's `focus=` index and bounds, and the wrapped node's
  * name and props are what is printed.
  *
  * '''Threads.''' The tree belongs to the loop that painted it, so call this on the render thread — from a test,
  * `pilot.readOnRenderThread(ElementTreeSnapshot.render(app))`. The bounds read is of the last published frame.
  */
object ElementTreeSnapshot:

  /** The dump for the frame `host` last rendered, or `"<no frame rendered>"` before its first one. */
  def render(host: ElementHost): String =
    host.decoratedTree.fold("<no frame rendered>")(tree => lines(tree, host.bounds.current, 0).mkString("\n"))

  /** The dump for the frame `app` last rendered, or `"<not running>"` outside a run. */
  def render(app: TuiApp): String =
    app.currentHost.fold("<not running>")(render)

  private def lines(node: Element, frame: NodeBoundsFrame, depth: Int): Vector[String] =
    node match
      case viewport: ScrollViewportElement => lines(viewport.inner, frame, depth)
      case _                               =>
        val unwrapped = Unwrapped.of(node)
        val props     = unwrapped.node.props
        val bounds    = unwrapped.boundsId.flatMap(frame.entry) match
          case Some(entry) => s"${entry.layout.x},${entry.layout.y} ${entry.layout.width}x${entry.layout.height}"
          case None        => "-"
        val parts     =
          Vector(unwrapped.node.debugName) ++
            props.focusKey.map(key => s"key=$key") ++
            unwrapped.focusIndex.map(index => s"focus=$index") ++
            Option.when(props.focused)("focused") ++
            Option.when(props.inert)("inert") ++
            props.constraint.map(_.toString) ++
            Vector(s"@ $bounds")
        ("  " * depth + parts.mkString(" ")) +: node.children.toVector.flatMap(lines(_, frame, depth + 1))

  /** A node with its focus-pass wrappers peeled off: the index its `TrackedElement` carries, the id its `BoundsElement`
    * carries, and the themed node underneath whose name and props are printed.
    */
  private final case class Unwrapped(node: Element, focusIndex: Option[Int], boundsId: Option[Int])

  private object Unwrapped:
    def of(node: Element): Unwrapped =
      node match
        case tracked: TrackedElement => of(tracked.inner).copy(focusIndex = Some(tracked.index))
        case pointer: PointerElement => of(pointer.inner)
        case bounded: BoundsElement  => of(bounded.inner).copy(boundsId = Some(bounded.id))
        case other                   => Unwrapped(other, None, None)
