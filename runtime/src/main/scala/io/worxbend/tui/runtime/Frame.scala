package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Buffer, Effect, Position, Rect, StatefulWidget, Widget}
import scala.concurrent.duration.FiniteDuration

/** One frame being rendered: the drawable `area` plus the buffer widgets write into.
  *
  * The buffer itself stays module-private — application render code goes through the widget contract, which keeps every
  * write attributable to a widget and an area.
  *
  * `count` is how many frames this runner composed before this one: the first frame of a run is `0` and the number goes
  * up by one per composed frame, wrapping back to `0` after `Long.MaxValue` (about 300 million years at a thousand
  * frames a second — the wrap is stated so the type is total, not because anyone will see it). It counts *composed*
  * frames, not cells that reached the terminal: a frame the backend's diff turned into no output at all still consumed
  * its number. Use it to do expensive work only every Nth frame, or to label a frame in a debug overlay. Do **not**
  * drive animation from it — a frame rate is not a clock, and the same animation would then run at a different speed on
  * a fast terminal than on a slow one. `core.Progress` with a real elapsed duration is the one owner of
  * time-to-position arithmetic.
  */
final class Frame(val area: Rect, private[runtime] val buffer: Buffer, val count: Long = 0L):

  def renderWidget(widget: Widget, area: Rect): Unit =
    widget.render(area, buffer)

  def renderStatefulWidget[S](widget: StatefulWidget[S], area: Rect, state: S): Unit =
    widget.render(area, buffer, state)

  /** Applies a post-render [[io.worxbend.tui.core.Effect Effect]] to what has been drawn so far — call after the
    * widgets rendered.
    *
    * `elapsed` is time since **this effect** started, not since this frame began and not since the application started.
    * The frame does not track it: an effect is a pure function of the elapsed time it is given, so the caller is the
    * one that has to remember each effect's start. `TuiApp.runEffect` in `tui-dsl` keeps that timestamp for every
    * active effect and passes the difference here; code driving a bare [[Runner]] must do the same.
    */
  def applyEffect(effect: Effect, elapsed: FiniteDuration): Unit =
    effect.process(elapsed, buffer, area)

  private var requestedCursor: Option[Position] = None

  /** Asks for the terminal's own cursor to be parked at `position` once this frame has been flushed.
    *
    * Text fields paint their own block cursor into a cell, which looks right but is not the same thing: the *physical*
    * cursor is what a terminal input method anchors its composition popup to, what a screen reader tracks, and what
    * blinks the way the platform blinks. Declaring it here is how a frame asks for that.
    *
    * One frame has one caret. A later call in the same frame replaces an earlier one, so the innermost element to claim
    * the cursor is the one that gets it, and a frame that never calls this leaves the cursor hidden. Coordinates are
    * buffer coordinates — the same space [[area]] and mouse events use, not an offset inside some widget.
    *
    * Called on the render thread, from inside the render function, like everything else on a frame.
    */
  def setCursorPosition(position: Position): Unit = requestedCursor = Some(position)

  /** Withdraws a cursor position declared earlier in this same frame. */
  def clearCursorPosition(): Unit = requestedCursor = None

  /** What the composer honours after the flush: the position the frame asked for, or `None` for "keep it hidden". */
  private[runtime] def declaredCursor: Option[Position] = requestedCursor
