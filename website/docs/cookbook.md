---
title: Cookbook
description: Copy practical glyphora recipes for layout, state, search, commands, modals, async data, tables, timers, forms, scrolling, and tests.
---

# Cookbook

These recipes solve common application shapes with the high-level DSL. Unless a
snippet shows additional imports, assume:

```scala
import io.worxbend.tui.dsl.*
```

State objects shown as fields belong on the app or owning screen—never recreate them
inside `view`.

## Minimal app with concise keys

```scala
object Hello extends TuiApp:
  def view(using ReactiveScope, Theme): Element =
    centered(36, 7) {
      panel("Hello")(
        text("Welcome to glyphora").bold.fg(Color.Cyan),
        text("q quit").dim,
      ).rounded.onKey(Key.char('q')) { quit() }
    }
```

`TuiApp` supplies `main`, so `object Hello extends TuiApp` is the whole program.

Use `.onKey` for exact key/action pairs; use `.onKeyEvent` only when the handler must
inspect modifiers or decide whether to consume.

## Fixed sidebar, fluid content

```scala
row(
  panel("Projects")(projectList).length(26),
  panel("Workspace")(workspace).fill,
).gap(1)
```

Inside a row, `.length(26)` means columns. Inside a column, it means rows. `.fill`
claims what fixed and percentage siblings leave behind.

## Weighted dashboard columns

```scala
row(
  panel("Queue")(queueGauge).fill(1),
  panel("Throughput")(throughputChart).fill(2),
  panel("Errors")(errorCount).fill(1),
).gap(1).length(12)
```

The middle panel gets twice the remaining width of either side panel.

## Filter a list as the user types

```scala
private val query = TextInputState()
private val selection = ListState()
private val projects = Signal(Vector("atlas", "borealis", "glyphora"))

def projectPicker(using ReactiveScope, Theme): Element =
  val needle = query.value.trim.toLowerCase
  val visible = projects.get.filter(_.toLowerCase.contains(needle))

  column(
    input(query, placeholder = "filter projects").key("project-query"),
    rule(s"${visible.size} matches"),
    list(visible, selection).key("project-list").fill,
  )
```

Input's built-in handler requests a redraw after editing, so `query.value` is fresh
on the next `view`. Keep derived filtering local unless several parts of the app need
the result.

## Keep commands in one registry

```scala
override def bindings = KeyBindings(
  binding("ctrl+r", "refresh data")(reload()),
  binding("ctrl+s", "save changes")(save()),
  binding("?", "show keyboard help") {
    pushScreen(Screen(centered(48, 12)(helpOverlay(bindings))))
  },
  binding("esc", "quit")(quit()),
)

def view(using ReactiveScope, Theme): Element =
  scaffold(statusBar = Some(statusBar(bindings)))(content)
```

The same declarations dispatch commands, render status hints, populate help, and
feed the `Ctrl+P` palette.

## Confirm an action in a modal

```scala
private def confirmDelete(name: String): Unit =
  pushScreen(Screen {
    centered(44, 8) {
      panel("Delete deployment?")(
        text(name).bold,
        text("This cannot be undone.").fg(Color.Red),
        text("Enter confirm · Esc cancel").dim,
      ).rounded
        .onKey(Key.Enter) {
          delete(name)
          popScreen()
          notify("Deployment deleted", NoticeLevel.Success)
        }
        .onKey(Key.Escape) { popScreen() }
    }
  })
```

The modal removes the underlying view from tab order and from input routing automatically.

## Load data without blocking the UI

