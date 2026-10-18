package io.worxbend.tui.dsl

import io.worxbend.tui.core.{
  CharWidth,
  Constraint,
  KeyCode,
  KeyEvent,
  MouseButton,
  MouseEventKind,
  Style,
  Versioned,
  Widget,
}
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

/** A group of `collapsible` sections of which at most one is open — the policy is [[w.AccordionState.toggle]]: opening
  * a section closes the open one, toggling the open one closes it. Each header is a tab stop that Enter or Space
  * toggles; a folded body leaves the tab order, exactly as a lone `collapsible`'s does. The open section fills the rows
  * the headers leave.
  */
final case class AccordionElement(content: Element, state: w.AccordionState, props: ElementProps = ElementProps())
    extends Element:
  type Self = AccordionElement
  override private[dsl] def watchedStates: Seq[Versioned]                          = Seq(state)
  override def debugName: String                                                   = "accordion"
  override def children: Seq[Element]                                              = Seq(content)
  def widget: Widget                                                               = content.widget
  private[dsl] override def layoutOnly: Boolean                                    = true
  private[dsl] override def claim: SizeClaim                                       = content.claim
  private[dsl] def withProps(props: ElementProps): AccordionElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): AccordionElement =
    children.headOption.fold(this)(child => copy(content = child))

private[dsl] object AccordionElement:

  /** One `collapsible` per section, wired to the shared state, stacked in a column. */
  def of(state: w.AccordionState, sections: Seq[(String, Element)]): AccordionElement =
    val headers = sections.zipWithIndex.map { case ((title, body), index) =>
      CollapsibleElement(title, body, state.open.contains(index), _ => state.toggle(index))
    }
    AccordionElement(ColumnElement(headers), state)

/** One screen of a [[Element.wizard]]: its title in the progress header, its content, and the check `Next` runs before
  * leaving it — `Some(message)` refuses and shows the message, `None` lets the user on. The check runs on the render
  * thread when `Next` is pressed, so it may `peek` the signals the step's controls write.
  */
final case class WizardStep(title: String, content: Element, validate: () => Option[String] = () => None)

/** An ordered multi-step flow over a caller-owned [[w.WizardState]]: the [[w.Wizard]] progress header, the refusal
  * message of the last failed `Next` (in the theme's error style), the current step's content, and a `Back` / `Next`
  * row — `Next` reads `Finish` on the last step and runs `onFinish` once that step validates. `Back` is disabled on the
  * first step. The two buttons carry the focus keys `wizard-back` and `wizard-next`, so focus stays on `Next` while the
  * user walks forward.
  */
final case class WizardElement(content: Element, state: w.WizardState, props: ElementProps = ElementProps())
    extends Element:
  type Self = WizardElement
  override private[dsl] def watchedStates: Seq[Versioned]                       = Seq(state)
  override def debugName: String                                                = "wizard"
  override def children: Seq[Element]                                           = Seq(content)
  def widget: Widget                                                            = content.widget
  private[dsl] override def layoutOnly: Boolean                                 = true
  private[dsl] def withProps(props: ElementProps): WizardElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): WizardElement =
    children.headOption.fold(this)(child => copy(content = child))

private[dsl] object WizardElement:

  def of(state: w.WizardState, steps: Seq[WizardStep], onFinish: () => Unit, errorStyle: Style): WizardElement =
    val current  = math.max(0, math.min(steps.size - 1, state.current))
    val last     = current == steps.size - 1
    val header   = WidgetElement(w.Wizard(steps.map(_.title), current), rows = Some(1))
    val error    = state.error.map(message => TextElement(message).style(errorStyle).length(1))
    val body     = steps.lift(current).fold[Element](SpacerElement())(_.content).fill
    val advance  = () =>
      steps.lift(current).flatMap(_.validate()) match
        case Some(message) => state.fail(message)
        case None if last  =>
          state.clearError()
          onFinish()
        case None          => state.next(steps.size)
    val nextText = if last then "Finish" else "Next"
    val back     = ButtonElement("Back", () => state.back()).disabled(current == 0).key("wizard-back")
    val next     = ButtonElement(nextText, advance).key("wizard-next")
    val buttons  = RowElement(
      Seq(back.length(CharWidth.of("Back") + 4), SpacerElement(), next.length(CharWidth.of(nextText) + 4))
    ).length(1)
    WizardElement(ColumnElement(Seq(header) ++ error.toSeq ++ Seq(body, buttons)), state)

