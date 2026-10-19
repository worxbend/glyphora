package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, KeyCode, MouseEvent, MouseEventKind, Position, Rect, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.Clipboard

import org.scalatest.funsuite.AnyFunSuite

/** Mouse text selection: drag, double- and triple-click, the highlight overlay, and copy through the app clipboard. */
final class TextSelectionSpec extends AnyFunSuite with PilotFixture:

  /** A title on row 0 and `body` on row 1, with mouse capture on — the configuration selection exists for. */
  private final class SelectApp(body: => Element) extends TuiApp:
    override def config: RunnerConfig             = RunnerConfig(mouse = MouseCaptureMode.Buttons)
    def view(using ReactiveScope, Theme): Element = column(text("title").length(1), body.length(1), text("").fill)

  private def driving(app: TuiApp, size: Size = Size(16, 3))(body: (Pilot, HeadlessBackend) => Unit): Unit =
    val backend = HeadlessBackend(size)
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    body(pilot, backend)
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  private def reversed(pilot: Pilot, x: Int, y: Int): Boolean =
    pilot.cellAt(x, y).style.modifiers.hasAny(Modifiers.Reverse)

  test("a drag from (2,1) to (6,1) over a text highlights the cells and the release copies \"llo w\""):
    driving(SelectApp(text("hello world"))) { (pilot, backend) =>
      pilot.drag(Position(2, 1), Position(6, 1)).waitForIdle()
      pilot.waitUntil("the release copied")(backend.clipboardContents.isDefined)
      assert(backend.clipboardContents.contains("llo w"))
      assert((2 to 6).forall(reversed(pilot, _, 1)))
      assert(!reversed(pilot, 1, 1) && !reversed(pilot, 7, 1))
      pilot.assertGoldenStyled("text-selection-drag")
    }

  test("dataTable: a press selects the row; a drag past the threshold selects cell text instead"):
    val table = DataTable.fromStrings(
      columns = Seq("name", "size"),
      rows = Seq(Seq("alpha", "100"), Seq("bravo", "20"), Seq("charlie", "3")),
      widths = Seq(Constraint.Length(8), Constraint.Length(6)),
      // the selected row underlined rather than reversed, so the golden tells it apart from the text selection
      options = DataTableOptions(highlightStyle = Style.Default.underline),
    )
    val state = DataTableState()
    val app   = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(mouse = MouseCaptureMode.Buttons)
      def view(using ReactiveScope, Theme): Element = dataTable(table, state)
    driving(app, Size(20, 5)) { (pilot, backend) =>
      pilot.click(3, 2).waitForIdle()                          // the second body row
      assert(state.selected.contains(1))
      assert(backend.clipboardContents.isEmpty)
      pilot.drag(Position(2, 3), Position(3, 3)).waitForIdle() // one cell: under the threshold, only a press
      assert(state.selected.contains(2))
      assert(backend.clipboardContents.isEmpty)
      pilot.drag(Position(0, 1), Position(3, 1)).waitForIdle() // four cells along the first row
      pilot.waitUntil("the release copied")(backend.clipboardContents.isDefined)
      assert(backend.clipboardContents.contains("alph"))
      assert(state.selected.contains(0)) // the press that began the drag still selected its row
      pilot.assertGoldenStyled("data-table-drag-select")
    }

  test("a drag backwards selects the same cells as the drag forwards"):
    driving(SelectApp(text("hello world"))) { (pilot, backend) =>
      pilot.drag(Position(6, 1), Position(2, 1)).waitForIdle()
      pilot.waitUntil("the release copied")(backend.clipboardContents.isDefined)
      assert(backend.clipboardContents.contains("llo w"))
    }

  test("a double click selects the word under the pointer, per BreakIterator"):
    driving(SelectApp(text("hello world"))) { (pilot, backend) =>
      pilot.click(8, 1).waitForIdle()
      assert(backend.clipboardContents.isEmpty, "a plain click selects nothing")
      pilot.click(8, 1).waitForIdle()
      pilot.waitUntil("the word was copied")(backend.clipboardContents.isDefined)
      assert(backend.clipboardContents.contains("world"))
      assert((6 to 10).forall(reversed(pilot, _, 1)))
      assert(!reversed(pilot, 5, 1))
    }

  test("a triple click selects the whole line"):
    driving(SelectApp(text("hello world"))) { (pilot, backend) =>
      pilot.click(1, 1).waitForIdle().click(1, 1).waitForIdle().click(1, 1).waitForIdle()
      pilot.waitUntil("the line was copied")(backend.clipboardContents.contains("hello world"))
    }

  test("selection copies whole graphemes: a wide character is one cell pair, never half of one"):
    driving(SelectApp(text("日本語 ok"))) { (pilot, backend) =>
      // columns 0-1 日, 2-3 本, 4-5 語; a drag from inside 本 to the first half of 語 takes both
      pilot.drag(Position(3, 1), Position(4, 1)).waitForIdle()
      pilot.waitUntil("copied")(backend.clipboardContents.isDefined)
      assert(backend.clipboardContents.contains("本語"))
    }

  test(".selectable(false) opts an element out: a drag over it copies nothing and highlights nothing"):
    driving(SelectApp(text("hello world").selectable(false))) { (pilot, backend) =>
      pilot.drag(Position(2, 1), Position(6, 1)).waitForIdle()
      assert(backend.clipboardContents.isEmpty)
      assert(!(0 until 11).exists(reversed(pilot, _, 1)))
    }

  test("without mouse capture the terminal selects natively and the app draws no selection"):
    val app = new TuiApp:
      def view(using ReactiveScope, Theme): Element = column(text("hello world").length(1))
    driving(app) { (pilot, backend) =>
      pilot.drag(Position(2, 0), Position(6, 0)).waitForIdle()
      assert(backend.clipboardContents.isEmpty)
      assert(!(0 until 11).exists(reversed(pilot, _, 0)))
    }

  test("a press elsewhere clears the selection"):
    driving(SelectApp(text("hello world"))) { (pilot, _) =>
      pilot.drag(Position(2, 1), Position(6, 1)).waitForIdle()
      pilot.waitUntil("highlighted")(reversed(pilot, 2, 1))
      pilot.click(14, 2).waitForIdle()
      assert(!(0 until 11).exists(reversed(pilot, _, 1)))
    }

  test("Ctrl+Shift+C copies the current selection again"):
    driving(SelectApp(text("hello world"))) { (pilot, backend) =>
      pilot.drag(Position(0, 1), Position(4, 1)).waitForIdle()
      pilot.waitUntil("copied")(backend.clipboardContents.contains("hello"))
      pilot.drag(Position(14, 2), Position(14, 2)).waitForIdle() // empty area: no selection, clipboard untouched
      pilot.drag(Position(6, 1), Position(10, 1)).waitForIdle()
      pilot.waitUntil("copied")(backend.clipboardContents.contains("world"))
      pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl | KeyModifiers.Shift).waitForIdle()
      assert(backend.clipboardContents.contains("world"))
    }

  test("an embedding host selects across rows and hands the text to the clipboard it is given"):
    given ReactiveScope = ReactiveScope.untracked
    val host            = ElementHost()
    host.textSelection = true
    val buffer          = Buffer(Rect(0, 0, 8, 3))
    val view: View      = column(text("one two").length(1), text("three").length(1), text("four").length(1))
    def frame(): Unit   = host.render(buffer.area, buffer, Theme.Dark, view)
    frame()
    val _               = host.dispatchMouse(MouseEvent(Position(4, 0), MouseEventKind.Down, KeyModifiers.None))
    frame()
    val _               = host.dispatchMouse(MouseEvent(Position(2, 1), MouseEventKind.Drag, KeyModifiers.None))
    frame()
    // three separate `text` nodes: a selection stays inside the node it started in
    assert(host.selectedText.contains("two"))
    val clipboard       = Clipboard.inMemory()
    assert(AppClipboard.during(clipboard)(host.copySelection()))
    assert(clipboard.paste().contains("two"))

  test("a selection inside one multi-line node streams across its rows"):
    given ReactiveScope = ReactiveScope.untracked
    val host            = ElementHost()
    host.textSelection = true
    val buffer          = Buffer(Rect(0, 0, 8, 3))
    val view: View      = text("one two\nthree\nfour")
    def frame(): Unit   = host.render(buffer.area, buffer, Theme.Dark, view)
    frame()
    val _               = host.dispatchMouse(MouseEvent(Position(4, 0), MouseEventKind.Down, KeyModifiers.None))
    frame()
    val _               = host.dispatchMouse(MouseEvent(Position(1, 2), MouseEventKind.Drag, KeyModifiers.None))
    frame()
    assert(host.selectedText.contains("two\nthree\nfo"))
