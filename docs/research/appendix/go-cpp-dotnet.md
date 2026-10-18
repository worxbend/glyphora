# Go / C++ / .NET TUI ecosystems — exclusive features and what glyphora could borrow

Researched 2026-10-18. Stars from `gh api repos/<r> --jq .stargazers_count` on that date. READMEs were pulled with
`gh api repos/<r>/readme`; release notes with `gh api repos/<r>/releases`; FTXUI, tview, bubbletea, bubbles,
charmbracelet/x, spectre.console and Terminal.Gui were shallow-cloned into `tui-research/clones/` and their headers or docs
read directly. Every glyphora claim was checked with grep against `/home/worxbend/Projects/Github/glyphora` (read-only).

Tags: **[HAS]** glyphora already does this (file cited) · **[PARTIAL]** some of it exists · **[NEW]** missing.
Effort **S/M/L** · Value **H/M/L**. "(unverified)" = could not confirm from the source I read.

Paths are shortened: `core/…`, `terminal/…`, `widgets/…`, `runtime/…` and `dsl/…` mean
`<module>/src/main/scala/io/worxbend/tui/<module>/…`.

---

## What glyphora already covers (so no idea below repeats it)

Already shipped, so the ideas below leave these alone: `suspend`, `printAbove`, styled `insertBefore`, OSC 52
`copyToClipboard` (`terminal/Backend.scala`, `dsl/TuiApp.scala`); `Viewport.Inline(n)` (`runtime/Viewport.scala`);
kitty keyboard plus key-release events (`terminal/KittyKeys.scala`, `RunnerConfig.keyEventTypes`); DECRPM probing for
sync-output 2026, bracketed paste and focus (`terminal/TerminalCapabilities.scala`); cursor shape and blink
(`terminal/CursorShape.scala`); OSC 8 links and curly/dotted underline with its own colour (`core/Style.scala`);
colour-depth detection from NO_COLOR/COLORTERM plus `override colorDepth` (`terminal/ColorDepth.scala`); title stack;
`Spring`/`Tween`/`Easing`/`Effect` (fade/dissolve/sweep/typewriter/sequence/parallel) in `core/`; `ColorRamp`
gradients; `Shadow`; braille/quadrant/sextant/octant canvas (`widgets/SubCell.scala`, `widgets/Canvas.scala`);
half-block `Image`; fuzzy `CommandPalette`; `helpOverlay` + `statusBar(bindings)` (`dsl/Chrome.scala`); screen stack;
toasts; portals and layers; a drag-resizable `splitPane` (`dsl/NavigationElements.scala`); `onHover`/`onDrag`;
`TextArea` with undo/redo; `Markdown` with syntax-highlighted fences; `Log` that follows the tail; `DataTable` with
sort, filter and paging; `Calendar` with `dayStyles`; derived forms plus `Form.accessible`; `Snapshot.render`;
`GoldenFrames`; `Pilot` with `sendBytes`; `ManualClock`; `FrameTiming`; `LatestFrameObserver`; emergency restore and
SIGTSTP/SIGCONT handling; native-image with no reflection.

---

## Go — Charm ecosystem

### Bubble Tea v2 — 45,445★ — Elm-architecture TUI runtime
https://github.com/charmbracelet/bubbletea · v2 notes: https://github.com/charmbracelet/bubbletea/releases/tag/v2.0.0 (latest is v2.1.0, 2026-10-08)
- **Model:** `Init() Cmd`, `Update(Msg) (Model, Cmd)`, `View() tea.View`. `Cmd` is `func() Msg`, combined with
  `tea.Batch` (concurrent) and `tea.Sequence` (ordered) (`commands.go`).
