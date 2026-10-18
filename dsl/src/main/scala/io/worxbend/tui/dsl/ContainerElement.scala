package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Constraint, CrossAlign, Rect, Style, Widget}

/** What a custom [[ContainerElement]]'s placement function is told about one child: where it sits in the child list,
  * the `.key` it was given, the layout extension it carries, and how big its content measures.
  *
  * The measurements are the same [[Element.intrinsicHeight]] / [[Element.intrinsicWidth]] the built-in containers lay
  * out by, taken with the child's own constraint set aside — the constraint is reported on its own, in [[constraint]],
  * because a custom layout decides for itself what a `.length(n)` means. `None` means "cannot say", never zero.
  *
  * A view of the child, not a copy: built fresh for every paint, read during that paint, on the render thread.
  */
final class ChildInfo private[dsl] (val index: Int, element: Element):

  /** The name the child was given with `.key(name)`, if any. */
  def key: Option[String] = element.props.focusKey

  /** The size the child asked for with `.length(n)`, `.fill`, `.percent(n)`, `.auto`, …, or `None` for no request. */
  def constraint: Option[Constraint] = element.props.constraint

  /** The child's own `.alignSelf(...)`, for a layout that honours one. */
  def alignSelf: Option[CrossAlign] = element.props.alignSelf

  /** The rows the child's content needs at `width`, when it can say. */
  def intrinsicHeight(width: Int): Option[Int] = unconstrained.intrinsicHeight(width)

  /** The columns the child's content needs at `height`, when it can say — display columns, through `CharWidth`. */
  def intrinsicWidth(height: Int): Option[Int] = unconstrained.intrinsicWidth(height)

  private def unconstrained: Element = element.withProps(element.props.copy(constraint = None))

  override def toString: String = s"ChildInfo($index, key = $key, constraint = $constraint)"

/** A container whose layout is the application's own: `container(children*)(place, chrome)`.
  *
  * Every paint, `place` is handed the container's area and one [[ChildInfo]] per child and answers one `Rect` per
  * child, in child order. Each child is painted into its rect clipped to the container's area — a child the answer
  * leaves out (a shorter `Seq`) is not painted, and a rect partly outside the area is cut down to the part inside it.
  * `chrome`, when given, is painted across the whole area first, under the children: a background, a frame, grid lines.
  *
  * Everything that is not placement stays the framework's. The children are ordinary children of the tree, so the focus
  * pass numbers them in child order (the Tab order), wraps each interactive one so the area it was painted into is what
  * the mouse router hit-tests, and records each one's bounds for `boundsOf(key)`. A layout cannot get any of that wrong
  * because it never sees it.
  *
  * `place` runs on the render thread during the paint and must be a pure function of its arguments: it is called again
  * on every frame, and nothing it returns is kept between them. The container cannot measure itself — its size depends
  * on a function it cannot invert — so it fills by default; give it `.length(n)` to fix it.
  */
final case class ContainerElement(
    override val children: Seq[Element],
    place: (Rect, Seq[ChildInfo]) => Seq[Rect],
    chrome: Option[Widget] = None,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = ContainerElement
  override def debugName: String = "container"

  /** A container with a chrome widget paints something of its own; one without only arranges its children. */
  private[dsl] override def layoutOnly: Boolean = chrome.isEmpty

  def widget: Widget =
    (area, buffer) =>
      chrome.foreach(_.render(area, buffer))
      val infos = children.zipWithIndex.map((child, index) => ChildInfo(index, child))
      children.lazyZip(place(area, infos)).foreach { (child, rect) =>
        val target = rect.intersection(area)
        if !target.isEmpty then child.widget.render(target, buffer)
      }
      // the content style goes under whatever the children drew, as on every other container
      if props.style != Style.Default then buffer.mapStyle(area)(props.style.patch)

  private[dsl] def withProps(props: ElementProps): ContainerElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): ContainerElement = copy(children = children)
  private[dsl] override def intrinsicHeight(width: Int): Option[Int]               = constrainedHeight(None)
  private[dsl] override def intrinsicWidth(height: Int): Option[Int]               = constrainedWidth(None)
