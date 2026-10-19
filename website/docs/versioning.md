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
def mvnDeps = Seq(mvn"com.worxbend::tui-dsl:0.20.0")
```

```scala
// sbt
libraryDependencies += "com.worxbend" %% "tui-dsl" % "0.20.0"
```

Applications normally need only `tui-dsl`. Lower-tier artifacts are `tui-core`,
`tui-terminal`, `tui-widgets`, `tui-runtime`, and `tui-macros`; `tui-test` carries the
headless test harness and belongs in the test configuration only.

Version `0.20.0` is published to Maven Central under `com.worxbend` by the `v0.20.0`
tag (see [Release process](#release-process)); Central keeps every earlier release, so a
build pinned to `0.19.0` keeps resolving.
Downloads require no credentials, and Mill and sbt use Central by default. GitHub
Packages also hosts the libraries; its resolver requires a classic PAT with
`read:packages`.
See [Getting started](./getting-started#1-add-glyphora) for both options.

GitHub Packages releases through `v0.18.1` used `io.worxbend`. Update dependency
coordinates to `com.worxbend` when moving to `0.18.3`; Scala imports use `io.worxbend.tui`.

## Migrating to 0.20.0

0.20.0 is a large minor release: a layout engine, component theming, key sequences, a shared
editing core, mouse selection and drag and drop, the modern terminal protocols, print mode and
two batches of widgets. Most of it is additive. The changes below are the ones that can stop a
build or change what an existing app does; each says what changed, why, and what to write
instead. The full list of additions is in the
[changelog](https://github.com/worxbend/glyphora/blob/main/CHANGELOG.md).

Three changes reach almost every app, so check them first:

- a `panel`'s style builders now style its content, and the border is `.frameStyle(…)`
  ([Theming](#theming-style-and-framestyle));
- `TuiApp.theme` defaults to `Theme.auto`, which picks `Theme.Light` on a light terminal
  ([Terminal protocols](#terminal-protocols-and-themeauto));
- `TuiApp` captures `System.out`/`System.err` while it runs, so `println` goes to the scrollback
  above the UI instead of over the frame ([Runner configuration](#runner-configuration-and-stdio-capture)).

### Layout

- **`Constraint` has two new cases, `Auto` and `Bounded(min, max, base)`.** `Auto` sizes a segment
  to what its content measures (a `Fill(1)` when the content cannot say); `Bounded` clamps a base
  constraint. `Constraint` is a sealed enum, so under `-Werror` an exhaustive `match` without a
  wildcard stops compiling. Add the cases, or a wildcard so the next case does not break the
  match again — the enum's Scaladoc now says it grows:

  ```scala
  // before
  constraint match
    case Constraint.Length(n) => n
    case Constraint.Fill(_) | Constraint.Min(_) | Constraint.Max(_) |
        Constraint.Percentage(_) | Constraint.Ratio(_, _) => 0

  // after
  constraint match
    case Constraint.Length(n) => n
    case _                    => 0
  ```

- **`.minSize` / `.maxSize` compose with a size instead of replacing it.** Before, the last
  constraint builder called won, so `.minSize(8).fill` was a plain fill. Now a size and a bound
  combine in either order into `Constraint.Bounded`, because "fill, but at least 20 and at most
  60" could not be written at all. `.minSize(n)` and `.maxSize(n)` on their own are still
  `Min(n)` / `Max(n)`.

  ```scala
  // before: both meant a plain fill
  pane.minSize(8).fill
  // after: the first is a fill floored at 8; drop the bound for the old meaning
  pane.fill
  ```

- **`scrollView` scrolls to the focused child.** When focus moves (Tab, Shift+Tab, `focusTo`)
  onto a child outside the viewport, the view scrolls just far enough to show it, on the same
  frame and once per focus move. Apps used to redo the `scrollTo(row)` arithmetic themselves. An
  app that drives the offset itself turns it off:

  ```scala
  scrollView(content, state).followFocus(false)
  ```

- **`button(…)` measures its own width** (`Button` is `Measured`). A `flow` now lays buttons side
  by side instead of one per line, and `.auto` sizes a button to its label. To keep a button on a
  line of its own, put it in its own `column(…)` or give it `.fill`.

  ```scala
  // before: four lines; after: one wrapped row
  flow()(button("New")(n()), button("Open")(o()), button("Save")(s()), button("Quit")(q()))
  ```

- **`dropdown` floats its list instead of reflowing its siblings.** The node claims one row
  whether open or closed, and the list is drawn at the frame root like `portal` content — below
  the row when there is room, above it when there is not, never clipped by the enclosing panel.
  Before, an open dropdown claimed `1 + maxVisibleRows + 2` rows and pushed everything below it
  down, and one near the bottom of a short area got a clipped list (research bug B2). A click
  outside an open list now closes it *and is spent on that* — the control under the pointer does
  not also get the click — and Tab closes the list on its way to the next control. Nothing to
  change unless a test pinned the reflowed positions.
- **`responsive` is an object.** `responsive(build)` and `responsive { size => … }` compile
  unchanged; the new `responsive.local { size => … }` is the container-query form. Only code that
  passed `responsive` itself as a function value has to name `apply`:

  ```scala
  // before
  val wrap: (Size => Element) => Element = responsive
  // after
  val wrap: (Size => Element) => Element = responsive.apply
  ```

- **Panel slots.** `panel(…).header(e)` / `.footer(e)` put an element in a border row, and the
  slots are ordinary children for focus, hit-testing and theming — so `PanelElement.children` is
  now `content ++ header ++ footer`, and the node's constructor field for the stacked children is
  renamed `children` → `content`. The factories are unchanged; code that built a node by hand
  moves to the new name, or better, to the factory:

  ```scala
  // before
  PanelElement(Some("t"), children = Seq(text("x")))
  // after
  panel("t")(text("x"))
  ```

  Not breaking, but the spelling changes: a `panel(…).borderless` that existed only for its
  padding is now the padding itself — `form.padding(1)`, `.padding(Padding.symmetric(2, 1))`, or
  `.padding(1, 2)` (rows, then columns) on any element.
- Smaller: `Layout.apply` gained a `crossAlign` parameter, with the 0.19.0 five-argument arity
  kept as a default-free overload; `Layout.toString` adds `, crossAlign=…` only when it is not
  `Stretch`. `widgets.LayoutItem` gained `align` and `Row`/`Column` gained `crossAlign`, appended
  with defaults, so their extractors have one more field. `widgets.Column` is `Measured`. `text`
  (clipped), `line` and `badge` now claim their size through `Measured` (`Constraint.Auto`); the
  layout they get is identical.

### Theming, style and `frameStyle`

- **`style` means the content style on every node; a panel's border is `.frameStyle(…)`.** On a
  `panel` the style builders (`.fg`, `.bg`, `.bold`, `.dim`, `.styled(…)`) used to recolour the
  border; `style` meant the border on `panel`, the text on `text`, the body on a list and nothing
  on `widget(…)`. Now they style the content, layered under the children the way `withStyle`
  does, so a child's own colour still wins. The border has its own field, set with
  `.frameStyle(style)` (replace) or `.frameStyle(transform)` (edit the theme's `border`); without a
  `titleStyle`, captions follow the frame style. A call left as it was still compiles, and now
  colours the content — so search for style builders on panels:

  ```scala
  // before (0.19)
  panel("Danger zone")(body).doubleBorder.fg(Color.Red)
  panel(metric.label)(rows*).styled(_.patch(band.style))

  // after (0.20)
  panel("Danger zone")(body).doubleBorder.frameStyle(_.withFg(Color.Red))
  panel(metric.label)(rows*).frameStyle(_.patch(band.style))
  ```

  Code that read `panelElement.props.style` to get the border reads `panelElement.frameStyle`.
  New container nodes follow the same split — `window(title, state)(content)` included: its border
  is `.frameStyle(…)`, the theme's `border` by default and bold while focus is inside it.
- **`withStyle(transform)(subtree)` returns one scope node.** It used to return `subtree` rebuilt
  with the style patched into every node's props, on every frame (research G3). It now returns a
  `StyleScopeElement` wrapping `subtree` unchanged, and the style reaches the descendants at
  paint. What is drawn is the same. Only code that inspected the result changes:
  `withStyle(…)(row(a, b)).children` is `Seq(row(a, b))`, and the children's `props.style` no
  longer carry the inherited style. Test what is painted, through `ElementHost` or `Pilot`.
- **Elements built outside a view paint in the app's theme.** A themed factory (`panel`, `rule`,
  `gauge`, `progressBar`, the spinners and skeletons, `notice`, `badge`, `dialog`, `markdown`,
  `logConsole`, …) called where only `Theme.default` is in scope — a top-level `def`, a helper
  object — used to paint dark-theme colours even in a `Theme.Light` app (research G2). It is now
  re-resolved against the app's theme when painted, keeping any builders applied afterwards. A
  helper that *wanted* the dark palette passes it: `panel("x")(body)(using Theme.Dark)`.
  `statusBar`, `topBar` and `helpOverlay` are not late-bound.
- **`given Theme.default` is its own instance**, `==` to `Theme.Dark` but not `eq`, so late binding
  can tell "no theme in scope" from an explicit `using Theme.Dark`. Replace
  `summon[Theme] eq Theme.Dark` with `==`.
- **`Theme` carries component styles.** `Theme.apply` takes `components` as a new last parameter
  (default `Map.empty`), and every preset now carries `button.primary` and `button.danger`
  components. A theme rebuilt field by field from a preset loses those unless it passes them on;
  prefer the `with*` builders:

  ```scala
  // before
  Theme(name = "mine", primary = p, /* … */ syntax = Theme.Dark.syntax)
  // after
  Theme(name = "mine", primary = p, /* … */ syntax = Theme.Dark.syntax, components = Theme.Dark.components)
  // or
  Theme.Dark.withName("mine").withPrimary(p)
  ```

  With no `.variant(…)` nothing renders differently: no preset styles the default `button`,
  `input`, `list` or `table.header` roles. `Theme.Light` and `Theme.HighContrast` gain values of
  their own for the new `MarkdownTheme` and `SyntaxTheme` fields.

### Keys and bindings

- **`KeyBinding.triggers` is `Seq[KeySequence]`**, where a single key is a one-step sequence, so
  `"g g"` and `"ctrl+k ctrl+s"` share one dispatch path with single keys. `KeyBinding` also gains
  two trailing defaulted fields, `explicitId` and `mode`. Code that goes through the `binding(…)`
  factories is unaffected; code that builds a `KeyBinding` or reads its triggers wraps or unwraps
  them:

  ```scala
  // before
  KeyBinding(Seq(KeyEvent.char('s')), "s", "save", () => save())
  binding.triggers.contains(event)
  // after
  KeyBinding(Seq(KeySequence.of(KeyEvent.char('s'))), "s", "save", () => save())
  binding.triggers.exists(_.keys == Vector(event))
  ```

- **A prefix that is also a binding throws at construction.** `KeyBindings(…)` and
  `KeyBindings.modes(…)` throw `IllegalArgumentException`, naming both, when one sequence is a
  strict prefix of another in the same mode. Only sequences can do that — except a plain
  space-bar binding next to a `<leader>` binding under the default (space) leader. Choose another
  leader before the bindings: `given KeyLeader = KeyLeader(",")`.
- **Framework keys are rebindable ids.** Tab, Shift+Tab, Ctrl+P, Ctrl+C and Esc are `FrameworkKeys`
  fields with the ids `focus-next`, `focus-previous`, `command-palette`, `quit` and `close-modal`,
  read from the overridable `TuiApp.frameworkKeys`; the PageUp/PageDown step (a hard-coded 10) is
  `FrameworkKeys.pageStep`. Defaults and matching are unchanged, so nothing behaves differently
  unless the app overrides `frameworkKeys`:

  ```scala
  override def frameworkKeys: FrameworkKeys =
    FrameworkKeys.default.rebind(Keymap.parse(userFile).getOrElse(Map.empty)).copy(pageStep = 20)
  ```

- **New framework keys that act where they used to do nothing.** F12 (`inspector`) toggles the
  inspector overlay; Ctrl+Shift+C (`copy-selection`) copies a mouse selection while one shows —
  before, it matched `quit` (Ctrl+C plus any modifiers) and quit, which it still does with nothing
  selected; F10 (`menu-bar`), Alt+M (`window-move`) and Alt+R (`window-resize`) act only when the
  view contains a `menuBar` or a `window`. Bindings and focused elements still see each key first.
  Move one with a user keymap:

  ```scala
  override def frameworkKeys: FrameworkKeys =
    FrameworkKeys.default.rebind(Map(FrameworkKeys.Inspector -> keyseq"ctrl+alt+i"))
  ```

  `FrameworkKeys` gained these six fields at the end, all defaulted, so positional construction
  and `copy` keep compiling; a positional pattern over `FrameworkKeys(…)` needs more binders.
  `FrameworkKeys.entries`, and so `Keymap.markdown(bindings, frameworkKeys)`, lists more rows.
- **Keys taken ahead of the focused control.** A `menuBar` takes `Alt+<mnemonic>` (and the arrows,
  Enter and Esc while a menu is open) before the focused control sees it, and a `window` takes
  Alt+M/Alt+R while focus is inside it. The one exception is a focused text field's own
  Alt+B/Alt+D/Alt+F/Alt+Y chords, which stay with the field while no menu is open; F10 then the
  mnemonic letter reaches that menu from inside the field (see
  [Alt keys: menu bar or text field](./mouse#alt-keys-menu-bar-or-text-field)). Apps without these
  nodes are unaffected.
- **Focused text fields consume the editing chords** — see [Editing](#editing); an app binding on
  Ctrl+U, Ctrl+W, Ctrl+Z, Ctrl+Y, Ctrl+K or Ctrl+A no longer fires while an `input` or `textArea`
  has focus.
- **`Prompt.form` steps back with Shift+Tab, not Ctrl-U.** Ctrl-U was the form's "previous field"
  key, which took kill-to-line-start away from every text field in a form. The back key is now the
  `focus-previous` framework key (Shift+Tab by default), and Ctrl-U kills to the start of the line
  as it does in any `input`. Nothing to change in code; tell users, or move the key:

  ```text
  # before: Ctrl-U  -> previous field
  # after:  Shift+Tab -> previous field, Ctrl-U -> kill to line start
  ```

- **`statusBar(bindings)`** leads with `[mode]` when the bindings declare modes and shows a pending
  prefix with its continuations; the command palette lists only the live mode's bindings. Apps
  without sequences or modes render exactly as before.

### Editing

- **Undo is coalesced and diff-based** (research bug B5). `TextAreaState` — and now
  `TextInputState` — store diffs instead of a whole-document snapshot per edit, and consecutive
  single-grapheme inserts join one undo step, as do consecutive backspaces and forward deletes. A
  space after a word, any caret move, a selection change, undo or redo starts a new step; the
  history keeps the newest 100 steps. Snapshotting cost O(document) memory per keystroke and made
  Ctrl+Z undo one character at a time.

  ```scala
  // before: three undo steps
  state.insert("a"); state.insert("b"); state.insert("c")
  state.undo() // "ab"

  // after: one step — call breakUndoGroup() between edits to keep them apart
  state.undo() // ""
  ```

  `TextInputState` gains `undo()` / `redo()`; `clear()` on either state also forgets the history.
- **In a focused `textArea`, Ctrl+Y yanks and redo is Ctrl+Shift+Z.** Ctrl+Y used to redo; it now
  yanks the newest kill-ring entry, as in `input` and readline, because the kill ring needs it.
- **Focused `input` and `textArea` consume more chords:** Ctrl+A, Ctrl+V, Ctrl+K, Ctrl+U, Ctrl+W,
  Ctrl+Y, Ctrl+Z, Ctrl+arrows, Alt+B/F/D/Y, Alt+Backspace, Shift+motion, and Ctrl+C / Ctrl+X while
  text is selected; `textArea` also takes Ctrl+F and F3. Move an app binding on one of these to
  another chord, or bind it on an ancestor's `onKeyEvent`. Ctrl+C with nothing selected still
  bubbles, so it still quits.
- **`TextInputState` and `TextAreaState` extend the new `EditBuffer`.** Every public method keeps its
  name and meaning. The motion methods gained a defaulted `select: Boolean = false`, so
  `state.moveLeft()` compiles but passing `state.moveLeft` as a `() => Unit` value needs
  `() => state.moveLeft()`. Both constructors take an optional `KillRing`.
- **`TextAreaOptions` grew fields**, and `syntax` is now the fourth, so a positional
  `TextAreaOptions(Some(lexer), theme)` must name it:
  `TextAreaOptions(lexer = Some(lexer), syntax = theme)`. `TextAreaElement` gained `options` and
  `editable` fields; use `textArea(state, options)` and `.readOnly`.
- **The terminal's own cursor follows the focused text field.** `input`, `numberInput`,
  `templateInput`, `autocomplete`, `textArea` and the command palette's filter report their caret,
  and the frame parks the hardware cursor there; it hides when focus leaves text. Before, the DSL
  painted a reverse-video cell and left the terminal cursor hidden — invisible to screen readers
  and to input-method popups (research bug B1). The default is `CaretStyle.Both`; keep the old look
  with `.caretStyle(CaretStyle.Painted)`:

  ```scala
  // before: painted cell only, implicitly
  input(name)
  // after: both by default; pick one explicitly
  input(name).caretStyle(CaretStyle.Painted)
  ```

  A `Pilot` test that asserted `assertNoCursor()` while a text field had focus now fails; assert
  `assertCursorAt(x, y)` instead, or set `CaretStyle.Painted` on the field under test.
- **`TextAreaState` search normalises to NFC**, so a precomposed `é` in the query matches `e` plus a
  combining acute in the text, which used to find nothing. Smartcase is unchanged.

### State and hooks

- **`each` scopes its rows' hooks under the item key** (research bug B6).
  `each(items)(key)(render)` runs `render` inside `keyed(key)` as well as stamping `.key(key)`, so
  focus and `useSignal`/`useState` share one identity and follow the item through reorders. Hook
  state declared directly inside an `each` row (with no `keyed` of its own) therefore moves from a
  positional slot to the key's and starts fresh once on upgrade. A row that was already wrapped in
  `keyed(sameKey)` keeps its state: a `keyed` opened as the first thing inside a scope of the same
  key *is* that scope, not a second one under it, so the path stays `sameKey`. The inner `keyed`
  can go:

  ```scala
  // before
  column(items.get.map(item => keyed(item.id)(row(item)).key(item.id))*)
  // after
  column(each(items.get)(_.id)(row)*)
  ```

- **Widget states schedule their own frames.** Every caller-owned widget state (`ListState`,
  `TreeState`, `ScrollViewState`, `DataTableState`, `LogState`, `TextInputState`,
  `TextAreaState`, the new picker states, `MarkdownStream`, …) is `Versioned`: each mutation
  raises `version`, and a `TuiApp` redraws when a painted state's version moves. A render-thread
  write outside an event handler — an `Async` continuation — is drawn without a `Signal` write or
  `requestRedraw()` (research E4); an app may see a frame where it used to see none. A redraw
  request that only followed a widget-state write can go:

  ```scala
  // before
  Async.run(fetchLines()) { lines => lines.foreach(log.append); requestRedraw() }
  // after
  Async.run(fetchLines())(lines => lines.foreach(log.append))
  ```

  An `ElementHost` that owns its loop calls `host.pollStates()` once per iteration for the same.
  A new caller-owned state class must extend `Versioned` and bump its version on every mutation.
- **`ListState`, `MenuState` and `DropdownState` are built through `apply`.** Their constructor
  `var`s became getter/setter pairs that count writes, so the primary constructors are private.
  `ListState(selected = Some(0))`, `state.selected = …` and `state.offset += 1` compile
  unchanged; only `new` breaks:

  ```scala
  // before
  val list = new ListState(selected = Some(0))
  // after
  val list = ListState(selected = Some(0))
  ```

- **`TreeState` takes a type parameter**, so a state can be keyed by node identity
  (`TreeState.keyed(_.label)`, `TreeState.byLabelPath`) and keep expansion and selection across a
  reordering refresh. `TreeState()` still infers the positional `TreeState[Seq[Int]]`; only an
  explicit annotation changes:

  ```scala
  // before
  def helper(state: TreeState): Unit
  // after
  def helper(state: TreeState[Seq[Int]]): Unit // or TreeState[?] to only render or navigate
  ```

  `TreeState.toggle` now ignores a selection that names no visible row; it used to toggle a
  hidden node inside a collapsed branch.
- **`ListView` remembers its item layout on the `ListState`**, once per `items` reference, so a
  frame no longer walks every item (research bug B4). A `Seq` mutated in place under the same
  reference is re-measured when its count changes or a drawn item's height disagrees; an
  off-screen item that changed height is noticed when it scrolls in. Replace the sequence, as a
  `Signal[Vector[…]]` does, rather than mutating it.
- **`DataTable` checks the previous row first** for the selected key. With duplicate keys — which
  `KeyedRow` already forbids — a selection on the second duplicate now stays there.
- **The hook-outside-a-view error** now names every hook (`useSignal`, `useState`, `useEffect`,
  `useMemo`, `useTask`, `useArea`); a test matching the old message prefix needs updating.

### Mouse and selection

- **`MouseEvent` has a fifth field, `clickCount: Int = 1`.** Terminals never report a double
  click, so the DSL host synthesises one: a `Down` with the same button on the same cell within
  400 ms carries `clickCount = 2`, then 3. Construction is unaffected; a four-field positional
  pattern stops compiling:

  ```scala
  // before
  case MouseEvent(Position(x, y), MouseEventKind.Down, _, MouseButton.Left) => …
  // after
  case MouseEvent(Position(x, y), MouseEventKind.Down, _, MouseButton.Left, _) => …
  ```

- **A consumed mouse-down captures the pointer.** Every `Drag` and the `Up` until release go to the
  pressed element, with its press-time area, even after the pointer has left it — so a slider or
  split divider keeps following a drag past its edge and an `onDragEnd` fires for a release
  outside. Handlers that assumed a `Drag` lies inside their area should clamp. A press nobody
  consumed captures nothing.
- **Prefer `.onMouse(ctx => …)` to absolute coordinates.** It hands the handler `ctx.local`,
  relative to the element's top-left, and `ctx.area`. `.onMouseEvent`, `onClickAt`, `onHover` and
  `onDrag` stay and run first.

  ```scala
  // before
  canvas(w, h)(draw).onMouseEvent { e => plot(Position(e.position.x - knownX, e.position.y - knownY)); true }
  // after
  canvas(w, h)(draw).onMouse { ctx => plot(ctx.local); true }
  ```

- **A mouse-capturing `TuiApp` may switch to all-motion tracking by itself**: the first frame that
  draws a `.hoverStyle(…)` or an explicit `.pointer(shape)` asks the terminal for every pointer
  movement (more input traffic over ssh). Apps with mouse capture off never switch; to stay on
  button-only tracking, use neither. `ElementHost.dispatchMouse` may now answer `true` for an
  unconsumed event when a hover or pressed cue needs a repaint.
- **Mouse-capturing apps select text.** With `RunnerConfig(mouse = MouseCaptureMode.Buttons)`, a left drag over
  `text`, `markdown`, `log`, `logConsole` or a table selects text and copies it through OSC 52 on
  release, because capturing the mouse took the terminal's own selection away. A press an element
  consumes (a button, a list row) never starts one. Opt a subtree out:

  ```scala
  // before: a drag over the log did nothing
  logConsole(ring)
  // after, to keep that
  logConsole(ring).selectable(false)
  ```

- **Press and motion on a `.draggable` element is a drag.** Its `Drag`/`Up` events go to the drag
  gesture, not the element's `onDrag`/`onDragEnd`; a press and release without motion is still a
  click.
- **A click on a click-outside dialog's own border stays inside it** (research bug B3). A modal
  pushed with `Dismissal.ClickOutside` or `EscapeOrClickOutside`, and any `dismissibleOverlay`,
  used to treat a press on the dialog's border, title or caption as outside. Now a press on any
  node of the dialog that draws something is absorbed, and only a press on layout-only nodes
  dismisses. A dialog that relied on a border press closing it has no replacement.
  `dismissibleOverlay(content)(onOutsidePress)` now returns its own node, whose single child is
  `content`, instead of `layers(backdrop, content)`; a test that destructured it as a
  `LayersElement` reads `children` instead:

  ```scala
  // after
  assert(overlay.children == Seq(dialog))
  ```

- Bounds recording is on for every headless run, and on a real terminal from the first
  `boundsOf(key)` call or click-outside modal; it does not change what a frame shows. Nodes with a
  pointer role (selectable text, draggable, drop target) are recorded on every frame.

### Terminal protocols and `Theme.auto`

- **`TuiApp.theme` defaults to `Theme.auto`, not `Theme.Dark`.** `Theme.auto` resolves on every frame
  to `Theme.Light` on a terminal that reported a light background (OSC 11 at start-up, or a mode
  2031 report since) and to `Theme.Dark` otherwise — including every terminal that cannot say,
  which is what every app got before. An app that overrides `theme` sees no change; to keep the
  dark palette on light terminals, say so:

  ```scala
  // before: nothing — Dark was the default
  // after
  override def theme: Theme = Theme.Dark
  ```

  Headless tests are unaffected: `HeadlessBackend` reports no colour unless a test installs one
  with `backend.capabilitiesTo(TerminalCapabilities(backgroundColor = Some(Color.Rgb(255, 255, 255))))`.
- **`Event` gained `ColorSchemeChanged(scheme)`.** An exhaustive `match` over `Event` without a
  wildcard needs a new arm; `TuiApp` users never match `Event`.
- **`TerminalCapabilities` gained eleven defaulted fields** after `focusReporting` —
  `backgroundColor`, `reportedColorScheme`, `colorSchemeUpdates`, `inBandResize`, `unicodeWidth`,
  `pointerShapes`, `desktopNotifications`, `taskbarProgress`, `terminalVersion` (the XTVERSION
  reply), `kittyGraphics` and `inlineImages` — plus `def colorScheme`. Named construction and `copy`
  are unaffected; a positional pattern `TerminalCapabilities(a, b, c, d)` no longer compiles —
  read the fields by name.
- **The start-up probe asks more.** It adds DECRQM for modes 2031, 2048 and 2027, OSC 11, DSR 996,
  XTVERSION (`CSI > 0 q`) and the kitty graphics query (an APC string) before the DA1 fence. The
  backend enables modes 2031 and 2027 unless the terminal denied them, and 2048 only on an
  explicit yes, and resets all three — and the pointer shape — on the way out. A test that models
  the wire byte for byte gains those sequences and must accept APC strings.
  `GLYPHORA_NO_CAPABILITY_PROBE=1` still skips the whole round trip.
- **`ColorDepth.detect` honours `FORCE_COLOR`** (`0` off; `1`, `2`, `3` force 16, 256 or true
  colour; anything else forces colour on, like `CLICOLOR_FORCE`) and, above everything else when
  set, `GLYPHORA_COLOR_DEPTH`. A CI runner that sets `FORCE_COLOR` for Node tooling now gets the
  forced depth; unset it or override `TuiApp.colorDepth`. The other new overrides —
  `GLYPHORA_KEYBOARD=legacy`, `GLYPHORA_MOUSE=off|buttons`, `GLYPHORA_ESCAPE_DELAY`,
  `GLYPHORA_DEBUG_LAYOUT=1` — change nothing unless set.
- **`HeadlessBackend.draw` runs the picture bookkeeping** the JLine backend runs. For a frame that
  places pictures (`Buffer.placeGraphic`), placements the installed capabilities cannot draw are
  dropped, and the protocol bytes owed are appended to `rawSequences` as one entry. Frames with no
  pictures behave as before.
- `Backend` gained `setPointerShape`, `notifyDesktop`, `setTaskbarProgress` and `keepInlineRows`, all
  with no-op defaults, so a custom backend keeps compiling. `JLine3Backend.wrapping` takes an
  optional `env`.

### Runner configuration and stdio capture

- **`TuiApp` captures `System.out`, `System.err` and Scala's `println` by default.** While an app
  runs, each completed line is written into the scrollback above the live UI; a partial line left
  at exit is flushed once the terminal is restored, and `suspend` passes the streams straight
  through. Before, a `println` from a handler painted over the frame. An app that relied on
  `println` reaching a redirected stdout *while running* — a wrapper reading its pipe live — opts
  out:

  ```scala
  // before: nothing to write; println went to System.out, and onto the frame
  // after
  override def config: RunnerConfig = RunnerConfig.Default.withCaptureStdio(CaptureStdio.Off)
  ```

  Only a configuration whose `captureStdio` is *unset* takes the app default, so an existing
  `override def config = RunnerConfig(tickRate = Some(d))` keeps the capture. A bare
  `TerminalRunner` reads the unset field as `Off` and behaves as before.
- **`RunnerConfig`'s mouse setting is one `MouseCaptureMode`, not a Boolean.** 0.19.0's
  `mouseCapture: Boolean` could say "off" or "buttons", and a second Boolean for hover would have
  allowed motion tracking with capture off. The field is now `mouse: MouseCaptureMode` — `Off` (the
  default), `Buttons` or `AllMotion` — and `MouseCaptureMode` gained the `Off` case
  (`Backend.enableMouseCapture(MouseCaptureMode.Off)` is `disableMouseCapture()`, so an exhaustive
  match on the enum needs that case). `withMouseCapture` (buttons) and `withoutMouseCapture` (off)
  keep working, `withMouse(mode)` sets any mode, and `config.mouseCapture` stays as a derived
  `Boolean`. The positional Boolean arities of `apply` still compile, `true` meaning `Buttons`; the
  named argument `mouseCapture = true` does not, since the defaulted `apply` can only take one type
  for it:

  ```scala
  // before
  RunnerConfig(tickRate = Some(rate), mouseCapture = true)
  // after
  RunnerConfig(tickRate = Some(rate), mouse = MouseCaptureMode.Buttons)
  // or, unaffected by the next field: RunnerConfig.Default.withTickRate(rate).withMouseCapture
  ```

- **`RunnerConfig` gained three fields**: `captureStdio` (`withCaptureStdio` / `withoutCaptureStdio`),
  `recording` (`withRecording` / `withoutRecording`) and `nonInteractive` (`withNonInteractive`,
  default `NonInteractivePolicy.Fail`, the old behaviour). As the
  [growth policy](#types-that-will-break-when-they-grow) promised, every earlier arity of the
  companion `apply` — seven, eight, nine and ten fields, each with its Boolean `mouseCapture` — is
  kept as a default-free overload, so compiled callers keep linking; `with*` chains compile
  unchanged. A positional call to one of the intermediate pre-release arities is
  clearest rewritten as a builder chain:

  ```scala
  // before (positional)
  RunnerConfig(rate, true, None, None, Viewport.Fullscreen, false, None, Some(CaptureStdio.Off))
  // after
  RunnerConfig.Default.withTickRate(rate).withMouseCapture.withCaptureStdio(CaptureStdio.Off)
  ```

- **`-Dglyphora.record=<file>`** makes every `TerminalRunner` run whose configuration names no
  recording write an asciicast v2 file; without the property nothing changes. A recording path that
  cannot be opened ends `run` with `RunnerError.Handler(IOException)` before the terminal is
  touched.
- **Hand-written `AppServices`, `Notifications` and `RunnerHandle` implementations** (usually test
  doubles) must add the new abstract members; apps that only call them, and `AppServices.NoOp`,
  are unaffected. `AppServices` gained `colorScheme`, `taskbarProgress(source)`,
  `clearTaskbarProgress()`, `keyMode` (return `Signal(ModeId.Default)`), `editExternally` and
  `openInEditor`, and `runEffect(key, effect)` — kept abstract on purpose, because a default that
  ignored the key would silently keep the stacking bug it fixes; `runEffect(key, effect, scope)`
  and `clipboard` are concrete with defaults. `Notifications` gained `desktop(title, body)`.
  `RunnerHandle` gained `setPointerShape`, `notifyDesktop` and `setTaskbarProgress` (add them as
  `()`) and `setMouseCaptureMode` (defaulted).

  ```scala
  // after: the members a 0.19 test double now also needs
  def runEffect(key: String, effect: Effect): Unit                       = ()
  def editExternally(initial: String, suffix: String): Option[String]    = None
  def openInEditor(path: Path, line: Option[Int]): Unit                  = ()
  def keyMode: Signal[ModeId]                                            = Signal(ModeId.Default)
  ```

- `object TuiApp` now exists as a companion; nothing changes unless code relied on its absence.

### Print mode

- **`Console` under the one import is glyphora's print-mode object.** `Console.print(view)` renders
  any element once to standard output, the name every terminal toolkit uses. Under
  `import io.worxbend.tui.dsl.*` it shadows `scala.Console`, so a file with that import that writes
  `Console.err` or `Console.println` spells the standard one out:

  ```scala
  // before
  Console.err.println("failed")
  // after
  scala.Console.err.println("failed") // or System.err.println
  ```

- **No-TTY runs are unchanged unless asked for.** Under the default
  `NonInteractivePolicy.Fail`, an app started without a TTY still prints
  `glyphora: terminal not supported: …` and exits 1. To render instead:

  ```scala
  override def config: RunnerConfig =
    RunnerConfig.Default.withNonInteractive(NonInteractivePolicy.PrintFinalFrame)
  ```

  See [Print mode](./print-mode).

### Widgets

- **An unfocused `confirmDialog` underlines its selected button instead of painting it in the
  focus cue.** In 0.19 the selected button wore the theme's `focus` style whether or not the
  dialog held focus, so moving focus onto it changed nothing on screen — `pilot.a11yReport`'s
  `NoFocusCue`. The focus cue now shows only while the dialog is focused, and a resting dialog
  marks its selection with an underline. The 0.20 pickers (`datePicker`, `timePicker`,
  `colorPicker`) follow the same rule. Nothing to change in code; a golden frame that records
  styles of an unfocused dialog needs re-recording.

- **`Language` is an alias for the open `Lexer` trait.** `widgets.Language` was a closed enum; it is
  now `type Language = Lexer` with an `object Language` whose `Scala`, `Json`, `Bash` and
  `Generic` are lexer values, so anyone can add a language. `SyntaxHighlighter.highlight(code,
  Language.Scala)`, `Language.of("sh")`, equality and `case Language.Scala =>` still compile;
  `Language.values`, `valueOf`, `.ordinal` and exhaustivity do not, and `Language.of("yaml")` now
  answers the YAML lexer rather than `Generic`:

  ```scala
  // before (0.19)
  def label(l: Language): String = l match
    case Language.Scala   => "Scala"
    case Language.Json    => "JSON"
    case Language.Bash    => "Shell"
    case Language.Generic => "Text"
  // after (0.20)
  def label(l: Lexer): String = l.name
  ```

- **`markdown` renders more of the source.** GFM tables, nested lists (indented, with per-level
  bullets), task lists (`☐` / `☑`) and `+` bullets render instead of showing literally; a fence's
  info string resolves only its first word, and the new lexers highlight `yaml`, `sql`, `diff` and
  other fences that used to render verbatim. To keep a list flat remove its indentation; to keep
  fences verbatim pass `MarkdownOptions(lexers = Seq.empty)`.
- **`Effect.slideInFromRight` delegates to `slideIn(MotionDirection.Left, …)`**, with the same
  frames; new code writes `Effect.slideIn(MotionDirection.Left, duration)`.
- **Search fields on scrolling nodes.** `ScrollViewElement`, `LogElement` and `WidgetElement` gained a
  trailing `search` field, and `Log` / `ScrollView` gained trailing `matchStyle` /
  `currentMatchStyle` parameters with defaults; factory calls and the previous positional arity are
  unchanged.
- **Node fields that grew.** `DropdownElement` gained a framework-only `floating` field,
  `ResponsiveElement` gained `local` and `probe`, and `ElementProps` gained `caretStyle`,
  `pointerShape`, `variant`, `lateBinding`, `selectable`, `dragPayload` and `dropTarget` at the
  end, all defaulted. Node constructors are not a compatibility surface (see
  [below](#types-that-will-break-when-they-grow)); code that spelled every `ElementProps` field
  positionally needs the extra arguments.
- **Pictures in pixels are opt-in.** `image(…)` still draws half blocks. `pixelImage(rgbaImage)`
  draws real pixels through the kitty graphics protocol or iTerm2 inline images where the terminal
  can, and the same half blocks where it cannot:

  ```scala
  // after
  pixelImage(RgbaImage.fromColors(pixels).toOption.get)
  ```

- **`dataTable` answers the mouse.** A left press on a body row now selects that row (it used to
  only focus the table), and because the press is consumed, a drag on the table starts a text
  selection only once it covers two cells rather than from the first cell. An app that selected
  rows itself in `.onMouseEvent` can drop that code, or return `true` from it to keep its own
  behaviour; a one-cell drag that used to copy one character now copies nothing.
- **Picker and toolbar constructors grew.** `ColorPicker` gained `mode` as its *second* parameter
  (after `showCaption`) and `highlightStyle` / `invalidStyle` at the end, so a positional
  `ColorPicker(false, style)` must name its style: `ColorPicker(showCaption = false, style =
  style)`. `DatePickerState` gained `min` and `max` after `today`, `DatePicker` a trailing
  `disabledStyle`, `Inspect` a trailing `highlightStyle` (and it is now also a
  `StatefulWidget[InspectState]`). Among the nodes, `ColorPickerElement` gained `mode` and
  `InspectElement` gained `state` before `props`, and `ToolbarElement`'s first field is now
  `controls` (its `children` include the overflow popup while it is open), followed by
  `overflow`; factory calls are unchanged.

  ```scala
  // before
  ColorPicker(false, captionStyle)
  // after
  ColorPicker(showCaption = false, style = captionStyle)
  ```

- **A grid move resets a `ColorPickerState`'s RGB fields.** Every arrow move, `moveTo` and click
  on a swatch refills the R/G/B fields from that swatch and hands the keyboard back to the grid.
  Palette-mode pickers see no difference.
- `MarkdownTheme` gained `tableHeader`, `tableBorder` and `taskDone`, `SyntaxTheme` gained
  `inserted` and `deleted`, `Markdown` gained an `options` parameter and `TextArea` an `options`
  parameter — all appended with defaults.

### New names under the one import

`import io.worxbend.tui.dsl.*` reaches many new names. An application that defines a top-level
member with one of them in a package where it also wildcard-imports the DSL now has an ambiguity;
rename its own, qualify one, or exclude the DSL's (`import io.worxbend.tui.dsl.{markup as _, *}`).
The likeliest clashes are `Console` (see [Print mode](#print-mode)), `Pattern` (with a wildcard
`java.util.regex.*` import — import the regex class by name instead, which takes precedence),
`Patch`, `Hunk`, `DiffLine`, `Prompt`, `ProgressTask`, `Store`, `Codec`, `Persisted`, `memo`,
`markup`, `AnsiParser`, `inspect` and `placeholder`.

### Built-in labels come from `Messages`

Every word the framework draws on its own — a bare `dialog`'s `OK`, `Screen.confirm`'s
buttons, the command palette, `helpOverlay`, the wizard, the inspector, `searchBar` and the
prompts — is read from the `Messages` table the run binds, with `Messages.Default` holding
the English strings earlier releases drew, so nothing changes until an app overrides it
(see [the app shell](./app-shell#translate-the-built-in-labels)). Two things can still bite:

- `TuiApp` gained a concrete `def messages: Messages`. An app that already declares a member
  called `messages` — a chat app's `val messages = Signal(Vector.empty[String])` — no longer
  compiles ("needs `override` modifier"); rename it.

  ```scala
  // before
  val messages = Signal(Vector.empty[String])
  // after
  val chatLog = Signal(Vector.empty[String])
  ```

- `Messages` is a new name under the one import; a type of your own called `Messages` in a
  package that wildcard-imports the DSL is now ambiguous — qualify one or exclude the DSL's
  (`import io.worxbend.tui.dsl.{Messages as _, *}`).

### `Dialog` sizes its box to its buttons

The `Dialog` widget (and so `dialog`, `confirmDialog` and `Screen.confirm`) used to size its
box from the message alone and silently drop buttons that did not fit, which a translated
label made the common case. The box now widens to the button row. A dialog whose buttons were
wider than its message draws a wider box than before; golden frames of such a dialog need
re-recording, and no source change is needed.

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
callers keep working. 0.20.0 is the first release to exercise that promise: `RunnerConfig`
grew three fields (`captureStdio`, `recording`, `nonInteractive`) and turned its mouse Boolean into
a `MouseCaptureMode`, and keeps a default-free `apply` for each earlier arity — seven, eight, nine
and ten fields — and
`Layout.apply` keeps its 0.19.0 five-argument arity beside the new `crossAlign` parameter.
`Theme.apply` gained a defaulted `components` parameter without an overload, so a compiled call
to it needs a recompile; `Theme.Dark.with*` chains do not.

These public types are still `case class`es that will grow; each grows only by appending a
defaulted field, so named construction and `copy` keep compiling, while a positional pattern
over the extractor needs one more binder per field:

| Type | Module | Why it will grow |
|---|---|---|
| `ElementProps` | `tui-dsl` | every new element modifier lands here |
| `FrameworkKeys` | `tui-dsl` | every rebindable framework key is a field (six were added in 0.20.0) |
| `TerminalCapabilities` | `tui-terminal` | every probed terminal feature is a field (eleven were added in 0.20.0) |

`Constraint` is a sealed `enum` that grows by cases rather than fields — 0.20.0 added `Auto` and
`Bounded`. A `match` over it that must survive the next case carries a wildcard.

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
gh workflow run publish-packages.yml --ref main -f version=0.20.0
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
