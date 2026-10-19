package io.worxbend.tui.dsl

import io.worxbend.tui.core.{CharWidth, KeyCode, KeyEvent, MouseButton, MouseEventKind, Style, Versioned, Widget}
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
  def of(state: w.AccordionState, sections: Seq[AccordionSection]): AccordionElement =
    val headers = sections.zipWithIndex.map { (section, index) =>
      CollapsibleElement(section.title, section.content, state.open.contains(index), _ => state.toggle(index))
    }
    AccordionElement(ColumnElement(headers), state)

/** One section of an [[Element.accordion]]: the title its header shows and the content it opens onto. */
final case class AccordionSection(title: String, content: Element)

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
    val labels   = Messages.active
    val nextText = if last then labels.wizardFinish else labels.wizardNext
    val back     = ButtonElement(labels.wizardBack, () => state.back()).disabled(current == 0).key("wizard-back")
    val next     = ButtonElement(nextText, advance).key("wizard-next")
    val buttons  = RowElement(
      Seq(back.length(CharWidth.of(labels.wizardBack) + 4), SpacerElement(), next.length(CharWidth.of(nextText) + 4))
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
      focus = Some(children.indexWhere(holdsFocus)).filter(_ >= 0),
      gap = gap,
      divider = divider,
      style = props.style,
    )
  def widget: Widget                                                             = strip
  private[dsl] def withProps(props: ElementProps): ToolbarElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): ToolbarElement = copy(children = children)
  private[dsl] override def claim: SizeClaim                                     = SizeClaim.OneRow
  private[dsl] override def intrinsicWidth(height: Int): Option[Int] = constrainedWidth(strip.widthAt(height))

/** Whether focus sits on `element` or anywhere under it, as the focus pass marked the decorated tree. */
private def holdsFocus(element: Element): Boolean = element.props.focused || element.children.exists(holdsFocus)

/** Whether `element` or anything under it is focusable. */
private def offersFocus(element: Element): Boolean = element.props.focusable || element.children.exists(offersFocus)

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

/** The content of a [[WindowElement]]: transparent to layout and painting. Its `onMouse` — heard by every node on the
  * path as an event bubbles — raises the window on any press inside it, then declines, so the control pressed still
  * gets the press. Built by `window`; application code never sees it.
  */
private[dsl] final case class WindowBodyElement(content: Element, props: ElementProps) extends Element:
  type Self = WindowBodyElement
  override def debugName: String                                                    = "windowBody"
  override def children: Seq[Element]                                               = Seq(content)
  def widget: Widget                                                                = content.widget
  private[dsl] override def layoutOnly: Boolean                                     = true
  private[dsl] def withProps(props: ElementProps): WindowBodyElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): WindowBodyElement =
    children.headOption.fold(this)(child => copy(content = child))

/** A movable, resizable floating window over a caller-owned [[w.WindowState]], drawn by [[w.Window]]: a frame with the
  * title on top and a `◢` grip in the bottom-right corner, its content inside. Place windows in a
  * [[WindowStackElement]] (`windowStack`), which draws them at their state's rectangles in stacking order.
  *
  * Pointer: a press anywhere on the window raises it above the others; a drag that starts on the title row moves it and
  * one that starts on the grip resizes it — the press captures the pointer, so the drag keeps following once the
  * pointer leaves the window. Keyboard, while focus is inside the window: the `window-move` framework key (`Alt+M`)
  * enters a move mode and `window-resize` (`Alt+R`) a resize mode, in which the arrows move or resize the window one
  * cell at a time and `Enter` or `Esc` leave; the mode shows in the title. A window whose content has nothing to focus
  * is a tab stop itself, so it can always be arranged from the keyboard.
  */
