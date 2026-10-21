package io.worxbend.tui.dsl

import io.worxbend.tui.core.{
  Constraint,
  Direction,
  Flex,
  KeyCode,
  KeyEvent,
  Layout,
  Line,
  MouseEventKind,
  Position,
  Rect,
  Size,
  StatefulWidget,
  Style,
  Versioned,
  Widget,
}
import io.worxbend.tui.terminal.PointerShape
import io.worxbend.tui.widgets as w

/** A subtree chosen by the terminal's size — a media query — or, built by `responsive.local`, by its own area.
  *
  * `build` is handed the size of the whole terminal, not this node's allotted area, and runs during [[ResponsivePass]]
  * before the focus pass, so whatever it returns is an ordinary part of the tree: its focusables take Tab stops, its
  * handlers receive keys, and clicks hit-test into it — unless the node sits on a layer a modal covers, in which case
  * [[ResponsivePass]] suppresses the branch along with the rest of that layer. That is what makes swapping *components*
  * work and not just constraints — a `row` of three panes at 120 columns can become a `tabbedContent` at 60.
  *
  * The node is otherwise transparent: it holds the built branch as its single child, so a constraint, a style, or an
  * `onKeyEvent` set on it applies exactly as it would on a `column` wrapping the same content, and the branch's own
  * layout claim becomes the node's when none is set explicitly.
  *
  * `resolved` is filled in by [[ResponsivePass]] on every render, never by user code — the same contract [[FocusState]]
  * has. While it is empty the node still renders: it falls back to building against its own area, so a construction
  * test that draws `element.widget` straight into a buffer without a [[TuiApp]] behind it shows content rather than
  * blank space. That fallback has no focus pass behind it, so focusables inside it are inert — which is why the pass
  * exists rather than doing this at render time.
  *
  * '''Container queries.''' With `local` set — `responsive.local` — `build` is handed this node's *own* area instead,
  * found by the layout pre-pass in [[ResponsivePass.resolveFrame]]: the tree is painted once into a scratch buffer with
  * this node unresolved, the node records the area it was handed, and the pass builds the branch for that size — still
  * before the focus pass, so the branch is as much a part of the tree as a media query's. Because the area has to be
  * known before the branch exists, a local node's size never depends on its branch: it claims what its own constraint
  * says, or a fill, and measures as unmeasurable unless given a `.length(n)` — the size containment a CSS container
  * query asks for too. `probe` is the pre-pass's handle on an unresolved local node; like `resolved`, only the pass
  * writes it.
  */
final case class ResponsiveElement(
    build: Size => Element,
    resolved: Option[Element] = None,
    props: ElementProps = ElementProps(),
    local: Boolean = false,
    probe: Int = -1,
) extends Element:
  type Self = ResponsiveElement
  override def debugName: String                                                    = "responsive"
  private[dsl] override def layoutOnly: Boolean                                     = true
  override def children: Seq[Element]                                               = resolved.toSeq
  def widget: Widget                                                                =
    resolved match
      case Some(branch) => branch.widget
      case None         =>
        (area, buffer) =>
          // the pre-pass paints this node unresolved only to learn its area: it records the area and draws nothing
          if LocalProbe.measuring then LocalProbe.record(probe, area.size)
          else build(area.size).widget.render(area, buffer)
  private[dsl] def withProps(props: ElementProps): ResponsiveElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): ResponsiveElement =
    copy(resolved = children.headOption.orElse(resolved))

  /** This node holding what `build` produces at `size` — [[ResponsivePass]]'s single mutation. */
  private[dsl] def resolvedAt(branch: Element): ResponsiveElement = copy(resolved = Some(branch))

  /** This unresolved local node carrying the pre-pass handle `token` — see the class notes. */
  private[dsl] def probedAs(token: Int): ResponsiveElement = copy(probe = token)

  /** A local node claims a fill whatever its branch is — see the class notes; a media query claims its branch's. */
  private[dsl] override def claim: SizeClaim =
    if local then SizeClaim.Fill else resolved.map(_.claim).getOrElse(SizeClaim.Fill)

  /** With no constraint of its own, this node measures as the branch [[ResponsivePass]] built — and as unmeasurable
    * until it has one, or always when it is local. The trait default cannot serve here: this node's `widget` is a
    * lambda that either forwards to the branch or builds one from the render area, so it is never a
    * [[io.worxbend.tui.core.Measured]] itself.
    */
  private[dsl] override def intrinsicHeight(width: Int): Option[Int] =
    constrainedHeight(if local then None else resolved.flatMap(_.intrinsicHeight(width)))

  /** @see [[intrinsicHeight]] */
  private[dsl] override def intrinsicWidth(height: Int): Option[Int] =
    constrainedWidth(if local then None else resolved.flatMap(_.intrinsicWidth(height)))

