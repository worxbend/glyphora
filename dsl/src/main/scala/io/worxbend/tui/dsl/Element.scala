package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Constraint, Direction, KeyEvent, Measured, MouseEvent, Rect, Style, Widget}
import io.worxbend.tui.widgets as w

/** Framework key behavior an element performs while focused, `true` when the event is consumed. */
private[dsl] type BuiltinKeyHandler = KeyEvent => Boolean

/** Framework mouse behavior: the event plus the element's rendered area, `true` when the event is consumed. */
private[dsl] type BuiltinMouseHandler = (MouseEvent, Rect) => Boolean

/** Framework paste behavior: the pasted text, `true` when this element took it. */
private[dsl] type BuiltinPasteHandler = String => Boolean

/** A node of the retained-mode UI tree.
  *
  * Every element ultimately renders through a `tui-core` [[Widget]] — the DSL is a declarative layer over
  * `tui-widgets`, never a parallel rendering path. The hierarchy is plain data: construction tests can inspect `props`,
  * `children` and `widget`, and styling extensions rebuild nodes instead of mutating them.
  *
  * The node types' constructors, `copy` methods and pattern extractors are not part of the compatibility contract — the
  * factories (`text`, `panel`, `list`, …) and the fluent builders on them are. A node gains a field whenever its
  * factory gains an option, and before 1.0 a node may become a `final class` behind its factory with no user-visible
  * change, so application code names a node type at most as a result type and never destructures one.
  *
  * The node types themselves live one family per file (`LayoutElements`, `DisplayElements`, …). What closes the
  * hierarchy to outside implementors is the `private[dsl]` on [[withProps]]: no class outside this package can
  * implement a package-private member, so no class outside this package can extend `Element`. That is why the trait is
  * deliberately *not* `sealed` — sealing would add nothing but a rule that all 53 node types share one file.
  */
trait Element:

  /** This node's own type, so a fluent extension gives back what it was handed: `progressBar(0.5).fill.bare`
    * type-checks because `.fill` returns a `ProgressBarElement`, not an abstract `Element`. Every concrete node fixes
    * it to itself with one line, `type Self = ThatNodeType`.
    */
  type Self >: this.type <: Element

  def props: ElementProps
  def style: Style           = props.style
  def widget: Widget
  def children: Seq[Element] = Seq.empty

  /** This node rebuilt with different props — the single write path behind every styling and layout extension. */
  private[dsl] def withProps(props: ElementProps): Self

  /** Containers rebuild with transformed children during the focus pass; leaves ignore it. */
  private[dsl] def withChildren(children: Seq[Element]): Element = this

  /** Framework behavior an interactive element performs when focused (editing, toggling, cycling) — runs after the
    * user's own `onKeyEvent` handler declined the event.
    */
  private[dsl] def builtinKeyHandler: Option[BuiltinKeyHandler] = None

  /** Framework mouse behavior (click-to-activate, wheel scrolling, drags), given the event and the element's rendered
    * area. Runs after the user's `onMouseEvent` declined; only ever called on the hit element.
    */
  private[dsl] def builtinMouseHandler: Option[BuiltinMouseHandler] = None

  /** Framework paste behavior: how a bracketed paste lands in this element while focused. */
  private[dsl] def builtinPasteHandler: Option[BuiltinPasteHandler] = None

  /** The space this element claims inside a container when the user set nothing explicit — one-row widgets claim one
    * row vertically but their natural width (or a fill) horizontally.
    */
  private[dsl] def claim: SizeClaim = SizeClaim.Fill

  /** The rows this element needs at `width`, when knowable — the measurement pass scroll views and auto-sizing
    * containers use. `None` means "unmeasurable", never "zero rows".
    *
    * Three sources are consulted, most authoritative first:
    *
    *   1. an explicit `.length(n)` the caller put on this node — the caller's own answer always wins, and any other
    *      explicit constraint (`.fill`, a percentage) is by definition the container's decision, so it measures as
    *      unmeasurable (that first step is [[constrainedHeight]], which every override below shares);
    *   1. the widget itself, when it implements [[Measured]] — a wrapped paragraph, a markdown document, an animated
    *      text that needs room to bounce all know their own content;
    *   1. this node's default [[claim]], for the many one-row controls whose widget has nothing to add.
    *
    * Delegating to the widget is what stops every node type from hand-wiring a bespoke measurement method for its own
    * widget: a leaf whose widget can measure itself needs no override here at all. Containers still override, because
    * the arithmetic over their children is knowledge no single widget has.
    */
  private[dsl] def intrinsicHeight(width: Int): Option[Int] =
    constrainedHeight(measuredHeight(width).orElse(claimedHeight))

  /** The height an explicit constraint on this node fixes, or `fallback` when the caller set none.
    *
    * The policy every `intrinsicHeight` shares: an explicit `.length(n)` is the caller's own answer and always wins;
    * any other explicit constraint (`.fill`, a percentage) is by definition the container's decision, so the node
    * measures as unmeasurable. Only what to do with *no* constraint differs per node, and that is `fallback`.
    */
  private[dsl] final def constrainedHeight(fallback: => Option[Int]): Option[Int] =
    props.constraint match
      case Some(Constraint.Length(cells)) => Some(cells)
      case Some(_)                        => None
      case None                           => fallback

  /** What the widget says about its own content, or nothing when it cannot say. */
  private def measuredHeight(width: Int): Option[Int] =
    widget match
      case measured: Measured => measured.heightAt(width)
      case _                  => None

  /** The rows this node's default claim fixes, when the claim fixes one at all. */
  private def claimedHeight: Option[Int] =
    claim.vertical match
      case Constraint.Length(cells) => Some(cells)
      case _                        => None

  private[dsl] final def layoutItem(direction: Direction): w.LayoutItem =
    w.LayoutItem(props.constraint.getOrElse(claim.along(direction)), widget)

/** Every element factory, in one place: `Element.text(...)`, `Element.panel(...)`, and so on. The methods themselves
  * live in [[ElementFactories]] — see there for why — and the package re-exports all of them, so an application that
  * wrote `import io.worxbend.tui.dsl.*` just writes `text(...)`.
  */
object Element extends ElementFactories
