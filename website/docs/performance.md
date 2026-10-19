---
title: Performance
description: The benchmarks that measure glyphora's render path, how to run them, and why none of them is a CI gate.
---

# Measure the render path

Every frame a glyphora application draws goes through three steps, and each has a
benchmark of its own:

1. **Composition** — the widgets draw into a `Buffer`. No terminal is involved and no
   escape sequence exists yet. Measured by `RenderLoopBench`.
2. **Diff** — the new buffer is compared against the last flushed one, cell by cell, and
   only the differences survive. Measured by `FramePipelineBench`.
3. **Encode** — the surviving cells are turned into one batched string of ANSI escape
   sequences, which is what is finally written to the terminal. Also measured by
   `FramePipelineBench`.

Both benchmarks share the `Bench` harness published as part of `tui-test`, so their
output has the same shape and two runs diff cleanly in a terminal.

## Run them

```bash
./mill widgets.test.runMain io.worxbend.tui.widgets.RenderLoopBench
./mill terminal.test.runMain io.worxbend.tui.terminal.FramePipelineBench
./mill dsl.test.runMain io.worxbend.tui.dsl.ElementHostBench
```

None prints anything but a table. None asserts anything, and none is run by any
suite — the last section says why.

## What each row means

`RenderLoopBench` composes one dashboard-like frame — a header, two one-row meters, a
sparkline and a body of prose that must be re-wrapped every frame — at four buffer sizes,
twice each:

- **fresh buffer** allocates a `Buffer` per frame, which is what a naive render loop costs.
- **reused buffer** clears one buffer and draws into it again, which is what `RenderThread`
  actually does.

The gap between those two rows is the allocation, so a change claiming to reduce
allocation has somewhere to show it.

`FramePipelineBench` builds two frames that differ in one of three ways and measures the
diff and the encode over each:

- **idle** — nothing moved, the shape of a tick where the application had no news.
- **onecell** — one cell moved, the shape of a cursor blink or a clock updating.
- **full** — everything moved, the shape of the first frame after a resize.

The encode rows are measured at two color depths, because `ColorDepth.TrueColor` and
`ColorDepth.Ansi16` emit different sequences for the same style, and the per-run style
handling is exactly where that shows.

`ElementHostBench` renders one frame of a hundred-node element tree — chrome bars, a keyed
form, meters, a forty-row `scrollView` — through an `ElementHost` (resolve, focus pass,
paint) at two sizes, with per-node **bounds recording** off and on. The gap between the
two rows is what `boundsOf(key)`, the element-tree snapshot and the click-outside backdrop
cost per frame: a wrapper per node plus one bounds entry each. On a loaded 16-core x86-64
machine under glyphora 0.20.0 the gap at 80x24 was indistinguishable from the run-to-run
noise (which was itself about 2x), and at 200x50 it read between +13% and +56% of the
frame with the configurations timed in the order least favourable to recording. That is
why recording is off by default on a real terminal and switched on only by something that
reads it — a headless run, the first `boundsOf` call, a click-outside modal — and why the
number is worth re-measuring on a quiet machine before deciding it is too dear to leave on.

Two further configurations time a tree whose per-frame *walks*, not its paint, dominate:
**wide** is two thousand keyed rows of a label and a button in a `scrollView`, and **lazy**
is the same two thousand rows as a `lazyList`. The pair is the cost of element-level
virtualisation in one line: on a loaded machine the wide tree took about 5–6 ms a frame
and the lazy one about 0.07 ms, because a `lazyList` builds, decorates and paints only the
rows on screen.

### What a frame walks

A frame of an `ElementHost` (and so of every `TuiApp`) walks the element tree twice before
it paints:

1. **Resolve.** The view is evaluated, `responsive` nodes take their branch, each
   `lazyList` builds its window, and — in the same walk — the tab order, the autofocus
   request, the focus groups and traps, and every caller-owned state the tree paints are
   collected. Subtrees the walk changes nothing in are kept as they are, not copied.
2. **Decorate.** Focus has to be reconciled against the whole tab order before any node
   can be told it is the focused one, so the focus cue, the input wrappers and the bounds
   recorder are stamped on in a second walk. It also records the path to the focused
   element, so a key, a key release and a paste go straight down that path instead of
   searching the tree for it.

