package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Measured, Rect, StatefulWidget, Style, VersionedState, Widget}

/** The caller-owned state of a [[LazyListView]]: which item is selected and which item is scrolled to the top.
  *
  * Both count *items*, not rows, the same way [[ListState]] does. The widget re-derives [[offset]] on every render to
  * clamp it to the last page and to keep the selection on screen, so setting a selection is enough to scroll to it.
  *
  * Besides the two fields the caller writes, the state remembers what the last render saw — how many items the viewport
  * held and which item was on top — because whoever *builds* the items (the DSL's `lazyList`) has to decide which ones
  * to build before layout has run, and the last frame's viewport is the best answer available then. See
  * [[buildWindow]].
  *
  * '''Ownership and threads.''' Created and owned by the caller, mutated on the render thread only. Every public
  * mutator raises [[version]], so a host watching the state redraws after a write made outside the event path; what a
  * render records about itself does not, since it is not a change to what the state holds.
  */
final class LazyListState private (initialSelected: Option[Int], initialOffset: Int) extends VersionedState:

  private var selectedValue: Option[Int] = initialSelected
  private var offsetValue: Int           = math.max(0, initialOffset)

  /** Items wholly visible in the last render's viewport, `0` before the first one. */
  private var pageItems: Int = 0

  /** Items at least partly visible in the last render, which is one more than [[pageItems]] when the last is cut off.
    */
  private var visibleItems: Int = 0

  /** The first item the last render drew. */
  private var firstVisible: Int = 0

  /** Whether the last render scrolled the list itself, rather than being handed room for every item (inside a scroll
    * view, which clips instead).
    */
  private var scrolledItself: Boolean = true

  /** Whether the last render reached an item nobody had built for it — and drew a blank where it belongs. */
  private var missed: Boolean = false

  /** The selected item, or `None`. */
  def selected: Option[Int] = selectedValue

  def selected_=(value: Option[Int]): Unit =
    selectedValue = value.map(math.max(0, _))
    touch()

  /** The first item on screen; the widget re-derives it during render to keep the selection visible. */
  def offset: Int = offsetValue

  def offset_=(value: Int): Unit =
    offsetValue = math.max(0, value)
    touch()

  /** Moves the selection one item down, selecting the first item when nothing was selected. */
  def selectNext(count: Int): Unit = moveTo(count, selectedValue.fold(0)(_ + 1))

  /** Moves the selection one item up, selecting the first item when nothing was selected. */
  def selectPrevious(count: Int): Unit = moveTo(count, selectedValue.fold(0)(_ - 1))

  /** Selects the first item. */
  def selectFirst(count: Int): Unit = moveTo(count, 0)

  /** Selects the last item. */
  def selectLast(count: Int): Unit = moveTo(count, count - 1)

  /** Moves the selection `delta` items, clamped to the list; from no selection it counts from the first item. */
  def selectBy(count: Int, delta: Int): Unit = moveTo(count, selectedValue.getOrElse(0) + delta)

  /** Selects the item `delta` pages away — one page being the items the last render showed whole. */
  def pageBy(count: Int, pages: Int): Unit = selectBy(count, pages * math.max(1, pageItems))

  private def moveTo(count: Int, index: Int): Unit =
    if count > 0 then
      selectedValue = Some(math.max(0, math.min(count - 1, index)))
      offsetValue = settled(count, math.max(1, pageItems))
      touch()

  /** Whether the last render had to leave an item blank because it was not built — the builder's cue to build again. */
  private[tui] def missedItems: Boolean = missed

  /** The offset clamped to the last page of `count` items and moved just far enough to show the selection. */
  private def settled(count: Int, page: Int): Int =
    val maxOffset = math.max(0, count - page)
    val clamped   = math.max(0, math.min(offsetValue, maxOffset))
    val shown     = selectedValue.map(math.min(_, math.max(0, count - 1))) match
      case Some(selected) if selected < clamped         => selected
      case Some(selected) if selected >= clamped + page => selected - page + 1
      case _                                            => clamped
    math.max(0, math.min(shown, maxOffset))

  /** The items a builder should materialise for the next frame: what the last render showed, re-anchored on the current
    * offset and selection, plus one item of overscan on either side — `≤ viewport + 2` items, whatever `count` is.
    *
    * Before the first render there is no viewport to go on, so `fallbackRows` (the frame's height is the usual answer)
    * stands in for it. Inside a scroll view the list is handed room for every item and does not scroll itself; there
    * the window is anchored on the item the last render drew first, and the overscan is what keeps a one-row scroll of
    * the enclosing view from showing a blank.
    */
  def buildWindow(count: Int, fallbackRows: Int, itemHeight: Int): Range =
    if count <= 0 then 0 until 0
    else
      val height  = math.max(1, itemHeight)
      val visible = if visibleItems > 0 then visibleItems else (math.max(1, fallbackRows) + height - 1) / height
      val page    = if pageItems > 0 then pageItems else visible
      val first   = if scrolledItself then settled(count, page) else math.min(firstVisible, count - 1)
      math.max(0, first - 1) until math.min(count, first + visible + 1)

  /** What a render records about itself; not a mutation, so it leaves [[version]] alone. */
  private[widgets] def recordRender(
      offset: Int,
      page: Int,
      visible: Int,
      first: Int,
      itself: Boolean,
      missing: Boolean,
  ): Unit =
    offsetValue = offset
    pageItems = page
    visibleItems = visible
    firstVisible = first
    scrolledItself = itself
    missed = missing

  /** [[settled]] for the widget's render. */
  private[widgets] def settledOffset(count: Int, page: Int): Int = settled(count, page)

