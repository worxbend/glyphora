package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Rect, Size}

/** How much of the terminal one run owns.
  *
  * [[io.worxbend.tui.runtime.Viewport.Fullscreen Fullscreen]] is the takeover every glyphora app has had until now. The
  * terminal has two screen buffers: the *primary* one, which holds the shell's output and scrolls into scrollback, and
  * the *alternate* one, a single screenful with no history. A full-screen app switches to the alternate screen, paints
  * over the whole of it, and switches back on exit — so the user's shell output is hidden for the app's lifetime and
  * comes back untouched afterwards, and the app leaves no trace behind.
  *
  * [[io.worxbend.tui.runtime.Viewport.Inline Inline]] stays on the primary screen and owns only the bottom `height`
  * rows of it. The shell's earlier output stays visible above the app, and the app's last frame is still there after it
  * exits, the way `git`'s output is. That is the shape a progress panel, an installer, or a one-question prompt wants.
  *
  * These are terminal-space decisions, not widget-space ones: this picks the `Rect` a frame is composed into. Scrolling
  * *inside* a widget is a separate thing entirely and is unaffected.
  */
enum Viewport:

  /** The whole terminal, on the alternate screen. The default. */
  case Fullscreen

  /** The bottom `height` rows of the primary screen. */
  case Inline(height: Int)

  /** The rectangle a frame is composed into on a terminal of `terminal` size.
    *
    * Bottom-anchored and clamped both ways, so no size of terminal produces a rectangle outside it: a strip taller than
    * the terminal becomes the whole terminal rather than a negative origin, and a strip of zero (or negative) rows
    * becomes an empty rectangle at the bottom edge, which the composer draws as nothing rather than failing. Both cases
    * are reachable from a user resizing their window, not just from a bad argument.
    */
  def areaIn(terminal: Size): Rect =
    this match
      case Fullscreen     => Rect(terminal)
      case Inline(height) =>
        val rows = math.max(0, math.min(height, terminal.height))
        Rect(0, terminal.height - rows, terminal.width, rows)

  /** How many rows the runner has to reserve on the primary screen at startup; zero for a full-screen run. */
  private[runtime] def reservedRows: Int =
    this match
      case Fullscreen     => 0
      case Inline(height) => math.max(0, height)
