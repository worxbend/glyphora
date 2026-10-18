---
title: State & signals
description: Learn glyphora's tracked reactive state, computed values, conditional dependencies, and render-thread rules.
---

# State & signals

glyphora state is intentionally small: `Signal[A]` stores a mutable value,
`Computed[A]` derives one, and `ReactiveScope` records what a view reads. That is
enough to update a terminal UI without reducers, message plumbing, or manual redraw
calls.

## Read, write, redraw

```scala
private val count = Signal(0)

def view(using ReactiveScope, Theme): Element =
  text(s"Count: ${count.get}")

// key/mouse/tick handler
count.update(_ + 1)
```

While `view` runs, `count.get` subscribes its root reactive scope. `update` changes
the value, marks that scope stale, and wakes the runtime. On the next pass the view
reads the new value and widgets render a new buffer.

Setting a signal to an equal value does not notify dependents. Equality is `==`, with
one deliberate exception: `Double` and `Float` are compared by total order, so a
signal holding `0.0` *does* notice a set to `-0.0` (the sign carries direction in a
scroll or velocity delta) and a signal holding `NaN` does *not* repaint on every
rewrite of `NaN`. Both are IEEE-754 artefacts of using `==` as a change flag rather
than anything a caller meant.

Which comparison runs is not hard-coded: a `Signal` consults the `SignalEquality[A]`
given in scope at its creation, and the two behaviours above are just the companion's
defaults. A `given SignalEquality[A]` in lexical or imported scope outranks them, so
a type whose "same value" means something narrower — a case class compared on a subset
of its fields, say — can declare it once and every signal of that type picks it up:

```scala
given SignalEquality[Reading] with
  def unchanged(next: Reading, current: Reading): Boolean =
    next.station == current.station && next.value == current.value
```

The instance is consulted inline on the render thread on every `set`, so it must be
stateless and pure — an implementation that allocates, blocks, or reads mutable state
outside its two arguments breaks the signal's contract, not just its performance.

The one case no comparison can catch is mutating a value in place and setting the same
instance back — see below.

## Signal operations

| Operation | Tracks? | Purpose |
|---|---:|---|
| `signal.get` | yes | read inside `view` or a `Computed` and subscribe |
| `signal.peek` | no | inspect current state in a handler or service without adding a dependency |
| `signal.set(value)` | — | replace the value and invalidate when it changed |
| `signal.update(f)` | — | replace the value using its current value |

Use immutable values inside signals so updates are obvious:

```scala
private val jobs = Signal(Vector.empty[Job])

def add(job: Job): Unit =
  jobs.update(_ :+ job)

def remove(id: JobId): Unit =
  jobs.update(_.filterNot(_.id == id))
```

Mutating a collection in place and setting the same reference can bypass equality
change detection. Prefer a new `Vector`, `Map`, case class, or other immutable value.

## Derive state with Computed

`Computed` values are lazy and cached. They recompute on the next read after a
dependency changes:

```scala
private val query = Signal("")
private val jobs = Signal(Vector.empty[Job])

private val visibleJobs = Computed {
  val needle = query.get.trim.toLowerCase
  if needle.isEmpty then jobs.get
  else jobs.get.filter(_.name.toLowerCase.contains(needle))
}

def view(using ReactiveScope, Theme): Element =
  column(
    text(s"Filter: ${query.get}").dim,
    text(s"${visibleJobs.get.size} matching jobs"),
    jobTable(visibleJobs.get),
  )
```

A `Computed` is never created per evaluation. One created inside a `view` body
subscribes to its dependencies again on every frame and nothing releases it, and
calling `.dispose()` on it in that same body does not repair that — it breaks two more
things. Dispose invalidates the dependents of the computed, and the dependent here is
the scope drawing this very frame, so every frame owes the next one and an idle app
repaints at the poll rate for ever. And the disposing scope ends the frame subscribed to
nothing — the computed was its only edge to the source — so a real `set` on that signal
reaches nobody; the continuous redraw is what hides it. Hoist long-lived computed values
out beside your signals and dispose them where their owner goes away (`Screen.onLeave`,
`TuiApp.onStop`); for a per-frame derivation use `map`, below, or read the signal
inline — neither needs disposing.

`dispose` detaches; it does not close. Anything derived from a disposed computed is
marked stale so it recomputes rather than freezing at its last value, and reading the
disposed computed again simply re-attaches it.

