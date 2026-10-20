package io.worxbend.tui.widgets

import io.worxbend.tui.core.*

import org.scalatest.funsuite.AnyFunSuite

import java.time.{LocalDate, LocalTime}

/** Offset-buffer sentinel sweep retained from the review: every write, including styling, belongs to the widget. */
final class WidgetClipInvariantSpec extends AnyFunSuite:

  private val field    = Rect(10, 5, 40, 30)
  private val sentinel = Cell("#", Style.Default)
  private val areas    = Seq(
    Rect(13, 8, 0, 0),
    Rect(13, 8, 0, 5),
    Rect(13, 8, 5, 0),
    Rect(13, 8, 1, 1),
    Rect(13, 8, 2, 1),
    Rect(13, 8, 1, 5),
    Rect(13, 8, 3, 2),
    Rect(13, 8, 7, 3),
    Rect(13, 8, 12, 4),
    Rect(13, 8, 23, 9),
    Rect(5, 2, 12, 8),
    Rect(45, 30, 20, 10),
    Rect(-100, -100, 5, 5),
    Rect(12, 7, 100000, 3),
    Rect(12, 7, 3, 100000),
    Rect(-1000, 6, 2000, 2),
    Rect(14, 9, 30, 20),
    Rect(Int.MaxValue - 5, 8, 3, 2),
  )

  private def bind[S](widget: StatefulWidget[S], state: => S): () => Widget = () =>
    val current = state
    (area, buffer) => widget.render(area, buffer, current)

  private def paragraph(text: String): Widget = Paragraph(Text.raw(text))
  private val image = RgbaImage.tabulate(8, 6)((x, y) => 0xff000000 | ((x * 30) << 16) | ((y * 40) << 8))
  private val nodes = Seq(TreeNode("root 漢字", Seq(TreeNode("child"))), TreeNode("other"))

  private val widgets: Seq[(String, () => Widget)] = Seq(
    "Accordion"           -> bind(
      Accordion(Seq(Accordion.Section("One 漢字", paragraph("body")), Accordion.Section("Two", paragraph("two")))),
      AccordionState(Some(1)),
    ),
    "Breadcrumb"          -> (() => Breadcrumb(Seq("Home", "Projects", "glyphora 漢字", "widgets", "Breadcrumb"))),
    "ColorPicker palette" -> bind(ColorPicker(), ColorPickerState(196)),
    "ColorPicker RGB"     -> bind(ColorPicker(mode = ColorPickerMode.Rgb), ColorPickerState(33)),
    "DatePicker"          -> bind(
      DatePicker(),
      DatePickerState(LocalDate.of(2026, 7, 15), () => LocalDate.of(2026, 7, 1), Some(LocalDate.of(2026, 7, 3)), None),
    ),
    "DiffView split"      -> bind(
      DiffView(Patch.of("a\nb\nc 漢字\n", "a\nB\nc 漢字\nd\n"), DiffViewOptions(layout = DiffLayout.Split)),
      DiffViewState(),
    ),
    "DiffView unified"    -> bind(DiffView(Patch.of("a\nb\nc\n", "a\nB\nc\nd\n")), DiffViewState()),
    "Grid"                -> (() =>
      Grid(
        GridLayout(Seq(Constraint.Auto, Constraint.Fill(1)), Seq(Constraint.Length(1), Constraint.Auto)),
        Seq(
          GridEntry(GridPlacement(0, 0), Badge("漢字")),
          GridEntry(GridPlacement(0, 1), paragraph("wide")),
          GridEntry(GridPlacement(1, 0, 1, 2), paragraph("span")),
        ),
      )
    ),
    "Flow"                -> (() => Flow(Seq(Badge("a"), Badge("漢字漢字"), Button("ok"), paragraph("body")))),
    "HexViewer"           -> bind(HexViewer(Vector.tabulate(100)(_.toByte)), HexViewerState()),
    "HexViewer empty"     -> bind(HexViewer(Vector.empty[Byte]), HexViewerState()),
    "Inspect"             -> (() => Inspect(PrettyValue.Atom("漢字 value"))),
    "Inspect state"       -> bind(Inspect(PrettyValue.Atom("漢字 value")), InspectState()),
    "LazyListView"        -> bind(LazyListView(1000, 2, i => Some(paragraph(s"item $i 漢字"))), LazyListState(Some(5))),
    "MenuBar"             -> bind(
      MenuBar(Seq(MenuBarMenu("File", Seq(MenuEntry.Item("Open"))), MenuBarMenu("編集", Seq(MenuEntry.Item("Copy"))))),
      MenuBarState(Some(0)),
    ),
    "MenuBar dropdown"    -> bind(
      MenuBar(Seq(MenuBarMenu("File", Seq(MenuEntry.Item("Open 漢字"), MenuEntry.Separator)))).dropdown(0),
      MenuState(),
    ),
    "MultiProgress"       -> (() => MultiProgress(Seq(ProgressTask("下载 files", Some(10L)), ProgressTask("idle")))),
    "PixelImage contain"  -> (() => PixelImage(image)),
    "PixelImage stretch"  -> (() => PixelImage(image, PixelImageOptions(fit = ImageFit.Stretch))),
    "Placeholder"         -> (() => Placeholder("Sidebar 漢字")),
    "QrCode"              -> (() => QrCode("https://example.com")),
    "QrCode too long"     -> (() => QrCode("x" * 400, QrEcc.High)),
    "RangeSlider"         -> bind(RangeSlider(), RangeSliderState(SliderRange.of(0, 100), 20, 70)),
    "Searchable"          -> bind(Searchable(paragraph("foo bar foo 漢字")), SearchState()),
    "TagInput"            -> bind(TagInput(), TagInputState(Seq("scala", "漢字tag", "native"))),
    "TimePicker"          -> bind(TimePicker(), TimePickerState(LocalTime.of(9, 30, 15))),
    "TimePicker hidden field" -> bind(
      TimePicker(),
      { val state = TimePickerState(LocalTime.NOON, withSeconds = false); state.field = TimeField.Second; state },
    ),
    "Toolbar"                 -> (() =>
      Toolbar(
        Seq(Button("one"), Button("two"), Button("three 漢"), Button("four")),
        focus = Some(3),
        divider = Some("│"),
      )
    ),
    "TreeTable"               -> bind(
      TreeTable[Seq[Int]](nodes, Seq("Name", "Kind"), _ => Seq("dir"), Seq(Constraint.Fill(1), Constraint.Length(4))),
      TreeState(),
    ),
    "Window"                  -> bind(Window("Notes 漢字"), WindowStack().window(0, 0, 12, 4)),
    "Wizard"                  -> (() => Wizard(Seq("Account", "Profile 漢字", "Confirm"), 1)),
    "Markdown"        -> (() => MarkdownStream("# T\n\n| a | 漢字 |\n|---|---:|\n| 1 | 2 |\n\n- long item\n").markdown()),
    "Image"           -> (() => Image(Vector(Vector(Color.Rgb(1, 2, 3), Color.Rgb(4, 5, 6))))),
    "ListView prefix" -> bind(ListView(Seq("漢字", "next"), itemPrefix = _ => "[x] "), ListState(Some(1))),
  )

  test("all widgets preserve neighbors in empty, tiny, clipped, huge and offset rectangles"):
    for (name, build) <- widgets; area <- areas do
      withClue(s"$name at $area: ") {
        val buffer = Buffer(field)
        field.foreachPosition((x, y) => buffer.set(x, y, sentinel))
        build().render(area, buffer)
        field.foreachPosition { (x, y) =>
          if !area.contains(x, y) then assert(buffer.get(x, y) == sentinel, s"write at ($x, $y)")
        }
      }
    info(s"clip invariant: ${widgets.size} widgets x ${areas.size} rectangles")
