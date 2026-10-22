package io.worxbend.tui.terminal

/** The shape the terminal draws the *mouse pointer* in while it is over the window — OSC 22 (kitty's pointer-shape
  * protocol, also spoken by foot, WezTerm, Ghostty and recent xterm).
  *
  * What it is for: the affordances a desktop user already reads without thinking. A hand over a link or a button says
  * "this can be clicked", an I-beam over a text field says "this takes typing", a double-headed arrow over a split
  * divider says "this can be dragged". None of it is load-bearing — a terminal without OSC 22 ignores the request and
  * the app works exactly as before — so a widget may set a shape and must never depend on it.
  *
  * This is the mouse pointer, not the hardware caret; the caret's shape is [[CursorShape]].
  * [[io.worxbend.tui.terminal.PointerShape.Default Default]] hands the pointer back to the user's own configuration,
  * and `AnsiSequences.RestoreAll` emits it so a process killed with a hand pointer up does not leave one behind in the
  * shell.
  */
enum PointerShape:

  /** Whatever the user's terminal shows when nothing asked for a shape — normally an arrow. */
  case Default

  /** The I-beam a text field shows. */
  case Text

  /** The hand a link or a button shows. */
  case Pointer

  /** The horizontal double-headed arrow over a vertical divider that can be dragged left and right. */
  case ResizeHorizontal

  /** The vertical double-headed arrow over a horizontal divider that can be dragged up and down. */
  case ResizeVertical

  /** The busy indicator, over something that is working and cannot be interacted with yet. */
  case Wait

  /** The "not allowed" sign, over something that is disabled. */
  case NotAllowed

object PointerShape:

  /** The CSS cursor keyword OSC 22 takes for `shape`.
    *
    * Written out case by case rather than derived from the case name, for the reason [[CursorShape.parameter]] gives:
    * the names are a wire format defined by someone else (the CSS `cursor` property, which kitty's protocol adopted),
    * and a case renamed for readability here must not silently change what reaches the terminal.
    */
  def name(shape: PointerShape): String =
    shape match
      case Default          => "default"
      case Text             => "text"
      case Pointer          => "pointer"
      case ResizeHorizontal => "ew-resize"
      case ResizeVertical   => "ns-resize"
      case Wait             => "wait"
      case NotAllowed       => "not-allowed"