```scala
private enum LoadState[+A]:
  case Idle, Loading
  case Ready(value: A)
  case Failed(message: String)

private val rows = Signal[LoadState[Vector[Row]]](LoadState.Idle)

private def reload(): Unit =
  rows.set(LoadState.Loading)
  Async.runCatching(api.loadRows()) {
    case Right(value) => rows.set(LoadState.Ready(value))
    case Left(error)  => rows.set(LoadState.Failed(error.getMessage))
  }

def rowsView(using ReactiveScope, Theme): Element = rows.get match
  case LoadState.Idle            => text("Press r to load.").dim
  case LoadState.Loading         => spinner("loading…")
  case LoadState.Ready(value)    => renderRows(value)
  case LoadState.Failed(message) => text(s"Error: $message").fg(Color.Red)
```

`Async.runCatching` performs work on a daemon worker and delivers the `Either` on the
render thread.

## Poll with cancellation

```scala
import scala.concurrent.duration.*

private var poller: Cancelable = () => ()

private def startPolling(): Unit =
  poller.cancel()
  poller = Async.every(15.seconds) { reload() }

private def stopPolling(): Unit =
  poller.cancel()
```

Make the component or screen that starts work responsible for canceling it.

Two things this sketch leaves out, both of which bite in a real poller: `Async.every`
fires for the first time *after* a full interval, so a screen armed this way is empty
for fifteen seconds, and nothing here drops a slow response that arrives after a newer
one. [Live data & background work](./live-data) covers both, plus where the poller must
be started from.

## Show a subprocess's coloured output in a log

A build tool or a test runner colours its output with SGR escape sequences. Fed to a
`log` as plain strings they arrive as stray characters; fed through `AnsiParser` they
arrive as the styled lines the tool meant:

```scala
private val output = LogState(maxLines = 5000)

private def runBuild(): Unit =
  val owner  = RenderThread.capture()           // on the render thread, before the worker starts
  val parser = AnsiParser()                     // one parser per stream; it keeps the style across chunks
  Async.run {
    val process = ProcessBuilder("./mill", "__.compile").redirectErrorStream(true).start()
    val reader  = process.inputReader()
    val chunk   = new Array[Char](4096)
    var read    = reader.read(chunk)
    while read >= 0 do
      val lines = parser.feed(String(chunk, 0, read))
      if lines.nonEmpty then
        val _ = owner.execute { lines.foreach(output.append); requestRedraw() }
      read = reader.read(chunk)
    process.waitFor()
  } { _ =>
    output.append(parser.finish())              // the unterminated last line, if the tool left one
    requestRedraw()
  }

def buildView(using ReactiveScope, Theme): Element = log(output).fill
```

The reader runs on the worker; every `LogState.append` runs on the render thread through
the captured loop, and the `requestRedraw()` beside it is what puts the new lines on
screen, because `LogState` is a plain mutable object the reactive layer cannot see. The
parser is touched by one thread at a time — the worker while reading, the render thread
for the final `finish()` once the worker has returned — which is the ownership it asks
for. A chunk boundary can fall anywhere, inside an escape sequence included; the parser
puts the pieces back together the way a terminal would. Force the tool's colours on when
it checks for a TTY (`--color=always`, `FORCE_COLOR=1`), since a pipe reports none.

For a one-shot result rather than a stream, `Text.fromAnsi(wholeOutput)` is the same
parser run once, and `text(...)` shows the result.

## Show a git diff

Run `git diff` on a worker, parse its output, and show it in a pane that steps through hunks:

```scala
private val state = DiffViewState()
private val patch = Signal(Patch.empty)

private def loadDiff(): Unit =
  Async.runCatching {
    val process = ProcessBuilder("git", "diff", "--no-color").start()
    val output  = String(process.getInputStream.readAllBytes())
    process.waitFor()
    output
  } {
    case Right(output) => Patch.parse(output).foreach(patch.set)
    case Left(_)       => ()                     // not a repository, or git is missing
  }

def view(using ReactiveScope, Theme): Element =
  panel("git diff")(diffView(patch.get, state).split.contextLines(3)).fill
```

