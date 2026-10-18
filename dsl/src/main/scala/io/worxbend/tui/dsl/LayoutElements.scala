package io.worxbend.tui.dsl

import io.worxbend.tui.core.{
  Alignment,
  Buffer,
  Cell,
  Constraint,
  CrossAlign,
  Direction,
  Flex,
  GridAreas,
  GridItem,
  GridLayout,
  GridPlacement,
  Line,
  Measured,
  Rect,
  Style,
  Widget,
}
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
    content: Seq[Element],
    borderType: w.BorderType = w.BorderType.Plain,
    titleBottom: Option[String] = None,
    titleStyle: Option[Style] = None,
    frameStyle: Style = Style.Default,
    padding: w.Padding = w.Padding.zero,
    spacing: Int = 0,
    flex: Flex = Flex.Start,
    mergeBorders: w.MergeStrategy = w.MergeStrategy.Replace,
    borders: w.Borders = w.Borders.All,
    titleAlignment: Alignment = Alignment.Left,
    titleBottomAlignment: Alignment = Alignment.Right,
    extraTitles: Seq[w.BlockTitle] = Seq.empty,
    props: ElementProps = ElementProps(),
    header: Option[Element] = None,
    footer: Option[Element] = None,
    headerAlignment: Alignment = Alignment.Right,
    footerAlignment: Alignment = Alignment.Right,
) extends FlexContainer:
  type Self = PanelElement
  override def debugName: String = "panel"

  /** The stacked content first, then the header and footer slots: the slots are ordinary children for focus order,
    * hit-testing and theming, they are only painted somewhere else — into the border rows.
    */
  override def children: Seq[Element] = content ++ header ++ footer

  /** The block this panel frames itself with — one value, so `render` and the height measurement below cannot disagree
    * about how many cells the chrome costs.
    */
  private def block: w.Block =
    w.Block(
      blockTitles,
      borders,
      padding,
      borderStyle = frameStyle,
      borderType = borderType.degraded(props.glyphs),
      mergeBorders = mergeBorders,
    )

  def widget: Widget =
    (area, buffer) =>
      val framed  = block
      framed.render(area, buffer)
      val inner   = framed.inner(area)
      w.Column(columnItems(this.content), spacing, flex).render(inner, buffer)
      // the content style goes *under* whatever the children drew, as `withStyle` does: a child's own colour wins,
      // and a cell nobody styled — a gap between children, the background — takes the panel's
      val content = props.style
      if content != Style.Default then buffer.mapStyle(inner)(content.patch)
      header.foreach(slot => PanelSlots.paintSlot(slot, area, area.y, headerAlignment, borders, buffer))
      if area.height > 1 then
        footer.foreach(slot => PanelSlots.paintSlot(slot, area, area.bottom - 1, footerAlignment, borders, buffer))

  def withFlex(mode: Flex): PanelElement    = copy(flex = mode)
  def withSpacing(cells: Int): PanelElement = copy(spacing = math.max(0, cells))

  /** The captions handed to `w.Block`.
    *
    * The titles and the border are styled separately — `titleStyle` when the caller set one, the frame style otherwise
    * — so `panel("Errors")(...).frameStyle(_.withFg(Color.Red))` reddens the frame and `.titleStyle(_.withFg(...))`
    * only the name. The element's own `style` is neither: it is the *content* style, as on every other node.
    */
  private def blockTitles: Seq[w.BlockTitle] =
    val captionStyle = titleStyle.getOrElse(frameStyle)
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

  /** Styles the captions independently of the border. */
  def titleStyle(transform: Style => Style): PanelElement =
    copy(titleStyle = Some(transform(titleStyle.getOrElse(frameStyle))))

  /** The style the border is drawn in, replacing the theme's `border` — `.frameStyle(Style.fg(Color.Red))`. The
    * element's own `style` (`.fg(...)`, `.bold`, `.styled(...)`) is the *content* style, layered under the children, as
    * on every other node; before 0.20.0 it was the border, which this replaces.
    */
  def frameStyle(style: Style): PanelElement = copy(frameStyle = style)

  /** The border style rebuilt from the current one — `.frameStyle(_.bold)` keeps the theme's colour and adds bold. */
  def frameStyle(transform: Style => Style): PanelElement = copy(frameStyle = transform(frameStyle))

  /** Blank cells between the border and the children, per side. */
  def padded(cells: w.Padding): PanelElement = copy(padding = cells)

  /** Padding that reads as `cells` even all the way round: `cells` rows above and below, twice that many columns either
    * side, because a terminal cell is about twice as tall as it is wide. See `w.Padding.proportional`.
    */
  def padding(cells: Int): PanelElement = padded(w.Padding.proportional(cells))

  /** Blank cells between the border and the children, per side — the same as [[padded]], under the name the
    * `.padding(...)` decorator on every other element uses. On a panel the padding goes *inside* the border.
    */
  def padding(cells: w.Padding): PanelElement = padded(cells)

  /** `vertical` blank rows and `horizontal` blank columns inside the border, the CSS two-value shorthand. */
  def padding(vertical: Int, horizontal: Int): PanelElement = padded(w.Padding.symmetric(horizontal, vertical))

  private[dsl] def withProps(props: ElementProps): PanelElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): PanelElement =
    val (body, slots) = children.splitAt(content.size)
    val (top, bottom) = slots.splitAt(header.size)
    copy(content = body, header = header.flatMap(_ => top.headOption), footer = footer.flatMap(_ => bottom.headOption))

  /** An element written into the top border row, right-aligned unless `alignment` says otherwise — a count, a badge, a
    * spinner beside the title. It costs no content row and never widens the box; it is cut to the border's width.
    */
  def header(slot: Element, alignment: Alignment = Alignment.Right): PanelElement =
    copy(header = Some(slot), headerAlignment = alignment)

  /** An element written into the bottom border row, right-aligned unless `alignment` says otherwise — the elements
    * counterpart of `titleBottom`: a `badge("3")`, a `spinner`, a keybinding hint in its own colours.
    */
  def footer(slot: Element, alignment: Alignment = Alignment.Right): PanelElement =
    copy(footer = Some(slot), footerAlignment = alignment)
  private[dsl] override def intrinsicHeight(width: Int): Option[Int]              =
    constrainedHeight(framedHeight(width))

  /** The columns the widest child needs plus the frame's left and right cells and padding. Captions are not counted:
    * they are written over the border and clipped to it, so they never widen the box.
    */
  private[dsl] override def intrinsicWidth(height: Int): Option[Int] =
    val chrome = borderCells(w.Borders.Left) + borderCells(w.Borders.Right) + padding.horizontalCells
    constrainedWidth(
      Option
        .when(content.nonEmpty)(
          content.map(child => child.withProps(child.props.copy(constraint = None)).intrinsicWidth(height))
        )
        .fold(Option(0))(widths => Option.when(widths.forall(_.nonEmpty))(widths.flatten.max))
        .map(_ + chrome)
    )

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
    val gaps        = spacing * math.max(0, content.size - 1)
    measuredHeights(content, math.max(0, width - chrome)).map(_.sum + gaps + rows)

  /** One cell if that side of the frame is drawn, none if it is not — the column rule `w.Block.inner` applies. */
  private def borderCells(side: w.Borders): Int = if borders.hasAny(side) then 1 else 0

  /** How many rows one horizontal edge of the frame costs the content: one for a border, one for a caption with no
    * border under it, and one — not two — when there is both. The same rule `w.Block.inner` applies, computed here from
    * the same `blockTitles` the render hands the block, so a titled borderless panel measures the row its caption is
    * written on.
    */
  private def rowsAbove(side: w.Borders, position: w.TitlePosition): Int =
    if borders.hasAny(side) || blockTitles.exists(_.position == position) then 1 else 0

