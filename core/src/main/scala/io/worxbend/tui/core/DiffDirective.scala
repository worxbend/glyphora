package io.worxbend.tui.core

/** What [[Buffer.diff]] is instructed to do with one position of a frame, regardless of whether its content changed.
  *
  * A frame is normally flushed by comparing it against the frame before it and emitting only the cells that differ.
  * That reasoning holds for exactly as long as glyphora is the only thing writing to the terminal, and there are two
  * ways for it to stop holding — one in each direction:
  *
  *   - Something else painted a region and glyphora must not paint over it. An inline-image protocol (Sixel, the kitty
  *     graphics protocol, iTerm2 inline images) fills a rectangle with an escape sequence that has no cell-by-cell
  *     representation, so re-emitting any column of it from the grid tears a hole in the picture. That is
  *     [Skip](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/DiffDirective.html#Skip-0).
  *   - Something else painted a region and glyphora must paint over it. A subprocess writing to the same terminal, or
  *     an image that has just been torn down, leaves the screen showing something the buffer never wrote — and because
  *     the buffer's own memory of those cells is unchanged, the comparison says "nothing to do" and the wrong pixels
  *     stay until something unrelated happens to touch them. That is
  *     [AlwaysUpdate](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/DiffDirective.html#AlwaysUpdate-0).
  *
  * The two are cases of one enumeration rather than two independent flags because a single position cannot sensibly be
  * both never-emitted and always-emitted, and an enumeration is the shape that makes that unrepresentable.
  *
  * Directives are per position and per frame: they belong to the [[Buffer]], not to the [[Cell]] (a `Cell` is a value
  * that says what to draw, and the same value is skipped in one column and flushed in the next), and [[Buffer.reset]]
  * clears them back to
  * [Default](https://worxbend.github.io/glyphora/api/core/io/worxbend/tui/core/DiffDirective.html#Default-0). A caller
  * that owns a region re-declares it on every render, in the same render pass that draws the rest of the frame.
  */
enum DiffDirective:

  /** Ordinary diffing: the position is emitted when, and only when, its content changed. */
  case Default

  /** Never emit this position — something outside this renderer owns those pixels.
    *
    * Honoured on every path that writes cells to a terminal, including the full repaint of [[Buffer.emitAll]] after a
    * resize, because a resize is precisely the moment a blind full repaint would erase the picture. The owner of the
    * region is responsible for redrawing it after a resize.
    *
    * Marking a cell suppresses only the flush. [[Buffer.set]] still writes to it, so a widget can keep a text fallback
    * in the grid and have it appear the moment the region stops being skipped.
    */
  case Skip

  /** Emit this position on the next diff even if its content is identical to the previous frame's.
    *
    * The narrow counterpart of a whole-screen repaint: a caller that knows *which* rectangle a foreign painter may have
    * damaged repaints that rectangle instead of ten thousand cells. It does not override the wide-grapheme rule — the
    * reserved second column of a two-column cluster is still never emitted, because emitting the cluster repaints both
    * of its columns and a stray blank in the second one would clip the glyph.
    */
  case AlwaysUpdate
