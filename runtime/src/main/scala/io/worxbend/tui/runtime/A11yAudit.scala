package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Buffer, Position, Rect}

/** What an accessibility lint can flag on one painted frame. */
enum A11yRule:

  /** A focusable control that paints exactly the same focused as unfocused, so a keyboard user cannot see where focus
    * is.
    */
  case NoFocusCue

  /** Text whose foreground and background are too close to read — the failure that makes colour-only status (a red
    * "error", a green "ok") invisible to anyone who does not see that hue. Measured by WCAG contrast ratio.
    */
  case LowContrast

  /** A focused text input that did not place the terminal cursor, so a screen reader or an input method has no
    * insertion point to follow.
    */
  case MissingCaret

  /** A modal on top of the stack with no declared way out by `Esc`. */
  case ModalWithoutEscape

/** One accessibility problem on a frame: which rule, a sentence saying what and where, and the cells it concerns when
  * it has a place on screen.
  */
final case class A11yFinding(rule: A11yRule, message: String, area: Option[Rect] = None)

/** How to lint a running `A` for accessibility, given the frame it last painted and where it parked the cursor.
  *
  * The seam between `tui-test`'s `Pilot.a11yReport`, which knows how to read a frame and a cursor off a headless run,
  * and the layer that knows what the frame is made of — `tui-dsl` provides the instance for its `TuiApp`, found on the
  * app type's companion. Implementations are called on the render thread of the run that painted `frame`.
  */
trait A11yAudit[-A]:
  def findings(subject: A, frame: Buffer, cursor: Option[Position]): Seq[A11yFinding]
