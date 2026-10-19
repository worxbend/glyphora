package io.worxbend.tui.dsl

import io.worxbend.tui.core.VersionedState

/** Caller-owned scroll state for `logConsole(ring, state)`: where the console's window sits over the filtered records,
  * and whether it follows the tail.
  *
  * Following (the default) pins the window to the newest records, as the stateless console always does. Scrolling up
  * detaches it, and the window then stays on the records it shows while new ones arrive below; scrolling back down to
  * the end — or [[followTail]], or `End` on a focused console — re-attaches it. [[toggleFollow]] flips following by
  * hand, for a key the app binds itself. The window is a row index into the filtered records, so a ring at capacity
  * that evicts its oldest record shifts a detached window by that one row.
  *
  * Render-thread-only. Every mutation raises `version`, so the node that paints this state schedules the frame that
  * shows a change made outside the event path. Rendering reads the last viewport into it without counting as one.
  */
final class LogConsoleState extends VersionedState:

  private var followValue: Boolean = true
  private var top: Int             = 0

  // the viewport and row count of the last render, which the scroll methods measure against; not mutations
  private var lastHeight: Int = 1
  private var lastRows: Int   = 0

  /** Whether the window is pinned to the newest records. */
  def following: Boolean = followValue

  /** Scrolls `count` records towards the oldest, detaching from the tail. */
  def scrollUp(count: Int = 1): Unit =
    if followValue then top = tailTop
    followValue = false
    top = math.max(0, top - count)
    touch()

  /** Scrolls `count` records towards the newest; reaching the end re-attaches to the tail. */
  def scrollDown(count: Int = 1): Unit =
    if !followValue then
      top = math.min(tailTop, top + count)
      if top >= tailTop then followValue = true
      touch()

  /** Jumps to the oldest record, detaching from the tail. */
  def scrollToTop(): Unit =
    followValue = false
    top = 0
    touch()

  /** Pins the window to the newest records again. */
  def followTail(): Unit =
    followValue = true
    touch()

  /** Flips [[following]]: off keeps the window on the records it shows now, on jumps to the tail. */
  def toggleFollow(): Unit =
    if followValue then
      top = tailTop
      followValue = false
      touch()
    else followTail()

  private def tailTop: Int = math.max(0, lastRows - lastHeight)

  /** The first row to draw for `rows` filtered records in a viewport `height` rows tall, remembering both for the
    * scroll methods. Called by the render; clamps a detached window that the records no longer reach.
    */
  private[dsl] def windowTop(rows: Int, height: Int): Int =
    lastRows = rows
    lastHeight = math.max(1, height)
    if followValue then tailTop else math.max(0, math.min(top, tailTop))
