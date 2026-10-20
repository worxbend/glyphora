package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent, KeyModifiers, MouseEvent, MouseEventKind, Position, Style}
import io.worxbend.tui.terminal.PointerShape

/** Named key constants and constructors, so handlers read `onKey(Key.Up){ … }` instead of matching raw
  * `KeyEvent(KeyCode.Up, _)`. Mirrors terminus's `Key.up` / `Key.controlQ` vocabulary.
  */
object Key:

  val Up: KeyEvent     = KeyEvent.of(KeyCode.Up)
  val Down: KeyEvent   = KeyEvent.of(KeyCode.Down)
  val Left: KeyEvent   = KeyEvent.of(KeyCode.Left)
  val Right: KeyEvent  = KeyEvent.of(KeyCode.Right)
  val Enter: KeyEvent  = KeyEvent.of(KeyCode.Enter)
  val Escape: KeyEvent = KeyEvent.of(KeyCode.Escape)
  val Tab: KeyEvent    = KeyEvent.of(KeyCode.Tab)

  /** Shift+Tab, the key that moves focus backwards. `KeyEvent.parse` names this same event from either of two spec
    * spellings: `"shift+tab"`, which describes what the terminal reports, and `"backtab"`, the name most terminals and
    * toolkits give the key.
    */
  val BackTab: KeyEvent   = KeyEvent(KeyCode.Tab, KeyModifiers.Shift)
  val Backspace: KeyEvent = KeyEvent.of(KeyCode.Backspace)
  val Delete: KeyEvent    = KeyEvent.of(KeyCode.Delete)
  val Insert: KeyEvent    = KeyEvent.of(KeyCode.Insert)
  val Home: KeyEvent      = KeyEvent.of(KeyCode.Home)
  val End: KeyEvent       = KeyEvent.of(KeyCode.End)
  val PageUp: KeyEvent    = KeyEvent.of(KeyCode.PageUp)
  val PageDown: KeyEvent  = KeyEvent.of(KeyCode.PageDown)
  val Space: KeyEvent     = KeyEvent.char(' ')

  /** A bare printable character with no modifiers. `Char` is a UTF-16 code unit, so this names only keys in the Basic
    * Multilingual Plane; use [[charAt]] for anything above it.
    */
  def char(c: Char): KeyEvent = KeyEvent.char(c)

  /** A bare printable character named by its Unicode **code point**, so keys outside the Basic Multilingual Plane — an
    * emoji, say — can be written here too. `Key.charAt(0x1F600)` is the same event that `KeyEvent.parse` returns for
    * the one-code-point string spelling that emoji.
    */
  def charAt(codePoint: Int): KeyEvent = KeyEvent.charAt(codePoint)

  /** A function key, `f(1)` … `f(35)`. `f(13)` and up exist only on terminals that speak the kitty keyboard protocol,
    * which is the range `InputDecoder` can report.
    */
  def f(n: Int): KeyEvent = KeyEvent.of(KeyCode.F(n))

  /** `Ctrl`+letter (e.g. `Key.ctrl('s')`). */
  def ctrl(c: Char): KeyEvent = Key.char(c).ctrl

  /** `Alt`/Option+letter. */
  def alt(c: Char): KeyEvent = Key.char(c).alt

  /** `Shift`+another key (e.g. `Key.shift(Key.Tab)`). */
  def shift(key: KeyEvent): KeyEvent = key.shift

  val CtrlC: KeyEvent = ctrl('c')
  val CtrlS: KeyEvent = ctrl('s')
  val CtrlP: KeyEvent = ctrl('p')
  val CtrlQ: KeyEvent = ctrl('q')
  val CtrlD: KeyEvent = ctrl('d')

/** Modifiers as suffixes, so any key in the [[Key]] vocabulary can carry any combination of them: `Key.Left.ctrl`,
  * `Key.Enter.alt`, `Key.f(5).shift`, `Key.char('p').ctrl.shift`.
  *
  * Each one adds its modifier to whatever the key already had rather than replacing the set, so the calls chain in any
  * order and applying the same one twice is harmless.
  *
  * Whether a terminal can actually *report* a given combination is a separate question: on a terminal without the kitty
  * keyboard protocol, `Ctrl` plus `i`, `m`, `j`, `h` or `[` arrives as `Tab`, `Enter`, `Enter`, `Backspace` and
  * `Escape` respectively — see [[io.worxbend.tui.core.KeyEvent.parse]], which rejects those spellings outright.
  */