final case class WindowElement(
    title: String,
    state: w.WindowState,
    body: Element,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = WindowElement
  override private[dsl] def watchedStates: Seq[Versioned]                       = Seq(state)
  override def debugName: String                                                = "window"
  override def accessibleName: String                                           = s"window $title"
  override def children: Seq[Element]                                           = Seq(body)
  private def frame: w.Window                                                   =
    val active = holdsFocus(this)
    w.Window(title, style = props.style, borderStyle = if active then Style.Default.bold else Style.Default)
  def widget: Widget                                                            =
    val current = frame
    (area, buffer) =>
      current.render(area, buffer, state)
      body.widget.render(current.inner(area), buffer)
  private[dsl] def withProps(props: ElementProps): WindowElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): WindowElement =
    children.headOption.fold(this)(child => copy(body = child))
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler]    = Some(handleMouse)
  private[dsl] override def captureKeyHandler: Option[BuiltinKeyHandler]        =
    Some(event => holdsFocus(this) && handleKey(event))

  private def handleMouse(event: MouseEvent, area: Rect): Boolean =
    event.kind match
      case MouseEventKind.Down if event.button == MouseButton.Left =>
        state.raise()
        val onGrip = event.position.x == area.right - 1 && event.position.y == area.bottom - 1
        if onGrip || event.position.y == area.y then state.startDrag(event.position, resizing = onGrip)
        true
      case MouseEventKind.Drag if state.dragging                   =>
        state.dragTo(event.position)
        true
      case MouseEventKind.Up if state.dragging                     =>
        state.endDrag()
        true
      case _                                                       => false

  private def handleKey(event: KeyEvent): Boolean =
    val keys = FrameworkKeys.current.value
    if keys.fires(keys.windowMove, event) then
      state.mode = if state.mode == w.WindowMode.Moving then w.WindowMode.Normal else w.WindowMode.Moving
      state.raise()
      true
    else if keys.fires(keys.windowResize, event) then
      state.mode = if state.mode == w.WindowMode.Resizing then w.WindowMode.Normal else w.WindowMode.Resizing
      state.raise()
      true
    else if state.mode == w.WindowMode.Normal then false
    else
      val step: (Int, Int) => Unit =
        if state.mode == w.WindowMode.Moving then state.moveBy else state.resizeBy
      event match
        case KeyEvent(KeyCode.Left, _)                            => step(-1, 0); true
        case KeyEvent(KeyCode.Right, _)                           => step(1, 0); true
        case KeyEvent(KeyCode.Up, _)                              => step(0, -1); true
        case KeyEvent(KeyCode.Down, _)                            => step(0, 1); true
        case KeyEvent(KeyCode.Enter | KeyCode.Escape, _)          => state.mode = w.WindowMode.Normal; true
        case KeyEvent(KeyCode.Tab, _)                             => state.mode = w.WindowMode.Normal; false
        case KeyEvent(KeyCode.Char(_), _) if isPlainTyping(event) => true
        case _                                                    => false

private[dsl] object WindowElement:

  /** A window over `content`; a tab stop itself when `content` offers nothing to focus. */
  def of(title: String, state: w.WindowState, content: Element): WindowElement =
    val body = WindowBodyElement(
      content,
      ElementProps(onMouse = Some { event =>
        if event.kind == MouseEventKind.Down then state.raise()
        false
      }),
    )
    WindowElement(title, state, body, ElementProps(focusable = !offersFocus(content)))

/** Floating windows over the area it is given — usually the overlay of a `layers(base, windowStack(...))`. Each
  * [[WindowElement]] is drawn at its state's rectangle, nudged back inside the area when it has been dragged past an
  * edge (the state keeps where it was put), in stacking order: a larger `z` — the window raised last — draws on top and
  * so takes the pointer where windows overlap. Other children draw across the whole area, beneath every window. Paints
  * nothing of its own, so a press between the windows reaches whatever is underneath.
  */
final case class WindowStackElement(override val children: Seq[Element], props: ElementProps = ElementProps())
    extends Element:
  type Self = WindowStackElement
  override def debugName: String                                                     = "windowStack"
  private[dsl] override def layoutOnly: Boolean                                      = true
  def widget: Widget                                                                 =
    (area, buffer) =>
      if !area.isEmpty then
        children.sortBy(child => WindowStackElement.windowOf(child).fold(Long.MinValue)(_.state.z)).foreach { child =>
          WindowStackElement.windowOf(child) match
            case Some(window) => child.widget.render(WindowStackElement.placed(window.state.rect, area), buffer)
            case None         => child.widget.render(area, buffer)
        }
  private[dsl] def withProps(props: ElementProps): WindowStackElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): WindowStackElement = copy(children = children)

private[dsl] object WindowStackElement:

  /** The window a child is, under whatever decorators the focus pass put around it. */
  def windowOf(element: Element): Option[WindowElement] =
    element match
      case window: WindowElement        => Some(window)
      case decorator: DecoratingElement => windowOf(decorator.inner)
      case _                            => None

  /** `rect` (relative to `area`) in screen cells, shrunk to fit `area` and shifted back inside it. */
  def placed(rect: Rect, area: Rect): Rect =
    val width  = math.min(rect.width, area.width)
    val height = math.min(rect.height, area.height)
    val x      = math.max(area.x, math.min(area.x + rect.x, area.right - width))
    val y      = math.max(area.y, math.min(area.y + rect.y, area.bottom - height))
    Rect(x, y, width, height)
