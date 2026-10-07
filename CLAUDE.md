# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

glyphora is a Scala 3 terminal-UI toolkit published as seven `io.worxbend::tui-*` artifacts. Build tool is Mill (`.mill-version` pins 1.1.9, use the checked-in `./mill` wrapper); Scala 3.9.0; `-release:25` (JDK 25 or newer for CI and for consumers of the published artifacts — `.mill-jvm-version` pins Mill's own build process to `temurin:25`, independent of whatever JDK happens to be on `PATH`), GraalVM `graalvm-community:25.0.2` for native images.

## Commands

```bash
./mill __.compile                 # everything

# tests: one module at a time, the way CI runs them. `./mill __.test` is a single step
# that only ever reports "still running", so a hang is indistinguishable from a slow module.
./mill core.test
./mill terminal.test
./mill widgets.test
./mill runtime.test
./mill macros.test
./mill dsl.test
./mill test-support.test

./mill core.test.testOnly io.worxbend.tui.core.RectSpec            # one suite
./mill core.test.testOnly io.worxbend.tui.core.RectSpec -- -z inset # one test (ScalaTest -z)

./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll __.sources   # CI gate
./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
./mill __.fix --check             # scalafix lints, CI gate
./mill __.fix                     # apply them

./mill examples.showcase.run          # manual product tour against a real terminal
./mill examples.showcase.nativeImage  # GraalVM binary → out/examples/showcase/nativeImage.dest/
```

Docs (canonical Markdown lives in `website/docs/`, published to both GitHub Pages and the Wiki):

```bash
(cd website && npm ci && npm run build)
node scripts/export-wiki.mjs --output build/wiki
```

Golden-frame fixtures: run tests with `GLYPHORA_GOLDEN_UPDATE=<module>/src/test/resources` and each `GoldenFrames.assertMatches` writes its actual frame instead of comparing.

`core/src/main/scala/io/worxbend/tui/core/WidthTable.scala` is generated — regenerate with `python3 tools/generate-width-table.py`, never hand-edit. So is `widgets/src/main/scala/io/worxbend/tui/widgets/WorldTable.scala`, the coastline data behind `Shape.WorldMap` — regenerate with `python3 tools/generate-world-table.py --low <lon-lat file> --high <lon-lat file> --output widgets/src/main/scala/io/worxbend/tui/widgets/WorldTable.scala`; the script's docstring names the public-domain source and explains why the coordinates are packed into strings rather than written as an array of doubles.

## Hard constraints (CI fails on these)

- Both greps below scan `*/src/main/scala examples/*/src/main/scala` — the seven modules **and** the ten examples. `*/src/main/scala` alone expands only to the top-level module directories, so for as long as that was the whole scope the examples were silently exempt from the two rules the project is built around; they are the code people copy from, so that is the last place an exemption belongs.
- **No runtime reflection.** `java.lang.reflect` / `Class.forName` are grepped out of every main source. Any bridge to user-defined code goes through `tui-macros` compile-time derivation instead — this is what keeps native-image builds reflect-config-free.
- **No `String.substring` in main sources outside `core/CharWidth.scala`.** All display-width, truncation, and layout arithmetic goes through `CharWidth` (grapheme clusters, CJK, emoji ZWJ, combining marks). One further file is excluded by name: `examples/weather/.../Json.scala`, whose parser indexes offsets in an ASCII wire format and never touches anything that gets drawn — the reasoning is written out on `object Json`, and a new exemption needs the same argument.
- **Warnings are errors**: `-deprecation -feature -unchecked -Wunused:all -Werror` (set in `build.mill`).
- Scalafmt (`.scalafmt.conf`, 120 cols, Scala 3 dialect, `align.preset = more`) and Scalafix (`.scalafix.conf`) are checked by CI. On a pull request from this repository an `autofix` job applies both and pushes the result, so `lint` is the gate for `main` and for forks.
- **Every** example must build with `--no-fallback` and exit cleanly with no TTY. The CI example list is *derived*, not hardcoded: `ci.yml` runs `find examples -mindepth 2 -maxdepth 2 -name package.mill` and a discipline step asserts the derivation is non-empty, so adding `examples/<name>/package.mill` is the only step needed to get that example compiled, tested and built as a native binary. `java.net.http` needs no extra native configuration on this toolchain — `weather`, `airsensor` and `loadtest` all use it and all build clean.

## Architecture

Seven published modules. Dependency edges are real Mill `moduleDeps`, they only point downward — **nothing above `tui-core` may be referenced by a lower tier** — and each module declares exactly the modules it names, no more: a `moduleDeps` entry a module does not actually import is a dependency every consumer of its POM is made to resolve for nothing.

| Module | `moduleDeps` | Owns |
|---|---|---|
| `core` | — | `Buffer`/`Cell`, `Rect`/`Size`/`Position`, `Style`/`Color`/`Modifiers`, `Text`/`Line`/`Span`, `Layout` + `Constraint` solver, `Widget`/`StatefulWidget`/`Measured`, the `Event`/`KeyEvent`/`MouseEvent` ADT, `CharWidth`, and the pure time/curve values `Progress`/`Easing`/`Tween`/`Spring`/`Effect` |
| `terminal` | core | `Backend` trait, `JLine3Backend` (pinned `org.jline:jline-terminal` + `jline-terminal-jni` 4.4.x — *not* the `org.jline:jline` bundle, which drags in a line reader, an SSH server, a telnet server and a shell this module never calls), `InputDecoder`, `HeadlessBackend` |
| `widgets` | core | all 40+ built-in widgets, backend-agnostic |
| `runtime` | core, terminal | `Signal`/`Computed`/`ReactiveScope` (change detection via the `SignalEquality` typeclass), `RenderThread`, `Runner`/`TerminalRunner`/`Frame`, `Async`, `Timers`, the caller-owned tick clocks `Stopwatch`/`Timer`/`TickDriven` |
| `macros` | — | `deriveForm` / `FormFieldType`, inline + `Mirror` only (it derives over the caller's own types and names nothing from `tui-core`) |
| `dsl` | core, terminal, widgets, runtime, macros | `Element` tree, `TuiApp`, `EventRouter`, `Focus`, `Chrome`, `Theme`, `Screen`, `Form` |
| `test-support` | core, terminal, runtime | `Pilot`, `BufferAssertions`, `GoldenFrames` — published as `tui-test`; the Scala package stays `io.worxbend.tui.testsupport` |

The design invariants worth knowing before editing:

- **The event ADT lives in `core`, not `terminal`**, so widgets stay backend-agnostic. Everything above `terminal` talks to the `Backend` trait, which imports no JLine types; fallible backend ops return `Either[BackendError, A]`.
- **Time-to-position arithmetic lives in `core.Progress`.** One owner for "where is this animation at `elapsed`": `normalized` is the one-shot fraction `Tween` and the timed `Effect`s ease, `stepped`/`steppedAtRate` are the looping whole positions the animated widgets use (`widgets.Animation` is just the widget-side name for them). Because these are pure values with no clock and no runner, they sit in `core` where `tui-widgets` can reach them — a widget and an effect can never disagree about where a moment falls in a cycle.
- **Widgets render into a `Buffer` and nothing else.** `Widget` is a SAM (`(Rect, Buffer) => Unit`); interactive/scrollable renderers use `StatefulWidget[S]` where `S` is owned by the *caller*, keeping the widget value immutable and reusable.
- **Content measurement has one contract, `core.Measured`.** A widget that knows how much room its content needs mixes it in and answers `heightAt(width)` / `widthAt(height)`; `None` means "cannot say" and callers must treat it as unmeasurable, never as zero. `Element.intrinsicHeight` consults an explicit `.length(n)` first, then the widget through `Measured`, then the node's `SizeClaim` — so a leaf whose widget can measure itself needs no measurement override at all.
- **`Element` is a declarative layer over `tui-widgets`, never a parallel render path** — every node exposes a `widget`. The tree is plain sealed data, so styling extensions rebuild nodes instead of mutating, and construction tests can pattern-match it.
- **One import, and the `View` carries its contexts.** `import io.worxbend.tui.dsl.*` is the whole application-facing surface: `dsl.scala` re-exports every core, runtime and widget type that appears in a signature it exposes — including every caller-owned `*State` a factory requires. Eight of the ten examples import nothing else, and that is the regression test for the promise. Opaque types (`KeyModifiers`, `Modifiers`) are spelled as a `type` alias *plus* a `val` alias rather than exported, because an exported opaque type loses its companion's extension methods. `type View = (ReactiveScope, Theme) ?=> Element`: the theme travels *in* the view's type rather than being installed as a given around the call, because a given installed around the call is not in scope inside the body — which is how an app that overrode `theme` used to get `Theme.Dark` from a `statusBar(bindings)` written in its own `view`.
- **Event ordering in the DSL**: the user's `onKeyEvent`/`onMouseEvent` handler runs first; only if it returns `false` does the element's `builtinKeyHandler`/`builtinMouseHandler` fire; unconsumed keys bubble to ancestors, then to `TuiApp.bindings`. Return `false` to let an event keep bubbling.
- **Single render thread.** `Signal.set` calls `RenderThread.checkRenderThread()`, which throws off-thread but is a deliberate no-op when no runner is registered — so plain unit tests need no runtime. Each `Runner` owns its own work queue (multiple runners can coexist in one JVM); background work must call `RenderThread.capture()` *before* going async so its continuation returns to the right loop.
- **Signals track dependencies per evaluation.** `get` (needs a `ReactiveScope`) subscribes; `peek` doesn't. Edges are rebuilt on every recompute, so conditional reads subscribe only the branch that ran. Change detection is the `SignalEquality[A]` given in scope at the signal's creation — `==` by default, IEEE-754 total order for `Double`/`Float` — and writing a value it calls unchanged notifies nobody.
- **Rendering is diff-based**: the full frame is composed into a `Buffer` with no terminal involved; `JLine3Backend` compares against the last flushed frame and emits only changed cells as one batched ANSI string. That is why `HeadlessBackend` + `Pilot` tests are exact rather than approximate.

Every module is `<module>/src/{main,test}/scala/io/worxbend/tui/<module>/`. Examples live in `examples/<name>/` with `Main` at `io.worxbend.tui.examples.<name-without-dashes>.Main`.

## Build-file conventions

`build.mill` holds four shared traits, and every `package.mill` should be small enough to fit on a screen:

- `TuiModule` — Scala version, strict flags, and the nested `TuiTests` ScalaTest wiring. `TuiTests` also mixes in `ScalafixModule`, so `./mill __.fix` lints test sources on the same rules as main ones; the handful of legitimate exceptions there carry an inline `// scalafix:ok DisableSyntax; <why>`. `TuiTests` forks **one JVM per test class** (`testForkGrouping`), because the `RenderThread` registry and the runner-less `AnimationClock` that `freezeAt` pins are process-global and Mill's default work-stealing grouping is not stable between runs — sharing a worker made suites decide each other's frames, which is what used to make `./mill __.test` flaky.
- `TuiPilotTests extends TuiTests` — adds `test-support` to `moduleDeps`, for any module whose tests drive a whole app through `Pilot`.
- `TuiExampleModule extends TuiModule with NativeImageModule` — the GraalVM pin (`graalvm-community:25.0.2`, one place, not ten — also the JDK every example compiles under, which is why it moves in lockstep with `TuiModule`'s `-release:25`; macOS x64 has no GraalVM CE build past 25.0.1, so a contributor on an Intel Mac needs to override this locally to build an example), `nativeImageOptions = Seq("--no-fallback", "--enable-native-access=ALL-UNNAMED")` (the second flag silences the JEP 472 warnings JLine 4's native access otherwise prints — four lines — at every binary startup), and a nested `object test extends TuiPilotTests`. An example's `package.mill` then declares only `moduleDeps` and `mainClass`, and `moduleDeps` is always exactly `Seq(build.dsl)` — Mill's `moduleDeps` are transitive and `dsl` already pulls in the other five, so a longer list changes nothing about the build and only drifts away from what the example imports. `--march`/`-Os` deliberately stay *out* of `nativeImageOptions`: CI builds and runs on the same machine, so GraalVM's default is right there; they are guidance for consumers shipping binaries, documented in `website/docs/native-image.md`.
- `TuiPublishModule extends TuiModule with PublishModule` — POM metadata plus the single synchronized `publishVersion`, currently `0.16.1`; bump it in one place. `publish.yml` fires on a `v*` tag and refuses to run unless `${GITHUB_REF_NAME#v}` equals `./mill show core.publishVersion`, because the tag is not what gets published — `publishVersion` is — and Maven Central coordinates cannot be taken back once uploaded.

Test-only dependencies go through `def extraTestDeps`, not by overriding `mvnDeps`, because Mill cannot resolve a second `super.mvnDeps` chain in nested test objects.

## Adding a widget

1. Constructor parameters go in one fixed order: **required data, then layout and behaviour, then `style`, then specialised styles, then glyph/symbol overrides.** Every widget reads the same way at the call site, and a reader can tell at a glance which arguments are content and which are appearance. "Layout and behaviour" is the slot for a parameter that changes *what is drawn where* rather than what colour it is — `Block`'s `borders`/`padding`, `Table`'s `columnSpacing`, `BarChart`'s `barWidth`/`barGap`, `Sparkline`'s `max`. It sits before the styles because it is closer to the data than to the appearance. `BorderType` belongs in the *last* slot, not this one: it picks glyphs, not geometry. Once that list grows past a handful of optional knobs, **everything past the few arguments that identify the widget bundles into a dedicated options case class in the same file** — `Chart(datasets, xBounds, yBounds, options: ChartOptions = ChartOptions())`, `DataTable(columns, rows, widths, options: DataTableOptions = …)`, `Scrollbar(contentLength, position, options: ScrollbarOptions = …)`, `OrbitSpinner(elapsed, options: OrbitSpinnerOptions = …)`. Every field of the options object keeps the default it would have had as a constructor parameter, so `XOptions()` is exactly the widget the bare constructor drew; the ordering rule above applies within the options object's own field list. The pre-options `apply` is *not* kept on the companion as a `@deprecated` delegate: an overloaded `apply` may not repeat the primary constructor's default arguments, so a delegate would have to spell out every old parameter by hand, and — `io.worxbend` never having been published — there are no released call sites whose compilation such a delegate could preserve. Callers move straight to the options-bundle form.
2. Implement in `widgets/` in its **own file named after the widget** — no grab-bag files — depending only on `tui-core`; render inside the given `Rect`, clip safely, all width math via `CharWidth`. Stateless → `Widget`; interactive → `StatefulWidget[S]` with caller-owned state; if the widget knows how much space its content needs, also mix in `Measured` rather than inventing a `heightOf`/`preferredWidth` of its own. Shared render helpers go in a `private[widgets]` object named for the concept (`Fraction`, `BlockLadder`), not copied per widget.
3. Test the buffer with `BufferAssertions`: empty/tiny areas, truncation, Unicode, focus/selection styling, state boundaries.
4. Add the DSL node to the node-family file it belongs to (`LayoutElements`, `DisplayElements`, `LoadingElements`, `TextEntryElements`, `ChoiceElements`, `CollectionElements`, `NavigationElements`, `ElementDecorators`) — `Element.scala` holds only the `Element` trait itself. Each node declares `type Self = ThatNodeType` so the fluent builders stay type-preserving. Built-in key/mouse behavior is composed from `ElementBuiltins`; the factory goes in `ElementFactories` (which `object Element` extends) and is re-exported from `dsl.scala`.
5. Drive interaction through `Pilot` (focus, keys, mouse, resize, redraw).
6. Document it in `website/docs/widgets.md`; new pages must also be registered in `website/docs-navigation.mjs`, which drives both the Docusaurus sidebar and the generated Wiki. This step is enforced: `DocumentedFactorySpec` reads the `export Element.{…}` block out of `dsl.scala` and fails `dsl.test` if any exported factory is named on no page under `website/docs/` and nowhere in `README.md`. It wants the name in code voice — `positioned(…)` or `` `positioned` `` — so a passing mention has to be about the factory, not a coincidence of English. `DocumentedKeySpecSpec` guards the same prose for key specs, and both share the file walker in `DocumentationSources`.

## Style docs

`SCALA_CODE_STYLE.md` is the general Scala convention doc (explicit result types, sealed ADTs over booleans, `Either` for recoverable failures, no `return`, Scaladoc that states ownership and thread constraints). Its sections on Ox, HTTP/JSON, and DI describe conventions that have no counterpart in this repo — there is no Ox dependency here. Scalafix *is* wired in (`.scalafix.conf`, syntactic rules only), but only the subset this codebase actually satisfies: `noVars`, `noThrows`, `noDefaultArgs` and `ExplicitResultTypes` are deliberately off, because local `var` in render loops, `throw` for programmer errors in static declarations, and default arguments on widget constructors are all deliberate here. The handful of legitimate exceptions to the enabled rules carry an inline `// scalafix:ok DisableSyntax; <why>`. `docs/STYLE_GUIDE.md` covers visual/editorial rules for the README, site, and `docs/assets/`.