object ResponsiveElement:
  /** Capture the declaration's hook scope once. Case-class copies retain the already captured builder. */
  def apply(
      build: Size => Element,
      resolved: Option[Element] = None,
      props: ElementProps = ElementProps(),
      local: Boolean = false,
      probe: Int = -1,
  ): ResponsiveElement = new ResponsiveElement(ViewState.deferred(build), resolved, props, local, probe)

/** A scrollable viewport over taller-than-the-screen content. Up/Down/PageUp/PageDown scroll while focused.
  *
  * `contentHeight` is the caller's own measurement; `None` means "measure the content each frame", which is what the
  * two-argument `scrollView(content, state)` factory asks for.
  *
  * A searched view (`.searchable(state)`) finds its matches by painting the whole content once per query and content,
  * then reuses them: a frame that only scrolls, steps or redraws paints just the window.
  *
  * The view keeps what matters on screen by itself. When focus moves onto a child outside the window, or a child marked
  * `.ensureVisible` appears, the view scrolls just far enough to show it, and records the request in `state`, so a
  * child that *stays* focused or marked does not drag the view back after the reader scrolls away. A mark wins over
  * focus; `.followFocus(false)` turns focus-following off. Every scroll view around the child scrolls its part of the
  * way, nested ones included. The offset is resolved *before* the content paints, from where the last frame painted the
  * target, so the content is painted once and every recorded rect is the one on screen; a target that was not on the
  * last frame (it just appeared) is revealed on the frame after it first paints.
  */
