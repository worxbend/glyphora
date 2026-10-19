# Changelog

All seven `com.worxbend::tui-*` artifacts share one version. Before 1.0, a minor release may break
source compatibility; every break is listed under **Breaking** with a pointer to its migration
note in [Versioning & releases](website/docs/versioning.md).

## 0.20.0

### Added

**Layout and composition**

- `grid(cols, rows, gap)(children*)` with `.at(row, col)`, `.span(rows, cols)` and named `.areas(…)` / `.area(name)`; core `GridLayout`, widget `Grid`.
- `flow(gap, rowGap)(children*)`: children at their natural width, wrapped onto new lines; widget `Flow`.
- `dock(top, bottom, left, right)(center)`: panes docked to the edges of a centre pane.
- `Constraint.Auto` / `.auto` sizes to measured content; `Constraint.Bounded` / `.minSize(n).maxSize(m).fill` bounds any size.
- `CrossAlign` with `.alignItems(…)`, `.alignSelf(…)` and `Layout.withCrossAlign`; `Element.intrinsicWidth`.
- `container(children*)(place, chrome, measure)` with `ChildInfo`: custom placement that keeps focus, hit-testing and `boundsOf`; `measure` lets it size itself for `.auto`.
- Panel slots `panel(…).header(e)` / `.footer(e)`; `.border`, `.padding`, `.background`, `.shadow` (`Shadow`), `.clip` on any element.
- `scrollView(…).followFocus` (on by default) and `.ensureVisible`; `ScrollViewState.reveal(top, height)`.
- `responsive.local { size => … }`: container queries against the node's own area.
- `lazyList(count, itemHeight, state)(i => element)` with `LazyListState`: builds only the visible items.

**Theming and style**

