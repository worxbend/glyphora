package io.worxbend.tui.dsl

import io.worxbend.tui.core.{CharWidth, Size}

/** Cells between a continuation's keys and its description in [[whichKeyPopup]]. */
private val WhichKeyGap = 2

/** The which-key popup: while a key sequence is half typed, a bordered box titled with the prefix that lists every key
  * that may follow it and what that key does — `None` when no prefix is pending or nothing continues it.
  *
  * The box is a [[Element.portal]] placed against the bottom-right corner of an area of `size`, one row clear of the
  * bottom edge so a [[statusBar]] stays readable, which is how `TuiApp` lays it over the composed view on every frame a
  * prefix is pending (switch that off with `override def whichKey = false`). Call it directly only to place the popup
  * in a view of your own.
  *
  * A pure builder over the `bindings` value it is handed — `activeBindings`, so the prefix is the live one — and the
  * ambient [[Theme]].
  */
def whichKeyPopup(bindings: KeyBindings, size: Size)(using theme: Theme): Option[Element] =
  for
    prefix <- bindings.pending
    rows = bindings.continuations(prefix.keys)
    if rows.nonEmpty
  yield
    val keyColumn = rows.map(hint => CharWidth.of(hint.keys)).max
    val lines     = rows.map { hint =>
      hint.keys + " " * (keyColumn - CharWidth.of(hint.keys) + WhichKeyGap) + hint.description
    }
    val title     = s"${prefix.spelling} …"
    val width     = math.min(size.width, math.max(lines.map(CharWidth.of).max, CharWidth.of(title) + 2) + 4)
    val height    = math.min(size.height, lines.size + 2)
    val box       = FilledElement(Element.panel(title)(lines.map(line => Element.text(line).length(1))*), theme.surface)
    Element.portal(math.max(0, size.width - width - 1), math.max(0, size.height - height - 1), width, height)(box)