private[dsl] object PanelSlots:

  /** Paints a header or footer `slot` on row `y` of a panel's `area`: as wide as it measures (in display columns, so a
    * CJK label counts two per character), cut to the run between the side borders when it is wider, and placed along
    * that run by `alignment` — the rule the captions on the same row follow. A slot that cannot say how wide it is
    * takes the whole run.
    */
  def paintSlot(slot: Element, area: Rect, y: Int, alignment: Alignment, borders: w.Borders, buffer: Buffer): Unit =
    val insetLeft = if borders.hasAny(w.Borders.Left) then 1 else 0
    val available = area.width - insetLeft - (if borders.hasAny(w.Borders.Right) && area.width > 1 then 1 else 0)
    if available > 0 then
      val wanted = slot.withProps(slot.props.copy(constraint = None)).intrinsicWidth(1).getOrElse(available)
      val width  = math.max(0, math.min(wanted, available))
      if width > 0 then
        slot.widget.render(Rect(alignment.originAt(area.x + insetLeft, available, width), y, width, 1), buffer)

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

/** Children laid out side by side, left to right.
  *
  * A child marked `.auto` is exactly as wide as it measures ([[Element.intrinsicWidth]]); `.alignItems(...)` places the
  * children vertically inside the row and a child's own `.alignSelf(...)` overrides that — see
  * [[io.worxbend.tui.core.CrossAlign]].
  */
