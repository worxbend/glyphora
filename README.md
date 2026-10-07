<div align="center">

<img src="docs/assets/banner.svg" alt="glyphora — terminal UI, written like Scala" width="100%"/>

<h3>✦ Terminal UI, written like Scala.</h3>

<p>
  <strong>Reactive signals</strong> · <strong>50+ widgets</strong> · <strong>keyboard &amp; mouse</strong><br/>
  <strong>composable motion</strong> · <strong>headless tests</strong> · <strong>GraalVM native-image</strong>
</p>

<p>
  <a href="https://github.com/oleksandr-balyshyn/glyphora/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/oleksandr-balyshyn/glyphora/actions/workflows/ci.yml/badge.svg"/></a>
  <a href="https://oleksandr-balyshyn.github.io/glyphora/"><img alt="Docs" src="https://github.com/oleksandr-balyshyn/glyphora/actions/workflows/docs.yml/badge.svg"/></a>
  <a href="https://github.com/oleksandr-balyshyn/glyphora/tags"><img alt="Release" src="https://img.shields.io/github/v/tag/oleksandr-balyshyn/glyphora?label=release&color=EF3340"/></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/oleksandr-balyshyn/glyphora?color=FBBF24"/></a>
</p>

<p>
  <img alt="Scala" src="https://img.shields.io/badge/Scala-3.9-DC322F?logo=scala&logoColor=white"/>
  <img alt="JDK" src="https://img.shields.io/badge/JDK-25%2B-437291?logo=openjdk&logoColor=white"/>
  <img alt="Mill" src="https://img.shields.io/badge/build-Mill-22D3EE"/>
  <img alt="native-image" src="https://img.shields.io/badge/native--image-ready-34D399?logo=oracle&logoColor=white"/>
  <img alt="Zero reflection" src="https://img.shields.io/badge/reflection-zero-A78BFA"/>
</p>

<p>
  <a href="https://oleksandr-balyshyn.github.io/glyphora/"><b>📖 Read the guide</b></a> ·
  <a href="https://github.com/oleksandr-balyshyn/glyphora/wiki"><b>📚 Browse the wiki</b></a> ·
  <a href="website/docs/cookbook.md"><b>🍳 Cookbook</b></a> ·
  <a href="https://oleksandr-balyshyn.github.io/glyphora/api/"><b>🔎 Scaladoc</b></a> ·
  <a href="examples/README.md"><b>🎮 Examples</b></a>
</p>

</div>

---

## ✦ Why glyphora

<p align="center"><img src="docs/assets/capabilities.svg" alt="Reactive signals, rich widgets, motion, mouse input, and native binaries" width="100%"/></p>

<table>
<tr>
<td width="25%" align="center" valign="top">
  <img src="docs/assets/icons/signals.svg" width="56" alt=""/><br/>
  <b>⚡ Signals, not plumbing</b><br/>
  <sub>Read <code>Signal</code> and <code>Computed</code> in a typed Scala view; glyphora tracks the dependencies and redraws what changed.</sub>
</td>
<td width="25%" align="center" valign="top">
  <img src="docs/assets/icons/widgets.svg" width="56" alt=""/><br/>
  <b>🧩 A real widget vocabulary</b><br/>
  <sub>Inputs, tables, trees, Markdown, charts, spinners, dialogs, menus and app chrome all ship together.</sub>
</td>
<td width="25%" align="center" valign="top">
  <img src="docs/assets/icons/mouse.svg" width="56" alt=""/><br/>
  <b>⌨️ Interaction is first-class</b><br/>
  <sub>Focus order, bubbling keys, bracketed paste, mouse hit-testing and resize events are built in.</sub>
</td>
<td width="25%" align="center" valign="top">
  <img src="docs/assets/icons/effects.svg" width="56" alt=""/><br/>
  <b>🎬 Motion stays composable</b><br/>
  <sub>Effects transform the completed frame and animated widgets are pure functions of elapsed time, so every renderer stays deterministic and testable.</sub>
</td>
</tr>
<tr>
<td align="center" valign="top">
  <img src="docs/assets/icons/testing.svg" width="56" alt=""/><br/>
  <b>🧪 Tests share one pipeline</b><br/>
  <sub>Render to a real terminal or the in-memory <code>HeadlessBackend</code> — drive whole apps with no PTY.</sub>