`Async.runCatching` reads the output on a worker and hands the result back on the render
thread, where the `Signal` is set. `n`/`]` and `p`/`[` jump between hunks while the pane has
focus. Pass `--no-color`: the parser reads plain unified-diff text, and a user's
`color.ui = always` would otherwise put escape sequences into the pipe. Keeping the parsed
`Patch` in a `Signal` hands the node the same value every frame, so the word-level highlight
is computed once per diff rather than once per frame. To compare two strings you already
hold — a file before and after an edit — skip git and use `Patch.of(before, after)`.

## Sort, filter, and select tabular data

> This recipe shows a `DataTable` over rows that do not change. When the rows are
> replaced on a timer, sorting and selection need more than this — see
> [Tables & selection](./tables-and-selection).

```scala
import io.worxbend.tui.widgets.{DataTable, DataTableState}

private val tableState = DataTableState()
private val table = DataTable.fromStrings(
  columns = Seq("Service", "Status", "Replicas"),
  rows = Seq(
    Seq("api", "ready", "3"),
    Seq("worker", "scaling", "12"),
  ),
  widths = Seq(
    Constraint.Fill(2),
    Constraint.Fill(1),
    Constraint.Length(9),
  ),
)

def deployments: Element =
  dataTable(table, tableState).onKey(Key.Enter) {
    tableState.selected
      .flatMap(table.visibleRows(tableState).lift)
      .foreach(row => openDeployment(row.cells))
  }
```

Selection indexes the filtered/sorted `visibleRows`, which is why the action maps
through that method. Rows built with `KeyedRow(key, cells)` instead of `fromStrings`
carry a record identity, and then `table.selectedKey(tableState)` answers the selected
key with no indexing at all.

## Build tabs with reactive selection

```scala
private val activeTab = Signal(0)

def workspace(using ReactiveScope): Element =
  tabbedContent(
    "Overview" -> overview,
    "Events"   -> eventLog,
    "Settings" -> settings,
  )(activeTab)
```

The element tree contains only the selected page. Arrow/click behavior updates the
selection signal and rebuilds the correct branch.

## Scroll measured Markdown

```scala
import io.worxbend.tui.widgets.ScrollViewState

private val scroll = ScrollViewState()

def releaseNotes: Element =
  panel("Release notes")(
    scrollView(markdown(notesSource), scroll)
  ).rounded
```

The `Markdown` widget implements `tui-core`'s `Measured`, so it reports its wrapped
height for the current width and the two-argument `scrollView` measures it automatically,
showing a scrollbar only when needed. The same holds for any raw widget you drop in with
`widget(...)`: implement `Measured` on it and the scroll view finds its full height. A
widget that cannot report a height measures as exactly one viewport, which means it will
not scroll at all — give that content an explicit `.length(n)` or pass `contentHeight`.

## Stream an LLM reply as Markdown

```scala
private val reply    = MarkdownStream()
private val scroll   = ScrollViewState()
private val revision = Signal(0L) // bumped per chunk, so the view redraws

private def ask(prompt: String): Unit =
  reply.clear()
  val loop = RenderThread.capture() // on the render thread, before going async
  Async.runCatching {
    llm.streamTokens(prompt).foreach { token =>
      loop.execute {
        reply.append(token)
        revision.set(reply.revision)
      }
    }
  } { _ =>
    reply.finish()
    revision.set(reply.revision)
  }

def view(using ReactiveScope, Theme): Element =
  val _ = revision.get // subscribe: every appended chunk is a new frame
  panel("Assistant")(markdownStream(reply, scroll)).rounded
```

`markdownStream(reply, scroll)` stays pinned to the newest row while tokens arrive. Scroll
up and the view holds still; press End to follow again. While the reply is still arriving,
`MarkdownSanitizer` closes the syntax a half-written reply leaves open. `**bold` renders bold,
an unfinished link shows its label, and an open code fence is closed. `finish()` turns the
sanitizer off, so the last frame is exactly the finished document. The stream is
render-thread-only, which is why each token goes through the loop captured before going
async.

## Build a chat prompt with history and @-mentions

