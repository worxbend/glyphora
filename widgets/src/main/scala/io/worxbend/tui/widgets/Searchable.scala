package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, StatefulWidget, Style, Widget}

/** Any widget made searchable: draws `content`, finds the [[SearchState]]'s query in what it drew — over the whole
  * area, even the part a scroll view's window clips away — and highlights the matches. This is how the DSL searches
  * `markdown`: the highlight is a restyle of cells already drawn, so a match inside a table cell keeps the table's
  * column alignment.
  *
  * It does not scroll: matches are reported in area-relative content coordinates for the host to reveal. Searching
  * costs one extra paint of `content` when the buffer does not cover the whole area, once per query, content and area
  * size: later frames reuse the matches. `contentIdentity` stands in for `content` in that comparison when the caller
  * rebuilds the widget every frame; `None` compares the widget itself. Nothing at all without a query.
  */
final case class Searchable(
    content: Widget,
    contentIdentity: Option[Any] = None,
    matchStyle: Style = SearchHighlight.DefaultMatchStyle,
    currentMatchStyle: Style = SearchHighlight.DefaultCurrentStyle,
) extends StatefulWidget[SearchState]:

  def render(area: Rect, buffer: Buffer, state: SearchState): Unit =
    content.render(area, buffer)
    if !state.isActive || area.isEmpty then state.record(Vector.empty, 0)
    else
      val key   = (contentIdentity.getOrElse(content), area.width, area.height)
      val found = state.matchesFor(key) {
        val covered = area.intersection(buffer.area) == area
        val source  =
          if covered then buffer
          else
            val scratch = Buffer(area)
            content.render(area, scratch)
            scratch
        SearchHighlight.find(SearchHighlight.glyphsIn(source, area), state.query)
      }
      state.record(found, buffer.area.y - area.y)
      SearchHighlight.paint(buffer, area, area.x, area.y, state, matchStyle, currentMatchStyle)
