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

/** The frame-sized layer an open menu floats over: it paints nothing, and a press anywhere outside the menu closes it
  * and goes no further — the way a click away from a menu is spent on dismissing it. Everything but a press is
  * declined. Built by the menu nodes each frame a menu is open, never by application code.
  */
private[dsl] final case class MenuScrimElement(dismiss: () => Unit, props: ElementProps = ElementProps())
    extends Element:
  type Self = MenuScrimElement
  override def debugName: String                                             = "menuScrim"
  def widget: Widget                                                         = (_, _) => ()
  private[dsl] def withProps(props: ElementProps): MenuScrimElement          = copy(props = props)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] = Some { (event, _) =>
    event.kind match
      case MouseEventKind.Down =>
        dismiss()
        true
      case _                   => false
  }

/** Hands each scrim (with its screen rectangle) and then `popup` to the frame root, `popup` at `screenTarget` (screen
  * coordinates) — or, outside a frame, paints the popup in place. `area` is the local area of the node doing the
  * floating, whose screen placement gives the shift back to the local coordinates [[PortalQueue.offer]] expects.
  */
private def floatPopup(
    area: Rect,
    buffer: Buffer,
    screenTarget: Rect,
    scrims: Seq[(Element, Rect)],
    popup: Element,
): Unit =
  val screen = FrameCoordinates.translate(area)
  val shiftX = screen.x - area.x
  val shiftY = screen.y - area.y
  if PortalQueue.isCollecting then
    scrims.foreach((scrim, rect) => if !rect.isEmpty then PortalQueue.offer(rect.offset(-shiftX, -shiftY), scrim))
    PortalQueue.offer(screenTarget.offset(-shiftX, -shiftY), popup)
  else popup.widget.render(screenTarget.offset(-shiftX, -shiftY), buffer)

/** The frame a node floating a popup is drawn in: the host's boundary, or — outside a host — a generous stand-in. */
private def popupFrame(area: Rect, fallbackHeight: Int): Rect =
  val screen = FrameCoordinates.translate(area)
  FrameCoordinates.frameBoundary.getOrElse(Rect(screen.x, screen.y, math.max(area.width, 80), fallbackHeight))

/** A one-row menu bar over [[w.MenuBar]] with drop-downs drawn by the existing [[w.Menu]] through the frame root, over
  * everything else. `Alt+<mnemonic>` opens a menu and the `menu-bar` framework key (`F10`) opens the first, whatever
  * holds focus; while a menu is open `Left`/`Right` move to the neighbouring menu, `Up`/`Down` move the highlight,
  * `Enter` runs the item through `onSelect(menu, item)` and closes the bar, `Esc` closes it. A click on a title opens
  * or closes that menu, a click on an item runs it, a click anywhere else closes the bar.
  *
  * The bar is not a tab stop: like the menu bars it imitates, it is reached by its keys and the pointer, and takes the
  * keyboard only while a menu is open (it swallows plain typing then, so letters do not leak into the control
  * underneath). Those keys are taken ahead of the focused control — so a field's own `Alt+F` word motion yields to a
  * bar with a `File` menu.
  *
  * `floating` is the framework's slot for the decorated scrim and drop-down; application code never sets it.
  */
