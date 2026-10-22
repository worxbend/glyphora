package io.worxbend.tui.terminal

/** What the terminal's tab or taskbar progress indicator (OSC 9;4) shows: the five states of the ConEmu protocol that
  * Windows Terminal, Ghostty, WezTerm and others implement.
  *
  * A fraction is clamped to `0.0..1.0` and rounded to the whole percent the protocol takes; `NaN` reads as empty. How a
  * terminal draws `Error` and `Paused` is its own choice — Windows Terminal paints them red and yellow — and one that
  * knows only the determinate bar may show them as one. Immutable; safe to share between threads.
  */
enum TaskbarState:

  /** No indicator (`9;4;0`). */
  case Cleared

  /** A determinate bar `fraction` of the way (`9;4;1;percent`). */
  case Progress(fraction: Double)

  /** The bar in its error state at `fraction` (`9;4;2;percent`) — a step failed, the work stopped or goes on. */
  case Error(fraction: Double)

  /** Busy with no known extent (`9;4;3`) — the spinner of a taskbar. */
  case Indeterminate

  /** The bar in its paused or warning state at `fraction` (`9;4;4;percent`). */
  case Paused(fraction: Double)

  /** The fraction this state shows, for a backend that can only draw a determinate bar or nothing: `None` for
    * [Cleared](https://worxbend.github.io/glyphora/api/terminal/io/worxbend/tui/terminal/TaskbarState.html#Cleared-0)
    * and
    * [Indeterminate](https://worxbend.github.io/glyphora/api/terminal/io/worxbend/tui/terminal/TaskbarState.html#Indeterminate-0).
    */
  def progress: Option[Double] =
    this match
      case Progress(value) => Some(value)
      case Error(value)    => Some(value)
      case Paused(value)   => Some(value)
      case Cleared         => None
      case Indeterminate   => None

object TaskbarState:

  /** The state the fraction-only API (`setTaskbarProgress(Option[Double])`) means: `Some` is a determinate bar, `None`
    * clears it.
    */
  def fromProgress(progress: Option[Double]): TaskbarState =
    progress.fold(Cleared)(Progress(_))
