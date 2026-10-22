package io.worxbend.tui.core

/** How a [[Layout]] distributes any space its segments do not consume (ratatui's `Flex`).
  *
  * Flex only has an effect when the constraints leave leftover space — i.e. there is no `Fill`/`Min` greedily absorbing
  * it. With such a growing constraint present the leftover is zero and every mode behaves like [[Flex.Start]].
  *
  *   - [[io.worxbend.tui.core.Flex.Start Start]] — pack segments at the start; leftover trails at the end (the
  *     default).
  *   - [[io.worxbend.tui.core.Flex.End End]] — pack at the end; leftover leads at the start.
  *   - [[io.worxbend.tui.core.Flex.Center Center]] — center the block; leftover splits evenly before and after.
  *   - [[io.worxbend.tui.core.Flex.SpaceBetween SpaceBetween]] — first and last segments touch the edges; leftover
  *     splits between segments.
  *   - [[io.worxbend.tui.core.Flex.SpaceAround SpaceAround]] — equal space around each segment (edges get a half-gap).
  *   - [[io.worxbend.tui.core.Flex.SpaceEvenly SpaceEvenly]] — equal space in every gap including both edges.
  */
enum Flex:
  case Start, End, Center, SpaceBetween, SpaceAround, SpaceEvenly