final case class ScrollViewElement(
    content: Element,
    contentHeight: Option[Int],
    state: w.ScrollViewState,
    followsFocus: Boolean = true,
    props: ElementProps = ElementProps(focusable = true),
    search: Option[w.SearchState] = None,
) extends Element:
  type Self = ScrollViewElement

  /** The caller-owned states this node paints, watched for writes made between frames. */
  override private[dsl] def watchedStates: Seq[Versioned] = Seq(state) ++ search
  override def debugName: String                          = "scrollView"
  private[dsl] override def selectionIsFocusCue: Boolean  = true
  override def children: Seq[Element]                     = Seq(content)
  def widget: Widget                                      =
    (area, buffer) =>
      // `area.height` is the last resort, and it is a lie the caller cannot see: content that reports no height
      // measures as exactly one viewport tall, the offset clamps to zero, and everything below the fold becomes
      // unreachable rather than merely mis-scrolled. There is no better answer at render time — the content has
      // already been asked — so the fix belongs upstream: give the content a `.length(n)`, pass `contentHeight`, or
      // make its widget answer `Measured.heightAt`.
      val rows      =
        contentHeight
          .filter(_ > 0)
          .getOrElse(content.intrinsicHeight(math.max(1, area.width - 1)).getOrElse(area.height))
      // the content renders into an offscreen buffer, so it cannot see where this scroll view sits on screen; the
      // focus pass's viewport wrapper needs that to translate the areas recorded underneath it
      content match
        case viewport: ScrollViewportElement => viewport.publishScreenArea(area)
        case _                               => ()
      val found     = SearchStyles.of(props.focusState.theme)
      val view      = searched(
        w.ScrollView(
          content.widget,
          rows,
          contentIdentity = searchIdentity,
          matchStyle = found.matched,
          currentMatchStyle = found.current,
        )
      )
      // the container-query pre-pass paints an undecorated tree, which asks for nothing: that is not a request going
      // away, so the pre-pass leaves the bookkeeping of the frames that do ask alone
      val measuring = LocalProbe.measuring
      val atEnd     = state.isAtEnd
      val revealed  = if measuring then None else revealBeforePaint()
      // a view that was following a tail and is still at its end goes to the new end before it paints, so a row that
      // arrived since the last frame shows on this one; the paint clamps the offset to the content's new bottom
      if !measuring && atEnd && state.revealedRequest.exists(_.startsWith(RevealProbe.TailPrefix)) then
        state.offset = Int.MaxValue / 2
      val requests  = RevealRequests(followsFocus, area, state, atEnd)
      RevealProbe.collecting(requests)(view.render(area, buffer, state))
      if !measuring then settleAfterPaint(requests, revealed)

  /** Scrolls to the request the focus pass found for this frame, when it is new and the last paint recorded where its
    * target lands — the common case: focus moved onto a child that was already there. Answers the request acted on.
    */
  private def revealBeforePaint(): Option[String] =
    state.owesFrame = false
    state.pendingReveal.filterNot(state.revealedRequest.contains).flatMap { id =>
      state.placements(id).map { rect =>
        state.revealedRequest = Some(id)
        state.reveal(rect.y, rect.height)
        id
      }
    }

  /** Records where every possible request landed this frame, for the next one, and acts on a request the paint made
    * that could not be resolved beforehand — a child that was not there on the last frame, a search match, a target an
    * inner view moved this frame. Acting means moving the offset for the *next* frame (the state owes that frame): this
    * one is never painted a second time, so every rect recorded during it is the one on screen.
    */
  private def settleAfterPaint(requests: RevealRequests, revealed: Option[String]): Unit =
    state.placements = requests.placements.get
    requests.chosen match
      case None             => state.revealedRequest = None
      case Some((id, rect)) =>
        // act on a request once, when it appears: acting on it every frame would pin the view and undo every
        // scroll the reader makes while the same element stays focused or marked
        val fresh = !state.revealedRequest.contains(id)
        val stale = revealed.contains(id) && !shows(rect)
        if fresh || stale then
          state.revealedRequest = Some(id)
          val before = state.offset
          state.reveal(rect.y, rect.height)
          if state.offset != before then state.owesFrame = true

  /** Whether content rows `rect` are inside the window the view just painted. */
  private def shows(rect: Rect): Boolean =
    rect.y >= state.offset && rect.bottom <= state.offset + state.viewportRows

  /** What the searched view compares to decide whether the matches it found last frame still hold: the content subtree
    * itself (plain data, compared structurally — a node holding a fresh closure never compares equal, which only costs
    * a re-search) and the versions of the caller-owned states it paints, since a state's text can change under an equal
    * node. Built only while a query is set; an unsearched view pays nothing for it. This is a walk of this view's own
    * subtree, made only on a searched frame, and in exchange the view paints its window instead of its whole content.
    */
  private def searchIdentity: Option[Any] =
    search.filter(_.isActive).map { _ =>
      // the focus pass wraps the content in a fresh viewport translator every frame; what it translates is the identity
      val subject = content match
        case viewport: ScrollViewportElement => viewport.inner
        case other                           => other
      (subject, StateWatch.collect(subject).map(_._2))
    }

  /** The view as drawn: plain, or searched for `search`'s query. */
  private def searched(view: w.ScrollView): StatefulWidget[w.ScrollViewState] =
    search.fold[StatefulWidget[w.ScrollViewState]](view)(found =>
      (area, buffer, state) => view.render(area, buffer, state, found)
    )

  /** Searches the whole content for `state`'s query — below and above the fold, not just the window — highlighting
    * every match and scrolling to the current one when the query changes; `n`/`N` step to the next/previous match while
    * the view is focused. Pair it with a [[searchBar]] over the same state to type the query. See
    * [[io.worxbend.tui.widgets.SearchState w.SearchState]].
    */
  def searchable(state: w.SearchState): ScrollViewElement = copy(search = Some(state))

  /** Whether this view scrolls to keep its focused child on screen — on by default. Moving focus with Tab onto a child
    * below or above the window scrolls just far enough to show it; turn it off for a view whose offset the app drives
    * itself.
    */
  def followFocus(enabled: Boolean): ScrollViewElement                              = copy(followsFocus = enabled)
  private[dsl] def withProps(props: ElementProps): ScrollViewElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): ScrollViewElement =
    copy(content = children.headOption.getOrElse(content))
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]            =
    Some(
      searchSteps(search)
        .orElse(scrollKeys(rows => state.scrollUp(rows), rows => state.scrollDown(rows)))
        .orElse(scrollJumpKeys(() => state.first(), () => state.last()))
    )
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler]        =
    Some(wheelScrolls(() => state.scrollUp(), () => state.scrollDown()))

