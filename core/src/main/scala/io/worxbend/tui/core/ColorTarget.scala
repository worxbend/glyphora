package io.worxbend.tui.core

/** Which colour layer of a cell a colour effect acts on — [[Effect.hslShift]] and [[Effect.paint]] take one.
  *
  *   - `Foreground` changes the text colour of every non-blank cell; blank cells have no text to recolour.
  *   - `Background` changes the background of every cell in the area, blank ones included, because a filled panel is
  *     mostly blank cells. A cell with no background of its own is treated as black.
  *   - `Both` does both.
  */
enum ColorTarget:
  case Foreground, Background, Both

  /** Whether the text colour is changed. */
  def foreground: Boolean = this != Background

  /** Whether the background colour is changed. */
  def background: Boolean = this != Foreground