extension (key: KeyEvent)
  def ctrl: KeyEvent  = key.copy(modifiers = key.modifiers | KeyModifiers.Ctrl)
  def alt: KeyEvent   = key.copy(modifiers = key.modifiers | KeyModifiers.Alt)
  def shift: KeyEvent = key.copy(modifiers = key.modifiers | KeyModifiers.Shift)

/** Ergonomic key handlers that hide the `true`/`false` stop-propagation ceremony.
  *
  * `onKey` binds an action to one or more keys; it consumes the event only when a bound key matches, delegating
  * anything else to a handler already on the element — so several `.onKey(…)` calls compose instead of overwriting each
  * other. Use [[Element.onKeyEvent]] directly when a handler needs the raw event or conditional consumption.
  *
  * Like the styling and layout builders it gives back the element's own type, so `panel(…).onKey(Key.Enter){…}.rounded`
  * still sees a `PanelElement` and the panel-only builders stay reachable after the binding.
  */
extension [E <: Element](element: E)

  def onKey(keys: KeyEvent*)(handler: => Unit): element.Self =
    bindKeys(element)(keys)(handler)

  /** The same binding written in the key-spec vocabulary the rest of the library speaks.
    *
    * An app-level binding is declared as a string — `binding("ctrl+s", "save")` — and a test presses the same string —
    * `pilot.press("ctrl+s")`. Before this overload existed an element-level handler was the one place that had to say
    * it differently, as `.onKey(Key.ctrl('s'))`; now `.onKey("ctrl+s") { save() }` names the key the way the binding
    * and the test do. The two forms build exactly the same `KeyEvent`, because both go through
    * [[io.worxbend.tui.core.KeyEvent.parse]].
    *
    * A malformed spec throws where the element is built, exactly as `binding` does. A view is a static declaration, so
    * a typo like `"ctlr+s"` is a programmer error, and failing at start-up is better than a key that silently never
    * fires.
    *
    * The first spec is a separate parameter rather than the whole list being a `String*` because `onKey(keys:
    * KeyEvent*)` and `onKey(specs: String*)` would erase to the same signature and could not both exist.
    */
  def onKey(spec: String, more: String*)(handler: => Unit): element.Self =
    bindKeys(element)((spec +: more).map(parseSpec))(handler)

/** Ergonomic mouse handlers, the mouse-side counterpart of the `onKey` block above.
  *
  * The DSL's only mouse seam used to be [[Element.onMouseEvent]], which takes a raw `MouseEvent => Boolean`. Every call
  * site that only wanted "run this when the user clicks me" therefore repeated the same shape:
  *
  * {{{
  * .onMouseEvent { event =>
  *   if event.kind == MouseEventKind.Down then
  *     select(row)
  *     true
  *   else false
  * }
  * }}}
  *
  * which is the exact ceremony `onKey` was written to remove for keys. Each handler here consumes only the mouse kinds
  * it names and hands every other kind to a handler already on the element, so several of them compose instead of
  * overwriting each other — the same layering rule `onKey` follows. Reach for [[Element.onMouseEvent]] directly when a
  * handler needs the raw event, its modifiers, or conditional consumption.
  *
  * Positions are absolute, zero-based terminal cells — the same coordinate space a `Rect` uses — because that is what a
  * `MouseEvent` carries. An element that wants coordinates relative to its own area subtracts that area's origin.
  *
  * Like the key handlers and the styling builders, each one hands back the element's own type, so the node-specific
  * builders stay reachable after the binding.
  */
