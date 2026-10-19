package io.worxbend.tui.dsl

import io.worxbend.tui.core.{
  KeyCode,
  KeyEvent,
  Line,
  MouseButton,
  MouseEvent,
  MouseEventKind,
  Rect,
  Text,
  Versioned,
  Widget,
}
import io.worxbend.tui.widgets as w

import java.nio.file.Path

/** A scrollable single-selection list. Up/Down move the selection while focused, the wheel does the same on hover; the
  * widget scrolls to keep the selection visible. `state` is caller-owned, so the app can read or set the selection.
  *
  * `direction` says which edge the rows are anchored to — call [[bottomToTop]] for the chat-transcript shape.
  *
  * An item is a plain `String`, a styled [[Line]], or a whole [[Text]], and the three may be mixed in one list — the
  * same union [[w.ListView]] itself accepts. A plain string is the common case and needs no ceremony; a `Line` is how a
  * single row carries its own colour (a red failure, a dimmed disabled entry) or several styles at once; a `Text` is
  * how one item takes several rows, a title with a dimmed subtitle under it being the usual reason. Selection and the
  * keyboard moves count items, not rows, so one press of Down still moves past a whole multi-row item.
  */
final case class ListElement(
    items: Seq[String | Line | Text],
    state: w.ListState,
    direction: w.ListDirection = w.ListDirection.TopToBottom,
    highlightSpacing: w.HighlightSpacing = w.HighlightSpacing.Always,
    highlightSymbolOverride: Option[String] = None,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = ListElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state)
  override def debugName: String                                             = "list"
  private[dsl] override def componentRole: Option[ComponentRole]             = Some(ListRole)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(wheelScrolls(() => state.selectPrevious(items.size), () => state.selectNext(items.size)))

  /** Anchors the list to the bottom of its area and grows it upward — the chat-transcript or log-tail shape, where the
    * first item of the sequence is drawn on the bottom row. Feed the items newest-first.
    */
  def bottomToTop: ListElement = copy(direction = w.ListDirection.BottomToTop)

  /** Chooses when the columns holding the `> ` selection marker are reserved; see [[w.HighlightSpacing]]. The default
    * reserves them always, so the text never shifts sideways.
    */
  def highlightGutter(spacing: w.HighlightSpacing): ListElement = copy(highlightSpacing = spacing)

  /** Replaces the `> ` marker drawn in front of the selected row.
    *
    * The marker's display width is reserved on *every* row (unless [[highlightGutter]] says otherwise), so a wider
    * marker shifts the whole list right, not only the selected line. The width is counted in terminal columns through
    * `CharWidth`, so a two-column marker such as `"▶ "` reserves two columns and not two `Char`s.
    */
  def highlightSymbol(symbol: String): ListElement                       = copy(highlightSymbolOverride = Some(symbol))
  def widget: Widget                                                     =
    // no whole-body focus styling: the selection highlight is the focus cue for scrollable widgets
    val view = w.ListView(
      items,
      direction = direction,
      highlightSpacing = highlightSpacing,
      style = componentStyled(props),
      highlightStyle = selectedRowStyle(props),
      highlightSymbol = highlightSymbolOverride.getOrElse("> "),
    )
    (area, buffer) => view.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): ListElement           = copy(props = props)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler] =
    Some(
      selectionKeys(() => state.selectNext(items.size), () => state.selectPrevious(items.size))
        .orElse(
          selectionJumpKeys(
            () => state.selectFirst(items.size),
            () => state.selectLast(items.size),
            delta => state.selectBy(items.size, delta),
          )
        )
    )

/** A collapsible tree over an in-memory node list. Up/Down move the selection through the *visible* rows and Enter
  * expands or collapses the selected branch (a no-op on a leaf), all while focused; the wheel moves the selection on
  * hover, as it does over a `list`.
  */
