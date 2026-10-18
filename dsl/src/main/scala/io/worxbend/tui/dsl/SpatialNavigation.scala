package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, Rect}

/** A direction for spatial focus movement — see [[ElementHost.focusInDirection]] and `TuiApp.spatialNavigation`. */
enum SpatialDirection:
  case Up, Down, Left, Right

object SpatialDirection:

  /** The direction an arrow key names, `None` for any other key. */
  def of(code: KeyCode): Option[SpatialDirection] =
    code match
      case KeyCode.Up    => Some(Up)
      case KeyCode.Down  => Some(Down)
      case KeyCode.Left  => Some(Left)
      case KeyCode.Right => Some(Right)
      case _             => None

/** Picks the focusable to move to from where each one was painted on the last frame.
  *
  * A candidate must lie in the asked direction — its centre strictly past the current one's along that axis — and the
  * nearest wins, with sideways offset counted double so that the control straight across beats one diagonally closer:
  * from the top-left of a 2x2 grid, `Right` is the top-right, never the bottom-right. Ties go to the earlier control in
  * tab order. Pure arithmetic on rects; owns no state.
  */
private[dsl] object SpatialNavigation:

  def nearest(from: Rect, candidates: Seq[(Int, Rect)], direction: SpatialDirection): Option[Int] =
    // doubled centres keep the arithmetic in whole cells
    def centre(rect: Rect): (Long, Long) = (2L * rect.x + rect.width, 2L * rect.y + rect.height)
    val (cx, cy)                         = centre(from)
    candidates
      .flatMap { (index, rect) =>
        val (x, y)              = centre(rect)
        val (primary, sideways) = direction match
          case SpatialDirection.Right => (x - cx, math.abs(y - cy))
          case SpatialDirection.Left  => (cx - x, math.abs(y - cy))
          case SpatialDirection.Down  => (y - cy, math.abs(x - cx))
          case SpatialDirection.Up    => (cy - y, math.abs(x - cx))
        Option.when(primary > 0)((primary + 2 * sideways, index))
      }
      .minOption
      .map(_._2)
