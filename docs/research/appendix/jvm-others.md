# glyphora vs JVM / Scala / other-language TUI libraries: competitive scan

Researched 2026-10-18. Sources: `gh api` repo metadata and READMEs (saved under
`tui-research/readmes/`), shallow clones of tamboui, jatatui and tui4j (`tui-research/clones/`),
TamboUI AsciiDoc sources (`tamboui/docs/src/docs/asciidoc/*.adoc`), the Mosaic CHANGELOG, JLine
release notes, the Brick user guide (`docs/guide.rst`), and the Laravel Prompts docs. `web_extract`
was not available in this session (search-only backend), so every claim here comes from GitHub
sources. Anything I could not confirm against a source is marked **(unverified)**.

Each idea is checked against glyphora at `/home/worxbend/Projects/Github/glyphora` (v0.19.0,
560 commits, last commit 2026-10-18).
Tags: **[HAS]** already present (file cited) · **[PARTIAL]** some of it exists · **[NEW]** absent.
Effort S/M/L, value H/M/L.

---

## 1. Competitive matrix (JVM + Scala)

Stars and last push are from `gh api repos/...` on 2026-10-18.

| Dimension | **glyphora** | TamboUI | jatatui | tui4j | Lanterna | Mosaic | Kotter | Mordant | layoutz | siglyph | terminus | cue4s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Lang / model | Scala 3; signals + declarative `Element` tree over immediate-mode widgets | Java; immediate + TuiRunner + fluent Toolkit DSL (3 levels) | Java 21; ratatui 0.30 port + optional React hooks layer | Java; Elm (Bubble Tea/Bubbles/Lipgloss port) | Java; Terminal → Screen → gui2 windowed widgets | Kotlin; Compose runtime (snapshot state) | Kotlin; imperative `session/section` + `liveVarOf` | Kotlin MPP; styled-output and animation library, not a TUI framework | Scala; ANSI string layout + Elm runtime | Scala 3; retained component tree (pi-tui style) | Scala 3; tagless-final terminal algebra + experimental `ui` with signals | Scala 3; prompts only |
| Layout | Constraint solver (Length/%/Ratio/Min/Max/Fill) + Flex + responsive bands + layers/portal/positioned | Constraints + Flex + **Columns/Grid (template areas)/Dock/Stack/Flow** + CSS layout props | ratatui Cassowary-style constraints; React row/column/stack | Lipgloss joins/place | Linear/Grid/Border/Absolute layout managers | Compose Row/Column/Box, modifiers, intrinsic size | Grid with fit/star columns, spans | Panels, tables, columns | row/column/box combinators | VStack/HStack | Row/Column, constraints, insets | n/a |
| Reactivity | Fine-grained `Signal`/`Computed`, dependency tracking per evaluation, `useSignal` hooks | Event → redraw; no signals | `useState`/`useEffect` hooks, fiber reconciliation | Elm `update` → `view` | Imperative listeners | Compose snapshot state (strongest of the group) | `liveVarOf`/`liveListOf` auto-rerender | n/a (animations are refreshed) | Elm `update` | Imperative setters | Signals (`ui/.../Signal.scala`) | n/a |
| Widgets (approx.) | ~80 DSL factories, 100+ widget files | ~25 widget packages, 40 toolkit elements | ratatui set + ~20 components | Bubbles set (~15) | ~35 gui2 components | Text/Row/Column/Box/Filler (minimal) | text, input, grid, anim | tables, panels, progress, select list | ~40 elements + 8 chart types + prompts | ~15 components | ~6 | ~8 prompt types |
| Styling | `Style`/`Theme` semantic roles, `HighContrast`; code only | **TCSS stylesheets**: selectors, pseudo-classes, variables, nesting, hot reload | Style values, Theme context | Lipgloss | **Property-file themes** (6 bundled) | Modifiers | inline color calls | Theme + markup | fluent `.color` | theme hooks | style props | n/a |
| Testing | **Pilot** (keys, mouse, drag, paste, focus, resize, ManualClock), golden frames, `Snapshot` | Pilot (click by element id), **VHS tape driving + asciicast recording**, JFR events | `TestHarness` on embeddable `Renderer` | n/a | Virtual terminal (unverified) | `mosaic-testing` | `kotter-test-support`, virtual terminal | n/a (unverified) | n/a | Virtual and stream backends | n/a | n/a |
| Native image | GraalVM, zero reflection, CI builds every example `--no-fallback` | GraalVM documented; `@OnAction` processor avoids reflection | JNI registration for native-image (commit `96f27ae`) | n/a | Pure Java (native-image unverified) | Kotlin/Native (no GraalVM needed) | Kotlin/Native + GraalVM docs | Kotlin/Native | **Scala Native** | **Scala Native** | Scala Native | Scala Native |
| Images | Half-block only; `writeRaw` + `DiffDirective.Skip` reserve cells for a protocol (`terminal/.../Backend.scala:340`) | **Kitty, iTerm2, Sixel, Braille, half-block** (`tamboui-image`) | n/a | n/a | `ImageComponent` (text art) | n/a | twemoji in virtual terminal | n/a | **Kitty images** inline | image protocol helpers | n/a | n/a |
| Markdown | Viewer: headings, lists, quotes, fences with highlighting, OSC 8 links; **no tables, images or nested lists** (`widgets/.../Markdown.scala:22`) | CommonMark + GFM, **streaming sanitizer** for LLM output, CSS resolver | n/a | No (Glamour not ported) | n/a | n/a | n/a | `mordant-markdown` | n/a | dependency-free markdown module | n/a | n/a |
| Mouse | Full: buttons, drag, hover/all-motion, 4-way wheel | Full + `DragHandler` + horizontal wheel | Full + hover/drag (`a70c8df`) | Yes | Yes | n/a (unverified) | n/a | Raw-mode events | n/a | `MouseRegion`, selection | n/a | n/a |
| Inline mode | `Viewport.Inline(n)`, `printAbove`, styled `insertBefore` | Inline display, InlineTuiRunner, InlineToolkit | n/a | Yes (Bubble Tea default) | n/a | **Inline-first** (Ink style) + `StaticEffect` | **Inline-first**, `aside` | Animations inline | `loader(iter)` + inline | Normal-screen differential | n/a | Inline prompts |
| Async | Owner-scoped `Async.run`, bounded admission, `Timers`, `TaskScope` | Scheduler injection, `runOnRenderThread` | `useEffect`/`useTimeout` | `Cmd` | GUI thread | Coroutines (`LaunchedEffect`) | `run {}` block + timers | coroutines module | `Cmd`/`Sub` (file watch, HTTP poll) | Cancellable async providers | Cats Effect runner (`core-ce`) | sync, Future, Cats Effect |
| Docs | Docusaurus site + wiki: 29 pages including 3 build tutorials, cookbook, troubleshooting | AsciiDoc site (19 guides) + demo videos from VHS tapes | README + DESIGN.md | Mintlify, tutorial, compat maps | Wiki/tutorial | Dokka + README | Very long README | Docs site | Long README | README + docs/ | creativescala site | README |
| Scala.js / Native | **Neither** (JVM 25 only) | n/a | n/a | n/a | n/a | Kotlin MPP | Kotlin MPP | Kotlin MPP | **JVM/JS/Native** | JVM + Native | JVM/JS (xterm.js)/Native | JVM/JS/Native |
| Min JDK | **25** | 8 (Panama backend 22+) | 21 | n/a | 8 | 11 | n/a | n/a | n/a | n/a | n/a | n/a |
| License | MIT | MIT | MIT | MIT (README; GitHub says NOASSERTION) | **LGPL-3.0** | Apache-2.0 | Apache-2.0 | Apache-2.0 | Apache-2.0 | MIT | Apache-2.0 | Apache-2.0 |
| Stars / last push | 0 / 2026-10-18 | 682 / 2026-10-18 | 288 / 2026-08-22 | 131 / 2026-08-14 | 2630 / 2026-07-07 | 2773 / 2026-10-16 | 698 / 2026-08-09 | 1246 / 2026-08-31 | 370 / 2026-09-01 | 3 / 2026-10-13 | 42 / 2026-10-13 | 45 / 2026-06-24 |

