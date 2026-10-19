package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Position, Rect, Versioned}

/** Caller-owned state of a context menu: where it was opened — the screen cell the right click landed on — while it is
  * open, and the highlight inside it (a [[MenuState]]).
  *
  * Render-thread-only and `Versioned`: [[version]] is its own mutation count plus the menu state's.
  */
final class ContextMenuState private () extends Versioned:

  private var atValue: Option[Position] = None
  private var mutations: Long           = 0L

  /** The highlight inside the open menu. */
  val menu: MenuState = MenuState()

  def version: Long = mutations + menu.version

  /** Where the menu was opened, while it is open. */
  def at: Option[Position] = atValue

  /** Whether the menu is showing. */
  def isOpen: Boolean = atValue.isDefined

  /** Opens the menu at `position` with its highlight reset onto the first selectable entry. */
  def openAt(position: Position): Unit =
    atValue = Some(position)
    menu.selected = None
    menu.offset = 0
    mutations += 1

  /** Closes the menu. */
  def close(): Unit =
    atValue = None
    mutations += 1

object ContextMenuState:

  /** A closed context menu. */
  def apply(): ContextMenuState = new ContextMenuState()

/** Where a context menu goes: a [[Menu]] whose top-left corner is the cell that was clicked, flipped to end at that
  * cell instead on any axis where it would run past `frame` — so a menu opened near the right edge opens to the left of
  * the pointer, one opened near the bottom opens upwards — then clipped to the frame.
  */
object ContextMenu:

  /** The rectangle a `width` x `height` menu opened at `at` occupies inside `frame`. */
  def placement(at: Position, width: Int, height: Int, frame: Rect): Rect =
    val w = math.max(0, math.min(width, frame.width))
    val h = math.max(0, math.min(height, frame.height))
    val x = if at.x + w <= frame.right then at.x else at.x - w + 1
    val y = if at.y + h <= frame.bottom then at.y else at.y - h + 1
    Rect(clamp(x, frame.x, frame.right - w), clamp(y, frame.y, frame.bottom - h), w, h)

  private def clamp(value: Int, low: Int, high: Int): Int = math.max(low, math.min(high, value))