- Component styles: `StateStyles` per role via `Theme.withComponent`, roles `ButtonRole`, `InputRole`, `ListRole`, `TableHeaderRole`, `ComponentRole.named`, and `.variant(ButtonRole.Primary)`.
- Hierarchical `StyleName` (`"list.selected"` falls back to `"list"`), `theme.component(role)`, `theme.style(name)`.
- `.frameStyle(…)` for borders and `.style(s)` for content on every container.
- Theme files: `Theme.parse` / `Theme.render`; `Style.parse` / `Style.render`. Theme files and keymaps share one `LineError(line, message)`.
- Presets `Theme.Dracula`, `Catppuccin`, `Nord`, `Gruvbox`, `Solarized`, `Ansi16`; `Theme.presets` lists all nine.
- `Theme.auto` follows the terminal's light or dark background; `lightDark(light, dark)`, `ColorScheme`, `Event.ColorSchemeChanged`.
- Component roles `DialogRole` / `DialogRole.Selected` for `dialog` and `confirmDialog`, and `SearchRole.Match` / `SearchRole.Current` for search highlights (defaulting to the theme's `warning` / `error`).
- `statusBar`, `topBar`, `helpOverlay`, `whichKeyPopup` and `.border()` built outside a view paint in the app's theme (late binding).
- `widget(…)` leaves paint the element style (and a `withStyle` cascade) under the wrapped widget.
- Theme files read and write `loading.fill-ramp`.

**Keys and focus**

- Key sequences: `binding("g g", …)`, `KeySequence`, the compile-checked `keyseq"…"` literal, `KeyLeader`, `TuiApp.sequenceTimeout`.
- `whichKeyPopup` for pending prefixes; modes via `KeyBindings.modes(Mode(…), …)`, `ModeId`, `keyMode`.
- Framework keys handled by `TuiApp` may be sequences (`FrameworkKeys.rebind(Map(FrameworkKeys.Quit -> keyseq"ctrl+x ctrl+c"))`); keymap files take trailing `# comments`, with `\#` for the `#` key.
- User keymaps: `BindingId`, `Keymap.parse`, `KeyBindings.rebind`, `Keymap.markdown`; `FrameworkKeys` ids via `TuiApp.frameworkKeys`. Key help is typed: `KeyHint`, `HelpSection` (`KeyBindings.continuations`, `helpSections`) and `KeymapEntry` (`FrameworkKeys.entries`).
- `.disabled(cond)`, `.hoverStyle(…)`, `.pressedStyle(…)`, `TuiApp.accessibleLabel(key)`.
- `focusGroup(children*)` (one tab stop, arrows inside), `.trapFocus`, `TuiApp.spatialNavigation` (Alt+arrows).

**Mouse, selection and drag and drop**

- Pointer capture for drags; `.onMouse(ctx => …)` with `MouseContext`; synthesised `MouseEvent.clickCount`.
- `RunnerConfig.withMouse(MouseCaptureMode)` — one mouse setting, `Off`, `Buttons` or `AllMotion`; `RunnerHandle.setMouseCaptureMode`.
- Mouse text selection over `text`, `markdown`, `log`, `logConsole` and tables, copied through OSC 52; `.selectable(…)`, framework key `copy-selection`. On a `dataTable` a press selects the row and a drag past two cells selects cell text.
- Typed drag and drop: `.draggable(payload)`, `.dropTarget[A](accept)(onDrop)`, `DragKey[A]`.
- Pointer shapes (OSC 22) with `.pointer(PointerShape.…)`.

**Editing**

- `EditBuffer` shared by `TextInputState` and `TextAreaState`: selection, word and paragraph motion, `Clipboard`, `KillRing` (`Ctrl+K`/`U`/`W`/`Y`, `Alt+D`/`Y`).
- `textArea(state, options)` with `.wrap`, `.lineNumbers`, `.lexer(…)`, `.readOnly`, incremental search (`Ctrl+F`, `F3`).
- `promptComposer(state)(onSubmit)` with `PromptComposerState`: history, reverse search, ghost suggestions, `/` and `@` completion.
- `AppServices.clipboard`; `AppServices.editExternally` / `openInEditor` through `$VISUAL` / `$EDITOR`.
- `.caretStyle(CaretStyle.…)`; `TextInput.caretAt` / `TextArea.caretAt`.

**State, hooks and async**

- `useEffect`, `useMemo`, `useTask` (returning `Loadable`) and `useArea` hooks; `memo(deps*)(subtree)`.
- `Versioned` caller-owned widget states that redraw on mutation; `ElementHost.pollStates()`.
- `TaskScope.latest(key)` latest-wins tasks; `Store[S]` with `dispatch`, `select`, undo and redo. `Async.scopeIfRunning()` answers `None` where no runner is registered.
- `Codec[A]` / `deriveCodec` (`tui-macros`), `Persisted.signal` and `persisted[A](path, initial)`.
- `signal.animateTo(target, duration, easing)` for `Signal[Double]` / `Signal[Int]`.
- `TreeState.keyed` / `TreeState.byLabelPath`; `TableRows[K]` for lazily read table rows.

**Text, Markdown and highlighting**

- `Text.fromAnsi`, the streaming `AnsiParser`, and the compile-checked `markup"[bold red]…[/]"` interpolator.
- `text(content: Text)` for pre-styled text.
- GFM tables, nested lists and task lists in `markdown`; `MarkdownOptions`, `TableOverflow`.
- `MarkdownStream` and `markdownStream(stream)` / `markdownStream(stream, scroll)` for streamed output. Inside a `scrollView`, `markdownStream(stream)` pins the view to its newest row while the view is at its end; `ScrollViewState.isAtEnd`.
- The open `Lexer` trait with `Lexer.Yaml`, `Sql`, `Markdown`, `Diff`, `TypeScript`, `Python`, `Lexer.forName`.
- `.searchable(state)` on `log`, `scrollView` and `markdown`, `searchBar(state)`, `SearchState`, `Searchable`.
- `textArea(state).searchable(search)` (and `TextArea.render(…, search)`) puts an editor on the shared `SearchState` path; `SearchState.view(key)` gives each of several views its own matches under one query.
- Multi-line lexing: `Lexer.highlightLine(line, theme, state)` with `LexState`, threaded through `highlight`, Markdown fences and `textArea` — block comments, triple-quoted strings and template literals span lines. The YAML lexer highlights flow collections, block scalars and quoted scalars that span lines.
- `AnsiParser` expands tabs to eight-column stops and honours `\r` overwrite and erase-in-line (`ESC [ K`), so progress bars show their latest state.
- `markup` accepts a named close (`[/bold]`, `[/link]`) that names the innermost open tag.
- Markdown list items wrap with a hanging indent.

**Widgets**

- `datePicker`, `timePicker`, `rangeSlider`, `colorPicker`, `hexViewer`, `treeTable`, `placeholder`, `inspect` (with `Pretty` / `derivePretty`) and `qrCode`.
- `menuBar`, `contextMenu`, `window` / `windowStack` (windows built by a caller-owned `WindowStack`), `tagInput`, `wizard`, `breadcrumb`, `toolbar`, `buttonGroup` and `accordion` (`AccordionSection`). A focused text field keeps its `Alt+B`/`D`/`F`/`Y` chords over a `menuBar` mnemonic; `F10` then the letter reaches the menu.
- `timePicker` takes typed digits into the focused field (`TimePickerState.typeDigit`); `datePicker` bounds the cursor with `DatePickerState(…, min, max)`, dims the days outside and chooses a day on click (`DatePicker.dateAt`, `Calendar.dateAt`, `DatePicker.disabledStyle`).
- `colorPicker(state, onSelect, ColorPickerMode.Rgb)`: three validated R/G/B fields beside the palette, choosing a `Color.Rgb` (`ColorPickerField`, `ColorChannel`, `ColorPickerState.chooseRgb`).
- Collapsible `inspect(value, state)` over `InspectState`: Enter/Space/click fold a node, Left/Right collapse/expand; `Inspect` also renders as a `StatefulWidget[InspectState]`.
- `toolbar(state)(buttons*)` over `ToolbarState`: the overflow marker opens the controls that did not fit in a popup; `Toolbar.hidden(width)`.
- `dataTable` selects the row under a click (`DataTable.rowAt`).
- `diffView(patch, state)` with `Patch.parse`, `Patch.of` (Myers), `DiffView`, `DiffViewState` (scrolls like `ScrollViewState`, `pageUp`/`pageDown` included).
- `diffView` expands a folded context run (Enter, a click; `DiffViewState.expandAt` / `expandFirstVisible` / `collapseAll`), scrolls line text sideways (Left/Right, Shift+wheel; `scrollLeft` / `scrollRight` / `column`) and picks each file's lexer from its extension (`DiffViewOptions.lexerFromPath`). `Patch.of(…, maxEditDistance)` caps Myers' search at `Patch.DefaultMaxEditDistance` (2 000) and marks a capped patch `approximate`.
- `TableRows.view(sort, filter)`: a provider sorts and filters at the source. `DataTableOptions(multiLine = true)`: a `\n` in a cell adds a row to its record.
- `TreeNode` gained an optional stable `id`; `TreeState.byId` keys nodes by it (falling back to the label).
- `promptComposer(…).prompt("❯ ")` draws a prompt glyph; the composer grows with its soft-wrapped rows up to `maxRows` (`TextArea.heightAt(width, state)`).
- `pixelImage(image, options)` with `RgbaImage`, `ImageLoader` and `Buffer.placeGraphic` (kitty graphics, iTerm2 inline images, half-block fallback). Pictures are encoded off the render thread (the first frame shows the fallback), and a picture a `scrollView` edge cuts is drawn cropped (`GraphicCrop`, `GraphicPayload`).
- `multiProgress(tasks, columns)` with `ProgressTask`, `ProgressColumn`, `MultiProgress`.
- `logConsole(ring)` over `LogRing`, `LogRecord`, `LogLevel` and `JulBridge`; `errorBoundary(fallback)(subtree)`.
- Effects: `CellFilter` / `Effect.filtered`, `Pattern` / `Effect.patterned`, `Progress.staggered`, `Effect.hslShift`, `paint`, `glitch`, `evolve`, `reversed`, `pingPong`, `slideIn(MotionDirection)`.
- Keyed `runEffect(key, fx)` and element-scoped `.withEffect(fx)` / `runEffect(key, fx, scope)`.
- `Effect.hslShift` and `paint` take a trailing `target: ColorTarget` (`Foreground`, `Background`, `Both`).

**Terminal, runner and output**

- `RunnerConfig.withCaptureStdio(CaptureStdio)`: `println` goes to the scrollback or a `LogRing`.
- Print mode: `Console.print` / `Console.render` / `Console.printLines`, and `RunnerConfig.withNonInteractive(NonInteractivePolicy)`.
- Inline prompts: `Prompt.text`, `password`, `confirm`, `select`, `multiSelect`, `number`, `path`, `form`, `task`, `loader`; `PromptSession`, `Prompt.answers`. A terminal that fails under a prompt falls back to plain lines rather than throwing.
- Desktop notifications `Notifications.desktop`, taskbar progress `AppServices.taskbarProgress`, in-band resize (mode 2048), grapheme widths (mode 2027).
- `Backend.keepInlineRows(n)`; `StandardOutput.isTerminal` / `isInputTerminal` / `width`.
- `TerminalCapabilities.terminalVersion` (XTVERSION), `kittyGraphics`, `inlineImages`; `ImageProtocol.select`.
- `TaskbarState` (`Error`, `Indeterminate`, `Paused`, …) with `AppServices.taskbarState(signal)`, `Backend.setTaskbarState`, `RunnerHandle.setTaskbarState`.
- `NonInteractivePolicy.awaitIdle(timeout)`: print mode waits for queued `Async` work, timers and ticks to settle before printing.
- `logConsole(ring, state)` with `LogConsoleState`: scroll back, detach from the tail, `toggleFollow()`; `errorBoundary(fallback, ring)(subtree)` logs into a `LogRing`.
- `Codec` version tags with `codec.versioned(n) { case old => Codec.readAs[Old](upgrade) }` migrations.
- `Theme.isAuto` / `Theme.resolve(scheme)`; `ProgressTask(…, rateWindow)`; `input(state).masked()`, `TextInput(mask = …)`, `Prompt.password(label, mask = Some("•"))`.
- `AsciicastRecorder.flush()`; `HeadlessBackend.printAboveCalls`, `taskbarStateRequests`, `currentTaskbarState`.

**Testing and tooling**

- `TuiApp.boundsOf(key)` / `ElementHost.boundsOf`, `Pilot.click(rect)` / `Pilot.hover(rect)`; `NodeBounds`; `ElementHost.enableBoundsRecording()` (keyed nodes, containers, input targets, pointer roles) and `enableFullBoundsRecording()` (every node) for an embedding host.
- `ElementTreeSnapshot.render(app)` and `Element.debugName`.
- `BufferExport.svg` / `html` / `ansi` with `ExportOptions`; styled goldens `GoldenFrames.assertMatchesStyled` / `Pilot.assertGoldenStyled`.
- `BufferExport.ansiLines(buffer, colors)` (print-mode text) and `ExportOptions.ansiColors`; core `AnsiColors` and `ColorDepth.ansiColors` — the one SGR colour policy the terminal, print mode and the exports share.
- Asciicast recording via `RunnerConfig.withRecording(path)`, `-Dglyphora.record=…` or `AsciicastRecorder`; `Tape.parse` / `Tape.run`.
- Inspector overlay (F12, framework key `inspector`); `GLYPHORA_DEBUG_LAYOUT=1` debug borders; `examples/doctor`.
- `pilot.a11yReport(app)` accessibility lint (`A11yFinding`, `A11yRule`); its focus-cue exemption follows the node's own `selectionIsFocusCue`.
- `AppServices.screenLabels`: the screen trail for a `breadcrumb` drawn by a helper outside the app.
- `FormState.disable(name)` / `enable` / `setDisabled` / `disabled`: disabled form fields, announced by `Form.accessible`.
- `Theme.withDisabled(style)` and a theme file's `disabled = …`: the disabled role is stored, falling back to `muted.dim`.
- `Esc` cancels a drag and drop in flight; Shift+arrows select text on a focused selectable node; a drag-selection past a `scrollView`'s edge scrolls it.
- `TuiApp.inspector = false` and `FrameworkKeys.unbind(ids*)` (`KeySequence.unbound`) turn the inspector — or any framework key — off.
- JDK Flight Recorder events `FrameDrawn`, `EventRouted`, `FocusChanged`, `AsyncAdmitted`, `AsyncRejected` (`JfrEvents`).
- Environment overrides `GLYPHORA_COLOR_DEPTH`, `GLYPHORA_KEYBOARD`, `GLYPHORA_MOUSE`, `GLYPHORA_ESCAPE_DELAY`.

**Localisation, gallery and quickstart**

- `Messages` — one table for every built-in label (dialog and confirm buttons, palette title and placeholder, help title, wizard buttons, inspector titles, `searchBar` placeholder, prompt hints, glyphs and yes/no); `TuiApp.messages`, `Messages.Default`, `with…` builders, `Messages.scoped`.
- `Messages` also covers the which-key ellipsis, framework-key descriptions (`withFrameworkKeyDescription`), prompt validation errors (templates with `{input}`, `{min}`, `{max}`, …) and severity icons and tags (`withIcon(level, …)`, `withTag(level, …)`); `Badge.of(…, label)`.
- `examples/gallery`: every exported factory on its own page, grouped by family, with one golden frame per factory; `GLYPHORA_GALLERY_EXPORT` and `scripts/render-gallery.sh` write each page as an SVG screenshot. A generic Props panel (`Prop.Number`/`Toggle`/`Choice`) edits the simple arguments of the `gauge`, `sparkline`, `barChart`, `progressBar`, `spinner` and `badge` pages live.
- `errorBoundary` logs a repeated failure at `SEVERE` once, then at `FINE`.
- `examples/scala-cli/hello.scala`: a single-file app run with `scala run`, no build tool.

### Changed

- `TuiApp.theme` defaults to `Theme.auto` instead of `Theme.Dark`.
- `TuiApp` captures `System.out` / `System.err` into the scrollback by default.
- `scrollView` scrolls the focused child into view.
- `dropdown` floats its list over the frame instead of reflowing its siblings; `.passThroughOutside` lets the click that closes it also reach the control beneath.
- `button` measures its own width, so `flow` and `.auto` lay buttons out by label.
- `withStyle` is a paint-time cascade (`StyleScopeElement`); elements built outside a view paint in the app's theme.
- Widget states redraw on mutation; a `requestRedraw()` after a state write is no longer needed.
- `markdown` renders GFM tables, nested lists, task lists and more highlighted fence languages.
- `ColorDepth.detect` honours `FORCE_COLOR`.
- Frames make two tree walks instead of six; key routing follows the recorded focus path, and mouse routing each input target's recorded path.
- A `lazyList` inside a `scrollView` shows the rows a jump scrolled to on the same frame (no blank frame); the container-query pre-pass paints only the way to the `responsive.local` nodes, so other widgets render once per frame.
- `ListView` costs the viewport, not the list; `DataTable` no longer scans for its selected key every frame.
- A mouse-capturing `TuiApp` turns on all-motion tracking when a frame draws a hover or pressed cue or `.pointer`; hovered and pressed marks follow a keyed control across a reshaped tree.
- Searched views cache their matches by content and query: a searched `scrollView` paints its whole content once per query instead of every frame, and a searched `log` re-matches only when its lines change.
- A soft-wrapped `textArea` moves Up/Down by wrapped row and re-wraps only the lines an edit changed.
- Every `scrollView` around a focused or `.ensureVisible` child scrolls to it, nested ones included.
- `terminal.Sgr`, `BufferExport.ansi` and `FrameEncoder.encodeLines` write through one SGR codec (`core.SgrCodec`); their bytes are unchanged.
- Captured `println` under `CaptureStdio.PrintAbove` reaches the scrollback in one trip per frame, not one per line.
- Asciicast recording encodes and writes on a background writer thread, flushed before `suspend` and on every exit.
- `ProgressTask` rate and ETA follow a ten-second sliding window (`rateWindow = None` for the old average).
- `Theme.auto.withX(…)` stays automatic: every builder applies to both the light and the dark variant.
- A mode 2048 in-band resize report fills the window-size pixel cache.
- `Effect.patterned` caches each progress step's evaluation while the content is unchanged: a radial reveal over 120x40 costs about a fifth of what it did (`EffectPatternBench`).
- `ScrollWindow.offsetForItems` reads a multi-row `ListView`'s heights in place, bounded by the viewport.
- `log` draws each line at its own `Line.alignment`, and its search highlight follows.

### Fixed

- `Pilot`'s default wait is 10 s (was 2 s): a cold test JVM's first frame on a loaded machine crossed 2 s and failed healthy tests with "did not go idle". `PromptComposerSpec` waits on the last query's completions rather than any popup naming "albert".

- Focused DSL text fields now move the terminal's hardware cursor, so screen readers and input-method popups follow the caret.
- `dropdown` no longer reflows its siblings or clips its list near the bottom edge.
- A click on a click-outside dialog's own border, title or caption no longer dismisses it.
- `ListView` no longer does O(n) work per frame, restoring its row virtualisation.
- `TextArea` undo no longer snapshots the whole document per keystroke; typing a word is one undo step.
- `each` scopes hooks under the item key, so focus and hook state share one identity.
- A `scrollView` that scrolls to reveal a child no longer paints its content twice on that frame, so `boundsOf` and the other recorded rects are never one frame stale.
- A mouse selection stays on its node when nodes are added elsewhere in the tree, and moves with the content when a `scrollView` scrolls.
- `scrollbar(…)` and `notice(…)` can be placed in a `grid` with `.at(row, col)`.
- One resize on a mode 2048 terminal (SIGWINCH plus the in-band report) delivers one `Event.Resize`, not two.
- `keepInlineRows` erases the rows below the kept ones on close.
- `CaptureStdio.PrintAbove` lines still queued when the loop closes reach the original stream instead of being lost.
- `hexViewer` pads the ASCII column of a short final row, so its closing bar lines up.
- `Patch.of` over two very different large texts no longer exhausts the heap.

### Breaking

Each item has a before/after migration note in
[Migrating to 0.20.0](website/docs/versioning.md#migrating-to-0200).

- `panel` style builders style the content; borders move to `.frameStyle(…)`.
- `Constraint` gained `Auto` and `Bounded`; `.minSize` / `.maxSize` compose with a size.
- `KeyBinding.triggers` is `Seq[KeySequence]`; a sequence prefix that is also a binding throws.
- Focused text fields consume editing chords; in `textArea`, `Ctrl+Y` yanks and redo is `Ctrl+Shift+Z`.
- `each` hook state starts fresh once; `ListState`, `MenuState`, `DropdownState` lose `new`; `TreeState` takes a type parameter.
- `MouseEvent` gained `clickCount`; consumed presses capture the pointer.
- `TerminalCapabilities` and `FrameworkKeys` gained fields (positional patterns break); `Event` gained `ColorSchemeChanged`.
- `FrameworkKeys` fields and `KeymapEntry.key` are `KeySequence` (were `KeyEvent`); a keymap's `#` after a space starts a comment (`\#` is the key).
- `AppServices`, `Notifications` and `RunnerHandle` gained abstract members.
- `Console` under the one import is glyphora's print-mode object.
- `Language` is an alias for the open `Lexer` trait; `responsive` is an object; `PanelElement` field `children` → `content`.
- `TextAreaOptions` field order changed (`syntax` is fourth); `Theme.apply` gained `components`.
- `scrollbar(…).at(offset)` → `.thumbAt(offset)`, `notice(…).at(time)` → `.stamped(time)`, so `.at(row, col)` places both in a `grid`.
- Soft-wrapped `textArea` Up/Down move by wrapped row; a selection follows its node, not the screen; `widgets.ScrollView` and `widgets.Searchable` gained a `contentIdentity` parameter after `showScrollbar` / `content`; `ExportOptions` gained `ansiColors`.

- `Prompt.form` goes back a field with Shift+Tab (`focus-previous`); Ctrl-U kills to the line start in its text fields.
- `TuiApp` gained an overridable `messages` member; an app that declares its own `messages` needs `override` or a rename.
- `Dialog` widens its box to fit its buttons, so a button row wider than the message is no longer dropped.
- An unfocused `confirmDialog` (and the new pickers) underlines its selection instead of painting the focus cue, so focus is visible.
- `dialog(…)` returns `DialogElement` (was `WidgetElement`); `ConfirmDialogElement` keeps its theme style in `bodyStyle`.
- `widget(…)` leaves no longer ignore `.fg`/`.bg`/`.style`/`withStyle`; search highlights take theme colours (Dark's current match is red, not bright red).
- `Messages` gained trailing fields (positional construction past `searchPlaceholder` breaks).
- `AnsiParser` / `Text.fromAnsi` expand tabs and apply `\r` as an overwrite instead of dropping both; `log` honours `Line.alignment`.
- `DiffViewOptions` gained `lexerFromPath` (after `lexer`, on by default) and `approximateNotice`; `Patch` gained `approximate`; a focused `diffView` consumes Left/Right/Enter and a click; `DataTableOptions` gained `multiLine` after `alignments`; `TreeNode` gained `id`; `TextAreaElement` and `PromptComposerElement` gained trailing fields; the YAML lexer reads a block scalar's lines as one string.
- `RunnerConfig.mouseCapture: Boolean` is now `mouse: MouseCaptureMode` (`MouseCaptureMode` gained `Off`); `RunnerConfig(mouseCapture = true)` becomes `RunnerConfig(mouse = MouseCaptureMode.Buttons)`. `withMouseCapture`, `withoutMouseCapture`, a derived `mouseCapture` and the positional Boolean arities still work.
- `Codec` text gains a leading `@<version>;` tag (untagged files still decode); `NonInteractivePolicy` gained `AwaitingIdle`; `AppServices` gained `taskbarState`.