- **Standout features:**
  - **Declarative `tea.View` struct.** Alt-screen, mouse mode, focus reporting, bracketed paste, window title,
    keyboard enhancements, cursor (position/shape/blink/**colour**), terminal FG/BG colour and a native `ProgressBar`
    are all fields the view returns each frame, not imperative commands.
  - **"Cursed renderer"**, based on the ncurses algorithm, plus `ultraviolet`, which uses ECH/REP/ICH/DCH and scroll
    optimisation. Its stated goal is cutting bandwidth over SSH.
  - Terminal queries that return as messages: `RequestBackgroundColor`/`BackgroundColorMsg.IsDark()`,
    `RequestForegroundColor`, `RequestCursorColor`, `RequestTerminalVersion` (XTVERSION),
    `RequestCapability("RGB")` (XTGETTCAP), `ModeReportMsg` (DECRPM), `CursorPositionMsg`, `tea.Raw(seq)`.
  - Sets mode **2027** (grapheme-cluster width) automatically on terminals that support it. Uses `modifyOtherKeys`
    as the fallback where kitty keyboard is missing.
  - `Key.BaseCode` (the key's position on a US layout), `Key.IsRepeat`, `Keystroke()`. `PasteStartMsg`/`PasteEndMsg`.
  - `tea.ReadClipboard()` (an OSC 52 read) and `SetPrimaryClipboard`.
  - `tea.EnvMsg` carries the *client's* environment, which matters over SSH. `WithEnvironment`, `WithColorProfile`,
    `WithWindowSize(80,24)` for tests, and `OpenTTY()` so the app works with stdin/stdout piped.
  - `tea.Exec(cmd, callback)` / `ExecProcess(*exec.Cmd, fn)` run a child process and send its result back as a
    `Msg`. `tea.Suspend` / `Interrupt` (`exec.go`, `tea.go`).
  - `tea.Println`/`Printf` print above an inline program (`renderer.go`).
- **Borrow for glyphora:**
  - [HAS] Suspend, print above, inline mode, clipboard write, cursor shape, kitty keys, DECRPM, sync output
    (`terminal/Backend.scala:299,306,326,277`, `runtime/Viewport.scala`).
  - [NEW] **Background colour query (OSC 11) and a light/dark decision.** `InputDecoder` already recognises and
    *discards* OSC 11 replies (`terminal/InputDecoder.scala:344`). Turn the reply into
    `TerminalCapabilities.background: Option[Rgb]` plus `isDark`, and let `TuiApp.theme` default to
    `Theme.Light`/`Theme.Dark` from it. S/M · **H**
  - [NEW] Expose terminal identity: XTVERSION (the decoder already swallows it, `InputDecoder.scala:672`) and
    XTGETTCAP. Feeds bug reports and per-terminal workarounds. S · M
  - [NEW] Set the terminal's FG/BG/cursor colour (OSC 10/11/12) and restore it on exit. No grep hit for this. S · L-M
  - [NEW] Native taskbar/tab progress through OSC 9;4 (ConEmu, Windows Terminal, Ghostty), wired to `progressBar` or
    to a `RunnerConfig`/`TuiApp` hook. S · M
  - [NEW] Turn on mode 2027 when DECRPM reports it. Only `WidthTable.scala` contains "2027"; the mode is not toggled.
    Pair it with `CharWidth`'s `WidthMode`. S · M
  - [PARTIAL] `suspend` returns a value at the backend level (`Backend.suspend[A]`), but `TuiApp.suspend(body: => Unit)`
    drops it. Add `suspendFor[A](body): Option[A]` plus an `openInEditor(path, line)` helper, modelled on
    `charmbracelet/x/editor`, which knows the `+line` flags for vi/emacs/code. S · M
  - [PARTIAL] Per-frame declarative terminal state. glyphora fixes mouse mode and viewport once in `RunnerConfig`;
    title and cursor are imperative. A `Signal`-driven `TerminalState` (title, mouse mode, cursor colour, progress)
    that the runner diffs once per frame would match the reactive model. M · M
  - [NEW] Renderer bandwidth: ECH (erase N cells) and REP (repeat last glyph) for runs of identical cells. Nothing in
    `FrameEncoder.scala` emits either. Worth it once SSH serving exists. M · M (H over SSH)
  - [PARTIAL] Reading the clipboard: OSC 52 read is missing, write exists. S · L
  - [NEW] An `/dev/tty` fallback when stdout is piped (`OpenTTY`, also `TVISION_USE_STDIO` below), so
    `app | tee log` works. Whether JLine's system terminal already handles this is (unverified). S · M

### Lip Gloss v2 — 11,937★ — style, layout and compositing for strings
https://github.com/charmbracelet/lipgloss · https://github.com/charmbracelet/lipgloss/releases/tag/v2.0.0
- **Model:** pure string styling. In v2 Bubble Tea owns all I/O and Lip Gloss "is pure".
- **Standout features:** `LightDark(isDark)(light, dark)` and `HasDarkBackground(in, out)`. A cell-based
  **compositor**: `NewLayer(s).X().Y().Z()` then `compositor.Compose(...)`, with mouse hit-testing on layers.
  `Blend1D/2D` colour blending and **border colour blend**. `Wrap` that keeps styles and links. Sub-packages
  `table` (Markdown/ASCII border presets), `list` (enumerators: roman, alphabet, bullet, custom) and `tree`
  (indenter styling). A `colorprofile` writer that downsamples *any* ANSI written through it.
- **Borrow:**
  - [HAS] Layers and offsets (`layers`, `positioned`, `portal`), blending (`core/Color.scala`, `widgets/ColorRamp.scala`).
  - [NEW] **Parse ANSI into `Text`** (`Text.fromAnsi`): SGR to `Style`, OSC 8 to links. Lets an app show
    `git diff --color` or a subprocess's coloured log inside `Log`/`ScrollView`. Today the only path strips control
    sequences (`printAbove`). Must go through `CharWidth`. M · **H**
  - [NEW] Print a widget/`Element` once to stdout as ANSI, with no runner — the Lip Gloss/Spectre "styled CLI output"
    use. `Snapshot.render` already produces a `Buffer` (`dsl/Snapshot.scala:32`); add `Snapshot.ansi(…)`/`printOnce`
    that reuses `FrameEncoder` at the detected colour depth. S · **H**
  - [NEW] A border gradient (`ColorRamp` along the perimeter of a `Block`). S · L-M
  - [PARTIAL] List enumerators (roman, alpha, custom) on `list`/`tree`, and a Markdown border preset for `table`. S · L
  - [HAS] Light/dark adaptive colours are covered by the OSC 11 idea above.

### Bubbles v2 — 8,998★ — component library
https://github.com/charmbracelet/bubbles · https://github.com/charmbracelet/bubbles/releases/tag/v2.0.0
- **Standout features:** `viewport` gained `LeftGutterFunc` (line numbers, soft-wrap markers), regex
  `SetHighlights` + `HighlightNext/Previous`, `SoftWrap`, horizontal scrolling, `StyleLineFunc`. `list` has fuzzy
  filter, pagination, status line and spinner. `help` builds short and full help from `key.Binding`. `progress`
  takes `WithColors(...)`, `WithScaled` and `WithColorFunc(total,current)`, and animates with a spring. Every
  component ships `DefaultStyles(isDark)`. `textinput` shows suggestions.
- **Borrow:**
  - [HAS] help/key bindings (`dsl/Chrome.scala:83`, `dsl/KeyBindings.scala`), paginator, stopwatch/timer, spinner,
    file picker, fuzzy matching (`dsl/Fuzzy.scala`), autocomplete.
  - [NEW] **Search inside a viewport**: highlight every match, step next/previous, scroll to the active one, on
    `ScrollView`/`Log`/`Paragraph`/`Markdown`. No grep hit for highlight-next or find. M · **H** (log viewers,
    pagers)
  - [NEW] A gutter on `ScrollView`/`TextArea` (line numbers, diff markers, soft-wrap glyph). S/M · M
  - [PARTIAL] `progressBar` colouring: `ColorRamp` exists; add a scaled-to-fill mode and a `(fraction) => Color`
    function. S · L
  - [PARTIAL] Built-in widget styles that pick a light or dark default from the detected background (see OSC 11). S · M

### Huh — 7,206★ — forms and prompts
https://github.com/charmbracelet/huh
- **Standout features:** `Group`s act as form pages. `TitleFunc`/`OptionsFunc(fn, &binding)` recompute *and cache*
  dependent fields (country → states). `form.WithAccessible(true)` **drops the TUI for plain line prompts** for screen
  readers. Theme presets: Charm, Dracula, Catppuccin, Base16. Runs on its own with `form.Run()` or embedded in Bubble
  Tea. Has a `spinner.New().Action(fn)` helper.
- **Borrow:**
  - [HAS] Derived forms, validation, accessible rendering (`dsl/Form.scala:164`, `macros/`).
  - [PARTIAL] glyphora's accessible form is still a TUI that announces "Field 1 of 3". huh's mode is a sequential
    cooked-mode prompt that screen readers handle better. Add `Form.prompt(spec)`: line-by-line, no raw mode. M · M-H
  - [NEW] **A one-shot form or prompt that returns a value** without the caller writing a `TuiApp`, e.g.
    `Prompt.select("Env", envs): Either[Cancelled, String]`, `Prompt.confirm`, `Form.run(spec): Option[A]`, drawn inline
    with `Viewport.Inline(n)` and leaving a summary line behind. M · **H** (CLI tools, the gum/Spectre use case)
  - [NEW] Multi-page form groups and fields that depend on other fields (options recomputed from another field's
    `Signal`). M · M
  - [NEW] Named theme presets (Dracula, Catppuccin, Nord, …). glyphora has only Dark, Light and HighContrast
    (`dsl/Theme.scala:267–324`) plus Tailwind/Material palettes. S · M

### Wish — 5,560★ — serve TUIs over SSH
https://github.com/charmbracelet/wish
- **Standout features:** SSH middleware chain: the `bubbletea` middleware gives each session its own `tea.Program`
  on the session PTY with resize forwarded, plus `logging`, `activeterm`, `accesscontrol` and `git`. Public-demo
  culture (`ssh git.charm.sh`).
- **Borrow:**
  - [NEW] **An optional `tui-ssh` module.** JLine ships `jline-remote-ssh` (Apache MINA sshd); its `ShellFactoryImpl`
    builds `TerminalBuilder.builder().system(false).streams(in,out).size(...)` and raises WINCH on resize
    (https://github.com/jline/jline3/blob/master/remote-ssh/src/main/java/org/jline/builtins/ssh/ShellFactoryImpl.java,
    https://jline.org/docs/remote-terminals/). glyphora already supports several runners per JVM (CLAUDE.md
    "Each Runner owns its own work queue") and an overridable `createBackend()` (`dsl/TuiApp.scala:484`). What is
    missing:
    1. a streams-based `JLine3Backend` factory — `create` is system-terminal only (`JLine3Backend.scala:630`);
    2. an app factory per session;
    3. client env/TERM passed through to `ColorDepth`;
    4. auth hooks.

    Must stay a separate artifact: CLAUDE.md deliberately avoids pulling the JLine SSH server into `terminal`.
    Native-image compatibility of MINA is (unverified). L · **H**

### VHS — 21,143★ — scripted terminal recordings
https://github.com/charmbracelet/vhs
- **Standout features:** a `.tape` DSL (`Output`, `Set Width/Theme/FontSize/TypingSpeed`, `Type`, `Ctrl+…`,
  `Sleep`, `Wait /regex/`, `Hide`/`Show`, `Screenshot`, `Source`, `Env`, `Require`) producing GIF/MP4/WebM/PNG. Its
  `.txt`/`.ascii` output **doubles as golden files in CI**, and `vhs-action` keeps README GIFs current.
- **Borrow:**
  - [PARTIAL] `Pilot` + `GoldenFrames` already give scripted, deterministic driving and text goldens
    (`test-support/…/Pilot.scala`, `GoldenFrames.scala`).
  - [NEW] **A tape runner on top of `Pilot`**: read a `.tape`-like script (or a Scala DSL), drive the app headlessly
    with `ManualClock`, and write **asciicast v2** plus SVG/PNG frames. The documentation GIFs under `docs/assets/`
    could then be regenerated in CI with no real terminal or ttyd. M · **H**

### Freeze — 4,889★ — code and terminal screenshots
https://github.com/charmbracelet/freeze
- **Standout features:** turns ANSI or `tmux capture-pane -pet` output into SVG/PNG/WebP, with window chrome,
  shadow, font, line numbers and padding.
- **Borrow:**
  - [NEW] **Export a `Buffer`/`CompletedFrame` as SVG and HTML**, keeping style, wide glyphs and links.
    `CompletedFrame.text` exists but is plain (`runtime/CompletedFrame.scala:28`). Gives "export screen" commands,
    bug-report attachments, docs and golden diffs a reviewer can see. S/M · **H**

### Gum — 24,515★ — TUI components for shell scripts
https://github.com/charmbracelet/gum
- **Standout features:** subcommands `choose`, `filter`, `input`, `write`, `confirm`, `file`, `pager`, `spin`,
  `table`, `style`, `join`, `format`, `log`, configured through flags or `GUM_*` environment variables.
- **Borrow:**
  - [NEW] A `glyphora` native binary (an example module) exposing `choose`/`filter`/`input`/`confirm`/`spin`/`pager`
    for shell scripts. A showcase for native-image startup time, and a forcing function for the one-shot prompt API.
    M · M

### Glamour — 3,736★ — stylesheet-based Markdown rendering
https://github.com/charmbracelet/glamour (powers Glow, 27,726★)
- **Standout features:** JSON stylesheets (dark, light, dracula, …, plus `GLAMOUR_STYLE` set to a file),
  auto light/dark, word wrap, tables, task lists.
- **Borrow:**
  - [PARTIAL] `Markdown` + `MarkdownTheme` exist but **exclude tables and images** on purpose
    (`widgets/Markdown.scala:22`). Add GFM tables (reusing `Table`) and task lists. M · M
  - [NEW] Load a `MarkdownTheme`/`Theme` from a file or environment variable (see Terminal.Gui config below).

### Harmonica — 1,628★ — damped-spring physics
https://github.com/charmbracelet/harmonica — `NewSpring(FPS(60), angularFreq, damping)`, `Update(pos, vel, target)`.
- [HAS] `core/Spring.scala`, `Tween`, `Easing`. Nothing to borrow.

### Log — 3,407★ — styled structured logger
https://github.com/charmbracelet/log — levels, key/value fields, styles, sub-loggers, an `slog.Handler`, and a
standard-library `log.Logger` adapter.
- [NEW] A small `tui-log` adapter: an SLF4J/JUL handler that sends records to a `Log` widget state or to
  `insertBefore` with level colours, so library logging cannot corrupt the frame (troubleshooting.md documents the
  `println` trap). Must not add dependencies to core modules, so it would be an optional artifact. M · M

### charmbracelet/x (experimental) — 322★ and ultraviolet — 395★
https://github.com/charmbracelet/x · https://github.com/charmbracelet/ultraviolet
- **Notable packages:** `exp/teatest` (`NewTestModel`, `WaitFor(cond)`, `WithInitialTermSize`, final-output golden);
  `exp/golden`; `vt` (a terminal emulator with a cell buffer); `vttest` ("drawing a terminal emulator screen to an
  image"); `xpty`/`conpty`; `editor`; `nerdfont` (detect Nerd Font, fall back); `mosaic` (image → cells).
  ultraviolet: `Screen` interface, `Window` with parent/child **shared-buffer views**, Cassowary layout, a diffing
  renderer that uses ECH/REP/ICH/DCH, repeatable `Stop()`/`Start()`.
- **Borrow:**
  - [HAS] teatest/golden equivalents (`Pilot`, `GoldenFrames`, `ManualClock`).
  - [NEW] An **embedded terminal-emulator widget** (as in `x/vt`, or tvision's `tvterm`) that runs a child under a PTY
    inside a pane. Needs a VT parser plus a PTY (JLine has `pty` support; native-image compatibility unverified).
    L · M (high for IDE-like apps)
  - [PARTIAL] Nerd Font detection: `GlyphSupport`/`GLYPHORA_ASCII` cover the ASCII fallback. A Nerd Font tier
    (opt in through an environment variable) would allow file icons in `filePicker`/`directoryTree`. S · L-M

### Bubblezone — 918★ — mouse zones for Bubble Tea
https://github.com/lrstanley/bubblezone — zero-width markers around rendered strings, scanned after render to recover
bounds.
- [HAS] glyphora hit-tests mouse events per element natively (`dsl/FrameCoordinates.scala`, `EventRouter`).
  Nothing to borrow.

### Soft Serve — 7,264★ — a Git server whose UI is a TUI over SSH
https://github.com/charmbracelet/soft-serve — the flagship Wish app. Its relevance is as proof that a TUI served over
SSH can be a product (see Wish).

---

## Go — others

### tview — 14,137★ — retained-mode widget toolkit on tcell (used by k9s)
https://github.com/rivo/tview
- **Standout features:**
  - `Flex`, plus `Grid` with **row/column spans and minimum-size breakpoints**:
    `AddItem(p,row,col,rowSpan,colSpan,minGridHeight,minGridWidth,focus)`, `SetGap` (`grid.go:201`).
  - `Pages` with names, `ShowPage` and `SwitchToPage`.
  - `TextView` with inline `[color]` tags, `[″region″]` regions, `Highlight(ids…)` and `ScrollToHighlight()`.
  - `Table` over a virtual `TableContent` interface for lazy or huge data (`table.go:235`).
  - `TextArea.SetClipboard(copy, paste)`, `Replace(start,end,text)`.
  - `Application.QueueUpdateDraw(f)`; `Modal`; `Image`.
- **Borrow:**
  - [HAS] Pages (`dsl/ScreenStack.scala`), modal/dialog, thread hand-off (`RenderThread.capture`, `Async`).
  - [NEW] **A `grid(…)` layout element with row/col spans and gaps**. Only `row`/`column`/`Constraint` exist; no
    grid layout factory found. Breakpoints can reuse `responsive`. M · M-H (dashboards)
  - [NEW] **Lazy/virtual table and list content** (`rowCount` + `row(i)`), so a `DataTable` over a million rows
    never holds them all. `DataTable` windows a materialised `Seq` today. M · M-H
  - [PARTIAL] Inline markup strings such as `"[red::b]x[-]"`. glyphora builds `Span`s in code. A
    compile-time-checked `markup"[bold red]Hi[/]"` interpolator (like the existing `ColorLiteral`/`KeySpecLiteral`)
    fits the codebase well. S/M · M (shared with Spectre markup)

### tcell — 5,231★ — terminal cell library
https://github.com/gdamore/tcell
- **Standout features:**
  - Environment escape hatches that override the application:
    `TCELL_KEYBOARD_PROTOCOL=auto|legacy|kitty|win32|xterm`, `TCELL_NEGOTIATE=disable`, `TCELL_MOUSE=disable`,
    `TCELL_ESCDELAY=ms`, `TCELL_TRUECOLOR=disable`.
  - Alternate character set fallback, locale conversion.
  - A **simulation screen**.
  - Targets: WASM, Windows and Plan 9.
- **Borrow:**
  - [HAS] Simulation screen (`HeadlessBackend`); ASCII fallback; `GLYPHORA_NO_CAPABILITY_PROBE`
    (`terminal/ReplyQueries.scala:37`).
  - [PARTIAL] A **documented, complete set of user-side overrides**: `GLYPHORA_KEYBOARD=legacy|kitty`,
    `GLYPHORA_MOUSE=off`, `GLYPHORA_ESCDELAY` (the decoder has `escapeTimeoutMillis` but no env knob was found),
    `GLYPHORA_COLOR=16|256|truecolor`. Lets users work around a misbehaving terminal without a rebuild. S · M

### termui — 13,586★ (last push 2025-07) — dashboard widgets
https://github.com/gizak/termui — BarChart, braille Canvas, Gauge, Image, List, Tree, Plot, PieChart, Sparkline,
StackedBarChart, Table, Tabs; a relative grid.
- [HAS] All of these widgets exist in glyphora. [NEW] Relative grid: see tview Grid above.

### gocui — 10,613★ (last push 2025-05) — minimalist overlapping views
https://github.com/jroimartin/gocui — **views implement `io.ReadWriter`**, so an app can `fmt.Fprintf(view, …)` into
them; overlapping views; concurrency-safe `g.Update`.
- [PARTIAL] A `java.io.Writer`/`OutputStream` adapter onto `LogState` (thread-safe, marshalled through
  `RenderThread.capture`), so existing JVM code that writes to a stream can feed a pane. S · M

---

## C / C++

### FTXUI — 10,815★ — functional C++ DOM and components
https://github.com/ArthurSonzogni/FTXUI · examples running in the browser via WebAssembly:
https://arthursonzogni.github.io/FTXUI/examples/
- **Model:** immutable `Element` trees (`vbox`, `hbox`, `flex`, decorators applied with `|`) plus stateful
  `Component`s. Decorators include `Renderer(child, fn)`, `CatchEvent`, `Hoverable`, `Modal`, `Collapsible`, and
  `ResizableSplitLeft/Right/Top/Bottom` (`component.hpp`).
- **Standout features:**
  - **`Window(WindowOptions)`**: draggable and resizable on each side, with title, `left/top/width/height` refs and a
    custom `render(WindowRenderState{drag,resize,hover_*})` (`component_options.hpp:242–274`).
  - **Selection API**: `App::GetSelection()`, `SelectionChange(cb)`, and `selectionColor`/`selectionStyle`
    decorators — mouse text selection across the whole UI.
  - `flexbox` / `hflow` / `vflow` with wrap.
  - Paragraph justify.
  - `App::PrintAbove(Element)`, `App::FixedSize`, `TerminalName()`.
  - Emscripten builds of whole apps; fuzzers in CI.
- **Borrow:**
  - [HAS] Element tree and decorators (`Element` fluent builders), collapsible, modal/dialog, resizable split,
    hover, canvas, animations, insert-above, fixed-size headless rendering.
  - [NEW] **Movable/resizable floating windows**: a `window(title, Signal[Rect])(content)` element that handles drag
    on the title bar and edges, raise-on-click z-order, and clamping. `positioned`/`layers` give the drawing half;
    drag, edge hit-testing and z-order are missing. M · M
  - [NEW] **App-wide mouse text selection + copy**: drag a range on any text leaf, highlight it with a theme
    selection style, extract the text through the leaf's widget (wide glyphs via `CharWidth`), copy with OSC 52.
    Apps that capture the mouse lose the terminal's native selection, so this restores it. M/L · M-H
  - [NEW] **Flex wrap** (`hflow`): children flow onto the next line when the width runs out — tag lists, chips,
    button bars. No `wrap` layout found (`Overflow` is text wrapping). M · M
  - [PARTIAL] Justified paragraphs: `Alignment` is Left/Center/Right only (`core/Alignment.scala:12`). S · L
  - [NEW] Run in a browser: FTXUI and imtui both ship Emscripten demos. For glyphora that means Scala.js plus a
    xterm.js `Backend` — only feasible because `Backend` imports no JLine types. Interactive docs would be a big win.
    L · M

### notcurses — 4,755★ — "blingful" character graphics
https://github.com/dankamongmen/notcurses
- **Model:** z-ordered **planes** ("piles") with transparency and reparenting; `ncvisual` blitting.
- **Standout features:**
  - Bitmap graphics through **Sixel, Kitty and the Linux framebuffer**, with an automatic step down to sextants,
    octants and half blocks unless `NCVISUAL_OPTION_NODEGRADE` is set.
  - FFmpeg video playback (`ncplayer`).
  - Palette fades.
  - Widgets `ncreel`, `ncreader`, `ncmenu`.
  - "TUI mode" vs "CLI mode".
  - Tools: **`notcurses-info`** (prints a terminal-capability diagnostic) and **`notcurses-input`** (decodes and
    prints keypresses).
- **Borrow:**
  - [HAS] Sextant/octant/half-block blitting, fades (`Effect.fadeIn/fadeOut`), layers.
  - [NEW] **Pixel image protocols** (Kitty graphics, then Sixel and iTerm2) behind a capability probe, falling back to
    the existing half-block `Image`. `Image.scala` says "Kitty/iTerm2/Sixel protocols remain out of scope". The diff
    renderer needs placement IDs and must clear images when cells change. L · M
  - [NEW] **A `doctor` tool**: an example or `main` that prints `TerminalCapabilities`, colour depth, glyph tier,
    XTVERSION, the OSC 11 background and a live key/mouse decoder echo — like `notcurses-info` and
    `notcurses-input`. Makes "it looks wrong in my terminal" issues triageable. S · M-H
  - [PARTIAL] Per-plane transparency: layers are opaque overlays plus `Clear`. A cell-level "transparent background"
    for overlays (blend with what is underneath, as `Shadow` does) would allow translucent modals. M · L

### Turbo Vision (magiblot/tvision) — 3,189★ — modern port of Borland TV 2.0
https://github.com/magiblot/tvision
- **Model:** a desktop of overlapping `TWindow`s, `TMenuBar`, `TStatusLine`, dialogs, `TEditor`, file dialogs;
  command/event routing.
- **Standout features:**
  - Windows drag and resize, with middle-button drag. Scrollbars scroll while dragged; menus close when clicked again.
  - **`TClipboard`** that tries, in order, the system clipboard, X11 forwarding, far2l extensions and OSC 52, and
    otherwise **falls back to an internal clipboard**. `requestText()` delivers the paste as events.
  - **stderr redirected to a buffer while running** and printed at exit or suspend.
  - `TVISION_MAX_FPS` (default 60).
  - Signal handlers that restore the terminal on SIGINT, SIGSEGV and others.
  - Linux console GPM mouse and `TIOCLINUX` modifiers; win32-input-mode and far2l keyboard extensions.
  - The `tvhc` help compiler, a `tvdemo` event-viewer applet, and the `tvterm` terminal emulator.
- **Borrow:**
  - [HAS] Terminal restore on crash or signal (`Backend.emergencyRestore`, SIGTSTP/SIGCONT in `TerminalDressing.scala`).
  - [NEW] **Capture `System.err` (and optionally `System.out`) while the TUI runs**: buffer it, show it in a debug
    toast or log, and flush it at exit or suspend. A stray `e.printStackTrace()` then cannot corrupt the screen.
    S · **H**
  - [NEW] **A `menuBar(...)` element** (Alt+letter mnemonics, dropdown `Menu`s, opens on mouse). glyphora has `Menu`,
    `Dropdown` and `CommandPalette` but no menu bar or context menu (no grep hit). M · M
  - [NEW] A frame-rate cap (`maxFps`) in `RunnerConfig` for slow terminals or SSH. No fps or throttle setting found;
    frames are produced on demand. S · L-M
  - [NEW] A debug event-viewer overlay: toggled by a key, it shows the last N decoded events with modifiers. Pairs
    with `doctor`. S · M
  - [PARTIAL] A clipboard fallback chain plus an internal clipboard: today `copyToClipboard` is OSC 52 only, with no
    read and no local fallback for `TextArea` cut/paste. S/M · M

### FINAL CUT — 1,208★ — Qt-style C++ widget toolkit
https://github.com/gansm/finalcut — a virtual terminal with overlapping virtual windows; "NewFont" (a custom
X11/console font with graphical glyphs); a Rotozoomer benchmark of terminal output speed.
- [HAS] Virtual buffer and diffing. [NEW, low value] A terminal-throughput benchmark example (`performance.md` exists;
  a full-screen stress pattern would add a real-terminal number). S · L

### ImTui — 3,634★ — Dear ImGui in the terminal
https://github.com/ggerganov/imtui — immediate mode; the whole ImGui API; ncurses and **Emscripten** backends (live web
demos).
- No architectural borrow: glyphora's declarative `View` re-runs every frame, so it already behaves much like
  immediate mode. [NEW] The web target is the same idea as under FTXUI.

### termbox2 — 774★ — single-header C terminal I/O
https://github.com/termbox/termbox2 — a deliberately tiny API (`tb_init`, `tb_set_cell`, `tb_present`, `tb_poll_event`)
with bindings in about 10 languages.
- [HAS] glyphora's `Backend` plus `Buffer` is the same layer. Nothing to borrow.

---

## .NET

### Spectre.Console — 11,665★ — rich console output, prompts and live displays
https://github.com/spectreconsole/spectre.console (CLI parsing now lives in
https://github.com/spectreconsole/spectre.console.cli, 84★)
- **Model:** `IRenderable`s written to `IAnsiConsole`, plus a `LiveDisplay` that redraws a region in place. Not an
  event-loop TUI.
- **Standout features** (from the source tree):
  - `Live/Progress/Columns/`: **`DownloadedColumn`, `ElapsedTimeColumn`, `PercentageColumn`, `ProgressBarColumn`,
    `RemainingTimeColumn`, `SpinnerColumn`, `TaskDescriptionColumn`, `TransferSpeedColumn`** — multi-task progress
    with ETA and throughput.
  - `Status`: a spinner plus a message around a block of work.
  - Prompts: `TextPrompt<T>` with validation, secret input and default; `SelectionPrompt`; `MultiSelectionPrompt` with
    groups.
  - `Recorder` / `AnsiConsole.ExportHtml()` / `ExportText()` / `ExportCustom(encoder)`.
  - **`WriteException(ex, ExceptionFormats)`**: pretty stack traces (shortened types and paths, links).
  - `FigletText` with a parser for `.flf` font files.
  - `TextPath`: a path that shrinks to fit and keeps the file name.
  - `BreakdownChart`; `Calendar` with `CalendarEvent`s; `Canvas`; `Grid`/`Columns`/`Rows`/`Layout` splitter.
  - `[bold red]markup[/]` with `Markup.Escape`.
  - `Enrichment/CI/*`: detects GitHub Actions, GitLab, Jenkins, TeamCity and others to turn interactivity and colour
    off.
  - `Spectre.Console.Testing.TestConsole`, an ImageSharp image extension and a JSON-highlighting extension.
- **Borrow:**
  - [HAS] Tables, trees, bar charts, calendar, canvas, rule, panel, BigText, spinners, JSON syntax highlighting
    (`widgets/SyntaxHighlighter.scala`), test console (`Pilot`/`HeadlessBackend`).
  - [NEW] **Progress columns and multi-task progress**: a `progressTasks(tasks)` element whose columns compute ETA,
    rate (items/s or bytes/s), elapsed and remaining from a caller-owned `ProgressTask` (`started`, `completed`,
    `total`, clock). `ProgressLabel` today offers only Percentage/Text/Hidden (`widgets/ProgressLabel.scala:12–21`).
    `DurationFormat` and `Stopwatch` already exist to build on. M · **H** (loadtest/installer use cases)
  - [NEW] **An exception renderer**: a `Throwable` drawn as a styled, collapsible stack (frames grouped by package,
    the app's own frames highlighted). Usable from `RunnerError.Handler` to make crash screens readable. S/M · M
  - [NEW] **Shrink a path to fit** (`/home/u/…/src/Main.scala`): one `CharWidth`-aware helper that `filePicker`,
    `directoryTree`, `DataTable` cells and titles can share. S · M
  - [NEW] **A `markup"…"` interpolator** (see tview). Compile-time validated, which fits `ColorLiteral` and
    `KeySpecLiteral`. S/M · M
  - [NEW] **HTML export**: see Freeze. Spectre shows the API shape: `Recorder` wraps the console and offers
    `Export(encoder)`.
  - [NEW] **Detect CI and non-interactive runs**: when CI is detected or stdout is not a TTY, run in a "plain"
    profile (no alt screen, no colour, one final frame) instead of failing with `UnsupportedTerminal`
    (`JLine3Backend.scala:658`). Every example must already exit cleanly without a TTY, so the hook exists. S/M · M
  - [PARTIAL] FIGlet: `BigText` has one built-in font (`widgets/BigText.scala`). Parsing standard `.flf` files from
    resources (no reflection needed) opens up the FIGlet font library. M · L-M
  - [PARTIAL] `BreakdownChart` (a single proportional bar plus a tag legend) can be composed from `stackedBarChart`
    today; a dedicated widget is S · L.

### Terminal.Gui v2 — 11,278★ — full GUI-style toolkit for .NET
https://github.com/gui-cs/Terminal.Gui · docs in repo `docfx/docs/` (newinv2.md, arrangement.md, layout.md,
config.md, input-injection.md, Popovers.md, inline-output.md)
- **Model:** a `View` hierarchy with **Adornments** (Margin/Border/Padding as views), `Pos`/`Dim` layout,
  `IRunnable`, instance-based `Application`, ANSI/Windows/Unix drivers and a fake test driver.
- **Standout features:**
  - **`ViewArrangement` flags**: `Movable`, `Left/Right/Top/BottomResizable`, `Overlapped` (z-order), plus a
    keyboard **Arrange mode** for moving and resizing views without a mouse.
  - **`Pos`/`Dim`**: `Pos.Right(otherView)+1`, `Pos.AnchorEnd()`, `Pos.Center()`, `Pos.Align()` for button groups,
    `Dim.Fill(to: view)`, `Dim.Auto()`, `Dim.Percent()`.
  - **`TuiConfigurationBuilder`**: JSON config (themes, key-binding overlays, view defaults, app settings POCOs
    through `BindAppSettings<T>`), with `ThemeManager.SwitchTheme`. End users can remap keys and themes without code
    changes.
  - New views: `CharMap` (Unicode browser with UCD data), `ColorPicker` (several colour models), `DatePicker`,
    `FlagSelector`, `LinearRange` (range selection), `NumericUpDown<T>`, `HexView`, `GraphView`, `Wizard` (validated
    steps), `Shortcut`/`Bar`/`StatusBar`/`MenuBar`/`PopoverMenu`, `FileDialog` with search and history, and a
    `TableView` with check boxes and tree rows.
  - `Application.Popover` hosts popovers centrally.
  - `PrintAboveAsync` for inline apps.
  - `Logging` on Microsoft.Extensions.Logging, with a `Meter` for frame rate and redraw time.
  - Input injection with **virtual time** (direct vs ANSI-pipeline modes).
  - Sixel images; AOT.
  - `Terminal.Gui.Interop.Spectre`: renders Spectre renderables inside a view.
- **Borrow:**
  - [HAS] Popovers (`portal`, `dsl/PortalQueue.scala`), StatusBar with shortcuts, number input, multi-select
    (`selectionList`), inline print-above, frame metrics (`runtime/FrameTiming.scala`), virtual-time tests
    (`ManualClock`, `Pilot.sendBytes`), native AOT.
  - [NEW] **User-editable config** (HOCON/JSON or a tiny key=value file) for theme overrides and **key remapping** of
    `KeyBindings`, loaded from `$XDG_CONFIG_HOME/<app>/`. Needs a zero-dependency parser; `examples/weather/Json.scala`
    shows the "no dependency" route. M · M-H
  - [NEW] **A `wizard(steps)` element**: Back/Next/Finish, per-step validation gate, step indicator. Composes with
    derived `Form`. S/M · M
  - [NEW] **Two-thumb range slider** (`LinearRange`). `SliderRange` is bounds plus step only
    (`widgets/SliderRange.scala`). S · M
  - [NEW] Specialised inputs: `colorPicker` (HSL/RGB sliders plus hex, using `Color.parse`), `datePicker` (`Calendar`
    plus keyboard navigation and commit — `Calendar` today is display-only, and `macros/FormFieldType.scala:137` notes a
    calendar picker is not there yet), `hexView` (binary inspector over a caller-owned byte source), `charMap`. Each
    S/M · L-M; `datePicker` is M because forms can then derive `LocalDate` fields.
  - [NEW] Relative positioning (`Pos.Right(view)`, `Dim.Fill(to: view)`, `Pos.Align` for button rows): glyphora has
    row/column constraints plus `place`/`centered` (`dsl/Chrome.scala:148–176`). A `buttonRow(alignment)` helper
    covers most real uses of `Pos.Align`. S · L-M
  - [NEW] Keyboard **arrange mode** for floating windows (accessibility: move and resize without a mouse). Do it
    together with FTXUI `Window`. M · M
  - [PARTIAL] `TableView` cells holding check boxes or tree rows (`DataTable` plus a selection column). M · L-M

---

## Top 12 ideas from Go + C++/.NET (ranked by value ÷ effort, filtered to what glyphora lacks)

| # | Idea | Source(s) | Tag | Effort | Value |
|---|------|-----------|-----|--------|-------|
| 1 | **Light/dark detection via OSC 11** → `TerminalCapabilities.background`, `isDark`, default `Theme` chosen from it, `lightDark(a, b)` colours. The decoder already sees and drops these replies (`InputDecoder.scala:344`). | Bubble Tea v2, Lip Gloss v2, Bubbles `DefaultStyles(isDark)`, Glamour | NEW | S/M | H |
| 2 | **Export a frame as SVG/HTML/ANSI**: `CompletedFrame`/`Buffer` keeping style, wide glyphs and links, plus `Snapshot.ansi` to print any `Element` once to stdout with no runner. | Freeze, Spectre `Recorder.ExportHtml`, Lip Gloss | NEW | S/M | H |
| 3 | **Capture stderr/stdout while running**, buffer it, show it in a debug pane, flush at exit or suspend. | Turbo Vision | NEW | S | H |
| 4 | **Progress task columns**: ETA, rate, transfer speed, elapsed/remaining, multi-task list over a caller-owned `ProgressTask`. | Spectre `Live/Progress/Columns/*` | NEW | M | H |
| 5 | **ANSI → `Text` parser** (SGR + OSC 8), `CharWidth`-correct, to embed coloured subprocess or `git` output in `Log`/`ScrollView`. | Lip Gloss / colorprofile writer | NEW | M | H |
| 6 | **One-shot inline prompts and forms that return a value** (`Prompt.select/confirm/input`, `Form.run(spec): Option[A]`), plus a cooked-mode accessible variant; later a gum-like native CLI. | Huh, Spectre prompts, Gum | NEW / PARTIAL | M | H |
| 7 | **A tape runner over `Pilot`**: scripted `Type`/`Sleep`/`Wait /re/`/`Screenshot` → asciicast + SVG frames + `.txt` goldens; regenerate docs GIFs in CI. | VHS (+ teatest) | NEW (builds on HAS Pilot) | M | H |
| 8 | **Search in viewport**: highlight matches, next/prev, scroll-to-match; optional gutter (line numbers) on `ScrollView`/`Log`/`TextArea`/`Markdown`. | Bubbles viewport v2, tview `TextView.Highlight` | NEW | M | M-H |
| 9 | **A `tui-ssh` module** serving a `TuiApp` per SSH session (streams-based `JLine3Backend` factory, client env/TERM, resize, auth hooks); then ECH/REP in the encoder to save bandwidth. | Wish, Soft Serve, Bubble Tea cursed renderer / ultraviolet | NEW | L | H |
| 10 | **`doctor` + event viewer**: print capabilities, depth, glyph tier, XTVERSION and background; live key/mouse echo; an in-app debug overlay for decoded events; documented `GLYPHORA_*` user overrides (keyboard, mouse, escape delay, colour). | notcurses-info/-input, tvdemo event viewer, tcell `TCELL_*` | NEW / PARTIAL | S | M-H |
| 11 | **Grid layout with spans and gaps** plus **flex wrap** (`hflow`) plus **lazy/virtual table/list content** for huge datasets. | tview `Grid`/`TableContent`, termui grid, FTXUI `hflow`/`flexbox`, Spectre `Grid` | NEW | M | M-H |
| 12 | **Floating windows + menu bar**: movable/resizable `window(…)` with z-order and a keyboard arrange mode, `menuBar` with mnemonics and a context menu; then app-wide mouse text selection + copy. | FTXUI `Window` + Selection API, Terminal.Gui `ViewArrangement`, Turbo Vision | NEW | M-L | M |

**Runners-up** (smaller or more niche):
- Terminal niceties bundle (S each): OSC 9;4 native progress, mode 2027, setting OSC 10/11/12 colours, XTGETTCAP.
- `suspendFor[A]` + `openInEditor(path,line)` (S).
- Exception renderer (S/M).
- Path shrinking (S).
- `markup"…"` interpolator (S/M).
- Named theme presets (Dracula/Catppuccin/Nord) (S).
- User config for theme overrides and key remaps (M).
- `wizard`, range slider, `datePicker`, `colorPicker`, `hexView` (S–M each).
- GFM tables in `Markdown` (M).
- An SLF4J/JUL → `Log` widget adapter (M, as a separate artifact).
- Kitty/Sixel pixel images with half-block fallback (L).
- An embedded terminal-emulator widget (L).
- A Scala.js + xterm.js backend for in-browser docs (L).

**Already covered, so skip:** harmonica springs, bubblezone mouse zones, help/key bindings, paginator, timers,
pages/screens, teatest/golden, the simulation screen, the termui widget set, resizable split, collapsible, hover,
popovers, inline print-above, frame metrics, virtual-time tests, native AOT.
