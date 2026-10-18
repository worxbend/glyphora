package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Alignment, CharWidth, Constraint, Flex, Line, Span, Style, Text, Widget}
import io.worxbend.tui.runtime.{LogLevel, LogRecord, LogRing}
import io.worxbend.tui.widgets.{TableCell, TableRow}
import io.worxbend.tui.widgets as w

import java.time.{LocalTime, ZoneId}
import java.time.format.DateTimeFormatter

/** A block of plain text; one row per newline-separated line.
  *
  * Two things about how the text meets the edges of its area can be asked for here. *Overflow* decides what happens to
  * a line longer than the area is wide: [[io.worxbend.tui.widgets.Overflow.Clip]], the default, cuts it off at the
  * right edge, while `.wrapped` breaks it onto as many rows as it needs. *Alignment* decides where a line shorter than
  * the area sits: at the left edge by default, or in the middle with `.centered`, or against the right edge with
  * `.rightAligned`.
  *
  * Both were already parameters of the `widgets.Paragraph` this node renders through, but the node built that paragraph
  * with the defaults and offered no way to say otherwise, so a paragraph of prose in the DSL was clipped at the first
  * screen column it ran past and every heading had to be centred by hand with padding.
  *
  * Wrapping also changes what the node claims from its container, and it has to: a wrapping paragraph does not want a
  * column per character of its longest line — it wants whatever width it is given and however many rows that width
  * makes it into. A clipping paragraph still claims its exact unwrapped box, which is what makes `row(text("ab"),
  * text("cd"))` put the two next to each other rather than letting the first eat the row.
  */