Notes. JLine 3/4 (1807★, BSD-3, 4.4.8 released 2026-10-11) is glyphora's terminal layer, not a
competitor. fansi (237★, last push 2025-10) is styled strings only. kitlangton/zio-tui (57★) has had
no push since 2024-02, so it is effectively dormant. There is no repo under the names "termflow"
(Scala) or "tui-scala": tui-scala is preserved as a branch of jatatui (`main-tui-scala`). Searching
Maven Central for `g:com.worxbend` returned 0 hits at research time, so glyphora's own
publish status is **(unverified)**.

---

## 2. Per-project analysis

### TamboUI: tamboui.dev, github.com/tamboui/tamboui (682★, MIT, Java 8+)
**Positioning:** the main JVM competitor, and the project glyphora originally borrowed from. It is
moving fast: 0.1.0 shipped 2026-02-18 and 0.6.0 shipped 2026-10-12, six minor releases in eight
months. Modules: core, widgets, tui, toolkit, css, annotations, processor, image, markdown,
toolkit-markdown, picocli, tfx, tfx-toolkit, tfx-tui, and the jline3/panama/aesh backends.
**Model:** three API levels (immediate → `TuiRunner` → Toolkit DSL), plus CSS on top.

**Exclusive features:**
- **TCSS** (`styling.adoc`, 1206 lines): type, class and `#id` selectors; `:focus :hover :disabled :active :selected :first-child :nth-child(even)`; `$variables`; nesting with `&`; `!important`; layout properties (constraints, flex, grid-template-areas) settable from CSS; runtime theme switching; `StyleEngine.reloadStylesheet` for hot reload.
- **Recording and playback** (`recording.adoc`): `-Dtamboui.record=x.cast` writes an asciicast from any app with no code change, and `-Dtamboui.record.config=script.tape` drives the app from a **VHS-format tape**. Pitched as "letting AI agents interact with and verify TUI applications". Recent commit `f00b7684`: tapes can press function keys and wait for screen text.
- **Buffer export** to **SVG, HTML and ANSI text**, with cropping (`developer-guide.adoc` §Exporting).
- **JFR events** (`tracing.adoc`): `terminal.draw`, `toolkit.route`, `focus.change`, `focus.navigation`, `candidate`, `global.handler`, `drag.state`.
- **Image protocols**: Kitty, iTerm2, Sixel, Braille, half-block (`tamboui-image/.../{Kitty,ITerm,Sixel,Braille,HalfBlock}Protocol.java`).
- **Markdown streaming**: `PartialMarkdownSanitizer` trims an unterminated `**`, `[label](` or a bare `##` so text arriving token by token from an LLM renders stably (`markdown.adoc` §Streaming).
- **Bindings in `.properties` files**, plus an `@OnAction` annotation processor that generates `ServiceLoader` registrars (no reflection).
- **Pluggable backends** found through `ServiceLoader`: JLine3, Panama FFM (no dependencies), Aesh (SSH and HTTP-hosted terminals, `demos/aesh-ssh-http-demo`).
- **Layouts**: `Grid` with CSS grid-template-areas (spans, contiguity checked), `Dock`, `Flow` (wrapping), `Stack`.
- BBCode-style **markup text** (`[bold red]x[/]`, `markup.adoc`), `WaveText`, a Picocli `TuiCommand` mixin, `ErrorDisplay`/fault-tolerant rendering (`ErrorPlaceholder.java`), `LazyElement`.
- tfx effects: dissolve, expand, fade, paint, slide, stretch and sweep shaders, a cell-filter DSL, and a `TFxColorSpace` for interpolation.