final case class RowElement(
    override val children: Seq[Element],
    spacing: Int = 0,
    flex: Flex = Flex.Start,
    props: ElementProps = ElementProps(),
    crossAlign: CrossAlign = CrossAlign.Stretch,
) extends FlexContainer:
  type Self = RowElement
  override def debugName: String                                             = "row"
  private[dsl] override def layoutOnly: Boolean                              = true
  private def layout: w.Row                                                  =
    val items = children.map { child =>
      val item = child.layoutItem(Direction.Horizontal)
      item.copy(widget = RowChildWidget(child, item.widget))
    }
    w.Row(items, spacing, flex, crossAlign)
  def widget: Widget                                                         = layout
  def withFlex(mode: Flex): RowElement                                       = copy(flex = mode)
  def withSpacing(cells: Int): RowElement                                    = copy(spacing = math.max(0, cells))
  private[dsl] def withProps(props: ElementProps): RowElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): RowElement = copy(children = children)
  private[dsl] override def intrinsicHeight(width: Int): Option[Int]         =
    constrainedHeight(layout.heightAt(width))
  private[dsl] override def intrinsicWidth(height: Int): Option[Int]         =
    constrainedWidth(layout.widthAt(height))

/** A row child's widget with the DSL's measurement attached. The child's explicit constraint belongs to its horizontal
  * slot, not to its height, so the height query clears only that node's constraint, preserving vertical constraints
  * inside its subtree; the width query keeps it, because along the row it *is* the child's width. Row owns allocation
  * and the zero-width policy for both painting and measurement.
  */
private final case class RowChildWidget(element: Element, underlying: Widget) extends Widget with Measured:
  def render(area: Rect, buffer: Buffer): Unit = underlying.render(area, buffer)

  override def heightAt(width: Int): Option[Int] =
    element.withProps(element.props.copy(constraint = None)).intrinsicHeight(width)

  override def widthAt(height: Int): Option[Int] = element.intrinsicWidth(height)

/** A column child's widget with the DSL's measurement attached — [[RowChildWidget]] one axis over: the height query
  * keeps the child's constraint, which along the column is its height, and the width query clears it.
  */
private final case class ColumnChildWidget(element: Element, underlying: Widget) extends Widget with Measured:
  def render(area: Rect, buffer: Buffer): Unit = underlying.render(area, buffer)

  override def heightAt(width: Int): Option[Int] = element.intrinsicHeight(width)

  override def widthAt(height: Int): Option[Int] =
    element.withProps(element.props.copy(constraint = None)).intrinsicWidth(height)

/** `children` as the items of a vertical `w.Column`, each carrying the DSL's measurement of its element. */
private def columnItems(children: Seq[Element]): Seq[w.LayoutItem] =
  children.map { child =>
    val item = child.layoutItem(Direction.Vertical)
    item.copy(widget = ColumnChildWidget(child, item.widget))
  }

/** Children stacked top to bottom.
  *
  * The same vocabulary as [[RowElement]] one axis over: an `.auto` child is exactly as tall as it measures, and
  * `.alignItems(...)` / `.alignSelf(...)` place children horizontally — a `button` in a centred column is as wide as
  * its label rather than the column. Its measured height is the `w.Column`'s own, so painting and measurement share one
  * allocation.
  */
