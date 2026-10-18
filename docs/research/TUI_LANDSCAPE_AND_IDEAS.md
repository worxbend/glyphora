# TUI landscape research and idea backlog for glyphora

Date: 2026-10-18. Status: research and brainstorm, not a commitment. Nothing in this document is implemented yet.

Method: five parallel research passes (Rust; Python + JS/TS; Go + C/C++ + .NET; JVM/Scala + other languages; an
internal review of glyphora's own widget system). Star counts come from the GitHub API on 2026-10-18. Feature claims
come from READMEs, docs and shallow clones of about 20 repositories. Every "glyphora already has / lacks this" claim
was checked by grepping this repository. The five detailed reports, with per-project notes, file citations and source
URLs, are in [`appendix/`](appendix/):

| Report | Covers |
|---|---|
| [`appendix/rust.md`](appendix/rust.md) | ratatui 0.30, tachyonfx, ratatui-image, tui-widgets, tui-textarea/edtui, tui-tree-widget, tui-logger, ratzilla, cursive, tui-realm, iocraft, crossterm/termion/termwiz, helix/yazi/gitui/zellij |
| [`appendix/python-js.md`](appendix/python-js.md) | Textual, Rich, prompt_toolkit, urwid, asciimatics, pytermgui, Trogon, Ink, OpenTUI, blessed, Inquirer/clack/prompts, terminal-kit |
| [`appendix/go-cpp-dotnet.md`](appendix/go-cpp-dotnet.md) | Bubble Tea v2, Lip Gloss v2, Bubbles, Huh, Wish, VHS, Freeze, Gum, Glamour, tview, tcell, FTXUI, notcurses, Turbo Vision, Spectre.Console, Terminal.Gui v2 |
| [`appendix/jvm-others.md`](appendix/jvm-others.md) | TamboUI, jatatui, tui4j, Lanterna, JLine, Mosaic, Kotter, Mordant, layoutz, siglyph, terminus, cue4s, Brick, libvaxis, nocterm, Laravel Prompts, Owl, and others; competitive matrix |
| [`appendix/widget-system.md`](appendix/widget-system.md) | Internal review: 35 observed pain points with `path:line`, ~55 improvement ideas with API sketches |

Labels used throughout:

- Effort: **S** up to 2 days, **M** up to 2 weeks, **L** more.
- Value: **H**, **M**, **L**.
- Status: **NEW** (nothing like it exists), **PARTIAL** (pieces exist), **HAS** (already shipped; listed only for context).
- Votes: how many of the five research passes independently proposed the idea. Ideas that several passes reached on
  their own are the strongest signal in this document.

---

## 1. Landscape at a glance

| Ecosystem | Leaders (stars) | Dominant model |
|---|---|---|
| Rust | ratatui 23.0k, cursive 4.9k, crossterm 4.2k, iocraft 1.6k, tachyonfx 1.3k | Immediate-mode buffer diffing (ratatui); retained views (cursive); React-like hooks (iocraft) |
| Python | Rich 57.6k (output only), Textual 37.5k, prompt_toolkit 10.6k | CSS + DOM + async message pumps (Textual) |
| Go | Bubble Tea 45.4k, tview 14.1k, Lip Gloss 11.9k, Bubbles 9.0k, Huh 7.2k | Elm architecture (Model / Update / View) |
| JS/TS | Ink 40.1k, Inquirer 21.6k, OpenTUI 13.6k, blessed 11.9k | React reconcilers over Yoga flexbox |
| C/C++ | FTXUI 10.8k, notcurses 4.8k, ImTui 3.6k, Turbo Vision 3.2k | Functional DOM (FTXUI); windowed desktop (Turbo Vision) |
| .NET | Spectre.Console 11.7k, Terminal.Gui 11.3k | Rich-style output + prompts; full GUI-style toolkit |
| Java | Lanterna 2.6k, JLine 1.8k, TamboUI 0.7k, jatatui 0.3k | Windowed toolkit; ratatui-style three-layer API |
| Kotlin | Mosaic 2.8k, Mordant 1.2k, Kotter 0.7k | Compose runtime; sessions with live vars |
| Scala | layoutz 0.4k, fansi 0.2k, cue4s, terminus, siglyph (all < 50) | Mostly output and prompt libraries; no adopted full framework |

Four architectures dominate: immediate-mode buffer diffing, Elm/MVU, React/Compose components, and retained widget
trees. Glyphora is a hybrid: fine-grained signals driving a declarative `Element` tree that renders through
immediate-mode widgets into a diffed `Buffer`.

Three market observations shape the ranking below:

1. **The libraries with the most adoption ship an ecosystem, not a widget set.** Charm has Bubble Tea plus Lip Gloss,
   Bubbles, Huh, Glamour, VHS, Freeze, Gum and Wish. Textualize has Rich, Textual, textual-serve and snapshot testing.
   Tooling around the framework (recording, screenshots, prompts, SSH serving) is a large part of why people choose
   them.
2. **AI coding CLIs are now the biggest driver of TUI work.** Ink powers Claude Code and Gemini CLI, OpenTUI powers
   opencode, Bubble Tea powers Crush. These apps need streaming Markdown, diff views, prompt composers with history,
   inline mode and stdout capture.
3. **ratatui's design is being cloned across languages** (TamboUI and jatatui in Java, php-tui in PHP). Being "ratatui
   for X" is no longer a differentiator on the JVM. TamboUI is the direct rival: six minor releases in eight months,
   with CSS stylesheets, recording, SVG export, image protocols and streaming Markdown.

---

## 2. Where glyphora stands

### Ahead of every JVM/Scala competitor

- Fine-grained reactivity: dependency-tracked `Signal`/`Computed`, `SignalEquality`, positional hooks. Only Mosaic
  (Compose) is comparable.
- The strictest native-image story: zero reflection, grep-enforced in CI over modules and examples, every example
  built `--no-fallback` and run with no TTY in CI.
- Testing depth: `Pilot` with keys, key releases, raw bytes, paste, focus, mouse, resize, `ManualClock`, cursor
  assertions, golden frames, `Snapshot`.
- Unicode correctness: generated width table, grapheme clusters everywhere, CI ban on `substring`.
- Widget breadth: about 80 DSL factories, the largest set on the JVM.
- Motion: pure `Progress`/`Easing`/`Tween`/`Spring`/`Effect` in core, composable and deterministic in tests.
- Async discipline: owner-scoped `Async.run`, bounded admission, `TaskScope`, documented failure semantics.
- App shell: command palette, screen stack, toasts, help overlay, splash, semantic `Theme` with `HighContrast`.
- Documentation: 29 pages including three build tutorials, guarded by `DocumentedFactorySpec`.

Many features other ecosystems advertise as flagship are already here: `suspend`, `printAbove`/`insertBefore`, inline
viewport, OSC 52 copy, OSC 8 links, kitty keyboard with key release, synchronized output gated on a probe, octant and
braille canvas, shadows, merged borders, spring physics, column-spanning table cells, emergency terminal restore.

### Behind

1. **Adoption**: no stars yet; no published Maven Central coordinates were found at research time (unverified).
2. **Platform reach**: JDK 25 minimum (deliberate, see CLAUDE.md); no Scala.js or Scala Native, while layoutz,
   siglyph, terminus and cue4s have both; no Windows support (`website/docs/faq.md:78-80`).
3. **Plain CLI use**: no way to print an element once as ANSI, no one-shot prompts, no defined behaviour without a TTY.
4. **Artifact tooling**: no SVG/HTML export, no asciicast recording, no tape playback, no Flight Recorder events.
5. **Images**: half-block only. `Backend.writeRaw` + `DiffDirective.Skip` were designed for pixel protocols but no
   encoder exists.
6. **Markdown**: no tables, nested lists or images (`widgets/.../Markdown.scala:22-23`), no streaming support.
7. **External configuration**: no theme files, no keybinding files.
8. **Newer terminal protocols**: no colour-scheme query (OSC 11 / mode 2031), in-band resize (2048), pointer shapes
   (OSC 22), desktop notifications (OSC 9/777), tab progress (OSC 9;4), Unicode width mode (2027).
9. **Layout vocabulary**: no grid, no cross-axis alignment, no wrapping flow.
10. **Effect-system integration**: none (terminus and cue4s ship Cats Effect variants).

---

## 3. Bugs and correctness gaps found during the review

These are not ideas; they are defects in the current code, found by the internal review and re-verified.

| # | Finding | Evidence | Effort |
|---|---|---|---|
| B1 | **DSL text fields never move the terminal's hardware cursor.** They paint a styled cell instead. The accessibility doc says screen readers and IME candidate windows follow the real cursor, and `Frame.setCursorPosition` exists, but nothing under `dsl/` calls it. | `website/docs/unicode-and-accessibility.md:83-87`; `runtime/Frame.scala:52`; `dsl/TextEntryElements.scala:23,72,136,179,248` pass only `showCursor`; grep for `setCursorPosition` under `dsl/` is empty | S–M |
| B2 | **Dropdown reflows its siblings** instead of floating, although `portal` exists for exactly this. | `dsl/ChoiceElements.scala:76-79, 96-100`; `dsl/LayoutElements.scala:305` | S |
| B3 | **"Click outside the dialog" treats the dialog's own border as outside**, because non-control nodes record no bounds. | `dsl/Chrome.scala:122-133` | M (needs per-node bounds, idea D8) |
| B4 | **`ListView` does O(n) work per render**, defeating its row virtualisation (`forall(heightOf == 1)` and a full height sum). | `widgets/ListView.scala:140, 201` | S |
| B5 | **`TextArea` undo snapshots the whole document on every keystroke**, no coalescing. | `widgets/TextArea.scala:136-143` | S |
| B6 | **Two identity mechanisms can disagree**: `each` sets the focus key, hook state needs a separate `keyed`. | `dsl/ElementFactories.scala:103-110`; `dsl/ViewState.scala:273-274` | S (behaviour change; needs a migration note) |

---

## 4. Ideas where several research passes converged

Ranked by votes, then value ÷ effort. These are the strongest candidates.

| # | Idea | Votes | Inspired by | Status | Effort | Value |
|---|---|---|---|---|---|---|
| 1 | **Frame export to SVG / HTML / ANSI**, plus style-aware golden snapshots | 5/5 | Textual `save_screenshot`, Rich `export_svg`, Freeze, Spectre `Recorder`, TamboUI export | NEW | S–M | H |
| 2 | **Print mode**: render any `Element`/`Widget` once to stdout as ANSI, no runner; defined behaviour with no TTY | 4/5 | Rich `console.print`, Ink `renderToString`, iocraft `.print()`, Mordant, Mosaic `NonInteractivePolicy` | PARTIAL | S | H |
| 3 | **Capture stray `System.out`/`System.err`** while the app runs; show above the UI or in a console overlay; flush at exit | 4/5 | Ink `patchConsole`, Rich `redirect_stdout`, prompt_toolkit `patch_stdout`, Turbo Vision, Owl, OpenTUI console | NEW | S | H |
| 4 | **Multi-key sequences, modes, which-key popup, user remapping** | 4/5 | helix keymap trie, prompt_toolkit filters, Textual keymaps, Brick keybinding API | NEW | M | H |
| 5 | **Automatic light/dark theme** from OSC 11 + live updates via mode 2031 | 3/5 | Bubble Tea v2, Lip Gloss `lightDark`, yazi, Mosaic, libvaxis | NEW (decoder drops the reply today) | S | H |
| 6 | **Inline prompt layer**: one-shot `Prompt.text/select/confirm`, task spinners, `deriveForm` as a prompt chain, pre-supplied answers for CI | 3/5 | clack, Inquirer, Huh, Spectre prompts, Laravel Prompts, cue4s, layoutz | NEW / PARTIAL | M | H |
| 7 | **`Text.fromAnsi`**: parse SGR + OSC 8 into styled `Text` so subprocess output can be shown | 3/5 | ansi-to-tui, Lip Gloss, Rich `Text.from_ansi` | NEW | S–M | H |
| 8 | **Component-lifetime hooks**: `useEffect(deps)`, `useMemo`, `useTask`, `useArea` | 3/5 | iocraft, Mosaic `LaunchedEffect`, jatatui-react, React | NEW | M | H |
| 9 | **Latest-wins tasks**: `TaskScope.latest(key)` replaces the hand-rolled generation counter the docs teach | 3/5 | Textual `@work(exclusive=True)`, yazi discardable tasks | PARTIAL | S | H |
| 10 | **Streaming Markdown + GFM tables and nested lists** | 3/5 | Textual `Markdown.append`, TamboUI `PartialMarkdownSanitizer`, OpenTUI | NEW | S–M | H |
| 11 | **Pixel images: Kitty / iTerm2 / Sixel** with half-block fallback | 4/5 | ratatui-image, yazi, TamboUI, notcurses, layoutz | PARTIAL (hooks exist) | L | H |
| 12 | **Asciicast recording + tape playback** over `Pilot`/`HeadlessBackend` | 2/5 | VHS, TamboUI `recording.adoc` | NEW | M | H |
| 13 | **TextArea / editor upgrade**: selection, word motion, kill ring, history with Ctrl-R, gutter, search, soft wrap | 4/5 | tui-textarea, edtui, siglyph, prompt_toolkit, JLine | PARTIAL | M–L | H |
| 14 | **Grid layout with spans and gaps; flex wrap** | 3/5 | tview `Grid`, TamboUI grid-template-areas, FTXUI `flexbox`/`hflow`, Spectre `Grid` | NEW | M | H |
| 15 | **Mouse text selection and copy** (drag, double-click word, triple-click line) | 3/5 | Textual 6.x, OpenTUI, FTXUI Selection API | NEW | M–L | H |
| 16 | **Inspector overlay, debug borders, `doctor` command, event viewer** | 3/5 | Textual devtools, notcurses-info, tvdemo, tcell env overrides | NEW | S–M | H |
| 17 | **Diff viewer** (unified / side by side, highlighted) | 2/5 | OpenTUI `DiffRenderable` | NEW | M | H |
| 18 | **Pilot by element key**: `click("save")`, `boundsOf(key)` | 2/5 | TamboUI Pilot, Textual `pilot.click("#id")` | NEW | S | H |
| 19 | **Modern OSC set**: OSC 22 pointer shapes, OSC 9/777 desktop notifications, OSC 9;4 tab progress, mode 2027, mode 2048 | 3/5 | libvaxis, siglyph, Mosaic, Bubble Tea v2 | NEW | S each | M |
| 20 | **Serve an app over SSH** (one app per session) | 3/5 | Wish, Soft Serve, `@opentui/ssh`, TamboUI Aesh backend | NEW | L | H |

---

## 5. Full idea catalogue by theme

Each entry: what it is, why, a sketch where useful, and the CLAUDE.md rules it must respect. Rule shorthands: **NR**
no runtime reflection; **CW** all width math through `CharWidth`; **TIER** dependencies only point down; **OPT**
options-bundle rule for widgets; **ONE** one-import surface (`OneImportSurfaceSpec`); **DOC** every exported factory
documented (`DocumentedFactorySpec`).

### A. Beyond the full-screen app: CLI output, prompts and no-TTY behaviour

Most JVM CLI authors are not writing full-screen apps. They want pretty output and a few prompts. Today glyphora has
nothing to offer them, and this is the single biggest gap against Rich, Spectre, Mordant, layoutz and clack.

**A1. Print mode.** `Element.print(width = terminalWidth)` / `Console.print(view)`: lay out with `intrinsicHeight`,
render through `Snapshot.render`, encode with `FrameEncoder`, write to stdout. Respects `NO_COLOR` and colour depth.
Opens every widget (tables, trees, charts, Markdown, panels) to non-interactive tools. *S / H.* TIER: needs terminal
for encoding, so it lives in `dsl` or `runtime`.

```scala
Console.print(panel("Build")(table(rows, header)))          // once, styled, no runner
Console.print(markdown(readme), width = 80, colors = ColorDepth.None)
```

**A2. No-TTY policy.** When stdout is not a terminal, or `CI` is set, a `TuiApp` today fails to start. Offer a
`NonInteractivePolicy`: `Fail` (current), `PrintFinalFrame`, `PrintEveryFrame`, or `Plain` (accessible line output).
*S / H.*

**A3. Inline prompt layer.** One-shot prompts that return a value, rendered in `Viewport.Inline`, leaving a one-line
summary in scrollback:

```scala
val name: Option[String]   = Prompt.text("Project name", validate = nonEmpty)
val tier: Option[Tier]     = Prompt.select("Tier", Tier.values.toSeq)
val ok: Boolean            = Prompt.confirm("Deploy?")
val cfg: Option[Config]    = Prompt.form[Config]                // deriveForm, one field per step, Ctrl-U goes back
Prompt.task("Downloading") { log => ... }                       // spinner + scrolling log, summary kept
```

Plus pre-supplied answers for tests and CI (`Prompt.answers(...)`), Ctrl-C cancellation returning `None`, and an
automatic plain stdin fallback when the terminal is unsupported (Laravel Prompts `fallbackWhen`). *M / H.* NR: the
form step reuses `tui-macros` derivation.

**A4. Progress with columns.** Spectre/Rich-style multi-task progress: description, bar, percentage, ETA, rate,
transferred bytes, elapsed/remaining, spinner, each a composable column over a caller-owned `ProgressTask`. Plus
`loader(iterable)` that wraps any loop (layoutz, tqdm). *M / H.* Width math via CW; `DurationFormat` exists.

**A5. Stdout/stderr capture.** While an app runs, `System.setOut`/`setErr` into a buffer; render it above the live
UI through `printAbove`, or into a toggleable console overlay; flush whatever is left on exit or `suspend`. Removes
the "debug output destroys the UI" trap documented in `troubleshooting.md`. *S / H.*

**A6. Log bridge + console overlay.** A `System.LoggerFinder` (and optionally a separate SLF4J artifact) feeding a
thread-safe ring that a `logConsole()` element displays, filterable by level and logger, toggled by a key
(tui-logger, cursive's debug console). *M / H.* Check ServiceLoader under native-image (unverified).

**A7. A slim `tui-print` artifact.** Core + widgets + an ANSI encoder, without JLine, for print-only CLIs (Ruby TTY
toolkit's decomposition). *M / M.* Would require splitting the encoder out of `terminal`.

### B. Tooling: export, recording, inspection

**B1. Buffer export to SVG / HTML / ANSI.** A pure function `BufferExport.svg(buffer, palette)` keeping colours,
modifiers, wide glyph spans and OSC 8 links. Uses: documentation screenshots regenerated in CI so they never drift
from the code; bug reports; style-aware goldens with a visual diff report next to today's text goldens. *S–M / H.*
Lives in `test-support` or `core` (pure).

**B2. Recording and tape playback.** `RunnerConfig.recording(path)` or `-Dglyphora.record=x.cast` writes asciicast v2
from `onFrame`. A tape runner (VHS syntax subset: `Type`, `Enter`, `Sleep`, `Wait /regex/`, `Screenshot`) drives an
app through `Pilot` and emits `.cast`, SVG frames and text goldens. Docs GIFs and demo videos become build outputs.
*M / H.*

**B3. Inspector overlay.** F12 toggles an overlay showing, for the node under the pointer: type, bounds, constraint,
size claim, measured height, focus index and key; optional tree panel. Needs per-node bounds recording (D8). Node
names come from a `debugName` each node declares (NR). *M / H.*

**B4. Debug borders.** `GLYPHORA_DEBUG_LAYOUT=1` outlines every container dimly and marks zero-size children in red.
*S / H.*

**B5. `doctor` and event viewer.** A small built-in program (or example) printing detected capabilities, colour depth,
glyph tier, XTVERSION, background colour, then echoing decoded key and mouse events live. Document `GLYPHORA_*`
overrides for keyboard protocol, mouse mode, escape delay and colour depth (tcell's `TCELL_*`). Cuts support time on
"my keys don't work". *S / M–H.*

**B6. Flight Recorder events** for draw, event routing, focus changes and async admission (TamboUI). JFR needs no
reflection. Pairs with the existing `FrameTiming`. *S / M.*

**B7. Widget gallery example.** `examples/gallery`: every exported factory on its own page with live-editable props.
Doubles as a visual regression harness (each page a golden) and as the docs' screenshot source. CI picks it up
automatically. *M / M.*

**B8. Error boundary.** `errorBoundary(fallback)(subtree)` catches an exception from a subtree's render and paints
the message in its area instead of killing the frame. *S–M / M.*

### C. Terminal protocols

**C1. Colour-scheme detection.** Query OSC 11 at start, subscribe to mode 2031 updates, expose
`Signal[ColorScheme]` in `AppServices`; `Theme.auto` picks `Light` or `Dark`; `lightDark(a, b)` colour helper. The
decoder currently consumes and drops these replies on purpose (`terminal/InputDecoder.scala:341-345`); a probe would
make them expected. *S / H.*

**C2. In-band resize (mode 2048).** Resize reports through the input stream instead of SIGWINCH polling; more reliable
over SSH and multiplexers. *S / M.*

**C3. Unicode width mode (2027).** Ask the terminal to use grapheme-cluster widths, removing emoji width drift
between glyphora's table and the terminal. *S / M.*

**C4. Pointer shapes (OSC 22).** Hand over links and buttons, I-beam over inputs, resize arrows over split dividers.
*S / M.*

**C5. Desktop notifications (OSC 9 / OSC 777 / OSC 99)** from `Notifications` when the terminal is unfocused. *S / M.*

**C6. Tab/taskbar progress (OSC 9;4)** driven by a progress signal; Windows Terminal, Ghostty, WezTerm and others
show it. *S / L–M.*

**C7. Pixel images.** Kitty, iTerm2 and Sixel encoders behind `Backend.writeRaw` + `DiffDirective.Skip` (designed for
this, `terminal/Backend.scala:340`), selected by capability probe, encoded on `Async`, erased and redrawn when popups
cover them, with the existing half-block `Image` as fallback. Today `Image.scala` declares protocols out of scope.
*L / H.*

**C8. SSH serving (`tui-ssh` module).** One `TuiApp` per SSH session, using a streams-based backend factory, the
client's TERM and window size, and auth hooks (Wish). Candidate stack: `jline-remote-ssh` / Apache MINA SSHD;
native-image compatibility is unverified. Later, ECH/REP sequences in `FrameEncoder` to save bandwidth. *L / H.*

**C9. Browser backend.** Scala.js + xterm.js, or a WebSocket bridge, for live demos embedded in the docs site
(textual-serve, ratzilla). *L / M.* Depends on a Scala.js cross-build (M2).

### D. Layout

**D1. `grid`.** Track lists per axis reuse `Constraint` and the existing solver twice; children placed by
`(row, col, rowSpan, colSpan)` or auto-flow; optional named template areas (TamboUI). *M / H.* Pure core addition;
CW not involved beyond integers.

```scala
grid(cols = Seq(Fill(1), Length(30)), rows = Seq(Length(3), Fill(1)), gap = 1)(
  topBar.span(cols = 2), main.at(1, 0), sidebar.at(1, 1))
```

**D2. Cross-axis alignment.** Every layout segment spans the full cross axis today (`core/Layout.scala:155-158`), so
a button in a column is always full width. Add `CrossAlign = Stretch | Start | Center | End` and `.alignSelf`, using
`Measured` for the cross size and falling back to stretch on `None`. *M / H.*

**D3. `Constraint.Auto`.** Ask the child's `Measured` along the split axis. The solver currently ignores measurement
(`dsl/ElementFactories.scala:189-191`), so natural sizing lives in about 30 hand-written `SizeClaim` overrides that
could collapse into `Measured` on the widgets. *M / H.* `None` must mean "treat as `Fill`", never zero.

**D4. Compound constraints.** "Fill, but at least 20 and at most 60": `.minSize(20).maxSize(60).fill`. The solver's
single `demandOf` classification (`core/LayoutSolver.scala:59-70`) keeps the change local. *S–M / M.*

**D5. `flow` (wrap).** Lay children by natural width and wrap: tags, chips, button bars. Needs width measurement
(D3). *M / M.* CW for natural widths.

**D6. Container queries.** `responsive.local { size => ... }` sees the node's own area, not the terminal's
(`dsl/NavigationElements.scala:6-8` calls the current one "a media query"). Needs a layout pre-pass. *L / M.*

**D7. Dock layout.** Children docked top/bottom/left/right with the remainder to the last (TamboUI, Textual). Mostly
sugar over nested rows and columns. *S / L–M.*

**D8. Record bounds for every node.** An opt-in recorder during paint. Fixes bug B3, enables the inspector (B3),
mouse text selection (F7), Pilot-by-key (I1) and element-scoped effects (H1). *M / M*, but unlocks a lot.

**D9. Visibility requests.** A `scrollView` that automatically keeps the focused child, or a child marked
`.visible`, in view (Brick), replacing manual `scrollTo(row)` arithmetic (`widgets/ScrollView.scala:32`). *S–M / H.*

### E. Component model and composition

**E1. Fix the hardware caret (bug B1).** The focused text node reports its caret cell; the host translates it through
`FrameCoordinates` (so scroll views map correctly) and calls `Frame.setCursorPosition` after paint. `Pilot` already
has `assertCursorAt`/`assertCursorHidden` to test it. *S–M / H.*

**E2. `each` also scopes hooks (bug B6).** `each(items)(key)(render)` wraps `render` in `keyed(key)` too. One rule
for users: loop with `each`. *S / H.* Changes hook identity; needs a versioning note.

**E3. Lifetime hooks.** On the existing `ViewState` slots (`dsl/ViewState.scala:121-139`), store a disposer and run
it in `sweep()`:

```scala
def useEffect(deps: Any*)(start: => Cancelable)(using ReactiveScope): Unit   // runs after paint, cancels on unmount
def useMemo[A](deps: Any*)(compute: => A): A
def useTask[A](deps: Any*)(work: => A)(using ReactiveScope): Loadable[A]       // tied to TaskScope, cancelled on unmount
def useArea(): Option[Rect]                                                     // last painted rect of this node
```

A panel that polls while visible stops polling when it disappears, with no app-level bookkeeping. *M / H.* Keeps the
"no hooks in event handlers" rule (`dsl/ViewState.scala:212-220`); effects run on the render thread.

**E4. Reactive widget states.** Checkbox/toggle/select take signals; input/list/tree/scroll take plain mutable objects
that do not redraw when written from background work (`widgets/TextInput.scala:14-17`, a whole troubleshooting entry).
Add a version counter interface in core (`trait Versioned { def version: Long }`) that the DSL node reads through a
signal it owns. *M / H.* TIER: widgets cannot see runtime, hence the core interface.

**E5. Public custom container.** Outside code can only add leaves (`widget`, `interactiveWidget`); `Element` is closed
by `private[dsl] withProps` (`dsl/Element.scala:27-29, 45`). A sanctioned `container(children*)(place, chrome)` lets
users write their own layout while the framework keeps focus and hit testing working. *M / H.* Do after D3 so
placement can use measurement. ONE: export `ChildInfo`.

**E6. Slots.** `panel(title).header(e).footer(e)` where the footer renders inside the bottom border row; generalises
"status in the border" from strings to elements (badge, spinner). *M / M.* CW for slot widths.

**E7. Decorators.** Public `.border(...)`, `.padding(...)`, `.background(style)`, `.shadow`, `.clip` on any element,
built on `DecoratingElement` (`dsl/ElementDecorators.scala:12-29`); no more wrapping in `panel(...).borderless` just
for padding. *S / M.*

**E8. Consistent `style` meaning.** On `panel` it is the border style, on `text` the text style, on `widget(...)` it is
ignored, on lists it is the body style (`dsl/LayoutElements.scala:73`, `dsl/DisplayElements.scala:34`,
`dsl/ElementFactories.scala:186-187`). Split into `style` (content) and `frameStyle` (border). *M / M.* Breaking.

**E9. Subtree memoisation.** `memo(deps*)(subtree)` caches a built element across frames, optionally with a child
`ReactiveScope` so a signal change re-evaluates only the subtree that read it. Today any signal change re-evaluates
the whole view (`dsl/TuiApp.scala:585, 617-629`). *L / H.* Measure first with `RenderLoopBench`.

### F. Interaction

**F1. Interaction states.** Extend the framework-written `FocusState` (`dsl/ElementProps.scala:17-21`) with
`hovered`, `pressed` and `disabled`; add `.disabled` (removed from tab order, built-ins blocked, disabled style,
announced in accessible forms). Today only `MenuEntry.Item.enabled` knows about disabling. *S–M / H.*

**F2. Key sequences and modes.** `KeyBindings.handle` matches one event (`dsl/KeyBindings.scala:79-83`). Add a
`KeySequence` parser (`"g g"`, `"ctrl+k ctrl+s"`), a pending-prefix state with timeout on the tick, a which-key popup
listing possible continuations, named modes for vim-style apps, and collision detection (Brick). Status bar and help
overlay show the pending prefix. *M / H.*

**F3. User keymaps.** Rebind by binding id from a file or a `Map[BindingId, KeySpec]`; export the keymap as Markdown
or a help widget (Brick). *M / M.*

**F4. Focus groups.** `focusGroup(children*)` is one tab stop with arrow keys moving inside (toolbars, radio rows,
menu bars); `.trapFocus` for non-modal popovers. Today focus is one flat depth-first order. *M / H.*

**F5. Spatial navigation.** Alt+arrows moves to the nearest focusable in that direction using recorded hit areas.
*S / M.*

**F6. Pointer capture and local coordinates.** A built-in that consumes mouse-down captures the pointer until
mouse-up, so slider and divider drags don't stop at the hit area's edge (`dsl/InteractiveWidgetElement.scala:9-10`).
Mouse handlers receive `MouseContext(local: Position, area: Rect)` instead of absolute coordinates
(`dsl/Grammar.scala:129-130`). Also synthesise click counts for double-click. *M / H.*

**F7. Mouse text selection and copy** over `text`, `markdown`, `log` and tables, copying through the existing OSC 52
service. Mouse-capturing apps currently block the terminal's own selection, which users notice immediately. *M–L / H.*
Needs D8.

**F8. Drag and drop.** `draggable(payload)` / `dropTarget[A](accept)(onDrop)` with a portal-drawn ghost, typed through
a `DragKey[A]` (NR). *L / M.* Needs F6.

**F9. Floating windows and menu bar.** Movable/resizable `window(...)` with z-order and a keyboard arrange mode;
`menuBar` with mnemonics; context menus via `portal` (Turbo Vision, Terminal.Gui, FTXUI). *M–L / M.*

### G. Styling and theming

**G1. Component styles with state variants.** The theme has 10 flat roles (`dsl/Theme.scala:63-78`) and widget
defaults are hard-coded (`widgets/TextInput.scala:71`, `widgets/ListView.scala:97`). Add typed per-component style
sets with variants, resolved by the DSL node so widgets stay theme-agnostic (TIER):

```scala
final case class StateStyles(base: Style, focused: Style, hovered: Style, pressed: Style, disabled: Style)
theme.withComponent(ButtonRole.Primary, StateStyles(...))
button("Save").variant(ButtonRole.Primary)
```

*M / H.* NR: lookups through a given per component type, not class names.

**G2. Late theme binding.** Factories copy resolved styles at construction (`dsl/ElementFactories.scala:66`), so an
element built in a helper outside a view silently uses the default theme (`dsl/Theme.scala:359-368`). Store a role
reference and resolve it at paint. *M / M.*

**G3. Lazy style cascade.** `withStyle` rewrites the whole subtree eagerly every frame (`dsl/Grammar.scala:214-219`);
push an inherited-style context during paint instead. *M / M.*

**G4. Theme files.** `Theme.parse` / `Style.parse` from a small text format (cursive TOML, gitui RON, Lanterna
properties, Brick INI), so end users can restyle an app without recompiling. Parsing by hand, no reflection. *S–M / H.*

**G5. Theme presets.** Dracula, Catppuccin, Nord, Gruvbox, Solarized, plus an `Ansi16` theme that uses only the
terminal's own palette so it follows the user's terminal theme (Textual `ansi-dark`). *S / M.*

**G6. Markup interpolator.** `markup"[bold red]error[/] in [link=$url]$file[/]"` checked at compile time, built the
same way as the existing `key"..."` literal. *S–M / H.*

**G7. Hierarchical style names.** Brick's attribute maps (`list.selected` falls back to `list`, merging unset fields
from the parent) as a typed, lighter alternative to CSS. *M / M.*

### H. Motion and effects

**H1. Element-scoped effects.** `EffectStack` always covers the whole frame (`dsl/EffectStack.scala:22`).
`element.withEffect(fx)` resolves the node's painted rect: flash this panel, dissolve this toast, sweep in this
dialog. *M / H.* Needs D8.

**H2. Cell filters.** `Effect.filtered(pred)` with ready-made filters (text cells only, by foreground colour, inner
or outer margin), from tachyonfx `CellFilter`. The existing `mapCells`/`eraseWhere` already iterate cells. *S / H.*

**H3. Keyed effects.** `runEffect(key, fx)` replaces the running effect with the same key, so pressing a key 20 times
doesn't stack 20 fades. *S / M.*

**H4. Spatial patterns.** Per-cell progress from global progress and position: radial, diagonal, sweep, checker,
wave. Pure functions next to `Progress` and `Easing` in core. *M / M.*

**H5. More effects.** `hslShift`, `paint`, `glitch`, `evolve(symbolSet)`, `pingPong`, `reversed`, slide from any
direction (only right-to-left exists). *S each / M.*

**H6. `signal.animateTo(target, duration, easing)`** sugar over `Tween` (Textual `animate`). *S / M.*

### I. Testing

**I1. Pilot by element key.** `pilot.click("save")`, `pilot.focus("search")`, `pilot.boundsOf(key)`, so tests survive
layout changes. Elements already carry keys. *S / H.* Needs D8 for non-focusable nodes.

**I2. Styled goldens with visual diff** on top of B1, next to today's text goldens (which drop styles deliberately,
see `GoldenFrames` Scaladoc). *S–M / H.*

**I3. Property-based layout tests.** Generators over constraint lists and areas checking that segments stay inside
the area, sum to at most the axis, are monotone in weights. ScalaCheck through `extraTestDeps`. *S / M.*

**I4. Accessibility lint.** `pilot.a11yReport`: focusables with no visible focus change, colour-only status (contrast
via `Color.contrastRatio`), focused inputs that don't place the caret (B1), modals without Esc. *M / M.*

**I5. Element-tree snapshot.** Snapshot the decorated tree (types, keys, focus indices) to answer "why didn't it get
focus". *S / M.*

### J. Text, content and editing

**J1. `Text.fromAnsi`.** The inverse of `Sgr`: parse SGR and OSC 8 into `Text`, widths through CW. Shows coloured
`git`, compiler or test output inside `log`, `text` and `scrollView`. *S–M / H.*

**J2. Editor core.** A shared edit buffer for `TextInput` and `TextArea` with selection (anchor + head), word and
paragraph motion (`java.text.BreakIterator`), select-all, cut/copy/paste through the app clipboard, kill-line and kill
ring with yank, soft wrap, a line-number gutter, search, and coalesced undo storing diffs (fixes B5). Today
`TextInputState` has insert, delete and left/right/home/end only (`widgets/TextInput.scala:30-54`). `GraphemeEdit`
stays the splice owner. *L / H.* CW for all column math.

**J3. Prompt-composer features** (the LLM-CLI shape): input history with Ctrl-R search, ghost auto-suggest from
history, `/` and `@` trigger completion with an async debounced provider, large-paste compaction into a placeholder
(siglyph, prompt_toolkit, Claude Code). *M / H.*

**J4. `editExternally`.** Open `$EDITOR` on a temp file through `suspend`, read the result back; `openInEditor(path,
line)`. *S / M.*

**J5. Streaming Markdown.** `Markdown.append(chunk)` with a sanitizer for unterminated syntax (`**`, `[label](`, a bare
`##`) so token-by-token LLM output renders stably (TamboUI `PartialMarkdownSanitizer`), plus a scroll view that stays
pinned to the bottom while new content arrives. *S–M / H.*

**J6. Markdown completeness.** GFM tables, nested lists, task lists. `Markdown.scala:22-23` excludes these on
purpose ("a document viewer, not a rendering-complete engine"); README and LLM output use tables heavily, so the scope
decision is worth revisiting. *M / H.*

**J7. Diff viewer.** `diffView(patch, Unified | Split)` with syntax highlighting, line numbers and hunk navigation.
The flagship widget of AI coding CLIs. *M / H.*

**J8. Pluggable highlighters.** Turn the closed `Language` enum (Scala, JSON, Bash, Generic;
`widgets/SyntaxHighlighter.scala:22-23`) into a `Lexer` trait with more instances (YAML, SQL, Markdown, diff,
TypeScript, Python) and let `TextArea` use one. *M / M.* Tree-sitter would be an optional module with native code.

**J9. Search in scrolling views.** Highlight matches, next/previous, scroll to match in `scrollView`, `log`,
`markdown` and `textArea` (Bubbles viewport v2, tview). *M / M–H.*

**J10. Bidirectional text.** Visual reordering per line through `java.text.Bidi` at render time, cursor movement in
logical order. *L / L–M.*

### K. State and async

**K1. Latest-wins tasks.** `TaskScope.latest(key)(work)`: starting a new task with the same key cancels and discards
the old one. Replaces the generation-counter pattern taught in `live-data.md` ("Drop a stale response"). *S / H.*

**K2. Store.** `Store[S](initial)` with `select(f): Computed[A]`, `dispatch(action)` and optional
`history(limit)` for undo/redo, as sugar over `Signal`/`Computed`. *S / M.*

**K3. Persisted signals.** `Persisted.signal(path, initial)(using Codec[A])`, writing off-thread on change; codec
derived through `Mirror` in `tui-macros` (NR). Enables "restart preserving state". *M / M.*

**K4. Identity-keyed tree state.** `TreeState` expansion and selection keyed by item identity rather than position
(tui-tree-widget), so refreshing the data doesn't lose the user's place. *S–M / H.*

**K5. Effect-system adapters.** Separate `tui-cats-effect`, `tui-zio`, `tui-ox` artifacts bridging `Async` and
`TaskScope` to those runtimes (terminus, cue4s). *M each / M.*

### L. Performance

**L1. `ListView` O(viewport)** by caching the "all rows one line high" flag and total height by item identity, the
pattern `TreeState` and `DataTableState` already use (fixes B4). *S / M.*

**L2. Virtual element list.** `lazyList(count, itemHeight)(i => Element)` builds only visible items; today
`scrollView(column(rows.map(card)*))` builds, measures, decorates and renders every card (`widgets/ScrollView.scala:87-92`).
*L / H.*

**L3. Fuse per-frame passes.** View evaluation, responsive resolution, focus key collection, autofocus and decoration
are four separate tree walks (`dsl/ElementHost.scala:80-81`). One traversal, retaining the focus path for key routing.
*M / M.* Measure first.

**L4. Lazy table data.** `TableContent`-style row providers for huge datasets (tview). *M / M.*

### M. Platform and reach

**M1. Published artifacts and a one-liner.** Maven Central publishing (not found at research time, unverified), a
`scala-cli` single-file example and a JBang-style quickstart. Adoption is the largest gap in section 2. *S / H.*

**M2. Scala.js / Scala Native cross-build of `core` + `widgets`.** Every Scala competitor has both. Native also gives
a second route to small binaries. *L / M.* Constrained by `-release:25` and JDK-only APIs in lower tiers.

**M3. Windows.** Most competitors run there (`faq.md:78-80` says "Unknown"). JLine supports Windows; the work is CI
and input decoding. *M / M.*

**M4. Localisable built-in strings.** A `Messages` table for built-in labels (dialog buttons, palette prompt, help
headers) (Lanterna). *S / L–M.*

### N. New widgets

| Widget | Notes | Effort | Value |
|---|---|---|---|
| Diff viewer | J7 | M | H |
| Grid | D1 | M | H |
| Virtual element list | L2 | L | H |
| Menu bar + context menu | `menu` + `portal` + focus groups | M | H |
| Multi-task progress with columns | A4 | M | H |
| Log console overlay | A6 | M | H |
| Tag / chip input | `flow` (D5) + `TextInputState` | M | M |
| Date / time picker | interactive wrapper around `Calendar` (display-only today) | M | M |
| Tree-table | `Tree` rows + `DataTable` columns | M | M |
| Wizard / stepper | ordered screens + progress | S | M |
| Breadcrumb | from `screenLabels`, clickable | S | M |
| Toolbar / button group | focus group | S | M |
| Range slider | two thumbs | S | M |
| Colour picker | Terminal.Gui | S–M | L |
| Accordion | `collapsible` group with single-open policy | S | L |
| Hex viewer | fixed-width grid | S | L |
| QR code | from `Canvas` half-blocks | M | L–M |
| Embedded terminal | needs a VT parser and PTY | L | L |
| Placeholder | sized, labelled box for prototyping layouts (Textual) | S | L |
| Pretty / inspect | tree view of any Scala value for debugging (Rich, Term.jl) | S–M | L |

---

## 6. Deliberately not recommended (for now)

| Idea | Why not |
|---|---|
| A full CSS selector engine (Textual TCSS, TamboUI) | Selectors need class names on nodes and per-frame matching cost; without reflection it degenerates to explicit `.classes("x")`. Component styles with state variants (G1) plus theme files (G4) cover most of the value with typed, compile-checked code. |
| Live code reload (nocterm, Textual dev mode) | JVM hot-swap doesn't apply to native images. "Restart preserving state" via persisted signals (K3) plus `mill -w` is the realistic version. |
| A general z-index | Paint order in `layers`/`portal` already decides stacking and hit testing. |
| An annotation processor for actions (TamboUI `@OnAction`) | `tui-macros` derivation already does this without codegen or ServiceLoader. |
| Being an Elm/MVU framework | Signals are the differentiator; an Elm layer would duplicate them. Latest-wins tasks (K1) and `Store` (K2) cover the useful parts. |

---

## 7. Suggested roadmap

Grouped by dependency and value. Each step stands on its own.

**Now: correctness and cheap wins (days)**
1. Bug B1: drive the hardware caret (E1).
2. Bug B2: dropdown through `portal`.
3. Bugs B4, B5: `ListView` O(viewport) (L1), undo coalescing.
4. Bug B6: `each` scopes hooks (E2).
5. Latest-wins tasks (K1).
6. Colour-scheme detection and `Theme.auto` (C1).
7. Stdout/stderr capture (A5).
8. `Text.fromAnsi` (J1).

**Next: the reach release (weeks)**
1. Buffer export to SVG/HTML/ANSI (B1) and styled goldens (I2); regenerate README/docs screenshots in CI.
2. Print mode and no-TTY policy (A1, A2).
3. Per-node bounds recording (D8), then Pilot by key (I1), inspector and debug borders (B3, B4).
4. Interaction states and `.disabled` (F1).
5. Key sequences, modes, which-key (F2).
6. Streaming Markdown (J5) and GFM tables (J6).
7. Asciicast recording and tape runner (B2).
8. Maven Central + scala-cli quickstart (M1).

**Later: the layout and component release**
1. `grid` (D1), cross-axis alignment (D2), `Constraint.Auto` (D3), `flow` (D5).
2. Lifetime hooks (E3) and reactive widget states (E4).
3. Component styles with variants (G1), theme files (G4), presets (G5), markup interpolator (G6).
4. Focus groups (F4), pointer capture and local coordinates (F6).
5. Public custom container (E5).
6. Inline prompt layer (A3) and progress columns (A4).

**Bets: larger, each its own module or milestone**
- Editor core (J2) with prompt-composer features (J3) and diff viewer (J7): the AI-CLI package.
- Pixel images (C7).
- SSH serving (C8).
- Mouse text selection (F7) and element-scoped effects (H1).
- Virtual element list (L2) and subtree memoisation (E9).
- Scala.js/Native cross-build (M2) and browser backend (C9).

---

## 8. Open questions and unverified points

- Is glyphora published to Maven Central? A search for `g:com.worxbend` returned nothing at research time.
- Does Apache MINA SSHD / `jline-remote-ssh` build under native-image with `--no-fallback`? (Blocks C8 design.)
- How does `ServiceLoader` behave for a `System.LoggerFinder` bridge under native-image? (A6.)
- Can piped stdin and `/dev/tty` input coexist under the current JLine `.system(true)` terminal? (Relevant to A1/A3
  in pipelines.)
- Does adding an OSC 11 probe interact badly with the deliberate drop of OSC replies in `InputDecoder` (the trade-off
  that Alt+`]` cannot be bound)? (C1.)
- Should `Markdown` stay a "viewer, not an engine"? J5/J6 argue the scope should grow; that is a product decision.
- Effort for mouse selection (F7) and translucent colours may be underestimated: both change how the frame is
  composed.
