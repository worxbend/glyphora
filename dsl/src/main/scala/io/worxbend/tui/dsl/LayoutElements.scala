package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Alignment, Buffer, Cell, Direction, Flex, Line, Measured, Rect, Style, Widget}
import io.worxbend.tui.widgets as w

/** The heights of every child at `width`, or `None` if any one of them cannot say how tall it is.
  *
  * `Measured` treats `None` as "unmeasurable", never as zero, so a container whose children are not all measurable has
  * no measurement of its own to give. Every container in this file answers through this.
  */
private def measuredHeights(children: Seq[Element], width: Int): Option[Seq[Int]] =
  val heights = children.map(_.intrinsicHeight(width))
  Option.when(heights.forall(_.nonEmpty))(heights.flatten)

/** A container that arranges its children along an axis, so `.gap`/`.center`/`.spaceBetween` can be typed to the
  * containers they actually do something on instead of silently being the identity everywhere else.
  *
  * `withFlex`/`withSpacing` are the plain-method form of those extensions; both give back this node's own type.
  */
trait FlexContainer extends Element:

  /** This container rebuilt with a different alignment for the space its children leave over. */
  def withFlex(mode: Flex): Self

  /** This container rebuilt with `cells` blank cells between neighbouring children (negative counts clamp to zero). */
  def withSpacing(cells: Int): Self

/** A bordered box with an optional title, stacking its children vertically inside the border.
  *
  * Two captions are available, and neither costs a content row: `title` is written into the top border and
  * `titleBottom` into the bottom border — the name-above / status-below shape most panes in a real application want.
  * Each has its own alignment (left for the top one, right for the bottom one, unless `.titleAligned` /
  * `.titleBottomAligned` say otherwise), and `.titles(...)` adds any number of further captions built as
  * [[io.worxbend.tui.widgets.BlockTitle]]s, which is how a caption made of differently-styled runs gets in — a
  * `BlockTitle` carries a `Line`, and a `Line` carries spans.
  *
  * `borders` chooses which sides are drawn. The default is all four, but a pane can be a horizontal rule above its
  * content (`Borders.Top`), a column separated from its neighbour by a single line (`Borders.Left`), or unframed
  * entirely (`.borderless`) while keeping the panel's padding and captions. Where two drawn sides meet, the block draws
  * a corner glyph; where they do not, it does not, so a half-framed panel has no dangling corners.
  *
  * [[padding]] reserves blank cells between the border and the children.
  *
  * The children are stacked by the same `w.Column` a [[ColumnElement]] builds, so a panel is a [[FlexContainer]] too:
  * `.gap`/`.center`/`.spaceBetween` work inside the border without wrapping the children in a `column` first.
  */
