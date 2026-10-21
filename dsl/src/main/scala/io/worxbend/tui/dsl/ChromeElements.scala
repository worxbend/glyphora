package io.worxbend.tui.dsl

import io.worxbend.tui.core.{
  Buffer,
  CharWidth,
  KeyCode,
  KeyEvent,
  MouseButton,
  MouseEvent,
  MouseEventKind,
  Rect,
  Style,
  Versioned,
  Widget,
}
import io.worxbend.tui.terminal.PointerShape
import io.worxbend.tui.widgets as w

// ---- application chrome: menus, windows, trails, toolbars, steppers ----

/** A clickable navigation trail over [[io.worxbend.tui.widgets.Breadcrumb w.Breadcrumb]]: a left click on a visible
  * item hands its index to `onSelect`; the `…` fold and the separators select nothing. One row; the trail folds its
  * middle items when it runs out of columns. Not focusable — a breadcrumb is a pointer affordance, and the keyboard
  * reaches the same places through the app's own back binding.
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

/** A group of `collapsible` sections of which at most one is open — the policy is
  * [[io.worxbend.tui.widgets.AccordionState.toggle w.AccordionState.toggle]]: opening a section closes the open one,
  * toggling the open one closes it. Each header is a tab stop that Enter or Space toggles; a folded body leaves the tab
  * order, exactly as a lone `collapsible`'s does. The open section fills the rows the headers leave.
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

/** An ordered multi-step flow over a caller-owned [[io.worxbend.tui.widgets.WizardState w.WizardState]]: the
  * [[io.worxbend.tui.widgets.Wizard Wizard]] progress header, the refusal message of the last failed `Next` (in the
  * theme's error style), the current step's content, and a `Back` / `Next` row — `Next` reads `Finish` on the last step
  * and runs `onFinish` once that step validates. `Back` is disabled on the first step. The two buttons carry the focus
  * keys `wizard-back` and `wizard-next`, so focus stays on `Next` while the user walks forward.
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
  * widths over [[io.worxbend.tui.widgets.Toolbar w.Toolbar]], `gap` columns apart with an optional `divider`. When they
  * do not all fit, the strip shows the window that does, marked `«` / `»`, and slides it so the focused control is
  * always on screen — the controls themselves stay in the enclosing focus group, so the arrows still reach every one of
  * them.
  *
  * With an `overflow` state the `»` is also a button: a click on it opens a popup, floated through the frame root under
  * the marker, holding the controls the strip left out — the very same elements, so a click on one presses it and
  * closes the popup. A press outside the popup, Esc from the focused control, or another click on `»` closes it. While
  * it is open the hidden controls are children of the popup rather than of the strip, so they keep their place in the
  * focus group but come after the visible ones in it.
  */