final case class TreeElement(
    nodes: Seq[w.TreeNode],
    state: w.TreeState[?],
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = TreeElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state)
  override def debugName: String                                             = "tree"
  private[dsl] override def componentRole: Option[ComponentRole]             = Some(ListRole)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(wheelScrolls(() => state.selectPrevious(nodes), () => state.selectNext(nodes)))
  def widget: Widget                                                         =
    // no whole-body focus styling: the selection highlight is the focus cue for scrollable widgets
    val tree = w.Tree(nodes, style = componentStyled(props), highlightStyle = selectedRowStyle(props))
    (area, buffer) => tree.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): TreeElement               = copy(props = props)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(
      keys { case KeyEvent(KeyCode.Enter, _) => state.toggle(nodes) }
        .orElse(selectionKeys(() => state.selectNext(nodes), () => state.selectPrevious(nodes)))
    )

/** A vertical menu / dropdown / context menu popup. Up/Down move the highlight (skipping separators and disabled
  * items), Enter (or a click) fires `onSelect` with the chosen index, the wheel scrolls. Escape is left unconsumed so
  * an enclosing app can close it.
  */
final case class MenuElement(
    items: Seq[w.MenuEntry],
    state: w.MenuState,
    onSelect: Int => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = MenuElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state)
  override def debugName: String                                             = "menu"
  private[dsl] override def componentRole: Option[ComponentRole]             = Some(ListRole)
  def widget: Widget                                                         =
    val menu = w.Menu(items, style = componentStyled(props), highlightStyle = selectedRowStyle(props))
    (area, buffer) => menu.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): MenuElement               = copy(props = props)
  private[dsl] override def claim: SizeClaim                                 =
    // the popup asks for exactly the box it paints; `Fill` is the honest fallback if it ever stops knowing that
    val menu = w.Menu(items)
    (menu.widthAt(0), menu.heightAt(0)) match
      case (Some(width), Some(height)) => SizeClaim.box(width, height)
      case _                           => SizeClaim.Fill
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(
      keys { case KeyEvent(KeyCode.Enter | KeyCode.Char(' '), _) =>
        state.selected.filter(index => items.lift(index).exists(_.selectable)).foreach(onSelect)
      }.orElse(selectionKeys(() => state.selectNext(items), () => state.selectPrevious(items)))
    )
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] = Some(handleMouse)

  /** Rows a bordered menu draws above its first item — just the top border, so a press on it resolves to no item. */
  private val MenuBorderRows = 1

  private def handleMouse(event: MouseEvent, area: Rect): Boolean =
    event.kind match
      case MouseEventKind.ScrollUp   =>
        state.selectPrevious(items)
        true
      case MouseEventKind.ScrollDown =>
        state.selectNext(items)
        true
      case MouseEventKind.Down       =>
        val row = event.position.y - (area.y + MenuBorderRows) + state.offset
        if row >= 0 && row < items.size && items(row).selectable then
          state.selected = Some(row)
          onSelect(row)
        true
      case _                         => false

/** A multi-select list: Up/Down move the cursor, Space toggles membership of the cursor row; the wheel moves the cursor
  * on hover, as it does over a `list`.
  *
  * `selected` is the set the view read (tracked at construction, see [[CheckboxElement]]); `onToggle` is handed the row
  * whose membership Space flipped.
  */
final case class SelectionListElement(
    items: Seq[String],
    selected: Set[Int],
    onToggle: Int => Unit,
    state: w.ListState,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = SelectionListElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state)
  override def debugName: String                                             = "selectionList"
  private[dsl] override def componentRole: Option[ComponentRole]             = Some(ListRole)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(wheelScrolls(() => state.selectPrevious(items.size), () => state.selectNext(items.size)))
  def widget: Widget                                                         =
    val rendered = items.zipWithIndex.map { (item, index) =>
      val marker = if selected.contains(index) then "[x] " else "[ ] "
      Line.raw(marker + item)
    }
    val view     = w.ListView(rendered, style = componentStyled(props), highlightStyle = selectedRowStyle(props))
    (area, buffer) => view.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): SelectionListElement      = copy(props = props)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(
      keys { case KeyEvent(KeyCode.Char(' '), _) => state.selected.foreach(onToggle) }
        .orElse(selectionKeys(() => state.selectNext(items.size), () => state.selectPrevious(items.size)))
        .orElse(
          selectionJumpKeys(
            () => state.selectFirst(items.size),
            () => state.selectLast(items.size),
            delta => state.selectBy(items.size, delta),
          )
        )
    )