### Release a view's tracking scope

A caller-owned `ReactiveScope.generational(...)` tracks successive evaluations of a
view. Call `beginGeneration()` before each evaluation and `dispose()` when that
owner stops, on its render thread **before the runner unregisters**. Disposal
releases both generations of subscriptions and is idempotent but terminal:
subsequent tracked reads and `beginGeneration()` throw. This differs from the
re-attachable `Computed.dispose()` above.

```scala
var invalidated = false
val count = Signal(0)
val scope = ReactiveScope.generational(() => invalidated = true)
// On the owning render thread, for each evaluation:
scope.beginGeneration()
val current = count.get(using scope)
// On that same owner, during teardown:
scope.dispose()
```

`TuiApp` owns and disposes its root scope automatically, after screen `onLeave`
callbacks and app `onStop`, even when a callback fails. Do not dispose the scope
passed to your `view`; only release scopes you created yourself.

## Keep one state value in a Store

`Store` is sugar over a `Signal` and its `Computed`s for the app whose state is one
value with pure transitions — the Elm shape, without a second reactive system:

```scala
final case class Todo(items: Vector[String], filter: String)

private val store = Store(Todo(Vector.empty, ""), history = 50)
private val shown = store.select(s => s.items.filter(_.contains(s.filter)))

def view(using ReactiveScope, Theme): Element =
  column(
    list(shown.get, listState),
    row(button("add")(store.dispatch(s => s.copy(items = s.items :+ "write docs"))), button("undo")(store.undo())),
  )
```

`dispatch(action)` applies the transition and publishes the result; a transition that
produces an equal state notifies nobody and records nothing. `select(f)` is a long-lived
`Computed` — create it once beside the store, as above, and dispose it with the
store's owner. `history = n` keeps up to `n` previous states for `undo()`/`redo()`,
each returning whether anything happened (`canUndo`/`canRedo` ask without moving); a
`dispatch` after an `undo` discards the redo branch. `store.get`, `store.peek` and the
read-only `store.state` are the signal's reads, under the same render-thread rules.

## Persist a signal across restarts

`persisted(path, initial)` is a `Signal` loaded from a file when it is created and
written back, off the render thread, every time it changes:

```scala
final case class Prefs(theme: String, sidebar: Boolean, recent: List[String])

private val prefs = persisted(configDir.resolve("prefs.txt"), Prefs("dark", sidebar = true, Nil))
```

The file format comes from the `Codec` for the value's type. One is derived for any
case class, enum or sealed hierarchy built from `String`, `Int`, `Long`, `Double`,
`Boolean`, `Option`, `Seq`, `List`, `Vector` and `Set`; a type the derivation cannot see
through is taught with one line next to it — `given Codec[Email] = Codec.atom[Email](_.value, Email.parse)` —
and a hand-written given always wins over the derivation. The text is a compact
length-prefixed form made for round-tripping, not for editing by hand or for exchange:
a type whose fields change simply decodes its old file as a failure, and a missing,
unreadable or undecodable file yields `initial`, so a stale file is never a reason the
app cannot start.

Writes coalesce — a burst of changes leaves at most one write in flight and the newest
value pending — and each one lands through an atomic rename, so the file always holds a
complete state. Create the signal as a field or in `onStart`, never inside `view`, and
treat the file as the signal's own: two signals on one path overwrite each other. A write
failure is reported like a failing `Async.run`, as `RunnerError.QueuedTask` when the app
exits; the in-memory value is unaffected. Under `tui-runtime` alone the same thing is
`Persisted.signal(path, initial)(encode, decode)` with the two halves of any text
format you like.

## Derive cheaply with map

`signal.map(f)` returns a `Derived[B]`: a transparent view that applies `f` on every
read and subscribes to nothing of its own. Reading one inside `view` subscribes the
view to the underlying signal, not to a per-frame intermediate, so a `Derived` is
safe to create inline and there is nothing to dispose:

```scala
def view(using ReactiveScope, Theme): Element =
  text(count.map(n => s"$n items").get)
```

The trade is that `f` re-runs on each read. When the derivation is expensive enough
to be worth caching, use a `Computed` — and give it an owner, as above.

## Conditional dependencies stay accurate

Dependencies are rebuilt every time a computation runs:

