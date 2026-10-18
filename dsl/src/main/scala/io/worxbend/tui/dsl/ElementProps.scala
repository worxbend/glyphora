package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Constraint, GlyphSupport, KeyEvent, MouseEvent, Style}
import io.worxbend.tui.terminal.PointerShape

/** The focus bookkeeping the framework writes and an application only reads.
  *
  * `focused` marks the one element keystrokes go to; `inert` marks a subtree a modal layer covers, which takes no key
  * and no mouse event at all and supplies neither a focus path nor a hit-test path; `focusStyle` is the theme's focus
  * cue, resolved once per render and stamped on *every* node rather than only the focused one — a list draws its
  * selected row in it whether or not the keyboard is currently there, so reading it unconditionally is correct. The
  * default here is the widget-level `reverse`, which is what a tree that no focus pass has run over renders with.
  *
  * `hovered` is true on the one input target the pointer was last over — the node a mouse hit resolves to, so a
  * container is hovered only where none of its children is — and is only ever written on a host that receives
  * `MouseEventKind.Moved`, which takes mouse-motion tracking (`RunnerConfig.withMouseMotion`; a `TuiApp` turns it on by
  * itself once a frame contains a node with a hover or pressed style or an explicit `.pointer`). `pressed` is true on
  * the target a button went down on, from that press until the matching release wherever it lands. `disabled` is the
  * effective flag `.disabled(true)` sets on a node *and every node under it*: the node leaves the tab order, its
  * handlers and built-ins are not run, and it draws in `disabledStyle` — the theme's `disabled` style, stamped on every
  * node the way `focusStyle` is.
  *
  * The constructor is package-private on purpose. [[FocusPass]] rewrites this on every render and [[EventRouter]]
  * honours what it finds, so an application able to build one could hand itself focus that the router would then
  * respect. There is no supported way to construct one outside the framework.
  */
final case class FocusState private[dsl] (
    focused: Boolean = false,
    inert: Boolean = false,
    focusStyle: Style = Style.Default.reverse,
    hovered: Boolean = false,
    pressed: Boolean = false,
    disabled: Boolean = false,
    disabledStyle: Style = Style.Default.dim,
)

/** The cross-cutting properties every [[Element]] carries: its style, an optional layout constraint (how much space it
  * claims inside a `row`/`column`/`panel`), its event handlers, and focus participation. Styling and layout extension
  * methods produce a new element with updated props — elements stay immutable values.
  *
  * `focusable` opts the element into tab-order traversal, and `autofocus` asks for the keyboard the frame the element
  * first appears in — see [[AutofocusRequest]] for why "first appears" and not "every frame". Everything the framework
  * itself sets each render lives in [[focusState]], which user code can read but not build.
  *
  * `onKeyUp` is the handler `.onKeyRelease(...)` installs. It is separate from `onKey` rather than a flag on it,
  * because a release is a different event and an element that wants both wants two bodies: firing the press handler
  * again on the way up would double every action the element takes.
  *
  * `glyphs` is the glyph ceiling the themed factory that built this element read off the [[Theme]] — see
  * [[io.worxbend.tui.core.GlyphSupport]]. It rides on the props rather than on each node so that a fluent builder such
  * as `panel(...).borderType(BorderType.Thick)` keeps it: the ceiling belongs to the terminal the app is running on,
  * not to the border the author picked, so an explicit choice still degrades on a terminal that cannot draw it. An
  * element built outside a themed factory keeps the permissive default and degrades nothing.
  *
  * `caretStyle` is read only by the text-entry nodes: whether, while focused, they paint their cursor cell, report it
  * for the terminal's own cursor to follow, or both — see [[CaretStyle]]. It rides on the props so one
  * `.caretStyle(...)` builder serves every text node rather than each carrying a field of its own.
  *
  * `pointerShape` is the mouse pointer `.pointer(shape)` asked for over this element — an *explicit* request, which
  * wins over the default an interactive node declares through `Element.pointerShape` and, unlike that default, also
  * covers the node's children. `None` means "whatever the node itself says".
  *
  * `disabled` is what `.disabled(cond)` writes — the author's word, on this node. The *effective* state, which also
  * covers every descendant of a disabled node, is `focusState.disabled`, and that is what the router and the widgets
  * read. `hoverStyle` and `pressedStyle` are the optional cues `.hoverStyle(...)`/`.pressedStyle(...)` install, layered
  * over the node's style while `focusState.hovered`/`focusState.pressed` holds; `None` draws nothing extra, so an app
  * that never asks for them never pays for mouse-motion tracking either.
  */
final case class ElementProps(
    style: Style = Style.Default,
    constraint: Option[Constraint] = None,
    onKey: Option[KeyEvent => Boolean] = None,
    onMouse: Option[MouseEvent => Boolean] = None,
    onKeyUp: Option[KeyEvent => Boolean] = None,
    onPaste: Option[String => Boolean] = None,
    focusable: Boolean = false,
    autofocus: Boolean = false,
    focusKey: Option[String] = None,
    focusState: FocusState = FocusState(),
    glyphs: GlyphSupport = GlyphSupport.Full,
    caretStyle: CaretStyle = CaretStyle.Both,
    pointerShape: Option[PointerShape] = None,
    disabled: Boolean = false,
    hoverStyle: Option[Style] = None,
    pressedStyle: Option[Style] = None,
):

  /** Whether this element is the one the focus pass marked — where keystrokes go this frame. */
  private[dsl] def focused: Boolean = focusState.focused

  /** Whether this element sits on a layer a modal covers, and so takes no input at all. */
  private[dsl] def inert: Boolean = focusState.inert

  /** The focus cue the theme resolved for this render. */
  private[dsl] def focusStyle: Style = focusState.focusStyle

  /** Whether this element, or an ancestor, was `.disabled(true)` — the effective state the focus pass wrote. */
  private[dsl] def isDisabled: Boolean = focusState.disabled

  /** The pointer is resting over this element — see [[FocusState.hovered]]. */
  private[dsl] def hovered: Boolean = focusState.hovered

  /** A button is down on this element — see [[FocusState.pressed]]. */
  private[dsl] def pressed: Boolean = focusState.pressed