Until 0.20.0 those were six walks — resolve, tab order, autofocus, focus scopes,
decoration, watched states — and every key event searched the tree once more.
`FramePassSpec` holds the fused walk to exactly the answers the separate walks give.

A tree with a `responsive.local` container query in it pays for one more paint into a
scratch buffer, the layout pre-pass that finds each query's area (see
[Responsive layouts](./responsive#branch-on-the-nodes-own-area)); a tree without one pays
nothing for the feature.

## Reference numbers

Taken on one developer machine so that the *shape* of the table is on record — a 16-core
x86-64 Linux box, JDK 23, glyphora 0.13.0, nothing else running. Your absolute numbers
will differ; what should hold is the relationships (idle and one-cell frames cost about the
same, a full frame costs several times more, encode dominates diff on a full frame).
Predates the 0.14.0 toolchain bump (Scala 3.9, JDK 25, JLine 4); the relationships above
are expected to hold, but the absolute numbers have not been re-captured against it yet.

| Benchmark | ns/op |
| --- | --- |
| compose 80x24, reused buffer | ~117 000 |
| compose 200x50, reused buffer | ~131 000 |
| diff 200x50, idle | ~52 000 |
| diff 200x50, full | ~65 000 |
| encode 200x50, idle, truecolor | ~52 000 |
| encode 200x50, full, truecolor | ~179 000 |

## Measure the running application first

The microbenchmarks above isolate widget composition and diff/encoding. They do not
measure DSL view construction, backend baseline copies, terminal writes, frame observers,
or queued callback latency. The encode rows already include the diff walk: adding a
diff row to an encode row double-counts that work.

Before changing the pipeline, time a representative interaction session on the terminal
where the application runs. Split render-thread wall time into view/layout/paint,
`backend.draw`, queued callbacks, and `onFrame`; report p50/p95 frame duration and the
age of callbacks when they start. Exclude intentional `readEvent` waiting. If draw
is slow, distinguish encoding CPU from output write/flush time. A CPU-only profile
cannot establish that a blocked terminal is cheap.

`RunnerConfig.onFrameTiming` provides elapsed phase measurements without enabling
frame snapshots:

```scala
val config = RunnerConfig(onFrameTiming = Some { (timing: FrameTiming) =>
  recordSample(timing.compositionNanos, timing.drawNanos, timing.observerNanos)
})
```

The callback runs on the render thread after a successful frame. Keep its work short
and bound any samples you retain. Composition includes the size query, buffer reset and
render callback; draw includes the backend's diff, encoding and synchronous writes;
observer time includes snapshot creation and synchronous observation/admission. It does
not measure a `LatestFrameObserver`'s off-thread callback. Cursor placement, queued work,
input and the timing callback itself are excluded; the phase sum is not a loop total.
Disabled timing adds no profiling clock reads. Waiting, scheduling and GC may contribute
to these elapsed measurements.

### Flight Recorder events

Without writing a callback, run the JVM under JDK Flight Recorder and every frame is
recorded with the rest of the process's profile:

```bash
java -XX:StartFlightRecording=filename=run.jfr,settings=profile -jar app.jar
jfr print --categories glyphora run.jfr
```

| Event | Fields | Committed |
|---|---|---|
| `io.worxbend.tui.FrameDrawn` | duration (composition + draw), `frame`, `dirtyCells` | after each flushed frame, on the render thread |
| `io.worxbend.tui.EventRouted` | duration (the dispatch), `kind`, `repaint` | per event handed to the app |
| `io.worxbend.tui.FocusChanged` | `fromIndex`, `fromKey`, `toIndex`, `toKey` | when a DSL host's focus lands somewhere new |
| `io.worxbend.tui.AsyncAdmitted` / `AsyncRejected` | `queue` (`worker` or `owner`), `outstanding` / `capacity` | per `Async`/`TaskScope` admission, on the submitting thread |

They are plain `jdk.jfr.Event` subclasses with no reflection in the library, and until
a recording enables them `isEnabled` is false and no field — the dirty-cell diff
included — is computed. The names are constants on `JfrEvents` for a test that starts a
`jdk.jfr.Recording` itself. In a native image the events compile in; recording them
needs the image built with `--enable-monitoring=jfr`.

Keep terminal writes ordered: a frame, its diff baseline, terminal handover and error
reporting must agree about what reached the terminal. Background export or logging is
separate from terminal output and should have bounded admission rather than enqueueing
one retained frame forever for every redraw.

A fix's elapsed-time ceiling is the share of the workload it removes. A path responsible
for 5% of runtime cannot buy more than a 5% runtime reduction even if eliminated
completely. A shorter isolated benchmark is not an end-to-end speedup claim.

## Lists and tables over large data

The three collection widgets virtualise their rows — they paint the window and nothing
else — and 0.20.0 closes the gaps where per-frame work still grew with the data rather
than with the viewport. Each one is pinned by a test that counts the rows a widget asked
for, in the style `ViewportCostSpec` set.

**`ListView` measures its items once per sequence.** The widget has to learn whether
every item is one row high — a `Text` item can take several — and used to find out with
a full `forall` on every frame, and `heightAt` summed every item on every call. The
measurement is now taken once and remembered on the `ListState`, keyed by the *identity*
of the `items` sequence, the way `TreeState` and `DataTableState` already remember their
flattened rows and filtered views. A frame over an unchanged reference reads only the
rows it draws (`ListViewCostSpec`: a 100 000-item list is touched at most viewport + 2
times after its first frame), and a second `heightAt` on the same widget value costs
nothing. So:

- keep the sequence between frames — a `Signal[Vector[String]]` does this for you —
  and replace it, rather than mutating it in place, when the data changes;
- a sequence mutated under the same reference is still drawn correctly — the widget
  re-measures when the count changes or a drawn item's height disagrees with what it
  remembered — but an off-screen item that changed height is not noticed until it
  scrolls into view;
- prefer an `IndexedSeq` (`Vector`, `ArraySeq`): the visible window is then read by
  position, so a window deep inside a long list costs the window and not the offset.
  A `List` is walked from the front, which is the cost its own `iterator.drop` carries.

**`TreeState` keyed by identity** (`TreeState.keyed(_.label)`, `TreeState.byLabelPath`)
flattens the visible rows once per node sequence and expansion change, like the positional
state always did, and because expansion and selection name the node rather than its row,
a refresh that reorders the nodes does not cost the reader their place. See
[Lists and navigation](./widgets#lists-and-navigation) in the catalog.

**`DataTable` over a `TableRows` provider.** For data too large or too remote to hold —
a million records behind a query — implement `TableRows[K]` with `count` and `apply(i)`
and pass it where the row sequence goes; a provider *is* a `Seq[KeyedRow[K]]`, so a
plain `Vector` needs no adapter. The table reads only the body window, the selected
row (one read, to confirm the key still sits where it did), and the rows of the current
page (`DataTableLazyRowsSpec`: a 1 000 000-row provider is asked for at most body + 2
rows per frame). Two costs remain with the caller: `state.sort` and `state.filter`
walk every row, because they are defined on the cells the table draws — sort and filter
at the source and leave both unset — and `selectKey` scans for a key, which is as
expensive as the provider makes `apply`. Replace the provider instance (or call
`state.invalidate()`) when its contents change: the memoised view is keyed by identity.

**Thousands of elements: `lazyList`.** The three widgets above virtualise rows of text.
When every row is a whole element — a card, a row with a button — `scrollView(column(rows*))`
builds, measures, decorates and paints every one of them on every frame.
`lazyList(count, itemHeight, state)(i => element)` builds only the items on screen plus one
either side (`LazyListElementSpec`: a 100 000-item list builds at most viewport + 2
elements per frame), and the items are ordinary elements for focus, mouse routing and the
caret. See [A virtual list of elements](./widgets#a-virtual-list-of-elements).

## Why none of this is a CI gate

A wall-clock threshold asserted in CI fails for reasons that have nothing to do with the
commit under test: a noisy neighbour on a shared runner, a different machine class, a
garbage collection that happened to land inside the timed window. The repository has been
through enough flaky-suite work already to not want a timing assertion added to it.

What *can* be asserted is work done rather than seconds spent, and that is asserted:
`ViewportCostSpec` in the widget tests pins that a scrolled viewport does an amount of
work proportional to the visible rows rather than to the document. When a performance
property must hold, write it that way — count the operations, not the nanoseconds.

The benchmarks here are for a person comparing two commits on one machine in one sitting,
which is the only setting in which their numbers mean anything.

## Related

- [Testing](./testing) — the `Pilot` harness, buffer assertions and golden frames.
- [Architecture](./architecture) — why rendering is diff-based in the first place.
