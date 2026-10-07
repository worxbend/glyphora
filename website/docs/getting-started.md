---
title: Getting started
description: Install glyphora, run a reactive counter, and understand each piece of your first terminal app.
---

# Build your first screen

In this guide you will create a small reactive counter, run it in a real terminal,
and make two changes that exercise state, layout, styling, and keyboard commands.

> **You need:** JDK 25 or newer, Scala 3, and either Mill or sbt. Run the final app
> from a terminal—not an IDE output panel—because raw input needs a controlling TTY.

## 1. Add glyphora

:::caution Not on Maven Central yet

A source release tag does not make artifacts available on Maven Central. Until `0.16.1`
is published there, the coordinates below will not resolve from a public repository.
Build the artifacts locally:

```bash
git clone https://github.com/oleksandr-balyshyn/glyphora.git
cd glyphora
./mill __.publishLocal
```

That publishes `tui-core`, `tui-terminal`, `tui-widgets`, `tui-runtime`,
`tui-macros`, `tui-dsl` and `tui-test` at version `0.16.1` into your local Ivy cache
(`~/.ivy2/local`). Mill reads that cache by default. **sbt does not**, so an sbt build
also needs

```scala title="build.sbt"
resolvers += Resolver.defaultLocal
```

:::

The normal application dependency is `tui-dsl`. It brings in the core types,
widgets, terminal backend, and runtime transitively.

### Mill

```scala title="build.mill"
package build

import mill.*, scalalib.*

object app extends ScalaModule:
  def scalaVersion = "3.9.0"
  def mvnDeps = Seq(mvn"io.worxbend::tui-dsl:0.16.1")
```

Put application sources under `app/src/`, then run them with `mill app.run` (or
`./mill app.run` when your project checks in the Mill launcher).

### sbt

```scala title="build.sbt"
scalaVersion := "3.9.0"

libraryDependencies += "io.worxbend" %% "tui-dsl" % "0.16.1"
```

Put application sources under `src/main/scala/`, then use `sbt run`.

<details>
<summary>Why depend on tui-dsl instead of every module?</summary>

`tui-dsl` is the batteries-included application layer. It exports `TuiApp`, element
factories, style and layout extensions, reactive state, widgets, terminal events,
and runtime effects. Lower-level module dependencies are useful only when you are
embedding glyphora or writing a widget library; see [Architecture](./architecture).

The one artifact worth adding on purpose is `io.worxbend::tui-test`, in the **test**
configuration: it carries the `Pilot` driver that runs a whole app without a TTY. See
[Testing](./testing).

</details>

## 2. Create the app

```scala title="Counter.scala"
import io.worxbend.tui.dsl.*

class CounterApp extends TuiApp:
  private val count = Signal(0)

  override def bindings: KeyBindings = KeyBindings(
    binding("+", "increment")(count.update(_ + 1)),
    binding("-", "decrement")(count.update(_ - 1)),
    binding("q", "quit")(quit()),
  )

  def view(using ReactiveScope, Theme): Element =
    scaffold(statusBar = Some(statusBar(bindings))) {
      centered(34, 7) {
        panel("Counter")(
          text(s"Count: ${count.get}").bold.fg(Color.Cyan),
          spacer,
          text("Change state; the view follows.").dim,
        ).rounded
      }
    }

object Counter extends CounterApp
```

There is no hand-written `def main`. `TuiApp` supplies one, so `object Counter extends
CounterApp` is already a runnable program: the launcher starts it, and if the app cannot
take over the terminal at all it prints the reason on standard error and exits non-zero.
(Standard *output* is where the UI itself is drawn, so a failure message printed there
would land in the middle of the screen the app just gave back.)

Why the class plus a one-line object, rather than a single `object Counter extends
TuiApp`? Because a test needs a *fresh* app for each scenario. `TuiApp` keeps its state
— the signals, the screen stack, any running effect — on the instance and never resets
it between runs, so running the same object twice starts the second run holding
whatever the first left behind. Splitting it lets [Testing](./testing) write
`Pilot.using(Size(40, 10))(backend => CounterApp().runWith(backend))(testBody)` per test while the launcher still has
its `object Counter`. The
runnable twin of this app is [`examples/counter`](./examples).