final case class MenuBarElement(
    menus: Seq[w.MenuBarMenu],
    state: w.MenuBarState,
    onSelect: (Int, Int) => Unit,
    props: ElementProps = ElementProps(),
    floating: Seq[Element] = Seq.empty,
) extends Element:
  type Self = MenuBarElement
  override private[dsl] def watchedStates: Seq[Versioned]         = Seq(state)
  override def debugName: String                                  = "menuBar"
  private def bar: w.MenuBar                                      =
    w.MenuBar(menus, style = props.style, highlightStyle = selectedRowStyle(props))
  private def openMenu: Option[Int]                               = state.open.filter(menus.indices.contains)
  override def children: Seq[Element]                             =
    if floating.nonEmpty then floating
    else
      openMenu.toSeq.flatMap { index =>
        Seq(
          MenuScrimElement(() => state.close()),
          MenuScrimElement(() => state.close()),
          MenuElement(menus(index).items, state.menu, item => run(index, item), ElementProps()),
        )
      }
  def widget: Widget                                              =
    val current = bar
    (area, buffer) =>
      current.render(Rect(area.x, area.y, area.width, math.min(1, area.height)), buffer, state)
      (openMenu, children) match
        case (Some(index), Seq(above, below, popup)) if !props.inert && !area.isEmpty =>
          val frame      = popupFrame(area, 1 + current.dropdown(index).heightAt(0).getOrElse(0))
          val screen     = FrameCoordinates.translate(area)
          val menu       = current.dropdown(index)
          val width      = math.min(menu.widthAt(0).getOrElse(0), frame.width)
          val (start, _) = current.titleSpan(index)
          val x          = math.max(frame.x, math.min(screen.x + start, frame.right - width))
          val top        = screen.y + 1
          val height     = math.min(menu.heightAt(0).getOrElse(0), frame.bottom - top)
          // the scrim covers the frame above and below the bar's own row, so a press on another title still lands on
          // the bar and switches menus rather than only dismissing the open one
          val scrims     = Seq(
            above -> Rect(frame.x, frame.y, frame.width, math.max(0, screen.y - frame.y)),
            below -> Rect(frame.x, top, frame.width, math.max(0, frame.bottom - top)),
          )
          if height > 0 then floatPopup(area, buffer, Rect(x, top, width, height), scrims, popup)
        case _                                                                        => ()
  private[dsl] def withProps(props: ElementProps): MenuBarElement = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): MenuBarElement = copy(floating = children)
  private[dsl] override def claim: SizeClaim                                     = SizeClaim.OneRow
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler]     = Some { (event, area) =>
    if event.kind == MouseEventKind.Down && event.button == MouseButton.Left then
      bar.titleAt(event.position.x - area.x).exists { index =>
        if state.open.contains(index) then state.close() else state.openMenu(index)
        true
      }
    else false
  }
  private[dsl] override def captureKeyHandler: Option[BuiltinKeyHandler]         = Some(handleKey)

  private def run(menu: Int, item: Int): Unit =
    state.close()
    onSelect(menu, item)

  /** The menu a typed code point opens; letters outside the basic plane are never mnemonics. */
  private def mnemonic(codePoint: Int): Option[Int] =
    Option.when(Character.isBmpCodePoint(codePoint))(codePoint.toChar).flatMap(bar.menuFor)

  private def handleKey(event: KeyEvent): Boolean =
    val frameworkKeys = FrameworkKeys.current.value
    event match
      case KeyEvent(KeyCode.Char(letter), mods) if mods == KeyModifiers.Alt && mnemonic(letter).isDefined =>
        mnemonic(letter).foreach(state.openMenu)
        true
      case _ if frameworkKeys.fires(frameworkKeys.menuBar, event) && menus.nonEmpty                       =>
        if state.open.isDefined then state.close() else state.openMenu(0)
        true
      case _                                                                                              =>
        openMenu.exists(index => whileOpen(index, event))

  private def whileOpen(index: Int, event: KeyEvent): Boolean =
    val items = menus(index).items
    event match
      case KeyEvent(KeyCode.Left, _)                            => state.move(-1, menus.size); true
      case KeyEvent(KeyCode.Right, _)                           => state.move(1, menus.size); true
      case KeyEvent(KeyCode.Up, _)                              => state.menu.selectPrevious(items); true
      case KeyEvent(KeyCode.Down, _)                            => state.menu.selectNext(items); true
      case KeyEvent(KeyCode.Enter | KeyCode.Char(' '), _)       =>
        state.menu.selected
          .orElse(Some(items.indexWhere(_.selectable)).filter(_ >= 0))
          .filter(i => items.lift(i).exists(_.selectable))
          .fold(state.close())(item => run(index, item))
        true
      case KeyEvent(KeyCode.Escape, _)                          => state.close(); true
      case KeyEvent(KeyCode.Tab, _)                             => state.close(); false
      case KeyEvent(KeyCode.Char(_), _) if isPlainTyping(event) => true
      case _                                                    => false

/** The target of a [[ContextMenuElement]]: transparent to layout and painting. Its `onMouse` — which, unlike a
  * built-in, every node on the path hears as an event bubbles — takes the right-button press that comes out of whatever
  * was clicked inside `target`. Built by the context menu with that handler in its props; application code never sees
  * it.
  */
