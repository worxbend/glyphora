---
title: Build a process monitor
description: Build a top-style live process monitor in glyphora — a sortable table over refreshing data, derived summaries, and a selection that survives every refresh.
---

# Build a process monitor

You will build `procmon`: a `top`-like screen whose rows are thrown away and rebuilt
every couple of seconds while the user is still looking at them. Sorting, filtering
and the highlighted row all have to survive that.

Each step names a file, gives the complete code for it, gives the command to run,
and says what appears on screen. Every step compiles and runs, so you can stop
anywhere and still have a working program.

> **You need:** a clone of the glyphora repository and JDK 25 or newer. Run the app
> from a real terminal, not an IDE output panel, because raw input needs a
> controlling TTY.

> **The finished app already ships in the clone**, at `examples/procmon/`. Build yours
> beside it under a different name — `examples/myprocmon/`, with `build.examples.myprocmon`
> in its `package.mill` and the package renamed to match — or read the finished source
> as you go. Everything below is taken verbatim from it.

## 1. Create the module

```scala title="examples/procmon/package.mill"
package build.examples.procmon

import mill.*

// Everything an example has in common — the GraalVM pin, `--no-fallback`, and a Pilot-capable
// test submodule — lives in `TuiExampleModule` in build.mill.
object `package` extends build.TuiExampleModule {

  def moduleDeps = Seq(build.dsl)

  def mainClass = Some("io.worxbend.tui.examples.procmon.Main")
}
```

