package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent, KeyModifiers, MouseButton, MouseEventKind, Style}
import io.worxbend.tui.widgets as w

// ---- the shared built-in vocabulary ----
//
// The small combinators every element reuses for its layout claim, its focus styling, and the framework key and
// mouse behavior it performs while focused. They own no state of their own — each closes over state the *element*
// (and therefore the app) owns — and they are called only from `builtinKeyHandler`/`builtinMouseHandler`, so they run
// on the render thread like every other part of a frame.

/** How far PageUp/PageDown move a scrollable, in rows. One place to change the page step for every scrollable: the
  * running app's `FrameworkKeys.pageStep`, which `TuiApp` installs around each key dispatch (10 outside a run).
  */
private def PageStep: Int = FrameworkKeys.currentPageStep.value

/** The divider bounds and keyboard step of every [[SplitPaneElement]]: the split is never driven past these, so neither
  * pane can be collapsed away entirely by a drag or by a held `[`/`]`.
  */
private val MinSplitPercent = 10
private val MaxSplitPercent = 90
private val SplitStep       = 5

private def clampSplit(percent: Int): Int = math.max(MinSplitPercent, math.min(MaxSplitPercent, percent))

/** A key table: the listed keys run their action and consume the event, anything else is declined.
  *
  * The alternative — a `match` ending in `case _ => false` — spells out the same fact once per node, and every one of
  * those matches had to remember to return `true` from each branch.
  */
private def keys(table: PartialFunction[KeyEvent, Unit]): BuiltinKeyHandler =
  event => table.lift(event).isDefined

extension (handler: BuiltinKeyHandler)
  /** This handler's keys first, then `next`'s — how a node layers its own keys over a shared vocabulary. */
  private def orElse(next: BuiltinKeyHandler): BuiltinKeyHandler = event => handler(event) || next(event)

/** Focused interactive elements render with the focus style (the theme's, once the focus pass ran) layered over their
  * own, so the user can see where keystrokes go.
  *
  * The other interaction states layer here too, so every node that reads its style through this one helper shows them
  * without a line of its own: a disabled node draws in the disabled style and nothing else — it cannot be focused,
  * hovered or pressed in any way the user should see — and otherwise the focus cue goes on first, the hover cue over it
  * and the pressed cue over that, each only when the node asked for one and the state holds. Pressed wins over hover
  * because a press is the stronger, briefer signal.
  */
private def focusStyled(props: ElementProps): Style =
  if props.isDisabled then props.style.patch(props.focusState.disabledStyle)
  else
    val focused = if props.focused then props.style.patch(props.focusStyle) else props.style
    val hovered = props.hoverStyle.filter(_ => props.hovered).fold(focused)(focused.patch)
    props.pressedStyle.filter(_ => props.pressed).fold(hovered)(hovered.patch)

/** The theme colour a severity draws in — the mapping shared by the `notice`/`badge` factories and the toast stack,
  * which reverses it into a padded badge at its own call site.
  */
private[dsl] def noticeLevelStyle(level: w.NoticeLevel)(using theme: Theme): Style =
  level match
    case w.NoticeLevel.Success => theme.success
    case w.NoticeLevel.Info    => theme.accent
    case w.NoticeLevel.Warning => theme.warning
    case w.NoticeLevel.Error   => theme.error

/** A left-button press activates the control (focus already moved on the press).
  *
  * Only the left button. A right-click over a control has to fall through — the handler returns `false`, so the event
  * keeps bubbling — or an application could never put a context menu on a button: the button would fire first.
  */
private def clickActivates(activate: () => Unit): BuiltinMouseHandler =
  (event, _) =>
    if event.kind == MouseEventKind.Down && event.button == MouseButton.Left then
      activate()
      true
    else false

/** Wheel events scroll by one step. */
private def wheelScrolls(up: () => Unit, down: () => Unit): BuiltinMouseHandler =
  (event, _) =>
    event.kind match
      case MouseEventKind.ScrollUp   =>
        up()
        true
      case MouseEventKind.ScrollDown =>
        down()
        true
      case _                         => false

/** Space/Enter activates a two-state control. Only reached while the element is focused — [[EventRouter]] gates every
  * built-in key handler on that.
  */
private def toggleOnActivate(activate: () => Unit): BuiltinKeyHandler =
  event =>
    event.code match
      case KeyCode.Char(' ') | KeyCode.Enter =>
        activate()
        true
      case _                                 => false

