package io.worxbend.tui.examples.procmon

import io.worxbend.tui.dsl.*

import java.util.Locale
import scala.concurrent.duration.DurationInt

/** procmon: a `top`-like live process monitor — a sortable, filterable `DataTable` whose rows are thrown away and
  * rebuilt every couple of seconds while the user is still looking at them.
  *
  * The five things it exists to show:
  *   - a `DataTable` driven entirely from the app's own key bindings — the widget ships no sort or filter keys;
  *   - a refresh on a timer that does its blocking work off the render thread and lands the result back on it;
  *   - derived statistics as `Computed` fields on the app rather than arithmetic inside `view`;
  *   - source-aware table caching, so immutable row refreshes automatically update filtering and ordering;
  *   - a selection anchored to a *process* by row key, so a row that moves under a re-sort takes the highlight with it
  *     — the pid is the `KeyedRow` key, never parsed back out of a formatted cell.
  *
  * It runs on any machine: [[ProcessSource.detect]] uses live `ps` output when it can and a synthetic list when it
  * cannot, and the tests always use the synthetic one.
  *
  * Keys: `↑`/`↓` select (mouse wheel too) · `/` filter, then `Enter` to keep it or `Esc` to clear it ·
  * `p`/`u`/`c`/`m`/`n` sort by PID/USER/CPU/MEM/COMMAND, again to reverse · `r` refresh now · `+`/`-` slower/faster
  * refresh · `ctrl+p` command palette · `q` quit.
  */
