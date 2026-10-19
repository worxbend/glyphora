package io.worxbend.tui.widgets

import io.worxbend.tui.core.Versioned

/** One occurrence of a search query in a searched view: `width` columns starting at display column `column` of content
  * row `row`. Rows and columns are in the view's *content* coordinates — a log's line index, a scroll view's content
  * row, a wrapped widget's row inside its own area — not screen positions, so a match keeps its identity while the view
  * scrolls.
  */
final case class SearchMatch(row: Int, column: Int, width: Int)

/** Caller-owned incremental search over one scrolling view: the query, the matches the view found for it on its last
  * render, and which of them is current.
  *
  * The view does the finding. [[Log]], [[ScrollView]] and [[Searchable]] each take a `SearchState` in an overload of
  * `render`, look for the query in what they show — grapheme by grapheme, so a combining sequence, an emoji or a wide
  * CJK character is matched whole and never split, and precomposed and decomposed accents match each other — record the
  * matches here, highlight them, and scroll so the current one is on screen. Matching folds case unless the query holds
  * a capital letter (the `smartcase` rule [[TextAreaState]] uses as well).
  *
  * When the query changes, the first match at or after the top of the view's window becomes current; [[next]] and
  * [[previous]] step through the rest, wrapping round. The view scrolls to the current match once per move, so a reader
  * who scrolls away from it is not dragged back on the next frame. A `SearchState` belongs to one view: two views
  * sharing one would overwrite each other's matches.
  *
  * The query is edited through [[input]], the [[TextInputState]] a DSL `searchBar(state)` types into; [[query_=]] sets
  * it in one go.
  *
  * Render-thread-only. Every mutation — typing into [[input]] included — raises `version` (see [[Versioned]]), so a DSL
  * node that paints this state schedules the frame that shows it. Matches recorded by a render do not move it: a frame
  * must not schedule another frame just by having been drawn.
  */
final class SearchState extends Versioned:

  /** The editable query: what a `searchBar` types into. Reading [[query]] reads this. */
  val input: TextInputState = TextInputState()

  private var mutations: Long                 = 0L
  private var found: Vector[SearchMatch]      = Vector.empty
  private var currentIndex: Option[Int]       = None
  private var recordedQuery: String           = ""
  private var navigationCount: Int            = 0
  private var revealedNavigation: Option[Int] = None

  def version: Long = mutations + input.version

  /** The text being searched for; empty means no search. */
  def query: String = input.value

  /** Replaces the query — the next render finds its matches and makes the first one at or after the window current. */
  def query_=(text: String): Unit = input.setValue(text)

  /** Whether there is a query to search for. */
  def isActive: Boolean = query.nonEmpty

  /** The matches the view found on its last render, in document order. */
  def matches: Vector[SearchMatch] = found

  /** Index into [[matches]] of the current match, if there is one. */
  def current: Option[Int] = currentIndex

  def currentMatch: Option[SearchMatch] = currentIndex.flatMap(found.lift)

  /** Makes the next match current, wrapping from the last to the first; answers whether there was one to move to. */
  def next(): Boolean = step(1)

  /** Makes the previous match current, wrapping from the first to the last. */
  def previous(): Boolean = step(-1)

  /** Empties the query and forgets the matches — what Esc in a `searchBar` does. */
  def clear(): Unit =
    input.clear()
    found = Vector.empty
    currentIndex = None
    recordedQuery = ""
    mutations += 1

  /** A count of the moves that should scroll the current match into view — a query change or a step. The DSL keys its
    * scroll-to-match request on it, so one move scrolls once.
    */
  private[tui] def navigation: Int = navigationCount

  private def step(delta: Int): Boolean =
    if found.isEmpty then false
    else
      currentIndex = Some(currentIndex.fold(if delta > 0 then 0 else found.size - 1) { index =>
        Math.floorMod(index + delta, found.size)
      })
      navigationCount += 1
      mutations += 1
      true

  /** Records what a render found for the current query. A new query makes the first match at or after content row
    * `from` (the top of the view's window) current and asks for it to be revealed; the same query found again keeps the
    * current index, clamped, so content that grows under a search does not move the reader.
    */
  private[widgets] def record(matches: Vector[SearchMatch], from: Int): Unit =
    val q = query
    if q != recordedQuery then
      recordedQuery = q
      found = matches
      currentIndex = if matches.isEmpty then None else Some(math.max(0, matches.indexWhere(_.row >= from)))
      navigationCount += 1
    else
      found = matches
      currentIndex =
        if matches.isEmpty then None else Some(currentIndex.fold(0)(index => math.min(index, matches.size - 1)))

  /** The current match, once per move: `Some` the first time it is asked after a query change or a step, then `None`
    * until the next one. What a view that scrolls itself calls before drawing its window.
    */
  private[widgets] def takeReveal(): Option[SearchMatch] =
    if revealedNavigation.contains(navigationCount) then None
    else
      revealedNavigation = Some(navigationCount)
      currentMatch
