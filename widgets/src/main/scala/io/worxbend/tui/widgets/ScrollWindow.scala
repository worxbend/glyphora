package io.worxbend.tui.widgets

/** The scroll-to-selection rule every selectable list widget follows.
  *
  * Widgets keep their scroll offset in caller-owned state and re-derive it during render, so this is a pure function of
  * the offset the caller last saw and the geometry of the current frame.
  */
private[widgets] object ScrollWindow:

  /** The offset to render at: `offset` clamped so the window never runs past the end of the content, then nudged by the
    * smallest amount that brings `selected` back inside it.
    *
    * Nudging by the minimum is what makes a one-row keyboard move scroll by one row instead of re-centering the view.
    * With no selection the clamped offset stands, so scrolling a list nobody has selected in still works.
    *
    * `padding` widens what "inside the window" means. With the default of zero the selection may sit on the very first
    * or very last visible row, which is the classic "the cursor is welded to the bottom line" feel: the reader cannot
    * see what is coming next. With `padding = 2` the window is nudged so that two further rows of content stay visible
    * on each side of the selection whenever the content has that much to show, so pressing "down" starts scrolling the
    * list underneath a cursor that has stopped two rows short of the edge.
    *
    * Two clamps keep that well behaved. The padding itself is capped at `(viewportHeight - 1) / 2`, because a padding
    * bigger than half the window would ask for more rows above and below the selection than the window holds, and the
    * two demands would then fight each other into an oscillation from frame to frame. And the resulting offset is
    * clamped back into `0 .. total - viewportHeight`, so near either end of the content — where there is nothing left
    * to reveal — the selection does reach the edge row rather than the list scrolling past its own contents.
    */
  def offsetFor(offset: Int, selected: Option[Int], total: Int, viewportHeight: Int, padding: Int = 0): Int =
    val maxOffset = math.max(0, total - viewportHeight)
    val clamped   = math.max(0, math.min(offset, maxOffset))
    selected match
      case None        => clamped
      case Some(index) =>
        val pad     = math.max(0, math.min(padding, (viewportHeight - 1) / 2))
        // the highest offset that still leaves `pad` rows of content visible above the selection, and the lowest that
        // leaves `pad` rows visible below it. With `pad == 0` these are exactly the old two branches.
        val highest = math.max(0, index - pad)
        val lowest  = math.max(0, index + pad - viewportHeight + 1)
        val nudged  =
          if clamped > highest then highest
          else if clamped < lowest then lowest
          else clamped
        // the selection is caller-owned, so clamp rather than trust it: an index outside the content must never become
        // an offset outside the content, because callers index their content with the result directly.
        math.max(0, math.min(nudged, maxOffset))

  /** The offset to render at when the items are not all the same height — [[offsetFor]]'s counterpart for a list whose
    * rows come in blocks, such as a [[ListView]] carrying multi-line items.
    *
    * `heights` is one row count per item, in order, and the answer is still an *item* index, not a row: the caller
    * slices its items with it exactly as it would with [[offsetFor]]. The rule is the same one — clamp, then nudge by
    * the smallest amount that brings the selection into view — expressed in rows rather than in indices:
    *
    *   - the offset is clamped to the last item at which the remaining items still fill the viewport, so the list never
    *     scrolls past its own end and leave blank rows below with content above;
    *   - with a selection above the window, the window moves up to start at the selection;
    *   - with a selection whose last row falls below the window, the offset advances one item at a time until the
    *     selection's last row fits, stopping at the selection itself so an item taller than the whole viewport still
    *     shows its top rather than scrolling off entirely.
    *
    * With every height equal to one this returns exactly what [[offsetFor]] with no padding returns; a list of uniform
    * items cannot tell the two apart. Padding has no counterpart here on purpose: "keep two more items visible" is not
    * a fixed number of rows when items differ in height, so a caller that wants it should reach for [[offsetFor]] on a
    * uniform list rather than get a rule that means something different on every frame.
    */
  def offsetForItems(offset: Int, selected: Option[Int], heights: Seq[Int], viewportHeight: Int): Int =
    val count = heights.size
    if count == 0 || viewportHeight <= 0 then 0
    else
      // heights are read in place: a ListView hands over its remembered per-item heights, and copying them would make
      // every frame over a long multi-row list cost the list rather than the viewport. Every walk below stops once it
      // has gathered a viewport's worth of rows, so the reads are bounded by the viewport, not by `count`.
      // (`toIndexedSeq` returns an immutable indexed sequence itself, unchanged; only a linear `Seq` is copied)
      val indexed               = heights.toIndexedSeq
      def rows(index: Int): Int = math.max(1, indexed(index))
      // the largest offset whose remaining items still fill the viewport: walk back from the end while they fit.
      // The walk starts at `count` — one past the last item — and stops without moving when even the last item alone
      // overflows the viewport, so its result is capped at `count - 1`: an offset equal to the item count would slice
      // every item away and paint a blank area over content the reader can still be shown the top of.
      val maxOffset             =
        var index = count
        var taken = 0
        while index > 0 && taken + rows(index - 1) <= viewportHeight do
          taken += rows(index - 1)
          index -= 1
        math.min(index, count - 1)
      val clamped               = math.max(0, math.min(offset, maxOffset))
      selected match
        case None        => clamped
        case Some(index) =>
          val target = math.max(0, math.min(index, count - 1))
          if clamped > target then target
          else
            // the lowest window start whose rows down to the end of the selected item still fit, found by walking back
            // from the selection — never further than a viewport — and never below the selection itself, so an item
            // taller than the viewport shows its top. The window advances to it only when it sits below `clamped`.
            var start = target
            var span  = rows(target)
            while start > clamped && span + rows(start - 1) <= viewportHeight do
              start -= 1
              span += rows(start)
            start