/** A file chooser over a [[FilePickerState]]: arrows navigate, Enter opens directories or accepts a file into
  * `state.chosen`, and the wheel moves the selection on hover. `chosen` is the accepted path the view read, tracked at
  * construction.
  */
final case class FilePickerElement(
    state: FilePickerState,
    chosen: Option[Path],
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = FilePickerElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state.tree)
  override def debugName: String                                             = "filePicker"
  private[dsl] override def componentRole: Option[ComponentRole]             = Some(ListRole)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(wheelScrolls(() => state.tree.selectPrevious(), () => state.tree.selectNext()))
  def widget: Widget                                                         =
    val tree = w.DirectoryTree(style = componentStyled(props), highlightStyle = selectedRowStyle(props))
    (area, buffer) =>
      val treeArea   = Rect(area.x, area.y, area.width, math.max(0, area.height - 1))
      tree.render(treeArea, buffer, state.tree)
      val chosenLine = chosen.map(path => s"→ $path").getOrElse("→ (nothing selected)")
      buffer.setString(area.x, area.bottom - 1, chosenLine, props.style.dim)
  private[dsl] def withProps(props: ElementProps): FilePickerElement         = copy(props = props)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(
      // the guard keeps Enter with nothing selected unconsumed, so it still reaches the app's own bindings
      keys { case KeyEvent(KeyCode.Enter, _) if state.tree.selected.isDefined => openOrAccept(state.tree.selected) }
        .orElse(selectionKeys(() => state.tree.selectNext(), () => state.tree.selectPrevious()))
    )

  /** Uses snapshot metadata to toggle directories; acquisition happens in this event handler, never in painting. */
  private def openOrAccept(selected: Option[Path]): Unit =
    selected.filter(state.tree.visiblePaths().contains).foreach { path =>
      val wasExpanded = state.tree.expanded.contains(path)
      state.tree.toggle()
      // toggle changes membership only for a known directory, without probing the filesystem again.
      if wasExpanded != state.tree.expanded.contains(path) then state.tree.loadVisible()
      else state.chosen.set(Some(path))
    }

/** A filesystem snapshot browser. Up/Down move the selection, Enter expands or collapses the selected directory, while
  * focused; the wheel moves the selection on hover. Neither painting nor navigation acquires listings. The caller loads
  * visible branches explicitly and requests a redraw after installing a snapshot.
  */
final case class DirectoryTreeElement(
    state: w.DirectoryTreeState,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = DirectoryTreeElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state)
  override def debugName: String                                             = "directoryTree"
  private[dsl] override def componentRole: Option[ComponentRole]             = Some(ListRole)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(wheelScrolls(() => state.selectPrevious(), () => state.selectNext()))
  def widget: Widget                                                         =
    val tree = w.DirectoryTree(style = componentStyled(props), highlightStyle = selectedRowStyle(props))
    (area, buffer) => tree.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): DirectoryTreeElement      = copy(props = props)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(
      keys { case KeyEvent(KeyCode.Enter, _) => state.toggle() }
        .orElse(selectionKeys(() => state.selectNext(), () => state.selectPrevious()))
    )

/** A sortable, filterable table. Up/Down move the row selection while focused, and PageUp/PageDown turn the page once
  * `state.paging` is set (they are left unconsumed otherwise, so they keep bubbling). Sorting and filtering have no
  * built-in keys — drive `state.sortBy`/`state.setFilter` from the app's own bindings.
  *
  * A click on a body row selects it; a drag that starts on the table and covers a couple of cells selects cell text
  * instead (see `.selectable`). Alone among the selectable collections this one has no wheel behavior. Its selection
  * indexes the *visible* page (`table.visibleRows(state)`), so a wheel step would have to decide whether it moves
  * within the page or turns it, and either answer is wrong for half the tables; the page keys say which one the app
  * meant. Bind `onMouseEvent` if this table wants one of the two.
  */
