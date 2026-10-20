package io.worxbend.tui.widgets

/** A half-open range of grapheme positions. [[EditBuffer.selection]] supplies the earlier endpoint as `start`,
  * regardless of the selection's direction. Immutable data; safe to read independently of the buffer's next edit.
  */
final case class TextRange(start: TextPosition, end: TextPosition)
