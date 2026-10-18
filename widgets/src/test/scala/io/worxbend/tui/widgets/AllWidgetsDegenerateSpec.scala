package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Color, Constraint, Direction, Line, Rect, Style, Text, Widget}
import io.worxbend.tui.core.StatefulWidget
import io.worxbend.tui.testsupport.BufferAssertions.line

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** The two promises every widget in this module makes, asserted across the whole catalogue rather than for the handful
  * `DegenerateInputSpec` happens to name.
  *
  * A widget that throws out of `render` takes the render thread — and with it the application — down. A widget that
  * writes outside the `Rect` it was handed silently corrupts whatever its neighbour drew, because `Buffer.set` clips to
  * the *buffer* and knows nothing about the rectangle a widget was given. Neither promise had a test for most of the
  * widgets here, and the areas that break them are the ones a real terminal produces by ordinary means: a pane solved
  * to zero columns by a `Constraint`, a one-row status strip, a window dragged down to a couple of cells.
  *
  * Containment is checked on all four sides. `DegenerateInputSpec.spillToTheRight` renders at the buffer's own top-left
  * corner, so it can only ever see a rightward spill; here the widget's rect is inset into a larger frame pre-filled
  * with a sentinel cell, and every cell outside the rect must still hold that sentinel afterwards.
  */
