package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Position, Rect}

/** How a focused text-entry node shows its insertion point: as the reverse-video cell the widget paints, as the
  * terminal's own cursor, or as both.
  *
  * The two are not the same thing. The painted cell is what a sighted user sees, and it is all a frame snapshot
  * records. The terminal's cursor — the *hardware caret* — is what a screen reader announces as the insertion point and
  * what an input method editor anchors its candidate popup to; neither can see a styled cell. Every text node moves
  * both by default, and `.caretStyle(...)` on the node picks one of them instead: [[Painted]] for an app that hid the
  * terminal cursor on purpose and wants it to stay hidden, [[Hardware]] for one that would rather let the terminal draw
  * the caret in the user's own configured shape and colour than paint a block over the text.
  */
enum CaretStyle:

  /** The painted cell and the terminal cursor, together — the default. */
  case Both

  /** Only the terminal's own cursor; the widget paints no cursor cell. */
  case Hardware

  /** Only the painted cell; the terminal cursor stays hidden, as it did before 0.20.0. */
  case Painted

  /** Whether the widget paints its cursor cell. */
  private[dsl] def paints: Boolean = this != Hardware

  /** Whether the node reports its caret cell for the terminal cursor to follow. */
  private[dsl] def moves: Boolean = this != Painted

/** Where the focused text node parks the frame's caret during a render pass, for the host to hand to the frame once
  * painting is over.
  *
  * Frame-scoped and thread-scoped the way [[PortalQueue]] is — the same [[FrameSlot]] — and for the same reasons: the
  * host arms it for exactly one paint with [[collect]], a node rendering outside any host finds nothing armed and
  * reports into the void, and a `ThreadLocal` keeps two runners in one JVM from reading each other's caret. The report
  * is translated onto the screen *when it is made*, while the [[FrameCoordinates]] stack of the scroll views enclosing
  * the node is still live — the host reads it after that stack has unwound.
  *
  * One frame has one caret: a later report replaces an earlier one, which is also [[io.worxbend.tui.runtime.Frame]]'s
  * own rule, and only the focused node reports, so in practice there is one.
  */
private[dsl] object CaretRegister:

  private final class Pending:
    var caret: Option[Position] = None

  private val current: FrameSlot[Pending] = FrameSlot(() => new Pending)

  /** Arms the register around `paint` and answers the screen cell the focused text node reported, or `None` when
    * nothing did — or the node that did is scrolled out of its viewport, which is the same thing to the terminal.
    */
  def collect(paint: => Unit): Option[Position] =
    current.around { pending =>
      paint
      pending.caret
    }

  /** Called by a focused text node from inside its render, with the caret cell in its own area's coordinates. The cell
    * is mapped through every enclosing viewport onto the screen; a cell that no viewport lets through — the field is
    * scrolled out of view — leaves the caret hidden rather than parked on a stale screen cell.
    */
  def report(cell: Position): Unit =
    current.armed.foreach { pending =>
      val onScreen = FrameCoordinates.visible(Rect(cell.x, cell.y, 1, 1))
      pending.caret = Option.when(!onScreen.isEmpty)(Position(onScreen.x, onScreen.y))
    }
