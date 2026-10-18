package io.worxbend.tui.dsl

import io.worxbend.tui.core.Widget

/** One tab stop for a set of controls — a toolbar, a row of radio-like buttons, a menu bar — with the arrow keys moving
  * between them (a *roving* tab stop). `Tab` enters the group on the member it was last left on (the first one, the
  * first time), `Right`/`Down` and `Left`/`Up` cycle through the members, wrapping, and the next `Tab` leaves to the
  * next stop after the group. A member that wants an arrow for itself — a slider, a list — still gets it first: the
  * arrows only move focus when the focused member declined them.
  *
  * Laid out as its `content`, which `focusGroup(children*)` makes a `row`; pass a single `column(...)` for a vertical
  * group. Membership is every focusable in the subtree, at any depth, except those inside a nested group, which is a
  * stop of its own. A disabled member is skipped like any disabled control.
  */
final case class FocusGroupElement(content: Element, props: ElementProps = ElementProps()) extends Element:
  type Self = FocusGroupElement
  override def debugName: String                                                    = "focusGroup"
  override def children: Seq[Element]                                               = Seq(content)
  def widget: Widget                                                                = content.widget
  private[dsl] override def layoutOnly: Boolean                                     = true
  private[dsl] override def claim: SizeClaim                                        = content.claim
  private[dsl] override def intrinsicHeight(width: Int): Option[Int]                =
    constrainedHeight(content.intrinsicHeight(width))
  private[dsl] def withProps(props: ElementProps): FocusGroupElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): FocusGroupElement =
    children.headOption.fold(this)(child => copy(content = child))

/** Which focus scopes one focusable sits in: the innermost [[FocusGroupElement]] around it, and every `.trapFocus`
  * subtree around it, innermost first. Ids are pre-order positions in the tree, so they are stable while the tree's
  * shape is — the same bargain an unkeyed focus index makes.
  */
private[dsl] final case class FocusScope(group: Option[Int], traps: List[Int])

private[dsl] object FocusScope:

  val Unscoped: FocusScope = FocusScope(None, Nil)

  /** The scope of every focusable in tab order — aligned index for index with [[FocusPass.focusKeys]], which skips
    * exactly the same inert and disabled subtrees.
    */
  def of(root: Element): Vector[FocusScope] =
    var counter                                                       = 0
    def walk(element: Element, scope: FocusScope): Vector[FocusScope] =
      val id = counter
      counter += 1
      if element.props.inert || element.props.disabled then Vector.empty
      else
        val traps  = if element.props.trapFocus then id :: scope.traps else scope.traps
        val inside = element match
          case _: FocusGroupElement => FocusScope(Some(id), traps)
          case _                    => scope.copy(traps = traps)
        val own    = if element.props.focusable then Vector(inside) else Vector.empty
        own ++ element.children.flatMap(walk(_, inside))
    walk(root, Unscoped)
