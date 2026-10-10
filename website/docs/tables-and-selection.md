---
title: Tables & selection
description: Refresh, sort, filter, page, and select rows in glyphora tables without losing ordering, scroll position, or the highlighted item.
---

# Drive a table over data that keeps changing

A table over static rows needs nothing from this page. A table whose rows are
replaced wholesale every couple of seconds — a process list, a job queue, a
metrics sample — needs all of it, because the ordering, the filter, the scroll
position and the highlighted row survive a refresh only if you make them.

Every snippet here comes from `examples/procmon`, a `top`-style monitor whose
`DataTable` is thrown away and rebuilt on a timer while the reader is still
looking at it. [Build a process monitor](./build-a-process-monitor) assembles
them into a running application; this page is the reference for each piece on
its own. Unless a snippet shows additional imports, assume:

```scala
import io.worxbend.tui.dsl.*
```

## Choose Table or DataTable

| Widget | Owns | Reach for it when |
|---|---|---|
| `table` / `TableElement` | column widths and an optional header, nothing else | the rows are short, static and read-only — a percentile summary, a key list |
| `dataTable` / `DataTableElement` | sort, filter, selection, scroll and paging, all in a caller-owned `DataTableState` | the reader has to find a row, and the rows outlive one frame |
| `styledTable` / `StyledTableElement` | the same as `table`, but every cell is a `Line`, so it can carry its own colour, and a row can be taller than one line | one cell has to look different from its neighbours — a red `failed`, a dim timestamp |

`table` renders and stops there: no state, no focus, no keys. That is the point —
a six-row latency summary that could be highlighted but never acted on invites a
click that does nothing. The `table(rows, widths*)` factory omits the header, so
add one with `.header(...)`, and a bottom-pinned summary row with `.footer(...)`.

## Colour one cell without colouring the row

`table` takes `String`s, and a `String` has no style of its own: the whole table
gets one style and every cell in it looks the same. `styledTable` takes cells as
`Line`s instead, and a `Line` carries its own spans and styles, so a status
column can be green on the rows that passed and red on the one that did not:

```scala
def view(using ReactiveScope, theme: Theme): Element =
  styledTable(
    Seq(
      Seq(Line.raw("api"), Line.styled("ready", theme.success)),
      Seq(Line.raw("cache"), Line.styled("failed", theme.error)),
    ),
    Constraint.Fill(1),
    Constraint.Length(8),
  ).header(Line.raw("service"), Line.raw("state"))
    .footer(Line.raw("total"), Line.raw("2"))
```

Two further shapes are accepted wherever a cell or a row is:

- a `TableCell(line, columnSpan)` covers several columns at once — a grouped
  caption over a pair of data columns, or a full-width note inside the body;
- a `TableRow(cells, height, topMargin, bottomMargin, style)` gives one row extra
  vertical room, a blank line above or below it, or a style of its own.

Both may be mixed with plain `Line`s and plain cell sequences in the same call,
so nothing pays for a feature it does not use. Everything else — the varargs
widths, the equal-column fallback when you pass none, rendering only the visible
rows — behaves exactly as it does for `table`.

## Rebuild the table, keep the state

`DataTable[K]` is an immutable case class over the rows — `K` is the key type
naming a row's record, the process pid here. `DataTableState[K]` is the
mutable half holding everything the reader has done to them. Build the first per
frame; create the second once:

```scala
val tableState: DataTableState[Int] = DataTableState()

private def buildTable(rows: Seq[ProcessInfo]): DataTable[Int] =
  DataTable(
    columns = Seq("   PID", "USER", " CPU%", " MEM%", "COMMAND"),
    rows = rows.map(process => KeyedRow(process.pid, cellsOf(process))),
    widths = Seq(
      Constraint.Length(8),
      Constraint.Length(10),
      Constraint.Length(7),
      Constraint.Length(7),
      Constraint.Fill(1),
    ),
  )

def view(using ReactiveScope, Theme): Element =
  dataTable(buildTable(processes.get), tableState).fill
```