final case class DataTableElement[K](
    table: w.DataTable[K],
    state: w.DataTableState[K],
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = DataTableElement[K]

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]              = Seq(state)
  override def debugName: String                                       = "dataTable"
  def widget: Widget                                                   =
    (area, buffer) => table.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): DataTableElement[K] = copy(props = props)

  /** A left press on a body row selects it. In a mouse-capturing app a drag that starts here and covers a couple of
    * cells selects the cells' text instead of doing anything more to the rows — see [[TextSelection]].
    */
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some { (event, area) =>
      val pressed = event.kind == MouseEventKind.Down && event.button == MouseButton.Left
      table.rowAt(area, event.position.y, state).filter(_ => pressed) match
        case Some(index) =>
          state.selected = Some(index)
          true
        case None        => false
    }

  // the visible page is filtered and paginated on each call; name it once so a key press computes it once.
  // it stays a `def`: the thunks below run after `state` may already have moved, so the count is read at call time
  private def rowCount: Int = table.visibleRows(state).size

  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler] =
    Some(
      keys {
        case KeyEvent(KeyCode.PageDown, _) if state.paging.nonEmpty => state.nextPage(table.filteredRows(state).size)
        case KeyEvent(KeyCode.PageUp, _) if state.paging.nonEmpty   => state.previousPage()
      }.orElse(
        selectionKeys(
          () => state.selectNext(rowCount),
          () => state.selectPrevious(rowCount),
        )
      ).orElse(
        // the paging branch above is guarded on `state.paging.nonEmpty` and runs first, so a paged table keeps
        // PageUp/PageDown turning pages; only an unpaged one falls through to jumping the selection by a screenful
        selectionJumpKeys(
          () => state.selectFirst(rowCount),
          () => state.selectLast(rowCount),
          delta => state.selectBy(rowCount, delta),
        )
      )
    )

/** A scrollable log panel: Up/Down (and PageUp/PageDown) scroll while focused; the tail re-follows when scrolled back
  * to the bottom.
  */
final case class LogElement(
    state: w.LogState,
    props: ElementProps = ElementProps(focusable = true),
    search: Option[w.SearchState] = None,
) extends Element:
  type Self = LogElement

  /** The caller-owned states this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state) ++ search
  override def debugName: String                                             = "log"
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(wheelScrolls(() => state.scrollUp(), () => state.scrollDown()))
  def widget: Widget                                                         =
    val log = w.Log(props.style)
    search match
      case Some(found) => (area, buffer) => log.render(area, buffer, state, found)
      case None        => (area, buffer) => log.render(area, buffer, state)

  /** Searches every line the log holds for `state`'s query, highlighting the matches and scrolling to the current one;
    * `n`/`N` step through them while focused. Pair it with a [[searchBar]] over the same state. See [[w.SearchState]].
    */
  def searchable(state: w.SearchState): LogElement                       = copy(search = Some(state))
  private[dsl] def withProps(props: ElementProps): LogElement            = copy(props = props)
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler] =
    Some(searchSteps(search).orElse(scrollKeys(rows => state.scrollUp(rows), rows => state.scrollDown(rows))))

/** The items a [[LazyListElement]] built for one frame: item `from` onwards, in order. */
private[dsl] final case class LazyWindow(from: Int, items: Vector[Element]):
  def item(index: Int): Option[Element] = items.lift(index - from)

/** A virtual list of `count` items, each `itemHeight` rows tall, that builds, decorates and paints only the items the
  * viewport shows — plus one either side — however large `count` is.
  *
  * The items are built during the frame's resolution pass, not at the factory call, because which items are visible is
  * a question about the frame: the pass asks the caller-owned [[w.LazyListState]] for its build window (the last
  * frame's viewport, re-anchored on the current offset and selection; the frame's height before the first render) and
  * calls `build` for exactly those indices. What it builds is then an ordinary part of the tree: focusables in it take
  * Tab stops, keys bubble out of it, the bounds recorder sees it, a focused text field in it drives the caret. An item
  * that falls outside the window between frames — the enclosing scroll view jumped a page — is left blank for one frame
  * and the miss asks the host for the frame that fills it.
  *
  * While focused, Up/Down move the selection, Home/End jump to the ends and PageUp/PageDown move a page; the wheel
  * moves the selection on hover, and a press selects the item under the pointer. The selected item is drawn in the same
  * highlight a `list` uses.
  *
  * Focus follows a *keyed* item (`.key(...)`) across frames as the window moves; an unkeyed focusable inside an item is
  * identified by its tab position, which shifts as items scroll in and out — key anything focusable.
  *
  * Drawn with no host behind it (a construction test rendering `widget` straight into a buffer), the node builds each
  * visible item at render time instead; with no focus pass behind that, focusables in it are inert.
  */
