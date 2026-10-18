package io.worxbend.tui.core

/** Which way something moves across the screen: the direction of travel, not the edge it starts from.
  *
  * `Effect.slideIn(MotionDirection.Up)` moves content upward, so it enters from the bottom edge;
  * `Pattern.sweep(MotionDirection.Right)` sends its front rightward, so the left column leads. Distinct from
  * [[Direction]], which names the axis a [[Layout]] splits along and has no sense of travel.
  */
enum MotionDirection:
  case Up, Down, Left, Right
