---
title: Print mode and no-TTY runs
description: Print any glyphora view, widget or styled text once to standard output, and decide what a TuiApp does when there is no terminal.
---

# Print once, and run without a terminal

Most command-line tools are not full-screen apps. They want a table, a tree, a
panel or a chart written to the terminal once, styled, and then they want the shell
prompt back. glyphora's widgets can do that without a runner, an event loop or the
alternate screen: `Console` renders a view to text and writes it to standard output.

The same machinery answers a second question — what a `TuiApp` should do when it is
started without a terminal, in a pipe or on CI — through `NonInteractivePolicy`.

## Print a view

```scala
import io.worxbend.tui.dsl.*

@main def report(): Unit =
  Console.print(
    panel("Build")(
      text("compiled 412 files").fg(Color.Green),
      text("2 warnings").fg(Color.Yellow),
    )
  )
```

`Console.print(view)` lays the view out at the terminal's width, asks the view how
many rows it needs at that width (the same `intrinsicHeight` measurement a
`column` uses to size its children), composes one frame through `Snapshot` and
writes it as newline-separated rows. There is no cursor movement in the output, so
it lands in the scrollback like any other line a program prints, and it can be
redirected into a file.

Every parameter defaults to what the process environment says, and each can be
given explicitly:

```scala
Console.print(markdown(readme), width = 80, colors = ColorDepth.NoColor)
Console.print(table(rows, header), height = Some(12))   // decide the height yourself
Console.print(view, theme = Theme.Light)
```

| Parameter | Default |
|---|---|
| `width` | the terminal's width when standard output is one; else `COLUMNS`; else 80 |
| `colors` | what `ColorDepth.detect()` resolves on a terminal; no colour when standard output is not a terminal, unless forced |
| `height` | the view's measured height at `width`; else `LINES`; else 24 |
| `theme` | `Theme.Dark` |

### Colour and the environment

Print mode follows the conventions every modern CLI does:

- `NO_COLOR` set to any non-empty value turns colour off. Text attributes —
  bold, dim, underline — are still written, as [no-color.org](https://no-color.org)
  asks.
- `FORCE_COLOR` forces colour on, even into a pipe: `1`, `2` and `3` pick sixteen
  colours, the 256 palette and true colour; any other value forces the terminal's
  own capability; `FORCE_COLOR=0` turns colour off. `CLICOLOR_FORCE` is honoured
  the same way.
- `TERM=dumb` means no escape sequences at all.
- Output that is not a terminal — a file, a pipe into `less` or `grep` — gets **no**
  colour unless one of the force variables says otherwise. `TERM` is inherited by a
  piped process, so trusting it there would fill the file with escape sequences.

With colour off and only colour-styled content, the output contains no escape
sequence at all: `NO_COLOR=1 mytool > report.txt` leaves plain text behind.

### Raw widgets and styled text

A widget that is not wrapped in an element prints at the size you give it, and a
`Text` prints one row per line:

```scala
Console.print(statusWidget, Size(40, 3))          // any `Widget` value
Console.printLines(markup"[bold]done[/] in [green]1.2s[/]")
```

To get the text without writing it — for a log line, a message, a test —
`Console.render(view, width, colors)` returns the same string `print` writes, minus
the trailing newline. `Console.renderWidget(widget, size, colors)` and
`Console.renderLines(text, colors)` are the counterparts of the other two.

`Console` is callable from any thread: it registers no render loop and writes no
signal. It is not interactive or animated — focus is drawn on the first focusable
element, an animated widget renders at whatever position it computes for the time it
is handed, and reading a `Signal` subscribes nothing.

:::note `Console` shadows `scala.Console`
Under `import io.worxbend.tui.dsl.*` the name `Console` is glyphora's. Code that
wrote `Console.err.println(...)` with that import writes `scala.Console.err` or
`System.err` instead.
:::

## Run a TuiApp without a terminal

By default a `TuiApp` started without a terminal refuses to run: it prints
`glyphora: terminal not supported: …` on standard error and exits 1. That stays the
default — it is what a user who piped a full-screen app by mistake needs to see, and
what CI's smoke test of every example asserts.

An app that has something useful to say without a terminal opts in through its
configuration:

```scala
object Main extends TuiApp:
  override def config: RunnerConfig =
    RunnerConfig().withNonInteractive(NonInteractivePolicy.PrintFinalFrame)

  def view(using ReactiveScope, Theme): Element = dashboard()
```

The policy applies when standard output is not a terminal, or when `CI` is set (to
anything but empty, `0` or `false`). The app then runs once without a terminal:
`onStart` runs, the first frame is composed at `COLUMNS`×`LINES` (80×24 when unset),
and the run ends the first time the loop would wait for a key — none is ever going to
arrive — so an app that only exits on `q` still exits, and exits 0. Background work
and ticks are not waited for: what is printed is the app's initial state plus
whatever `onStart` set synchronously.

| `NonInteractivePolicy` | What a run without a terminal does |
|---|---|
| `Fail` | Today's behaviour, and the default: `terminal not supported`, exit 1. |
| `PrintFinalFrame` | Writes the last frame the run composed, styled per the colour rules above. |
| `PrintEveryFrame` | Writes every frame the run composed, in order. |
| `Plain` | Writes the text rows of each frame with no styling, identical consecutive frames once — for a screen reader, a log or `grep`. |

While a no-TTY run is printing, the app's own `println` goes straight to standard
output instead of being captured above the UI, because there is no UI for it to land
above.

### Deciding for yourself

`TuiApp.printTarget` is the seam the policy consults. Override it to force the
interactive path regardless of the environment (`None`), or to capture what a no-TTY
run prints in a test:

```scala
val out = StringBuilder()
val app = new MyApp:
  override protected def printTarget: Option[PrintTarget] =
    Some(PrintTarget(Size(60, 10), ColorDepth.NoColor, text => out.append(text)))
assert(app.run() == Right(()))
assert(out.result().contains("Ready"))
```

`PrintTarget.detect()` is what the default answers when the process is
non-interactive; `Console.isNonInteractive()` is the check it makes.

## Testing printed output

`Console.render` is a pure function from a view to a string, so a test asserts on it
directly — no `Pilot` needed. For whole-app behaviour without a terminal, override
`printTarget` as above. Both inject everything they read: `Console.detectedColors(env,
terminal)`, `Console.detectedWidth(env, terminalWidth)` and
`Console.isNonInteractive(env, terminal)` take the environment and the "is standard
output a terminal" answer as parameters, so a test never depends on how its JVM was
started.

Next: ask questions before or after a print with [Prompts](./prompts), drive whole apps
headlessly in [Testing](./testing), and ship the result as one binary with
[Native image](./native-image).