final class AllWidgetsDegenerateSpec extends AnyFunSuite:

  /** The cell every column of the scratch frame starts out as. Anything else outside a widget's rect is a spill. */
  private val Sentinel: Cell = Cell("#", Style.Default)

  /** The scratch frame the containment cases render into — large enough to leave room on all four sides. */
  private val Field: Rect = Rect(0, 0, 16, 12)

  /** How far into [[Field]] a widget's rect starts, so a spill has somewhere to land above and to the left of it. */
  private val Inset: Int = 3

  /** The areas a laid-out application hands a widget when there is next to nothing left to give it. */
  private val Areas: Seq[Rect] = Seq(
    Rect(Inset, Inset, 0, 0),
    Rect(Inset, Inset, 0, 5),
    Rect(Inset, Inset, 5, 0),
    Rect(Inset, Inset, 1, 1),
    Rect(Inset, Inset, 2, 1),
    Rect(Inset, Inset, 1, 5),
  )

  private def sentinelBuffer(): Buffer =
    val buffer = Buffer(Field)
    var y      = 0
    while y < Field.height do
      var x = 0
      while x < Field.width do
        buffer.set(x, y, Sentinel)
        x += 1
      y += 1
    buffer

  private def assertStaysInside(buffer: Buffer, area: Rect, name: String): Unit =
    var y = 0
    while y < Field.height do
      var x = 0
      while x < Field.width do
        if !area.contains(x, y) then
          assert(buffer.get(x, y) == Sentinel, s"$name wrote outside $area at ($x,$y): ${buffer.get(x, y)}")
        x += 1
      y += 1

  /** A `StatefulWidget` paired with the state its caller owns, as the plain [[Widget]] this suite's table holds.
    *
    * An explicit anonymous class rather than a lambda: the two `render` signatures differ, so there is nothing for a
    * SAM conversion to bridge, and the state has to be captured somewhere.
    */
  private def bound[S](widget: StatefulWidget[S], state: S): Widget =
    new Widget:
      def render(area: Rect, buffer: Buffer): Unit = widget.render(area, buffer, state)

  // ---------------------------------------------------------------- the widgets under test

  private def chart: Chart =
    Chart(
      datasets = Seq(Dataset("cpu", Seq((0.0, 0.0), (0.5, 0.8), (1.0, 1.0)))),
      xBounds = Bounds(0.0, 1.0),
      yBounds = Bounds(0.0, 1.0),
      options = ChartOptions(showLabels = true, showLegend = true, xTitle = Some("t"), yTitle = Some("%")),
    )

  private def image: Image =
    val warm: Color.Rgb = Color.Rgb(200, 100, 50)
    val cool: Color.Rgb = Color.Rgb(10, 20, 30)
    Image(Vector(Vector(warm, cool), Vector(cool, warm)))

  private def table: Table =
    Table.ofStrings(
      rows = Seq(Seq("a", "one"), Seq("b", "two")),
      widths = Seq(Constraint.Length(2), Constraint.Fill(1)),
      header = Some(Seq("k", "v")),
      footer = Some(Seq("", "2")),
    )

  private def dataTable: Widget =
    val rows   = Seq(Seq("1", "ann"), Seq("2", "bob"))
    val widths = Seq(Constraint.Length(2), Constraint.Fill(1))
    val state  = DataTableState()
    state.selected = Some(1)
    state.selectedColumn = Some(1)
    state.sort = Some(ColumnSort(0, SortDirection.Ascending))
    bound(DataTable.fromStrings(Seq("id", "name"), rows, widths, DataTableOptions(footer = Some(Seq("", "2")))), state)

  private def logView: Widget =
    val state = LogState()
    (1 to 8).foreach(n => state.append(Line.raw(s"line $n")))
    bound(Log(), state)

  private def tree: Widget =
    val state = TreeState()
    state.expanded += Seq(0)
    state.selected = Some(Seq(0, 0))
    bound(Tree(Seq(TreeNode("root", Seq(TreeNode("child"))), TreeNode("other"))), state)

  private def menu: Widget =
    val entries = Seq(MenuEntry.Item("cut", Some("^X")), MenuEntry.Separator, MenuEntry.Item("copy", enabled = false))
    bound(Menu(entries), MenuState(selected = Some(0)))

  private def listView: Widget =
    bound(ListView(Seq("alpha", "beta"), highlightSymbol = "==> "), ListState(selected = Some(1)))

  private def multiProgress: Widget =
    val task = ProgressTask("下载 files", Some(10L))
    task.advance(3L)
    MultiProgress(Seq(task, ProgressTask("idle")), ProgressColumn.Default :+ ProgressColumn.SpinnerColumn())

  private def scrollView: Widget =
    bound(ScrollView(Paragraph(Text.raw("a\nb\nc\nd\ne")), 5), ScrollViewState())

  /** Every widget in the module that can be built without touching the filesystem, paired with a name for the failure
    * message. A thunk rather than a value, because the stateful ones mutate their caller-owned state as they render and
    * each area must start from the same place.
    */
  private val cases: Seq[(String, () => Widget)] = Seq(
    "AnimatedText" -> (() => AnimatedText("hello world", 250.millis)),
    "Badge"        -> (() => Badge("new")),
    "BarChart"     -> (() => BarChart(Seq("a" -> 3L, "b" -> 7L, "c" -> 5L), BarChartOptions(showValues = true))),
    "BigText"      -> (() => BigText("AB")),
    "Block"        -> (() => Block(Seq(BlockTitle.top(Line.raw("p")), BlockTitle.bottom(Line.raw("f"))))),
    "Button"       -> (() => Button("ok")),
    "Calendar"     -> (() => Calendar(2026, 6, selected = Some(15))),
    "Canvas"    -> (() => Canvas(Bounds(0.0, 9.0), Bounds(0.0, 9.0), Seq(Shape.Points(Seq((0.0, 0.0), (9.0, 9.0)))))),
    "Chart"     -> (() => chart),
    "Checkbox"  -> (() => Checkbox("ship it now", checked = true)),
    "Clear"     -> (() => Clear()),
    "DataTable" -> (() => dataTable),
    "Dialog"    -> (() => Dialog("confirm", Text.raw("really do it?"), Seq("OK", "Cancel"))),
    "Dropdown"  -> (() => bound(Dropdown(Seq("a", "b", "c"), 1), DropdownState(open = true))),
    "DualSparkline"    -> (() => DualSparkline(Seq(1L, 5L, 3L), Seq(2L, 4L, 6L))),
    "Gauge"            -> (() => Gauge(0.42)),
    "GroupedBarChart"  -> (() => GroupedBarChart(Seq(BarGroup.of("q1", "a" -> 3L, "b" -> 5L)))),
    "Heatmap"          -> (() => Heatmap(Seq(Seq(1.0, 2.0), Seq(3.0, 4.0)))),
    "Image"            -> (() => image),
    "IndeterminateBar" -> (() => IndeterminateBar(300.millis)),
    "LineGauge"        -> (() => LineGauge(0.42)),
    "LinearSpinner"    -> (() => LinearSpinner(300.millis)),
    "Link"             -> (() => Link("docs", "https://example.com")),
    "ListView"         -> (() => listView),
    "Log"              -> (() => logView),
    "Markdown"         -> (() => Markdown("# Title\n\n- one\n- two\n\n> note\n")),
    "Marquee"          -> (() => Marquee("scrolling text", 300.millis)),
    "Menu"             -> (() => menu),
    "MultiProgress"    -> (() => multiProgress),
    "Notice"           -> (() => Notice("disk almost full", NoticeLevel.Warning)),
    "OrbitSpinner"     -> (() => OrbitSpinner(300.millis)),
    "Paginator"        -> (() => Paginator(2, 7)),
    "Paragraph"        -> (() => Paragraph(Text.raw("hello wrapping world"), overflow = Overflow.Wrap)),
    "PieChart"         -> (() => PieChart(Seq("a" -> 1.0, "b" -> 2.0))),
    "RadioGroup"       -> (() => RadioGroup(Seq("first", "second"), selected = 1)),
    "Rule"             -> (() => Rule(Some("section"))),
    "Scrollbar (h)"    -> (() =>
      Scrollbar(120, 40, ScrollbarOptions(orientation = Direction.Horizontal, beginSymbol = Some("◀")))
    ),
    "Scrollbar (v)"    -> (() => Scrollbar(120, 40)),
    "ScrollView"       -> (() => scrollView),
    "Select"           -> (() => Select(Seq("alpha", "beta"), selected = 1)),
    "Skeleton"         -> (() => Skeleton(300.millis)),
    "Slider"           -> (() => Slider(50)),
    "Spacer"           -> (() => Spacer),
    "Sparkline"        -> (() => Sparkline(Seq(1L, 5L, 3L, 8L, 2L))),
    "Spinner"          -> (() => Spinner(300.millis, "loading")),
    "SpinnerGrid"      -> (() => SpinnerGrid(300.millis)),
    "StackedBarChart"  -> (() => StackedBarChart(Seq("a" -> Seq(1L, 2L), "b" -> Seq(3L, 1L)))),
    "Table"            -> (() => table),
    "Tabs"             -> (() => Tabs(Seq(Line.raw("one"), Line.raw("two")), selected = 1)),
    "TextArea"         -> (() => bound(TextArea(), TextAreaState("line1\nline2\nline3"))),
    "TextInput"        -> (() => bound(TextInput(), TextInputState("the quick brown fox"))),
    "Toggle"           -> (() => Toggle("dark mode", on = true)),
    "Tooltip"          -> (() => Tooltip("a hint")),
    "Tree"             -> (() => tree),
  )

  cases.foreach: (name, build) =>
    test(s"$name survives a degenerate area and stays inside the rect it was given"):
      Areas.foreach: area =>
        val buffer = sentinelBuffer()
        build().render(area, buffer)
        assertStaysInside(buffer, area, name)

  // ---------------------------------------------------------------- caller-supplied wide glyphs

  /** A fullwidth grapheme: one cluster, two terminal columns. */
  private val Wide: String = "漢"

  /** The one-row strip the wide-glyph cases are drawn into. An even width, so a widget that tiles the glyph correctly
    * fills it exactly and there is no half cell at the right edge to argue about.
    */
  private val Strip: Int = 12

  private val widePreset: ProgressPreset = ProgressPreset("wide", fill = Wide, track = Wide)

  private def wideScrollbar: Scrollbar =
    Scrollbar(
      contentLength = 1,
      options = ScrollbarOptions(
        orientation = Direction.Horizontal,
        trackSymbol = Wide,
        thumbSymbol = Wide,
        thumbWhenFits = true,
      ),
    )

  private def wideSparkline: Sparkline =
    Sparkline(
      data = Seq.fill(Strip)(1L),
      absentColumns = (0 until Strip).toSet,
      absentSymbol = Some(Wide),
    )

  /** What one widget drew across a [[Strip]]-column row, as glyphs rather than as cells.
    *
    * `BufferAssertions.line` steps over the continuation column of a wide grapheme, so a correctly tiled strip reads as
    * `Strip / 2` glyphs and one that erased itself reads as blanks.
    */
  private def stripOf(widget: Widget): String =
    val buffer = Buffer(Rect(0, 0, Strip, 1))
    widget.render(buffer.area, buffer)
    line(buffer, 0)

  test("a widget that advances by the cluster's own width tiles a fullwidth glyph across its strip"):
    // The control for the characterisations below, and the shape they should eventually take. `Marquee` writes through
    // `ClusterRow.put`, which advances the write head by the cluster's width rather than by one column, so the same
    // glyph the six widgets below lose is laid down cleanly end to end.
    assert(stripOf(Marquee(Wide * 3, 0.millis, gap = 0)) == Wide * (Strip / 2))

  /** Every widget that paints a strip out of a glyph its caller chose, handed a glyph two columns wide.
    *
    * These all drive `Buffer.set` once per *column*, which is not the same thing as once per *glyph*. `setMeasured`
    * blanks `cells(index - 1)` when the column it is handed is a wide grapheme's continuation, so a loop stepping one
    * column at a time erases the glyph the previous iteration drew, and the strip comes back blank while the widget
    * believes it painted every cell. `Buffer.fill` and `ClusterRow.put` both already step by the cluster's width; these
    * loops each reimplemented that rule and dropped half of it.
    */
  private val erasingCases: Seq[(String, () => Widget)] = Seq(
    "Gauge"            -> (() => Gauge(1.0, label = ProgressLabel.Hidden, preset = Some(widePreset))),
    "IndeterminateBar" -> (() => IndeterminateBar(0.millis, preset = widePreset, motion = IndeterminateMotion.Pulse)),
    "LineGauge"        -> (() => LineGauge(1.0, label = ProgressLabel.Hidden, preset = widePreset)),
    "Scrollbar"        -> (() => wideScrollbar),
    "Skeleton"         -> (() => Skeleton(0.millis, baseSymbol = Wide, bandSymbol = Wide)),
    "Sparkline"        -> (() => wideSparkline),
  )

  erasingCases.foreach: (name, build) =>
    test(s"CHARACTERISATION: $name erases a caller-supplied fullwidth glyph rather than tiling it"):
      // Today's behaviour, pinned rather than endorsed — the strip comes back entirely blank. Whether the fix is to
      // advance by `CharWidth.ofCluster` (what the `Marquee` case above shows) or to refuse a glyph wider than one
      // column outright (what `Shadow.Symbol` documents for the same hazard) is a decision for whoever owns these
      // widgets; either way this test goes red and should be rewritten as the positive assertion, not deleted.
      val row = stripOf(build())
      assert(row.forall(_ == ' '), s"$name no longer blanks its strip — rewrite this as the positive case: '$row'")