extension [E <: Element](element: E)

  /** A mouse handler that is told where the element is: a [[MouseContext]] carrying the event, the position relative to
    * the element's top-left cell (`ctx.local`, `(0, 0)` at the corner) and the element's laid-out area. Prefer it to
    * [[Element.onMouseEvent]], whose absolute coordinates leave a handler to subtract an origin it was never given.
    *
    * `true` consumes the event, `false` lets it keep bubbling, and several handlers compose, the most recent first. A
    * press this handler consumes captures the pointer, so the drags and the release that follow arrive here even when
    * the pointer has left the element; `ctx.event.clickCount` is `2` for a double click.
    *
    * {{{
    * canvas(width, height)(draw).onMouse { ctx =>
    *   if ctx.event.kind == MouseEventKind.Down then { plot(ctx.local); true } else false
    * }
    * }}}
    */
  def onMouse(handler: MouseContext => Boolean): element.Self =
    val previous = element.props.onMouseContext
    element.withProps(
      element.props.copy(onMouseContext = Some(context => handler(context) || previous.exists(_(context))))
    )

  /** A mouse press inside this element (`MouseEventKind.Down`).
    *
    * This is the same press the framework's own click-to-activate behavior reacts to, and a handler here runs first, so
    * a click bound this way consumes the press and the built-in behavior does not also fire.
    */
  def onClick(handler: => Unit): element.Self = bindMouse(element)(Set(MouseEventKind.Down))(_ => handler)

  /** As [[onClick]], but the handler is told which cell was pressed. */
  def onClickAt(handler: Position => Unit): element.Self = bindMouse(element)(Set(MouseEventKind.Down))(handler)

  /** The pointer moved over this element with no button held (`MouseEventKind.Moved`).
    *
    * Only terminals with any-motion reporting turned on deliver these. On the others the element simply never hears
    * one, so a hover cue must never be the only way to reach something.
    */
  def onHover(handler: Position => Unit): element.Self = bindMouse(element)(Set(MouseEventKind.Moved))(handler)

  /** The pointer moved with a button held down (`MouseEventKind.Drag`). */
  def onDrag(handler: Position => Unit): element.Self = bindMouse(element)(Set(MouseEventKind.Drag))(handler)

  /** The mouse pointer to show over this element and everything inside it — OSC 22, a hand over something clickable, an
    * I-beam over something that takes typing. `button`, `link`, the text inputs and `splitPane`'s divider already ask
    * for theirs; this is for a custom clickable (`.onClick` on a `text`) or to overrule a default.
    *
    * Decoration only: a terminal without the protocol, or one the backend knows will swallow it, shows the ordinary
    * pointer and nothing else changes. The app updates the shape on every mouse event it receives, so under the default
    * button-only mouse capture it changes on a click or a drag; under all-motion tracking it follows the hover.
    */
  def pointer(shape: PointerShape): element.Self =
    element.withProps(element.props.copy(pointerShape = Some(shape)))

  /** The button came back up (`MouseEventKind.Up`) — the end of a drag, and where a drag gesture is committed. */
  def onDragEnd(handler: Position => Unit): element.Self = bindMouse(element)(Set(MouseEventKind.Up))(handler)

  /** Wheel steps: `up` runs on `MouseEventKind.ScrollUp` and `down` on `MouseEventKind.ScrollDown`.
    *
    * Both directions are taken together rather than as two separate builders because a wheel that scrolls one way and
    * not the other is a bug, not a feature — asking for both makes forgetting one impossible.
    */
  def onScroll(up: => Unit, down: => Unit): element.Self =
    val previous = element.props.onMouse
    element.withProps(
      element.props.copy(onMouse = Some { event =>
        event.kind match
          case MouseEventKind.ScrollUp   =>
            up
            true
          case MouseEventKind.ScrollDown =>
            down
            true
          case _                         => previous.exists(_(event))
      })
    )

/** The body the mouse handlers share: consume the `kinds` this handler names, and hand every other mouse event to the
  * handler the element already carried.
  */
private def bindMouse[E <: Element](
    element: E
)(kinds: Set[MouseEventKind])(handler: Position => Unit): element.Self =
  val previous = element.props.onMouse
  element.withProps(
    element.props.copy(onMouse = Some { (event: MouseEvent) =>
      if kinds.contains(event.kind) then
        handler(event.position)
        true
      else previous.exists(_(event))
    })
  )

/** The body both `onKey` overloads share: consume `keys`, and hand anything else to the handler the element already
  * carried, so several `.onKey(…)` calls layer instead of overwriting each other.
  */
private def bindKeys[E <: Element](element: E)(keys: Seq[KeyEvent])(handler: => Unit): element.Self =
  val previous = element.props.onKey
  element.withProps(
    element.props.copy(onKey = Some { event =>
      if keys.contains(event) then
        handler
        true
      else previous.exists(_(event))
    })
  )

/** Pushes a default style onto a whole subtree (terminus's auto-restoring `foreground.green { … }`): every style-aware
  * descendant renders with `transform(Style.Default)` as its base, with any style the node set itself layered on top,
  * so a child's own colour wins and a child's `notBold` opts out of an inherited `bold`. Style-ignoring leaves (raw
  * `widget(...)`, images) are unaffected.
  *
  * A cascade, not a rewrite: the subtree is wrapped as it is, and the style reaches each node when the frame is painted
  * — the focus pass, which already rebuilds every node once per frame, layers the inherited style in on the same visit.
  * Wrapping a thousand-node subtree therefore allocates one node, not a thousand, and nested scopes layer inner over
  * outer. Layout builders (`.fill`, `.length(n)`, `.key(...)`) apply to the scope itself, which takes the wrapped
  * subtree's constraint so the call is layout-neutral.
  */
def withStyle(transform: Style => Style)(inner: Element): Element =
  StyleScopeElement(inner, transform(Style.Default))