final case class ColumnElement(
    override val children: Seq[Element],
    spacing: Int = 0,
    flex: Flex = Flex.Start,
    props: ElementProps = ElementProps(),
    crossAlign: CrossAlign = CrossAlign.Stretch,
) extends FlexContainer:
  type Self = ColumnElement
  override def debugName: String                = "column"
  private[dsl] override def layoutOnly: Boolean = true
  private def layout: w.Column                  = w.Column(columnItems(children), spacing, flex, crossAlign)
  def widget: Widget                            = layout
  def withFlex(mode: Flex): ColumnElement       = copy(flex = mode)
  def withSpacing(cells: Int): ColumnElement    = copy(spacing = math.max(0, cells))
  private[dsl] override def intrinsicHeight(width: Int): Option[Int]            =
    constrainedHeight(layout.heightAt(width))
  private[dsl] override def intrinsicWidth(height: Int): Option[Int]            =
    constrainedWidth(layout.widthAt(height))
  private[dsl] def withProps(props: ElementProps): ColumnElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): ColumnElement = copy(children = children)

/** Cross-axis alignment for the two axis containers. */
extension (row: RowElement)

  /** Places every child vertically inside the row by `align`; a child's own `.alignSelf(...)` overrides it. */
  def alignItems(align: CrossAlign): RowElement = row.copy(crossAlign = align)

extension (column: ColumnElement)

  /** Places every child horizontally inside the column by `align` — `CrossAlign.Center` for a centred stack of buttons;
    * a child's own `.alignSelf(...)` overrides it.
    */
  def alignItems(align: CrossAlign): ColumnElement = column.copy(crossAlign = align)

/** Children placed on a two-axis grid of column and row tracks — a dashboard of cards that does not reflow when one
  * card's content changes width.
  *
  * The tracks are ordinary [[io.worxbend.tui.core.Constraint]]s solved by the same solver `row` and `column` use, with
  * `columnGap` / `rowGap` blank cells between neighbouring tracks; an `Auto` track is sized by the children sitting in
  * that track alone. A child says where it goes with `.at(row, col)`, how many tracks it covers with `.span(rows,
  * cols)`, or which named area of an `.areas(...)` template it fills with `.area(name)`; a child that says nothing is
  * auto-flowed into the next free run of cells, in reading order (see [[io.worxbend.tui.core.GridLayout.autoFlow]]). A
  * child placed past the last track is clipped to the tracks there are, or not drawn at all when it starts outside
  * them.
  *
  * A child's own `.length` / `.fill` means nothing here — the tracks decide every size — so it is ignored.
  */
final case class GridElement(
    columns: Seq[Constraint],
    rows: Seq[Constraint],
    override val children: Seq[Element],
    columnGap: Int = 0,
    rowGap: Int = 0,
    template: Map[String, GridPlacement] = Map.empty,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = GridElement
  override def debugName: String                = "grid"
  private[dsl] override def layoutOnly: Boolean = true

  private def layout: w.Grid =
    val core       = GridLayout(columns, rows).withGap(math.max(0, columnGap), math.max(0, rowGap))
    val items      = children.map { child =>
      val spot = child.props.gridSpot
      spot.area.flatMap(template.get) match
        case Some(area) => GridItem.at(area.row, area.col, area.rowSpan, area.colSpan)
        case None       =>
          spot.position.fold(GridItem.span(spot.rowSpan, spot.colSpan))((r, c) =>
            GridItem.at(r, c, spot.rowSpan, spot.colSpan)
          )
    }
    val placements = GridLayout.autoFlow(math.max(1, columns.size), items)
    w.Grid(core, children.zip(placements).map((child, at) => w.GridCell(at, GridChildWidget(child))))

  def widget: Widget = layout

  /** Names the grid's cells with a template, one string per row: `.areas("top top", "main side")` gives a two-column
    * `top` area over a `main` and a `side` cell, and a child marked `.area("top")` fills it. A `.` leaves a cell
    * unnamed. A child naming an area the template does not have is auto-flowed instead.
    *
    * A template whose rows differ in length, or in which a name does not cover one filled rectangle, is a mistake in
    * the view's source rather than a runtime condition, so it fails here with an `IllegalArgumentException`.
    */
  def areas(templateRows: String*): GridElement =
    GridAreas.parse(templateRows*) match
      case Right(named)  => copy(template = named)
      case Left(problem) => throw IllegalArgumentException(problem)

  /** Different gaps between columns and between rows; the `gap` the factory took sets both. */
  def gaps(columnGap: Int, rowGap: Int): GridElement = copy(columnGap = columnGap, rowGap = rowGap)

  private[dsl] def withProps(props: ElementProps): GridElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): GridElement = copy(children = children)
  private[dsl] override def intrinsicHeight(width: Int): Option[Int] = constrainedHeight(layout.heightAt(width))

