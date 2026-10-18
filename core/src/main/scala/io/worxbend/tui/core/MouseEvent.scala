package io.worxbend.tui.core

/** What the mouse did.
  *
  * `Drag` is motion with a button held; `Moved` is motion with none. Which of the two a terminal can even report
  * depends on how the backend asked for the mouse: under button-event tracking (DEC modes 1000/1002/1006, the default)
  * a terminal reports motion only while a button is down, so `Moved` never arrives. A backend asked for all-motion
  * tracking (DEC mode 1003 as well) reports every pointer movement, which is what makes hover highlighting and tooltips
  * possible — and a great deal more input traffic, which is why it is opt-in.
  *
  * `ScrollUp`/`ScrollDown` are the vertical wheel. `ScrollLeft`/`ScrollRight` are the horizontal one (xterm buttons 66
  * and 67), which is what a sideways trackpad swipe sends. No built-in element consumes the horizontal pair, because no
  * widget in this library scrolls sideways; they are decoded and delivered so that an application's own `onMouseEvent`
  * can act on them, and they bubble untouched past every built-in that declines them.
  */
enum MouseEventKind:
  case Down, Up, Drag, Moved, ScrollUp, ScrollDown, ScrollLeft, ScrollRight

/** A mouse action at an absolute terminal position.
  *
  * `position` is absolute and zero-based, in the same coordinate space as [[Position]] and [[Rect]]: column `0`, row
  * `0` is the top-left cell of the terminal, not of any widget. A handler that wants coordinates relative to the area
  * it was given subtracts that area's own origin (`event.position.x - area.x`).
  *
  * `button` names which physical button the action concerns, so an application can tell a right-click (open a context
  * menu) from a left-click (activate the thing under the pointer). It defaults to [[MouseButton.Left]] so that every
  * three-argument construction written before the field existed still compiles and still means what it meant. That
  * default is a convenience for hand-built events only: what the decoder reports for a wheel notch, and for an X10
  * release that cannot name a button, is [[MouseButton.Unknown]].
  *
  * `clickCount` is how many presses in a row this `Down` is: `2` for the second press of a double click, `3` for a
  * triple. Terminals report no such thing — every press arrives as a single `Down` — so the count is *synthesised* by
  * whoever routes the events (the DSL's `ElementHost` counts presses of the same button on the same cell within 400
  * ms), and the decoder, like every hand-built event, leaves it at `1`. It is `1` on every kind other than `Down`.
  */
final case class MouseEvent(
    position: Position,
    kind: MouseEventKind,
    modifiers: KeyModifiers,
    button: MouseButton = MouseButton.Left,
    clickCount: Int = 1,
)