final case class PanelElement(
    title: Option[String],
    override val children: Seq[Element],
    borderType: w.BorderType = w.BorderType.Plain,
    titleBottom: Option[String] = None,
    titleStyle: Option[Style] = None,
    padding: w.Padding = w.Padding.zero,
    spacing: Int = 0,
    flex: Flex = Flex.Start,
    mergeBorders: w.MergeStrategy = w.MergeStrategy.Replace,
    borders: w.Borders = w.Borders.All,
    titleAlignment: Alignment = Alignment.Left,
    titleBottomAlignment: Alignment = Alignment.Right,
    extraTitles: Seq[w.BlockTitle] = Seq.empty,
    props: ElementProps = ElementProps(),
) extends FlexContainer:
  type Self = PanelElement

  /** The block this panel frames itself with — one value, so `render` and the height measurement below cannot disagree
    * about how many cells the chrome costs.
    */
  private def block: w.Block =
    w.Block(
      blockTitles,
      borders,
      padding,
      borderStyle = props.style,
      borderType = borderType.degraded(props.glyphs),
      mergeBorders = mergeBorders,
    )

  def widget: Widget =
    (area, buffer) =>
      val framed = block
      framed.render(area, buffer)
      w.Column(children.map(_.layoutItem(Direction.Vertical)), spacing, flex).render(framed.inner(area), buffer)

  def withFlex(mode: Flex): PanelElement    = copy(flex = mode)
  def withSpacing(cells: Int): PanelElement = copy(spacing = math.max(0, cells))

  /** The captions handed to `w.Block`.
    *
    * The titles and the border are styled separately — `titleStyle` when the caller set one, the element's own style
    * otherwise — so `panel("Errors")(...).fg(Color.Red)` reddens the frame and `.titleStyle(_.withFg(Color.Red))`
    * reddens only the name. Passing one style for both, as this node used to, made the second of those impossible to
    * express.
    */
  private def blockTitles: Seq[w.BlockTitle] =
    val captionStyle = titleStyle.getOrElse(props.style)
    title.map(text => w.BlockTitle.top(Line.styled(text, captionStyle), titleAlignment)).toSeq ++
      titleBottom.map(text => w.BlockTitle.bottom(Line.styled(text, captionStyle), titleBottomAlignment)).toSeq ++
      extraTitles

  /** A second caption, written into the bottom border at the right — a status, a count, a keybinding hint. */
  def titleBottom(text: String): PanelElement = copy(titleBottom = Some(text))

  /** Moves the top caption along the top border: left (the default), centre, or right. */
  def titleAligned(alignment: Alignment): PanelElement = copy(titleAlignment = alignment)

  /** Moves the bottom caption along the bottom border. It starts at the right, where a status belongs. */
  def titleBottomAligned(alignment: Alignment): PanelElement = copy(titleBottomAlignment = alignment)

  /** Adds further captions, built by hand as `BlockTitle.top(line, alignment)` / `BlockTitle.bottom(...)`.
    *
    * `title` and `titleBottom` take a plain `String` and paint it in one style, which cannot say "build" in the default
    * colour and "failed" in red. A `BlockTitle` carries a `Line`, and a `Line` carries differently-styled spans, so
    * this is the way to a mixed-style caption — or simply to a third and fourth one.
    *
    * Captions sharing a border *and* an alignment are drawn as one run separated by a single space, in the order given,
    * with the `title`/`titleBottom` pair first. Captions never widen the box and never cost a content row: they are
    * written over border cells that are being drawn anyway.
    */
  def titles(more: w.BlockTitle*): PanelElement = copy(extraTitles = extraTitles ++ more)

  /** Draws only the named sides: `.borders(Borders.Top | Borders.Bottom)` for a horizontal band, `Borders.Left` for a
    * plain column separator. Corner glyphs appear only where two drawn sides meet.
    */
  def borders(sides: w.Borders): PanelElement = copy(borders = sides)

  /** Draws no border at all, while keeping the panel's padding, its flex layout and its captions — a grouping box with
    * no frame around it. (Captions live *in* the border cells, so with no border there is nowhere to write them.)
    */
  def borderless: PanelElement = copy(borders = w.Borders.None)

  /** Styles the captions independently of the border, which keeps the element's own style. */
  def titleStyle(transform: Style => Style): PanelElement =
    copy(titleStyle = Some(transform(titleStyle.getOrElse(props.style))))

  /** Blank cells between the border and the children, per side. */
  def padded(cells: w.Padding): PanelElement = copy(padding = cells)

  /** Padding that reads as `cells` even all the way round: `cells` rows above and below, twice that many columns either
    * side, because a terminal cell is about twice as tall as it is wide. See `w.Padding.proportional`.
    */
  def padding(cells: Int): PanelElement = padded(w.Padding.proportional(cells))

  private[dsl] def withProps(props: ElementProps): PanelElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): PanelElement = copy(children = children)
  private[dsl] override def intrinsicHeight(width: Int): Option[Int]           =
    constrainedHeight(framedHeight(width))

  /** The rows this panel's chrome and children need at `width` — what [[intrinsicHeight]] answers once
    * [[constrainedHeight]] has found no explicit constraint of the caller's own to honour instead.
    */
  private def framedHeight(width: Int): Option[Int] =
    // Every drawn border side eats one cell across its own axis, and the padding eats whatever it was asked for on
    // top of that. Counting the sides that are actually drawn, rather than assuming all four, is what keeps a
    // `.borders(Borders.Top)` panel from reserving two rows it never paints. A caption on a side with no border
    // reserves the outermost row too — the same rule `w.Block.inner` applies — so the measurement can never hand a
    // scrollView a height that leaves the panel's last content row unreachable.
    val chrome      = borderCells(w.Borders.Left) + borderCells(w.Borders.Right) + padding.horizontalCells
    val topInset    = rowsAbove(w.Borders.Top, w.TitlePosition.Top)
    val bottomInset = rowsAbove(w.Borders.Bottom, w.TitlePosition.Bottom)
    val rows        = topInset + bottomInset + padding.verticalCells
    val gaps        = spacing * math.max(0, children.size - 1)
    measuredHeights(children, math.max(0, width - chrome)).map(_.sum + gaps + rows)

  /** One cell if that side of the frame is drawn, none if it is not — the column rule `w.Block.inner` applies. */
  private def borderCells(side: w.Borders): Int = if borders.hasAny(side) then 1 else 0

  /** How many rows one horizontal edge of the frame costs the content: one for a border, one for a caption with no
    * border under it, and one — not two — when there is both. The same rule `w.Block.inner` applies, computed here from
    * the same `blockTitles` the render hands the block, so a titled borderless panel measures the row its caption is
    * written on.
    */
  private def rowsAbove(side: w.Borders, position: w.TitlePosition): Int =
    if borders.hasAny(side) || blockTitles.exists(_.position == position) then 1 else 0

