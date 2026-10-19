package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Line, Position, Rect, StatefulWidget, Style, VersionedState}

/** What the keyboard does to a [[Window]]: nothing (`Normal`), or — in the keyboard arrange mode — move it or resize it
  * with the arrows.
  */
enum WindowMode:
  case Normal, Moving, Resizing

/** Caller-owned state of a floating [[Window]]: where it sits inside the area its stack is given (`x`, `y` from that
  * area's top-left), how big it is, its stacking order `z` (larger draws on top), its arrange mode, and the press a
  * pointer drag started from.
  *
  * Built by [[WindowStack.window]]: [[raise]] draws from its stack's counter, so a window raised later sits above any
  * window of the same stack raised earlier, and two stacks — two apps, two test suites in one JVM — never share one.
  *
  * Render-thread-only and `Versioned`: every mutation raises [[version]], which is how a running app learns it owes a
  * frame.
  */
final class WindowState private[widgets] (
    stack: WindowStack,
    initialX: Int,
    initialY: Int,
    initialWidth: Int,
    initialHeight: Int,
) extends VersionedState:

  private var xValue: Int        = initialX
  private var yValue: Int        = initialY
  private var widthValue: Int    = math.max(WindowState.MinWidth, initialWidth)
  private var heightValue: Int   = math.max(WindowState.MinHeight, initialHeight)
  private var zValue: Long       = 0L
  private var modeValue          = WindowMode.Normal
  private var drag: Option[Drag] = None

  private final case class Drag(from: Position, start: Rect, resizing: Boolean)

  /** The window's rectangle, relative to the area its stack is given. */
  def rect: Rect = Rect(xValue, yValue, widthValue, heightValue)

  /** The stacking order: a window with a larger `z` draws over one with a smaller. */
  def z: Long = zValue

  /** The keyboard arrange mode. */
  def mode: WindowMode = modeValue

  def mode_=(value: WindowMode): Unit =
    modeValue = value
    touch()

  /** Puts this window above every window of its stack raised before it. */
  def raise(): Unit =
    zValue = stack.nextOrder()
    touch()

  /** Moves the window by `dx` columns and `dy` rows. */
  def moveBy(dx: Int, dy: Int): Unit =
    xValue += dx
    yValue += dy
    touch()

  /** Grows the window by `dw` columns and `dh` rows, never below [[WindowState.MinWidth]] x [[WindowState.MinHeight]].
    */
  def resizeBy(dw: Int, dh: Int): Unit =
    widthValue = math.max(WindowState.MinWidth, widthValue + dw)
    heightValue = math.max(WindowState.MinHeight, heightValue + dh)
    touch()

  /** Starts a pointer drag from screen cell `from`: a move, or a resize when `resizing`. */
  def startDrag(from: Position, resizing: Boolean): Unit =
    drag = Some(Drag(from, rect, resizing))
    touch()

  /** Follows a drag to screen cell `to`: the window moves (or grows) by how far the pointer travelled since the press.
    * A no-op when no drag was started.
    */
  def dragTo(to: Position): Unit =
    drag.foreach { started =>
      val dx = to.x - started.from.x
      val dy = to.y - started.from.y
      if started.resizing then
        widthValue = math.max(WindowState.MinWidth, started.start.width + dx)
        heightValue = math.max(WindowState.MinHeight, started.start.height + dy)
      else
        xValue = started.start.x + dx
        yValue = started.start.y + dy
      touch()
    }

  /** Ends the pointer drag. */
  def endDrag(): Unit =
    drag = None
    touch()

  /** Whether a pointer drag is in progress. */
  def dragging: Boolean = drag.isDefined

object WindowState:

  /** The smallest window: room for a short title and one row of content inside the frame. */
  val MinWidth: Int  = 8
  val MinHeight: Int = 3

/** The caller-owned stacking order of a set of floating windows: the counter each [[WindowState.raise]] draws its `z`
  * from. Keep one per set of windows — usually one per `windowStack` — in a field, and build its windows with
  * [[window]]. Render-thread-only, like the windows themselves.
  */
final class WindowStack:

  private var order: Long = 0L

  /** A window of this stack at `(x, y)` inside the stack's area, `width` x `height` cells. */
  def window(x: Int, y: Int, width: Int, height: Int): WindowState = new WindowState(this, x, y, width, height)

  private[widgets] def nextOrder(): Long =
    order += 1
    order

/** A floating window's frame: a bordered box with ` title ` on its top border — followed by `· move` or `· resize`
  * while the keyboard arrange mode is on — and a `◢` resize grip in its bottom-right corner. The content goes in
  * [[inner]]; where the window sits is the caller's business ([[WindowState.rect]]). The window is opaque: its whole
  * area is cleared before the frame is drawn.
  *
  * Stateless apart from the caller-owned [[WindowState]]; render-thread use only.
  */
final case class Window(
    title: String,
    style: Style = Style.Default,
    borderStyle: Style = Style.Default,
    titleStyle: Style = Style.Default.bold,
    gripSymbol: String = "◢",
    borderType: BorderType = BorderType.Plain,
) extends StatefulWidget[WindowState]:

  /** The area inside the frame. */
  def inner(area: Rect): Rect = frame("").inner(area)

  def render(area: Rect, buffer: Buffer, state: WindowState): Unit =
    if !area.isEmpty then
      val suffix = state.mode match
        case WindowMode.Normal   => ""
        case WindowMode.Moving   => " · move"
        case WindowMode.Resizing => " · resize"
      // a window is opaque: whatever lies beneath it — the base layer, a window lower in the stack — is covered
      Clear(style).render(area, buffer)
      frame(s" $title$suffix ").render(area, buffer)
      if area.width > 1 && area.height > 1 then
        buffer.setString(area.right - 1, area.bottom - 1, gripSymbol, style.patch(borderStyle), 1)

  private def frame(label: String): Block =
    Block(
      titles = if label.isEmpty then Seq.empty else Seq(BlockTitle.top(Line.raw(label))),
      style = style,
      borderStyle = style.patch(borderStyle),
      borderType = borderType,
      titleStyle = style.patch(titleStyle),
    )
