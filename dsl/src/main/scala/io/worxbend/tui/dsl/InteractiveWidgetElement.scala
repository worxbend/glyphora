package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyEvent, MouseEvent, Rect, Style, Widget}

/** Appearance for a custom leaf, rebuilt from the current focus pass on each frame. */
final case class InteractiveContext(style: Style, focused: Boolean, focusStyle: Style)

/** Default behavior for a caller-owned state. User handlers run first; false leaves the event unconsumed. Mouse bounds
  * are translated, unclipped layout bounds in the event's coordinate space. The router has already checked the pointer
  * against every enclosing viewport and the host boundary. No pointer capture is implied.
  */
final case class InteractiveHandlers[S](
    onKey: (S, KeyEvent) => Boolean = (_: S, _: KeyEvent) => false,
    onPaste: (S, String) => Boolean = (_: S, _: String) => false,
    onMouse: (S, MouseEvent, Rect) => Boolean = (_: S, _: MouseEvent, _: Rect) => false,
)

/** An immutable adapter over Widget/Measured. State and resource lifetime remain the caller's responsibility. `build`
  * must be side-effect-free: measurement can request a widget before painting. Return a widget that mixes in Measured
  * to use the same sizing contract as built-in leaves. It is never wrapped in a measurement-erasing lambda.
  */
final case class InteractiveWidgetElement[S](
    state: S,
    handlers: InteractiveHandlers[S],
    build: (S, InteractiveContext) => Widget,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = InteractiveWidgetElement[S]
  def widget: Widget = build(state, InteractiveContext(style, props.focused, props.focusStyle))
  private[dsl] def withProps(props: ElementProps): InteractiveWidgetElement[S] = copy(props = props)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]       = Some(key => handlers.onKey(state, key))
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler]   =
    Some(text => handlers.onPaste(state, text))
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler]   =
    Some((event, area) => handlers.onMouse(state, event, area))
