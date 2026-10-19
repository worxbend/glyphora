package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Color, Rect, Style, Widget}
import io.worxbend.tui.testsupport.BufferAssertions.trimmedLines
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

/** `SearchState` driving the highlight and scroll-to-match of the scrolling views: `Log`, `ScrollView` and any widget
  * wrapped in `Searchable` (how `markdown` is searched).
  */
final class SearchSpec extends AnyFunSuite:

  private val matchBg   = Some(Color.Yellow)
  private val currentBg = Some(Color.BrightRed)

  /** 100 lines; every 10th one (10, 20, … 100) is a warning. */
  private def hundredLines: LogState =
    val state = LogState()
    (1 to 100).foreach(n => state.append(if n % 10 == 0 then s"$n WARN disk at $n%" else s"$n ok"))
    state

  private def render(log: LogState, search: SearchState, width: Int = 24, height: Int = 5): Buffer =
    val buffer = Buffer(Rect(0, 0, width, height))
    Log().render(buffer.area, buffer, log, search)
    buffer

  test("a log searched for warn highlights every visible match, the current one apart"):
    val log    = hundredLines
    val search = SearchState()
    search.query = "warn"
    val buffer = render(log, search)
    // the log follows the tail, so the first match at or after the top of the window is line 100
    assert(trimmedLines(buffer) == Seq("96 ok", "97 ok", "98 ok", "99 ok", "100 WARN disk at 100%"))
    assert(search.matches.size == 10)
    assert(search.currentMatch.contains(SearchMatch(row = 99, column = 4, width = 4)))
    assert(buffer.get(4, 4).style.bg == currentBg)
    assert(buffer.get(8, 4).style.bg != currentBg)
    GoldenFrames.assertMatchesStyled("search-log-warn", buffer)

  test("n moves to the next match and the log scrolls to show its row"):
    val log    = hundredLines
    val search = SearchState()
    search.query = "WARN"
    val _      = render(log, search)
    assert(search.next()) // wraps from line 100 to line 10
    val buffer  = render(log, search)
    assert(search.currentMatch.map(_.row).contains(9))
    assert(trimmedLines(buffer).contains("10 WARN disk at 10%"))
    assert(!log.follow)
    assert(search.next())
    val next    = render(log, search)
    assert(trimmedLines(next).contains("20 WARN disk at 20%"))
    assert(search.previous() && search.previous())
    val wrapped = render(log, search)
    assert(trimmedLines(wrapped).last == "100 WARN disk at 100%")

  test("matching folds case unless the query has a capital, and is grapheme-aware"):
    val log    = LogState()
    log.append("Café café CAFÉ 日本日本")
    val search = SearchState()
    search.query = "café"
    val _      = render(log, search, width = 30, height = 1)
    // "cafe" + combining acute is one grapheme per letter, so it is a match of the same four clusters
    assert(search.matches.map(_.column) == Vector(0, 5, 10))
    search.query = "CAFÉ"
    val _      = render(log, search, width = 30, height = 1)
    assert(search.matches.map(_.column) == Vector(10))
    search.query = "本日"
    val wide   = render(log, search, width = 30, height = 1)
    assert(search.matches == Vector(SearchMatch(0, 17, 4)))
    assert(wide.get(17, 0).style.bg == currentBg)

  test("an empty query clears the highlight and the matches"):
    val log    = hundredLines
    val search = SearchState()
    search.query = "warn"
    val _      = render(log, search)
    search.clear()
    val buffer = render(log, search)
    assert(search.matches.isEmpty && search.currentMatch.isEmpty && search.query.isEmpty)
    assert(buffer.get(4, 4).style == Style.Default)

  test("every mutation moves the version, so a host redraws on it"):
    val search = SearchState()
    val before = search.version
    search.query = "x"
    assert(search.version > before)
    val typed  = search.version
    search.input.insert("y")
    assert(search.version > typed && search.query == "xy")

  test("a searched scroll view finds matches below the fold and scrolls to the current one"):
    val content: Widget = (area, buffer) =>
      (0 until area.height).foreach(row => buffer.setString(area.x, area.y + row, s"row $row", Style.Default))
    val view            = ScrollView(content, contentHeight = 50)
    val state           = ScrollViewState()
    val search          = SearchState()
    search.query = "row 4"
    val buffer          = Buffer(Rect(0, 0, 10, 4))
    view.render(buffer.area, buffer, state, search)
    assert(search.matches.map(_.row) == Vector(4, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49))
    assert(state.offset == 1)
    assert(buffer.get(0, 4 - state.offset).style.bg == currentBg)
    assert(search.next())
    val scrolled        = Buffer(Rect(0, 0, 10, 4))
    view.render(scrolled.area, scrolled, state, search)
    assert(trimmedLines(scrolled).exists(_.startsWith("row 40")))
    assert(scrolled.get(0, 40 - state.offset).style.bg == currentBg)
    assert(search.next())
    view.render(scrolled.area, scrolled, state, search)
    assert(state.offset == 38)
    assert(scrolled.get(0, 41 - state.offset).style.bg == currentBg)
    assert(scrolled.get(0, 40 - state.offset).style.bg == matchBg)

  test("a scroll view the reader scrolled away from is not dragged back on the next frame"):
    val content: Widget = (area, buffer) =>
      (0 until area.height).foreach(row => buffer.setString(area.x, area.y + row, s"row $row", Style.Default))
    val view            = ScrollView(content, contentHeight = 50)
    val state           = ScrollViewState()
    val search          = SearchState()
    search.query = "row 30"
    val buffer          = Buffer(Rect(0, 0, 10, 4))
    view.render(buffer.area, buffer, state, search)
    assert(state.offset == 27)
    state.first()
    view.render(buffer.area, buffer, state, search)
    assert(state.offset == 0)

  /** Content that writes `row n` on every row and remembers the height of every buffer it was handed. */
  private final class CountingRows:
    val heights: collection.mutable.ArrayBuffer[Int] = collection.mutable.ArrayBuffer()
    val widget: Widget                               = (area, buffer) =>
      heights += buffer.area.height
      (0 until area.height).foreach(row => buffer.setString(area.x, area.y + row, s"row $row", Style.Default))

  test("a searched scroll view paints its whole content once per content and query, then only its window"):
    val rows   = CountingRows()
    val view   = ScrollView(rows.widget, contentHeight = 500)
    val state  = ScrollViewState()
    val search = SearchState()
    search.query = "row 4"
    val buffer = Buffer(Rect(0, 0, 10, 4))
    view.render(buffer.area, buffer, state, search)
    assert(rows.heights.toSeq == Seq(500), "the first frame of a query has to look at everything")
    state.scrollDown(2)
    view.render(buffer.area, buffer, state, search)
    assert(rows.heights.last == 4, "a scroll under the same query reuses the matches and paints only the window")
    assert(search.matches.size == 111)
    assert(search.next())
    view.render(buffer.area, buffer, state, search)
    assert(rows.heights.last == 4)
    assert(trimmedLines(buffer).exists(_.startsWith("row 40")))
    assert(buffer.get(0, 40 - state.offset).style.bg == currentBg, "the cached match is still highlighted")
    assert(search.next())
    view.render(buffer.area, buffer, state, search)
    assert(rows.heights.last == 4)
    assert(buffer.get(0, 41 - state.offset).style.bg == currentBg)
    assert(buffer.get(0, 40 - state.offset).style.bg == matchBg)
    search.query = "row 49"
    view.render(buffer.area, buffer, state, search)
    assert(rows.heights.last == 500, "a new query searches again")
    ScrollView(CountingRows().widget, contentHeight = 500).render(buffer.area, buffer, state, search)
    assert(rows.heights.last == 500, "the old content's cache is not reused for new content")

  test("a scroll view's content identity stands in for a content widget rebuilt every frame"):
    val first  = CountingRows()
    val second = CountingRows()
    val third  = CountingRows()
    val state  = ScrollViewState()
    val search = SearchState()
    search.query = "row 7"
    val buffer = Buffer(Rect(0, 0, 10, 4))
    ScrollView(first.widget, 100, contentIdentity = Some("doc-1")).render(buffer.area, buffer, state, search)
    ScrollView(second.widget, 100, contentIdentity = Some("doc-1")).render(buffer.area, buffer, state, search)
    assert(second.heights.toSeq == Seq(4), "same identity: the matches are reused")
    ScrollView(third.widget, 100, contentIdentity = Some("doc-2")).render(buffer.area, buffer, state, search)
    assert(third.heights.toSeq == Seq(100), "a different identity searches again")

  test("a searched log re-matches after it grows, and a scroll keeps its matches"):
    val log    = hundredLines
    val search = SearchState()
    search.query = "warn"
    val _      = render(log, search)
    assert(search.matches.size == 10)
    log.scrollUp(30)
    val _      = render(log, search)
    assert(search.matches.size == 10)
    log.append("101 WARN late")
    val _      = render(log, search)
    assert(search.matches.size == 11)
    assert(search.matches.last.row == 100)

  test("Searchable highlights what the wrapped widget drew, across the whole area"):
    val content: Widget = (area, buffer) => buffer.setString(area.x, area.y + 2, "| a | warn |", Style.Default)
    val search          = SearchState()
    search.query = "warn"
    val buffer          = Buffer(Rect(0, 0, 12, 3))
    Searchable(content).render(buffer.area, buffer, search)
    assert(trimmedLines(buffer).last == "| a | warn |")
    assert(search.matches == Vector(SearchMatch(2, 6, 4)))
    assert((6 until 10).forall(x => buffer.get(x, 2).style.bg == currentBg))
    assert(buffer.get(5, 2).style.bg.isEmpty && buffer.get(10, 2).style.bg.isEmpty)

  test("a match inside a markdown table cell is highlighted without moving a column"):
    val table    =
      """| Name | City | Qty |
        #|:-----|:----:|----:|
        #| Ann  | 東京 | 1 |
        #| Bartholomew | Rome | 200 |""".stripMargin('#')
    val markdown = Markdown(table)
    val plain    = Buffer(Rect(0, 0, 40, 4))
    markdown.render(plain.area, plain)
    val search   = SearchState()
    search.query = "東京"
    val searched = Buffer(Rect(0, 0, 40, 4))
    Searchable(markdown).render(searched.area, searched, search)
    assert(trimmedLines(searched) == trimmedLines(plain))
    assert(search.matches == Vector(SearchMatch(2, 14, 4)))
    assert(searched.get(14, 2).style.bg == currentBg && searched.get(16, 2).style.bg == currentBg)
    assert(searched.get(13, 2).style == plain.get(13, 2).style)
    assert(searched.get(18, 2).style == plain.get(18, 2).style)
    GoldenFrames.assertMatchesStyled("search-markdown-table", searched)
