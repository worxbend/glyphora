package io.worxbend.tui.widgets

/** Which edge of its area a list is anchored to, and which way its rows run from there.
  *
  * [[io.worxbend.tui.widgets.ListDirection.TopToBottom TopToBottom]] is the ordinary reading order: the first visible
  * item is drawn on the top row of the area and the rest follow downward, so a list shorter than its area leaves the
  * space underneath it empty.
  *
  * [[io.worxbend.tui.widgets.ListDirection.BottomToTop BottomToTop]] anchors to the opposite edge: the first visible
  * item is drawn on the *last* row of the area and the rest climb upward, so a short list sits against the floor of its
  * area and the empty space ends up above it. That is the shape a chat transcript or a log tail wants — the entry the
  * reader cares about most stays welded to the bottom edge instead of floating in the middle of a half-empty pane.
  * Because the first item of the sequence lands on the bottom row, a caller using this direction feeds its items
  * newest-first.
  *
  * This is an enum rather than a `reversed: Boolean` parameter for the reason the style guide gives for preferring a
  * sealed type over a flag: at the call site `ListDirection.BottomToTop` says what it does where `true` would not, and
  * a third anchoring rule could be added later without changing the parameter's type.
  *
  * These are plain values with no state, so they carry no thread constraint of their own.
  */
enum ListDirection:
  case TopToBottom, BottomToTop