/** `n` / `N` step a searched view to its next / previous match — consumed only while a query is set, so a view with no
  * search (or an empty one) lets the letters bubble to app bindings.
  */
private def searchSteps(search: Option[w.SearchState]): BuiltinKeyHandler =
  event =>
    search.exists { found =>
      found.isActive && isPlainTyping(event) && (event.code match
        case KeyCode.Char('n') =>
          val _ = found.next()
          true
        case KeyCode.Char('N') =>
          val _ = found.previous()
          true
        case _                 => false)
    }

/** A tab row plus the selected page (Textual's `TabbedContent`): Left/Right switch pages while focused. Only the active
  * page's focusables participate in the tab order.
  */
final case class TabbedContentElement(
    titles: Seq[String],
    activePage: Element,
    selected: Int,
    onSelect: Int => Unit,
    pageCount: Int,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = TabbedContentElement
  override def debugName: String                                                       = "tabbedContent"
  override def children: Seq[Element]                                                  = Seq(activePage)
  def widget: Widget                                                                   =
    val tabs = w.Tabs(titles.map(Line.raw), selected, focusStyled(props))
    (area, buffer) =>
      w.Column(
        Seq(
          w.LayoutItem(Constraint.Length(1), tabs),
          w.LayoutItem(Constraint.Fill(1), activePage.widget),
        )
      ).render(area, buffer)
  private[dsl] def withProps(props: ElementProps): TabbedContentElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): TabbedContentElement =
    copy(activePage = children.headOption.getOrElse(activePage))
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]               =
    Some(stepsWrapping(pageCount, selected, onSelect))

/** A toggleable section: `▸ title` collapsed, `▾ title` plus the body expanded; Enter/Space toggle while focused.
  * Collapsed bodies leave the tab order entirely.
  */
final case class CollapsibleElement(
    title: String,
    body: Element,
    expanded: Boolean,
    onToggle: Boolean => Unit,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = CollapsibleElement
  override def debugName: String                                      = "collapsible"
  override def children: Seq[Element]                                 = if expanded then Seq(body) else Seq.empty
  def widget: Widget                                                  =
    val marker         = if expanded then "▾ " else "▸ "
    val header: Widget = (area, buffer) => buffer.setString(area.x, area.y, marker + title, focusStyled(props))
    (area, buffer) =>
      if expanded then
        w.Column(
          Seq(w.LayoutItem(Constraint.Length(1), header), w.LayoutItem(Constraint.Fill(1), body.widget))
        ).render(area, buffer)
      else header.render(area, buffer)
  private[dsl] def withProps(props: ElementProps): CollapsibleElement = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): CollapsibleElement =
    copy(body = children.headOption.getOrElse(body))
  private[dsl] override def claim: SizeClaim                                         =
    if expanded then SizeClaim.Fill else SizeClaim.OneRow
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]             =
    Some(toggleOnActivate(() => onToggle(!expanded)))