final case class ToolbarElement(
    controls: Seq[Element],
    gap: Int,
    divider: Option[String],
    overflow: Option[w.ToolbarState] = None,
    props: ElementProps = ElementProps(),
    decorated: Seq[Element] = Seq.empty,
) extends Element:
  type Self = ToolbarElement
  override private[dsl] def watchedStates: Seq[Versioned] = overflow.toSeq
  override def debugName: String                          = "toolbar"
  private[dsl] override def layoutOnly: Boolean           = overflow.isEmpty

  /** The hidden controls' indices while the overflow popup is open, else empty. */
  private def floated: Seq[Int] = overflow.filter(_.open).fold(Seq.empty)(_.hidden.filter(controls.indices.contains))

  override def children: Seq[Element] =
    if decorated.nonEmpty then decorated
    else
      val hidden = floated
      overflow.filter(_ => hidden.nonEmpty) match
        case Some(state) =>
          val (inPopup, onStrip) = controls.indices.partition(hidden.contains)
          onStrip.map(controls) ++ Seq(
            MenuScrimElement(() => state.close()),
            ToolbarOverflowElement.of(inPopup.map(controls), () => state.close()),
          )
        case None        => controls

  private def strip(items: Seq[Element]): w.Toolbar =
    w.Toolbar(
      items.map(child => RowChildWidget(child, child.widget)),
      focus = Some(items.indexWhere(holdsFocus)).filter(_ >= 0),
      gap = gap,
      divider = divider,
      style = props.style,
    )

  def widget: Widget =
    val shown  = children
    val hidden = floated
    overflow match
      case Some(_) if hidden.nonEmpty && shown.size >= 2 =>
        val current = strip(shown.dropRight(2))
        val popup   = ToolbarOverflowElement(hidden.map(controls))
        (area, buffer) =>
          current.render(area, buffer)
          if !area.isEmpty then
            val marker = area.right - CharWidth.of(OverflowMarker)
            buffer.setString(marker, area.y, OverflowMarker, props.style.patch(Style.Default.dim), area.right - marker)
            if !props.inert then
              val placed = overflowTarget(area, popup.width, popup.height)
              if !placed.isEmpty then
                val frame = popupFrame(area, 1 + popup.height)
                floatPopup(area, buffer, placed, Seq(shown(shown.size - 2) -> frame), shown.last)
      case Some(state)                                   =>
        val current = strip(shown)
        (area, buffer) =>
          state.recordHidden(current.hidden(area.width))
          current.render(area, buffer)
      case None                                          => strip(shown)

  /** Where the popup goes, in screen coordinates: right-aligned under the `»` marker — or above the strip when the
    * frame has less room below than the popup needs and more above — clipped to the frame.
    */
  private def overflowTarget(area: Rect, width: Int, height: Int): Rect =
    val frame      = popupFrame(area, 1 + height)
    val screen     = FrameCoordinates.translate(area)
    val x          = math.max(frame.x, math.min(screen.right, frame.right) - width)
    val below      = frame.bottom - (screen.y + 1)
    val above      = screen.y - frame.y
    val opensBelow = below >= height || below >= above
    val rows       = math.min(height, if opensBelow then below else above)
    Rect(x, if opensBelow then screen.y + 1 else screen.y - rows, math.min(width, frame.width), math.max(0, rows))

  private[dsl] def withProps(props: ElementProps): ToolbarElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): ToolbarElement = copy(decorated = children)
  private[dsl] override def claim: SizeClaim                                     = SizeClaim.OneRow
  private[dsl] override def intrinsicWidth(height: Int): Option[Int] = constrainedWidth(strip(controls).widthAt(height))

  /** Esc closes an open overflow popup, whichever control has focus — offered in the capture phase, because a built-in
    * key handler only ever hears the keys of the focused node itself.
    */
  private[dsl] override def captureKeyHandler: Option[BuiltinKeyHandler] =
    overflow.filter(_.open).map(state => keys { case KeyEvent(KeyCode.Escape, _) => state.close() })

  /** A press on a drawn overflow marker opens or closes the overflow popup: the `»` at the right end while controls
    * follow the window (and always while the popup is open), the `«` at the left end while controls precede it.
    */
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    overflow.map { state => (event, area) =>
      val x        = event.position.x
      val hidden   = state.hidden
      val after    = state.open || hidden.contains(controls.size - 1)
      val before   = !state.open && hidden.contains(0)
      val onAfter  = after && x >= area.right - CharWidth.of(OverflowMarker)
      val onBefore = before && x < area.x + CharWidth.of(OverflowBeforeMarker)
      val pressed  = event.kind == MouseEventKind.Down && event.button == MouseButton.Left
      if pressed && event.position.y == area.y && (onAfter || onBefore) then
        state.toggle()
        true
      else false
    }

  /** The markers `w.Toolbar` draws when controls follow and precede the window — the buttons that open the popup. */
  private val OverflowMarker       = "»"
  private val OverflowBeforeMarker = "« "

/** The popup of a toolbar's overflow menu: a bordered column of the controls the strip left out, one row each at their
  * natural width. Built by the toolbar each frame the popup is open, never by application code. A press on a control is
  * the control's own; the release that follows bubbles here — through the `onMouse` slot [[ToolbarOverflowElement.of]]
  * fills, since an enclosing node's built-in behaviour only ever hears the wheel — and closes the popup.
  */
private[dsl] final case class ToolbarOverflowElement(
    items: Seq[Element],
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = ToolbarOverflowElement
  override def debugName: String      = "toolbarOverflow"
  override def children: Seq[Element] = items

  /** Columns the popup takes: the widest control plus the two border columns. */
  def width: Int = items.map(_.intrinsicWidth(1).getOrElse(1)).maxOption.getOrElse(0) + 2

  /** Rows the popup takes: one per control plus the two border rows. */
  def height: Int = items.size + 2

  def widget: Widget                                                                     =
    (area, buffer) =>
      val block = w.Block(style = props.style)
      block.render(area, buffer)
      val inner = block.inner(area)
      items.zipWithIndex.foreach { (item, row) =>
        if row < inner.height then
          val itemWidth = math.min(inner.width, item.intrinsicWidth(1).getOrElse(inner.width))
          item.widget.render(Rect(inner.x, inner.y + row, itemWidth, 1), buffer)
      }
  private[dsl] def withProps(props: ElementProps): ToolbarOverflowElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): ToolbarOverflowElement = copy(items = children)

  /** A press on the border is spent here, not on whatever lies beneath. */
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some((event, _) => event.kind == MouseEventKind.Down)

