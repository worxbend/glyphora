package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Style, UnderlineStyle}

import scala.deriving.Mirror
import scala.reflect.ClassTag

/** How a node a themed factory built outside a view — against the ambient [[Theme.default]] — picks up the app's own
  * theme when it is painted.
  *
  * A themed factory used to copy `theme.border` and friends into the node it built, once, at construction. Inside a
  * view that is the app's theme, because `View` hands it in; but an element built by a top-level `def` or a helper
  * object saw only [[Theme.default]], and an app running `Theme.Light` painted that panel in dark-theme colours without
  * a word. The factory now also records *how* it built the node — the function from a theme to the node — and the focus
  * pass, which already rebuilds every node each frame and knows the app's theme, calls [[rebound]] on it.
  *
  * Rebinding is field by field, so builders applied after the factory are kept: a field still equal to what the factory
  * produced under the default theme is theme-owned and takes the app theme's value; a `Style` field the caller changed
  * is rebased — the caller's own changes (an added `bold`, a new foreground) are layered over the app theme's value
  * rather than over the default's; any other field the caller changed is kept as it is. The node's [[ElementProps]] are
  * merged the same way.
  *
  * A factory handed an explicit theme (`panel(...)(using Theme.HighContrast)`, or any element built inside a view) is
  * pinned to it and never rebound: the theme it was given *is* the decision. Only the ambient default is late-bound.
  *
  * Built on `scala.deriving.Mirror` — compile-time derivation, no runtime reflection — so it stays native-image safe.
  * Render-thread only, like the focus pass that calls it.
  */
final class LateBinding private[dsl] (rebuild: (Element, Theme) => Element):

  /** `node` re-resolved against `theme`, with no late binding left on it. */
  private[dsl] def rebound(node: Element, theme: Theme): Element = rebuild(node, theme)

  /** Every binding equals every other: it is bookkeeping about how a node was built, not part of what it looks like, so
    * two props that draw the same compare equal whether or not one of them can still be rebound.
    */
  override def equals(other: Any): Boolean =
    other match
      case _: LateBinding => true
      case _              => false

  override def hashCode(): Int = 0

  override def toString: String = "LateBinding"

private[dsl] object LateBinding:

  /** Builds a node through `build` under `theme`, recording `build` so the node can be rebound later when `theme` is
    * the ambient default. The one entry point every themed factory goes through.
    */
  inline def themed[N <: Product & Element { type Self = N }](theme: Theme)(build: Theme => N)(using
      mirror: Mirror.ProductOf[N],
      tag: ClassTag[N],
  ): N =
    val node = build(theme)
    if theme ne Theme.default then node
    else node.withProps(node.props.copy(lateBinding = Some(binding(build, mirror))))

  /** [[themed]] for a builder that composes several nodes — `statusBar`, `topBar`, `helpOverlay`, `whichKeyPopup` — and
    * so has no single product node to merge field by field. The root of what `build` returns carries the binding;
    * rebinding builds the whole subtree again under the app's theme and keeps what was applied to the root after the
    * call (a `.key`, a `.length`, an added style) by merging its props the way [[themed]] merges a node's. A builder
    * applied to an inner node cannot be reached from outside the call, so there is nothing else to keep.
    */
  def composite(theme: Theme)(build: Theme => Element): Element =
    val node = build(theme)
    if theme ne Theme.default then node
    else
      val late = LateBinding { (current, ambient) =>
        val before = build(Theme.default)
        val after  = build(ambient)
        after.withProps(mergedProps(current.props, before.props, after.props))
      }
      node.withProps(node.props.copy(lateBinding = Some(late)))

  /** The node a frame should paint: `node` rebound to `ambient` when it was built against the ambient default and the
    * frame's theme is another one, `node` itself otherwise.
    */
  def resolve(node: Element, ambient: Theme): Element =
    node.props.lateBinding match
      case Some(late) if ambient ne Theme.default => late.rebound(node, ambient)
      case _                                      => node

  private def binding[N <: Product & Element { type Self = N }](build: Theme => N, mirror: Mirror.ProductOf[N])(using
      ClassTag[N]
  ): LateBinding =
    LateBinding { (node, theme) =>
      node match
        case current: N =>
          val before  = build(Theme.default)
          val after   = build(theme)
          val fields  = Array.tabulate(current.productArity) { index =>
            merged(current.productElement(index), before.productElement(index), after.productElement(index))
          }
          // `fromProduct` is the mirror's compile-time constructor; the props slot was merged as a value like any other
          val rebuilt = mirror.fromProduct(Tuple.fromArray(fields))
          rebuilt.withProps(mergedProps(current.props, before.props, after.props))
        case other      => other
    }

  /** One field: theme-owned when the caller left it as the factory made it, rebased when it is a style the caller
    * changed, the caller's own otherwise.
    */
  private def merged(current: Any, before: Any, after: Any): Any =
    if current == before then after
    else
      (current, before, after) match
        case (c: Style, b: Style, a: Style)                      => rebase(c, b, a)
        case (Some(c: Style), Some(b: Style), Some(a: Style))    => Some(rebase(c, b, a))
        case (c: ElementProps, b: ElementProps, a: ElementProps) => mergedProps(c, b, a)
        case _                                                   => current

  /** The props merged field by field, keeping whatever the focus pass and the builders own, with no binding left. */
  private def mergedProps(current: ElementProps, before: ElementProps, after: ElementProps): ElementProps =
    current.copy(
      style = rebase(current.style, before.style, after.style),
      glyphs = if current.glyphs == before.glyphs then after.glyphs else current.glyphs,
      lateBinding = None,
    )

  /** `value` — which started life as `before` — moved onto `after`: the caller's changes to `before` layered over
    * `after`. A value the caller never touched is `after` exactly.
    */
  private[dsl] def rebase(value: Style, before: Style, after: Style): Style =
    if value == before then after
    else
      val removed = before.modifiers.without(value.modifiers)
      val delta   = Style(
        fg = value.fg.filter(_ => value.fg != before.fg),
        bg = value.bg.filter(_ => value.bg != before.bg),
        modifiers = value.modifiers.without(before.modifiers),
        link = value.link.filter(_ => value.link != before.link),
        underlineColor = value.underlineColor.filter(_ => value.underlineColor != before.underlineColor),
        underlineStyle =
          if value.underlineStyle == before.underlineStyle then UnderlineStyle.None else value.underlineStyle,
        clearedModifiers = value.clearedModifiers.without(before.clearedModifiers) | removed,
      )
      after.patch(delta)
