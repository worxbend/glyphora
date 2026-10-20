package io.worxbend.tui.dsl

/** Positional descendants of keyed containers belong to that container, not to its current tab-order position. Explicit
  * control keys retain their public spelling (focusTo/keyed bounds lookup); only unkeyed controls receive an inherited
  * identity. Key lengths make nested keys containing separators unambiguous.
  */
private[dsl] final case class FocusIdentity(keys: List[String] = Nil, positions: List[Int] = Nil):
  def identify(node: Element): Element =
    if node.props.focusable && node.props.focusKey.isEmpty && keys.nonEmpty then
      val prefix = keys.reverse.map(key => s"${key.length}:$key").mkString("/")
      node.withProps(node.props.copy(focusKey = Some(s"@focus:$prefix#${positions.reverse.mkString("/")}")))
    else node

  def beneath(node: Element): FocusIdentity =
    node.props.focusKey.fold(this)(key => FocusIdentity(key :: keys))

  def child(index: Int): FocusIdentity = copy(positions = index :: positions)