Examples depend on the module in this repository rather than on the published `tui-dsl`
artifact, so a change to `core` breaks them in the same build. One entry is enough:
Mill's `moduleDeps` are transitive, and `build.dsl` already depends on `build.core`,
`build.terminal`, `build.widgets`, `build.runtime` and `build.macros`. Listing those
again would not add anything to the build — it would only invite the list to fall out of
date with what the example actually imports. Outside this repository you would take the
single dependency from [Getting started](./getting-started#1-add-glyphora); everything
below is identical.

```scala title="examples/procmon/src/main/scala/io/worxbend/tui/examples/procmon/Main.scala"
package io.worxbend.tui.examples.procmon

import io.worxbend.tui.dsl.*

final class ProcmonApp extends TuiApp:

  override def bindings: KeyBindings = KeyBindings(
    binding("q", "quit")(quit()),
  )

  def view(using ReactiveScope, Theme): Element =
    panel("procmon")(text("no processes yet").dim).rounded

// `TuiApp` supplies `main`, so the launcher only needs an object that *is* the app.
// The class stays a class so tests can build a fresh instance per case.
object Main extends ProcmonApp()
```

```bash
./mill examples.procmon.run
```

An empty rounded frame fills the terminal and `q` exits:

```
╭procmon─────────────────────────────────╮
│no processes yet                        │
╰────────────────────────────────────────╯
```

One build rule to internalise now, because it will bite you: this repository compiles
with `-Wunused:all -Werror`, so an unused import or an unused private field is a
**build failure**, not a warning. That is why each constant below arrives in the step
that first uses it rather than all at once — paste them early and the build stops.

(`Pilot.using(backend)(app.runWith(backend))` in step 13 hands the runner's own
`Either[RunnerError, Unit]` to the pilot rather than discarding it, so a run that
failed is reported as a test failure instead of reading as a clean exit.)

## 2. Model a process, not a row

The table widget speaks `KeyedRow`s of `String` cells. Your application must not: once
a process is only its row of strings, its pid is a substring and its CPU figure is
text.

```scala title="examples/procmon/src/main/scala/io/worxbend/tui/examples/procmon/ProcessSource.scala"
package io.worxbend.tui.examples.procmon

import java.util.concurrent.atomic.AtomicLong
import scala.util.Random

final case class ProcessInfo(pid: Int, user: String, cpuPercent: Double, memPercent: Double, command: String)

trait ProcessSource:

  /** Shown in the header, so a reader can tell live data from the fallback at a glance. */
  def name: String

  def sample(): Seq[ProcessInfo]
```

Everything the app cannot control sits behind that one method, so the whole UI can be
driven from a deterministic fake. `sample()` is allowed to block; step 5 keeps it off
the render thread. Append the fake to the same file — it is the one block here you can
paste without reading, because the drift arithmetic is not the lesson.

```scala title="ProcessSource.scala"
final class SyntheticProcessSource(seed: Long = 20260807L, processCount: Int = 48) extends ProcessSource:

  def name: String = "synthetic"

  private val samplesTaken = AtomicLong(0L)

  private val processes: Vector[SyntheticProcessSource.Template] =
    val random = Random(seed)
    Vector.tabulate(processCount) { index =>
      SyntheticProcessSource.Template(
        pid = 100 + index * 7 + random.nextInt(6),
        user = SyntheticProcessSource.Users(random.nextInt(SyntheticProcessSource.Users.size)),
        command = SyntheticProcessSource.Commands(index % SyntheticProcessSource.Commands.size),
        baseCpu = SyntheticProcessSource.BusyProcesses.getOrElse(index, math.pow(random.nextDouble(), 4) * 1.5),
        baseMem = 0.4 + math.pow(random.nextDouble(), 3) * 4.0,
        phase = random.nextDouble() * math.Pi * 2,
      )
    }

  def sample(): Seq[ProcessInfo] =
    val step = samplesTaken.incrementAndGet()
    processes.map { template =>
      ProcessInfo(
        pid = template.pid,
        user = template.user,
        cpuPercent = SyntheticProcessSource.drift(template.baseCpu, template.phase, step),
        memPercent = SyntheticProcessSource.drift(template.baseMem, template.phase * 0.5, step),
        command = template.command,
      )
    }

object SyntheticProcessSource:

  private final case class Template(pid: Int, user: String, command: String, baseCpu: Double, baseMem: Double,
      phase: Double)

  private val Users         = Vector("root", "worxbend", "postgres", "www-data", "systemd")
  private val BusyProcesses = Map(7 -> 34.0, 22 -> 12.5, 38 -> 5.5)

  private val Commands = Vector(
    "systemd", "kthreadd", "sshd", "postgres", "nginx", "node", "java", "chrome",
    "zsh", "dockerd", "containerd", "Xorg", "pipewire", "code", "rustc", "mill",
  )

  private def drift(base: Double, phase: Double, step: Long): Double =
    val swing = math.sin(step * 0.35 + phase)
    math.max(0.0, base * (1.0 + 0.3 * swing) + 0.15 * swing)
```

Two kinds of determinism, and the split matters. The *shape* of the table comes from
a seeded `Random`, so two runs produce the same processes; the *movement* is a pure
function of the sample number, so a test can take three samples and know exactly what
it is comparing. Values wander far enough to reorder a CPU-sorted table between
refreshes, which is the situation this app exists to survive.

Hold a sample in the app. Replace the class body in `Main.scala`:

```scala title="Main.scala"
final class ProcmonApp(val source: ProcessSource = SyntheticProcessSource()) extends TuiApp:

  val processes: Signal[Vector[ProcessInfo]] = Signal(source.sample().toVector)

  override def bindings: KeyBindings = KeyBindings(
    binding("q", "quit")(quit()),
  )

  def view(using ReactiveScope, Theme): Element =
    panel("procmon")(text(s"${processes.get.size} processes").dim).rounded
```

Run it: the panel reads `48 processes`. The source is a constructor parameter with a
default, and that is the whole of the seam the tests use.

## 3. Read the real process list, and fall back to samples

Widen the imports at the top of `ProcessSource.scala` to
`java.util.concurrent.TimeUnit`, `java.util.concurrent.atomic.AtomicLong` and
`scala.util.{Random, Try, Using}`, then add:

```scala title="ProcessSource.scala"
object ProcessSource:

  def detect(): ProcessSource =
    val live = PsProcessSource()
    if Try(live.sample()).toOption.exists(_.sizeIs > 1) then live else SyntheticProcessSource()

final class PsProcessSource extends ProcessSource:

  def name: String = "ps"

  def sample(): Seq[ProcessInfo] =
    val process = ProcessBuilder("ps", "-eo", "pid=,user=,pcpu=,pmem=,comm=")
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    val lines   =
      Using.resource(scala.io.Source.fromInputStream(process.getInputStream, "UTF-8"))(_.getLines().toVector)
    if !process.waitFor(PsProcessSource.TimeoutSeconds, TimeUnit.SECONDS) then
      val _ = process.destroyForcibly()
    lines.flatMap(PsProcessSource.parse)

object PsProcessSource:

  private val TimeoutSeconds = 2L

  private def parse(line: String): Option[ProcessInfo] =
    line.trim.split("\\s+", 5) match
      case Array(pid, user, cpu, mem, command) =>
        for
          parsedPid <- pid.toIntOption
          parsedCpu <- cpu.toDoubleOption
          parsedMem <- mem.toDoubleOption
        yield ProcessInfo(parsedPid, user, parsedCpu, parsedMem, command)
      case _                                   => None
```

`ps` rather than `/proc` directly: `/proc` is Linux-only, and building these five
fields from it means `/proc/<pid>/stat` for jiffy counters, `/proc/<pid>/statm` for
resident pages, `/proc/meminfo` for the total to divide by, and the file owner for
the user name — eighty lines of kernel-format parsing that teach nothing about the
toolkit. The cost, stated plainly: `ps` averages `%cpu` over each process's whole
lifetime rather than reporting a delta since the last sample, so the numbers drift
instead of jumping. `detect()` probes with a real sample rather than a
`File("/proc").exists()` guess, because `ps` can be present and still fail — a
stripped container image, a sandbox that forbids `fork` — and learning that once at
startup is kinder than learning it once a second forever.

```scala title="Main.scala"
final class ProcmonApp(val source: ProcessSource = ProcessSource.detect()) extends TuiApp:

  val processes: Signal[Vector[ProcessInfo]] = Signal(source.sample().toVector)

  def view(using ReactiveScope, Theme): Element =
    panel("procmon")(text(s"${processes.get.size} processes · source ${source.name}").dim).rounded
```

Run it: the count is your machine's, and the header reads `source ps` — or
`source synthetic` where `ps` is unavailable.

## 4. Put the snapshot on screen

Add `java.util.Locale` and `io.worxbend.tui.widgets.{ColumnSort, DataTable, DataTableState, KeyedRow, SortDirection}` to
`Main.scala`'s imports, plus `import ProcmonApp.*` at the top of the class body.

```scala title="Main.scala"
  val tableState: DataTableState[Int] = DataTableState()

  tableState.sort = Some(ColumnSort(CpuColumn, SortDirection.Descending))

  private def buildTable(rows: Seq[ProcessInfo]): DataTable[Int] =
    DataTable(
      columns = Seq("   PID", "USER", " CPU%", " MEM%", "COMMAND"),
      rows = rows.map(process => KeyedRow(process.pid, cellsOf(process))),
      widths = Seq(
        Constraint.Length(PidWidth + 2),
        Constraint.Length(10),
        Constraint.Length(NumberWidth + 2),
        Constraint.Length(NumberWidth + 2),
        Constraint.Fill(1),
      ),
    )

  private def cellsOf(process: ProcessInfo): Seq[String] =
    Seq(
      integer(process.pid, PidWidth),
      process.user,
      decimal(process.cpuPercent, NumberWidth),
      decimal(process.memPercent, NumberWidth),
      process.command,
    )

  private def header(using ReactiveScope, Theme): Element =
    panel("procmon")(text(s"${processes.get.size} processes · source ${source.name}").dim).rounded.length(3)

  def view(using ReactiveScope, Theme): Element =
    column(
      header,
      dataTable(buildTable(processes.get), tableState).fill,
    )
```

```scala title="Main.scala"
object ProcmonApp:

  private val CpuColumn = 2

  private val PidWidth    = 6
  private val NumberWidth = 5

  private def decimal(value: Double, width: Int): String = String.format(Locale.ROOT, s"%$width.1f", value)

  private def integer(value: Int, width: Int): String = String.format(Locale.ROOT, s"%${width}d", value)
```

A fresh `DataTable` per frame is correct and cheap — it is an immutable case class
over the rows. Only `tableState` persists, which is why it is a field and not a
local; see [the state ownership rule](./widgets#the-state-ownership-rule). Each
row is a `KeyedRow`: the cells are what the table sorts, filters and draws, and
the key — the pid — is the row's stable identity, which step 8's selection leans
on. Padding to a fixed width is what makes a numeric column sort the same whether
the widget reads it as numbers or as text, and `Locale.ROOT` rather than the
ambient locale because `%.1f` writes `12,5` across much of Europe — which looks
wrong and, from step 7, stops the column sorting as a number.

```bash
./mill examples.procmon.run
```

A busiest-first table, the way `top` opens:

```
╭procmon────────────────────────────────────────────╮
│312 processes · source ps                          │
╰───────────────────────────────────────────────────╯
   PID  USER        CPU%   MEM%  COMMAND
  1842  worxbend    34.0    1.9  chrome
  2201  worxbend    12.5    4.4  java
   918  root         5.5    0.7  Xorg
```

## 5. Refresh on a timer

The snapshot is frozen. Add `scala.concurrent.duration.DurationInt` to the imports
(`RunnerConfig` already comes in with `io.worxbend.tui.dsl.*`), then replace the eager
sample with a tick-driven one.

```scala title="Main.scala"
  override def config: RunnerConfig = RunnerConfig(tickRate = Some(TickRate))

  val processes: Signal[Vector[ProcessInfo]] = Signal(Vector.empty)

  val sampleCount: Signal[Int] = Signal(0)

  val refreshSeconds: Signal[Int] = Signal(2)

  private var ticksUntilRefresh: Int = 0

  override def onTick(): Unit =
    if ticksUntilRefresh <= 0 then
      ticksUntilRefresh = refreshSeconds.peek * TicksPerSecond
      refresh()
    ticksUntilRefresh -= 1

  private def refresh(): Unit =
    Async.runCatching(source.sample()) {
      case Right(sampled) =>
        processes.set(sampled.toVector)
        sampleCount.update(_ + 1)
      case Left(error)    =>
        notify(s"sample failed: ${error.getMessage}", NoticeLevel.Warning, duration = 3.seconds)
    }

  override def bindings: KeyBindings = KeyBindings(
    binding("r", "refresh now")(refresh()),
    binding("+", "slower refresh")(refreshSeconds.update(seconds => math.min(MaxRefreshSeconds, seconds + 1))),
    binding("-", "faster refresh")(refreshSeconds.update(seconds => math.max(1, seconds - 1))),
    binding("q", "quit")(quit()),
  )
```

```scala title="Main.scala"
  private val MaxRefreshSeconds = 10

  private val TickRate       = 250.millis
  private val TicksPerSecond = (1.second / TickRate).toInt
```

`Async.runCatching` is the load-bearing call. `sample()` shells out to `ps` and costs
tens of milliseconds; the render thread is also the thread drawing frames, so
sampling there would stutter the UI. `runCatching` performs the work on a worker and
delivers the `Either` back **on the render thread**, which is the only thread a
`Signal` may be written from — write one from the worker and the runtime throws. See
[State & signals](./state-and-signals#the-render-thread-rule).

Counting ticks rather than starting an `Async.every` poller keeps everything on one
clock: there is no `Cancelable` to leak at quit, `+`/`-` change the interval by
changing a number, and `r` reaches exactly the same code path — which is what makes
the refresh testable without waiting on wall-clock time.
[Live data & background work](./live-data) covers the poller alternative and when you
need it. Run the app: the table populates within two seconds and the numbers move.

## 6. Refresh the row snapshot

Each sample replaces the immutable row snapshot. The table recomputes sorting and
filtering for the new source even when the process count stays the same.

```scala title="Main.scala"
  private def refresh(): Unit =
    Async.runCatching(source.sample()) {
      case Right(sampled) =>
        processes.set(sampled.toVector)
        sampleCount.update(_ + 1)
      case Left(error)    =>
        notify(s"sample failed: ${error.getMessage}", NoticeLevel.Warning, duration = 3.seconds)
    }
```

`DataTable` caches by row-source identity, sort and filter, not merely row count.
A new snapshot cannot keep the previous sample's ordering. Keep an unchanged
snapshot stable between samples to retain cache hits; manual invalidation is an
optional escape hatch, not a refresh requirement. See
[Tables & selection](./tables-and-selection#refresh-immutable-row-snapshots).

Once sorting is bound below, the busiest process rises as it gets busy.

## 7. Sort from the keys you declare

The widget ships no sort keys of its own. Add a helper, five bindings, and the
remaining column constants beside `CpuColumn` in the companion.

```scala title="Main.scala"
  private def sortBy(column: Int): Unit =
    tableState.sortBy(column)
```

Five more entries in `bindings`:

```scala title="Main.scala"
    binding("p", "sort by pid")(sortBy(PidColumn)),
    binding("u", "sort by user")(sortBy(UserColumn)),
    binding("c", "sort by cpu")(sortBy(CpuColumn)),
    binding("m", "sort by memory")(sortBy(MemColumn)),
    binding("n", "sort by command")(sortBy(CommandColumn)),
```

Four more constants in `object ProcmonApp`:

```scala title="Main.scala"
  private val PidColumn     = 0
  private val UserColumn    = 1
  private val MemColumn     = 3
  private val CommandColumn = 4
```

`sortBy` on the same column twice flips the direction; that rule lives in the widget
state, so the binding stays one line. Two traps sit here. The widget sorts the
*rendered strings*: it compares a column numerically only when every cell in it
parses as a non-`NaN` `Double`, and lexicographically otherwise, so `"900M"` would
sort above `"1.2G"`. Step 4's padding is what makes both orderings agree — fixed
width, one decimal place, non-negative, units kept in the header. Put a suffix in a
cell and the column silently sorts as text; see
[Tables & selection](./tables-and-selection#sort-numbers-that-carry-units) for the
alternative of sorting the domain rows yourself. And a single-character key spec
keeps its case, because that case is what the terminal reports: bind `"G"`, never
`"shift+g"`.

Run it, press `p`, then `p` again: pid ascending, then descending.

## 8. Keep the selection on the same process

`tableState.selected` is an index into the *currently visible* rows. Sort, filter or
refresh, and index 3 is a different process. Because step 4 keyed every row by its
pid, the widget already pins the highlight to the process: every render records the
selected row's key on the state, and the next render re-anchors the selection to
wherever that pid landed. The application code for all of that is nothing — what
remains is reading the pid back, and steering the selection from the keys:

```scala title="Main.scala"
  def selectedProcessId: Option[Int] = buildTable(processes.peek).selectedKey(tableState)

  def visibleProcessIds: Seq[Int] = buildTable(processes.peek).visibleRows(tableState).map(_.key)

  private def moveSelection(delta: Int): Unit =
    val rows = buildTable(processes.peek).visibleRows(tableState)
    if delta < 0 then tableState.selectPrevious(rows.size) else tableState.selectNext(rows.size)

  private def tableElement(table: DataTable[Int]): Element =
    dataTable(table, tableState)
      .onKeyEvent {
        case KeyEvent(KeyCode.Down, _) =>
          moveSelection(1)
          true
        case KeyEvent(KeyCode.Up, _)   =>
          moveSelection(-1)
          true
        case _                         => false
      }
      .fill
```

`selectedKey` answers the recorded anchor, so a sort between frames never leaves it
naming a stale row — no parsing the PID cell back out of the widget's own text, and
no parallel index into the domain rows that the first sort would invalidate. The
widget's built-in Up/Down moves by row index the same way; intercepting the keys
here keeps the arrows and step 12's wheel on the one `moveSelection` path. An index
move drops the recorded key, and the next render records the key of the row the
highlight lands on — the anchor is always the process the reader is looking at.

Use `tableElement(buildTable(processes.get))` in `view` in place of the inline
`dataTable(...)`.

Run it, select a row with `↓`, then press `p`: the highlight follows its process to
the new position instead of staying on row 3.

## 9. Filter as you type

Add `TextInputState` to the widgets import.

```scala title="Main.scala"
  val filterInput: TextInputState = TextInputState()

  private val filterOpen: Signal[Boolean] = Signal(false)

  private def closeFilter(clear: Boolean): Unit =
    if clear then
      filterInput.clear()
      tableState.setFilter("")
    filterOpen.set(false)

  private def syncFilter(): Unit =
    if tableState.filter != filterInput.value then
      tableState.setFilter(filterInput.value)

  private def filterRow(using ReactiveScope): Seq[Element] =
    if !filterOpen.get then Seq.empty
    else
      Seq(
        row(
          text(" filter ").dim.length(8),
          input(filterInput, placeholder = "substring — matched against every column").onKeyEvent {
            case KeyEvent(KeyCode.Enter, _)  =>
              closeFilter(clear = false)
              true
            case KeyEvent(KeyCode.Escape, _) =>
              closeFilter(clear = true)
              true
            case _                           => false
          }.fill,
        ).length(1)
      )

  private def handleUnclaimedKey(event: KeyEvent): Boolean =
    event.code match
      case KeyCode.Down =>
        moveSelection(1)
        true
      case KeyCode.Up   =>
        moveSelection(-1)
        true
      case _            => false
```

Add `binding("/", "filter rows")(filterOpen.set(true))`, then rebuild `view` so the
filter row sits between the header and the table:

```scala title="Main.scala"
  def view(using ReactiveScope, Theme): Element =
    val table = buildTable(processes.get)
    syncFilter()
    column(
      (Seq(header) ++ filterRow ++ Seq(tableElement(table)))*
    ).onKeyEvent(handleUnclaimedKey)
```

`syncFilter` pushes the input's text into the table state **only when it changed**,
because `setFilter` also clears the selection and the scroll offset; doing that every
frame makes the table unusable. The filter row is deliberately unnamed: focus is
positional unless an element carries `.key(...)`, so this row becomes focusable zero
when it appears and gives the focus back to the table when it goes. Naming them would
pin focus to the table and `/` would open a box nobody was typing into.

That is also why typing `n` into the box filters rather than firing the
sort-by-command binding: the focused element consumes a key before app bindings are
consulted. `handleUnclaimedKey` on the outer column is what still lets `↑`/`↓` steer
the list while you type, since the table consumes them itself whenever it is focused.

Run it: `/`, type `ssh`, and the table narrows. `Enter` keeps the filter, `Esc`
clears it.

## 10. Derive the summary header

Replace the placeholder header with real statistics. Long-lived derived values belong
on the app as fields, not inside `view`.

```scala title="Main.scala"
  private val summary: Computed[Summary] = Computed {
    val all = processes.get
    Summary(all.size, all.map(_.cpuPercent).sum, all.map(_.memPercent).sum)
  }

  private def summaryPanel(shown: Int)(using ReactiveScope, Theme): Element =
    val stats = summary.get
    panel("procmon")(
      text(
        s"${stats.count} processes · $shown shown · sample ${sampleCount.get} · " +
          s"every ${refreshSeconds.get}s · source ${source.name}"
      ).dim,
      progressBar(stats.cpuPercent / 100.0).label(s"CPU ${decimal(stats.cpuPercent, 5)}%").ramp(ColorRamp.Traffic),
      progressBar(stats.memPercent / 100.0).label(s"MEM ${decimal(stats.memPercent, 5)}%").ramp(ColorRamp.Traffic),
    ).rounded.length(5)
```

Put `Summary` — `private final case class Summary(count: Int, cpuPercent: Double,
memPercent: Double)` — in the companion, then call `summaryPanel` from `view` with
`table.visibleRows(tableState).size`. Give `summaryPanel` a `(using Theme)` parameter
so the panel it builds is themed by the app: `view` receives the theme and hands it
down, but only to helpers that ask for it. **Delete** `header`: a private method
nothing calls is a build failure here.

A `Computed` built inside `view` would re-subscribe on every redraw and never be
released — that is what `Computed.dispose` exists to clean up after. These are sums
over every process, so on a many-core machine the CPU figure legitimately exceeds
100; `progressBar` clamps its ratio and saturates, and the exact number is in the
label either way.

## 11. Chrome, quit, and a toast that leaves

One row of hints along the bottom, fed by the same declarations that dispatch keys.
In `object ProcmonApp`:

```scala title="Main.scala"
  private val Hints = Seq(
    "↑/↓"       -> "select",
    "/"         -> "filter",
    "p/u/c/m/n" -> "sort",
    "r"         -> "refresh",
    "q"         -> "quit",
  )
```

```scala title="Main.scala"
  def view(using ReactiveScope, Theme): Element =
    val table = buildTable(processes.get)
    syncFilter()
    val visible = table.visibleRows(tableState)
    column(
      (Seq(summaryPanel(visible.size)) ++ filterRow ++ Seq(tableElement(table), statusBar(Hints)))*
    ).onKeyEvent(handleUnclaimedKey)
```

`Hints` is a curated subset rather than `statusBar(bindings)`: every binding is
already reachable through the built-in `Ctrl+P` palette, so only the ones worth a
permanent reminder earn a place on a one-row bar. The failure toast from step 5 ages
on wall-clock time, not on ticks: `duration = 3.seconds` means three seconds whatever
`TickRate` is, and leaving the argument off gives the three-second default. See
[The app shell](./app-shell#declare-commands-once) for the rest of the command
surface.

Run it and press `Ctrl+P`: every binding, fuzzy-searchable, with no extra code.

## 12. Scroll with the wheel

`DataTableElement` has no built-in wheel behaviour, unlike `list`. Turn capture on
and chain the handler onto `tableElement`'s `dataTable(...)` between `.onKeyEvent`
and `.fill`.

```scala title="Main.scala"
  override def config: RunnerConfig = RunnerConfig(tickRate = Some(TickRate), mouseCapture = true)
```

```scala title="Main.scala"
      .onMouseEvent { event =>
        event.kind match
          case MouseEventKind.ScrollDown =>
            moveSelection(1)
            true
          case MouseEventKind.ScrollUp   =>
            moveSelection(-1)
            true
          case _                         => false
      }
```

Routing the wheel through `moveSelection` rather than through the scroll offset keeps
one notion of where the user is, so the pinned pid stays correct after scrolling. The
app is finished. Run it and scroll.

## 13. Test it headlessly

`HeadlessBackend` renders the same buffers a terminal would and accepts the same
events, so the whole app is testable without a PTY. The synthetic source keeps it
offline and repeatable.

```scala title="examples/procmon/src/test/scala/io/worxbend/tui/examples/procmon/ProcmonAppSpec.scala"
package io.worxbend.tui.examples.procmon

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{DurationInt, FiniteDuration}

final class ProcmonAppSpec extends AnyFunSuite:

  private def waitUntil(timeout: FiniteDuration = 10.seconds)(predicate: => Boolean): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    while !predicate && System.nanoTime() < deadline do Thread.sleep(20)

  private def withApp[A](body: (ProcmonApp, Pilot) => A): A =
    val backend = HeadlessBackend(Size(96, 24))
    val app     = ProcmonApp(SyntheticProcessSource())
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      waitUntil()(app.sampleCount.peek > 0)
      pilot.waitForIdle()
      body(app, pilot)
    }

  test("the selection follows its process across a re-sort and across a refresh"):
    withApp { (app, pilot) =>
      pilot.press("down", "down", "down").waitForIdle()

      val pinned = pilot.readOnRenderThread(app.selectedProcessId)
      assert(pilot.readOnRenderThread(app.tableState.selected).contains(2))

      pilot.press("p").waitForIdle()
      assert(pilot.readOnRenderThread(app.selectedProcessId) == pinned)
      assert(pilot.readOnRenderThread(app.visibleProcessIds(app.tableState.selected.get)) == pinned.get)

      val samplesBefore = app.sampleCount.peek
      pilot.press("r")
      waitUntil()(app.sampleCount.peek > samplesBefore)
      pilot.waitForIdle()
      assert(pilot.readOnRenderThread(app.selectedProcessId) == pinned)
      assert(pilot.readOnRenderThread(app.visibleProcessIds(app.tableState.selected.get)) == pinned.get)

      pilot.press("q")
      assert(pilot.awaitTermination())
    }
```

`Pilot.waitForIdle` proves the posted event queue drained; it says nothing about a
sample a tick started on an `Async` worker that lands on a later render-thread drain.
`waitUntil` polls the app's own counter instead, which is why `sampleCount` exists,
and polling rather than sleeping a fixed time survives a parallel test run starving
the tick thread. `visibleProcessIds` and `selectedProcessId` exist for exactly this:
they are the domain projection the row keys make a one-liner, over a widget state
that otherwise speaks in row indices.

```bash
./mill examples.procmon.test
./mill show examples.procmon.nativeImage
```

The native build needs GraalVM and writes a self-contained binary under
`out/examples/procmon/nativeImage.dest/`. It works with `--no-fallback` and no
reflection configuration because nothing in the app reflects; see
[Native binaries](./native-image).

The full suite adds four tests worth copying: the header and hints render, a second
sort press reverses, the filter narrows and `Esc` clears it, and ticks alone produce
both a new sample and a repaint (`pilot.backend.drawCount > drawsBefore`).
[Read the procmon test source](https://github.com/oleksandr-balyshyn/glyphora/blob/main/examples/procmon/src/test/scala/io/worxbend/tui/examples/procmon/ProcmonAppSpec.scala).

## What you learned

| Step | Goes deeper in |
|---|---|
| 3 — an injectable source with a deterministic fake | [Live data & background work](./live-data#put-the-source-behind-an-interface) |
| 5 — blocking work off the render thread, on a timer | [Live data & background work](./live-data#fetch-once-then-every-interval) |
| 6 — immutable row snapshots | [Tables & selection](./tables-and-selection#refresh-immutable-row-snapshots) |
| 7 — sorting rendered strings, and key case | [Tables & selection](./tables-and-selection#sort-numbers-that-carry-units) |
| 8 — a selection pinned to a domain identity | [Tables & selection](./tables-and-selection#pin-the-selection-to-an-identity) |
| 10 — long-lived derived values | [State & signals](./state-and-signals) |
| 13 — driving a whole app headlessly | [Testing](./testing#drive-a-full-app-with-pilot) |

Three things to try next in this app: a `k` binding that kills the selected pid,
which is the payoff for `selectedProcessId` existing; `paging` on `DataTableState`
so `PgUp`/`PgDn` move a screen at a time; and a per-process CPU history behind a
sparkline, which needs a rolling `Vector` rather than a single snapshot.

## Where to go next

- [Live data & background work](./live-data) — pollers, cancellation, stale
  responses, and worker pools when one timer is no longer enough.
- [Tables & selection](./tables-and-selection) — paging, wheel scrolling, and every
  way a refreshing table loses the user's place.
- [Build a sensor dashboard](./build-a-sensor-dashboard) — the same skeleton over a
  remote source, with threshold bands instead of a table.
- [Build a load generator](./build-a-load-generator) — concurrent work you own,
  streamed back to the screen.
- [Read the procmon source](https://github.com/oleksandr-balyshyn/glyphora/blob/main/examples/procmon/src/main/scala/io/worxbend/tui/examples/procmon/Main.scala)
  — the finished file, with its reasoning in the comments.
