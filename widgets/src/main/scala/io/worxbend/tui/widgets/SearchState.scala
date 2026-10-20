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
  * who scrolls away from it is not dragged back on the next frame.
  *
  * A `SearchState` records the matches of one view. To search two views with one query — a search bar over a split
  * diff, a log beside its source — hand each view its own [[view]] of the state: `search.view("left")` and
  * `search.view("right")` share this state's [[input]], so one query reaches both, while each keeps its own matches,
  * current match and scroll-to-match. [[next]], [[previous]] and [[clear]] on this state reach every view; on a view
  * they act on that view alone. Rendering two views with the *same* state still makes them overwrite each other's
  * matches.
  *
  * The query is edited through [[input]], the [[TextInputState]] a DSL `searchBar(state)` types into; [[query_=]] sets
  * it in one go.
  *
  * Render-thread-only. Every mutation — typing into [[input]] included — raises `version` (see [[Versioned]]), so a DSL
  * node that paints this state schedules the frame that shows it. Matches recorded by a render do not move it: a frame
  * must not schedule another frame just by having been drawn.
  */
final class SearchState private (
    /** The editable query: what a `searchBar` types into. Reading [[query]] reads this. Shared with every [[view]]. */
    val input: TextInputState,
    owner: Option[SearchState],
) extends Versioned:

  /** A fresh search: an empty query, no matches, no views. */
  def this() = this(TextInputState(), None)

  private val views = scala.collection.mutable.LinkedHashMap.empty[Any, SearchState]

  private var mutations: Long                 = 0L
  private var found: Vector[SearchMatch]      = Vector.empty
  private var currentIndex: Option[Int]       = None
  private var recordedQuery: String           = ""
  private var navigationCount: Int            = 0
  private var revealedNavigation: Option[Int] = None

  // The match cache: what the view found for `cachedQuery` over the content `cachedKey` stood for. A view searches its
  // whole content only when either side of that pair moves, and otherwise reuses these — see [[cachedMatches]].
  private var cachedKey: Option[Any]      = None
  private var cachedQuery: String         = ""
  private var cached: Vector[SearchMatch] = Vector.empty

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

  /** The per-view state for the view `key` names — created on first use, the same instance ever after. It shares this
    * state's query and keeps its own matches; see the class notes. Asked of a view, it answers its owner's view, so
    * views do not nest.
    */
  def view(key: Any): SearchState =
    owner match
      case Some(root) => root.view(key)
      case None       => views.getOrElseUpdate(key, new SearchState(input, Some(this)))

  /** Makes the next match current, wrapping from the last to the first — in this state and in every [[view]] of it;
    * answers whether there was one to move to.
    */
  def next(): Boolean = stepAll(1)

  /** Makes the previous match current, wrapping from the first to the last, in this state and every [[view]]. */
  def previous(): Boolean = stepAll(-1)

  /** On the root, empties the shared query and forgets every view's matches (Esc in a search bar). On a view, forgets
    * only that view's matches; its next render may find them again for the unchanged query.
    */
  def clear(): Unit =
    if owner.isEmpty then input.clear()
    forget()
    views.valuesIterator.foreach(_.forget())

  private def forget(): Unit =
    found = Vector.empty
    currentIndex = None
    recordedQuery = ""
    cachedKey = None
    mutations += 1

  private def stepAll(delta: Int): Boolean =
    val own = step(delta)
    views.valuesIterator.map(_.step(delta)).foldLeft(own)(_ || _)

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

  /** The matches a view found for the current query over the content `key` stands for, if it has searched exactly that
    * pair before. `key` is the view's equality-comparable stand-in for what its content draws — the content widget
    * itself, an explicit content identity, a log's revision — so a frame that only scrolls, steps or redraws reuses the
    * matches instead of searching (and, for a scroll view, painting) the whole document again. One entry: the state
    * belongs to one view.
    */
  private[widgets] def cachedMatches(key: Any): Option[Vector[SearchMatch]] =
    if cachedKey.contains(key) && cachedQuery == query then Some(cached) else None

  /** Remembers `matches` as what the current query finds over the content `key` stands for. */
  private[widgets] def remember(key: Any, matches: Vector[SearchMatch]): Unit =
    cachedKey = Some(key)
    cachedQuery = query
    cached = matches

  /** [[cachedMatches]] for `key`, or `search()` remembered under it. */
  private[widgets] def matchesFor(key: Any)(search: => Vector[SearchMatch]): Vector[SearchMatch] =
    cachedMatches(key).getOrElse {
      val found = search
      remember(key, found)
      found
    }

  /** The current match, once per move: `Some` the first time it is asked after a query change or a step, then `None`
    * until the next one. What a view that scrolls itself calls before drawing its window.
    */
  private[widgets] def takeReveal(): Option[SearchMatch] =
    if revealedNavigation.contains(navigationCount) then None
    else
      revealedNavigation = Some(navigationCount)
      currentMatch
