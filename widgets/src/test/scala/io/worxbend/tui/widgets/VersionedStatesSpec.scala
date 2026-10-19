package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Color, Line, Versioned}

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Paths
import java.time.{LocalDate, LocalTime}

/** Every caller-owned widget state is `Versioned`, and every public mutating method raises its version.
  *
  * Table-driven: each row is a state, how to build one, and the mutations it offers. A row is one method (or setter)
  * applied to a fresh state, so a method that forgot its `touch()` fails by name rather than hiding behind a neighbour
  * that happened to bump.
  */
final class VersionedStatesSpec extends AnyFunSuite:

  private final case class Mutation[S <: Versioned](name: String, apply: S => Unit)

  private final case class Row[S <: Versioned](state: String, fresh: () => S, mutations: Seq[Mutation[S]])

  private def row[S <: Versioned](state: String, fresh: => S)(mutations: (String, S => Unit)*): Row[S] =
    Row(state, () => fresh, mutations.map((name, apply) => Mutation(name, apply)))

  private val nodes = Seq(TreeNode("a", Seq(TreeNode("a1"), TreeNode("a2"))), TreeNode("b"))
  private val menu  = Seq(MenuEntry.Item("one"), MenuEntry.Item("two"), MenuEntry.Item("three"))

  private val rows: Seq[Row[? <: Versioned]] = Seq(
    row("ListState", ListState())(
      "selected ="           -> (_.selected = Some(1)),
      "offset ="             -> (_.offset = 2),
      "scrollPadding ="      -> (_.scrollPadding = 1),
      "selectNext"           -> (_.selectNext(3)),
      "selectPrevious"       -> (_.selectPrevious(3)),
      "selectFirst"          -> (_.selectFirst(3)),
      "selectLast"           -> (_.selectLast(3)),
      "selectBy"             -> (_.selectBy(3, 2)),
      "clearSelection"       -> (_.clearSelection()),
    ),
    row("MenuState", MenuState())(
      "selected ="           -> (_.selected = Some(1)),
      "offset ="             -> (_.offset = 1),
      "selectNext"           -> (_.selectNext(menu)),
      "selectPrevious"       -> (_.selectPrevious(menu)),
    ),
    row("DropdownState", DropdownState())(
      "open ="               -> (_.open = true),
      "openAt"               -> (_.openAt(1)),
      "close"                -> (_.close()),
      "selectNext"           -> (_.selectNext(Seq("x", "y"))),
      "selectPrevious"       -> (_.selectPrevious(Seq("x", "y"))),
      "highlightAt"          -> (state => { val _ = state.highlightAt(0, 2) }),
      "menu.selected ="      -> (_.menu.selected = Some(0)),
    ),
    row("TreeState", TreeState())(
      "selected ="           -> (_.selected = Some(Seq(0))),
      "offset ="             -> (_.offset = 1),
      "expanded +="          -> (state => { val _ = state.expanded += Seq(0) }),
      "selectNext"           -> (_.selectNext(nodes)),
      "selectPrevious"       -> (_.selectPrevious(nodes)),
      "toggle"               -> (state => { state.selected = Some(Seq(0)); state.toggle(nodes) }),
    ),
    row("ScrollViewState", ScrollViewState())(
      "offset ="             -> (_.offset = 3),
      "scrollBy"             -> (_.scrollBy(1)),
      "scrollTo"             -> (_.scrollTo(0)),
      "scrollUp"             -> (_.scrollUp()),
      "scrollDown"           -> (_.scrollDown()),
      "reveal"               -> (state => { state.lastContentHeight = 10; state.reveal(5, 1) }),
    ),
    row("DataTableState", DataTableState())(
      "sort ="               -> (_.sort = Some(ColumnSort(0, SortDirection.Ascending))),
      "offset ="             -> (_.offset = 1),
      "paging ="             -> (_.paging = Some(Paging(10, 0))),
      "selected ="           -> (_.selected = Some(0)),
      "selectedColumn ="     -> (_.selectedColumn = Some(0)),
      "setFilter"            -> (_.setFilter("x")),
      "sortBy"               -> (_.sortBy(0)),
      "invalidate"           -> (_.invalidate()),
      "selectNext"           -> (_.selectNext(3)),
      "selectPrevious"       -> (_.selectPrevious(3)),
      "selectNextColumn"     -> (_.selectNextColumn(3)),
      "selectPreviousColumn" -> (_.selectPreviousColumn(3)),
      "selectCell"           -> (_.selectCell(1, 1)),
      "selectFirst"          -> (_.selectFirst(3)),
      "selectLast"           -> (_.selectLast(3)),
      "selectBy"             -> (_.selectBy(3, 1)),
    ),
    row(
      "DataTableState (paged)", {
        val state = DataTableState()
        state.paging = Some(Paging(10, 1))
        state
      },
    )(
      "nextPage"             -> (_.nextPage(30)),
      "previousPage"         -> (_.previousPage()),
    ),
    row("LogState", LogState())(
      "append(String)"       -> (_.append("line")),
      "append(Line)"         -> (_.append(Line.raw("line"))),
      "clear"                -> (_.clear()),
      "follow ="             -> (_.follow = false),
      "offset ="             -> (_.offset = 1),
      "scrollUp"             -> (_.scrollUp()),
      "scrollDown"           -> (_.scrollDown()),
    ),
    row("TextInputState", TextInputState("hello"))(
      "insert"               -> (_.insert("!")),
      "backspace"            -> (_.backspace()),
      "delete"               -> (state => { state.moveHome(); state.delete() }),
      "undo"                 -> (state => { state.insert("x"); state.undo() }),
      "clear"                -> (_.clear()),
      "setValue"             -> (_.setValue("other")),
      "moveLeft"             -> (_.moveLeft()),
      "moveHome"             -> (_.moveHome()),
      "moveWordLeft"         -> (_.moveWordLeft()),
      "selectAll"            -> (_.selectAll()),
      "clearSelection"       -> (_.clearSelection()),
      "killToStartOfLine"    -> (_.killToStartOfLine()),
      "deleteWordBackward"   -> (_.deleteWordBackward()),
      "paste"                -> (state => {
        val clipboard = Clipboard.inMemory()
        clipboard.copy("pasted")
        state.paste(clipboard)
      }),
    ),
    row("TextAreaState", TextAreaState("one\ntwo"))(
      "insert"               -> (_.insert("!")),
      "newline"              -> (_.newline()),
      "moveUp"               -> (_.moveUp()),
      "moveTo"               -> (_.moveTo(TextPosition(0, 0))),
      "openSearch"           -> (_.openSearch()),
      "closeSearch"          -> (state => { state.openSearch(); state.closeSearch() }),
      "cancelSearch"         -> (state => { state.openSearch(); state.cancelSearch() }),
    ),
    row(
      "TextAreaState (searching)", {
        val state = TextAreaState("one\ntwo")
        state.openSearch()
        state
      },
    )("updateSearch"         -> (_.updateSearch("o"))),
    row("DatePickerState", DatePickerState(LocalDate.of(2026, 1, 15)))(
      "cursor ="             -> (_.cursor = LocalDate.of(2026, 2, 1)),
      "selected ="           -> (_.selected = Some(LocalDate.of(2026, 2, 1))),
      "moveDays"             -> (_.moveDays(1)),
      "moveMonths"           -> (_.moveMonths(1)),
      "goToToday"            -> (_.goToToday()),
      "select"               -> (_.select()),
    ),
    row("TimePickerState", TimePickerState(LocalTime.of(10, 30)))(
      "time ="               -> (_.time = LocalTime.of(11, 0)),
      "field ="              -> (_.field = TimeField.Minute),
      "nextField"            -> (_.nextField()),
      "previousField"        -> (state => { state.nextField(); state.previousField() }),
      "increment"            -> (_.increment()),
      "decrement"            -> (_.decrement()),
      "typeDigit"            -> (_.typeDigit(1)),
    ),
    row("RangeSliderState", RangeSliderState(SliderRange.of(0, 100), 10, 90))(
      "thumb ="              -> (_.thumb = RangeThumb.High),
      "step"                 -> (_.step(1)),
      "toMin"                -> (_.toMin()),
      "toMax"                -> (_.toMax()),
      "moveTo"               -> (_.moveTo(50L)),
      "focusNext"            -> (state => { val _ = state.focusNext() }),
      "focusPrevious"        -> (state => { val _ = state.focusNext(); val _ = state.focusPrevious() }),
    ),
    row("ColorPickerState", ColorPickerState())(
      "chosen ="             -> (_.chosen = Some(Color.Red)),
      "moveLeft"             -> (state => { state.moveRight(); state.moveLeft() }),
      "moveRight"            -> (_.moveRight()),
      "moveUp"               -> (state => { state.moveDown(); state.moveUp() }),
      "moveDown"             -> (_.moveDown()),
      "moveTo"               -> (_.moveTo(5)),
      "choose"               -> (state => { val _ = state.choose() }),
      "focusNextField"       -> (state => { val _ = state.focusNextField() }),
      "focusPreviousField"   -> (state => { val _ = state.focusNextField(); val _ = state.focusPreviousField() }),
      "focusField"           -> (_.focusField(ColorPickerField.Channel(ColorChannel.Blue))),
      "typeDigit"            -> (state => { state.focusNextField(); state.typeDigit(4) }),
      "deleteDigit"          -> (state => { state.focusNextField(); state.deleteDigit() }),
      "stepChannel"          -> (state => { state.focusNextField(); state.stepChannel(1) }),
      "chooseRgb"            -> (state => { val _ = state.chooseRgb() }),
    ),
    row("InspectState", InspectState())(
      "select"               -> (_.select(Vector(1))),
      "collapse"             -> (_.collapse(Vector())),
      "expand"               -> (_.expand(Vector())),
      "toggle"               -> (_.toggle(Vector(0))),
    ),
    row("HexViewerState", HexViewerState())(
      "selected ="           -> (_.selected = Some(1)),
      "offset ="             -> (_.offset = 16),
      "select"               -> (_.select(3, 64)),
      "moveBy"               -> (_.moveBy(1, 64)),
    ),
    row("MarkdownStream", MarkdownStream())(
      "append"               -> (_.append("# title")),
      "finish"               -> (_.finish()),
      "clear"                -> (_.clear()),
    ),
    row("DiffViewState", DiffViewState())(
      "offset ="             -> (_.offset = 2),
      "scrollUp"             -> (state => { state.offset = 3; state.scrollUp() }),
      "scrollDown"           -> (state => { state.recordFrame(10, 1); state.scrollDown() }),
      "pageUp"               -> (state => { state.offset = 3; state.pageUp() }),
      "pageDown"             -> (state => { state.recordFrame(10, 1); state.pageDown() }),
      "first"                -> (_.first()),
      "last"                 -> (_.last()),
    ),
    row("DirectoryTreeState", DirectoryTreeState(Paths.get(".")))(
      "selected ="           -> (_.selected = Some(Paths.get("."))),
      "offset ="             -> (_.offset = 1),
      "expanded +="          -> (state => { val _ = state.expanded += Paths.get(".") }),
      "invalidate"           -> (_.invalidate()),
    ),
    row("AccordionState", AccordionState())(
      "open ="               -> (_.open = Some(1)),
      "toggle"               -> (_.toggle(0)),
    ),
    row("WizardState", WizardState())(
      "next"                 -> (_.next(3)),
      "back"                 -> (_.back()),
      "fail"                 -> (_.fail("no")),
      "clearError"           -> (_.clearError()),
    ),
    row("TagInputState", TagInputState(Seq("a")))(
      "tags ="               -> (_.tags = Seq("b")),
      "commit"               -> (state => { state.input.setValue("c"); val _ = state.commit() }),
      "removeLast"           -> (_.removeLast()),
      "remove"               -> (_.remove(0)),
      "input.insert"         -> (_.input.insert("x")),
    ),
    row("MenuBarState", MenuBarState(Some(0)))(
      "openMenu"             -> (_.openMenu(1)),
      "close"                -> (_.close()),
      "move"                 -> (_.move(1, 3)),
      "menu.selected ="      -> (_.menu.selected = Some(0)),
    ),
    row("WindowState", WindowStack().window(0, 0, 10, 4))(
      "mode ="               -> (_.mode = WindowMode.Moving),
      "raise"                -> (_.raise()),
      "moveBy"               -> (_.moveBy(1, 1)),
      "resizeBy"             -> (_.resizeBy(1, 1)),
      "startDrag"            -> (_.startDrag(io.worxbend.tui.core.Position(0, 0), resizing = false)),
      "endDrag"              -> (_.endDrag()),
    ),
    row("ContextMenuState", ContextMenuState())(
      "openAt"               -> (_.openAt(io.worxbend.tui.core.Position(1, 1))),
      "close"                -> (_.close()),
      "menu.selected ="      -> (_.menu.selected = Some(0)),
    ),
  )

  private def register[S <: Versioned](entry: Row[S]): Unit =
    entry.mutations.foreach { mutation =>
      test(s"${entry.state}.${mutation.name} raises the version"):
        val subject = entry.fresh()
        val before  = subject.version
        mutation.apply(subject)
        assert(subject.version > before, s"${entry.state}.${mutation.name} left version at $before")
    }

  rows.foreach(entry => register(entry))

  test("a state that is only read keeps its version"):
    val list   = ListState(selected = Some(1))
    val before = list.version
    val _      = (list.selected, list.offset, list.scrollPadding)
    assert(list.version == before)
