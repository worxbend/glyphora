package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Constraint, GlyphSupport, KeyEvent, MouseEvent, Style}

/** The focus bookkeeping the framework writes and an application only reads.
  *
  * `focused` marks the one element keystrokes go to; `inert` marks a subtree a modal layer covers, which takes no key
  * and no mouse event at all and supplies neither a focus path nor a hit-test path; `focusStyle` is the theme's focus
  * cue, resolved once per render and stamped on *every* node rather than only the focused one — a list draws its
  * selected row in it whether or not the keyboard is currently there, so reading it unconditionally is correct. The
  * default here is the widget-level `reverse`, which is what a tree that no focus pass has run over renders with.
  *
  * The constructor is package-private on purpose. [[FocusPass]] rewrites this on every render and [[EventRouter]]
  * honours what it finds, so an application able to build one could hand itself focus that the router would then
  * respect. There is no supported way to construct one outside the framework.
  */
final case class FocusState private[dsl] (
    focused: Boolean = false,
    inert: Boolean = false,
    focusStyle: Style = Style.Default.reverse,
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
):

  /** Whether this element is the one the focus pass marked — where keystrokes go this frame. */
  private[dsl] def focused: Boolean = focusState.focused

  /** Whether this element sits on a layer a modal covers, and so takes no input at all. */
  private[dsl] def inert: Boolean = focusState.inert

  /** The focus cue the theme resolved for this render. */
  private[dsl] def focusStyle: Style = focusState.focusStyle
