package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Style, Widget}
import io.worxbend.tui.widgets as w

/** A prototyping stand-in: a dashed box with `label` centred in it and the size the layout actually gave it written
  * underneath. Takes whatever room its container hands out, the way the panel it stands in for eventually will, so
  * swapping it for the real content does not move anything around it.
  */
final case class PlaceholderElement(
    label: String,
    labelStyle: Style,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = PlaceholderElement
  override def debugName: String                                      = "placeholder"
  def widget: Widget                                                  = w.Placeholder(label, props.style, labelStyle)
  private[dsl] def withProps(props: ElementProps): PlaceholderElement = copy(props = props)