**Borrow for glyphora:**
- [NEW] **Asciicast recorder + tape-driven playback** as a runner option or system property. glyphora already has `CompletedFrame`/`onFrame` hooks and an exact `HeadlessBackend` (`runtime/.../Runner.scala:60`), so most of the plumbing exists. S–M / **H** (demos, bug reports, agent verification).
- [NEW] **SVG/HTML export of a `Buffer`**. `Snapshot.render` already produces the buffer (`dsl/.../Snapshot.scala`); add an encoder in test-support or core. Docs screenshots would then be generated by CI rather than hand-made. S / **H**.
- [NEW] **JFR events** for draw, route, focus and async admission. Zero cost when disabled; uses `jdk.jfr` (no reflection, and it works in native-image). S / M.
- [PARTIAL] **Image protocols.** glyphora has half-block `Image` (`widgets/.../Image.scala:7` says "Kitty/iTerm2/Sixel remain out of scope"), `writeRaw` + `DiffDirective.Skip` (`terminal/.../Backend.scala:340`), and cell-pixel size (`terminal/.../WindowSize.scala:24`), so the hooks are ready but there is no encoder. M / **H**.
- [NEW] **Streaming-safe Markdown** sanitizer plus GFM tables and nested lists. Current exclusions are documented at `widgets/.../Markdown.scala:22`. S (sanitizer) / M (tables) / **H** for LLM-chat apps.
- [NEW] **External keybinding config.** glyphora's `KeyBindings` are code-only (`dsl/.../KeyBindings.scala`). The `KeyEvent.parse` vocabulary already exists, so loading `action = ctrl+s, f2` from a file is cheap. S / M.
- [PARTIAL] **Grid with template areas / Dock / Flow.** glyphora has rows, columns, layers, positioned and responsive, but no area templates and no wrap-flow layout (no `grid`/`flow` factory in `ElementFactories.scala`). M / M.
- [NEW] **Stylesheets.** A TCSS-like external style layer conflicts with glyphora's typed `Theme`. A cheaper middle ground is a typed **class → Style map** on `Theme` with a hierarchy (see Brick attr maps below). L / M.
- [NEW] **Pilot by element key**: `pilot.click("save")`, `find(key)`. glyphora's Pilot is coordinate-based (`test-support/.../Pilot.scala:111`) even though elements carry `key`s. S / **H**.
- [NEW] Panama/FFM backend (Java 22+, no JNI). glyphora already requires JDK 25, so FFM is always available. M / L (JLine 4 already ships an FFM provider).
- [NEW] SSH/remote serving (Aesh). L / L.
- [HAS] Synchronized output 2026 (`terminal/.../AnsiSequences.scala:52`), bracketed paste, window title (`TitleStack.scala`), OSC 8 (`AnsiSequences.scala`), horizontal wheel (`core/.../MouseEvent.scala:17`), drag (Pilot `drag`, `SplitPane`), inline mode (`runtime/.../Viewport.scala:26`), fault isolation in `Async`.

### jatatui: github.com/oyvindberg/jatatui (288★, MIT, Java 21, bleep build)
**Positioning:** a faithful ratatui v0.30.0 port, and the successor to tui-scala. The version
number tracks upstream ratatui. **Distinctive:** a **JNI binding to Rust crossterm** with native
libraries for 5 platforms instead of JLine, plus an opt-in **React layer** (`jatatui-react`):
fiber reconciliation that drops hook state when the component type changes at a slot,
`stopPropagation`, `ANY_KEY`/`ANY_CHAR` predicates, an embeddable `Renderer` for your own loop, and
`TestHarness`. `jatatui-components` adds a router, `formProvider`/`useField`, `toastsProvider`, a
picker, and IntelliJ-style `FuzzyMatch`. Recent: `useFrame` and full hover/drag (`a70c8df`).

