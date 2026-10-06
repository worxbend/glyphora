---
title: Examples
description: Run ten complete glyphora applications and learn which source to read for state, focus, dashboards, forms, async work, tables, live data, and app chrome.
---

# Learn from complete apps

The repository includes ten runnable applications. Each uses the same public APIs
described in this guide and has a headless end-to-end test. Start with the behavior
closest to your app and read its source beside the running terminal.

The last three are larger, and each has a step-by-step guide that builds it from
nothing — see [Build a real app](./build-a-process-monitor).

```bash
git clone git@github.com:oleksandr-balyshyn/glyphora.git
cd glyphora
./mill examples.showcase.run
```

## Pick an example

| Example | Run | Best place to learn |
|---|---|---|
| `hello-world` | `./mill examples.hello-world.run` | `TuiApp`, panels, text, local key handling |
| `counter` | `./mill examples.counter.run` | signal update → tracked redraw, concise `onKey` |
| `todo-list` | `./mill examples.todo-list.run` | text entry, list state, focus switching, delete action |
| `dashboard` | `./mill examples.dashboard.run` | tick-driven gauges, sparklines, chart layout |
| `form-demo` | `./mill examples.form-demo.run` | compile-time form derivation and validation |
| `weather` | `./mill examples.weather.run` | real HTTP through `Async.runCatching`, loading/error states |
| `showcase` | `./mill examples.showcase.run` | scaffold, themes, palette, screens, toasts, splash |
| `procmon` | `./mill examples.procmon.run` | a sortable table over refreshing rows, selection that survives a refresh — [guide](./build-a-process-monitor) |
| `airsensor` | `./mill examples.airsensor.run` | polling, threshold bands, trend arrows, loading/ready/error — [guide](./build-a-sensor-dashboard) |
| `loadtest` | `./mill examples.loadtest.run` | concurrent work off the render thread, histogram and percentiles — [guide](./build-a-load-generator) |

Every app exits with `q` or `Esc`; the source comment above each app lists its full
keyboard vocabulary.

### Which idiom should you copy?

The examples deliberately show two levels of key handling, and they are not
alternatives of equal standing:

| Level | Looks like | Use it for |
|---|---|---|
| **App bindings** (`counter` and every larger example) | `override def bindings = KeyBindings(binding("q", "quit")(quit()))` | anything global. One declaration also produces the status-bar hint, the help-overlay row, and the command-palette entry, and an undeliverable spec is rejected where you wrote it. |
| **Local element handlers** (`hello-world`) | `panel(...).onKey(Key.char('q')) { quit() }` / `.onKeyEvent { … }` | a key that belongs to one element and appears nowhere in the app's vocabulary — Enter inside a text field, `d` on the focused list row. |

`hello-world` is the smallest possible program, so it uses the raw `onKeyEvent`
escape hatch to keep the file to one idea. Start from `counter` for anything real.

## hello-world: the smallest useful tree

```scala
object HelloWorld extends TuiApp:
  def view(using ReactiveScope, Theme): Element =
    panel("Hello")(
      text("Welcome to glyphora!").bold.fg(Color.Cyan),
      spacer,
      text("Press 'q' to quit").dim,
    ).rounded.onKeyEvent {
      case KeyEvent(KeyCode.Char('q'), _) =>
        quit()
        true
      case _ => false
    }
```

This is the render model in miniature: describe an element tree, decorate it, and
handle an event near the UI it controls.