/** The selection key vocabulary shared by every element with a moving highlight (lists, trees, menus, tables): Up/Down
  * move the selection one entry. `next`/`previous` do the moving on the caller-owned state. Anything else is left
  * unconsumed so an element can layer its own keys on top and fall through to this.
  */
private def selectionKeys(next: () => Unit, previous: () => Unit): BuiltinKeyHandler =
  event =>
    event.code match
      case KeyCode.Down =>
        next()
        true
      case KeyCode.Up   =>
        previous()
        true
      case _            => false

/** The jump half of the selection vocabulary: Home and End go to the ends of the list, PageUp and PageDown move the
  * highlight [[PageStep]] entries at a time.
  *
  * It is a second combinator layered over [[selectionKeys]] with `.orElse` rather than four more thunks on that one,
  * because only the elements whose state addresses a row *by index* can answer these. The tree and menu nodes address a
  * row by the value it shows — a node path, a file path — and have no index to jump to, so they keep the Up/Down pair
  * on its own. Reusing [[PageStep]] keeps a list page and a scroll-viewport page the same size.
  */
private def selectionJumpKeys(first: () => Unit, last: () => Unit, by: Int => Unit): BuiltinKeyHandler =
  keys {
    case KeyEvent(KeyCode.Home, _)     => first()
    case KeyEvent(KeyCode.End, _)      => last()
    case KeyEvent(KeyCode.PageDown, _) => by(PageStep)
    case KeyEvent(KeyCode.PageUp, _)   => by(-PageStep)
  }

/** The scrolling key vocabulary shared by every viewport-shaped element: Up/Down move one row, PageUp/PageDown move
  * [[PageStep]] rows. `up`/`down` are handed the row count to move and do the scrolling on the caller-owned state;
  * anything else is left unconsumed so it keeps bubbling.
  */
private def scrollKeys(up: Int => Unit, down: Int => Unit): BuiltinKeyHandler =
  event =>
    event.code match
      case KeyCode.Up       =>
        up(1)
        true
      case KeyCode.Down     =>
        down(1)
        true
      case KeyCode.PageUp   =>
        up(PageStep)
        true
      case KeyCode.PageDown =>
        down(PageStep)
        true
      case _                => false

/** The jump half of the scrolling vocabulary: Home and End go to the top and bottom of the content.
  *
  * Layered over [[scrollKeys]] with `.orElse`, the same way [[selectionJumpKeys]] layers over [[selectionKeys]], so a
  * viewport that has no notion of its own bottom can keep the Up/Down/Page pair alone.
  */
private def scrollJumpKeys(first: () => Unit, last: () => Unit): BuiltinKeyHandler =
  keys {
    case KeyEvent(KeyCode.Home, _) => first()
    case KeyEvent(KeyCode.End, _)  => last()
  }

/** The caret and erase key vocabulary shared by every single-line text field: Backspace/Delete erase either side of the
  * caret, Left/Right/Home/End move it. `state` is the caller-owned editing state the keys mutate; anything else is left
  * unconsumed, which is what lets a field layer its own `Char` handling on top and fall through to this.
  */
private def cursorKeys(state: w.TextInputState): BuiltinKeyHandler =
  event =>
    event.code match
      case KeyCode.Backspace =>
        state.backspace()
        true
      case KeyCode.Delete    =>
        state.delete()
        true
      case KeyCode.Left      =>
        state.moveLeft()
        true
      case KeyCode.Right     =>
        state.moveRight()
        true
      case KeyCode.Home      =>
        state.moveHome()
        true
      case KeyCode.End       =>
        state.moveEnd()
        true
      case _                 => false

/** Whether the event is a plain printable keystroke: no modifiers, or Shift alone, which is how capital letters and
  * shifted symbols arrive. The guard every text field applies before inserting a typed character, so a modified chord
  * keeps bubbling to whatever bound it.
  */
private def isPlainTyping(event: KeyEvent): Boolean =
  event.modifiers.isEmpty || event.modifiers == KeyModifiers.Shift

/** Left/Right step from `index` through `size` positions, wrapping at both ends, reporting each move to `moveTo`.
  * `size` is by-name because it is derived from the element's current contents, and nothing is consumed when there is
  * nothing to step through — an empty option list or a tab bar with no pages leaves the arrows free to bubble.
  */
private def stepsWrapping(size: => Int, index: Int, moveTo: Int => Unit): BuiltinKeyHandler =
  event =>
    val count = size
    if count == 0 then false
    else
      event.code match
        case KeyCode.Left  =>
          moveTo((index - 1 + count) % count)
          true
        case KeyCode.Right =>
          moveTo((index + 1) % count)
          true
        case _             => false