final case class LazyListElement(
    count: Int,
    itemHeight: Int,
    build: Range => Seq[Element],
    state: w.LazyListState,
    window: Option[LazyWindow] = None,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = LazyListElement

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]        = Seq(state)
  override def debugName: String                                 = "lazyList"
  private[dsl] override def componentRole: Option[ComponentRole] = Some(ListRole)
  override def children: Seq[Element]                            = window.fold(Seq.empty[Element])(_.items)

  private[dsl] def withProps(props: ElementProps): LazyListElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): LazyListElement =
    window.fold(this)(built => copy(window = Some(built.copy(items = children.toVector))))

  /** This node holding the items its state's build window names for a frame `rows` tall — the resolution pass's one
    * write.
    */
  private[dsl] def builtFor(rows: Int): LazyListElement =
    val range = state.buildWindow(count, rows, itemHeight)
    copy(window = Some(LazyWindow(range.start, build(range).toVector)))

  /** Every item's rows, unless an explicit constraint says otherwise — what a scroll view sizes itself to. */
  private[dsl] override def intrinsicHeight(width: Int): Option[Int] =
    constrainedHeight(view(_ => None).heightAt(width))

  private def view(item: Int => Option[Widget]): w.LazyListView =
    w.LazyListView(count, itemHeight, item, highlightStyle = selectedRowStyle(props))

  def widget: Widget =
    val items: Int => Option[Widget] = window match
      case Some(built) => index => built.item(index).map(_.widget)
      case None        => index => build(index until index + 1).headOption.map(_.widget)
    val list                         = view(items)
    (area, buffer) => list.render(area, buffer, state)

  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler] =
    Some(
      selectionKeys(() => state.selectNext(count), () => state.selectPrevious(count))
        .orElse(
          keys {
            case KeyEvent(KeyCode.Home, _)     => state.selectFirst(count)
            case KeyEvent(KeyCode.End, _)      => state.selectLast(count)
            case KeyEvent(KeyCode.PageDown, _) => state.pageBy(count, 1)
            case KeyEvent(KeyCode.PageUp, _)   => state.pageBy(count, -1)
          }
        )
    )

  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    val wheel = wheelScrolls(() => state.selectPrevious(count), () => state.selectNext(count))
    Some { (event, area) =>
      event.kind match
        case MouseEventKind.Down =>
          val row   = event.position.y - area.y
          val index = state.offset + row / math.max(1, itemHeight)
          if row >= 0 && index < count then
            state.selected = Some(index)
            true
          else false
        case _                   => wheel(event, area)
    }

/** A tree with columns over a caller-owned [[w.TreeState]]. While focused, Up/Down move the selection through the
  * visible rows, Enter toggles the selected branch, Right expands it and Left collapses it; the wheel moves the
  * selection on hover. The selected row wears the focus style across every column.
  */
final case class TreeTableElement[K](
    table: w.TreeTable[K],
    state: w.TreeState[K],
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = TreeTableElement[K]

  /** The caller-owned state this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned]                    = Seq(state)
  override def debugName: String                                             = "treeTable"
  private def nodes                                                          = table.nodes
  def widget: Widget                                                         =
    val current = table.copy(style = props.style, highlightStyle = props.focusStyle)
    (area, buffer) => current.render(area, buffer, state)
  private[dsl] def withProps(props: ElementProps): TreeTableElement[K]       = copy(props = props)
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some(wheelScrolls(() => state.selectPrevious(nodes), () => state.selectNext(nodes)))
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]     =
    Some(
      keys {
        case KeyEvent(KeyCode.Enter, _)                                                    => state.toggle(nodes)
        case KeyEvent(KeyCode.Right, _) if !state.selected.exists(state.expanded.contains) => state.toggle(nodes)
        case KeyEvent(KeyCode.Left, _) if state.selected.exists(state.expanded.contains)   => state.toggle(nodes)
      }.orElse(selectionKeys(() => state.selectNext(nodes), () => state.selectPrevious(nodes)))
    )