[Read hello-world source](https://github.com/oleksandr-balyshyn/glyphora/blob/main/examples/hello-world/src/main/scala/io/worxbend/tui/examples/helloworld/Main.scala)

## counter: declared bindings and tracked redraws

```scala
class CounterApp extends TuiApp:
  val count: Signal[Int] = Signal(0)

  override def bindings: KeyBindings = KeyBindings(
    binding("+", "increment")(count.update(_ + 1)),
    binding("-", "decrement")(count.update(_ - 1)),
    binding("r", "reset")(count.set(0)),
    binding("q", "quit")(quit()),
  )

  def view(using ReactiveScope, Theme): Element =
    scaffold(statusBar = Some(statusBar(bindings))) {
      centered(34, 7) {
        panel("Counter")(
          text(s"Count: ${count.get}").bold.fg(Color.Green),
          spacer,
          text("Change state; the view follows.").dim,
        ).rounded
      }
    }

object Main extends CounterApp
```

Two things to take from this file. First, the key list exists once: `statusBar(bindings)`
renders the hints from the same values that dispatch the keys, and `Ctrl+P` opens a
fuzzy palette over them, so the on-screen help cannot drift away from the behaviour.
Second, reading `count.get` inside `view` is what makes the redraw automatic — nothing
in this app calls "refresh".

It is a `class` with a one-line `object Main extends CounterApp` on the end, because
`TuiApp` keeps its state on the instance and never resets it between runs; the test
builds a fresh `CounterApp()` per scenario. See [Testing](./testing).

[Read counter source](https://github.com/oleksandr-balyshyn/glyphora/blob/main/examples/counter/src/main/scala/io/worxbend/tui/examples/counter/Main.scala)

## todo-list: state belongs to the app

```scala
val items = Signal(Vector.empty[String])
val inputState = TextInputState()
val listState = ListState()

def view(using ReactiveScope, Theme): Element =
  panel("Todo")(
    input(inputState, placeholder = "what needs doing?").onKeyEvent {
      case KeyEvent(KeyCode.Enter, _) => addItem(); true
      case _                          => false
    },
    spacer(1),
    list(items.get.map(item => s"· $item"), listState).onKey(Key.char('d')) {
      deleteSelected()
    },
    text("Enter add · Tab switch · ↑/↓ select · d delete").dim,
  ).rounded
```

`TextInputState` and `ListState` are created once outside `view`. The input and list
keep their own editing/selection mechanics while the app owns the todo collection.

[Read todo-list source](https://github.com/oleksandr-balyshyn/glyphora/blob/main/examples/todo-list/src/main/scala/io/worxbend/tui/examples/todolist/Main.scala)

## dashboard: time becomes state

```scala
override def config = RunnerConfig(tickRate = Some(100.millis))
val tick = Signal(0)

override def onTick(): Unit = tick.update(_ + 1)

def view(using ReactiveScope, Theme): Element =
  val t = tick.get
  val load = (math.sin(t * 0.1) + 1) / 2
  val samples = Vector.tabulate(60)(i =>
    (math.sin((t + i) * 0.25) * 40 + 50).toLong
  )
  row(
    panel("Load")(gauge(load)).percent(50),
    panel("Throughput")(sparkline(samples)).percent(50),
  )
```

Ticks update a signal on the render thread; charts remain ordinary pure renderers.

[Read dashboard source](https://github.com/oleksandr-balyshyn/glyphora/blob/main/examples/dashboard/src/main/scala/io/worxbend/tui/examples/dashboard/Main.scala)

## form-demo: reflection-free derivation

```scala
import io.worxbend.tui.dsl.*
import io.worxbend.tui.macros.deriveForm

final case class Signup(username: String, age: Int, subscribe: Boolean)

val spec = deriveForm[Signup]
val formState = FormState.of(
  spec,
  spec.field(_.username).validate(_.trim.nonEmpty, "required"),
  spec.field(_.age).validate(_ >= 18, "must be 18 or older"),
)
```

The macro retains the original parsers and generates the constructor call at compile
time. Submission parses all controls first, constructs a candidate only if parsing
succeeds, then runs typed checks. Checks reject without replacing values: the username
check above tests trimmed text but does not trim the submitted username. The UI shows
errors inline and publishes `Some(Signup(...))` only after every check accepts it.

[Read form-demo source](https://github.com/oleksandr-balyshyn/glyphora/blob/main/examples/form-demo/src/main/scala/io/worxbend/tui/examples/formdemo/Main.scala)

## weather: real asynchronous I/O

```scala
private def search(): Unit =
  val city = cityInput.value.trim
  if city.nonEmpty then
    status.set(Status.Loading(city))
    Async.runCatching(client.fetch(city)) {
      case Right(Right(report))  => status.set(Status.Loaded(report))
      case Right(Left(failure))  => status.set(Status.Failed(city, WeatherError.describe(failure)))
      case Left(thrown)          => status.set(Status.Failed(city, thrown.getMessage))
    }
```

`Async.runCatching` does both halves of the job: it runs the two live Open-Meteo
requests on a worker thread, and it delivers the answer *back on the render thread*, so
`status.set` in the callback is an ordinary signal write with no explicit
`RenderThread.runOnRenderThread` hop. The three cases are the three real outcomes — a
report, a `Left` the client produced for a bad response, and an exception the client
threw. Handling that last one is what keeps a hard failure from leaving the UI on
`Status.Loading` for ever. The client is injected, so the headless test uses a
deterministic fake.

[Read weather source](https://github.com/oleksandr-balyshyn/glyphora/blob/main/examples/weather/src/main/scala/io/worxbend/tui/examples/weather/Main.scala)

## showcase: the integrated product surface

The showcase combines:

- launch splash plus frame effects;
- live theme switching;
- top bar, sidebar, tabbed content, and status hints;
- command palette generated from `KeyBindings`;
- modal screen and focus isolation;
- tick-aged toasts;
- input, gauge, sparkline, log, and Markdown widgets;
- clipboard output through the active terminal backend.

Use it as the manual PTY test bed and the fastest tour of app-level features.

[Read showcase source](https://github.com/oleksandr-balyshyn/glyphora/blob/main/examples/showcase/src/main/scala/io/worxbend/tui/examples/showcase/Main.scala)

## Run tests and native builds

```bash
# One example's end-to-end tests
./mill examples.todo-list.test

# Every example test
./mill examples.__.test

# A self-contained executable (GraalVM required)
./mill show examples.showcase.nativeImage
```

Native images use `--no-fallback` and no reflection configuration. Continue with
[Testing](./testing) or [Native binaries](./native-image).