class ProcmonApp(val source: ProcessSource = ProcessSource.detect()) extends TuiApp:

  import ProcmonApp.*

  override def config: RunnerConfig = RunnerConfig(tickRate = Some(TickRate), mouseCapture = true)

  // ---- state that must outlive `view` ----

  /** The latest sample. A `Signal`, so a refresh redraws without anyone asking. */
  val processes: Signal[Vector[ProcessInfo]] = Signal(Vector.empty)

  /** How many samples have completed — the app's own liveness counter, and what the tick test polls on. */
  val sampleCount: Signal[Int] = Signal(0)

  val refreshSeconds: Signal[Int] = Signal(2)

  /** Sort column and direction, filter text, selection and scroll offset. The widget half is rebuilt every frame; this
    * half is created once and never replaced, or every refresh would reset the user's view.
    *
    * The state is typed on the row key — the pid — because the widget records the selected row's key on every frame and
    * re-anchors the highlight to it after a re-sort or a data refresh; that anchor is what lets everything below speak
    * in pids rather than row numbers.
    */
  val tableState: DataTableState[Int] = DataTableState()

  val filterInput: TextInputState = TextInputState()

  private val filterOpen: Signal[Boolean] = Signal(false)

  private var ticksUntilRefresh: Int = 0

  /** Row count as of the last rendered frame, written once per frame in `view`: the steering keys read this instead of
    * rebuilding the table just to count its visible rows. The count is one frame stale at worst, and a steering key
    * only arrives after that frame rendered.
    */
  private var visibleRowCount: Int = 0

  // start where `top` starts, on the busiest process. `sortBy` always begins ascending, so the sort is set directly —
  // `sort` is public, and a `ColumnSort` is the whole of `DataTableState`'s sort API.
  tableState.sort = Some(ColumnSort(CpuColumn, SortDirection.Descending))

  /** Header statistics, derived once per change rather than recomputed per frame.
    *
    * A `Computed` built inside `view` would re-subscribe on every redraw and never be released, which is what
    * `Computed.dispose` exists to clean up after. Long-lived derived values belong here, as fields.
    */
  private val summary: Computed[Summary] = Computed {
    val all = processes.get
    Summary(all.size, all.map(_.cpuPercent).sum, all.map(_.memPercent).sum)
  }

  // ---- refreshing ----

  /** Ticks are the app's clock; refreshes are a multiple of them.
    *
    * Counting ticks rather than starting an `Async.every` poller keeps everything on one timer: there is no
    * `Cancelable` to leak when the app quits, `+`/`-` change the interval by changing a number, and `r` reaches exactly
    * the same code path — which is what makes the refresh testable without waiting on wall-clock time.
    */
  override def onTick(): Unit =
    if ticksUntilRefresh <= 0 then
      ticksUntilRefresh = refreshSeconds.peek * TicksPerSecond
      refresh()
    ticksUntilRefresh -= 1

  private def refresh(): Unit =
    // `sample()` shells out to `ps`, which costs tens of milliseconds — far too long to spend on the render thread,
    // because that is also the thread drawing frames. `Async.runCatching` runs it on a worker and delivers the result
    // back on the render thread, which is the only place a `Signal` may be written.
    Async.runCatching(source.sample()) {
      case Right(sampled) =>
        processes.set(sampled.toVector)
        sampleCount.update(_ + 1)
      // The new immutable row source automatically refreshes the table cache, even at the same row count.
      case Left(error)    =>
        notify(s"sample failed: ${error.getMessage}", NoticeLevel.Warning)
    }

  // ---- keys ----

  /** One declaration per key drives three things: dispatch, the status-bar hints, and the `ctrl+p` palette. */
  override def bindings: KeyBindings = KeyBindings(
    binding("r", "refresh now")(refresh()),
    binding("/", "filter rows")(filterOpen.set(true)),
    binding("p", "sort by pid")(sortBy(PidColumn)),
    binding("u", "sort by user")(sortBy(UserColumn)),
    binding("c", "sort by cpu")(sortBy(CpuColumn)),
    binding("m", "sort by memory")(sortBy(MemColumn)),
    binding("n", "sort by command")(sortBy(CommandColumn)),
    binding("+", "slower refresh")(refreshSeconds.update(seconds => math.min(MaxRefreshSeconds, seconds + 1))),
    binding("-", "faster refresh")(refreshSeconds.update(seconds => math.max(1, seconds - 1))),
    binding("q", "quit")(quit()),
  )

  private def sortBy(column: Int): Unit =
    // read the anchor *before* the sort changes what the view's indices mean
    val pinned = selectedProcessId
    tableState.sortBy(column) // the same column twice flips the direction — that rule lives in the widget state
    // the next frame re-anchors on its own; reselecting here covers a sort that lands before any frame does
    pinned.foreach(reselect)

  /** Enter: hide the filter box and keep filtering. The text stays in `filterInput`, so `/` reopens the box with the
    * same substring still in it and the table never flickers back to the full list.
    */
  private def applyFilter(): Unit =
    filterOpen.set(false)

  /** Escape: throw the filter away and show every process again.
    *
    * `setFilter("")` also drops the widget's selection and scroll offset, so the pid is selected again right after it —
    * the highlight follows its *process* back into the unfiltered list rather than landing on whatever row happens to
    * sit at the old index.
    */
  private def cancelFilter(): Unit =
    filterInput.clear()
    val pinned = selectedProcessId
    tableState.setFilter("")
    pinned.foreach(reselect)
    filterOpen.set(false)

  // ---- selection, anchored to a process by the row's key ----

  /** The rows the table is showing right now, in sort order — the domain `tableState.selected` indexes into. */
  private def visibleRows: Seq[KeyedRow[Int]] = buildTable(processes.peek).visibleRows(tableState)

  /** Puts the selection back on `pid` after something dropped it or moved every row — a filter edit, a re-sort. A no-op
    * when the process is not in the current view: it may have left the data or the filter, and `selectKey` says so by
    * answering `false`.
    */
  private def reselect(pid: Int): Unit =
    val _ = buildTable(processes.peek).selectKey(tableState, pid)

  private def moveSelection(delta: Int): Unit =
    if delta < 0 then tableState.selectPrevious(visibleRowCount) else tableState.selectNext(visibleRowCount)

  /** One steering step that also claims the event: keys and the wheel all dispatch through here. */
  private def steer(delta: Int): Boolean =
    moveSelection(delta)
    true

  /** The pid under the highlight, for tests and for anything that would act on the selection (a `kill` key). The row's
    * key *is* the pid, so this reads the widget's own selection anchor rather than parsing a formatted cell.
    */
  def selectedProcessId: Option[Int] = buildTable(processes.peek).selectedKey(tableState)

  /** The pids the table is showing, in sort order — the projection the tests assert against, and the same view
    * `tableState.selected` indexes into.
    */
  def visibleProcessIds: Seq[Int] = visibleRows.map(_.key)

  // ---- view ----

  def view(using ReactiveScope, Theme): Element =
    val table   = buildTable(processes.get)
    syncFilter()
    val visible = table.visibleRows(tableState)
    visibleRowCount = visible.size
    column(
      (Seq(summaryPanel(visible.size)) ++ filterRow ++ Seq(tableElement(table), statusBar(Hints)))*
    ).onKeyEvent(handleUnclaimedKey)

  /** Pushes the input's text into the table state, but only when it actually changed: `setFilter` also clears the
    * selection and the scroll offset, and doing that on every frame would make the table impossible to use. The pid is
    * selected again right after, so the highlight keeps following its process through each keystroke.
    */
  private def syncFilter(): Unit =
    if tableState.filter != filterInput.value then
      val pinned = selectedProcessId
      tableState.setFilter(filterInput.value)
      pinned.foreach(reselect)

  private def summaryPanel(shown: Int)(using ReactiveScope, Theme): Element =
    val stats = summary.get
    panel("procmon")(
      text(
        s"${stats.count} processes · $shown shown · sample ${sampleCount.get} · " +
          s"every ${refreshSeconds.get}s · source ${source.name}"
      ).dim,
      // these are sums over the process list, so on a many-core machine the CPU figure can exceed 100 and the bar
      // simply saturates — `progressBar` clamps its ratio, and the exact number is in the label either way
      progressBar(stats.cpuPercent / 100.0).label(s"CPU ${decimal(stats.cpuPercent, 5)}%").ramp(ColorRamp.Traffic),
      progressBar(stats.memPercent / 100.0).label(s"MEM ${decimal(stats.memPercent, 5)}%").ramp(ColorRamp.Traffic),
    ).rounded.length(5)

  /** The filter box, present only while filtering — and deliberately *above* the table.
    *
    * Focus is positional unless an element carries `.key(...)`: nothing here does, so when this row appears it becomes
    * focusable zero and takes the focus the table had, and when it disappears the focus falls back to the table. Naming
    * them would pin focus to the table instead, and `/` would open a filter box nobody was typing into.
    */
  private def filterRow(using ReactiveScope): Seq[Element] =
    if !filterOpen.get then Seq.empty
    else
      Seq(
        row(
          text(" filter ").dim.length(8),
          input(filterInput, placeholder = "substring — matched against every column").onKeyEvent {
            case KeyEvent(KeyCode.Enter, _)  =>
              applyFilter()
              true
            case KeyEvent(KeyCode.Escape, _) =>
              cancelFilter()
              true
            case _                           => false
          }.fill,
        ).length(1)
      )

  private def tableElement(table: DataTable[Int]): Element =
    dataTable(table, tableState).onMouseEvent { event =>
      // `DataTableElement` has no built-in wheel behavior of its own, unlike `list`; Up/Down it handles itself,
      // and the render re-anchors the highlight to the row's pid either way
      event.kind match
        case MouseEventKind.ScrollDown => steer(1)
        case MouseEventKind.ScrollUp   => steer(-1)
        case _                         => false
    }.fill

  /** Up/Down while the filter box holds focus. The table consumes them first whenever the table is focused, so this
    * only ever fires for the other case — the reader can keep steering the list while still typing.
    */
  private def handleUnclaimedKey(event: KeyEvent): Boolean =
    event.code match
      case KeyCode.Down => steer(1)
      case KeyCode.Up   => steer(-1)
      case _            => false

  // ---- rows ----

  /** A fresh `DataTable` per call: it is an immutable case class over the rows, and cheap. Only `tableState` persists.
    *
    * Each row is keyed by its pid — the identity the selection follows across re-sorts and refreshes. `cells` stays the
    * projection the widget sorts, filters and draws; the record is never parsed back out of its own formatting.
    */
  private def buildTable(rows: Seq[ProcessInfo]): DataTable[Int] =
    DataTable(
      columns = Seq("   PID", "USER", " CPU%", " MEM%", "COMMAND"),
      rows = rows.map(process => KeyedRow(process.pid, cellsOf(process))),
      widths = Seq(
        Constraint.Length(PidWidth + 2),
        Constraint.Length(UserWidth),
        Constraint.Length(NumberWidth + 2),
        Constraint.Length(NumberWidth + 2),
        Constraint.Fill(1),
      ),
    )

  /** Numbers are right-aligned to a fixed width rather than through `DataTableOptions.alignments`, because alignment
    * would buy only the look. The padding also makes the column sort correctly whichever branch the widget takes: it
    * compares cells numerically when every one of them parses as a number, and lexicographically otherwise — and for
    * non-negative numbers padded to one common width and one decimal place, those two orderings agree. Add a unit
    * suffix here and that stops being true, which is why the units live in the header.
    */
  private def cellsOf(process: ProcessInfo): Seq[String] =
    Seq(
      integer(process.pid, PidWidth),
      process.user,
      decimal(process.cpuPercent, NumberWidth),
      decimal(process.memPercent, NumberWidth),
      process.command,
    )

