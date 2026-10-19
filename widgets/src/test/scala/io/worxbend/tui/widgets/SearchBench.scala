package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, Style, Widget}
import io.worxbend.tui.testsupport.Bench

/** Times one frame of a *searched* scrolling view while a query is set — the cost the match cache removes.
  *
  * Run it with:
  * {{{
  * ./mill widgets.test.runMain io.worxbend.tui.widgets.SearchBench
  * }}}
  *
  * Each view is timed twice. *cached* is a steady-state frame: the same content and query as the last frame, the reader
  * scrolling, which reuses the matches and paints only the window. *uncached* hands the view a new content identity
  * every frame, which defeats the cache and so costs what every searched frame cost before it: a paint of the whole
  * document and a search of every row. Asserts nothing; see [[io.worxbend.tui.testsupport.Bench]].
  */
object SearchBench:

  private val viewport = Rect(0, 0, 80, 24)

  /** A 5 000-row document: every row its own line of prose, a match on every hundredth. */
  private val document: Widget = (area, buffer) =>
    var row = 0
    while row < area.height do
      val text = if row % 100 == 0 then s"$row needle in the hay" else s"$row lorem ipsum dolor sit amet"
      buffer.setString(area.x, area.y + row, text, Style.Default)
      row += 1

  def main(args: Array[String]): Unit =
    val _       = args
    val results = Seq(scrollView(cached = true), scrollView(cached = false), log(cached = true), log(cached = false))
    Bench.report("Searched views (one frame, query set)", results)
    println(s"checksum ${Bench.consumed}")

  private def scrollView(cached: Boolean): Bench.Result =
    val state  = ScrollViewState()
    val search = SearchState()
    search.query = "needle"
    val buffer = Buffer(viewport)
    var frame  = 0L
    Bench.measure(s"scrollView 5000 rows, ${if cached then "cached" else "uncached"}", iterations = 50) { () =>
      frame += 1
      val identity = if cached then 0L else frame
      ScrollView(document, 5000, contentIdentity = Some(identity)).render(viewport, buffer, state, search)
      state.scrollDown()
      Bench.consume(search.matches.size.toLong)
    }

  private def log(cached: Boolean): Bench.Result =
    val state  = LogState(maxLines = 10000)
    (0 until 10000).foreach(n => state.append(if n % 100 == 0 then s"$n WARN disk" else s"$n ok fine"))
    state.scrollUp(5000)
    val search = SearchState()
    search.query = "warn"
    val buffer = Buffer(viewport)
    Bench.measure(s"log 10000 lines, ${if cached then "cached" else "uncached"}", iterations = 50) { () =>
      // an append is a change to the text, so it is what defeats the cache
      if !cached then state.append("tick")
      Log().render(viewport, buffer, state, search)
      Bench.consume(search.matches.size.toLong)
    }
