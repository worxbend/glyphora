package io.worxbend.tui.dsl

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