private[dsl] object ToolbarOverflowElement:

  /** The popup over `items`, closed by `close` on the release that ends a click anywhere inside it. */
  def of(items: Seq[Element], close: () => Unit): ToolbarOverflowElement =
    val closeOnRelease: MouseEvent => Boolean = event =>
      val released = event.kind == MouseEventKind.Up
      if released then close()
      released
    ToolbarOverflowElement(items, ElementProps(onMouse = Some(closeOnRelease)))

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

/** A one-row menu bar over [[io.worxbend.tui.widgets.MenuBar w.MenuBar]] with drop-downs drawn by the existing
  * [[io.worxbend.tui.widgets.Menu w.Menu]] through the frame root, over everything else. `Alt+<mnemonic>` opens a menu
  * and the `menu-bar` framework key (`F10`) opens the first, whatever holds focus; while a menu is open `Left`/`Right`
  * move to the neighbouring menu, `Up`/`Down` move the highlight, `Enter` runs the item through `onSelect(menu, item)`
  * and closes the bar, `Esc` closes it. A click on a title opens or closes that menu, a click on an item runs it, a
  * click anywhere else closes the bar.
  *
  * The bar is not a tab stop: like the menu bars it imitates, it is reached by its keys and the pointer, and takes the
  * keyboard only while a menu is open (it swallows plain typing then, so letters do not leak into the control
  * underneath). Those keys are taken ahead of the focused control, with one exception that keeps both usable: while a
  * text field holds focus and the bar is closed, the field's own `Alt+B`/`Alt+D`/`Alt+F`/`Alt+Y` chords
  * ([[TextEntryAltChords]]) reach the field rather than a menu with that mnemonic. `F10` (the rebindable `menu-bar`
  * key) opens the bar whatever holds focus, and with the bar open a mnemonic letter — plain or with Alt — moves to its
  * menu; so `F10` then `F` opens `File` from inside a field.
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
          val frame  = popupFrame(area, 1 + current.dropdown(index).heightAt(0).getOrElse(0))
          val screen = FrameCoordinates.translate(area)
          val menu   = current.dropdown(index)
          val width  = math.min(menu.widthAt(0).getOrElse(0), frame.width)
          val start  = current.titleSpan(index).x
          val x      = math.max(frame.x, math.min(screen.x + start, frame.right - width))
          val top    = screen.y + 1
          val height = math.min(menu.heightAt(0).getOrElse(0), frame.bottom - top)
          // the scrim covers the frame above and below the bar's own row, so a press on another title still lands on
          // the bar and switches menus rather than only dismissing the open one
          val scrims = Seq(
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

  /** Whether an `Alt+<letter>` mnemonic yields to the focused text field: the bar is closed, a field that edits text
    * holds focus, and the letter is one of that field's own chords ([[TextEntryAltChords]]).
    */
  private def yieldsToTextField(letter: Int): Boolean =
    openMenu.isEmpty && FrameContext.current.textEntryFocused && Character.isBmpCodePoint(letter) &&
      TextEntryAltChords.contains(Character.toLowerCase(letter.toChar))

  private def handleKey(event: KeyEvent): Boolean =
    val frameworkKeys = FrameworkKeys.current.value
    event match
      case KeyEvent(KeyCode.Char(letter), mods)
          if mods == KeyModifiers.Alt && mnemonic(letter).isDefined && !yieldsToTextField(letter) =>
        mnemonic(letter).foreach(state.openMenu)
        true
      case _ if frameworkKeys.fires(frameworkKeys.menuBar, event) && menus.nonEmpty =>
        if state.open.isDefined then state.close() else state.openMenu(0)
        true
      case _                                                                        =>
        openMenu.exists(index => whileOpen(index, event))

  private def whileOpen(index: Int, event: KeyEvent): Boolean =
    val items = menus(index).items
    event match
      case KeyEvent(KeyCode.Left, _)                                 => state.move(-1, menus.size); true
      case KeyEvent(KeyCode.Right, _)                                => state.move(1, menus.size); true
      case KeyEvent(KeyCode.Up, _)                                   => state.menu.selectPrevious(items); true
      case KeyEvent(KeyCode.Down, _)                                 => state.menu.selectNext(items); true
      case KeyEvent(KeyCode.Enter | KeyCode.Char(' '), _)            =>
        state.menu.selected
          .orElse(Some(items.indexWhere(_.selectable)).filter(_ >= 0))
          .filter(i => items.lift(i).exists(_.selectable))
          .fold(state.close())(item => run(index, item))
        true
      case KeyEvent(KeyCode.Escape, _)                               => state.close(); true
      case KeyEvent(KeyCode.Tab, _)                                  => state.close(); false
      case KeyEvent(KeyCode.Char(letter), _) if isPlainTyping(event) =>
        // while the bar holds the keyboard a plain mnemonic letter moves to its menu, which is how a field's own
        // Alt+letter chords stay reachable: F10, then the letter
        mnemonic(letter).foreach(next => if next != index then state.openMenu(next))
        true
      case _                                                         => false

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
  * the existing [[io.worxbend.tui.widgets.Menu w.Menu]] over `items` at the cell clicked, drawn through the frame root
  * and flipped to open leftwards or upwards near the frame's edges. See
  * [[io.worxbend.tui.widgets.ContextMenu.placement]]. While it is open `Up`/`Down` move the highlight whatever holds
  * focus, `Enter` (or a click on an item) hands the item's index to `onSelect` and closes it, and `Esc`, `Tab` or a
  * click elsewhere close it. Lays out, measures and paints exactly as `target`.
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

