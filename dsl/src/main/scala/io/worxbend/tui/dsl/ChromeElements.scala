package io.worxbend.tui.dsl

import io.worxbend.tui.core.{MouseButton, MouseEventKind, Style, Widget}
import io.worxbend.tui.terminal.PointerShape
import io.worxbend.tui.widgets as w

// ---- application chrome: menus, windows, trails, toolbars, steppers ----

/** A clickable navigation trail over [[w.Breadcrumb]]: a left click on a visible item hands its index to `onSelect`;
  * the `…` fold and the separators select nothing. One row; the trail folds its middle items when it runs out of
  * columns. Not focusable — a breadcrumb is a pointer affordance, and the keyboard reaches the same places through the
  * app's own back binding.
  */
final case class BreadcrumbElement(
    items: Seq[String],
    onSelect: Int => Unit,
    separatorStyle: Style,
    currentStyle: Style,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = BreadcrumbElement
  override def debugName: String                                             = "breadcrumb"
  private def crumb: w.Breadcrumb                                            =
    w.Breadcrumb(items, style = props.style, currentStyle = currentStyle, separatorStyle = separatorStyle)
  def widget: Widget                                                         = crumb
  override def pointerShape: Option[PointerShape]                            =
    props.pointerShape.orElse(Some(PointerShape.Pointer))
  private[dsl] def withProps(props: ElementProps): BreadcrumbElement         = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 = SizeClaim.OneRow
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some { (event, area) =>
      if event.kind == MouseEventKind.Down && event.button == MouseButton.Left then
        crumb.itemAt(area.width, event.position.x - area.x).exists { index =>
          onSelect(index)
          true
        }
      else false
    }
