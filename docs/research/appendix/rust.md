# Rust TUI ecosystem → ideas for glyphora

Researched 2026-10-18. Stars from `gh api repos/<owner>/<repo>` on that date. Sources: GitHub READMEs fetched via
`gh api …/readme`, plus shallow clones in `tui-research/clones/` (ratatui 0.30.2, cursive, tui-realm, iocraft,
crossterm, tachyonfx, tui-widgets, tui-rs-tree-widget, tui-input); file paths below are inside those clones.
Glyphora claims were checked by grepping `core/ terminal/ widgets/ runtime/ dsl/ test-support/ website/docs/`.

Tags: **[HAS]** already in glyphora (file cited) · **[PARTIAL]** some of it exists · **[NEW]** missing.
Effort S/M/L · Value H/M/L. Glyphora already copied ratatui's Buffer/Cell, Constraint/Flex, Widget/StatefulWidget,
inline viewport/insertBefore, so those are only listed when something newer exists.

---

## 1. ratatui (core) — 23,006★ — https://github.com/ratatui/ratatui
The standard Rust immediate-mode TUI library. Currently 0.30.2 (CHANGELOG: 0.30.0 2025-12-26, 0.30.2 2026-06-19).

**Architecture:** immediate mode. `Terminal::draw(|frame| …)` renders widgets into a `Buffer`, diffs it against the
previous one, flushes the result through a `Backend` (crossterm / termion / termwiz / termina). Since 0.30 it is split
into crates: `ratatui-core`, `ratatui-widgets`, `ratatui-macros` and one crate per backend.

**Exclusive / standout (0.29–0.30+):**
- `Layout::areas::<N>(rect) -> [Rect; N]`, `try_areas`, `spacers::<N>`, `Rect::layout::<N>(&layout)`
  (ratatui-core/src/layout/layout.rs:580,616,648; rect.rs:598).
- **Layout LRU cache.** A thread-local `LAYOUT_CACHE` keyed by (area, layout), sized with `Layout::init_cache(n)`
  behind the `layout-cache` feature, which is on by default (layout.rs:50,313). The cassowary solve is memoised
  across frames.
- `CellDiffOption::{None, Skip, AlwaysUpdate}` per cell, so image and link pipelines can hide cells from the diff
  (ratatui-core/src/buffer/cell.rs:13).