/** A grid cell's widget with the DSL's measurement attached. The grid's tracks decide every size, so the child's own
  * constraint is cleared for both queries.
  */
private final case class GridChildWidget(element: Element) extends Widget with Measured:
  private lazy val unconstrained: Element      = element.withProps(element.props.copy(constraint = None))
  def render(area: Rect, buffer: Buffer): Unit = element.widget.render(area, buffer)

  override def heightAt(width: Int): Option[Int] = unconstrained.intrinsicHeight(width)

  override def widthAt(height: Int): Option[Int] = unconstrained.intrinsicWidth(height)

/** Grid placement — read only by an enclosing [[GridElement]]. */
extension [E <: Element](element: E)

  /** Puts this child's top-left corner at track `row`, `col` of the enclosing `grid` (both from zero). */
  def at(row: Int, col: Int): element.Self =
    element.withProps(element.props.copy(gridSpot = element.props.gridSpot.copy(position = Some((row, col)))))

  /** Makes this child cover `rows` row tracks and `cols` column tracks of the enclosing `grid`, gaps included. */
  def span(rows: Int = 1, cols: Int = 1): element.Self =
    element.withProps(element.props.copy(gridSpot = element.props.gridSpot.copy(rowSpan = rows, colSpan = cols)))

  /** Puts this child in the named area of the enclosing `grid`'s `.areas(...)` template. */
  def area(name: String): element.Self =
    element.withProps(element.props.copy(gridSpot = element.props.gridSpot.copy(area = Some(name))))

/** Children laid left to right at their natural widths, wrapping onto a new line when the next one does not fit — tags,
  * chips, a bar of buttons. Widths are each child's [[Element.intrinsicWidth]], so text is measured in display columns
  * and a CJK label takes two per character; a `.length(n)` on a child fixes its width. A child that cannot say how wide
  * it is takes a line of its own. `gap` blank columns separate neighbours and `rowGap` blank rows separate lines.
  */
final case class FlowElement(
    override val children: Seq[Element],
    gap: Int = 1,
    rowGap: Int = 0,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = FlowElement
  override def debugName: String                                              = "flow"
  private[dsl] override def layoutOnly: Boolean                               = true
  private def layout: w.Flow                                                  =
    w.Flow(children.map(child => RowChildWidget(child, child.widget)), gap, rowGap)
  def widget: Widget                                                          = layout
  private[dsl] def withProps(props: ElementProps): FlowElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): FlowElement = copy(children = children)
  private[dsl] override def intrinsicHeight(width: Int): Option[Int] = constrainedHeight(layout.heightAt(width))
  private[dsl] override def intrinsicWidth(height: Int): Option[Int] = constrainedWidth(layout.widthAt(height))

/** Blank space: flexible by default, fixed once `.length(n)` sets a constraint. */
final case class SpacerElement(props: ElementProps = ElementProps()) extends Element:
  type Self = SpacerElement
  override def debugName: String                                 = "spacer"
  private[dsl] override def layoutOnly: Boolean                  = true
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
  override def debugName: String                                                    = "positioned"
  private[dsl] override def layoutOnly: Boolean                                     = true
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
  override def debugName: String                                                = "portal"
  private[dsl] override def layoutOnly: Boolean                                 = true
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
  override def debugName: String                                                = "filled"
  private[dsl] override def layoutOnly: Boolean                                 = true
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
  override def debugName: String                                                = "layers"
  private[dsl] override def layoutOnly: Boolean                                 = true
  def widget: Widget                                                            =
    (area, buffer) => children.foreach(_.widget.render(area, buffer))
  private[dsl] def withProps(props: ElementProps): LayersElement                = copy(props = props)
  private[dsl] override def withChildren(children: Seq[Element]): LayersElement = copy(children = children)
