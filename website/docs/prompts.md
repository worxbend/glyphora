---
title: Prompts and progress
description: Ask one-shot questions from a command-line program, run work under a spinner, and show multi-task progress — inline, without taking over the terminal.
---

# Prompts and progress

Not every program that talks to a terminal is a full-screen app. An installer asks a
few questions, a deploy tool wants a yes or no, a build shows a few bars and then gives
the prompt back. `Prompt` is that layer: each call asks one question in a small strip
at the bottom of the terminal, returns the answer, and leaves one summary line behind
in the scrollback, the way `git` leaves its output.

```scala
import io.worxbend.tui.dsl.*

enum Tier:
  case Free, Pro, Team

@main def setup(): Unit =
  val name = Prompt.text("Project name", validate = Prompt.required)
  val tier = Prompt.select("Tier", Tier.values.toSeq)
  val ok   = Prompt.confirm("Create it?", default = true)
  if ok.contains(true) then
    Prompt.task("Scaffolding") { log =>
      log("writing build.mill")
      log("writing src/Main.scala")
    }
```

The terminal is left like this:

```text
? Project name glyphora
? Tier Pro
? Create it? yes
✓ Scaffolding
```

Every prompt returns an `Option`: `Some(answer)`, or `None` when the user pressed
`Ctrl-C` (or the input ended, when the answers come from a pipe). Nothing throws on a
cancel, so `for`-comprehensions over several prompts stop at the first `None`.

## The prompts

| Call | Answers | Keys |
|---|---|---|
| `Prompt.text(label, default, validate)` | `Option[String]` | typing and the editing chords; Enter |
| `Prompt.password(label, validate)` | `Option[String]` | as `text`, input hidden |
| `Prompt.confirm(label, default)` | `Option[Boolean]` | `y`/`n` answer at once; Enter takes the default |
| `Prompt.select(label, options, render)` | `Option[A]` | Up/Down or `k`/`j`; Enter |
| `Prompt.multiSelect(label, options, render, preselected, min)` | `Option[Seq[A]]` | Up/Down; Space ticks; Enter |
| `Prompt.number(label, default, min, max)` | `Option[Long]` | as `text` |
| `Prompt.path(label, default, base, mustExist)` | `Option[Path]` | as `text`; Tab completes |
| `Prompt.form[A]` | `Option[A]` | one field per step; `Ctrl-U` goes back |
| `Prompt.task(label)(log => work)` | `Option[A]` | `Ctrl-C` cancels |
| `Prompt.loader(label, items)(f)` | `Option[Seq[B]]` | `Ctrl-C` cancels |

`validate` returns `Left(message)` to reject what was typed; the message is shown under
the field and the prompt stays open. `Prompt.required` is the validator for "not blank".
An empty `text` answer is its `default`.

`select` and `multiSelect` take any values and a `render` to show them, so an enum or a
case class is chosen directly rather than through its label. `path` completes against
the directory being typed: one match fills in, several fill in their common prefix and
are listed under the field.

The summary for a `password` is a fixed `********`, never the secret's length.

## A form, one field per step

`Prompt.form[A]` asks for every field of a case class in declaration order, using the
same derivation as [`deriveForm`](./forms-and-validation): a `Boolean` field is a
`confirm`, an enum a `select`, everything else typed text checked by the field type's
own parser. A field that does not parse keeps the form on that field.

```scala
final case class Service(name: String, port: Int, tls: Boolean)

val service: Option[Service] = Prompt.form[Service]
```

`Ctrl-U` returns to the previous field with its answer still in it. To check the
assembled value as well, pass the spec and its validators to `Prompt.formOf`; a failing
check sends the user back to its field:

```scala
import io.worxbend.tui.macros.deriveForm

val spec = deriveForm[Service]
val port = spec.field(_.port).validate(p => p > 0 && p < 65536, "not a port")
val service = Prompt.formOf(spec, port)
```

## Work under a spinner

`Prompt.task` runs its body on a thread of its own, under a spinner, with the last few
lines it logs scrolling underneath. When the work ends the strip collapses to one line:
`✓ label`, or `✗ label <message>` when the work threw, in which case the throwable is
rethrown after the line is drawn. Anything the work prints to standard output lands in
the log rather than over the strip.

`Ctrl-C` answers `None` straight away. The work's thread is interrupted, not waited
for, so blocking work should respond to interruption.

`Prompt.loader` is the same with a progress row instead of the log: it runs `f` over
every item and draws the bar, the percentage and the ETA as it goes.