Run it in a terminal:

```bash
# Mill
./mill app.run

# sbt
sbt run
```

Press `+` and `-` to change the value; press `q` to exit. The runner enters raw mode,
uses the alternate screen, hides the cursor, and restores the terminal when it
finishes.

## 3. Understand the moving parts

| Code | Responsibility |
|---|---|
| `extends TuiApp` | owns the event/render lifecycle and terminal-safe cleanup |
| `Signal(0)` | stores mutable state and invalidates views that read it |
| `bindings` | declares global commands once for dispatch, help, palette, and status hints |
| `view(using ReactiveScope, Theme)` | tracks signal reads while returning an element tree |
| `scaffold(...)` | composes optional top bar, sidebar, content, and status bar |
| `centered(34, 7)` | gives the panel a fixed area centered in available space |
| `.rounded`, `.bold`, `.fg(...)` | type-safe element decoration and style extensions |
| `run()` | opens the backend and blocks until `quit()` or an unconsumed `Ctrl+C` |

The key detail is `count.get`. That tracked read connects this view to `count`.
Calling `count.update` marks it stale; the runtime schedules a redraw and rebuilds
the view. There is no manual refresh call.

## 4. Make it yours

### Add a derived value

Use `Computed` for a value that depends on one or more signals:

```scala
private val count = Signal(0)
private val parity = Computed(if count.get % 2 == 0 then "even" else "odd")

// inside view
text(s"${count.get} is ${parity.get}")
```

### Add another region

Rows and columns divide their available area using constraints:

```scala
row(
  panel("Value")(text(count.get.toString)).percent(40),
  panel("Parity")(text(parity.get)).fill,
).length(5)
```

Here the row is five cells high. Its first child receives 40% of the width and the
second consumes the remainder.

### Handle a key locally

Global commands belong in `bindings`. Interaction that belongs to one element can
stay beside that element:

```scala
panel("Counter")(text(count.get.toString))
  .onKey(Key.char('r')) { count.set(0) }
```

Local handlers run before global bindings. A low-level `.onKeyEvent` handler returns
`true` to stop bubbling or `false` to let the parent/global binding see the event.

## 5. Know the first two traps

### “terminal not supported” at startup

The app prints `glyphora: terminal not supported: dumb terminal (no TTY attached)` and
exits 1, because the process has no controlling TTY (the error is
`BackendError.UnsupportedTerminal` in the API). Run it from a normal terminal window;
for CI or unit tests, inject `HeadlessBackend` instead. See [Testing](./testing).

### Signal write rejected off the render thread

Key handlers, mouse handlers, `onStart`, `onStop` and `onTick` already run on the
render thread, so a signal write in any of them is fine. Blocking work — an HTTP call,
a database query, reading a large file — must *not* run there, because the loop that
runs it is the same loop that draws frames.

`Async` is the answer to both halves at once. It moves the work off the render thread
and delivers the result *back* on it, so the signal write in the callback is an
ordinary signal write:

```scala
import io.worxbend.tui.dsl.*

Async.runCatching(loadData()) {
  case Right(value) => data.set(value)
  case Left(error)  => message.set(s"could not load: ${error.getMessage}")
}
```

Handle the `Left`. `runCatching` delivers a thrown exception rather than dropping it,
and a UI that ignores it sits on its loading spinner for ever.

You need `RenderThread.runOnRenderThread { … }` only in one situation: a third-party
library that owns its own callback thread and hands you a value there, with no
opportunity to route the call through `Async`. Wrap just the state write:

```scala
someLibrary.onMessage { payload =>          // library's thread, not ours
  RenderThread.runOnRenderThread { data.set(payload) }
}
```

The complete pattern is in [Async work & timers](./async-and-timers).

## Where to go next

- [Layout & style](./layout-and-style) — rows, columns, constraints, borders, and
  reusable visual language.
- [State & signals](./state-and-signals) — tracked reads, computed values, and
  render-thread rules.
- [Widget catalog](./widgets) — choose the right building blocks.
- [The app shell](./app-shell) — themes, sidebars, screens, toasts, and the palette.
- [Examples](./examples) — run ten complete applications from the repository.
