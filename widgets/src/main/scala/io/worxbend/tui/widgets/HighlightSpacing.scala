package io.worxbend.tui.widgets

/** When a list reserves the gutter its highlight symbol is drawn in.
  *
  * A list draws a marker — `"> "` by default — to the left of the selected row. The columns that marker occupies have
  * to come from somewhere, and the three answers below differ in when they are taken away from the text.
  *
  *   - [Always](HighlightSpacing.html#Always-0) reserves the gutter on every row whether or not anything is selected,
  *     so the text never moves sideways. This is what every list in this library did before the choice existed, and it
  *     is the default for that reason.
  *   - [WhenSelected](HighlightSpacing.html#WhenSelected-0) reserves it only while something is selected. A list nobody
  *     has selected in yet uses its full width for text, and the text shifts right by the marker's width the first time
  *     a row is highlighted. In a narrow pane those two reclaimed columns are worth the shift.
  *   - [Never](HighlightSpacing.html#Never-0) reserves nothing and draws no marker at all. Use it when the highlight
  *     *style* — a reversed or coloured row — is the only cue wanted, which is the usual choice for a list inside a
  *     bordered panel where every column counts.
  *
  * An enum rather than two booleans, because "reserve the gutter" and "draw the marker" are not independent: reserving
  * nothing and still drawing the marker would push the marker over the text.
  *
  * These are plain values with no state, so they carry no thread constraint of their own.
  */
enum HighlightSpacing:
  case Always, WhenSelected, Never