Each row is a `KeyedRow(key, cells)`: the cells are what the table sorts,
filters and draws, and the key is the row's stable identity, which the selection
pins itself to — see [Pin the selection to an
identity](#pin-the-selection-to-an-identity). Rows with no identity of their own
build through `DataTable.fromStrings(columns, rows, widths)`, which keys each by
its original position; their state keeps the plain `DataTableState()` spelling,
because that key type is `Int`.

A fresh `DataTable` per frame is cheap: it holds the rows and nothing derived
from them. `DataTableState` is the half that must not be rebuilt — constructing
it inside `view` resets the sort column, filter, selection and scroll offset on
every redraw, so the table sits at row zero for ever. That is the general rule
applied to one widget: [The state ownership
rule](./widgets#the-state-ownership-rule).

One thing `dataTable` cannot do for you is theme the selected row. `list`, `tree`,
`menu`, `selectionList`, `filePicker` and `directoryTree` all draw their highlight in
the app theme's `focus` style, but `DataTable` is a widget value *you* built, so its
`highlightStyle` is yours to set — `dataTable` overriding it would throw away a
deliberate choice. Pass it explicitly to line the table up with everything else:

```scala
private def buildTable(rows: Seq[ProcessInfo])(using theme: Theme): DataTable[Int] =
  DataTable(..., options = DataTableOptions(highlightStyle = theme.focus))
```

## Refresh immutable row snapshots

`DataTable` memoises its filtered, sorted view on the state using the immutable
row collection's identity together with sort and filter settings. Replacing the
collection refreshes the view even when its row count is unchanged:

```scala
private def refresh(): Unit =
  Async.runCatching(source.sample()) {
    case Right(sampled) =>
      processes.set(sampled.toVector)
    case Left(error) =>
      notify(s"sample failed: ${error.getMessage}", NoticeLevel.Warning)
  }
```

Retain the same row snapshot while its data is unchanged to reuse the cached view.
Rebuilding an equal collection is safe but recomputes sorting/filtering. Explicit
`invalidate()` remains available when an application deliberately needs to drop the
cache; it is no longer part of the ordinary refresh protocol.
Selection needs no saving: the render re-anchors it to the recorded row key on
the next frame. The refresh itself — timers,
cancellation, stale responses — belongs to [Live
data & background work](./live-data).

## Sort numbers that carry units

`DataTable` picks one ordering for a whole column: numeric when every cell in it
parses as a non-`NaN` `Double`, case-insensitive text otherwise. So a column of
`"900M"` and `"1.2G"` has no numeric reading at all, and `"1.2G"` sorts above
`"900M"`. Emit bare numbers and put the unit in the header:

```scala
private def cellsOf(process: ProcessInfo): Seq[String] =
  Seq(
    integer(process.pid, PidWidth),
    process.user,
    decimal(process.cpuPercent, NumberWidth),
    decimal(process.memPercent, NumberWidth),
    process.command,
  )
```

The alternative is to sort the domain objects yourself and hand the widget rows
that are already in order, leaving `tableState.sort = None`:

```scala
val ordered = processes.get.sortBy(-_.cpuPercent)
dataTable(buildTable(ordered), tableState).fill
```

That costs the header's `▲`/`▼` indicator, which is drawn from `sort` — you
own signalling the sort in the column title. What cannot work is a unit suffix
inside the cell: it silently drops the column back to text ordering, and text
ordering of `"9.5"` against `"11.0"` is wrong in a way nobody reports as a bug.

## Bind sort keys, and mind the case

`DataTable` ships no sort or filter keys. Up/Down move the selection while
focused, PageUp/PageDown turn the page once `paging` is set, and everything
else is yours to declare:

```scala
override def bindings: KeyBindings = KeyBindings(
  binding("p", "sort by pid")(sortBy(PidColumn)),
  binding("u", "sort by user")(sortBy(UserColumn)),
  binding("c", "sort by cpu")(sortBy(CpuColumn)),
  binding("m", "sort by memory")(sortBy(MemColumn)),
  binding("n", "sort by command")(sortBy(CommandColumn)),
)

private def sortBy(column: Int): Unit =
  tableState.sortBy(column)
```

`state.sortBy(column)` starts ascending and flips the direction when the same
column is sorted again, so one key per column covers both directions. A
single-character spec keeps the case you wrote, because that case is what the
terminal reports: `binding("C", ...)` fires on Shift+C and never on plain `c`.
See [Declare commands once](./app-shell#declare-commands-once) for the rest of
the spec grammar.

## Filter as the user types

The filter lives on the table state, the text lives in a `TextInputState`, and
one of them has to push into the other. Push only on change:

```scala
import io.worxbend.tui.widgets.TextInputState

val filterInput: TextInputState = TextInputState()

private def syncFilter(): Unit =
  if tableState.filter != filterInput.value then
    tableState.setFilter(filterInput.value)

def view(using ReactiveScope, Theme): Element =
  val table = buildTable(processes.get)
  syncFilter()
  column((filterRow :+ dataTable(table, tableState).fill)*)
```

`setFilter` keeps rows where *any* cell contains the text, case-insensitively —
and clears the selection and the scroll offset on the way past. That is why the
guard matters: calling it unconditionally would wipe the highlight and jump to
the top on every frame, including frames a timer drew while nobody was typing.
While the input holds focus it consumes keys first, so `n` types an `n` instead
of re-sorting by command.

## Pin the selection to an identity

`tableState.selected` is an index into `visibleRows`, and that sequence is
rebuilt by every sort, filter and refresh — index 3 names a different process
each time. A `KeyedRow` table needs no workaround for that, because the key *is*
the identity: every render records the selected row's key on the state, and the
next render re-anchors the selection to wherever that key landed after a re-sort
or a refresh. The highlight follows the record rather than the row number, and
the application code for that is nothing at all.

Reading the identity back is `selectedKey`; setting it is `selectKey`:

```scala
def selectedProcessId: Option[Int] =
  buildTable(processes.peek).selectedKey(tableState)

private def selectProcess(pid: Int): Unit =
  buildTable(processes.peek).selectKey(tableState, pid)

private def moveSelection(delta: Int): Unit =
  val rows = buildTable(processes.peek).visibleRows(tableState)
  if delta < 0 then tableState.selectPrevious(rows.size)
  else tableState.selectNext(rows.size)
```

`selectedKey` answers the recorded anchor when there is one, so a sort between
frames never leaves it pointing at a stale index. `selectKey` moves the selection
to the row carrying the key in the current view, or returns `false` — touching
nothing — when no visible row does: a record that left the data, or one sitting
on another page, since a selection indexes the windowed view.

An index move — `selectNext` here, the built-in Up/Down, a direct `selected = …`
assignment — drops the recorded key on purpose, and the next render records the
key of the row the highlight now sits on, so the anchor is always the record the
reader is looking at rather than a stale one. The one move that clears the
selection outright is `setFilter`: a new filter is a new view, and a highlight
carried across it would land on a row the reader never chose.

`DataTable.fromStrings` keys rows by their original position, which follows a row
across a re-sort but means nothing across a refresh that rebuilds the list. An
app with an identity of its own — a pid, a path, an id — should key on it, and
then never has to parse its own formatted cells to learn which record is
selected.

## Page without arithmetic bugs

Paging is opt-in: with `paging` unset the body scrolls, PageUp/PageDown are left
unconsumed and keep bubbling to your bindings. A page size and a page number are
one value, `Paging`, because neither means anything without the other — a page
number with no page size describes nothing the widget can draw.

```scala
import io.worxbend.tui.widgets.Paging

tableState.paging = Some(Paging(size = 20, page = 0))

tableState.nextPage(table.filteredRows(tableState).size)
tableState.previousPage()
```

Pass `filteredRows(...).size`, not the unfiltered row count: the last page is
bounded by the filtered domain, and the larger number lets the reader page off
the end into blank rows. Both calls clear the selection and the offset
deliberately, because a highlight inherited from the previous page reads as a
choice the reader did not make. And with a page size set, `selected` indexes the
*page* rather than the whole filtered set, so `selectKey` reaches only the
records on the current page. `Paging(size = area.height - 2, page = 0)` is the tempting idiom
and yields a size of zero on a two-row terminal; the widget floors the page at
one row rather than showing nothing on every page for ever.

## Scroll with the wheel

`dataTable` has no built-in wheel behavior, unlike `list` — a wheel over an
uncustomised table does nothing at all, which reads as a hung application. Add
one handler, and turn mouse reporting on with `RunnerConfig(mouseCapture = true)`:

```scala
dataTable(table, tableState)
  .onMouseEvent { event =>
    event.kind match
      case MouseEventKind.ScrollDown =>
        moveSelection(1)
        true
      case MouseEventKind.ScrollUp =>
        moveSelection(-1)
        true
      case _ => false
  }
  .fill
```

Moving the selection rather than the offset keeps the wheel and the arrow keys
doing the same thing, and the key anchor records itself on the next render for
both. See [Mouse & focus](./mouse#backend-support) for what a terminal has to
support before any of this arrives.

## Right-align a numeric column

`DataTable` aligns a column through `alignments` on its `DataTableOptions` — one
`Alignment` per column by position (see
[Tables: simple and interactive](./widgets#tables-simple-and-interactive)) — and
`Table` aligns per cell, because its cells are `Line`s. Padding a `DataTable`
column to a fixed width still earns its keep, because it also makes the column
*sort* correctly:

```scala
import java.util.Locale

private def decimal(value: Double, width: Int): String =
  String.format(Locale.ROOT, s"%$width.1f", value)

private def integer(value: Int, width: Int): String =
  String.format(Locale.ROOT, s"%${width}d", value)
```

`Locale.ROOT`, not the ambient locale: `%.1f` writes `12,5` across much of
Europe, which looks wrong and also stops the cell parsing as a `Double`, dropping
the whole column back to text ordering. The `f` interpolator formats in the
default locale, so prefer `String.format` for any cell a column will sort.
Padding costs nothing in sort order — for non-negative numbers at one common
width and one decimal place, the numeric and lexicographic orderings agree. Pad
the header strings to match (`"   PID"`, `" CPU%"`), or the label sits left of
its own column of figures.

## Keep focus stable when the tree changes

Focus is positional unless an element carries `.key(...)`, and a table usually
shares its screen with something conditional:

```scala
private def filterRow(using ReactiveScope): Seq[Element] =
  if !filterOpen.get then Seq.empty
  else Seq(row(text(" filter ").dim.length(8), input(filterInput).fill).length(1))
```

Nothing here is keyed, and that is a decision rather than an omission: when the
row appears it becomes focusable zero and takes the focus the table had, so `/`
opens a box the reader can immediately type into, and when it disappears the
focus falls back to the table. Keying both would pin focus to the table and `/`
would open a filter box nobody was typing into. Reach for `.key("process-table")`
the moment a *different* conditional element sits above the table — one whose
appearance should not steal the keyboard. See [Keep focus stable across changing
trees](./mouse#keep-focus-stable-across-changing-trees).

## Where to go next

- [Build a process monitor](./build-a-process-monitor) — every recipe here,
  assembled into a running application with a headless test suite;
- [Live data & background work](./live-data) — the refresh that feeds the table:
  timers, cancellation, and stale responses;
- [Charts, gauges & status](./charts-and-status) — the summary header that sits
  above a table like this one;
- [Widget catalog](./widgets#tables-simple-and-interactive) — `DataTableState`'s
  fields and the rest of the caller-owned state models;
- [Mouse & focus](./mouse) — focus order, hit-testing, and the built-in
  interactions you do not have to write.