- Canvas `Marker` gained Quadrant/Sextant/Octant (#2235); Braille is drawn over block glyphs in Chart and Canvas (#2165).
- `Flex::SpaceEvenly`, and `SpaceAround` now follows CSS flexbox rules (BREAKING-CHANGES.md v0.30).
- Table `Cell`s that span several columns (#2150); `Block` shadows (#2481); `Fill` widget (#2520); a filled-area line
  shape for Canvas and Chart (#2426).
- `Stylize` trait: `"x".red().on_white().bold()` works on any `Styled` value, widgets included
  (ratatui-core/src/style/stylize.rs:233).
- `ratatui-macros`: `constraints![==50%, >=3]`, `span!`, `line!`, `text!`, `row!` (ratatui-macros/README.md).
- `ratatui::run(|terminal| …)`, `init()` and `restore()`, with a panic hook installed for you
  (ratatui/src/init.rs:323,433,634).
- `no_std`, which is what lets embedded (mousefood) and wasm (ratzilla) backends exist; `serde` for Style and Color;
  `palette` crate interop; a `scrolling-regions` feature (ratatui/Cargo.toml).
- `Backend` save/restore cursor is planned for 0.31 (BREAKING-CHANGES.md summary).

**Borrow for glyphora:**
- [HAS] `areas::<N>`, as `Layout.split2..split5` (core/Layout.scala:106-118).
- [HAS] Cell diff opt-out: `DiffDirective.{Default, Skip, AlwaysUpdate}` (core/DiffDirective.scala:28-48).
- [HAS] Quadrant/Sextant/Braille/Octant canvas resolutions (widgets/SubCell.scala:15-20).
- [HAS] Column-spanning table cells (`TableCell.columnSpan`, widgets/Table.scala:97); shadows (widgets/Shadow.scala);
  `Flex.SpaceEvenly` (core/Flex.scala:16); filled lines (`Shape.FilledLine`, widgets/Shape.scala:62).
- [HAS] Terminal restore on crash: a JVM shutdown hook plus `emergencyRestore()` (runtime/TerminalRunner.scala:169).
- [HAS] Fluent styling on spans and elements (dsl/dsl.scala:338-403).
- [NEW] **Layout memoisation.** An LRU cache in front of `LayoutSolver.solve` keyed by
  `(constraints, flex, spacing, axisLength)`. `LayoutSolver` is already a pure function, so the cache is trivially
  correct. Every frame re-solves the same nested rows and columns. S / M (measure first with `Bench`).
- [PARTIAL] `serde`-style round-tripping of Style and Theme. `Color.parse` and `render` exist (core/Color.scala:87);
  `Style` and `Theme` have no text form. See "theme files" under cursive. S / M.
- [PARTIAL] Constraint shorthand. `Layout.horizontal(Int | Double | Constraint*)` exists (Layout.scala:315). A
  `constraints"==50% >=3 *2"` interpolator checked at compile time would match how the codebase already does
  `hex"…"` and `key"…"`. S / L.

## 2. tachyonfx — 1,311★ — https://github.com/ratatui/tachyonfx
Shader-style post-processing effects that run over a rendered Buffer.

**Architecture:** an `Effect` is a stateful shader. `process(delta, buf, area)` runs *after* widgets render.
`EffectManager<K>` owns the running effects.

**Exclusive / standout:**
- 50+ effects. Colour: `fade_from/to(_fg)`, `paint(_fg/_bg)`, `hsl_shift`, `saturate`, `lighten`, `darken`. Text and
  motion: `coalesce`, `dissolve`, `evolve` (cycles symbol sets), `slide_in/out`, `sweep_in/out`, `explode`, `expand`,
  `stretch`, `glitch`, `translate`, `translate_buf`. Control: `parallel`, `sequence`, `repeat`, `ping_pong`, `delay`,
  `sleep`, `prolong_start/end`, `freeze_at`, `remap_alpha`, `never_complete`, `with_duration` (README; src/fx/*.rs).
- **`CellFilter`** chooses which cells an effect touches: `All`, `Area(Rect)`, `RefArea`, `FgColor`, `BgColor`,
  `Inner(Margin)`, `Outer(Margin)`, `Text`, `NonEmpty`, `AllOf`, `AnyOf`, `NoneOf`, `Not`, `Layout(layout, idx)`,
  `PositionFn`, `EvalCell` (src/cell_filter/filter.rs:90-358).
- **Spatial patterns** turn a single progress value into a per-cell progress: `RadialPattern`, `DiamondPattern`,
  `SpiralPattern`, `DiagonalPattern(.with_transition_width)`, `CheckerboardPattern`, `SweepPattern`, `WavePattern`
  (FM/AM), `CombinedPattern`, `BlendPattern`, `InvertedPattern` (src/pattern/).
- `ColorSpace::{Rgb, Hsl, Hsv}` for interpolation, HSL by default (src/color_space.rs).
- `EffectManager::add_unique_effect(key, fx)`: a new effect with the same key replaces the old one
  (src/effect_manager.rs:91).
- `RefRect` / `DynamicArea`: a shared, mutable area, so an effect follows a widget that moves during a resize
  (src/ref_rect.rs, src/fx/dynamic_area.rs).
- **Effect DSL** compiled at runtime (`EffectDsl::new().compiler().compile("fx::dissolve(500)")`), and the browser
  editor "TachyonFX FTL". Works on wasm and `no_std`.

**Borrow for glyphora:**
- [HAS] The base effect algebra: `fadeIn/Out`, `coalesce`, `dissolve`, `sweepIn`, `slideInFromRight`, `typewriter`,
  `pulse`, `sequence`, `parallel`, `delay`, `repeat` (core/Effect.scala, 186 lines). Run with `TuiApp.runEffect`
  (dsl/EffectStack.scala).
- [NEW] **Cell filters.** Add `Effect.filtered(pred: (Position, Cell) => Boolean)` with ready-made filters
  (`text`, `fg(c)`, `inner(n)`, `outer(n)`). The `mapCells` and `eraseWhere` helpers in Effect.scala already iterate
  cells, so the filter is one predicate. S / H.
- [NEW] **Effects attached to an element.** Today `EffectStack.start` always covers the whole frame (EffectStack.scala:22).
  `element.withEffect(fx)` could resolve the node's painted rect the way `FrameCoordinates` and `Focus.areaOf`
  already do, which gives "flash this panel", "dissolve this toast" and "sweep in this dialog". M / H.
- [NEW] **Spatial patterns.** `Effect.withPattern(Pattern.radial(center) | diagonal | sweep(dir) | checker)` maps
  per-cell progress = f(global progress, x, y). These are pure functions and fit `core` next to `Progress` and
  `Easing`. M / M.
- [NEW] **Keyed (unique) effects.** `runEffect(key, fx)` replaces whatever effect is running under that key, so
  hammering a key does not stack 20 fades. Glyphora already gives toasts and timers keys ("Give every timed action a
  key", live-data.md). S / M.
- [PARTIAL] More effects: `hslShift`, `paint`, `glitch`, `evolve(symbolSet)`, `pingPong`, `reversed`, `freezeAt`,
  `slideIn(direction)`. Only right-to-left slide exists today. S each / M.
- [PARTIAL] Choosing the interpolation colour space. `Color.mix` and `mixHsl` both exist (core/Color.scala:242,276),
  but fades are hard-wired. S / L.

## 3. ratatui-image — 408★ — https://github.com/ratatui/ratatui-image
Image widget that speaks the Sixel, Kitty and iTerm2 graphics protocols, falling back to halfblocks.

**Architecture:** a `Picker` guesses the protocol from environment variables, confirms it with control-sequence
queries, and measures the font size in pixels. `picker.new_protocol(img, size, Resize::Fit)` encodes once. The
stateless `Image` renders an already-encoded protocol cheaply. `StatefulImage` re-fits to its area but blocks, so
`ThreadProtocol` moves the encoding to a worker. Cells under the image are marked skip. Optional `chafa` gives better
text-art. There is also a screenshot test suite run across terminals (xterm, foot, kitty, wezterm, ghostty, rio, mlterm).

**Borrow for glyphora:**
- [PARTIAL] Glyphora renders halfblocks only. widgets/Image.scala:9 says "Kitty/iTerm2/Sixel protocols remain out of
  scope". The pieces underneath are already there: `DiffDirective.Skip` exists for exactly this purpose, and
  `WindowSize.cellPixels` gives the cell size in pixels (terminal/WindowSize.scala).
- [NEW] **Graphics-protocol image.** A `GraphicsProtocol` probe (kitty `APC _G` query, Sixel via DA1 `4`, iTerm2 via
  `TERM_PROGRAM`) in `TerminalCapabilities`, plus `Backend.drawImage(rect, encoded)` and Skip cells. Encode on
  `Async` and render the cached payload. Start with kitty, which is stateful and does not tear; Sixel needs a quantiser.
  L / H.
- [NEW] Kitty **Unicode placeholders**, as used by yazi (README "Kitty unicode placeholders", kitty ≥0.28). The image
  is addressed by placeholder cells inside the normal Buffer, so it survives the diff and works under tmux. M / M.

## 4. tui-widgets (joshka) — 241★ — https://github.com/ratatui/tui-widgets
A monorepo that collects tui-big-text, tui-popup, tui-prompts, tui-qrcode, tui-scrollview, tui-scrollbar,
tui-bar-graph, tui-box-text, tui-cards and tui-equalizer.

**Exclusive / standout:**
- tui-big-text: `font8x8` glyphs with `PixelSize::{Full, HalfHeight, HalfWidth, Quadrant, ThirdHeight, Sextant,
  QuarterHeight, …}` (tui-big-text/src/pixel_size.rs), giving several text sizes from one font.
- tui-popup: `PopupState::move_by`, `mouse_down` and `handle_mouse_event`, so a popup can be **dragged with the mouse**
  (tui-popup/src/popup_state.rs:42-97).
- tui-scrollview: renders arbitrary widgets into an oversized virtual `Buffer` (`ScrollView::new(Size)`,
  `render_widget(w, rect)`) and scrolls it on **both axes**. Scrollbars follow
  `ScrollbarVisibility::{Automatic, Always, Never}` (tui-scrollview/src/scroll_view.rs:72-213).
- tui-scrollbar: **fractional thumbs drawn in 1/8-cell steps**, arrow endcaps you can click, and a pure
  `ScrollMetrics` for hit testing (tui-scrollbar/src/lib.rs:14-24).
- tui-prompts: inquire-style inline prompts with a `Status::{Pending, Aborted, Done}` lifecycle.
- tui-qrcode: QR codes drawn in half-blocks. tui-bar-graph: gradient bar graphs using colorgrad palettes
  (Magma/Viridis/Plasma/Inferno/Sinebow) in braille, solid, quadrant and octant styles.

**Borrow for glyphora:**
- [HAS] BigText (widgets/BigText.scala, a 3×5 font with one cell per pixel), Popup chrome, Scrollbar, BarChart.
- [PARTIAL] **BigText pixel sizes.** Reuse `SubCell`'s `CanvasResolution` (HalfBlock/Quadrant/Sextant/Octant) to draw
  the existing font at 2–8 pixels per cell, plus an 8×8 font for legibility. S / M.
- [NEW] **Fractional scrollbar thumb** in eighths. `BlockLadder` already has the eighth glyphs (widgets/BlockLadder.scala:14).
  Add `ScrollbarOptions.fractional = true`. S / M.
- [PARTIAL] **ScrollView on both axes, with auto-hide.** Glyphora's is vertical only, with `showScrollbar: Boolean`
  (widgets/ScrollView.scala). Add a horizontal offset and `ScrollbarVisibility`. M / M.
- [NEW] **Draggable / movable dialogs.** Store dialog position in caller-owned state and let a title-bar drag move it.
  Drag events already reach `splitPane` (dsl/NavigationElements.scala:169). S / M.
- [NEW] **QR code widget.** Needs an in-house QR encoder (no dependencies): about 600 lines of Reed–Solomon plus
  masking. Useful for "scan to log in" and OTP setup in CLIs. M / M.
- [NEW] **Perceptual colour ramps** (viridis/magma/plasma/inferno/turbo) as `ColorRamp` presets. Glyphora has
  ColorRamp (widgets/ColorRamp.scala) and Material/Tailwind palettes but no scientific ramps. They would serve heatmap
  and sparkline. S / M.

## 5. tui-textarea — 513★ — https://github.com/rhysd/tui-textarea · edtui — 161★ — https://github.com/preiter93/edtui
Embeddable multi-line editors. tui-textarea is Emacs-flavoured; edtui is Vim-modal.

**Exclusive / standout:**
- tui-textarea: Emacs bindings (`C-k`, `C-j`, `C-w`, `M-f`/`M-b`, `M-<`/`M->`, paragraph moves), a **yank buffer**
  (paste back what `C-k` deleted), **regex search** (feature `search`), **text selection** with copy and cut, line
  numbers, cursor-line highlight, mouse scroll, password masking, placeholder, single-line mode with validation, and
  several independent instances on one screen.
- edtui: Vim normal, insert and visual modes behind a remappable `KeyEventHandler` (`KeyEventRegister::n(…)`), an
  Emacs mode, **absolute and relative line numbers**, line wrapping, syntect highlighting, system clipboard, a status
  line, and a **system-editor hand-off** (`Ctrl+e` opens `$VISUAL`/`$EDITOR`, then reloads the result).

**Borrow for glyphora:**
- [HAS] Grapheme-safe multi-line editing with undo/redo and horizontal scroll (widgets/TextArea.scala:119-138);
  placeholder and masking on input (widgets/TextInput.scala:68; core/Masked).
- [NEW] **Selection, plus copy/cut through OSC 52.** `copyToClipboard` already exists (terminal/Backend.scala:277).
  Shift+arrows extend the selection; render it with `selectionStyle`. M / H.
- [NEW] **Word and paragraph motions, and kill-line** (Ctrl/Alt+←/→, Ctrl+W, Ctrl+K, Alt+D). No word motion exists
  in TextArea or GraphemeEdit today. The semantics need a grapheme-aware word boundary; use `CharWidth` clusters plus
  `Character.isLetterOrDigit`. S / H.
- [NEW] **Search in TextArea / Log** (`/` then n/N, highlight every match). Pure Scala on the cluster lines. M / M.
- [NEW] **Line-number gutter** (absolute or relative) and cursor-line highlight as `TextAreaOptions`. S / M.
- [PARTIAL] **External editor round-trip.** `TuiApp.suspend(body)` exists (dsl/TuiApp.scala:425), but there is no
  `editExternally(state)` helper that writes a temp file, runs `$EDITOR`, reads it back and repaints. S / M.
- [NEW] An opt-in **modal (Vim) key map** for TextArea, as a pluggable `TextAreaKeymap` value. M / L.
- [NEW] **Input history** for `input(…)` (Up/Down recall, as in a REPL). No history was found in TextInput. S / M.

## 6. tui-tree-widget — 129★ — https://github.com/EdJoPaTo/tui-rs-tree-widget
**Exclusive:** `TreeState` is keyed by **identifier paths** (`Vec<Identifier>`), not indices. It offers
`select(path)`, `open(path)`, `toggle_selected()`, `close_all()`, `select_relative(f)`, `rendered_at(Position)`,
`click_at(Position)`, and `key_left()`, which closes the node or else jumps to the parent (src/tree_state.rs:98-374).

**Borrow:**
- [HAS] Tree with expand/collapse and a mouse handler (widgets/Tree.scala, dsl/CollectionElements.scala:82); lazy
  async `DirectoryTree` (widgets/DirectoryTree.scala).
- [PARTIAL] **Identity-keyed tree state.** `TreeNode(label, children)` is addressed by `Seq[Int]` index paths
  (Tree.scala:8,110), so inserting a sibling moves the selection and the expansion state onto other nodes. Add
  `TreeNode[K](id: K, …)` with expansion and selection stored as `Set[Seq[K]]`. This is the same "pin the selection to
  an identity" rule glyphora already documents for tables. M / H.
- [NEW] "Left goes to the parent" navigation, and `selectRelative`. S / L.

## 7. tui-logger — 319★ — https://github.com/gin66/tui-logger
**Exclusive:** a `log`/`tracing`/`slog` backend that copies only *enabled* records into a hot circular buffer. It
detects lost messages, filters per target, and ships a **smart widget**: a target selector with EWIDT columns that
change the displayed level and the captured level separately at runtime, a page/scrollback mode anchored to a
timestamp, and an optional mirror to a file.

**Borrow:**
- [HAS] `Log` widget with a ring buffer and follow-tail (widgets/Log.scala); `printAbove` and `insertBefore` for
  output above an inline UI.
- [NEW] **`System.Logger` / JUL bridge, plus a log-console overlay.** A `java.lang.System.LoggerFinder` (no dependency)
  or an optional SLF4J module feeds a thread-safe ring. The DSL gets `logConsole()` with level and target filters and
  a toggle key. This fixes the "println destroys the UI" problem (troubleshooting.md:261) at its source. Check how
  native-image handles ServiceLoader. M / H.

## 8. Throbbers, tui-input, tui-term, ansi-to-tui, color-eyre (small crates)
- throbber-widgets-tui (54★, https://github.com/arkbig/throbber-widgets-tui): symbol sets, `WhichUse::Spin/Full/Empty`,
  negative or random steps. **[HAS]** Spinner/SpinnerPreset/OrbitSpinner/LinearSpinner/SpinnerGrid go well beyond it.
- tui-input (207★, https://github.com/sayanarijit/tui-input): input state that does not depend on a backend, with
  `serde`. **[HAS]** `TextInputState`.
- tui-term (232★, https://github.com/a-kenji/tui-term): a **pseudoterminal widget** built on the `vt100` parser that
  embeds a live shell or command in a pane. **[NEW] L / M.** It needs a PTY (pty4j uses JNA, which complicates
  native-image) plus a VT parser. A cheaper first step is the ANSI parser below.
- ansi-to-tui (100★, https://github.com/ratatui/ansi-to-tui): converts ANSI-coloured bytes into `Text`.
  **[NEW] `Text.fromAnsi(String)`**, which turns SGR sequences into Spans, for showing `git diff --color`,
  `ls --color` and compiler output in `log` or `text`. Glyphora already encodes SGR (terminal/Sgr.scala); this is the
  inverse. It belongs in `core`, with all width maths through `CharWidth`. S / H.
- color-eyre (968★, https://github.com/eyre-rs/color-eyre) and human-panic (1,852★): colourful and human-readable
  crash reports. [PARTIAL] Glyphora restores the terminal and rethrows. [NEW] An optional `crashReport` hook could
  print a styled, trimmed stack trace and the last N log lines after the restore, and write the full trace to a file.
  S / M.

## 9. ratzilla — 1,471★ — https://github.com/ratatui/ratzilla · mousefood — 1,342★ — https://github.com/ratatui/mousefood
- ratzilla: ratatui in the browser through WebAssembly, with `DomBackend`, `CanvasBackend` and a WebGL2 backend, plus
  `terminal.on_key_event` and `draw_web`. It is built with `trunk` and has a `cargo generate` template.
- mousefood: a `no_std` embedded-graphics backend that drives SPI LCDs (ILI9341/ST7735/SSD1306) with bitmap fonts,
  including bold and italic.

**Borrow:**
- [NEW] **Browser backend.** Two possible routes. (a) Cross-compile `core` and `widgets` for Scala.js and write a DOM
  or canvas backend: L effort, and it requires those modules to have no JVM-only APIs. (b) A **WebSocket backend**
  that streams `FrameEncoder` output to xterm.js (in effect zellij's web client): M effort, works with the
  architecture as it is, and enables live demos on the docs site. M–L / M.
- [NEW] **HTML/SVG export of a Buffer** for docs, READMEs and bug reports. `Snapshot.render` already produces the
  Buffer (dsl/Snapshot.scala:32). Write `BufferExport.svg(buffer, theme)` and `.html`. This would also turn the
  glyphora docs' screenshots into generated artifacts. S / H.
- [NEW] Embedded / pixel backend: not relevant on the JVM. Skip. — / L.

## 10. cursive — 4,855★ — https://github.com/gyscos/cursive
A retained-mode TUI library: a view tree driven by callbacks.

**Architecture:** the root `Cursive` owns a `StackView` of layers inside `ScreensView`s. Views are boxed trait objects
looked up by name (`NamedView`, `call_on_name`). Callbacks receive `&mut Cursive`. `cb_sink()` lets any thread send a
closure to the UI thread. Backends are crossterm, ncurses, pancurses, termion, BearLibTerminal (`blt`) and `puppet`,
which is for tests.

**Exclusive / standout:**
- **TOML themes.** `siv.load_toml(include_str!("theme.toml"))` sets the palette, `shadow`, `borders`
  ("simple"/"outset"/"none") and per-role colours (cursive-core/src/theme.rs:1-60,230).
- **Builder / blueprints.** Views are built from YAML or JSON config (`LinearLayout: {children: [...]}`) with
  `$variables` and callbacks such as `$Cursive.quit`. Third-party crates register blueprints with `#[blueprint]`
  (cursive-core/src/builder.rs:1-60).
- **2D gradients.** `GradientView` with `Linear`, `Angled` and `Radial` interpolators (cursive-core/src/style/gradient).
- **Debug console.** `toggle_debug_console()` shows captured `log` records in a layer (cursive_root.rs:274).
- **`dump()` / `restore(Dump)`** move the whole UI (views, callbacks, menubar, user data, theme) out and back,
  for example to suspend one app inside another (cursive_root.rs:935-960).
- `EnableableView`, `HideableView`, `LastSizeView`, `OnLayoutView`, `FocusTracker`, `CircularFocus`, `FixedLayout`,
  `ThemedView` (a subtree-local theme), and a menubar with nested submenus.
- `set_autorefresh` / `set_fps`. `set_on_pre_event` vs `set_on_pre_event_inner` (before or after the children).
- The ecosystem includes cursive-async-view (a loading wrapper), cursive_hexview, cursive-hjkl (wraps any view with
  vim keys) and cursive-multiplex (tmux-like).

**Borrow:**
- [HAS] Layers and modals (`layers`, `dialog`, `portal`); a thread-safe hop to the UI thread (`RenderThread.capture`,
  runtime/RenderThread); the puppet backend (`HeadlessBackend` + `Pilot`); the named lookup equivalent (`focusKey`,
  `keyed`); inert subtrees (dsl/ElementProps.scala:19); `screen` stacks (dsl/ScreenStack).
- [NEW] **Theme files.** `Theme.parse(text)` / `Theme.render` in a tiny key=value or TOML subset (no dependency):
  `primary = "bold #7aa2f7"`, using `Color.parse` plus modifier words. Users could theme a binary without
  recompiling. Pair it with `Style.parse` and `Style.render`, building on `Style.asSource` (layout-and-style.md
  "Turn a style back into code"). S / H.
- [NEW] **`themed(theme)(subtree)`.** A subtree-local Theme override, like cursive's `ThemedView`. `View` already
  carries a `Theme` context parameter, so the decorator just re-binds it. S / M.
- [NEW] **Gradient background decorator** `.gradient(ColorRamp, angle | radial)`, painting the background per cell.
  `ColorRamp.at` already exists. S / M.
- [PARTIAL] **Menubar with submenus.** `Menu` is flat items plus separators (widgets/Menu.scala). Add a top `menuBar`
  with cascading `menu`s (Alt+letter mnemonics). M / M.
- [NEW] **Hex view widget**: offset, hex and ASCII columns over a `ByteVector`-like value, with a caller-owned cursor.
  S / M.
- [NEW] **Declarative UI from config** (cursive's builder). It needs a registry of factories and conflicts with the
  "no reflection" rule unless macro-derived. L / L, skip.

## 11. tui-realm — 1,006★ — https://github.com/veeso/tui-realm
"React + Elm" on top of ratatui.

**Architecture:** a `Component` holds props (an `Attribute → AttrValue` bag) and `State`, and emits `Msg` values.
An `Application` mounts and unmounts components by id and owns focus (`active`/`blur`). `tick(PollStrategy)` returns
`Vec<Msg>` for the app's `update` (src/core/application.rs:96-288). An `EventListener` merges terminal input, ticks
and user **Ports** (`SyncPort` / `AsyncPort`, polled sources for user events; src/listener/builder.rs:211-320).

**Exclusive / standout:**
- **Subscriptions with clauses.** A component can receive events while *not* focused when a clause holds:
  `SubClause::{Always, HasAttrValue(id, attr, v), HasState(id, s), IsMounted(id), Not, And, Or}` plus an event filter
  (src/core/subscription.rs:182). `lock_subs()` / `unlock_subs()` turn every global handler off, for example while a
  popup is open.
- `Injector`s set props on mount. `tuirealm_derive` forwards Component to an inner widget. `testing.rs` renders a
  component to a string for `insta` snapshots. The stdlib wraps every widget as a Component.

**Borrow:**
- [HAS] Snapshot testing (`GoldenFrames`, `Snapshot`), periodic sources (live-data.md "Fetch once, then every
  interval"), and global bindings that unconsumed keys bubble up to (`TuiApp.bindings`).
- [PARTIAL] **Conditional global bindings.** `KeyBinding` has no guard (dsl/KeyBindings.scala:12). Add
  `binding(…).when(cond: => Boolean)`, so disabled commands vanish from hints, help and the palette instead of
  silently doing nothing. S / M.
- [NEW] A `Port` abstraction (`TuiApp.source(every)(poll)(onValue)`) as a single named API for "poll something, push
  it as a signal". It largely duplicates `Async` plus `Timers`. S / L.

## 12. iocraft — 1,564★ — https://github.com/ccbrown/iocraft
React/Ink for Rust: `element!{ View(...) { Text(...) } }` with `#[component]` functions, and flexbox layout via taffy.

**Exclusive / standout:**
- Hooks: `use_state`, `use_future` (an async task tied to the component's lifetime), `use_effect(f, deps)`,
  `use_memo(f, deps)`, `use_ref`, `use_const`, `use_context` / `use_context_mut` with `ContextProvider`,
  `use_terminal_events`, `use_local_terminal_events`, `use_terminal_size`, **`use_component_rect`** (where this
  component was actually laid out), `use_output` (stdout and stderr handles that print *above* the live UI), and
  `use_async_handler` (packages/iocraft/src/hooks/*.rs).
- **One tree, three outputs.** `.print()`, `.eprint()` and `.to_string()` give static styled output, `render_loop()`
  gives a live inline UI, and `.fullscreen()` takes the screen. Options include `ignore_ctrl_c()`,
  `enable_bracketed_paste()` and `enable_mouse_capture()` (element.rs:204-523).
- **CSS flexbox** via taffy: `flex_wrap`, `gap` / `row_gap` / `column_gap`, `overflow_x/_y`, absolute position,
  margin, padding, percentages, `align_items` and `justify_content` (iocraft/src/style.rs:104-289).

**Borrow:**
- [HAS] `useSignal`, `useState`, `keyed` (dsl/ViewState.scala:239-273); inline vs fullscreen (`runtime/Viewport.Inline`);
  `printAbove` ≈ `use_output`; `responsive` ≈ `use_terminal_size`.
- [NEW] **`useTask` / `useEffect(deps)`.** A component-lifetime task that starts when a view slot first appears and is
  cancelled when it disappears, built on the existing `TaskScope` (runtime/TaskScope.scala) and `ViewState`
  lifetimes. Today owner-scoped tasks exist ("Optional owner-scoped tasks", async-and-timers.md) but are not tied to
  view slots. M / H.
- [NEW] **`useArea()`.** Returns the Rect this node was painted at last frame. The data is already recorded
  (`Focus.layoutAreaOf`, dsl/Focus.scala:110). It enables popovers anchored to a widget and effects scoped to a
  widget. S / M.
- [PARTIAL] **Static print of a View.** `Snapshot.render` produces a Buffer and `FrameEncoder.encodeRow` produces ANSI
  rows, but nothing public does `printView(view, width)`: measure the height through `intrinsicHeight`, emit styled
  ANSI, exit. That would make glyphora a Rich/Ink-style library for ordinary CLI output (tables, panels, progress
  summaries), which is a large audience. S / H.
- [NEW] **Wrapping flex row** (`row(...).wrap`) for chip and tag clouds and card grids. The `Constraint` solver has
  no wrap concept, so this needs a separate line-breaking pass over `Measured` widths. M / M.
- [PARTIAL] **Contexts.** `Theme` travels as a context parameter. A general `provide[A](value)(subtree)` /
  `useContext[A]` would serve app services (i18n, user prefs). S / L.

## 13. crossterm — 4,247★ · termion — 2,169★ · termwiz (in wezterm, 29,267★)
https://github.com/crossterm-rs/crossterm · https://github.com/redox-os/termion · https://github.com/wezterm/wezterm/tree/main/termwiz

**Exclusive / standout:**
- crossterm: `PushKeyboardEnhancementFlags` and `query_keyboard_enhancement_flags`, `EnableBracketedPaste`,
  `EnableFocusChange`, `BeginSynchronizedUpdate`, `SetCursorStyle`, `SetTitle`, `ScrollUp/Down`, `window_size()`
  (cells and pixels), an async `EventStream`. The **OSC 52 clipboard** takes `ClipboardType::{Clipboard, Primary}`
  (src/clipboard.rs). The **`use-dev-tty`** feature reads events from `/dev/tty` while stdin is piped
  (Cargo.toml:47). Also the Windows console API.
- termion: a small, Redox-compatible library with `async_stdin` and `read_passwd`.
- termwiz: a `Surface` with a **change log and sequence numbers**, so deltas can be computed between surfaces (used
  by the multiplexer and remote rendering). It has an escape-sequence parser that decodes *and* re-encodes,
  capability probing that overrides terminfo, and a `LineEditor` (readline with history and completion) (termwiz/src/lib.rs).

**Borrow:**
- [HAS] Kitty keyboard with event types (terminal/KittyKeys.scala, `enableKeyEventTypes`), synchronized output 2026
  (terminal/AnsiSequences.scala:52), bracketed paste (2004), focus reporting (1004), cursor shape and blink, title
  stack, scroll regions, OSC 52 copy (AnsiSequences.scala:204), pixel window size (WindowSize.scala), capability
  probe (TerminalCapabilities.scala), OSC 8 links (widgets/Link.scala), coloured and curly underlines (core/Style.scala:8).
- [NEW] OSC 52 **primary selection** (`p` target) and **paste-read** (`?` query; often disabled by terminals). S / L.
- [PARTIAL] **Piped stdin.** `cat list | app` pickers in the fzf style. JLine is built with `.system(true)` and
  refuses a dumb terminal (JLine3Backend.scala:640-658). Whether input can be read from `/dev/tty` while stdin is a
  pipe is unverified, so test it. If it works, document it, and add a `readStdinThenRun` helper. S / H.
- [NEW] **Theme change detection.** Query the background with `OSC 11` and subscribe to **CSI ?2031** (light/dark
  scheme change notifications, which yazi advertises: "CSI 2031" in its README), then pick `Theme.Light` or
  `Theme.Dark` automatically and switch at runtime. Nothing like this was found in terminal/ or dsl/. S / H.
- [NEW] A **one-shot line editor** (`Prompt.readLine(prompt, history, completer)`) on the inline viewport, the
  termwiz/rustyline niche, for CLIs that want a single nice prompt without a TuiApp. M / M.
- [NEW] **Desktop notifications** through OSC 9 / OSC 777 (iTerm2, kitty, wezterm), as `notifyDesktop(title, body)`
  next to `setTitle`. Not seen in any Rust lib above; listed for completeness. S / L.

## 14. Notable apps' techniques

**helix — 46,632★ — https://github.com/helix-editor/helix** (a modal editor on a fork of tui-rs)
- A compositor of layers: each `Component` has `handle_event → EventResult::{Consumed(Option<Callback>),
  Ignored(Option<Callback>)}`, `render(area, surface)`, `cursor(area) → (Option<Position>, CursorKind)`,
  `required_size`, and `id()` for `find_id`, `replace_or_push` and `remove` (helix-term/src/compositor.rs:13-213).
- A keymap **trie** with pending sequences (`KeymapResult::{Pending, Matched, MatchedSequence, Cancelled}`) and an
  **infobox** that pops up the possible next keys (which-key), plus sticky modes (helix-term/src/keymap.rs:30-333).
- An async fuzzy picker with a preview pane (nucleo matcher) (unverified detail: matcher name taken from project
  knowledge, not re-read).
- Borrow: [NEW] **key sequences / chords plus a which-key popup** (`binding("g g", …)`, `binding("ctrl+x ctrl+s", …)`).
  A pending prefix shows the possible continuations in a `tooltip`-style overlay, and `Pilot.press("g g")` drives it.
  `KeyBinding.triggers` is single events today (dsl/KeyBindings.scala:12). M / H.
  [HAS] layered overlay dispatch (portal/layers, inert) and per-element hardware caret (testing.md "Assert where the
  hardware caret went").

**yazi — 42,899★ — https://github.com/sxyazi/yazi** (an async file manager; https://yazi-rs.github.io/blog/why-is-yazi-fast)
- **Discardable tasks.** Every preview has a "ticket" (an atomic counter). CPU work checks the ticket on each line and
  stops as soon as the context changes; I/O is aborted.
- Micro and macro worker pools (5 and 10 by default) with low/normal/high priority and preemption.
- **Chunked and paged loading.** Only the visible page is preloaded; highlighting reads only as many lines as fit the
  viewport. Two-pass image downscaling with a cache.
- Image protocols with **stdout locked** while the escape is written, **partial erase of an image under a popup**, and
  automatic redraw when the popup closes.
- Also: Lua plugins, a pub-sub "DDS" that shares state across instances, CSI u, OSC 52, CSI 2031, drag and drop.
- Borrow: [PARTIAL] a **latest-wins `Async`** (`Async.latest(key)(work)(onResult)`) that cancels or ignores the
  previous run under the same key and exposes a cooperative `isCancelled` ticket to CPU loops. live-data.md documents
  "Drop a stale response" as a pattern; making it an API removes boilerplate. S / H.
  [NEW] priority classes on the Async pool. M / L.

**gitui — 22,578★ — https://github.com/gitui-org/gitui**
- Async git on background threads that report back through notification messages. **Context-based help**: the
  bottom bar shows only the keys valid for the focused pane. Key bindings and themes are user-overridable through RON
  files (README "Key Bindings", "Color Theme").
- Borrow: [HAS] hints and help derived from declared bindings (dsl/KeyBindings.scala `hints`, `statusBar(bindings)`)
  and per-screen bindings (dsl/Screen.scala:72). [NEW] **User-remappable keymaps** loaded from a file
  (`KeyBindings.overrides(path)` maps command ids to key specs, parsed by the same `KeyEvent.parse` the app already
  uses). S / M.

**zellij — 35,763★ — https://github.com/zellij-org/zellij** (a terminal workspace; https://zellij.dev/documentation/layouts.html)
- **KDL layout files** (`layout { pane split_direction="vertical" { pane; pane command="htop" } }`), applied at start
  or at runtime with `override-layout`. **Floating and stacked panes**. WASM plugins in any language. A built-in web
  client. Session resurrection. Multiplayer.
- Borrow: [NEW] **stacked panes**, an accordion splitPane where collapsed siblings shrink to their title row. S / M.
  [NEW] **floating windows** inside an app: movable and resizable `window(...)` layers with z-order, built on the
  draggable dialog above. M / M. [NEW] a layout DSL loaded from a file is low value for a library; skip.

---

## Top 10 ideas from the Rust ecosystem (ranked by value ÷ effort, fit with glyphora's architecture)

1. **Static `printView(view, width)` / `Element.print()`** (iocraft `.print()`, Ink/Rich niche). Reuses
   `Snapshot.render`, `intrinsicHeight` and `FrameEncoder.encodeRow` to emit styled ANSI with no TTY, which makes
   glyphora usable for ordinary CLI output. **S / H** [PARTIAL]
2. **`Text.fromAnsi` SGR parser** (ansi-to-tui). Shows coloured subprocess output in `log` and `text`; it is the
   inverse of `Sgr`, in `core`, with widths through `CharWidth`. **S / H** [NEW]
3. **Effect cell filters plus element-scoped effects** (tachyonfx `CellFilter`, `RefRect`).
   `element.withEffect(fx.filtered(Filter.text))`, with the rect resolved from `FrameCoordinates` / `Focus.areaOf`;
   then keyed unique effects and spatial patterns. **S–M / H** [NEW]
4. **Key sequences plus a which-key popup** (helix keymap trie and infobox). `binding("g g")` and
   `binding("ctrl+x ctrl+s")`; pending prefixes shown in an overlay and fed into the palette and help. **M / H** [NEW]
5. **Automatic light/dark theme**: OSC 11 background query plus CSI ?2031 change notifications (yazi), switching
   `Theme.Light` and `Theme.Dark` live. Also **theme files** with `Theme.parse` / `Style.parse` (cursive TOML themes,
   gitui RON). **S / H** [NEW]
6. **TextArea editing upgrade** (tui-textarea, edtui): word and paragraph motions, kill-line, selection with OSC 52
   copy, search, a line-number gutter, and an `editExternally($EDITOR)` helper on top of `suspend`. **M / H** [PARTIAL]
7. **Log capture bridge plus log-console overlay** (tui-logger, cursive debug console). `System.LoggerFinder`/JUL,
   optionally SLF4J, feeding a thread-safe ring, with a `logConsole()` element that filters by level and target and
   toggles on a key. **M / H** [NEW]
8. **Component-lifetime hooks: `useTask` / `useEffect(deps)` / `useArea()`** (iocraft). Tasks tied to view slots and
   cancelled on unmount via `TaskScope`, plus access to a node's last painted Rect. **M / H** [NEW]
9. **Graphics-protocol images** (ratatui-image, yazi). Probe for kitty, Sixel and iTerm2, encode on `Async`, protect
   the area with `DiffDirective.Skip`, erase and redraw under popups, and fall back to the existing halfblocks.
   **L / H** [PARTIAL]
10. **Identity-keyed `TreeState`, plus latest-wins `Async.latest(key)`** (tui-tree-widget; yazi discardable tasks).
    Both turn documented "keep the identity" and "drop stale results" patterns into APIs that cannot be got wrong.
    **S–M / H** [PARTIAL]

Runners-up: fractional scrollbar thumbs (S/M), a two-axis ScrollView with auto-hide (M/M), SVG/HTML export of a
Buffer for docs (S/H; ranked lower only because it is tooling rather than runtime), perceptual colour ramps (S/M),
draggable dialogs and stacked panes (S/M), layout memoisation (S/M, measure first), a WebSocket→xterm.js backend for
live docs demos (M/M), and a QR widget (M/M).

Unverified items: helix's picker matcher name (nucleo); whether piped stdin plus `/dev/tty` input works under
glyphora's JLine `.system(true)` terminal; how ServiceLoader behaves under native-image for a `System.LoggerFinder`
bridge.