/** Two panes split by an adjustable divider: `[`/`]` shift the split while the pane itself is focused. */
final case class SplitPaneElement(
    first: Element,
    second: Element,
    splitPercent: Int,
    onSplit: Int => Unit,
    axis: Direction = Direction.Horizontal,
    props: ElementProps = ElementProps(focusable = true),
) extends Element:
  type Self = SplitPaneElement
  override def debugName: String                                             = "splitPane"
  private[dsl] override def selectionIsFocusCue: Boolean                     = true
  private[dsl] override def builtinMouseHandler: Option[BuiltinMouseHandler] =
    Some { (event, area) =>
      if event.kind == MouseEventKind.Drag then
        val fraction = axis match
          case Direction.Horizontal => (event.position.x - area.x).toDouble / math.max(1, area.width)
          case Direction.Vertical   => (event.position.y - area.y).toDouble / math.max(1, area.height)
        onSplit(clampSplit(math.round(fraction * 100).toInt))
        true
      else false
    }
  override def children: Seq[Element]                                        = Seq(first, second)

  /** The resize arrow over the divider, and nothing elsewhere — the panes' own content keeps the pointer it had.
    *
    * The divider is computed with the same `Layout` the widget splits with, so the two cannot disagree about where it
    * is. A horizontal split has a one-column gap between the panes and the arrow shows over it; a vertical split has no
    * gap, so the arrow shows over the first row of the second pane, which is where the boundary is dragged from.
    */
  private[dsl] override def pointerShapeAt(position: Position, area: Rect): Option[PointerShape] =
    props.pointerShape.orElse {
      val Seq(top, bottom) =
        Layout(axis, Seq(Constraint.Percentage(clampSplit(splitPercent)), Constraint.Fill(1)), spacing, Flex.Start)
          .split(area)
      axis match
        case Direction.Horizontal =>
          Option.when(area.contains(position) && !top.contains(position) && !bottom.contains(position))(
            PointerShape.ResizeHorizontal
          )
        case Direction.Vertical   =>
          Option.when(bottom.contains(position) && position.y == bottom.y)(PointerShape.ResizeVertical)
    }

  /** One column between the panes of a horizontal split, none for a vertical one. */
  private def spacing: Int =
    axis match
      case Direction.Horizontal => 1
      case Direction.Vertical   => 0

  def widget: Widget                                                               =
    val percent = clampSplit(splitPercent)
    val items   = Seq(
      first.layoutItem(axis).copy(constraint = Constraint.Percentage(percent)),
      second.layoutItem(axis).copy(constraint = Constraint.Fill(1)),
    )
    axis match
      case Direction.Horizontal => w.Row(items, spacing = spacing)
      case Direction.Vertical   => w.Column(items, spacing = spacing)
  private[dsl] def withProps(props: ElementProps): SplitPaneElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): SplitPaneElement =
    children match
      case Seq(a, b) => copy(first = a, second = b)
      case _         => this
  private[dsl] override def builtinKeyHandler: Option[BuiltinKeyHandler]           =
    Some(
      keys {
        case KeyEvent(KeyCode.Char('['), _) => onSplit(clampSplit(splitPercent - SplitStep))
        case KeyEvent(KeyCode.Char(']'), _) => onSplit(clampSplit(splitPercent + SplitStep))
      }
    )

/** A scrollbar strip on its own, for content this package does not scroll for you.
  *
  * [[ScrollViewElement]] draws its own scrollbar, so the common case needs nothing here. This node is for the case
  * where the thing being scrolled is not a `scrollView` — a list whose offset the application keeps, a log tailing in a
  * pane, a table with more rows than fit — and the view wants to show how far through it the reader is. Before it, the
  * only way to put a scrollbar on such a pane from the DSL was `widget(Scrollbar(...))`, which meant importing
  * `io.worxbend.tui.widgets` into a view for one type.
  *
  * The widget underneath is stateless: where the thumb sits is a pure function of `contentLength`, `position` and the
  * area, so this node holds two plain numbers plus the [[io.worxbend.tui.widgets.ScrollbarOptions]] describing how the
  * bar looks, and nothing that has to be kept in step with anything. An out-of-range `position` pins the thumb to an
  * end rather than drawing it off the track, and when the content already fits the area only the track is drawn.
  *
  * A vertical scrollbar paints on the *right* edge of the area it is given and a horizontal one on the *bottom* edge,
  * which is why it is usually given a one-column or one-row slice next to the content rather than laid over it.
  */