/** A movable, resizable floating window over a caller-owned [[io.worxbend.tui.widgets.WindowState w.WindowState]],
  * drawn by [[io.worxbend.tui.widgets.Window Window]]: a frame with the title on top and a `◢` grip in the bottom-right
  * corner, its content inside. Place windows in a [[WindowStackElement]] (`windowStack`), which draws them at their
  * state's rectangles in stacking order.
  *
  * Pointer: a press anywhere on the window raises it above the others; a drag that starts on the title row moves it and
  * one that starts on the grip resizes it — the press captures the pointer, so the drag keeps following once the
  * pointer leaves the window. Keyboard, while focus is inside the window: the `window-move` framework key (`Alt+M`)
  * enters a move mode and `window-resize` (`Alt+R`) a resize mode, in which the arrows move or resize the window one
  * cell at a time and `Enter` or `Esc` leave; the mode shows in the title. A window whose content has nothing to focus
  * is a tab stop itself, so it can always be arranged from the keyboard.
  *
  * Styled like every container: `frameStyle` is the border and the caption (the theme's `border` from `window`, and
  * bold while focus is inside), and the element's own `style` is the *content* style, layered under what the content
  * draws.
  */
final case class WindowElement(
    title: String,
    state: w.WindowState,
    body: Element,
    props: ElementProps = ElementProps(),
    frameStyle: Style = Style.Default,
) extends Element:
  type Self = WindowElement
  override private[dsl] def watchedStates: Seq[Versioned] = Seq(state)
  override def debugName: String                          = "window"
  override def accessibleName: String                     = s"window $title"
  override def children: Seq[Element]                     = Seq(body)
  private def frame: w.Window                             =
    val border = if holdsFocus(this) then frameStyle.bold else frameStyle
    w.Window(
      title,
      borderStyle = border,
      titleStyle = border.bold,
      borderType = w.BorderType.Plain.degraded(props.glyphs),
      gripSymbol = if props.glyphs == io.worxbend.tui.core.GlyphSupport.Ascii then "+" else "◢",
    )
  def widget: Widget                                      =
    val current = frame
    (area, buffer) =>
      current.render(area, buffer, state)
      val inner = current.inner(area)
      body.widget.render(inner, buffer)
      // under whatever the content drew, as on a panel: a child's own colour wins, a cell nobody styled takes this one
      if props.style != Style.Default then buffer.mapStyle(inner)(props.style.patch)

  /** The style the border and caption are drawn in, replacing the theme's `border` — `.frameStyle(Style.fg(Red))`. */
  def frameStyle(style: Style): WindowElement = copy(frameStyle = style)

  /** The border style rebuilt from the current one — `.frameStyle(_.withFg(Color.Red))` keeps the rest of the theme's.
    */
  def frameStyle(transform: Style => Style): WindowElement       = copy(frameStyle = transform(frameStyle))
  private[dsl] def withProps(props: ElementProps): WindowElement = copy(props = props)
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
  def of(title: String, state: w.WindowState, content: Element, frameStyle: Style): WindowElement =
    val body = WindowBodyElement(
      content,
      ElementProps(onMouse = Some { event =>
        if event.kind == MouseEventKind.Down then state.raise()
        false
      }),
    )
    WindowElement(title, state, body, ElementProps(focusable = !offersFocus(content)), frameStyle)

/** Floating windows over the area it is given — usually the overlay of a `layers(base, windowStack(...))`. Each
  * [[WindowElement]] is drawn at its state's rectangle, nudged back inside the area when it has been dragged past an
  * edge (the state records the actual painted rectangle), in stacking order: a larger `z` — the window raised last —
  * draws on top and so takes the pointer where windows overlap. Other children draw across the whole area, beneath
  * every window. Paints nothing of its own, so a press between the windows reaches whatever is underneath.
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
            case Some(window) =>
              window.state.settle(Size(area.width, area.height))
              child.widget.render(WindowStackElement.placed(window.state.rect, area), buffer)
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