```scala
val sizes: Option[Seq[Long]] = Prompt.loader("Hashing", files)(file => hash(file))
```

## Answers for tests and CI

A `PromptSession` decides where answers come from. Every prompt takes one as a `using`
parameter and finds the terminal one when the caller names none. To pre-supply the
answers, bring your own:

```scala
given PromptSession = Prompt.answers(Seq("glyphora", "Pro", "y"))
```

Answers are consumed in order — one per prompt, one per field of a form — and parsed
exactly as a typed answer would be: an option by its label or its 1-based number, `y` or
`n`, a comma-separated list for `multiSelect`, an empty string for the default. Each
summary line is printed as if the user had answered. An answer the prompt rejects, or a
prompt with no answer left, throws `IllegalArgumentException`: a script that no longer
matches the program is a bug, and a CI job that hangs on a question is worse.

`session.transcript` holds every summary line the session produced, which is what a test
asserts on:

```scala
val session = Prompt.answers(Seq("alpha"), echo = _ => ())
assert(Prompt.text("Name")(using session) == Some("alpha"))
assert(session.transcript == Seq("? Name alpha"))
```

To drive the interactive prompt itself, run it on a `HeadlessBackend` under
[`Pilot`](./testing) with `PromptSession.on(backend)` and send it keys.

## Without a terminal

When there is nothing to draw on, prompts degrade to plain line-based questions instead
of failing: `? Name: ` is written to standard output and a line is read from standard
input. That happens when standard output or standard input is not a terminal, when `CI`
is set, or when the terminal cannot be opened (`TERM=dumb`). A terminal that fails while
a prompt is running falls back the same way: the questions are asked again as lines, and
a `Prompt.task` already under way is waited for rather than run twice. Only a bug in the
app's own code — a validator or a view that throws — escapes, as the exception it threw.
`PromptSession.terminal`
takes a `fallbackWhen` to decide differently — the same idea as Laravel Prompts'
`fallbackWhen` — and `PromptSession.plain(read, write)` builds the line shape over any
reader and writer.

```bash
printf 'glyphora\n2\ny\n' | my-installer
```

reads its three answers from the pipe. See the [FAQ](./faq#can-prompts-read-from-the-terminal-when-stdin-is-a-pipe)
for why a piped standard input is never bypassed in favour of `/dev/tty`.

## Multi-task progress

`multiProgress(tasks, columns)` is the element behind `loader`, for an app that tracks
several pieces of work at once. Each row is one caller-owned `ProgressTask`, each column
one `ProgressColumn`:

```scala
val fetch = ProgressTask("fetch sources", Some(120L))
val build = ProgressTask("build", Some(8L))

def view(using ReactiveScope, Theme): Element =
  multiProgress(Seq(fetch, build), ProgressColumn.Default :+ ProgressColumn.Elapsed)
```

```text
fetch sources █████░░░░░░░░░░░░  30% 00:28 00:12
build         ████████████░░░░░  75% 00:04 00:12
```

| Column | Shows |
|---|---|
| `ProgressColumn.Description` | the task's label |
| `ProgressColumn.Bar(width)` | the bar: `width` cells, or a share of what the text columns leave |
| `ProgressColumn.Percentage` | ` 40%` |
| `ProgressColumn.Eta` | time left at the average rate, `mm:ss` |
| `ProgressColumn.Rate(bytes)` | `12.5 it/s`, or `1.2 MiB/s` |
| `ProgressColumn.Bytes` | `1.0 KiB/4.0 KiB` |
| `ProgressColumn.Elapsed` | time since the start, frozen at the finish |
| `ProgressColumn.Remaining` | `60 left` |
| `ProgressColumn.SpinnerColumn(preset, done)` | a spinner, then `✓` |

`ProgressColumn.Default` is description, bar, percentage and ETA. Text columns are as
wide as their widest value, measured in terminal columns, so a CJK label lines up with
an ASCII one. A value that cannot be known yet prints `-` or `--:--`, never a zero.

`ProgressTask` holds the label, the optional total, the completed count and its start
and finish stamps; `advance(n)`, `update(n)` and `finish()` move it. Like every widget
state it is render-thread-only, and a write to it repaints the element on its own, so a
worker reports progress by posting `task.advance()` back to the render thread from an
`Async` continuation. Times come from the task's own `clock`, the monotonic system clock
by default; a test passes `() => manualClock.elapsed` and the ETA becomes exact.