```scala
private val people   = Seq("alice", "albert", "bob")
private val composer = PromptComposerState(
  completions = (trigger, query) =>
    if trigger == '@' then people.filter(_.startsWith(query)) else Seq("help", "clear").filter(_.startsWith(query)),
)

def view(using ReactiveScope, Theme): Element =
  column(
    markdownStream(reply, scroll).fill,
    promptComposer(composer)(text => ask(text)),
  )
```

`promptComposer` submits on Enter and inserts a newline on Shift+Enter or Alt+Enter.
Up and Down on the first or last line walk earlier submissions, `Ctrl+R` searches them,
and a dim suggestion from history is accepted with Tab. The provider runs on a worker,
debounced, and only the newest answer is shown. Paste a long log and the draft shows
`[Pasted 120 lines]`; `ask` receives the whole text.

## Create and validate a case-class form

```scala
import io.worxbend.tui.macros.deriveForm

final case class Deploy(name: String, replicas: Int, dryRun: Boolean)

private val spec = deriveForm[Deploy]
private val form = FormState.of(
  spec,
  spec.field(_.name).validate(_.trim.nonEmpty, "required"),
  spec.field(_.replicas).validate(value => value >= 1 && value <= 20, "must be from 1 to 20"),
)

def formView(using ReactiveScope): Element =
  Form(form).onKey(Key.CtrlS) { form.submit() }
```

Read `form.result.get` to react to valid submission and `form.errors.get` when you
need custom error presentation. Checks belong to this exact spec instance and reject
without normalizing values; the name check leaves accepted text unchanged. Parser
errors precede construction and typed checks. See [Forms & validation](./forms-and-validation)
for parser customization and check composition.

## Switch themes at runtime

```scala
private val themes = Vector(Theme.Dark, Theme.Light, Theme.HighContrast)
private val themeIndex = Signal(0)

override def theme = themes(themeIndex.peek)

override def bindings = KeyBindings(
  binding("ctrl+t", "switch theme") {
    themeIndex.update(i => (i + 1) % themes.size)
  }
)

def view(using ReactiveScope, Theme): Element =
  val _ = themeIndex.get
  scaffold(statusBar = Some(statusBar(bindings)))(content)
```

The explicit tracked read invalidates the tree; semantic theme roles repaint the
chrome. There is no `given Theme` to declare — `view` receives the app's own theme as
a `using` parameter and passes it on to every themed helper it calls.

## Add a countdown

```scala
import io.worxbend.tui.runtime.Timer
import scala.concurrent.duration.*

private val timer = Timer(30.seconds)

override def config = RunnerConfig(tickRate = Some(100.millis))

override def onTick(): Unit =
  timer.tick(100.millis)
  if timer.justExpired() then notify("Time is up", NoticeLevel.Warning)

def clock: Element = text(timer.formatted).bold
```

Call `timer.start()`, `stop()`, `toggle()`, or `reset()` from handlers.

## Test an interaction end to end

```scala
val app = TodoApp()
Pilot.using(Size(50, 10))(backend => app.runWith(backend)) { pilot =>
  pilot
    .waitForIdle()
    .typeText("ship docs")
    .press("enter")
    .waitForIdle()

  assert(pilot.screenText.contains("· ship docs"))
}
```

`Pilot` ships in the test-only `tui-test` artifact. `using` closes the runner even
after a failing assertion. See [Testing](./testing) for shutdown deadlines and
render-thread observations.

## Render a custom widget through the DSL

```scala
import io.worxbend.tui.core.*
import io.worxbend.tui.widgets.Paragraph

val raw = Paragraph(Text.raw("custom renderer"), overflow = Overflow.Wrap)

def view(using ReactiveScope, Theme): Element =
  panel("Embedded")(widget(raw).fill).rounded
```

The element wrapper is an escape hatch, not a second rendering system. For a reusable
interactive widget, follow the checklist in [Contributing](./contributing).