/** Border glyph sets, on the one node type where a border exists at all.
  *
  * `w.BorderType` has four members — the square `┌─┐`, the rounded `╭─╮`, the double `╔═╗` and the heavy `┏━┓` — and
  * all four are single-column glyphs, so choosing one changes only the look and never the space left for content.
  */
extension (panel: PanelElement)

  /** Rounded corners instead of square ones. */
  def rounded: PanelElement = panel.copy(borderType = w.BorderType.Rounded)

  /** A double-line border — the conventional "this box is important" cue. */
  def doubleBorder: PanelElement = panel.copy(borderType = w.BorderType.Double)

  /** A heavy single-line border (`┏━┓`): the same emphasis a double border gives, in one stroke rather than two, which
    * reads better next to plain-bordered neighbours.
    */
  def thick: PanelElement = panel.copy(borderType = w.BorderType.Thick)

  /** Any border glyph set by name, for code that picks one from a value rather than writing it out — a theme setting, a
    * user preference, a `match` on some state. The three named builders above are shorthands for this.
    */
  def borderType(glyphs: w.BorderType): PanelElement = panel.copy(borderType = glyphs)

  /** Joins this panel's border to any border already drawn where it lands, instead of overwriting it.
    *
    * Two panels laid side by side share a column. By default the second one drawn simply wins there, which gives one
    * straight wall but leaves the corners unjoined — the seam reads as `┐` above `┘` where a single frame would show
    * `┬` above `┴`. With `MergeStrategy.Exact` the two glyphs are combined into the one that shows both. `Fuzzy` does
    * the same and additionally accepts a lighter joint where Unicode has no exact one, which is what a double-walled
    * panel meeting a thick-walled one needs.
    *
    * Merging only looks at what is already in the buffer, so it depends on draw order: the panel drawn second is the
    * one that has something to join to.
    */
  def mergeBorders(strategy: w.MergeStrategy): PanelElement = panel.copy(mergeBorders = strategy)

/** Children laid out side by side, left to right. */
final case class RowElement(
    override val children: Seq[Element],
    spacing: Int = 0,
    flex: Flex = Flex.Start,
    props: ElementProps = ElementProps(),
) extends FlexContainer:
  type Self = RowElement
  private def layout: w.Row                                                  =
    val items = children.map { child =>
      val item = child.layoutItem(Direction.Horizontal)
      item.copy(widget = RowChildWidget(child, item.widget))
    }
    w.Row(items, spacing, flex)
  def widget: Widget                                                         = layout
  def withFlex(mode: Flex): RowElement                                       = copy(flex = mode)
  def withSpacing(cells: Int): RowElement                                    = copy(spacing = math.max(0, cells))
  private[dsl] def withProps(props: ElementProps): RowElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): RowElement = copy(children = children)
  private[dsl] override def intrinsicHeight(width: Int): Option[Int]         =
    constrainedHeight(layout.heightAt(width))

/** A row child's widget with the DSL's cross-axis measurement attached. The child's explicit constraint belongs to its
  * horizontal slot, not to its height; clear only that node's constraint for the query, preserving vertical constraints
  * inside its subtree. Row owns allocation and the zero-width policy for both painting and measurement.
  */
private final case class RowChildWidget(element: Element, underlying: Widget) extends Widget with Measured:
  def render(area: Rect, buffer: Buffer): Unit = underlying.render(area, buffer)

  override def heightAt(width: Int): Option[Int] =
    element.withProps(element.props.copy(constraint = None)).intrinsicHeight(width)