object LazyListState:

  /** A state with `selected` highlighted and `offset` scrolled to the top. */
  def apply(selected: Option[Int] = None, offset: Int = 0): LazyListState = new LazyListState(selected, offset)

/** A virtual list of `count` items, each `itemHeight` rows tall, that asks for — and draws — only the items on screen.
  *
  * `item(i)` is called once per visible item per render and nowhere else, so a list of a million items costs what a
  * screenful costs. It answers `None` for an item that is not available yet; the row is left blank and the state
  * records the miss ([[LazyListState]]), which is how the DSL's `lazyList` learns that the items it built for this
  * frame fell short of the viewport and builds again.
  *
  * Two ways of scrolling, decided by the area alone. Handed fewer rows than `count * itemHeight` — the usual case — the
  * list scrolls itself, by whole items, from the state's offset. Handed room for everything — the content of a scroll
  * view, which lays content out at full height and clips it with a window-sized buffer — it does not scroll at all and
  * draws only the items inside the buffer's bounds.
  *
  * The selected item has `highlightStyle` patched over the rows it occupies after it is drawn. An item that does not
  * fit whole at the bottom is drawn into the rows that remain.
  *
  * Named `LazyListView` rather than `LazyList` so it never shadows `scala.LazyList` in this package or under the DSL's
  * one import — the reason `ListView` is not `List`.
  */
final case class LazyListView(
    count: Int,
    itemHeight: Int,
    item: Int => Option[Widget],
    highlightStyle: Style = Style.Default.reverse,
) extends StatefulWidget[LazyListState]
    with Measured:

  private def rows: Int = math.max(1, itemHeight)

  /** Every item's rows, whatever the width — the content height a scroll view sizes itself to. */
  override def heightAt(width: Int): Option[Int] =
    val _ = width
    Some(math.min(Int.MaxValue.toLong, math.max(0, count).toLong * rows).toInt)

  def render(area: Rect, buffer: Buffer, state: LazyListState): Unit =
    val clip = area.intersection(buffer.area)
    if count > 0 && !area.isEmpty && !clip.isEmpty then
      val page    = math.max(1, area.height / rows)
      val itself  = count.toLong * rows > area.height
      val offset  = if itself then state.settledOffset(count, page) else 0
      // the items the clip shows, in item indices: from the one under the clip's top row to the one under its last
      val first   = math.min(count - 1, offset + (clip.y - area.y) / rows)
      val last    = math.min(count, offset + (clip.bottom - area.y + rows - 1) / rows)
      var missing = false
      (first until last).foreach { index =>
        val top  = area.y + (index - offset) * rows
        val cell = Rect(area.x, top, area.width, rows).intersection(area)
        item(index) match
          case Some(widget) => widget.render(cell, buffer)
          case None         => missing = true
        if state.selected.contains(index) then buffer.mapStyle(cell)(_.patch(highlightStyle))
      }
      state.recordRender(offset, page, last - first, first, itself, missing)
