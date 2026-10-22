package io.worxbend.tui.core

/** How a [[Layout]] distributes any space its segments do not consume (ratatui's `Flex`).
  *
  * Flex only has an effect when the constraints leave leftover space — i.e. there is no `Fill`/`Min` greedily absorbing
  * it. With such a growing constraint present the leftover is zero and every mode behaves like [[Flex.Start]].
  *
  *   - [Start](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/Flex.html#Start-0) — pack segments at
  *     the start; leftover trails at the end (the default).
  *   - [End](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/Flex.html#End-0) — pack at the end;
  *     leftover leads at the start.
  *   - [Center](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/Flex.html#Center-0) — center the
  *     block; leftover splits evenly before and after.
  *   - [SpaceBetween](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/Flex.html#SpaceBetween-0) —
  *     first and last segments touch the edges; leftover splits between segments.
  *   - [SpaceAround](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/Flex.html#SpaceAround-0) — equal
  *     space around each segment (edges get a half-gap).
  *   - [SpaceEvenly](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/Flex.html#SpaceEvenly-0) — equal
  *     space in every gap including both edges.
  */
enum Flex:
  case Start, End, Center, SpaceBetween, SpaceAround, SpaceEvenly
