# Python + JS/TS TUI ecosystems — exclusive features & what glyphora could borrow

Researched 2026-10-18. Sources: shallow sparse clones in `tui-research/clones/{textual,rich,ptk,ink,inkui,opentui,clack}`,
READMEs via `gh api repos/<r>/readme` (saved in `tui-research/readmes/`), star counts via `gh api repos/<r>` (same day).
glyphora claims checked by grepping `core/ terminal/ widgets/ runtime/ dsl/ macros/ test-support/ website/docs/`.

Tags: **[HAS]** glyphora already does it (file cited) · **[PARTIAL]** some of it exists · **[NEW]** absent.
Effort S/M/L (≈ days / 1–2 weeks / multi-week or new module). Value H/M/L (for glyphora's users: Scala CLI/dashboard/agent-tool authors).

Already borrowed from Textual and **out of scope here**: Pilot headless driver, screen stack, TabbedContent, event bubbling.

---

## Quick glyphora baseline (what surprised me while checking)

Glyphora already covers many "exclusive" items, so they are omitted or marked [HAS]:
`suspend {}` (`dsl/TuiApp.scala:425`), `printAbove` / `insertBefore` (Ink `<Static>` equivalent, `TuiApp.scala:431,451`),
`Viewport.Inline(h)` (`runtime/Viewport.scala`), OSC 52 `copyToClipboard`, OSC 8 links (`widgets/Link.scala`, Markdown links),
kitty keyboard protocol (`terminal/KittyKeys.scala`), bracketed paste, a fuzzy command palette (`dsl/CommandPalette.scala`),
toasts (`dsl/Toasts.scala`, `Notifications.scala`), frame `Effect`s with `sequence/parallel/delay/repeat`
(`core/Effect.scala`), `Tween`/`Spring`/`Easing`, `useSignal`/`useState`/`keyed` hooks (`dsl/ViewState.scala`),
`ManualClock` + `Pilot` (`test-support/`), golden text frames (`GoldenFrames`), `Form.accessible`, `Theme.detected()` glyph
ceiling, `HighContrast` theme, `collapsible`, `selectionList`, `templateInput` (masked input), `bigText` (≈ Digits),
`directoryTree`, `log` with follow-tail (`widgets/Log.scala`), `TaskScope` cancellable task groups (`runtime/TaskScope.scala`).

---

# Python

## Textual — Textualize/textual · ★ 37,476 · https://github.com/Textualize/textual
Positioning: the reference "web-app-like" Python TUI framework (asyncio, retained widget DOM, CSS).
Architecture: retained widget tree (DOM) + message pump per widget (async), TCSS stylesheet cascade, reactive descriptors
with `watch_`/`compute_`/`validate_` hooks, compositor that renders Rich `Segment`s with line-level diffing.

Exclusive / standout (cited from docs in the clone `docs/guide/*.md`, mirrored at https://textual.textualize.io/guide/):
- **TCSS stylesheets**: type/id/class selectors, pseudo-classes (`:focus`, `:hover`, `:disabled`, `:empty` added 5.1.0),
  combinators, specificity, `!important`, CSS variables, nesting; external `.tcss` files with **live reload** under
  `textual run --dev` (guide/CSS, guide/devtools "Live editing").
- **DOM queries**: `query_one("#id", Type)`, `query(".cls").filter/exclude/first/last`, loop-free bulk ops
  (`.add_class`, `.remove()`), `query_one_optional` (7.3.0) (guide/queries).
- **Workers**: `@work(exclusive=True, thread=True, group=…)` — `exclusive` cancels prior workers in the group so late
  responses can't overwrite newer ones; worker state events; `get_current_worker().is_cancelled` (guide/workers).
- **Command palette providers**: `command.Provider` with `startup/search/discover/shutdown`, `Hit`/`DiscoveryHit`,
  per-screen `COMMANDS`, `App.get_system_commands` (guide/command_palette).
- **Devtools**: `textual console` separate-terminal log sink with verbosity filters (`-x EVENT`), `log()` /
  `self.log(locals())`, Python `logging` handler (guide/devtools).
- **Snapshot testing**: `pytest-textual-snapshot` `snap_compare(app, press=[…], terminal_size=(w,h), run_before=…)`
  saves **SVG screenshots** and shows a visual HTML diff report on failure; `--snapshot-update`
  (https://github.com/Textualize/pytest-textual-snapshot, ★52).
- **Screenshots**: `App.save_screenshot()/export_screenshot()` → SVG; `deliver_screenshot` (src/textual/app.py:1855-1918).
- **textual-serve / textual-web**: run any Textual app in a browser over websockets (`Server("python -m app").serve()`);
  textual-web publishes public URLs (★407 / ★1,473; https://github.com/Textualize/textual-serve).
- **Themes**: 20+ registered themes (nord, gruvbox, dracula, tokyo-night, catppuccin×4, solarized, rose-pine×3, flexoki,
  monokai, atom-one, `ansi-dark/ansi-light` which keep the user's terminal palette) with derived shades
  (`$primary-darken-2`, `$text-muted`), theme switcher in palette (src/textual/theme.py; guide/design).
- **`animate("attr", value, duration=, easing=, delay=, on_complete=)`** on any style or reactive (guide/animation).
- **Reactive extras**: `validate_x` (coerce on set), `watch_x(old,new)`, `compute_x`, `recompose=True`, `data_bind`
  parent→child (guide/reactivity).
- **Widget.loading = True** overlays a `LoadingIndicator` on any widget; `Screen.get_loading_widget` (6.7.0).
- **Tooltips** as a widget property (`widget.tooltip = "…"`), shown on hover.
- **TextArea**: tree-sitter highlighting (`language="python"`, custom languages), selection, soft wrap, read-only,
  `code_editor()` preset, ghost `suggestion` (6.0.0), placeholder (docs/widgets/text_area.md).
- **DataTable**: `cursor_type` row/column/cell/none, `fixed_rows`/`fixed_columns`, zebra stripes, `update_cell`,
  multi-key `sort(key=…)` (docs/widgets/data_table.md).
- **Input**: `validators=[…]`, `restrict=` regex, `type="integer|number"`, `suggester=SuggestFromList`, `valid_empty`.
- **Keymaps**: bindings get ids; `App.set_keymap/update_keymap` lets users rebind (app.py:4011); `Binding.Group` with
  `compact` footer display (6.0.0/6.2.0).
- **App modes**: named, independent screen stacks (`MODES`, `switch_mode`, `push_screen(mode=…)` 8.0.0).
- **Mouse text selection** across widgets + `TextSelected` event (6.11.0), auto-scroll while selecting (8.2.0);
  `pointer` CSS rule → OSC 22 mouse cursor shape (7.4.0).
- **Markdown**: `MarkdownViewer` with TOC, `Markdown.append()`/`get_stream()` for streaming LLM output (5.0.0).
- `Screen` opacity / dimmed background under modals; `DOMNode.trap_focus` (6.5.0); `App.bell()`; `run(inline=True)`.

Borrow for glyphora:
- [NEW] **Styled SVG/HTML frame export** (`Buffer → svg`, also for golden diffs): glyphora golden frames record glyphs,
  not styling (`website/docs/testing.md` "A golden frame records glyphs, not styling"). Pure function in `test-support`
  (or `core`), no deps. Gives styled snapshot tests, docs screenshots, bug reports. **S–M / H**
- [PARTIAL] **Exclusive/latest-wins tasks**: glyphora has `Async.run` + `TaskScope` but the docs teach a manual
  generation counter ("Drop a stale response", `website/docs/live-data.md`). Add `TaskScope.latest(key)(work)(onResult)`
  / `exclusive = true` that cancels and suppresses earlier deliveries. **S / H**
- [NEW] **Signal animation**: `signal.animateTo(target, duration, easing, onComplete)` driving a `Tween` off
  `AnimationClock` (today `Tween.at(AnimationClock.elapsed)` is manual, `website/docs/motion.md`). **S / M**
- [NEW] **`.loading(flag)` decorator** on any element (overlay spinner/skeleton, blocks input). glyphora has
  `skeleton`/`spinner` but no per-element busy overlay (grep `loading(` → none). **S / M**
- [PARTIAL] **Command providers**: palette today filters only declared `KeyBindings` (`CommandPalette.scala:41`). Add a
  `CommandSource` trait (search(query) → hits, async-capable via `Async`) for "open file", "go to row", theme switcher.
  **M / H**
- [PARTIAL] **Theme catalogue**: glyphora ships `Dark/Light/HighContrast` (`Theme.scala:267-324`) + palettes
  (`core/palette/{Material,Tailwind}.scala`). Add nord/gruvbox/catppuccin/tokyo-night/dracula + an **ANSI-only theme**
  that never emits RGB (respects user terminal palette) + palette "switch theme" command. **S / M**
- [NEW] **User keymaps**: binding ids + `KeyBindings.remap(Map(id → keys))` loadable from a config file. **S–M / M**
- [PARTIAL] **Tooltip on hover**: `tooltip(text)` is a free-standing widget you place yourself
  (`ElementFactories.scala:863`); hover tracking exists (`MouseCaptureMode`). Add `.tooltip("…")` modifier that a
  portal shows after hover delay / on focus. **M / M**
- [PARTIAL] **TextArea power**: glyphora `TextArea` has undo/redo but "no syntax highlighting" (`widgets/README.md`), no
  selection, no soft-wrap toggle, no ghost suggestion. `SyntaxHighlighter` covers only `Scala/Json/Bash/Generic`
  (`widgets/SyntaxHighlighter.scala:22`). Add selection + highlighting via the existing highlighter + a pluggable
  `Lexer` trait. (Tree-sitter needs JNI/FFM natives — conflicts with native-image simplicity; prefer pure-Scala lexers.)
  **M–L / H**
- [NEW] **DataTable `fixedColumns`/`fixedRows`, zebra stripes, cell-cursor mode**: `DataTableOptions` has
  `columnHighlightStyle`/`cellHighlightStyle` but no frozen columns or striping (`widgets/DataTable.scala:310`). **S–M / M**
- [PARTIAL] **Input validators/suggester**: validators exist for derived forms (`macros/FormSpec.scala`), `numberInput`
  restricts digits; missing per-input `restrict` regex, live validation state, and **ghost-text suggestion**. **S / M**
- [NEW] **App modes** (several named screen stacks, e.g. "dashboard"/"logs"/"settings" each keeping its own back stack).
  **S–M / L**
- [NEW] **Mouse text selection + copy** (drag, double-click word, triple-click line, auto-scroll) — users can't copy text
  out of a mouse-capturing app today. Needs a selection overlay in the frame + `copyToClipboard`. **M–L / H**
- [NEW] **OSC 22 pointer shape** (`.pointer(Pointer.Hand)` on clickable elements). Pure escape, silently ignored where
  unsupported. **S / L**
- [PARTIAL] **Streaming Markdown**: `Markdown` widget excludes tables/images (`widgets/Markdown.scala:22`) and has no
  incremental append. Add tables + `MarkdownState.append(chunk)` that re-parses only the last block — key for LLM/agent
  CLIs. **M / H**
- [NEW] **Modal backdrop dim**: `dismissibleOverlay` backdrop is a plain spacer (`dsl/Chrome.scala:138`); add an
  opacity/dim pass over the covered frame (could reuse `Effect` cell transforms). **S / M**
- [NEW] **Dev console / live theme reload**: see Ink devtools + OpenTUI console below; Textual's `textual console` is a
  separate-process log sink — a JVM equivalent is a socket appender + `glyphora-console` CLI. **M / M**
- [NEW] **Serve in browser** (textual-serve analogue): a `Backend` that speaks xterm.js over WebSocket (JDK
  `HttpServer` + a tiny WS impl, or optional module). glyphora's `Backend` trait + diff encoder make this natural. **L / M**
- [PARTIAL] **Selector-based Pilot**: `Pilot.click` takes coordinates only; `FocusTracker.areaOf` exists internally
  (`dsl/Focus.scala:105`). Add `pilot.clickKey("save")`, `pilot.areaOf(key)` (Textual `pilot.click("#save")`). **S / H**
- Not recommended: full TCSS cascade — conflicts with glyphora's typed, rebuild-not-mutate `Element` tree and
  `Theme`-in-the-`View` design; the theme catalogue + live theme reload captures most of the value.

## Rich — Textualize/rich · ★ 57,563 · https://github.com/Textualize/rich
Positioning: non-interactive pretty output library; Textual's render layer.
Architecture: `Console` renders "renderables" (anything with `__rich_console__`/`__rich__`) into `Segment`s;
`Measurement` protocol (`__rich_measure__`) for min/max width; `Live` re-renders a renderable in place.

Exclusive / standout:
- **Renderable protocol**: any user object gets `__rich__` / `__rich_console__(console, options)` + `__rich_measure__`
  (https://rich.readthedocs.io/en/latest/protocol.html).
- **Console markup** `"[bold red]Error[/] in [link=…]file[/link]"`, emoji codes `:warning:`.
- **`Console.export_svg/save_svg/export_html/export_text`** + `record=True` (rich/console.py:2192-2606).
- **`Live(renderable, transient=True, refresh_per_second=4, redirect_stdout=True)`** — prints captured stdout above
  the live region; transient removes the region on exit (docs/source/live.rst).
- **Progress** with composable columns (`SpinnerColumn`, `BarColumn`, `TimeRemainingColumn`, `TransferSpeedColumn`,
  `MofNCompleteColumn`, `DownloadColumn`…), `track(iterable)`, `progress.open(file)` / `wrap_file`
  (rich/progress.py:507-914).
- **Pretty tracebacks** with locals and `suppress=[framework]`; `install()` global hook; `RichHandler` for `logging`.
- **`inspect(obj)`**, `Pretty` (auto-highlighted repr), `ReprHighlighter`/`JSONHighlighter`/`ISO8601Highlighter` regexes.
- `Console.status("…")` spinner context, `Console.pager()`, `Columns`, `Tree`, `Panel`, `Layout`, `Syntax`.

Borrow for glyphora:
- [NEW] **Print-mode `Console`**: render any `Element`/`Widget` at terminal width and write it to stdout as ANSI —
  no runner, no raw mode (Rich `console.print`, Ink `renderToString`). Ingredients exist: `Snapshot.render`
  (`dsl/Snapshot.scala:32`) + `FrameEncoder.encodeAll` (`terminal/FrameEncoder.scala:44`, currently
  `private[terminal]`). Gives every widget (tables, charts, trees, markdown) a second life in plain CLIs and CI logs.
  **S / H**
- [NEW] **Compile-time-checked markup interpolator** `markup"[bold red]Error[/] saving [link=$url]file[/]"` → `Line`;
  fits the existing `key"…"` macro pattern (`dsl/dsl.scala:202`) and `ColorLiteral`. **S–M / H**
- [NEW] **`Show`-style renderable typeclass**: `given Renderable[Order]` (→ `Element`) so lists/tables/log can take domain
  values; plus a **`Pretty` derivation** over `Mirror` (case class → collapsible tree with highlighted fields), which
  fits `tui-macros`' reflection-free derivation. **M / M**
- [NEW] **Progress column model**: `progressTable(tasks)(columns = Seq(Spinner, Description, Bar, Percent, Eta,
  Speed, MofN))` with an ETA/speed estimator — glyphora has gauges/bars but no task-list progress with ETA. **M / H**
- [PARTIAL] **Transient / stdout-redirecting live region**: `Viewport.Inline` + `printAbove` exist; missing
  `transient` (erase the region on exit) and auto-capture of `System.out/err` into `printAbove`. **S / M**
- [NEW] **Pretty error screen**: a `stackTrace(throwable)` widget (frames grouped, app frames highlighted, library
  frames folded) used as the default fallback when `view` throws. **S–M / M**

## prompt_toolkit — prompt-toolkit/python-prompt-toolkit · ★ 10,599 · https://github.com/prompt-toolkit/python-prompt-toolkit
Positioning: line-editing/REPL library (powers IPython, ptpython, pgcli); also full-screen apps.
Architecture: `Buffer`+`Document` editing model, layered `KeyBindings` with `Filter`s, `Completer`/`Validator`/
`AutoSuggest`/`History`/`Lexer` plug-ins, `Application` with container layout.

Exclusive / standout (docs/pages/asking_for_input.rst, advanced_topics/{filters,key_bindings}.rst):
- **Vi and Emacs editing modes**, switchable at runtime (`vi_mode=True`, `app.editing_mode`), incl. vi text objects.
- **Key-binding `Filter`s**: `Condition(lambda: …)` combinable with `&`, `|`, `~`; `ConditionalKeyBindings`,
  `merge_key_bindings`; multi-key sequences (`"c-x", "c-c"`), `eager=True`.
- **Completers**: `WordCompleter`, `NestedCompleter` (command trees), `PathCompleter`, `ExecutableCompleter`,
  `FuzzyCompleter`, `ThreadedCompleter`/`complete_in_thread=True`, `DynamicCompleter`, styled completions + meta column.
- **History + `AutoSuggestFromHistory`** (fish-style ghost text), `FileHistory`, Ctrl-R incremental search.
- `validate_while_typing`, `ThreadedValidator`; `bottom_toolbar`, `rprompt`; `patch_stdout()` (prints above prompt);
  `enable_open_in_editor` (edit buffer in `$EDITOR`); Pygments lexers.

Borrow for glyphora:
- [NEW] **Key sequences / chords + conditional bindings**: `binding("g g", …)`, `binding("ctrl+x ctrl+s", …)`,
  `.when(cond)`; glyphora `KeyBinding` matches single events (`dsl/KeyBindings.scala:79`). Show pending prefix in the
  status bar. **M / H**
- [NEW] **Input history + ghost auto-suggest** for `input`/`textArea` (`InputHistory` caller-owned state, Up/Down,
  Ctrl-R, Right-arrow accepts suggestion). **S–M / H** (REPL/agent CLIs)
- [PARTIAL] **Async/threaded, nested completer** for `autocomplete`: glyphora has `autocomplete` factory; add a
  `Completer` trait (sync or `Async`-backed with latest-wins) and a `NestedCompleter` for command trees. **M / M**
- [NEW] **Vi/Emacs editing modes** for `TextInput`/`TextArea` as a pluggable keymap. **M / M**
- [NEW] **Open buffer in `$EDITOR`**: built on existing `suspend {}` (`TuiApp.scala:425`) — tiny helper,
  `editExternally(text): Option[String]`. **S / M**

## urwid — urwid/urwid · ★ 3,025 · https://github.com/urwid/urwid
Positioning: veteran widget toolkit; pluggable event loops (asyncio, trio, Twisted, ZeroMQ…), raw/curses/web/LCD displays.
Standout: `Terminal` widget (embedded VT emulator, `TermCanvas`), `ListBox` over lazy `ListWalker` (virtualised
arbitrary widgets), display modules incl. an experimental web display.
Borrow:
- [PARTIAL] **Virtualised list of arbitrary elements** (`ListWalker`): glyphora `ListView` items are `String | Line`/`Text`;
  `scrollView` renders all children. A lazy `each(range)(i => Element)` with only-visible materialisation would scale
  to 100k rows. **M / M**
- [NEW] Embedded terminal — see OpenTUI. 

## asciimatics — peterbrittain/asciimatics · ★ 4,309 · https://github.com/peterbrittain/asciimatics
Positioning: animation/"demo-scene" toolkit + forms. Standout: `Scene`s of `Effect`s, sprites with paths, particle
systems (fireworks, rain, explosions), banners, anti-aliased line drawing, image→ASCII (JPEG/GIF) conversion, screen scraping.
Borrow:
- [PARTIAL] glyphora has frame `Effect`s (`core/Effect.scala`: fadeIn/dissolve/coalesce/sweepIn/typewriter/pulse) and
  `Canvas`. Add a **particle system effect** (seeded, pure `elapsed → cells`, matches `Progress` contract) for splashes
  and celebrations. **M / L**

## pytermgui — bczsalba/pytermgui · ★ 2,668 · archived · https://github.com/bczsalba/pytermgui
Standout: TIM markup language with aliases/macros, YAML styling, desktop-like **WindowManager** (draggable, overlapping,
modal windows), SVG/HTML terminal screenshots, inspector.
Borrow:
- [NEW] **Floating window manager layer** (z-ordered, draggable/resizable windows over the view; `portal` +
  `positioned` exist as building blocks). **M / L**
- Markup + SVG export: covered under Rich/Textual.

## Trogon — Textualize/trogon · ★ 2,844 · https://github.com/Textualize/trogon
Positioning: `@tui()` decorator auto-generates a TUI from a Click CLI schema ("Swagger for CLIs"); edit options,
see the composed command line, run it.
Borrow:
- [PARTIAL] glyphora's `deriveForm` (`macros/`) derives a form from a case class. A **`tui-cli` bridge** that derives a
  command-builder UI from a mainargs/decline/scopt definition (or from a case-class of options) and returns the parsed
  config is a natural extension. Keep it in a separate optional module to avoid a CLI-lib dependency. **M / M**

---

# JS / TS

## Ink — vadimdemedes/ink · ★ 40,116 · https://github.com/vadimdemedes/ink
Positioning: React renderer for CLIs; de-facto host for agentic coding CLIs (README "Who's using": Claude Code,
Gemini CLI, GitHub Copilot CLI, Wrangler, Shopify CLI, Prisma…).
Architecture: React reconciler → DOM-like nodes → Yoga flexbox → string output; log-update style inline rendering
(not alt-screen by default), `<Static>` for committed output.

Exclusive / standout (readme.md in clone):
- **`<Static items>`** permanent output above the live region (logs/completed tasks); **`<Transform transform>`**
  string post-processing of a subtree (gradients, links).
- **Hooks**: `useInput`, `usePaste`, `useFocus({autoFocus,id})`, `useFocusManager` (`focusNext/focus(id)`),
  `useBoxMetrics(ref)`/`measureElement`, `useWindowSize`, `useCursor().setCursorPosition` (IME), `useAnimation`
  (`frame/time/delta`), `useApp().suspendTerminal`, `waitUntilRenderFlush`, `useIsScreenReaderEnabled`.
- **Screen-reader mode** (`isScreenReaderEnabled` / `INK_SCREEN_READER=true`): `aria-role` (button, checkbox, listbox,
  progressbar, tab, table, textbox, timer, toolbar…), `aria-label`, `aria-hidden`, `aria-state` → emits linear text such
  as `(checked) checkbox: Accept terms`.
- **`patchConsole`**: intercepts `console.*`, clears the live frame, prints the log, redraws.
- **`interactive` auto-detection** (CI / non-TTY): skips cursor tricks and writes only the final frame at unmount.
- `incrementalRendering` (changed lines only), `maxFps`, `onRender({renderTime})`, `concurrent` (Suspense,
  `useTransition`), `alternateScreen`, `kittyKeyboard`, `renderToString(tree, {columns})`.
- **React DevTools** integration (`DEV=true` + `npx react-devtools`: inspect/edit props live).
- **ink-testing-library** (★153): `render()` → `lastFrame()`, `frames[]`, `stdin.write()`, `rerender`.
- **ink-ui** (★2,073): EmailInput, PasswordInput, ConfirmInput, MultiSelect, StatusMessage, Alert, Badge, ordered/
  unordered lists, theming via `extendTheme`.

Borrow for glyphora:
- [HAS] `<Static>` ≈ `printAbove`/`insertBefore` (`TuiApp.scala:431-451`); hooks ≈ `useSignal/useState/keyed`
  (`dsl/ViewState.scala`); `useCursor` ≈ hardware caret (`Pilot.cursorPosition`); diff rendering
  (`terminal/FrameEncoder.scala`); `suspendTerminal` ≈ `suspend`; `onRender` ≈ `RunnerConfig.onFrameTiming`.
- [NEW] **Non-interactive fallback**: glyphora exits 1 with "terminal not supported: dumb terminal (no TTY attached)"
  (`website/docs/build-a-load-generator.md:1071`). Offer `RunMode.Auto` → when stdout is not a TTY / `CI` is set, run
  headless and print the final frame (or each `printAbove`) as plain/ANSI text. Makes glyphora CLIs pipe- and CI-safe.
  **S–M / H**
- [PARTIAL] **Accessibility tree / screen-reader output**: only `Form.accessible` exists
  (`website/docs/forms-and-validation.md`). Add `.role(Role.Checkbox).ariaLabel("…").ariaHidden` on `ElementProps` and
  a `ScreenReaderBackend`/mode that linearises the focused path + changes into text lines. Unique among JVM toolkits.
  **M / H**
- [NEW] **stdout/stderr capture** (`patchConsole`): swap `System.out/err` for a stream that routes lines through
  `printAbove` (inline) or into a log overlay (fullscreen); troubleshooting currently just says don't `println`
  (`website/docs/troubleshooting.md` "Debug output destroys the live UI"). **S / H**
- [NEW] **Per-subtree transform** (`.transform(buffer ⇒ …)` / `.effect(e)` scoped to an element's rect): glyphora's
  `Effect`s are whole-frame (`dsl/EffectStack.scala`, `TuiApp.runEffect`). **S–M / M**
- [PARTIAL] **Element metrics hook**: `useBoxMetrics(ref)` — glyphora exposes `terminalSize`/`breakpoint` and
  `responsive`, but an element can't read its own laid-out `Rect` (only internal `FocusTracker.areaOf`). Add
  `useArea(key): Option[Rect]` (last-frame) for popover anchoring/virtualisation. **S / M**
- [PARTIAL] **Frame list in tests**: `Pilot.lastFrame` + `LatestFrameObserver` (sampling, explicitly "not a lossless
  recording API", `runtime/LatestFrameObserver.scala:10`). Add a lossless `pilot.frames` / `FrameRecorder` for
  animation assertions (also OpenTUI `TestRecorder`). **S / M**
- [NEW] **Element inspector** (React-DevTools analogue): toggle overlay listing the element path under the mouse,
  its key, props, claim and rect; or a JSON tree over a socket. **M / M**
- [PARTIAL] **Prompt-style inputs**: password input (Masked exists in `core/Masked.scala`), email/confirm inputs exist
  partially (`confirmDialog`, `templateInput`); `StatusMessage`/`Alert` ≈ `notice`. Mostly covered.

## OpenTUI — anomalyco/opentui (formerly sst/opentui) · ★ 13,574 · https://github.com/anomalyco/opentui · https://opentui.com/docs
Positioning: Zig-native core with TS bindings + React and Solid reconcilers; powers OpenCode.
Architecture: retained native scene (Yoga per node) → native paint into cell buffers → session diff; JS paint hooks
(`renderBefore/renderSelf/renderAfter`), `onUpdate`/`onResize` hooks, demand-driven or continuous frame scheduling
(docs: core-concepts/rendering-pipeline).

Exclusive / standout (paths under `packages/web/src/content/docs/`):
- **`DiffRenderable`**: unified **or split** view of a git patch with syntax highlighting + line numbers (components/diff).
- **`Code`/`Markdown`/`Textarea` with tree-sitter** highlighting (reference/tree-sitter), extmarks with undo history
  (`core/src/lib/extmarks*.ts`), `LineNumberRenderable`.
- **`EmbeddedTerminalRenderable`**: Ghostty VT parser inside the tree — write child PTY output in, encoded input out,
  scrollback, selection (components/embedded-terminal).
- **`@opentui/keymap`**: host-agnostic layered keymap — focus-scoped layers with priority, commands registry,
  multi-key sequences/leader keys, `getActiveKeys()`/`getPendingSequence()` for which-key UIs, ex-commands addon
  (keymap/overview, keymap/addons).
- **Console overlay**: captures global `console.*` into an in-app toggleable log pane with levels and caller
  `file:line` in debug mode (core-concepts/console).
- **Global mouse text selection** with word/line multi-click semantics, `selection` event, copy (core-concepts/interaction);
  drag/drop events `onMouseDrag/DragEnd/Drop/Over/Out`.
- **Mouse pointer styles** via OSC 22 (`renderer.setMousePointer("pointer")`).
- **Desktop notifications** via OSC 99 / 777 / 9 with capability detection (core-concepts/notifications).
- **Post-processing**: `colorMatrix` (sepia, grayscale, protanopia/deuteranopia/tritanopia **simulation and
  compensation** matrices), scanlines, noise, chromatic aberration, bloom, vignette, CRT bar, flames
  (`core/src/post/{filters,matrices,effects}.ts`, reference/color-matrix); **alpha blending** in buffers (core-concepts/colors).
- **ScrollBox**: `stickyScroll` + `stickyStart` (bottom/top/left/right, pauses when user scrolls away), viewport culling
  (components/scrollbox).
- **`@opentui/ssh`**: serve the app per SSH session, client PTY size tracking (reference/ssh).
- Testing: `createTestRenderer`, `mockKeys/mockMouse`, `ManualClock`, `TestRecorder` (all frames, with fg/bg/attr buffers),
  `MockTreeSitterClient` (core-concepts/testing).
- QR code renderable, `ASCIIFont`, `FrameBuffer` raw drawing, images, audio, Three.js WebGPU → terminal.

Borrow for glyphora:
- [NEW] **`diffView(patch, mode = Unified|Split)`** widget (parse unified diff → hunks, +/- gutters, intra-line
  highlight, reuse `SyntaxHighlighter`). High value for Scala dev-tools / agent CLIs. **M / H**
- [NEW] **Layered keymap with sequences & which-key**: `Keymap.layer(priority, scope = focus key)`; pending-sequence
  query feeding `statusBar`/`helpOverlay` (`dsl/Chrome.scala:83`). Combine with the prompt_toolkit chord item. **M / H**
- [NEW] **In-app console overlay**: `System.out/err` + an slf4j-free `Log` sink → toggleable bottom pane built on the
  existing `log` widget (follow-tail already in `widgets/Log.scala`). **S–M / H**
- [NEW] **OSC desktop notifications** `notifyDesktop(title, body)` behind `TerminalCapabilities` (glyphora already
  probes capabilities: `terminal/ReplyQueries.scala`). **S / M**
- [PARTIAL] **Sticky scroll**: `Log` follows tail; `ScrollView` only has a jump-to-bottom (`widgets/ScrollView.scala:45`).
  Add `ScrollViewState.stick(Edge.Bottom)` with pause-on-manual-scroll — chat/LLM transcript need. **S / H**
- [NEW] **Colour-vision simulation effects**: `Effect.colorMatrix(m)` + `ColorMatrix.{Protanopia,Deuteranopia,
  Tritanopia,Grayscale}` — doubles as an a11y QA tool ("view my app as a deuteranope", also in Pilot assertions).
  Fits `core/Effect.scala`. **S / M**
- [NEW] **Alpha / translucent styles**: `Color.Rgba` blended at compose time (needed for dim backdrops, glass
  overlays). Touches `Buffer` merge semantics. **M / M**
- [NEW] **Embedded terminal widget** (VT parser + PTY): enables "run tests in a pane" tooling. JVM needs pty4j (JNA,
  native-image config) + a VT parser (e.g. JediTerm core) → separate optional module, conflicts with zero-reflection
  rule unless hand-written. **L / M**
- [NEW] **SSH serving** module (Apache MINA SSHD; one `Runner` per session with its own `Backend`; glyphora's per-runner
  work queues already allow several runners per JVM — CLAUDE.md "Each Runner owns its own work queue"). **M–L / M**
- [NEW] **QR code widget** (pure encoder, half-block render) — handy for auth/device pairing flows. **S / L**
- [PARTIAL] **Lossless frame recorder** — see Ink item. [HAS] `ManualClock`, mock keys/mouse (`Pilot`).

## blessed / blessed-contrib — chjj/blessed ★ 11,889 · yaronn/blessed-contrib ★ 15,775
Positioning: curses-like widget lib + dashboard widget pack (line/bar/stacked charts, map, gauge, donut, LCD,
sparkline, table, tree, markdown, picture, **grid** layout, **carousel**) — https://github.com/yaronn/blessed-contrib.
Borrow:
- [HAS] charts, world map (`widgets/Shape.scala` WorldMap), donut (`pieChart`), gauge, sparkline, `bigText` (≈ LCD).
- [NEW] **`carousel(pages, interval)`**: auto-rotating dashboard pages with manual next/prev. **S / L**
- [PARTIAL] **12-column `grid(rows, cols)` placement** (`grid.set(row, col, rowSpan, colSpan, widget)`): glyphora has
  weighted rows/columns and `responsive`; a span-based grid is easier for dashboards. **S–M / M**

## Inquirer.js / @clack/prompts / prompts — SBoudrias/Inquirer.js ★ 21,639 · bombshell-dev/clack ★ 8,107 · terkelg/prompts ★ 9,310
Positioning: sequential, inline "wizard" prompts for CLIs.
Standout:
- clack: `intro/outro`, `group({...}, {onCancel})` where later prompts see earlier `results`, `isCancel` guard,
  `tasks([...])` spinners, `taskLog` (sub-process output that collapses on success), `stream.*` for **LLM token
  streams**, `log.step/success/warn`, `path`, `date`, `autocompleteMultiselect` (clack packages/prompts/README.md).
- Inquirer: `AbortSignal` per prompt (timeouts, `AbortSignal.timeout(5000)`), `clearPromptOnDone`, i18n, custom prompts
  via `createPrompt` + hooks, `search` (async source), `expand`, `editor` (opens `$EDITOR`).
- prompts: `prompts.inject([...])` / `override(argv)` to pre-answer (testing + non-interactive use).
Borrow for glyphora:
- [NEW] **Inline prompt API** (`tui-prompts` or in `dsl`): `Prompt.text/confirm/select/multiSelect/password/path`
  returning `Either[Cancelled, A]`, composed with a for-comprehension, rendered in `Viewport.Inline` and committed to
  scrollback via `printAbove` after each answer; `tasks(...)` and `taskLog`. Reuses existing widgets; huge for
  Scala CLIs (scala-cli scripts, sbt/Mill plugins). **M / H**
- [NEW] **Pre-answering / non-interactive answers** (`Prompt.inject` / from args or env) so the same prompt flow runs in CI.
  **S / M**
- [NEW] **Prompt timeouts/cancellation** (`.timeout(5.s, default)`). **S / L**

## terminal-kit — cronvel/terminal-kit · ★ 3,380 · https://github.com/cronvel/terminal-kit
Standout: chainable style API + short markup (`^r` red), **set terminal palette** (OSC 4), draw PNG/JPEG/GIF in the
terminal, off-screen `ScreenBuffer` / `TextBuffer` surfaces, GPM mouse on Linux console.
Borrow:
- [PARTIAL] glyphora `Image` is half-block only; "Kitty/iTerm2/Sixel protocols remain out of scope"
  (`widgets/Image.scala:9`). True-pixel kitty/iTerm/Sixel output would need a backend-side escape path (Buffer cells
  can't carry pixels). **M–L / M**
- [NEW] **Terminal palette set/query (OSC 4/10/11)** to theme the user's 16 colours or adapt to their background
  (glyphora's `InputDecoder` already parses an OSC 11 reply, `terminal/InputDecoder.scala:344`). **S / L**

## neo-blessed — embarklabs/neo-blessed · ★ 414 · last push 2021 — fork of blessed; nothing new beyond blessed.

---

# Top 12 ideas from Python + JS (ranked by value ÷ effort, glyphora fit)

| # | Idea | From | Tag | Effort | Value | Why it fits glyphora |
|---|------|------|-----|--------|-------|----------------------|
| 1 | **Print-mode console**: render any `Element`/`Widget` to an ANSI string at a width and print it (no runner); plus non-TTY/CI auto fallback that prints the final frame instead of exiting 1 | Rich `console.print`, Ink `renderToString` + `interactive` | NEW | S–M | H | `Snapshot.render` + `FrameEncoder.encodeAll` already exist; opens every widget to plain CLIs and CI |
| 2 | **Styled SVG/HTML frame export** + styled golden snapshots with visual diff report | Textual `save_screenshot`, pytest-textual-snapshot, Rich `export_svg` | NEW | S–M | H | Pure `Buffer → String`; fixes "golden records glyphs, not styling"; docs screenshots for free |
| 3 | **Latest-wins / exclusive tasks** (`TaskScope.latest(key)`) | Textual `@work(exclusive=True)` | PARTIAL | S | H | Replaces the hand-rolled generation counter the docs teach |
| 4 | **stdout/stderr capture → `printAbove` or in-app console overlay** | Ink `patchConsole`, OpenTUI console overlay, Rich `redirect_stdout`, ptk `patch_stdout` | NEW | S–M | H | Removes the #1 footgun listed in troubleshooting.md; `log` widget already follows tail |
| 5 | **Inline prompt/wizard API** (`Prompt.text/select/confirm`, `tasks`, `taskLog`, cancellation, injectable answers) | clack, Inquirer, prompts | NEW | M | H | Built on `Viewport.Inline` + `printAbove` + existing inputs; Scala scripting/CLI audience |
| 6 | **Compile-time markup interpolator** `markup"[bold red]x[/] [link=$u]y[/]"` | Rich markup, pytermgui TIM, terminal-kit | NEW | S–M | H | Same macro pattern as `key"…"`; one-import surface |
| 7 | **Key chords/sequences, conditional + layered keymaps, which-key hint, user remapping by id** | prompt_toolkit filters, OpenTUI keymap, Textual keymaps | NEW | M | H | `KeyBindings` is single-event; statusBar/helpOverlay/palette can show pending keys |
| 8 | **Streaming-friendly text surfaces**: `Markdown.append` (+tables), sticky-bottom `ScrollView` | Textual `Markdown.append/get_stream`, OpenTUI `stickyScroll`, clack `stream` | PARTIAL | S–M | H | Agent/LLM CLIs are Ink's main market; glyphora lacks both pieces |
| 9 | **`diffView(patch, Unified/Split)`** with syntax highlight | OpenTUI `DiffRenderable` | NEW | M | H | Dev-tool killer widget; reuses `SyntaxHighlighter` + `Table`-style gutters |
| 10 | **Mouse text selection & copy** (drag/double/triple click, auto-scroll) | Textual 6.11+, OpenTUI selection | NEW | M–L | H | Mouse-capturing apps currently block terminal-native copy; OSC 52 already there |
| 11 | **Accessibility roles + screen-reader linear output mode** | Ink `aria-*` / `INK_SCREEN_READER` | PARTIAL | M | H | Generalises `Form.accessible`; differentiator for a JVM toolkit |
| 12 | **Command providers + theme catalogue/switcher (incl. ANSI-palette theme)** | Textual `command.Provider`, 20+ themes, `ansi-dark` | PARTIAL | S–M | M–H | Palette today only lists declared bindings; 3 built-in themes |

Honourable mentions (cheap): `.loading(flag)` overlay decorator (S/M), `signal.animateTo` (S/M), Pilot `clickKey(key)`
selectors (S/H for tests), lossless `FrameRecorder` (S/M), OSC 9/777/99 desktop notifications (S/M), colour-vision
simulation `Effect.colorMatrix` (S/M), `editExternally` via `suspend` (S/M), input history + ghost auto-suggest (S–M/H),
DataTable frozen columns/zebra (S–M/M), modal backdrop dim (S/M).

Bigger bets (L, separate optional modules to protect the zero-reflection/native-image rules): browser serving over
WebSocket (textual-serve), SSH serving (`@opentui/ssh`), embedded terminal (OpenTUI/urwid), tree-sitter highlighting,
pixel image protocols, element inspector/devtools.

Unverified / caveats:
- Textual `App.set_keymap` exists in source (`src/textual/app.py:4011`); exact user-facing config story not checked.
- OpenTUI repo now lives at `anomalyco/opentui` (`sst/opentui` resolves to it via `gh api`).
- Effort estimates assume glyphora's current architecture; Mouse selection and alpha blending touch `Buffer`/compose
  semantics and could be larger.