object ProcmonApp:

  private final case class Summary(count: Int, cpuPercent: Double, memPercent: Double)

  private val PidColumn     = 0
  private val UserColumn    = 1
  private val CpuColumn     = 2
  private val MemColumn     = 3
  private val CommandColumn = 4

  private val PidWidth    = 6
  private val NumberWidth = 5
  // Must stay >= the longest synthetic user name (`www-data`, `postgres`) or the column truncates it.
  private val UserWidth   = 10

  private val MaxRefreshSeconds = 10

  /** Fast enough that a keystroke never waits on the clock, and a whole number of ticks to the second. */
  private val TickRate       = 250.millis
  private val TicksPerSecond = (1.second / TickRate).toInt

  /** A curated subset: every binding is reachable through `ctrl+p`, but only the ones worth a permanent reminder belong
    * on a one-row status bar.
    */
  private val Hints = Seq(
    "↑/↓"       -> "select",
    "/"         -> "filter",
    "p/u/c/m/n" -> "sort",
    "r"         -> "refresh",
    "q"         -> "quit",
  )

  // Locale.ROOT rather than the ambient locale: `%.1f` writes "12,5" in much of Europe, which would both look wrong
  // and stop the column sorting as a number.
  private def decimal(value: Double, width: Int): String = String.format(Locale.ROOT, s"%$width.1f", value)

  private def integer(value: Int, width: Int): String = String.format(Locale.ROOT, s"%${width}d", value)

object Main extends ProcmonApp()