final case class ScrollbarElement(
    contentLength: Int,
    position: Int,
    options: w.ScrollbarOptions,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = ScrollbarElement
  override def debugName: String = "scrollbar"

  def widget: Widget =
    w.Scrollbar(
      contentLength,
      position,
      options.copy(
        style = options.style.patch(props.style),
        thumbStyle = options.thumbStyle.patch(props.style),
      ),
    )

  /** Runs the bar down the right edge of its area — the default, and what a scrolling pane of text wants. */
  def vertical: ScrollbarElement = copy(options = options.copy(orientation = Direction.Vertical))

  /** Runs the bar along the bottom edge of its area, for content that is too wide rather than too tall. */
  def horizontal: ScrollbarElement = copy(options = options.copy(orientation = Direction.Horizontal))

  /** Moves the thumb to `offset`, in the same units as `contentLength`: rows for a vertical bar, columns for a
    * horizontal one. Values outside the content are clamped rather than rejected.
    *
    * Named `thumbAt` rather than `at`: a member `at` would shadow the `.at(row, col)` grid placement every element has.
    */
  def thumbAt(offset: Int): ScrollbarElement = copy(position = offset)

  /** Styles the thumb — the moving part — independently of the track it runs in. */
  def thumbStyle(transform: Style => Style): ScrollbarElement =
    copy(options = options.copy(thumbStyle = transform(options.thumbStyle)))

  /** Replaces the two glyphs the bar is drawn from: the track cell first, then the thumb cell. Both must be a single
    * terminal column wide, or the bar will not line up with the content beside it.
    */
  def symbols(track: String, thumb: String): ScrollbarElement =
    copy(options = options.copy(trackSymbol = track, thumbSymbol = thumb))

  private[dsl] def withProps(props: ElementProps): ScrollbarElement = copy(props = props)

  /** One cell across the short axis, everything available along the long one — a vertical bar is one column wide and as
    * tall as it is offered, and a horizontal bar the other way round.
    */
  private[dsl] override def claim: SizeClaim =
    options.orientation match
      case Direction.Vertical   => SizeClaim(Constraint.Fill(1), Constraint.Length(1))
      case Direction.Horizontal => SizeClaim(Constraint.Length(1), Constraint.Fill(1))

/** One tab stop for a set of controls — a toolbar, a row of radio-like buttons, a menu bar — with the arrow keys moving
  * between them (a *roving* tab stop). `Tab` enters the group on the member it was last left on (the first one, the
  * first time), `Right`/`Down` and `Left`/`Up` cycle through the members, wrapping, and the next `Tab` leaves to the
  * next stop after the group. A member that wants an arrow for itself — a slider, a list — still gets it first: the
  * arrows only move focus when the focused member declined them.
  *
  * Laid out as its `content`, which `focusGroup(children*)` makes a `row`; pass a single `column(...)` for a vertical
  * group. Membership is every focusable in the subtree, at any depth, except those inside a nested group, which is a
  * stop of its own. A disabled member is skipped like any disabled control.
  */
final case class FocusGroupElement(content: Element, props: ElementProps = ElementProps()) extends Element:
  type Self = FocusGroupElement
  override def debugName: String                                                    = "focusGroup"
  override def children: Seq[Element]                                               = Seq(content)
  def widget: Widget                                                                = content.widget
  private[dsl] override def layoutOnly: Boolean                                     = true
  private[dsl] override def claim: SizeClaim                                        = content.claim
  private[dsl] override def intrinsicHeight(width: Int): Option[Int]                =
    constrainedHeight(content.intrinsicHeight(width))
  private[dsl] def withProps(props: ElementProps): FocusGroupElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): FocusGroupElement =
    children.headOption.fold(this)(child => copy(content = child))