final case class TextElement(
    content: String,
    overflow: w.Overflow = w.Overflow.Clip,
    alignment: Alignment = Alignment.Left,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = TextElement
  override def debugName: String = "text"
  // Layout and painting share this immutable description and its bounded measurement cache. Node copies start fresh.
  lazy val widget: Widget        = w.Paragraph(Text.styled(content, props.style), alignment, overflow)

  /** Breaks lines that do not fit onto further rows instead of cutting them off at the right edge.
    *
    * The break happens at a grapheme-cluster boundary — never inside a wide character, an emoji or a combining sequence
    * — rather than at a word boundary, so a long word is split across rows rather than moved down whole.
    */
  def wrapped: TextElement = copy(overflow = w.Overflow.Wrap)

  /** Cuts each line at the right edge of the area, the default. Undoes a `.wrapped` on an element built elsewhere. */
  def clipped: TextElement = copy(overflow = w.Overflow.Clip)

  /** Positions each line within the area's width: left (the default), centre, or right. */
  def aligned(alignment: Alignment): TextElement = copy(alignment = alignment)

  /** Centres every line in the area — the alignment a title or a placeholder message usually wants. */
  def centered: TextElement = aligned(Alignment.Center)

  /** Pushes every line against the right edge, for a column of numbers or a right-hand caption. */
  def rightAligned: TextElement = aligned(Alignment.Right)

  /** Every line pinned to the left edge — the default, spelled out; undoes a `.centered` on an element built elsewhere.
    */
  def leftAligned: TextElement = aligned(Alignment.Left)

  private[dsl] def withProps(props: ElementProps): TextElement = copy(props = props)

  /** A clipping paragraph claims the exact box its unwrapped lines measure, in display columns. A wrapping one claims
    * the container's full width instead: its height is a function of the width it ends up with, which the layout solver
    * has not decided yet, so the rows are left to `intrinsicHeight` (which asks the paragraph itself, through
    * `Measured`, once a width exists).
    */
  private[dsl] override def claim: SizeClaim =
    if overflow.wraps then SizeClaim.Fill
    else
      val lines = Text.splitLines(content)
      SizeClaim.box(lines.map(CharWidth.of).maxOption.getOrElse(0), lines.length)

/** One terminal row of differently-styled runs — the mixed-style counterpart of [[TextElement]].
  *
  * `text(...)` paints a whole block in one style, which cannot say "Status: " in the default colour and "OK" in green.
  * Before this node the way to get that was `row(text("Status: ").length(8), text("OK").fg(Color.Green))`, i.e. a hand
  * counted display width in the source — wrong the moment the label is translated, and wrong today for any CJK or emoji
  * text, where one character is two columns. Here the widths are measured, not declared.
  *
  * The element's own style is carried on the `Line` as its base layer and resolved by the renderer, so `line(...).dim`
  * dims the whole row while a span that set its own colour keeps it.
  */
final case class LineElement(spans: Seq[Span], align: Option[Alignment] = None, props: ElementProps = ElementProps())
    extends Element:
  type Self = LineElement
  override def debugName: String                               = "line"
  lazy val widget: Widget                                      =
    w.Paragraph(Text(Seq(Line(spans, align, props.style))))
  private[dsl] def withProps(props: ElementProps): LineElement = copy(props = props)

  /** Places this row inside whatever width it is given; without one of these it sits at the left edge.
    *
    * The alignment is carried by the `Line` itself rather than by the paragraph that draws it, so stacking a plain
    * `line(...)` above a `line(...).rightAligned` in one `column` puts a label on the left and a total on the right
    * with no hand-counted padding in between.
    */
  def aligned(alignment: Alignment): LineElement = copy(align = Some(alignment))

  /** This row pinned to the left edge — the default, spelled out. */
  def leftAligned: LineElement = aligned(Alignment.Left)

  /** This row centred in the columns it is given. */
  def centered: LineElement = aligned(Alignment.Center)

  /** This row pinned to the right edge, which is what a column of numbers wants. */
  def rightAligned: LineElement = aligned(Alignment.Right)

  /** Exactly one row, and exactly as wide as the spans measure — in display columns, via `CharWidth`. */
  private[dsl] override def claim: SizeClaim = SizeClaim.box(spans.map(_.width).sum, 1)

/** A one-row filled bar with a caption over it; the caption defaults to the percentage.
  *
  * The caption trio and the two fill ramps come from [[ProgressMeterBuilders]], shared with [[ProgressBarElement]], so
  * the two progress meters are captioned and colored the same way whichever one a view reaches for. `trackStyle` and
  * `fillStyle` come from the same [[LoadingTheme]] the whole progress-and-spinner family draws from, so a gauge and a
  * `progressBar` side by side are the same two colours.
  */
final case class GaugeElement(
    ratio: Double,
    label: w.ProgressLabel,
    trackStyle: Style,
    fillStyle: Style,
    fillRamp: Option[w.ColorRamp],
    props: ElementProps = ElementProps(),
) extends Element,
      ProgressMeterBuilders[GaugeElement]:
  type Self = GaugeElement
  override def debugName: String = "gauge"
  def widget: Widget = w.Gauge(ratio, label, trackStyle.patch(props.style), fillStyle.patch(props.style), fillRamp)

  protected def progressLabel: w.ProgressLabel                                                = label
  protected def progressRamp: Option[w.ColorRamp]                                             = fillRamp
  protected def withProgress(label: w.ProgressLabel, ramp: Option[w.ColorRamp]): GaugeElement =
    copy(label = label, fillRamp = ramp)

  private[dsl] def withProps(props: ElementProps): GaugeElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                    = SizeClaim.OneRow

/** One styled message line, themed at construction. */
final case class NoticeElement(
    message: String,
    level: w.NoticeLevel,
    timestamp: Option[LocalTime],
    messageStyle: Style,
    accentStyle: Style,
    timestampStyle: Style,
    overflow: w.Overflow = w.Overflow.Clip,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = NoticeElement
  override def debugName: String = "notice"
  def widget: Widget             =
    w.Notice(
      message,
      level,
      timestamp,
      messageStyle.patch(props.style),
      accentStyle,
      timestampStyle,
      overflow = overflow,
    )

  /** Stamps the notice with the moment the event happened. Pass the time explicitly — a widget that read the clock
    * itself would render a different frame on every repaint.
    */
  def at(time: LocalTime): NoticeElement = copy(timestamp = Some(time))

  /** Lets a long message wrap onto further rows instead of clipping. */
  def wrapped: NoticeElement = copy(overflow = w.Overflow.Wrap)

  private[dsl] def withProps(props: ElementProps): NoticeElement = copy(props = props)

/** A short inline label, themed at construction. */
final case class BadgeElement(
    label: String,
    variant: w.BadgeVariant,
    badgeStyle: Style,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = BadgeElement
  override def debugName: String = "badge"
  def widget: Widget             = w.Badge(label, variant, badgeStyle.patch(props.style))

  /** Bracketed text with no block of colour behind it — for a badge sitting inside prose. */
  def outline: BadgeElement = copy(variant = w.BadgeVariant.Outline)

  /** A coloured dot before plain text: carries the colour without the emphasis. */
  def dot: BadgeElement = copy(variant = w.BadgeVariant.Dot)

  private[dsl] def withProps(props: ElementProps): BadgeElement = copy(props = props)

  /** One row, and exactly as wide as the badge paints itself — asking the widget is what keeps the claimed box from
    * drifting away from the drawn one.
    */
  private[dsl] override def claim: SizeClaim =
    w.Badge(label, variant).widthAt(1).fold(SizeClaim.OneRow)(width => SizeClaim.box(width, 1))

/** A one-row dense line chart of recent values. */
final case class SparklineElement(
    data: Seq[Long],
    max: Option[Long] = None,
    direction: w.SparkDirection = w.SparkDirection.LeftToRight,
    styleFor: (Int, Long) => Option[Style] = w.BarStyling.NoOverride,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = SparklineElement
  override def debugName: String = "sparkline"
  def widget: Widget             = w.Sparkline(data, max, direction, style = props.style, styleFor = styleFor)

  /** Pins the top of the scale instead of letting it float to the largest value present.
    *
    * Without this a sparkline always uses the full row height, which is what makes two of them *not* comparable: a
    * series peaking at 5 and one peaking at 5000 draw the same shape. Pin both to the same ceiling and the heights mean
    * the same thing.
    */
  def max(ceiling: Long): SparklineElement = copy(max = Some(ceiling))

  /** Anchors the series to the right edge, so the newest reading is always in the last column.
    *
    * By default a sparkline keeps the oldest points and clips the newest off the right, which is the wrong end for a
    * live metric: as readings arrive the one you care about is the one that disappears. With this the history scrolls
    * off the left instead and the latest value stays put, so the caller no longer has to trim the window by hand.
    */
  def rightToLeft: SparklineElement = copy(direction = w.SparkDirection.RightToLeft)

  /** Anchors the series to either edge — see [[rightToLeft]] for why that matters. */
  def direction(anchor: w.SparkDirection): SparklineElement = copy(direction = anchor)

  /** Restyles individual columns: given a point's index in `data` and its value, return `Some(style)` to make that
    * column look different and `None` to leave it as it is.
    *
    * The style is *patched over* the sparkline's own, so an override that sets only a foreground colour keeps
    * everything else the element was given. This is how one reading is marked without splitting the series across two
    * widgets:
    *
    * {{{
    * sparkline(readings).styleFor((_, value) => Option.when(value > limit)(Style.Default.withFg(Color.Red)))
    * }}}
    */
  def styleFor(restyle: (Int, Long) => Option[Style]): SparklineElement = copy(styleFor = restyle)

  private[dsl] def withProps(props: ElementProps): SparklineElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                        = SizeClaim.OneRow

/** A one-row tab strip. Purely presentational — see [[TabbedContentElement]] for the interactive version. */
final case class TabsElement(
    titles: Seq[String],
    selected: Int = 0,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = TabsElement
  override def debugName: String                               = "tabs"
  def widget: Widget                                           = w.Tabs(titles.map(Line.raw), selected, props.style)
  private[dsl] def withProps(props: ElementProps): TabsElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                   = SizeClaim.OneRow

/** A static table of rows under fixed column widths.
  *
  * A [[FlexContainer]], so the alignment builders every `row` and `column` has work here too — `.center` and `.flexEnd`
  * place the block of columns inside the area when fixed-width columns leave space over, and `.gap(n)` sets the blank
  * cells between neighbouring columns. Its "children" are its columns rather than nested elements, which is why it
  * carries the trait without carrying a `children` list.
  */
final case class TableElement(
    rows: Seq[Seq[String]],
    widths: Seq[Constraint],
    header: Option[Seq[String]] = None,
    footer: Option[Seq[String]] = None,
    columnSpacing: Int = 1,
    flex: Flex = Flex.Start,
    props: ElementProps = ElementProps(),
) extends FlexContainer:
  type Self = TableElement
  override def debugName: String = "table"
  def widget: Widget             =
    // every argument by name, so that this call deliberately stopping at `style` — leaving `headerStyle` and
    // `footerStyle` at their defaults — is visible, and a new `ofStrings` parameter cannot slide into the wrong slot
    w.Table.ofStrings(
      rows = rows,
      widths = widths,
      header = header,
      footer = footer,
      columnSpacing = columnSpacing,
      flex = flex,
      style = props.style,
    )

  /** This table rebuilt with the leftover width placed differently.
    *
    * Fixed-width columns — `Constraint.Length(8)` and friends — can add up to less than the space the table was given.
    * That leftover always used to trail off the right-hand side, because the widget passed no flex to the layout at
    * all. `Flex.Center` centres the block of columns instead and `Flex.End` pushes it right. It changes nothing when a
    * `Fill` or `Min` column is already soaking up the slack, because then there is no leftover to place.
    */
  def withFlex(mode: Flex): TableElement = copy(flex = mode)

  /** This table rebuilt with `cells` blank columns between neighbouring columns (negative counts clamp to zero). */
  def withSpacing(cells: Int): TableElement = copy(columnSpacing = math.max(0, cells))

  /** Adds a bold caption row above the data — one label per column, in the same order as `widths`.
    *
    * The header costs one row of the area and does not scroll away with the rows, because a static table does not
    * scroll at all. Pass a computed sequence with `.header(labels*)`.
    */
  def header(labels: String*): TableElement = copy(header = Some(labels))

  /** Adds a bold summary row pinned to the *bottom* of the table's area — one cell per column.
    *
    * Pinned, not appended: a totals row that followed the last data row would float in the middle of a pane the rows do
    * not fill. It is laid out on the same solved column widths as the body, which is the thing a second `table` stacked
    * underneath could not promise — that one had to repeat the width constraints, and drifted the moment they changed.
    * It costs one row of the area, like `.header(...)`.
    */
  def footer(labels: String*): TableElement = copy(footer = Some(labels))

  private[dsl] def withProps(props: ElementProps): TableElement = copy(props = props)

/** A static table whose cells carry their own styles, and whose rows can be taller than one line.
  *
  * The same widget as [[TableElement]] draws both; the difference is what a cell is made of. Here a cell is a
  * [[io.worxbend.tui.core.Line]] — or a [[io.worxbend.tui.widgets.TableCell]] when it has to span several columns — so
  * one figure in a row can be red while the rest of the row stays plain. A row is either a bare sequence of those
  * cells, one line tall, or a [[io.worxbend.tui.widgets.TableRow]], which adds a height, top and bottom margins and a
  * style of its own. Both shapes may appear in the same table.
  *
  * Before this node the DSL could express none of that: `table(...)` takes `String`s, so a caller wanting a single
  * green cell had to drop out of the element tree into `widget(w.Table(...))` and give up every fluent builder on the
  * way. The two nodes stay separate rather than becoming one node over a union type, because a union two collections
  * deep is unpleasant to read and to infer — the same reasoning that gives the widget its `Table.ofStrings`.
  *
  * A [[FlexContainer]], exactly like [[TableElement]]: `.center` / `.flexEnd` place the block of columns when
  * fixed-width columns leave room over, and `.gap(n)` sets the blank cells between neighbouring columns.
  */
final case class StyledTableElement(
    rows: Seq[TableRow.Source],
    widths: Seq[Constraint],
    header: Option[Seq[TableCell.Source]] = None,
    footer: Option[Seq[TableCell.Source]] = None,
    columnSpacing: Int = 1,
    flex: Flex = Flex.Start,
    props: ElementProps = ElementProps(),
) extends FlexContainer:
  type Self = StyledTableElement
  override def debugName: String = "styledTable"
  def widget: Widget             =
    w.Table(rows, widths, header, footer, columnSpacing, flex, props.style)

  /** This table rebuilt with the leftover width placed differently — see [[TableElement.withFlex]]. */
  def withFlex(mode: Flex): StyledTableElement = copy(flex = mode)

  /** This table rebuilt with `cells` blank columns between neighbouring columns (negative counts clamp to zero). */
  def withSpacing(cells: Int): StyledTableElement = copy(columnSpacing = math.max(0, cells))

  /** Adds a bold caption row above the data — one cell per column, in the same order as `widths`. */
  def header(cells: TableCell.Source*): StyledTableElement = copy(header = Some(cells))

  /** Adds a bold summary row pinned to the *bottom* of the table's area — see [[TableElement.footer]]. */
  def footer(cells: TableCell.Source*): StyledTableElement = copy(footer = Some(cells))

  private[dsl] def withProps(props: ElementProps): StyledTableElement = copy(props = props)

/** Escape hatch: any core [[Widget]] as a leaf element (its rendering ignores the element style).
  *
  * Width-dependent content — wrapped markdown, a paragraph, an image — reports its own height to the measurement pass
  * by implementing [[io.worxbend.tui.core.Measured]] on the widget; this node needs no measurement wiring of its own.
  *
  * That measurement is *not* a layout claim, though: the two passes ask different questions and only the measurement
  * pass (scroll views, auto-sized containers) consults `Measured`. By default a wrapped widget takes every cell its
  * siblings left over, because a bare `Widget` has no axis-independent size to derive one from — `heightAt` needs a
  * width the layout solver has not decided yet, so guessing would mis-size wrapped prose rather than fix anything.
  *
  * `rows` is how the call site says otherwise: `Some(n)` means "exactly `n` rows tall, and whatever width the container
  * has", which is the shape every fixed-height widget actually wants. Use it rather than `.length(n)` — that extension
  * sets one constraint that the container applies along *whichever* axis it runs, so a `.length(1)` widget is one row
  * tall in a `column` and one *column wide* in a `row`.
  */
final case class WidgetElement(
    wrapped: Widget,
    rows: Option[Int] = None,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = WidgetElement
  override def debugName: String = "widget"
  def widget: Widget             = wrapped

  /** This widget rebuilt to claim exactly `count` rows of height, taking the container's full width.
    *
    * The height a fixed-height widget wants is a fact about the widget, not about the container it happens to sit in,
    * so it belongs here rather than in a `.length(count)` at the call site: `row(widget(divider).rows(1), …)` keeps its
    * full width, where `row(widget(divider).length(1), …)` would be a single column.
    */
  def rows(count: Int): WidgetElement = copy(rows = Some(math.max(0, count)))

  private[dsl] def withProps(props: ElementProps): WidgetElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                     = rows.fold(SizeClaim.Fill)(SizeClaim.rows)

/** A free-form drawing surface, with the sub-cell resolution and the marker glyph reachable from the view.
  *
  * A terminal cell is a single character, so a plot drawn one dot per cell is very coarse. The underlying
  * [[io.worxbend.tui.widgets.Canvas]] can do better by packing several *sub-pixels* into one cell — two stacked
  * half-blocks (`▀`, `▄`, `█`), or the eight dots of a braille pattern (`⣿`), which is eight times the detail of one
  * marker per cell. Before this node the `canvas(...)` factory built the widget with both of those parameters left at
  * their defaults and gave back a plain `WidgetElement`, so a view had no way to ask for anything but the coarsest
  * mode; getting braille meant dropping out of the DSL and constructing `widgets.Canvas` by hand.
  *
  * The builders read the same way as the ones [[OrbitSpinnerElement]] already carries, which is the other place in this
  * package that paints onto a canvas: `.markers(glyph)` picks one-glyph-per-cell drawing with that glyph, `.halfBlocks`
  * and `.braille` pick the two finer modes. The marker is read only in cell mode — the finer modes have fixed glyph
  * sets — so setting one does not silently switch resolution back.
  */
final case class CanvasElement(
    xBounds: w.Bounds,
    yBounds: w.Bounds,
    shapes: Seq[w.Shape],
    marker: String = "•",
    resolution: w.CanvasResolution = w.CanvasResolution.Cell,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = CanvasElement
  override def debugName: String = "canvas"
  def widget: Widget             = w.Canvas(xBounds, yBounds, shapes, marker = marker, resolution = resolution)

  /** One `glyph` per hit cell — the coarsest mode, and the one that needs no special font. */
  def markers(glyph: String): CanvasElement = copy(marker = glyph, resolution = w.CanvasResolution.Cell)

  /** Two stacked sub-pixels per cell, drawn with `▀`/`▄`/`█`: twice the vertical detail, no braille font needed. */
  def halfBlocks: CanvasElement = copy(resolution = w.CanvasResolution.HalfBlock)

  /** Eight sub-pixels per cell (2 wide by 4 tall) drawn with braille patterns — the smoothest lines this toolkit can
    * draw, on any terminal whose font covers the braille block.
    */
  def braille: CanvasElement = copy(resolution = w.CanvasResolution.Braille)

  private[dsl] def withProps(props: ElementProps): CanvasElement = copy(props = props)

/** The tail of a [[LogRing]], one record per row, filtered by level and logger prefix and drawn through the `Log`
  * widget — the console an app shows when the user presses the key it bound for it.
  *
  * Each row is `HH:mm:ss LEVEL logger message`, the time and logger in the theme's `muted`, the level in `warning` or
  * `error` for those two severities, and a record's throwable appended as ` (Class: message)` — the class and message,
  * never the stack trace, which belongs in a file. The console always follows the tail: it shows the newest records
  * that fit, and there is no scroll state to own, so a view may build one per frame. The ring is read once per render,
  * as a snapshot, and it is thread-safe, so records appended from a worker thread show up on the next frame without any
  * marshalling — a frame that must happen *because* a record arrived is still the app's to request.
  *
  * `.minLevel(level)` hides everything below `level`; `.loggerPrefix("app.net")` keeps records from that logger and its
  * children (the prefix is matched at a `.` boundary, so `app` does not match `apple`); `.zone(zone)` chooses the zone
  * the timestamp is rendered in, the system default unless said otherwise.
  */
final case class LogConsoleElement(
    ring: LogRing,
    minimumLevel: LogLevel,
    prefix: String,
    zone: ZoneId,
    warningStyle: Style,
    errorStyle: Style,
    mutedStyle: Style,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = LogConsoleElement
  override def debugName: String = "logConsole"

  /** Hides records below `level`. */
  def minLevel(level: LogLevel): LogConsoleElement = copy(minimumLevel = level)

  /** Keeps records from logger `prefix` and its children only; the empty string keeps every logger. */
  def loggerPrefix(prefix: String): LogConsoleElement = copy(prefix = prefix)

  /** The zone timestamps are rendered in. */
  def zone(zone: ZoneId): LogConsoleElement = copy(zone = zone)

  def widget: Widget =
    (area, buffer) =>
      val rows = ring.snapshot.filter(matches)
      if !area.isEmpty && rows.nonEmpty then
        val state = w.LogState(rows.size)
        rows.foreach(record => state.append(row(record)))
        w.Log(props.style).render(area, buffer, state)

  private def matches(record: LogRecord): Boolean =
    record.level.atLeast(minimumLevel) &&
      (prefix.isEmpty || record.logger == prefix || record.logger.startsWith(prefix + "."))

  private def row(record: LogRecord): Line =
    val level    = record.level match
      case LogLevel.Trace   => Span("TRACE", mutedStyle)
      case LogLevel.Debug   => Span("DEBUG", mutedStyle)
      case LogLevel.Info    => Span("INFO ", Style.Default)
      case LogLevel.Warning => Span("WARN ", warningStyle)
      case LogLevel.Error   => Span("ERROR", errorStyle)
    val time     = LogConsoleElement.TimeFormat.format(record.instant.atZone(zone))
    val failure  = record.throwable.map(error => Span(s" ($error)", mutedStyle)).toSeq
    val message  = Span(record.message, Style.Default)
    val logger   = Span(record.logger, mutedStyle)
    val separate = Span.raw(" ")
    Line(Seq(Span(time, mutedStyle), separate, level, separate, logger, separate, message) ++ failure)

  private[dsl] def withProps(props: ElementProps): LogConsoleElement = copy(props = props)

object LogConsoleElement:
  private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