```scala
private val useRemote = Signal(false)
private val localRows = Signal(Vector.empty[Row])
private val remoteRows = Signal(Vector.empty[Row])

private val activeRows = Computed {
  if useRemote.get then remoteRows.get else localRows.get
}
```

When `useRemote` is false, changes to `remoteRows` do not invalidate `activeRows`.
After the branch switches, the old subscription is removed and the remote one is
added. There is no dependency list to maintain.

## Keep view pure and cheap

`view` may run many times. It should describe current UI, not perform work:

```scala
// Good: read state and compose elements.
def view(using ReactiveScope, Theme): Element =
  report.get match
    case Some(value) => reportPanel(value)
    case None        => text("No report loaded").dim

// Avoid inside view: HTTP calls, file writes, sleeps, starting Futures,
// mutating signals, or constructing long-lived resources.
```

Start side effects from `onStart`, a key/mouse handler, `onTick`, an app service, or
an `Async` callback. See [Async work & timers](./async-and-timers).

## The render-thread rule

Once a runner is active, `Signal.set` and `Signal.update` must run on its render
thread. This guarantees deterministic ordering between event handling, state
changes, focus, effects, and redraws.

Already safe:

- `.onKey` / `.onKeyEvent` handlers;
- `.onMouseEvent` handlers;
- `KeyBindings` actions;
- `onStart()`, `onTick()`, `onResize(size)`, `onStop()`;
- completion handlers passed to `Async.run` and `Async.runCatching`.

Callbacks owned by another thread must hop back. Capture the owner during `onStart`
before registering the callback, so multiple runners never make delivery ambiguous:

```scala
val owner = RenderThread.capture()
socket.onMessage { payload =>
  val _ = owner.execute {
    messages.update(_ :+ payload)
  }
}
```

Tracked reactive graphs belong to one live runner, not any registered render thread.
Foreign tracked reads, writes and disposal are rejected, including from a nested
runner. Unbound graphs bind on owner access and may rebind after retirement; `peek`
remains available for cross-thread observation without subscribing. `execute` returns
false for a retired owner; an accepted queued callback may still be discarded at
shutdown. Release external subscriptions when their owning feature ends.

The guard is a no-op when no runner is registered. Plain unit tests can construct,
read, and update signals without bootstrapping a runtime.

## Runtime theme switching

Themes demonstrate the whole model: one signal chooses a theme, `view` tracks it,
and a key action changes it.

```scala
private val themes = Vector(Theme.Dark, Theme.Light, Theme.HighContrast)
private val themeIndex = Signal(0)

override def theme: Theme = themes(themeIndex.peek)

override def bindings = KeyBindings(
  binding("ctrl+t", "switch theme") {
    themeIndex.update(i => (i + 1) % themes.size)
  }
)

def view(using ReactiveScope, Theme): Element =
  val _ = themeIndex.get // tracked read requests a new themed tree
  scaffold(statusBar = Some(statusBar(bindings)))(content)
```

There is no `given Theme` to declare. `view` receives the app's own `theme` as a
`using` parameter, and every themed helper it calls — `statusBar`, `topBar`, `panel`,
`markdown` — takes it from there, including helpers written as separate methods, as
long as those carry `(using Theme)` too.

`theme` itself uses `.peek`, and the tracked `themeIndex.get` in `view` is what owns
invalidation. That split matters: `TuiApp.theme` is read once per frame *outside* the
tracking scope, so changing what it returns does not by itself schedule a frame. If the
theme switch is not on a key binding — a binding that consumes the key already earns a
repaint — the tracked read is the only thing that makes the new palette appear.

The complete implementation is in the `showcase` example, which switches on `Ctrl+T` and
therefore leaves the tracked read out — keep it in your own app unless every path that
changes the theme is a key binding.

## A practical state checklist

- Put application facts in `Signal`; keep derived facts in `Computed`.
- Read with `.get` in view code and `.peek` in handlers when you do not need to
  create a dependency.
- Keep state values immutable and updates small.
- Create `Computed` values outside `view` (or dispose them when their owner goes
  away); `map` is free to use inline.
- Model async loading and errors as a single enum.
- Marshal third-party callbacks to the render thread before writing.

Next: use state in [Forms & validation](./forms-and-validation), animate it with
[Motion](./motion), or test it through full input cycles in [Testing](./testing).