/** The strip inside a [[Element.toolbar]] or [[Element.buttonGroup]]: its controls left to right at their natural
  * widths over [[w.Toolbar]], `gap` columns apart with an optional `divider`. When they do not all fit, the strip shows
  * the window that does, marked `«` / `»`, and slides it so the focused control is always on screen — the controls
  * themselves stay in the enclosing focus group, so the arrows still reach every one of them.
  */
final case class ToolbarElement(
    override val children: Seq[Element],
    gap: Int,
    divider: Option[String],
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = ToolbarElement
  override def debugName: String                                                 = "toolbar"
  private[dsl] override def layoutOnly: Boolean                                  = true
  private def strip: w.Toolbar                                                   =
    w.Toolbar(
      children.map(child => RowChildWidget(child, child.widget)),
      focus = Some(children.indexWhere(ToolbarElement.holdsFocus)).filter(_ >= 0),
      gap = gap,
      divider = divider,
      style = props.style,
    )
  def widget: Widget                                                             = strip
  private[dsl] def withProps(props: ElementProps): ToolbarElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): ToolbarElement = copy(children = children)
  private[dsl] override def claim: SizeClaim                                     = SizeClaim.OneRow
  private[dsl] override def intrinsicWidth(height: Int): Option[Int] = constrainedWidth(strip.widthAt(height))

private[dsl] object ToolbarElement:

  /** Whether focus sits on `element` or anywhere under it. */
  def holdsFocus(element: Element): Boolean = element.props.focused || element.children.exists(holdsFocus)

/** A tag (chip) field over a caller-owned [[w.TagInputState]]: committed tags draw as chips that wrap onto further rows
  * through [[w.Flow]], followed by the text being typed. While focused, typing edits the text with every key a text
  * field knows; Enter or a comma commits it as a tag, Backspace on an empty field removes the last tag, and Tab (or
  * Right at the end of the text) accepts the dim suggestion when there is one. `onChange` hears the tags after every
  * commit or removal. An Enter on a blank field, and a Tab with nothing to accept, keep bubbling.
  *
  * Measures as many rows as its chips wrap onto at the width it is given, and reports its caret like every text node.
  */
final case class TagInputElement(
    state: w.TagInputState,
    onChange: Seq[String] => Unit,
    placeholder: String = "",
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = TagInputElement
  override private[dsl] def watchedStates: Seq[Versioned]            = Seq(state)
  override def debugName: String                                     = "tagInput"
  private[dsl] override def componentRole: Option[ComponentRole]     = Some(InputRole)
  override def pointerShape: Option[PointerShape]                    = textPointer(props)
  private def field: w.TagInput                                      =
    w.TagInput(placeholder, showCursor = props.focused && props.caretStyle.paints, style = componentStyled(props))
  def widget: Widget                                                 =
    val current = field
    (area, buffer) =>
      current.render(area, buffer, state)
      reportCaret(props, current.caretAt(area, state))
  private[dsl] def withProps(props: ElementProps): TagInputElement   = copy(props = props)
  private[dsl] override def claim: SizeClaim                         = SizeClaim(Constraint.Auto, Constraint.Fill(1))
  private[dsl] override def intrinsicHeight(width: Int): Option[Int] =
    constrainedHeight(Some(field.heightAt(width, state)))
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     = Some(handleKey)
  private[dsl] override def builtinPasteHandler: Option[BuiltinPasteHandler] = Some { text =>
    if props.focused then
      state.input.insert(singleLineText(text))
      true
    else false
  }

  private def changed(): Unit = onChange(state.tags)

  private def handleKey(event: KeyEvent): Boolean =
    val empty = state.input.value.isEmpty
    event.code match
      case KeyCode.Char(',') if isPlainTyping(event)                                    =>
        if state.commit() then changed()
        true
      case KeyCode.Enter if event.modifiers.isEmpty && state.input.value.trim.nonEmpty  =>
        if state.commit() then changed()
        true
      case KeyCode.Backspace if event.modifiers.isEmpty && empty && state.tags.nonEmpty =>
        state.removeLast()
        changed()
        true
      case KeyCode.Tab if event.modifiers.isEmpty                                       => state.acceptSuggestion()
      case KeyCode.Right if event.modifiers.isEmpty && state.input.cursor == clusters   => state.acceptSuggestion()
      case KeyCode.Char(c) if isPlainTyping(event)                                      =>
        state.input.insert(Character.toString(c))
        true
      case _ => editingChords(state.input)(event) || cursorKeys(state.input)(event)

  private def clusters: Int = CharWidth.clusterCount(state.input.value)
