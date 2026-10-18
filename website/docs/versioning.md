---
title: Versioning & releases
description: Understand glyphora's pre-1.0 compatibility policy, synchronized module versions, Maven coordinates, and release process.
---

# Versioning & releases

glyphora is pre-1.0. Patch releases preserve public APIs; minor releases may make
breaking changes as the application layer matures. `tui-core` is the stability
anchor and has received additive changes only since `0.2`.

Pin an exact version in applications and review release notes before moving between
minor versions.

## Current coordinates

All publishable modules in this checkout share one synchronized version under `com.worxbend`:

```scala
// Mill
def mvnDeps = Seq(mvn"com.worxbend::tui-dsl:0.19.0")
```

```scala
// sbt
libraryDependencies += "com.worxbend" %% "tui-dsl" % "0.19.0"
```

Applications normally need only `tui-dsl`. Lower-tier artifacts are `tui-core`,
`tui-terminal`, `tui-widgets`, `tui-runtime`, and `tui-macros`; `tui-test` carries the
headless test harness and belongs in the test configuration only.

Version `0.19.0` is published to Maven Central under `com.worxbend` by the `v0.19.0`
tag (see [Release process](#release-process)); Central keeps every earlier release, so a
build pinned to `0.18.3` keeps resolving.
Downloads require no credentials, and Mill and sbt use Central by default. GitHub
Packages also hosts the libraries; its resolver requires a classic PAT with
`read:packages`.
See [Getting started](./getting-started#1-add-glyphora) for both options.

GitHub Packages releases through `v0.18.1` used `io.worxbend`. Update dependency
coordinates to `com.worxbend` when moving to `0.18.3`; Scala imports use `io.worxbend.tui`.

## Migrating to 0.19.0

- `Style`, `Layout`, `RunnerConfig` and `Theme` are now `final class`es with private
  constructors, as the "Types that will break when they grow" note promised. Construction
  through the companion `apply` with named arguments (`Style(fg = Some(c))`,
  `RunnerConfig(tickRate = Some(d))`, `Layout.horizontal(…)`) and every preset
  (`Theme.Dark`, `Style.Default`, the new `RunnerConfig.Default`) compiles unchanged.
  `copy` and pattern extractors are gone: `x.copy(field = v)` becomes `x.withField(v)` —
  `Layout.horizontal(3, 4).copy(spacing = 1)` → `.withSpacing(1)`,
  `Theme.Dark.copy(glyphs = …)` → `Theme.Dark.withGlyphs(…)`,
  `Theme.Dark.copy(name = "mine", loading = …)` → `Theme.Dark.withName("mine").withLoading(…)`,
  `Style.Default.copy(modifiers = m)` → `Style.mods(m)`. Only construction through a
  preset or `Default` plus `with*` is unaffected by future fields; a call to the companion
  `apply` still needs a recompile when a field is added — the `apply` is a construction
  convenience, not the compatibility surface. They are plain classes now — not `Product`s
  and not `java.io.Serializable`; `productIterator` and friends are gone with `copy`.
  - `Style` gains `withModifiers`, `withClearedModifiers` and `withoutLink`. `Layout` gains
    `withSpacing`, `withFlex` and `withoutBetween`; `Layout.spaced(Spacing)` remains the
    builder for `between`, and an explicit `spaced(…)` keeps precedence over `withSpacing`
    until `withoutBetween` clears it.
  - `RunnerConfig` gains `RunnerConfig.Default` and a `with*`/`without*` pair for every
    field (`withTickRate`/`withoutTickRate`, `withMouseCapture`/`withoutMouseCapture`,
    `withKeyEventTypes`/`withoutKeyEventTypes`, `withOnTaskError`/`withoutOnTaskError`,
    `withOnFrame`/`withoutOnFrame`, `withOnFrameTiming`/`withoutOnFrameTiming`,
    `withViewport`). Its `equals`/`hashCode` are structural except that the three
    function-valued fields compare by reference, as before.
  - `Theme` gains one builder per role — `withName`, `withPrimary`, `withAccent`,
    `withMuted`, `withError`, `withWarning`, `withSuccess`, `withSurface`, `withBorder`,
    `withFocus`, `withLoading`, `withMarkdown`, `withSyntax`, `withGlyphs`. `Theme.Dark`,
    `Theme.Light`, `Theme.HighContrast`, the `given Theme.default` and `Theme.detected()`
    are unchanged, and the companion `apply` keeps its thirteen named arguments plus
    `glyphs = GlyphSupport.Full`.
  - `toString` is briefer: `Theme` prints as `Theme(dark)` — the name only — and
    `RunnerConfig` as `RunnerConfig.Default` or
    `RunnerConfig(tickRate=Some(100 milliseconds), mouseCapture=true)` — the non-default
    fields only. `Layout` prints `Layout(Horizontal, List(Length(3)), spacing=0, flex=Start,
    between=None)` instead of the derived positional form.
- `BarChart`, `Calendar` and `LinearSpinner` bundle their optional knobs into
  `BarChartOptions`, `CalendarOptions` and `LinearSpinnerOptions`, matching `Chart`,
  `DataTable`, `Scrollbar` and `OrbitSpinner`: `BarChart(data, barSet = …)` →
  `BarChart(data, BarChartOptions(barSet = …))`;
  `Calendar(y, m, selected = Some(d), dayStyles = s)` →
  `Calendar(y, m, Some(d), CalendarOptions(dayStyles = s))` — or
  `Calendar(y, m, options = CalendarOptions(dayStyles = s))` when `selected` is left out,
  since the bundle is the fourth parameter; `LinearSpinner(elapsed, style, headStyle, axis = …)`
  → `LinearSpinner(elapsed, LinearSpinnerOptions(axis = …, style = …, headStyle = …))`. The
  DSL factories `barChart`, `horizontalBarChart`, `calendar` and `linearSpinner` are
  unchanged. There is no deprecated delegate for the old constructor shapes: an overloaded
  `apply` cannot repeat the primary constructor's defaults, so a delegate would have had to
  spell out every old parameter by hand. `WidgetOptionsDisciplineSpec` now fails
  `widgets.test` when a widget carries more than eight defaulted constructor parameters
  without an options bundle; `Block` is the one allowlisted deferral.
- `TerminalCapabilities.enabled(support)` is deprecated; write `support.usable`.
- Input and runtime fixes that may change observed behaviour. A `wake()` interrupt (every
  timer tick or async completion) no longer drops a pending Escape, turns Alt+key into a
  bare key, or dispatches a terminal reply's tail as keystrokes — a half-read sequence
  (`ESC`, `ESC O`, `ESC [ 1 ; 5`, an X10 mouse report, a split surrogate pair) is re-queued
  and re-parsed whole, the escape window is not restarted by the wake, and a wake inside a
  skipped reply resumes the skip with its length cap intact. `HeadlessBackend.wake()` now
  interrupts `readEvent`, so `Pilot` works with any `tickRate`; `HeadlessBackend.idleReads`
  also counts a wake that found the queue empty, and `pendingEvents` counts posted events
  only. `waitForIdle` means what it did; what changed is latency — a timer or async
  continuation now reaches the render thread within milliseconds instead of at the next
  tick poll, so a pilot test that waited on a `Signal` value and then asserted on
  `drawCount` (or any frame-derived state) in the next statement could pass on 0.18.3 by
  accident and now races the frame. Wait for the frame too:
  `pilot.waitUntil("repaint")(backend.drawCount > before)` or `waitForDraws(n)`. A
  throwing custom `Backend.wake()` no longer stops a repeating timer silently (it
  reaches the timer thread's uncaught handler); an uncancelled `Async.every` retires itself
  on its next firing once its owner has closed; a fatal throwable from the final drain
  escapes `run` even after an earlier failure; `RunnerError.message` reports the number of
  cleanup failures.
- Rendering fixes: `Buffer.blit` keeps the owner's style on a cut wide glyph; a `Cell("")`
  is stored and flushed as a styled one-column blank; `ListView` BottomToTop keeps a
  multi-row item's lines in reading order (a bottom-to-top item that does not fit loses its
  *top* lines, and one taller than the viewport shows its bottom); `Paragraph.scrollX` no
  longer leaves a one-column gap after a straddling wide glyph — the glyph is dropped whole
  and the column it would have occupied past the edge stays blank, so rows stay
  column-aligned as in `TextArea`; `Masked` falls back to its default glyph for a zero-width
  `maskChar`.
- One import: `Cell`, `UnderlineStyle`, `LegendConstraints`, `ScrollbarSide`,
  `SpinDirection` and `SignalEquality` are now reachable from `import io.worxbend.tui.dsl.*`.
  `DataTableState.keyed[K]` builds a state for a keyed table.

## Migrating to 0.18.0

- Async submissions reserve continuation capacity before accepting work. Saturated worker,
  owner, or runnerless queues throw `RejectedExecutionException` synchronously instead of
  accumulating an unbounded backlog. Catch overload at the submission boundary and reduce
  demand; do not retry in a tight loop. Cancellation suppresses unadmitted callbacks, not
  blocking I/O. Repeating timers coalesce ticks while a callback is pending or running.
  See [Async and timers](./async-and-timers).
- Builders beneath the last full-screen presentation are no longer evaluated. Keep resource
  acquisition in lifecycle hooks, not view builders. Eager covered-screen hook state is retained;
  responsive subtrees that disappear keep their existing reset semantics. See [App shell](./app-shell).
- `RunnerConfig` adds opt-in `onFrameTiming`. Recompile consumers against the updated case-class
  shape. `LatestFrameObserver` offers bounded, serial, latest-frame sampling for slow observers:
  it drops superseded pending frames and is not a lossless recorder. See [Performance](./performance)
  and [Testing](./testing).

Paragraphs now produce wrapped rows incrementally and reuse stable measurements. Tree
flattening caches are caller-owned, invalidate on source/expansion changes, and do not force
collapsed offscreen children. Procmon reuses its table projection until the immutable sample
changes. These remove repeated work; they are not claims of an application-wide speedup.

## Migrating to 0.17.0

- Directory trees and file pickers no longer acquire filesystem data while painting.
  Load the initial listing explicitly; acquire background snapshots with `DirectoryListing.load`
  and install them on the owning render thread. See [Widgets](./widgets).
- Reactive graphs belong to one live render loop. Capture `RenderThread.capture()` before
  registering an external callback, then dispatch mutations through `owner.execute`.
  See [State and signals](./state-and-signals).
- Render queues and shutdown drains have finite callback budgets. Do not depend on an
  arbitrarily large backlog completing during shutdown. Optional `Async.scope()` groups
  task cancellation, but does not interrupt blocking I/O. See [Async and timers](./async-and-timers).

This release also fixes deferred keyed state, navigation focus identity, cross-span
Unicode rendering, enhanced keyboard restoration, and async error visibility. It adds
source-aware table caching and a custom interactive-widget adapter.

## Compatibility policy

| Change | Patch release | Minor release before 1.0 |
|---|---:|---:|
| bug fix preserving signatures | yes | yes |
| additive widget or method | yes, when low risk | yes |
| source-breaking rename/removal | no | possible, documented |
| behavior change with migration work | no | possible, documented |
| raised minimum JDK / toolchain requirement | no | possible, documented |
| binary compatibility guarantee | not yet | not yet |

`0.14.0` raises the minimum JDK from 21 to 25: every published artifact declares `-release:25`, so a class
file built by this version will not load on JDK 21–24 (`UnsupportedClassVersionError`). Consumers pinned to
an older JDK should stay on `0.13.0` until they can move.

MiMa binary-compatibility gates are planned once a first published baseline is
selected. Until then, recompile downstream code on upgrade even when moving between
patch versions.

### Types that will break when they grow

Adding a field to a `case class` changes the signatures of `apply`, `copy` and
`unapply`, so downstream code compiled against the old shape fails with
`NoSuchMethodError` — recompiling fixes it, but only if you know to.

`Style`, `Layout`, `RunnerConfig` and `Theme` left this category in 0.19.0. Each is a
`final class` with a private constructor, and its compatibility surface is its presets or
`Default` plus the `with*` builders: `Style.Default`/`Style.Reset`/`Style.fg`/`bg`/`of`/`mods`,
`Layout.horizontal`/`vertical` (`Layout.spaced` for `between`), `RunnerConfig.Default`, and
`Theme.Dark`/`Light`/`HighContrast`. A value built that way keeps working when a field is
added. Their companion `apply` keeps today's defaults for convenience but is still
arity-sensitive — adding a field changes its descriptor, so a call to it needs a recompile —
and at that growth the previous arity is kept as a plain default-free overload so compiled
callers keep working. One public type is still a `case class` that will grow:

| Type | Module | Why it will grow |
|---|---|---|
| `ElementProps` | `tui-dsl` | every new element modifier lands here |

`ElementProps` stays a case class until the DSL-node step below because its user-facing
surface is already the fluent builders (`.fg(…)`, `.length(n)`, `.onKeyEvent(…)`), which
keep their shape when a field is added, and nothing published calls its `copy`. The
genuinely closed value types (`Position`, `Size`, `Rect`, `Cell`, `KeyEvent`,
`MouseEvent`) are expected to stay `case class`es: their shape is fixed by what a
terminal cell and a coordinate are.

The DSL node types — `TextElement`, `PanelElement` and the other `*Element` case classes
the factories return — are a related surface. Their constructors, `copy` methods and
pattern extractors are not part of the compatibility contract; the factories (`text(…)`,
`panel(…)`) and the fluent builders on `Element` are. A node gains a field whenever its
factory gains an option, so inspect a tree through `props`, `children` and `widget`
rather than pattern-matching a node's constructor. Before 1.0 a node may become a
`final class` behind its factory with no user-visible change.

## Release process

The Maven namespace is `com.worxbend`, corresponding to the domain `worxbend.com`.
Before a Central release, verify this namespace under the account whose Portal
token is stored in the publishing secrets. Sonatype checks the verification TXT
record on `worxbend.com`; see [namespace verification](https://central.sonatype.org/register/namespace/).
Scala imports continue to use `io.worxbend.tui`: the Maven group ID and Scala package
names are independent.

GitHub Packages releases through `v0.18.1` used the `io.worxbend` Maven group.
Consumers moving to the new group must update their dependency coordinates.

The **Publish GitHub Packages** workflow (`.github/workflows/publish-packages.yml`)
runs on `v*` tag pushes and can also be started manually with a `version` input.
For a manual run, select the ref to publish and supply the version declared by all
seven modules at that ref; the workflow publishes that ref's validated commit.
It calls the complete read-only CI workflow, checks out only its validated SHA,
and verifies every module version before exposing the built-in `GITHUB_TOKEN`.
Only the publishing job receives `packages: write`; no additional repository secret
or GPG signing key is needed for GitHub Packages.

The publisher uploads `tui-core`, `tui-terminal`, `tui-widgets`, `tui-runtime`,
`tui-macros`, `tui-dsl`, and `tui-test` with their POMs, JARs, sources and Scaladoc.
After upload, it downloads all 28 files, checks their hashes against the validated
build, and runs the isolated DSL/macro and test-support consumer using a fresh
Coursier cache and the remote resolver. Missing artifacts or incorrect transitive
dependencies fail the release workflow.

To publish the current main commit manually:

```sh
gh workflow run publish-packages.yml --ref main -f version=0.19.0
```

Treat released versions as immutable. Increment the shared `publishVersion`
before another release; a manual run is for a version that has not yet been uploaded.
If an upload partially succeeds, inspect the package versions and workflow logs
before retrying.

Releases are Git tags named `vX.Y.Z`. Pushing a tag runs the Publish workflow. Before
signing credentials are made available, it calls the same read-only CI workflow used
for main and pull requests on the tag's exact commit. Compilation, module and example
tests, formatting/lints, discipline checks, native images, the site build, and packaged
consumer validation must all pass. Publication checks out the validated SHA and refuses
to upload if it differs from the tag commit or if the tag version differs from `build.mill`.

The packaged-consumer gate stages all seven artifacts in a temporary Maven repository,
checks their coordinates and exact production dependency edges, rejects leaked test
libraries, and builds/runs a consumer outside the source module graph. That consumer
resolves `tui-dsl` in production and `tui-test` only in its test configuration, exercises
external derivation and DSL imports, and checks the resolved jars against the staged
artifacts. Run it locally with `python3 scripts/verify-packaged-consumer.py`; it does not
publish remotely or modify the normal local Maven/Ivy repository.

Only after these gates does Publish upload all seven libraries to Maven Central
with the synchronized version and signed POM metadata. It then waits for the public
repository, downloads all 28 release files without authentication, compares their
hashes, and runs the isolated consumer with a fresh Coursier cache. An accepted
Portal upload alone does not make the workflow successful. The separate Autofix workflow
remains limited to same-repository pull requests; release validation never rewrites the
commit being validated. After Autofix pushes with `GITHUB_TOKEN`, maintainers must
approve the token-generated full CI run for the resulting PR commit before merging.
The Autofix job's library tests and checks from the previous SHA are not substitutes
for full validation of that commit. If no run appears, create an ordinary maintainer
push to trigger it; do not merge based only on the pre-formatting checks.

Before a tag, maintainers should verify:

- formatter, compile, and complete test suite;
- public guide and Scaladoc changes;
- example JVM runs and native-image CI;
- module version in `build.mill`;
- migration notes for any source or behavior change.

## License

[MIT](https://github.com/worxbend/glyphora/blob/main/LICENSE) — use glyphora
in open-source or commercial software, keeping the copyright and license notice.
