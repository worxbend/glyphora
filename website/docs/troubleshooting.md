---
title: Troubleshooting
description: Diagnose terminal startup, stale views, focus, input, animation, Unicode, native-image, and cleanup problems.
---

# Troubleshooting

Start here when an app does not render, input is missing, or native behavior differs
from the JVM. Work from the first relevant symptom; most problems come from TTY
availability, state lifetime, tracked reads, or event consumption.

## terminal not supported at startup

The app exits immediately with status 1 and one line on standard error:

```text
glyphora: terminal not supported: dumb terminal (no TTY attached)
```

glyphora needs a controlling TTY. An IDE output panel, a redirected pipe, or a headless
CI process has none, so the backend deliberately returns `BackendError.UnsupportedTerminal`
rather than entering raw mode on something that cannot leave it — a half-configured
terminal is far harder to recover from than a refusal. Run the app in a terminal:

```bash
./mill examples.showcase.run
```

For CI, inject `HeadlessBackend`; see [Testing](./testing). To make the app print its
first screen and exit 0 instead of refusing, opt into a `NonInteractivePolicy`; see
[Print mode and no-TTY runs](./print-mode).

## The view does not update

Check these in order:

1. The view must read reactive state with `.get`, not `.peek`.
2. A signal must live outside `view`; recreating it resets the value every pass.
3. A signal only notifies when the new value is not equal (`!=`) to the old one.
4. Replace immutable collections instead of mutating one in place.
5. Third-party callbacks must write on the render thread.
6. Caller-owned widget state — `ListState`, `TextInputState`, `TextAreaState`,
   `TreeState`, `DataTableState`, `DirectoryTreeState`, `ScrollViewState`, `LogState`,
   `MenuState` — is a plain mutable object the reactive layer cannot see. Writing to
   one changes the next frame but does not ask for one. Pair the mutation with a
   `Signal` write, or call `requestRedraw()` — see [A background result updates nothing
   on screen](#a-background-result-updates-nothing-on-screen).

```scala
private val rows = Signal(Vector.empty[Row])

def view(using ReactiveScope, Theme) = renderRows(rows.get)
def append(row: Row) = rows.update(_ :+ row)
```

## Input edits, then resets

`TextInputState`, `TextAreaState`, `ListState`, and other interactive state objects
must be created once on the app or owning screen:

```scala
private val inputState = TextInputState()

def view(using ReactiveScope, Theme) = input(inputState)
```

Creating `TextInputState()` in `view` replaces it with an empty editor on the next
redraw.

## A background result updates nothing on screen

A `Signal` write schedules its own redraw. Caller-owned widget state — `ListState`,
`TextInputState`, `DataTableState`, `LogState` — is a plain mutable object that
nothing subscribes to, so mutating it outside an event handler changes the *next*
frame without asking for one, and the screen sits on the old contents until a key is
pressed. Call `requestRedraw()` after the mutation:

```scala
Async.run(fetchRows()) { rows =>
  tableState.rows = rows
  requestRedraw()
}
```

## Input or mouse events do not arrive

- Confirm the element is focusable and press `Tab` to move focus.
- Return `false` from a custom handler when the event should bubble.
- A user handler that always returns `true` can block built-in input/list behavior.
- Keep global behavior in `KeyBindings`; focused handlers run before them.
- Do not parse escape sequences in the app; `JLine3Backend` owns decoding.
- `TuiApp` enables backend interaction modes. A custom runner owns that lifecycle.

See [Mouse & focus](./mouse) for the exact routing order.

## Focus jumps after conditional rendering

Focus is positional unless an element has a stable key. Add `.key("unique-name")` to
interactive controls that can move when branches appear or disappear. Keys must be
unique among focusable elements in the current tree.

## Toasts never disappear or effects do not animate

Both advance on ticks. Configure a cadence:

```scala
import scala.concurrent.duration.*

override def config = RunnerConfig(tickRate = Some(100.millis))
```

A splash supplies ticks automatically to an app that configured none, but only for as
long as the intro is on screen; normal toasts and `runEffect` calls do not.

## Text alignment breaks with emoji or CJK

Use `CharWidth` for custom width calculations. Java/Scala string length counts
UTF-16 code units, not terminal cells. Built-in widgets already use `CharWidth` for
clipping, wrapping, and cursor placement.

If built-in widgets agree but the emulator still looks wrong, check the emulator's
ambiguous/emoji width policy and font fallback. An application cannot force an
emulator to assign a particular width to a new emoji.

## A Signal update throws off the render thread

Key, mouse, binding, and tick handlers are already safe. Futures, HTTP clients, and
other callbacks must hop back before writing.

Prefer the structured helper:

```scala
Async.runCatching(fetch()) {
  case Right(value) => data.set(value)
  case Left(error)  => failure.set(Some(error.getMessage))
}
```

Its completion already runs on the render thread. For an externally owned callback,
use `RenderThread.runOnRenderThread`.

## A panel or child looks empty

Borders consume space. A panel needs at least three rows to leave one inner row. In
a `row`, child constraints allocate width; in a `column`, they allocate height.

Temporarily replace nested content with labeled `text` elements and inspect each
container's `.length`, `.percent`, and `.fill` constraints from the outside in.

## DataTable opens the wrong row

`DataTableState.selected` indexes the filtered and sorted view, not the original row
sequence. Resolve the selection with:

```scala
state.selected.flatMap(table.visibleRows(state).lift)
```

A table whose rows are `KeyedRow`s usually wants the record instead of the row:
`table.selectedKey(state)` answers the selected row's key directly.

## Native-image compilation fails

First confirm the JVM build and tests:

```bash
./mill app.compile
./mill app.test
./mill app.nativeImage
```

Keep `--no-fallback` enabled. Inspect your own dependencies for runtime reflection,
dynamic class loading, resources, JNI, and proxies. glyphora's derivation APIs use
compile-time Scala 3 macros and require no reflection config. See
[Native binaries](./native-image).

## The screen is corrupted after a crash

It should not be. glyphora owns the terminal's signal handling instead of leaving it
to JLine's defaults, so every ordinary way of ending a TUI restores the terminal.
Measured on a real PTY with `examples/hello-world`:

| How the app ended | termios | alternate screen | cursor | paste / focus / kitty modes |
|---|:---:|:---:|:---:|:---:|
| normal return (`q`) | ✅ | ✅ | ✅ | ✅ |
| uncaught exception from `view` | ✅ | ✅ | ✅ | ✅ |
| `Ctrl+C` (SIGINT) | ✅ | ✅ | ✅ | ✅ |
| `SIGQUIT` | ✅ | ✅ | ✅ | ✅ |
| `SIGTERM` | ✅ | ✅ | ✅ | ✅ |
| `SIGHUP` (window closed) | ✅ | ✅ | ✅ | ✅ |
| `System.exit` from a handler | ✅ | ✅ | ✅ | ✅ |
| `SIGKILL` | ❌ | ❌ | ❌ | ❌ |

`Ctrl+C` arrives as `Event.Interrupt` and quits through the same teardown as any other
exit; override `TuiApp.onInterrupt()` and return `true` to intercept it (to confirm, or
to cancel in-flight work) instead. `TuiApp.onStop()` runs on this path too — and on
every other exit path — so teardown does not have to be hung off `onInterrupt`. Signal-terminated exits additionally go through a
JVM shutdown hook that writes the mode-reset sequences straight to the stdout
descriptor, so restoration does not depend on the backend still being intact.

`SIGKILL` cannot be caught by anything; after `kill -9`, run `reset`.

### The app pins a CPU core when its input is piped in

It no longer does. When standard input reaches end of file — input redirected from a file
that ran out (`my-app < script.txt`), or a terminal closed while the process kept running —
the backend now reports `Event.EndOfInput` instead of the same "nothing available right now"
answer an ordinary poll timeout gives. The runner offers that event to the application (so it
can save its work) and then stops the loop and restores the terminal, whatever the handler
answers: unlike `Ctrl+C`, the end of the stream is not a request an application may decline,
because there is no input left to deliver. Before this, the loop could not tell the two apart
and went straight back to reading a dead stream, spinning at 100% CPU until it was killed.

Set `GLYPHORA_DEBUG=1` to have failed teardown steps report themselves on stderr rather
than being swallowed.

When launching an external interactive program, use `TuiApp.suspend { ... }` so the
terminal is deliberately handed over and restored.

## What the terminal supports

When glyphora enters raw mode it asks the terminal about itself once, and then acts on
the answer. The queries go out together — a DECRQM (`CSI ? mode $ p`) each for
synchronised output (2026), bracketed paste (2004), focus reporting (1004),
colour-scheme reports (2031), in-band resize (2048) and grapheme widths (2027), a
`CSI ? u` for the kitty keyboard protocol, an OSC 11 `?` for the background colour, a
`CSI ? 996 n` for the terminal's own light/dark verdict, and a primary device
attributes request (`CSI c`) last. DA1 is the fence: terminals answer in the order the
queries arrived, so once its reply is back, anything still unanswered was never going
to be answered.

Each feature ends up in one of three states, and the third is the interesting one:

| State | What it means | What glyphora does |
|---|---|---|
| `Support.Yes` | the terminal said it has the mode | use it |
| `Support.No` | the terminal said the mode is not recognised | do not send it at all |
| `Support.Unknown` | the terminal said nothing | **use it anyway** |

Silence has to mean "carry on". Almost every terminal ignores a query it does not
implement rather than answering it, so treating silence as a denial would switch
synchronised output and bracketed paste off on the majority of terminals that support
them perfectly well. Only an explicit denial turns anything off, which is why adding
the probe changed nothing for a terminal that answers nothing. Mode 2048 is the one
exception: in-band resize reports are requested only on an explicit `Yes`, because the
signal-driven resize they supplement already works everywhere and there is nothing to
gain from asking a terminal that never said it understands the report.

The colour answers are kept rather than acted on: `TerminalCapabilities.backgroundColor`
is the OSC 11 colour, `reportedColorScheme` the `CSI ? 997 ; n` verdict, and
`colorScheme` combines them (`Light`, `Dark`, or `Unknown` when neither came back) —
which is what `Theme.auto` resolves against. A terminal that implements mode 2031 then
sends the same `CSI ? 997 ; n` report whenever the user switches system theme, and the
app follows.

Three features cannot be probed at all — pointer shapes (OSC 22), desktop notifications
(OSC 9, 777, 99) and tab or taskbar progress (OSC 9;4). They stay `Unknown`, and are
written, unless the environment rules them out: under tmux or GNU screen (`TMUX` or
`STY` set) the multiplexer swallows an OSC string it does not know, so `pointerShapes`,
`desktopNotifications` and `taskbarProgress` are `Support.No` there and nothing is
written. Which notification dialect is used is also decided from the environment:
OSC 9 under iTerm2 (`TERM_PROGRAM=iTerm.app`), OSC 99 under kitty (`TERM=xterm-kitty`
or `KITTY_WINDOW_ID`), OSC 777 otherwise — exactly one, so a terminal that speaks two
of them does not show the message twice.

The whole round trip is bounded at a tenth of a second, paid once at start-up and only
by a terminal that answers nothing at all. If even that is unwanted — a CI harness, a
terminal where a start-up read causes trouble — set `GLYPHORA_NO_CAPABILITY_PROBE` to
any non-empty value and it is skipped entirely. Skipping is safe by construction: it
produces exactly the "nothing established" answer a silent terminal would, so every
feature stays on.

`Backend.capabilities` reports what was established, for an application that wants to
show it or log it. A backend with no device to ask — `HeadlessBackend` — always reports
that nothing was established.

## Borders and spinners come out as boxes or question marks

The terminal's font has no glyph for what was drawn. Set `GLYPHORA_ASCII=1` in the
environment and run again: with `theme = Theme.detected()` the whole tree falls back to
`+`, `-`, `|` borders and a `|/-\` spinner. If that fixes it, the terminal cannot draw
the Unicode set and the application should be shipping `Theme.detected()` — see
[Degrading to ASCII](./unicode-and-accessibility#degrading-to-ascii) for what is
detected and what follows the ceiling.

## Debug output destroys the live UI

It no longer should, under `TuiApp`: since 0.20.0 `System.out`/`System.err` and Scala's
`println` are captured for the duration of the run and each line is written into the
scrollback above the UI through `printAbove`, with anything still buffered flushed to the
real streams once the terminal is restored. See
[Where `println` goes](./app-shell#where-println-goes) for the `CaptureStdio` policy —
`Off` to leave the streams alone, `Console(ring)` to collect them for a `logConsole`.

If output still lands on the frame, it is reaching the terminal by another path: a
`PrintStream` the code captured before the run started (`val out = System.out` in a
constructor), a native library writing to file descriptor 1 directly, or a bare
`TerminalRunner`, which captures nothing unless its `RunnerConfig` says
`withCaptureStdio(...)`. For output the app wants to be durable, `printAbove(...)` is
still the explicit way; when the line needs styling — a coloured log level, a bold
prefix — use `insertBefore(height) { (area, buffer) => ... }`, which inserts a drawn
block instead of stripped text.

## Still stuck?

Search [existing issues](https://github.com/worxbend/glyphora/issues), then
open a minimal reproduction with:

- glyphora, Scala, JVM, and OS versions;
- terminal emulator and shell;
- smallest terminal size that reproduces it;
- whether `HeadlessBackend` reproduces it;
- relevant stack trace and key/mouse sequence;
- JVM/native-image difference, if any.