/** Children stacked top to bottom. */
final case class ColumnElement(
    override val children: Seq[Element],
    spacing: Int = 0,
    flex: Flex = Flex.Start,
    props: ElementProps = ElementProps(),
) extends FlexContainer:
  type Self = ColumnElement
  def widget: Widget                         = w.Column(children.map(_.layoutItem(Direction.Vertical)), spacing, flex)
  def withFlex(mode: Flex): ColumnElement    = copy(flex = mode)
  def withSpacing(cells: Int): ColumnElement = copy(spacing = math.max(0, cells))
  private[dsl] override def intrinsicHeight(width: Int): Option[Int]            =
    constrainedHeight(measuredHeights(children, width).map(_.sum + spacing * math.max(0, children.size - 1)))
  private[dsl] def withProps(props: ElementProps): ColumnElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): ColumnElement = copy(children = children)

/** Blank space: flexible by default, fixed once `.length(n)` sets a constraint. */
final case class SpacerElement(props: ElementProps = ElementProps()) extends Element:
  type Self = SpacerElement
  def widget: Widget                                             = w.Spacer
  private[dsl] def withProps(props: ElementProps): SpacerElement = copy(props = props)

/** Renders `content` at an absolute offset inside whatever area it is given, sized `width` x `height` (clipped to the
  * area). The building block for context menus and tooltips anchored at a mouse or widget position — compose it over a
  * base with [[Element.layers]].
  */
final case class PositionedElement(
    dx: Int,
    dy: Int,
    width: Int,
    height: Int,
    content: Element,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = PositionedElement
  override def children: Seq[Element]                                               = Seq(content)
  def widget: Widget                                                                =
    (area, buffer) =>
      val target = Rect(area.x + dx, area.y + dy, width, height).intersection(area)
      if !target.isEmpty then content.widget.render(target, buffer)
  private[dsl] def withProps(props: ElementProps): PositionedElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): PositionedElement =
    copy(content = children.headOption.getOrElse(content))

/** Like [[PositionedElement]], but the content escapes its container.
  *
  * Both nodes measure `dx`/`dy` from the top-left of the area the node itself occupies. The difference is what happens
  * when the resulting rectangle runs past that area. A [[PositionedElement]] intersects with it, so a dropdown opened
  * from a row inside a bordered `panel` loses everything past the border. This node instead hands the rectangle and the
  * content to the frame root, which draws it *after* the whole tree — over the border, over any sibling pane, clipped
  * only by the terminal.
  *
  * Portals paint in the order they were rendered, all of them above the tree, and a portal inside portal content is
  * drawn above the portal that contains it.
  *
  * The content is still a child for event routing, focus order and measurement, so a menu inside a portal is clicked
  * and tabbed to exactly where it is drawn.
  *
  * There is one case with no frame root to hand the content to: rendering a bare `Element` outside a [[TuiApp]], as a
  * construction test that calls `widget.render` directly does. Rather than vanish, the content then falls back to
  * [[PositionedElement]]'s clipped in-place behaviour.
  */
final case class PortalElement(
    dx: Int,
    dy: Int,
    width: Int,
    height: Int,
    content: Element,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = PortalElement
  override def children: Seq[Element]                                           = Seq(content)
  def widget: Widget                                                            =
    (area, buffer) =>
      val target = Rect(area.x + dx, area.y + dy, width, height)
      if PortalQueue.isCollecting then PortalQueue.offer(target, content)
      // no frame root to hand the content to: render it exactly as `positioned` would
      else PositionedElement(dx, dy, width, height, content).widget.render(area, buffer)
  private[dsl] def withProps(props: ElementProps): PortalElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): PortalElement =
    copy(content = children.headOption.getOrElse(content))

/** Fills its whole area with `fill` (a solid background) before rendering `inner` — what the chrome bars use to read as
  * continuous surfaces. Transparent to focus, measurement and event routing.
  */
final case class FilledElement(
    inner: Element,
    fill: Style,
    override val props: ElementProps = ElementProps(),
) extends DecoratingElement:
  type Self = FilledElement
  def widget: Widget                                                            =
    (area, buffer) =>
      var y = area.y
      while y < area.bottom do
        var x = area.x
        while x < area.right do
          buffer.set(x, y, Cell(" ", fill))
          x += 1
        y += 1
      inner.widget.render(area, buffer)
  private[dsl] def withProps(props: ElementProps): FilledElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): FilledElement =
    copy(inner = children.headOption.getOrElse(inner))

/** Z-ordered stacking: every child renders over the full area in order, so later children paint over earlier ones — the
  * primitive under dialogs, toasts, palettes, and splash overlays.
  */
final case class LayersElement(
    override val children: Seq[Element],
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = LayersElement
  def widget: Widget                                                            =
    (area, buffer) => children.foreach(_.widget.render(area, buffer))
  private[dsl] def withProps(props: ElementProps): LayersElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): LayersElement = copy(children = children)