**Borrow:**
- [HAS] Hooks (`dsl/.../ViewState.scala` `useSignal`/`useState`/`keyed`), portal (`portal(…)`), command palette with fuzzy match (`dsl/.../Fuzzy.scala`, `CommandPalette.scala`), toasts, screens (`ScreenStack.scala`), embeddable engine ("Embed the engine without TuiApp" in `app-shell.md`).
- [NEW] **`useEffect`-style mount/unmount hooks** tied to a call site, e.g. start a poller while a panel is visible and cancel it when the panel leaves the tree. glyphora has `TaskScope`/owner-scoped tasks but no element-lifetime hook (grep for `useEffect|onMount` finds nothing). M / **H**.
- [NEW] **Hook-state reset when a node's type changes at a slot**, as a documented guarantee. Check whether `ViewState` already behaves this way. S / M.
- [NEW] Tracking upstream ratatui API names as an explicit compatibility or porting table (jatatui's version equals the ratatui version). Low value for glyphora. L / L.

### tui4j: github.com/WilliamAGH/tui4j (131★, Java, MIT per README)
**Positioning:** Bubble Tea, Bubbles, Lipgloss, x/ansi and Harmonica ported 1:1 under `compat.*`,
with a **porting-status table** per upstream example (`docs/compatibility/status.md`) and **Spring
Boot examples**. It started as a fork of Latte. Showcase app: Brief, an AI chat client.

**Borrow:**
- [HAS] Spring physics (`core/.../Spring.scala`), suspend for `$EDITOR` (`Backend.suspend`, `Runner.scala:261`), paginator, focus/blur events.
- [NEW] **"Upstream example parity" table**: port well-known Bubble Tea and ratatui demos (tetris, chat, package-manager, file-picker) as glyphora examples. Cheap marketing for people switching from Go or Rust. M / M.
- [NEW] Spring/DI integration recipe (a Spring Boot or ZIO-layer example). S / L.

### Lanterna: github.com/mabe02/lanterna (2630★, **LGPL-3.0**, pure Java)
**Positioning:** the incumbent, curses-like with a windowing toolkit. **Exclusive:** `gui2`
**MultiWindowTextGUI** with a window manager, window decorations, shadows, modal windows and
`MenuPopupWindow`; a **bundled Swing/AWT terminal emulator** (`SwingTerminalFrame`,
`AWTTerminalFrame`) so apps run from IDE consoles; `TelnetTerminalServer`; **property-file themes**
(6 bundled: `bigsnake`, `blaster`, `businessmachine`, `conqueror`, `default`, `defrost`); and
**i18n bundles** for built-in strings (`lanterna-ui_{da,de,es,fi,fr,ja,nb,sv,zh_CN,zh_TW}.properties`).

**Borrow:**
- [NEW] **IDE-console fallback**: when there is no TTY (`./mill run`, an IntelliJ run window), open a Swing window that hosts the app. glyphora exits with `UnsupportedTerminal("dumb terminal (no TTY attached)")` (`terminal/.../JLine3Backend.scala:658`). Kotter does this too (below). M / M. It cannot be included in native images without AWT, so it would have to be an opt-in module.
- [NEW] **Localisable built-in strings.** `dialog(… buttons = Seq("OK"))` (`dsl/.../ElementFactories.scala:456`), confirm dialogs, help overlay text and paginator labels are hard-coded English. A `Messages` value carried in `Theme` or `AppServices` would fix that. S / M.
- [PARTIAL] Overlapping movable windows. glyphora has `dialog`, `layers` and `portal`, but no draggable window manager. L / L.
- [NEW] Telnet server: L / L.

### JLine 3/4: github.com/jline/jline3 (1807★, BSD-3), glyphora's own dependency
Recent releases: 4.4.0 added Kitty keyboard protocol, **mode 2048 in-band resize**, mode 2026 and
musl support. 4.3.0 exposed `ScreenTerminal` scrollback and cell decoding. 4.4.x were security
fixes (ReDoS via `SafeRegex`, telnet). jline-reader has completers, history search, widgets, and
nano/less builtins.

**Borrow:**
- [NEW] **In-band resize (mode 2048)** instead of SIGWINCH plus a size query. Grep finds no 2048 in glyphora; it would give atomic size + pixel-size reports and work over SSH. S / M.
- [NEW] **Input history** for `input(…)`: Up/Down recall plus Ctrl-R search, as in jline-reader and siglyph. `TextInputState` has no history (`widgets/.../TextInput.scala:19`); `TextArea` has undo only. S / M.
- [NEW] **Use JLine's `ScreenTerminal` (a VT emulator) in tests** to feed the backend's real ANSI output through an emulator and assert the result. This is a stronger check than buffer-level `Pilot`, because it catches `FrameEncoder` bugs. M / M.
- [NEW] Pager builtin (`less`-like) as a widget or screen. glyphora has `scrollView` + `markdown`; a search-in-pager feature is missing. M / M.

### Mosaic: github.com/JakeWharton/mosaic (2773★, Apache-2.0, Kotlin MPP)
**Positioning:** Jetpack Compose runtime for the terminal, Ink-style and inline-first.
**Exclusive:** **Compose snapshot state** as the reactive model; Kotlin/Native binaries;
`mosaic-animation` (an analog of compose animation-core); `StaticEffect` (render once into
scrollback); `LocalStaticLogger`; `TerminalState` with **focused**, **theme light/dark (live)** and
**size in pixels**; `focused` bound to a Lifecycle (`LifecycleResumeEffect`); `NonInteractivePolicy`
for when there is no TTY; an FFM native bridge on JDK 22+; `IntrinsicSize`. Source: CHANGELOG
0.15–0.18 and Unreleased.

**Borrow:**
- [NEW] **Live terminal colour scheme (dark/light)** exposed as a `Signal`: a query on start plus
  **mode 2031** change notifications, which drive automatic `Theme.Dark`/`Theme.Light` selection.
  glyphora can decode the OSC 11 *reply* (`terminal/.../InputDecoder.scala:344`) but never issues
  the query, and grep finds no 2031 or `colorScheme`. S / **H**.
- [PARTIAL] **Focus as lifecycle**: glyphora delivers `focusGained`/`focusLost` events (Pilot
  `focusGained`, mode 1004 in `TerminalCapabilities.scala:70`). The missing step is pausing ambient
  ticks and pollers while the terminal is unfocused. S / M.
- [NEW] **`NonInteractivePolicy`**: when there is no TTY, choose between failing, rendering one
  final frame to stdout, or rendering plain text. glyphora examples "exit cleanly with no TTY" but
  produce no useful output in a pipe. S / M.
- [HAS] Inline `insertBefore` / `printAbove` (`dsl/.../TuiApp.scala:431,451`) covers `StaticEffect`/static logging.

### Kotter: github.com/varabyte/kotter (698★, Apache-2.0, Kotlin MPP)
**Positioning:** for CLI flows, not full-screen apps: `session { section {…}.run {…} }` blocks
run in sequence. **Exclusive:** `liveVarOf`/`liveListOf` (rerender on write); **`offscreen {}`**
buffers that you can measure, then emit row by row (this is how `bordered` is built); **`aside {}`**
one-off output above the live section; `runUntilSignal`; input with `Completions`, `viewMap` (for
masking), `customFormat` (per-character colouring), `rejectInput()`, and `multilineInput()`;
`textAnimOf`/`renderAnimOf` templates; a **Virtual Terminal** Swing fallback with `firstSuccess()`
backend chaining, plus a `twemoji` artifact; a README section on distribution (jlink, jpackage,
GraalVM, Kotlin/Native).

**Borrow:**
- [NEW] **Backend fallback chain**: `firstSuccess(JLine, Virtual/Swing, PlainStdout)`. M / M.
- [NEW] **Sequential "section" API for CLI flows**: run a small inline app until it signals a result, then run the next one, with one-shot prompts built on glyphora widgets. See the cue4s, layoutz `Ask` and Laravel Prompts sections; four of the scanned libraries offer this. M / **H**.
- [PARTIAL] Per-character input formatting and validation-reject hook. glyphora has `templateInput`, masking (`core/.../Masked.scala`) and form validators; a live `rejectInput`-style filter on `input` is unverified. S / L.
- [HAS] `offscreen` measurement corresponds to `Measured` + `Snapshot.render`.

### Mordant: github.com/ajalt/mordant (1246★, Apache-2.0, Kotlin MPP)
**Positioning:** rich styled output (the Kotlin answer to Python's Rich) and the rendering engine
behind Clikt help. **Exclusive:** automatic ANSI-level detection per stream, tables and panels for
plain stdout, `progressBarLayout` DSL with **MultiProgressBarAnimation** (aligned columns across
tasks), animate-on-your-own-thread (`BlockingAnimator.runBlocking`), `interactiveSelectList` and
multi-select, `mordant-markdown`.

**Borrow:**
- [NEW] **Render an Element to an ANSI string for plain stdout**, with no runner and no alternate screen: `Snapshot.render(view, size)` plus a "trim to content" step and an ANSI encoder. glyphora has `plainText` on `Text`/`Line` but no ANSI string output of a composed frame. This makes glyphora useful for non-interactive CLI output (tables and reports printed once). S / **H**.
- [NEW] **Multi-task progress layout** with aligned columns: a `progressGroup` of label | bar | % | ETA | rate rows. glyphora has `progressBar`, gauges and `ProgressMeterBuilders.scala`; aligned multi-task progress with ETA/rate cells is (unverified) absent. S / M.
- [NEW] **Clikt/scopt/decline/mainargs integration**: a `--no-tui` flag, help rendered with glyphora styles. TamboUI has a picocli module. S / M.

### layoutz: github.com/mattlianje/layoutz (370★, Apache-2.0, Scala 2.12/2.13/3, **JVM/JS/Native**)
**Positioning:** zero-dependency compositional ANSI strings, with an Elm-style `LayoutzApp` on
JVM and Native. Also ported to Haskell and OCaml. It is the closest Scala competitor for
"beautiful CLI output". **Exclusive:** built-in `Cmd`s (file I/O, HTTP, clipboard) and `Sub`s
(`Sub.file.watch`, `Sub.http.pollMs`, `Sub.time.everyMs`); **`Ask.*` one-shot prompts** (input,
confirm, choose, chooseMany, write, filter, file, pager, `spin { task }`); **`loader(iterable)`**
which wraps any `Iterable`/`Iterator` with a live bar or spinner; **box plot**; **Kitty inline
images** (`kitty.image(path, cols, rows)`, `kittyRGBA`) that compose inside boxes and tables.

**Borrow:**
- [NEW] **`loader(iterable)` / `Iterator.withProgress`**: one line to add a progress bar to a loop, rendered inline. S / **H** for adoption.
- [NEW] **`Ask` one-shot prompts** that return values directly (`Ask.choose(…): Option[A]`), built on glyphora's own widgets in an inline viewport. M / **H**.
- [NEW] **File-watch and HTTP-poll subscriptions** as ready-made `Timers`/`Async` helpers. glyphora has the primitives (`live-data.md` shows hand-rolled polling). S / M.
- [NEW] Box-plot chart. S / L.
- [NEW] Scala.js/Native cross-build of `tui-core` + `tui-widgets` (pure, no JLine). glyphora requires JDK 25 and has no cross-build. L / M (would let a browser show the same widgets through xterm.js, as terminus does).

### siglyph: github.com/zikolach/siglyph (3★, MIT, Scala 3, JVM + Scala Native, created 2026-06)
**Positioning:** a new Scala 3 rival modelled on pi-tui (the coding-agent TUI). It is aimed at
**agent prompt composers**. It publishes a **Java/Kotlin interop facade** and Scala-CLI single-file
demos. **Exclusive:** an `Editor` with **kill-ring, yank/yank-pop, prompt history, large-paste
compaction**, and jump-to-char; autocomplete for slash commands, `@file` attachments and `#tags`
with cancellable async providers and injectable debounce; **terminal colour-scheme and background
queries** (`queryTerminalColorScheme`, `onTerminalColorSchemeChange`); `setTerminalProgress`
(the OSC 9;4 taskbar progress); input drain; a Scala Native POSIX backend.

**Borrow:**
- [NEW] **Taskbar/tab progress (OSC 9;4)** from `Async` loads: Windows Terminal, Ghostty, iTerm2 and ConEmu show progress on the tab. Grep finds nothing. S / M.
- [PARTIAL] **Agent-composer editing.** glyphora's `TextArea` has a bounded undo stack (`widgets/.../TextArea.scala:32`) and `autocomplete`; kill-ring, history and `@`/`/` trigger completion are missing. M / **H** (LLM CLIs are the main TUI growth market in 2026).
- [NEW] **Scala-CLI single-file examples** (`//> using dep com.worxbend::tui-dsl:0.19.0`). The one-file onboarding path is missing from `getting-started.md`. S / **H**.
- [NEW] **Java-facing facade**: Java and Kotlin users cannot easily consume a Scala 3 DSL built on context functions. L / L.

### terminus: github.com/creativescala/terminus (42★, Apache-2.0)
**Positioning:** tagless-final terminal effects for Scala 3 on **JVM (JLine), Native and JS
(xterm.js)**. The experimental `ui/` module (2026) has Signal, Effect, Focus, a runtime with
`CatsEffectRunner`, and Button/Select/TextInput (tree listing via `gh api`). CHANGELOG v0.4.0
(2025-02): structured key parsing, Dimensions effect. Recent commits are a runtime/effect-queue
refactor and Scala 3.10.

**Borrow:**
- [NEW] **Effect-system adapters** (`tui-cats-effect`, `tui-zio`, Ox): run an `IO` from a handler and bring its result back to the render thread. glyphora's `Async` is executor-based. These would be separate, optional artifacts. M / M (the Scala audience expects them).
- [NEW] xterm.js/Scala.js target (as in layoutz). L / M.

### cue4s: github.com/neandertech/cue4s (45★, Apache-2.0)
**Positioning:** prompts only, on JVM, JS and Native, with sync, Future and Cats Effect variants.
**Exclusive:** `case class … derives PromptChain` with `@cue(_.text(…).validate(…))` annotations,
so a case class becomes a sequential prompt flow; `mapValidated` on prompts.

**Borrow:**
- [PARTIAL] glyphora has `deriveForm` (`macros/.../FormSpec.scala`), which builds a full-screen form. The new part is an **inline sequential "prompt chain" mode for the same derived spec**: one question at a time, like Laravel `form()` with Ctrl-U to go back. S–M / **H** (reuses existing macros).

### Kotlin Clikt (3018★), JBang, Picocli
TamboUI ships `tamboui-picocli` (a `TuiCommand` mixin) and a `jbang-catalog.json`, so
`jbang tamboui@...` runs demos with no clone. **Borrow:** [NEW] a one-command demo run via
`cs launch` / `scala-cli run` of the published showcase. S / M.

---

## 3. Other languages: distinctive ideas only

| Project | Stars | Distinctive idea | glyphora status | Effort/Value |
|---|---|---|---|---|
| **Brick** (Haskell, jtdaugherty/brick, 1744★) | | **Hierarchical attribute maps**: `attrName "list" <> attrName "selected"` falls back segment by segment and merges unset fg/bg from the parent (guide §How Attributes Work). **Attribute themes loadable from INI customisation files** (guide §Attribute Themes). **Viewport visibility requests** (`visible` makes the scrolling viewport follow the cursor without index arithmetic). **Keybinding API**: abstract events, user config parsing, **collision detection**, and help **generated as a Widget, plain text or Markdown** (§Customizable Keybindings, §Keybinding Collisions). Rendering cache (`cached name w`). Widgets request a cursor position. `joinBorders`. | Attr hierarchy [NEW]; theme files [NEW]; visibility requests [NEW] (`ScrollViewState.scrollTo(row)` is manual, `widgets/.../ScrollView.scala:32`); key collisions [PARTIAL] (shadowing handled, `dsl/.../TuiApp.scala:204`); keymap→Markdown export [NEW]; render cache [NEW]; cursor request [HAS] (`assertCursorAt`, `unicode-and-accessibility.md`); joined borders [HAS] (`widgets/.../BorderMerge.scala`) | Visibility S/H; attr map M/M; key Markdown export S/M; cache M/M |
| **libvaxis / vxfw** (Zig, 2027★) | | **No terminfo**: every feature detected by query. Mouse **pointer shapes (OSC 22)**, notifications (**OSC 9 / OSC 777**), program status OSC 7501, **mode 2027 (Unicode core)** width negotiation, **2031** colour-mode updates, **2048** in-band resize, **kitty text-sizing protocol** (explicit width), Flutter-like `vxfw` with a button that cancels the press if the release lands elsewhere. | Query-based caps [HAS] (`ReplyQueries.probeCapabilities`, `TerminalCapabilities.scala`); undercurl [HAS] (`core/.../Style.scala`, `terminal/.../Sgr.scala`); OSC 22 [NEW]; OSC 9/777 desktop notify [NEW] (`Notifications` are in-app toasts only, `dsl/.../Notifications.scala`); 2027/2031/2048 [NEW]; text-sizing [NEW] | OSC 22 S/M (hover shows a hand on links and buttons, I-beam on inputs); OSC 9/777 S/M; 2027 S/M (kills emoji-width drift) |
| **nocterm** (Dart, 408★) | | **Hot reload** of a running TUI (`dart --enable-vm-service`); Flutter `StatefulComponent`; `testNocterm` + `tester.pumpComponent`/`sendKey`; IDE lints package ("wrap with", "convert to stateful"); companion `nocterm_bloc`. | Hot reload: [NEW]. Not feasible on the JVM in general; a narrower version is watch, recompile and restart while restoring the signal snapshot. | L/M |
| **php-tui** (622★) | | ratatui port with **font rendering** (BigText fonts) and image shapes; Cassowary solver as a separate package. | BigText [HAS] (`widgets/.../BigText.scala`) | n/a |
| **Laravel Prompts** (728★) | | `form()` lets the user **go back to the previous prompt with Ctrl-U**; `task()` shows a spinner with a scrolling log area, a sub-label and streamed text, and can keep its summary; `search`/`multisearch` with async options; `pause`; **automatic fallback** to plain stdin when the terminal is unsupported (`Prompt::fallbackWhen`). | All [NEW] as an inline prompt layer | M/H (part of the "prompt layer" idea) |
| **Owl** (Elixir, 534★) | | LiveScreen blocks that **capture stdio output and print it above** live blocks; masked-secret logging when running shell commands; `$EDITOR` editing. | Capture of stray `System.out` [NEW]: glyphora documents "println destroys the UI" (`troubleshooting.md` §Debug output); `System.setOut` redirect into `printAbove` while running would remove that trap. `$EDITOR` [HAS] (`suspend`). | S/H |
| **Ratatouille** (Elixir, 850★, dormant since 2023) | | Elm runtime with an HTML-like view DSL | n/a | |
| **TTY toolkit** (Ruby, 2531★, dormant since 2022) | | 23 small gems (`tty-prompt`, `tty-pager`, `tty-pie`, `tty-font`, `tty-markdown`, `tty-link`, `tty-which`, `tty-platform`): Unix-philosophy decomposition. | glyphora's 7 modules are coarse; a slim `tui-ansi`/`tui-print` artifact (core + widgets, no JLine) would serve print-only CLIs. | M/M |
| **bubbletea-ruby** (148★) | | Ruby **bindings** to Go bubbletea, not a port | n/a | |
| **SwiftTUI** (1537★, dormant since 2024) | | SwiftUI `@State/@Binding/@Environment`, **structural identity**, `GeometryReader`, modifiers applied across a collection | Structural identity [HAS] (`keyed`, `Focus.scala`); `responsive(Size => Element)` corresponds to GeometryReader [HAS] | n/a |
| **Term.jl** (Julia, 494★) | | Rich-style `tprint` markup, tree and repr inspection of data structures, progress | `inspect(value)` as a tree for debugging: [NEW], S/L | |
| **ltui** (Lua/C, 876★) | | Turbo Vision style windows, menubar, dialogs | Menubar [PARTIAL] (`menu`) | |
| **Lwd / Nottui** (OCaml, 156★) | | **Incremental computation** graph (`Lwd.var`, `Lwd.map2`) feeding UI; the same library also drives a DOM through tyxml-lwd. Nottui-pretty: an interactive pretty-printer. | Signals ≈ Lwd [HAS]; a reactive pretty-printer for Scala values [NEW] S/L | |
| **notty** (OCaml, 384★) | | Images as composable values (`I.(<|>)`, `<->`, `</>`), pure layout engine | Corresponds to `Element` [HAS] | |
| **Mint Tea** (OCaml, 473★) | | Elm on the Riot actor runtime | n/a | |
| **illwill** (Nim, 470★) | | Zero dependencies, no terminfo, Windows console support | glyphora has **no Windows support** ("Unknown", `faq.md:80`) | M/M |
| **term_ui** (Elixir, agentjido, 210★, active) | | Newer BEAM full-screen TUI (not inspected in depth) | n/a | |

---

## 4. Where glyphora is behind / ahead (honest)

### Behind
1. **Mindshare**: 0 stars, versus TamboUI 682, Mosaic 2773 and Lanterna 2630. No Maven Central hit for `g:com.worxbend` was found (unverified). No JBang/`scala-cli` one-liner, and no demo GIFs generated from tapes.
2. **Platform reach**: JDK 25 minimum. TamboUI targets Java 8+, jatatui 21, Mosaic 11. glyphora has no Scala.js or Scala Native build, while layoutz, siglyph, terminus and cue4s all have both. **No Windows** support (`faq.md:78`), whereas TamboUI, jatatui (crossterm), Lanterna and Kotter run on Windows.
3. **Images**: half-block only. TamboUI has Kitty, iTerm2 and Sixel; layoutz has Kitty.
4. **Markdown**: no tables, images or nested lists, and no streaming sanitizer (TamboUI has all of these).
5. **External styling and config**: no stylesheets, theme files or keybinding files. TamboUI has TCSS, `.properties` bindings and hot reload; Lanterna and Brick have theme files.
6. **Artifact tooling**: no asciicast recording, VHS tape playback, SVG/HTML export or JFR events (TamboUI has all four).
7. **Non-interactive and CLI use**: no element-to-ANSI-string printing, no one-shot prompt layer, no `loader(iterable)`, no fallback when there is no TTY (Mordant, layoutz, Kotter, cue4s and Laravel Prompts cover these).
8. **Modern terminal protocols**: no colour-scheme query or 2031, 2048 resize, OSC 22 pointer shape, OSC 9/777 desktop notifications, OSC 9;4 progress, or 2027 (libvaxis, Mosaic and siglyph have them).
9. **Layout vocabulary**: no grid-template-areas, Dock or wrapping Flow (TamboUI).
10. **Effect-system integration**: none. terminus and cue4s ship Cats Effect variants.

### Ahead
1. **Fine-grained reactivity in Scala 3**: dependency-tracked `Signal`/`Computed` with `SignalEquality` (IEEE-754 total order for doubles), plus hooks (`useSignal`, `keyed`). Only Mosaic (Compose) is comparable; TamboUI, jatatui and tui4j use event or Elm redraws.
2. **Strictest native-image story**: no reflection, grep-enforced in CI over modules *and* examples; every example built `--no-fallback` and run without a TTY in CI (`CLAUDE.md`). Form derivation uses `Mirror` (`tui-macros`), whereas TamboUI needs an annotation processor plus `ServiceLoader`.
3. **Testing depth**: Pilot with key releases, raw bytes (`sendBytes`/`sendEscape`), paste, focus, interrupt, `ManualClock`, `waitUntil`, cursor assertions, golden frames with an update env var, and a `Snapshot` checked against a live run (`SnapshotSpec`). It is broader than TamboUI's Pilot except for click-by-id.
4. **Unicode rigor**: a generated width table, grapheme clusters everywhere, no `substring` outside `CharWidth` (CI-enforced), and an exact caret. TamboUI's recent fix log is full of CJK and emoji bugs (#511, #527, #530).
5. **Widget breadth**: about 80 DSL factories, including heatmap, world map, pie, stacked, grouped and dual sparkline, calendar, spinner families, skeleton, marquee, animated text, directory tree and file picker. The largest set on the JVM.
6. **Motion system**: pure `Progress`/`Easing`/`Tween`/`Spring`/`Effect` in core, composable `sequence`/`parallel`/`delay`/`repeat` (`core/.../Effect.scala:92-125`), and deterministic in tests. Comparable to TamboUI tfx and ahead of everyone else.
7. **Async discipline**: owner-scoped `Async.run`, bounded admission (256), `TaskScope`, render-thread capture, and documented failure semantics (`async-and-timers.md`).
8. **App shell**: command palette, screen stack, toasts, help overlay derived from bindings, splash player, effect stack, semantic `Theme` with a `HighContrast` variant, `Form.accessible`.
9. **Docs**: 29 pages including three end-to-end build tutorials, a cookbook and troubleshooting, enforced by `DocumentedFactorySpec`/`DocumentedKeySpecSpec`.
10. **Terminal correctness**: capability probing (`ReplyQueries`), 2026 sync gated on the probe, kitty keyboard flags including key release, diff-batched output, `writeRaw` + `DiffDirective.Skip` already designed for image protocols.

---

## 5. Top 12 ideas, ranked (value ÷ effort, fit with glyphora's invariants)

| # | Idea | Source(s) | Tag | Effort | Value | Why it fits |
|---|---|---|---|---|---|---|
| 1 | **Asciicast recording + VHS-tape playback** (`-Dglyphora.record=…`, or `RunnerConfig.withRecording`), reusing `onFrame` + `HeadlessBackend` | TamboUI `recording.adoc` | NEW | S–M | H | Exact headless frames already exist. Gives demos, bug reports and agent-driven verification. |
| 2 | **Buffer → SVG/HTML/ANSI export** (`Snapshot.render(…).toSvg`), used to generate the docs screenshots in CI | TamboUI export | NEW | S | H | Pure function over `Buffer`; screenshots can no longer drift from the code. |
| 3 | **Inline "prompt layer"**: `Ask.*` one-shot prompts, `loader(iterable)`, inline `deriveForm` prompt-chain with Ctrl-U back, and `task(label){ logger => }` | layoutz `Ask`/`loader`, cue4s `PromptChain`, Laravel Prompts `form`/`task`, Kotter sections | NEW / PARTIAL (`deriveForm`, `Viewport.Inline`) | M | H | Opens the CLI-tool market (most JVM CLI authors) using existing widgets and macros. |
| 4 | **Print an Element once to stdout as ANSI** (no runner, auto height via `Measured`), plus a no-TTY fallback policy | Mordant, Mosaic `NonInteractivePolicy`, layoutz | NEW | S | H | `Snapshot` + `FrameEncoder.encodeAll` almost do this already. |
| 5 | **Kitty/iTerm2/Sixel image encoders** behind the existing `writeRaw` + `DiffDirective.Skip`, with half-block fallback | TamboUI `tamboui-image`, layoutz, libvaxis | PARTIAL | M | H | The hook was designed for this (`Backend.scala:340`); capability probing already exists. |
| 6 | **Terminal colour-scheme signal** (OSC 11 query + mode 2031) for automatic dark/light `Theme`; also mode 2048 in-band resize | Mosaic 0.17, siglyph, libvaxis, JLine 4.4 | NEW (decoder PARTIAL) | S | H | One `Signal[ColorScheme]` in `AppServices`; fits the semantic-theme design. |
| 7 | **Streaming-safe Markdown + GFM tables/nested lists** | TamboUI `PartialMarkdownSanitizer` | NEW | S–M | H | LLM chat UIs are the growth segment; the sanitizer is a pure string function. |
| 8 | **Pilot by element key** (`click("save")`, `find(key)`, `boundsOf(key)`) | TamboUI Pilot | NEW | S | H | Elements already carry keys and frame coordinates are recorded (`FrameCoordinates.scala`); this makes tests robust to layout changes. |
| 9 | **Viewport visibility requests**: `scrollView` follows a `visible(key)`-marked child or the focused element automatically | Brick | NEW | S–M | H | Removes the manual `scrollTo(row)` arithmetic; this is a common source of bugs. |
| 10 | **Element-lifetime effects** (`useEffect(keys){ start; cleanup }`) bound to `TaskScope` | jatatui-react, Mosaic `LaunchedEffect` | NEW | M | H | Completes the hooks story; pollers stop when a panel unmounts. |
| 11 | **Agent-composer editing**: input history with Ctrl-R, kill-ring/yank, `/` and `@` trigger completion with an async debounced provider, large-paste compaction, plus a stdout-capture redirect into `printAbove` | siglyph, JLine reader, Owl | PARTIAL (`autocomplete`, `TextArea` undo) | M | H | Targets the 2026 LLM-CLI wave directly; the redirect also removes a documented trap. |
| 12 | **Modern OSC set**: OSC 22 pointer shapes on hover, OSC 9/777 desktop notifications from `Notifications`, OSC 9;4 tab progress from `Async`, and JFR events for draw/route/focus | libvaxis, siglyph, TamboUI `tracing.adoc` | NEW | S each | M | Each one is a small `AnsiSequences` + capability-gate addition; JFR needs no reflection. |

**Honourable mentions:** externalised keybindings and keymap→Markdown help export (Brick,
TamboUI); a typed hierarchical style-class map on `Theme` (Brick attr maps, as a typed alternative
to TCSS); grid-template-areas, Dock and Flow layouts (TamboUI); effect-system adapter artifacts
for Cats Effect, ZIO and Ox (terminus, cue4s); Scala-CLI single-file examples (siglyph); i18n
`Messages` for built-in strings (Lanterna); a Swing/virtual-terminal fallback module (Lanterna,
Kotter); a Scala.js/Native cross-build of core + widgets (layoutz, terminus); a `tui-print` slim
artifact (TTY toolkit); Windows support (most competitors have it).

---

### Source index
- TamboUI: https://github.com/tamboui/tamboui. Docs at `docs/src/docs/asciidoc/{recording,tracing,styling,bindings,layouts,markdown,backends,pilot-testing,developer-guide}.adoc`; https://tamboui.dev
- jatatui: https://github.com/oyvindberg/jatatui (`readme.md`; commits `a70c8df`, `96f27ae`)
- tui4j: https://github.com/WilliamAGH/tui4j (`README.md`, `docs/compatibility/status.md`)
- Lanterna: https://github.com/mabe02/lanterna (README, source tree: `gui2/*`, `terminal/swing/*`, `telnet/*`, `*-theme.properties`)
- JLine: https://github.com/jline/jline3 (README, releases 4.3.0–4.4.8)
- Mosaic: https://github.com/JakeWharton/mosaic (README, CHANGELOG.md)
- Kotter: https://github.com/varabyte/kotter (README §Offscreen, §Aside, §Virtual Terminal, §Testing)
- Mordant: https://github.com/ajalt/mordant (README, `docs/{guide,progress,input}.md`)
- layoutz: https://github.com/mattlianje/layoutz (README)
- siglyph: https://github.com/zikolach/siglyph (README)
- terminus: https://github.com/creativescala/terminus (CHANGELOG.md, `ui/` tree)
- cue4s: https://github.com/neandertech/cue4s (README)
- Brick: https://github.com/jtdaugherty/brick (`docs/guide.rst`)
- libvaxis: https://github.com/rockorager/libvaxis (README)
- nocterm: https://github.com/Norbert515/nocterm · php-tui: https://github.com/php-tui/php-tui · Laravel Prompts: https://github.com/laravel/prompts + https://github.com/laravel/docs/blob/master/prompts.md · Owl: https://github.com/fuelen/owl · Ratatouille: https://github.com/ndreynolds/ratatouille · TTY: https://github.com/piotrmurach/tty · SwiftTUI: https://github.com/rensbreur/SwiftTUI · Term.jl: https://github.com/FedeClaudi/Term.jl · ltui: https://github.com/tboox/ltui · Lwd/Nottui: https://github.com/let-def/lwd · notty: https://github.com/pqwy/notty · Mint Tea: https://github.com/leostera/minttea · illwill: https://github.com/johnnovak/illwill · bubbletea-ruby: https://github.com/marcoroth/bubbletea-ruby · zio-tui: https://github.com/kitlangton/zio-tui · fansi: https://github.com/com-lihaoyi/fansi