private[dsl] final case class ContextTargetElement(target: Element, props: ElementProps) extends Element:
  type Self = ContextTargetElement
  override def debugName: String                                                       = "contextTarget"
  override def children: Seq[Element]                                                  = Seq(target)
  def widget: Widget                                                                   = target.widget
  private[dsl] override def layoutOnly: Boolean                                        = true
  private[dsl] override def claim: SizeClaim                                           = target.claim
  private[dsl] override def intrinsicHeight(width: Int): Option[Int]                   =
    constrainedHeight(target.intrinsicHeight(width))
  private[dsl] override def intrinsicWidth(height: Int): Option[Int]                   =
    constrainedWidth(target.intrinsicWidth(height))
  private[dsl] def withProps(props: ElementProps): ContextTargetElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): ContextTargetElement =
    children.headOption.fold(this)(child => copy(target = child))

/** A right-click menu around `target`: a right-button press anywhere inside it — that nothing inside consumed — opens
  * the existing [[w.Menu]] over `items` at the cell clicked, drawn through the frame root and flipped to open leftwards
  * or upwards near the frame's edges ([[w.ContextMenu.placement]]). While it is open `Up`/`Down` move the highlight
  * whatever holds focus, `Enter` (or a click on an item) hands the item's index to `onSelect` and closes it, and `Esc`,
  * `Tab` or a click elsewhere close it. Lays out, measures and paints exactly as `target`.
  *
  * `decorated` is the framework's slot for the decorated children; application code never sets it.
  */
final case class ContextMenuElement(
    target: Element,
    items: Seq[w.MenuEntry],
    state: w.ContextMenuState,
    onSelect: Int => Unit,
    props: ElementProps = ElementProps(),
    decorated: Seq[Element] = Seq.empty,
) extends Element:
  type Self = ContextMenuElement
  override private[dsl] def watchedStates: Seq[Versioned]                            = Seq(state)
  override def debugName: String                                                     = "contextMenu"
  private[dsl] override def layoutOnly: Boolean                                      = true
  private def menu: w.Menu                                                           = w.Menu(items)
  override def children: Seq[Element]                                                =
    if decorated.nonEmpty then decorated
    else
      val opener = ElementProps(onMouse = Some { event =>
        if event.kind == MouseEventKind.Down && event.button == MouseButton.Right then
          state.openAt(event.position)
          true
        else false
      })
      ContextTargetElement(target, opener) +: (
        if state.isOpen && items.nonEmpty then
          Seq(MenuScrimElement(() => state.close()), MenuElement(items, state.menu, choose, ElementProps()))
        else Seq.empty
      )
  def widget: Widget                                                                 =
    (area, buffer) =>
      children.headOption.foreach(_.widget.render(area, buffer))
      (state.at, children.drop(1)) match
        case (Some(at), Seq(scrim, popup)) if !props.inert =>
          val frame  = popupFrame(area, at.y + menu.heightAt(0).getOrElse(0))
          val target =
            w.ContextMenu.placement(at, menu.widthAt(0).getOrElse(0), menu.heightAt(0).getOrElse(0), frame)
          if !target.isEmpty then floatPopup(area, buffer, target, Seq(scrim -> frame), popup)
        case _                                             => ()
  private[dsl] def withProps(props: ElementProps): ContextMenuElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): ContextMenuElement = copy(decorated = children)
  private[dsl] override def claim: SizeClaim                                         = target.claim
  private[dsl] override def intrinsicHeight(width: Int): Option[Int]                 =
    constrainedHeight(target.intrinsicHeight(width))
  private[dsl] override def intrinsicWidth(height: Int): Option[Int]                 =
    constrainedWidth(target.intrinsicWidth(height))
  private[dsl] override def captureKeyHandler: Option[BuiltinKeyHandler]             =
    Some(event => state.isOpen && whileOpen(event))

  private def choose(item: Int): Unit =
    state.close()
    onSelect(item)

  private def whileOpen(event: KeyEvent): Boolean =
    event match
      case KeyEvent(KeyCode.Up, _)                              => state.menu.selectPrevious(items); true
      case KeyEvent(KeyCode.Down, _)                            => state.menu.selectNext(items); true
      case KeyEvent(KeyCode.Enter | KeyCode.Char(' '), _)       =>
        state.menu.selected
          .orElse(Some(items.indexWhere(_.selectable)).filter(_ >= 0))
          .filter(i => items.lift(i).exists(_.selectable))
          .fold(state.close())(choose)
        true
      case KeyEvent(KeyCode.Escape, _)                          => state.close(); true
      case KeyEvent(KeyCode.Tab, _)                             => state.close(); false
      case KeyEvent(KeyCode.Char(_), _) if isPlainTyping(event) => true
      case _                                                    => false