</td>
<td align="center" valign="top">
  <img src="docs/assets/icons/native.svg" width="56" alt=""/><br/>
  <b>📦 Native-image by design</b><br/>
  <sub>Compile-time derivation replaces runtime reflection, so examples build with <code>--no-fallback</code> and no reflect config.</sub>
</td>
<td align="center" valign="top">
  <img src="docs/assets/icons/unicode.svg" width="56" alt=""/><br/>
  <b>🌍 Unicode done properly</b><br/>
  <sub>Grapheme clusters, CJK width, emoji ZWJ sequences and combining marks all go through one width table.</sub>
</td>
<td align="center" valign="top">
  <img src="docs/assets/icons/chrome.svg" width="56" alt=""/><br/>
  <b>🏗️ Batteries-included shell</b><br/>
  <sub>Scaffold, top bar, sidebar, status line, toasts, screens and a fuzzy command palette.</sub>
</td>
</tr>
</table>

## 🚀 Your first app

> [!NOTE]
> **Not on Maven Central yet.** A source release tag does not make artifacts available on Maven
> Central. Until `0.16.1` is published there, the coordinates below will not resolve from a public
> repository. Clone the repo and run `./mill __.publishLocal`
> — that puts `tui-core`, `tui-terminal`, `tui-widgets`, `tui-runtime`, `tui-macros`, `tui-dsl`
> and `tui-test` at `0.16.1` into `~/.ivy2/local`. Mill reads that cache by default; sbt needs
> `resolvers += Resolver.defaultLocal`. See [Build from source](#-build-from-source).

```scala
// build.mill
def mvnDeps = Seq(mvn"io.worxbend::tui-dsl:0.16.1")
```

```scala
// build.sbt
libraryDependencies += "io.worxbend" %% "tui-dsl" % "0.16.1"
```

Then return an ordinary Scala `Element` tree:

```scala
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

`TuiApp` supplies `main`, so `object Counter extends CounterApp` *is* the program — the
launcher runs it, and a run that fails to take the terminal reports on standard error
and exits non-zero.

The app is a **class** with a one-line `object` on the end so that tests can build a
fresh one per scenario: `TuiApp` keeps its state on the instance and never resets it
between runs, so re-running a single `object` starts holding whatever the last run left
behind. The launcher gets `object Counter`; a test writes
`Pilot.using(Size(40, 10))(backend => CounterApp().runWith(backend))(testBody)`.

Three ideas carry through the entire toolkit:

| | |
|---|---|
| 1️⃣ **Model** | changing values with `Signal`; derive cached values with `Computed` |
| 2️⃣ **Compose** | the screen from elements, constraints, semantic styles, and retained widget state |
| 3️⃣ **Ship** | on the JVM, test through `HeadlessBackend`, or compile a native binary |

📘 The guided walkthrough explains every line: **[Getting started →](website/docs/getting-started.md)**

## 🧭 One render pipeline

<p align="center"><img src="docs/assets/architecture.svg" alt="glyphora typed render pipeline and module architecture" width="100%"/></p>

| Module | Owns |
|---|---|
| 🧱 [`tui-core`](core/README.md) | cells, buffer, geometry, style, layout, events, Unicode display width, motion values |
| 🖥️ [`tui-terminal`](terminal/README.md) | backend contract, JLine 4 backend, ANSI diffing, input decoder, headless backend |
| 🧩 [`tui-widgets`](widgets/README.md) | backend-independent content, controls, data, visualization, and feedback widgets |
| ⚡ [`tui-runtime`](runtime/README.md) | signals, render thread, loop, async work, timers |
| 🎨 [`tui-dsl`](dsl/README.md) | element tree, `TuiApp`, focus/mouse routing, themes, shell, screens, toasts, palette |
| 🪄 [`tui-macros`](macros/README.md) | reflection-free form and action derivation at compile time |
| 🧪 [`tui-test`](test-support/README.md) | `Pilot` app driver, buffer assertions, golden-frame snapshots — a test-only dependency |

Use the complete `tui-dsl` stack for applications, or stop at a lower layer for a custom
backend, renderer, or widget library. **No widget depends on a terminal and no terminal
backend knows about signals.**

The modules above are the structure; this is what one frame actually does:

```mermaid
flowchart LR
  Input["⌨️ keyboard + mouse"] --> Router["focus & event routing"]
  Router --> Chrome

  subgraph Chrome["application scaffold"]
    direction TB
    Top["top bar · tabs · command palette"]
    Sidebar["sidebar · navigation"]
    Content["widgets · charts · forms"]
    Status["status line · shortcuts · toasts"]
    Top --> Content
    Sidebar --> Content
    Content --> Status
  end

  Chrome --> Buffer["headless buffer"]
  Buffer --> Diff["minimal terminal diff"]
  Diff --> ANSI["ANSI output"]

  Signals["Signal / Computed"] -. "invalidate" .-> Content
  Effects["effects engine"] -. "animate" .-> Content
```

Only the cells that changed reach the terminal, and the whole path up to `ANSI output` runs
without one — which is what makes [headless testing](#-test-the-terminal-without-a-terminal)
exact rather than approximate.

🧭 **[Architecture guide →](website/docs/architecture.md)**

## 🧩 Widget atlas

| Family | Highlights |
|---|---|
| 🧱 **Layout & chrome** | panel, row/column, spacer, rule, scroll view, tabs, collapsible, split pane, layers, scaffold, sidebar |
| 📄 **Content** | text, list, table, `DataTable`, tree, directory tree, log, Markdown, OSC 8 links, half-block image |
| ⌨️ **Input** | text input/area, checkbox, toggle, select, radio group, slider, masked/number input, autocomplete, file picker, button, derived form |
| 📊 **Data viz** | gauge, sparkline, bar/stacked/pie chart, line/scatter chart, heatmap, canvas shapes, calendar |
| ✨ **Feedback** | spinner, skeleton, indeterminate bar, marquee, wave text, dialog, tooltip, toasts, splash, effects |

Every interactive state object is caller-owned. Every widget renders into a `Buffer`. Every
width calculation goes through grapheme-aware `CharWidth`.

🧩 **[Browse the complete catalog →](website/docs/widgets.md)**

### New in 0.13.0

| | |
|---|---|
| **Share the terminal** | An app can run in an *inline* viewport instead of seizing the alternate screen, so its output stays in the scrollback like any other command. `insertBefore` writes styled lines above a live UI. |
| **Escape the pane** | `portal` draws a dropdown or menu outside its parent's bounds, clipped only by the terminal, and it is clicked and tabbed to where it is actually drawn. |
| **State beside the view** | `useSignal` and `useState` let a reusable view helper own private state at its call site, instead of every piece of state being hoisted onto the app object. |
| **Degrade honestly** | A `Monochrome` colour rung maps colour to contrast rather than discarding it, and ASCII glyph sets replace box drawing where a terminal cannot show it. |
| **More input** | Key *release* events behind an opt-in, the media and lock keys, the horizontal wheel, hover through all-motion mouse tracking, and the terminal's pixel size. |

Much of this release came from reading [ratatui](https://github.com/ratatui/ratatui) and
[jatatui](https://github.com/oyvindberg/jatatui) side by side with this codebase and closing the
gaps worth closing. Some were deliberately left open — where a feature would have contradicted a
decision this library has already made, the reasoning is written down next to the code.

## 🧪 Test the terminal without a terminal

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

`Pilot` posts the same event ADT used in production and exposes the last rendered screen as
text. Buffer helpers skip wide-character continuation cells, so assertions match what users see.
`using` closes the runner even when an assertion fails.

> [!TIP]
> `Pilot`, `BufferAssertions` and `GoldenFrames` ship as `io.worxbend::tui-test`, so add it as a
> test-only dependency (`mvn"io.worxbend::tui-test:0.16.1"`) rather than copying the harness.

🧪 **[Testing guide →](website/docs/testing.md)**

## 📦 Native binaries, zero reflection config

```bash
./mill examples.showcase.nativeImage
```

CI compiles every example under `examples/` with GraalVM `--no-fallback`, then launches each
without a TTY to verify a safe exit. The list is derived from the directory, so adding an example
adds a native-image build. Reflection and dynamic class loading are rejected in main Scala sources.

📦 **[Native-image guide →](website/docs/native-image.md)**

## 🧰 Build from source

Needs JDK 25 or newer — the published artifacts declare `-release:25`, so class files built and run by an
older JDK are rejected. `./mill` pins its own build process to `temurin:25` via `.mill-jvm-version`
regardless of whatever JDK is on `PATH`, but a real JDK 25+ is still required to run the `./mill` launcher
itself and, for the native-image examples, `graalvm-community:25.0.2` (`macOS x64` tops out at 25.0.1 on
GraalVM CE — override `TuiExampleModule.jvmVersion` in `build.mill` locally on that platform).

```bash
git clone https://github.com/oleksandr-balyshyn/glyphora.git
cd glyphora

./mill __.compile        # build everything
./mill __.test           # run every suite
./mill __.publishLocal   # install 0.16.1 into your local Ivy cache
```

Day-to-day development:

```bash
./mill widgets.test                                  # one module's suite
./mill core.test.testOnly io.worxbend.tui.core.RectSpec        # one suite
./mill core.test.testOnly io.worxbend.tui.core.RectSpec -- -z inset   # one test

./mill mill.scalalib.scalafmt.ScalafmtModule/reformatAll __.sources
./mill mill.scalalib.scalafmt.ScalafmtModule/checkFormatAll __.sources   # CI gate

./mill examples.showcase.run   # manual product tour against a real terminal
```

Docs and the shared Wiki export:

```bash
(cd website && npm ci && npm run build)
node scripts/export-wiki.mjs --output build/wiki
```

🧰 Read **[Contributing](website/docs/contributing.md)** for the widget checklist, quality
gates, docs workflow, and pull-request expectations. Shared visual and editorial rules live in
[`docs/STYLE_GUIDE.md`](docs/STYLE_GUIDE.md).

## 📚 Documentation map

The same Markdown publishes to the [📖 GitHub Pages site](https://oleksandr-balyshyn.github.io/glyphora/)
and the [📚 GitHub Wiki](https://github.com/oleksandr-balyshyn/glyphora/wiki) — `website/docs/`
is canonical.

| | Guides |
|---|---|
| 🟢 **Start** | [Introduction](website/docs/intro.md) · [Getting started](website/docs/getting-started.md) |
| 🧠 **Understand** | [State & signals](website/docs/state-and-signals.md) · [Layout & style](website/docs/layout-and-style.md) · [Architecture](website/docs/architecture.md) |
| 🏗️ **Build** | [App shell](website/docs/app-shell.md) · [Widgets](website/docs/widgets.md) · [Forms](website/docs/forms-and-validation.md) |
| ⚙️ **Integrate** | [Async & timers](website/docs/async-and-timers.md) · [Mouse & focus](website/docs/mouse.md) · [Motion](website/docs/motion.md) |
| ✅ **Ship** | [Testing](website/docs/testing.md) · [Native binaries](website/docs/native-image.md) · [Troubleshooting](website/docs/troubleshooting.md) |

## 🤝 Contributing

Contributions are welcome across runtime behavior, widgets, examples, tests, documentation, and
design. Six gates protect the design and every one of them fails the build: **no runtime
reflection**, **no `String.substring` for layout math outside `CharWidth`**, Scalafmt, Scalafix
(main *and* test sources), warnings-as-errors, and a `--no-fallback` native-image build of every
example under `examples/`. [`CONTRIBUTING.md`](CONTRIBUTING.md) has the exact commands to run
before you push; the [full guide](website/docs/contributing.md) has the widget checklist and the
architecture rules.

<div align="center">

**[🤝 Contributing](CONTRIBUTING.md)** ·
**[📖 Full guide](website/docs/contributing.md)** ·
**[🐛 Report an issue](https://github.com/oleksandr-balyshyn/glyphora/issues/new/choose)** ·
**[📜 Code of conduct](CODE_OF_CONDUCT.md)** ·
**[📚 Wiki](https://github.com/oleksandr-balyshyn/glyphora/wiki)**

</div>

## 📜 License

[MIT](LICENSE) — go build something glyphorious. ✦

<div align="center">
<sub>Built with Scala 3 · Mill · JLine · GraalVM</sub>
</div>
