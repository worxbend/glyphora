# glyphora widget & component system — critical review and improvement brainstorm

Scope: read-only review of `/home/worxbend/Projects/Github/glyphora` (core, widgets, dsl, runtime, macros, docs).
Every claim about current behaviour carries `path:line`. Paths are abbreviated:
`core/` = `core/src/main/scala/io/worxbend/tui/core/`, `widgets/` = `widgets/src/main/scala/io/worxbend/tui/widgets/`,
`dsl/` = `dsl/src/main/scala/io/worxbend/tui/dsl/`, `runtime/` = `runtime/src/main/scala/io/worxbend/tui/runtime/`.
Nothing was built or run; "verified by grep" means a negative search over `*/src/main`.

Labels used below: **Effort** S (≤2 days) / M (≤2 weeks) / L (more); **Value** H/M/L.
Constraints from CLAUDE.md that keep recurring: **NR** = no runtime reflection (macros only), **CW** = all width math
through `CharWidth`, no `substring`, **TIER** = module dependencies point downward only (widgets depend on core only),
**OPT** = options-bundle rule (>8 defaulted params ⇒ `XOptions`), **ONE** = one-import surface (`dsl.scala` re-exports
every type in an exported signature; `OneImportSurfaceSpec`), **DOC** = every exported factory documented
(`DocumentedFactorySpec`), **FILE** = one widget per file, private shared helpers in named objects.

---

## 1. How the system works today

### 1.1 Widget contract (core)
- `Widget` is a SAM: `render(area: Rect, buffer: Buffer): Unit`; must confine writes to `area`, route width math via
  `CharWidth`, never retain `area`/`buffer` (`core/Widget.scala:3-16`).
- `StatefulWidget[S]` adds a caller-owned mutable `S` that render may mutate in place (clamp offsets, memoise views);
  single-threaded, not shareable (`core/StatefulWidget.scala:3-16`).
- `Measured` is the single content-size contract: `heightAt(width)` / `widthAt(height)`, `None` = "cannot say", never
  zero (`core/Measured.scala:12-32`). Only 13 of 108 widget files mix it in (grep); notably `Table`, `DataTable`,
  `Tree`, `TextArea`, `Chart`, `Block`, `Gauge`, `Calendar` do not.
- Widgets are immutable case classes with style params in fixed order; past ~8 knobs an options bundle
  (`ChartOptions` `widgets/Chart.scala:81`, `DataTableOptions` `widgets/DataTable.scala:310`).

### 1.2 State ownership
- Interactive widget state is a plain mutable class owned by the app: `ListState` (`widgets/ListView.scala:20`),
  `TextInputState` (`widgets/TextInput.scala:19`), `TextAreaState` (`widgets/TextArea.scala:23`), `TreeState`
  (`widgets/Tree.scala:18`), `ScrollViewState` (`widgets/ScrollView.scala:12`). Each Scaladoc warns that mutating it
  does not schedule a frame (e.g. `widgets/ScrollView.scala:7-10`).
- Two-state controls instead take a `Signal` read through the scope: `checkbox(label, Signal[Boolean])`,
  `select(options, Signal[Int])` (`dsl/ElementFactories.scala:217-226`).
- Component-local state: `useSignal` / `useState` / `keyed`, positional hooks with generation sweep
  (`dsl/ViewState.scala:36-139, 239-274`). Slot lifetime = "dropped the first frame not reached" (`dsl/ViewState.scala:27-31`).

### 1.3 Element tree (dsl)
- `trait Element` with `props`, `widget`, `children`, F-bounded `type Self` for type-preserving builders
  (`dsl/Element.scala:31-45`). Not sealed, but closed to outsiders because `withProps` is `private[dsl]`
  (`dsl/Element.scala:27-29, 45`). ~53 node types across family files (`dsl/Element.scala:29`).
- `ElementProps` = style, one optional `Constraint`, key/mouse/keyUp/paste handlers, focusable/autofocus/focusKey,
  framework-written `FocusState`, glyph ceiling (`dsl/ElementProps.scala:41-53`).
- Built-ins: `builtinKeyHandler`/`builtinMouseHandler`/`builtinPasteHandler` overridden per node
  (`dsl/Element.scala:50-61`), composed from combinators in `dsl/ElementBuiltins.scala:30-201`.
- Escape hatches: `widget(w)` leaf (`dsl/ElementFactories.scala:197`) and `interactiveWidget(state, handlers)(build)`
  leaf (`dsl/ElementFactories.scala:200-202`, `dsl/InteractiveWidgetElement.scala:22-35`).

### 1.4 Layout
- `Layout` splits one axis by `Constraint`s (`Length/Percentage/Ratio/Min/Max/Fill`, `core/Constraint.scala:8-14`),
  `Flex` places leftover (`core/Flex.scala:15-16`), optional overlap spacing (`core/Layout.scala:14-17`).
  Solver is integer largest-remainder (`core/LayoutSolver.scala:12-17, 94-105`).
- Segments always span the full cross axis (`core/Layout.scala:155-158`).
- DSL containers: `row`/`column`/`panel` wrap `w.Row`/`w.Column` (`dsl/LayoutElements.scala:212-257, 78-82`);
  a child without explicit constraint uses its node's `SizeClaim` (`dsl/Element.scala:111-112`,
  `dsl/SizeClaim.scala:11-31`). Measurement is height-only: `intrinsicHeight` = explicit `.length` → `Measured` →
  claim (`dsl/Element.scala:84-109`). There is no `intrinsicWidth`.
- Overlay primitives: `layers` (`dsl/LayoutElements.scala:351-357`), `positioned` (clipped,
  `dsl/LayoutElements.scala:269-282`), `portal` (frame-root deferred, `dsl/LayoutElements.scala:305-320`,
  `dsl/PortalQueue.scala:20-67`, drained up to 8 rounds `dsl/ViewSession.scala:24-31`).
- Responsive = whole-terminal width bands (`dsl/Responsive.scala:14-42`), explicitly "a media query, not a container
  query" (`dsl/NavigationElements.scala:6-8`).

### 1.5 Frame pipeline, focus and events
- Per frame: evaluate `view` → `ResponsivePass.resolve` (`dsl/ViewSession.scala:10-14`) → `FocusPass.focusKeys` +
  `autofocusRequest` + `FocusTracker.reconcile` → `FocusPass.decorate` (wraps every focusable in `TrackedElement`,
  every mouse-handling non-focusable in `PointerElement`) → paint → drain portals
  (`dsl/ElementHost.scala:53-83`, `dsl/Focus.scala:375-400`). `TuiApp` adds screens/palette/toasts as `layers`
  (`dsl/TuiApp.scala:724-753`).
- Focus = depth-first index + optional key (`dsl/Focus.scala:40-49, 189-209`); layers push/pop anchors
  (`dsl/Focus.scala:244-266`); modals make the covered tree `inert` (`dsl/Focus.scala:325-329`).
- Hit testing = last-painted area wins (`dsl/Focus.scala:21-29, 113-115`).
- Keys: user handler → built-in (focused only) → bubble to ancestors → screen/app bindings → framework keys
  (Tab, Ctrl+P, Ctrl+C, Esc) (`dsl/EventRouter.scala:40-44, 158-160`, `dsl/TuiApp.scala:680-709`). Wheel is the only
  mouse event that may reach an outer built-in (`dsl/EventRouter.scala:104-109`).
- Bindings: single-key triggers, first match wins (`dsl/KeyBindings.scala:79-83`).

### 1.6 Theming
- `Theme` = 10 semantic `Style`s + `LoadingTheme` + `MarkdownTheme` + `SyntaxTheme` + glyph ceiling
  (`dsl/Theme.scala:63-78`); passed in the `View` context. Themed factories read it at construction
  (`dsl/ElementFactories.scala:61-67, 128-135`). Focus cue is one style stamped on every node
  (`dsl/Focus.scala:363-382`, `dsl/ElementProps.scala:17-21`). Subtree cascade: `withStyle` rewrites every descendant
  eagerly (`dsl/Grammar.scala:214-219`).

### 1.7 Reactivity
- `Signal` / `Computed` with per-evaluation dependency tracking (`runtime/Reactive.scala:58-126`); one
  `GenerationalScope` per frame (`dsl/TuiApp.scala:585`). Any read signal changing invalidates the **whole** view
  (`dsl/TuiApp.scala:617-629`).

---

## 2. Pain points, inconsistencies and gaps (observed)

### 2.1 Composition / widget contract
1. **No user-defined container nodes.** `Element` is closed via `private[dsl] withProps` (`dsl/Element.scala:27-29,45`);
   the two public extension points are leaves (`dsl/ElementFactories.scala:197-202`). A third-party "card with header
   slot + body + footer that handles keys for its children" can only be a function returning built-in nodes; a custom
   container that lays out children with its own algorithm *and* keeps their focus/hit-testing is impossible (a
   `widget(...)` child has no children for `FocusPass`).
2. **Components have state but no lifecycle.** Only `useSignal`/`useState` exist (grep: no `useEffect`/`useMemo`/
   `onMount`); lifecycle hooks exist only on `Screen` (`dsl/Screen.scala:114,125`). A reusable "poll while visible"
   component must push work to the app.
3. **Two identity systems.** `each(...)` stamps a *focus* key (`dsl/ElementFactories.scala:103-110` →
   `props.focusKey`, `dsl/ElementProps.scala:50`) while hook state needs `keyed(...)` (`dsl/ViewState.scala:273-274`).
   A loop over stateful rows needs both and they can disagree.
4. **Two state idioms.** Signals for checkbox/toggle/select (`dsl/ElementFactories.scala:217-226`) vs non-reactive
   mutable objects for input/list/tree/scroll (`dsl/ElementFactories.scala:213,240,244`). The latter don't redraw on
   background writes (`widgets/TextInput.scala:14-17`); troubleshooting docs have a whole entry for it.
5. **`style` means different things per node.** On `panel` it is the border style (`dsl/LayoutElements.scala:73`,
   `dsl/ElementFactories.scala:66`), on `text` the text style (`dsl/DisplayElements.scala:34`), on `widget(...)` it is
   ignored entirely (`dsl/ElementFactories.scala:186-187`), on lists it is body style while highlight is `focusStyle`
   (`dsl/CollectionElements.scala:50-56`).
6. **`InteractiveWidgetElement` has no access to its area for keyboard logic** (only mouse gets `Rect`,
   `dsl/InteractiveWidgetElement.scala:12-16`) and `build` must be side-effect free because measurement may call it
   (`dsl/InteractiveWidgetElement.scala:18-21`) — fine, but there's no hook for "remember my last area".

### 2.2 Layout
7. **No grid.** Grep finds no grid layout (only `SpinnerGrid`/`DotGrid` widgets). Dashboards nest rows in columns;
   the sensor tutorial has a section literally titled "A grid that does not reflow".
8. **No cross-axis alignment / sizing.** Every segment spans the full cross axis (`core/Layout.scala:155-158`); a
   button in a column is always full width unless wrapped in a `row` with spacers or `place(...)`.
9. **One constraint per child.** `ElementProps.constraint: Option[Constraint]` (`dsl/ElementProps.scala:43`): can't say
   "fill, min 20, max 60". `Max` only takes leftover (`core/LayoutSolver.scala:69`).
10. **Layout ignores `Measured` for widget leaves.** Documented: `Measured` "is consulted by the scroll-view measurement
    pass and not by the layout solver" (`dsl/ElementFactories.scala:189-191`). Natural sizing is per-node `SizeClaim`
    hand-coded in 30 overrides (grep count), e.g. `TextElement.claim` recomputes line widths on every call
    (`dsl/DisplayElements.scala:66-70`).
11. **Height-only DSL measurement.** `intrinsicHeight` exists, `intrinsicWidth` doesn't (`dsl/Element.scala:84`);
    `w.Column` isn't `Measured` while `w.Row` is (`widgets/Column.scala:6` vs `widgets/Row.scala:33`) — the DSL
    re-derives column height itself (`dsl/LayoutElements.scala:254-255`).
12. **No flex-wrap, no margin, no aspect ratio, no z-index** (grep: `zIndex` absent; stacking = child order in `layers`).
13. **Container queries missing.** `responsive` sees terminal size, not the node's allotted area
    (`dsl/NavigationElements.scala:8`); a sidebar component can't adapt to its own width.
14. **Dropdown ignores the portal it could use.** It draws its popup inline and reflows siblings
    (`dsl/ChoiceElements.scala:76-79, 96-100`), although `portal` exists (`dsl/LayoutElements.scala:305`).
15. **Non-control nodes record no bounds**, so "click outside the dialog" counts the dialog's border as outside
    (`dsl/Chrome.scala:122-133`). The same gap blocks an inspector and hover-on-container.

### 2.3 Focus, events, interaction
16. **Linear tab order only.** No focus scopes/groups, no roving tabindex, no arrow-key spatial navigation, no
    `tabIndex` ordering, no disabled state (`dsl/ElementProps.scala:48-50`; grep: `disabled` exists only on
    `MenuEntry.Item.enabled`, `widgets/Menu.scala:16`).
17. **Mouse handlers get absolute coordinates** (`dsl/Grammar.scala:129-130`); `onClickAt` users subtract origins they
    don't have (only built-ins receive the area, `dsl/Element.scala:9-10`).
18. **No pointer capture.** Stated in `dsl/InteractiveWidgetElement.scala:9-10`; a slider/divider drag leaves the
    control once the pointer exits its hit area. No double-click/click-count in `MouseEvent`
    (`core/MouseEvent.scala:17`), no drag-and-drop.
19. **No hover state.** `onHover` is a raw callback (`dsl/Grammar.scala:152`); no framework "hovered" flag in
    `FocusState`, so hover styling needs app-side signals.
20. **Single-key bindings.** `KeyBindings.handle` matches one `KeyEvent` (`dsl/KeyBindings.scala:79-83`): no chords
    (`g g`, `ctrl+k ctrl+s`), no leader keys, no modes, no user-remappable keymaps.
21. **Hard-coded framework keys** (Tab/Shift+Tab/Ctrl+P/Ctrl+C/Esc in `dsl/TuiApp.scala:695-709`), `PageStep = 10`
    rows constant (`dsl/ElementBuiltins.scala:14`).

### 2.4 Text
22. **Editors lack selection, word motion, clipboard integration.** `TextInputState` has insert/backspace/delete/
    left/right/home/end/clear only (`widgets/TextInput.scala:30-54`); `TextArea` adds up/down and undo
    (`widgets/TextArea.scala:92-140`). Clipboard *copy* exists only as an app service (`dsl/AppServices.scala:53-54`).
23. **Undo snapshots the whole document per keystroke** (`widgets/TextArea.scala:136-140`, limit 100,
    `:143`) — no coalescing, O(doc) per edit.
24. **TextArea: no soft wrap, no line numbers, no highlighting** ("No syntax highlighting.", `widgets/TextArea.scala:145-147`);
    highlighter supports `Scala, Json, Bash, Generic` only (`widgets/SyntaxHighlighter.scala:22-23`).
25. **Hardware caret not driven by DSL inputs.** `Frame.setCursorPosition` exists (`runtime/Frame.scala:52`) and the
    a11y docs say screen readers and IMEs follow the *terminal* cursor (`website/docs/unicode-and-accessibility.md:83-87`),
    but DSL text nodes only paint a styled cell (`dsl/TextEntryElements.scala:23,72,136,179,248`; grep finds no
    `setCursorPosition` under `dsl/`). Real a11y/IME bug.
26. **No bidi/RTL** (grep: no bidi handling anywhere in core/dsl).

### 2.5 Performance
27. **Whole-tree work every frame**: view evaluation, responsive pass (`dsl/Responsive.scala:65-75`), two focus walks +
    decoration that reallocates every focusable/pointer node (`dsl/ElementHost.scala:80-81`, `dsl/Focus.scala:384-398`),
    then paint. Any signal read anywhere invalidates the whole view (`dsl/TuiApp.scala:585, 617-629`).
28. **Each event walks the tree** to find a path (`dsl/EventRouter.scala:114-121`); `mouseHit` filters every recorded
    area (`dsl/Focus.scala:113-115`). Fine at 100 nodes, linear at 10 000.
29. **No element-level virtualization.** `ListView`/`Table` virtualize rows (`widgets/ListView.scala:155`) but
    `scrollView(column(rows.map(card)*))` builds, measures (`dsl/NavigationElements.scala:79-82`), decorates and
    renders every card into an offscreen buffer sized to the window but with the full content rect
    (`widgets/ScrollView.scala:87-92`).
30. **Per-render O(n) scans** that defeat virtualization: `items.forall(heightOf(_) == 1)` each render
    (`widgets/ListView.scala:140`), `heightAt` sums every item (`widgets/ListView.scala:201`).
31. **`withStyle` rewrites the entire subtree** every frame it's used (`dsl/Grammar.scala:216-219`).

### 2.6 Theming
32. **Flat role set, no component tokens/variants/states.** 10 roles (`dsl/Theme.scala:63-78`); no `button.primary`,
    `input.border`, `table.header`; widget defaults are hard-coded `Style.Default.reverse` etc.
    (`widgets/TextInput.scala:71`, `widgets/ListView.scala:97`, `widgets/Dialog.scala:18`). No disabled/hover/pressed
    styles; focus is a single style (`dsl/ElementProps.scala:20`).
33. **Themes bind at construction**, not render: factories copy `theme.border` into props
    (`dsl/ElementFactories.scala:66`), so an element built in a helper outside a view uses `Theme.default`
    (`dsl/Theme.scala:359-368`).

### 2.7 DX / testing
34. **No debug overlay / inspector / layout borders** (grep: none). **No SVG/HTML frame export** (grep: none).
    Golden frames deliberately drop styles (`test-support/.../GoldenFrames.scala`, class Scaladoc).
35. **Missing widgets** (grep negative): tag/chip input, date picker (calendar is display-only,
    `dsl/ElementFactories.scala:360-364`), tree-table, breadcrumb widget (only `screenLabels`), stepper/wizard,
    diff viewer, menubar/toolbar, hex viewer, embedded terminal, virtualized element list, grid.

---

## 3. Improvement ideas

### (a) Widget contract & composition

**A1. Public custom container node (`Component` / `container`).** Expose a sanctioned way to define a node with
children: `custom(children*)(layout: (Rect, Seq[Measured-ish child]) => Seq[Rect])`. The framework still owns
`withProps`/`withChildren`; the user supplies only placement and optional chrome painting, so focus/hit-testing keep
working. Rationale: fixes 2.1#1 without opening `Element`.
```scala
def container(children: Element*)(place: (Rect, Seq[ChildInfo]) => Seq[Rect],
                                  chrome: (Rect, Buffer) => Unit = (_, _) => ()): ContainerElement
final case class ChildInfo(claim: Constraint, heightAt: Int => Option[Int])
```
Effort M · Value H · ONE (export `ChildInfo`), DOC, keep node constructor non-public (Element Scaladoc).

**A2. Slots.** Named slots on chrome-heavy nodes: `panel(title).header(e).footer(e)` where the footer renders inside
the bottom border row (today titles are strings/`BlockTitle` only, `dsl/LayoutElements.scala:94-119`). Generalises
"status in the border" to arbitrary elements (badge, spinner). Effort M · Value M · must measure slot widths via
`CharWidth`.

**A3. Component lifecycle hooks.** `useEffect(keys*)(acquire: => Cancelable)` run on the render thread after paint,
cancelled when the slot is swept or keys change; `useMemo(keys*)(compute)`. Implement on the existing `ViewState`
slots (`dsl/ViewState.scala:121-139`) by storing a disposer and running it in `sweep()` (`:60-62`). Rationale: 2.1#2,
mirrors `Screen.onEnter/onLeave` at component granularity.
```scala
def useEffect(deps: Any*)(start: => Cancelable)(using ReactiveScope): Unit
def useMemo[A](deps: Any*)(compute: => A): A
```
Effort M · Value H · must keep "no hooks in event handlers" rule (`dsl/ViewState.scala:212-220`); effects run on the
render thread so `RenderThread` invariants hold.

**A4. Unify identity: `each` also scopes hooks.** Make `each(items)(key)(render)` wrap `render` in `keyed(key)` as well
as setting `.key`. One rule for users: "loop with `each`". Effort S · Value H · behavioural change to hook ids → note
in versioning.md.

**A5. Reactive widget states.** Add `TextInputState.reactive` / `ListState` backed by an internal `Signal[Long]` version
counter bumped on mutation, read through the scope when the node renders. Background writes then redraw (2.1#4).
Widgets module can't see `runtime` (TIER), so the version counter must be an interface in core
(`trait Versioned { def version: Long }`) and the DSL node reads it through a `Signal` it owns. Effort M · Value H.

**A6. Decorator vocabulary.** Public decorators built on `DecoratingElement` (`dsl/ElementDecorators.scala:12-29`):
`.border(...)`, `.padding(...)`, `.background(style)` (generalise `FilledElement`, `dsl/LayoutElements.scala:328-346`),
`.shadow`, `.clip`. Removes the need to wrap in `panel(...).borderless` for padding. Effort S · Value M.

**A7. Consistent `style` semantics.** Split props into `style` (content) and `frameStyle` (border), keep `.fg/.bg` on
content; `widget(...)` gets a `.styled` that patches the area after render via `Buffer.mapStyle`
(`core/Buffer.scala:326`). Effort M · Value M · breaking for `panel(...).fg` users.

### (b) Layout engine

**B1. `grid` layout.** Track lists per axis reuse `Constraint` and the solver twice; children placed by
`(row, col, rowSpan, colSpan)` or auto-flow. Pure core addition: `GridLayout.split(area): Seq[Rect]` as a `final class`
with builders like `Layout` (`core/Layout.scala:19-25`).
```scala
grid(cols = Seq(Fill(1), Length(30)), rows = Seq(Length(3), Fill(1)), gap = 1)(
  topBar.span(cols = 2), sidebar.at(1, 1), main.at(1, 0))
```
Effort M · Value H · CW (no width math beyond integers), TIER (core), DOC.

**B2. Cross-axis alignment and sizing.** Add `align: CrossAlign = Stretch | Start | Center | End` to `Layout` and an
element `.alignSelf`, using `Measured.widthAt/heightAt` for the cross size (falls back to stretch on `None`, keeping
the `None` contract `core/Measured.scala:12-16`). Effort M · Value H.

**B3. Compound constraints.** `Constraint.Bounded(base, min, max)` or element `.minSize(n).maxSize(m).fill` folding
into one demand; the solver already classifies demands in one exhaustive `demandOf` (`core/LayoutSolver.scala:59-70`),
so a new case is local. Effort S–M · Value M.

**B4. Content-sized layout ("auto").** `Constraint.Auto` = ask the child's `Measured` along the split axis at the
cross size; makes `widget(myMeasuredThing)` size itself (today explicitly not, `dsl/ElementFactories.scala:189-196`)
and lets ~30 `claim` overrides collapse into `Measured` on the widgets. Requires `Layout` to accept a measure function
(core-only types). Effort M · Value H · `None` ⇒ treat as `Fill`, never zero.

**B5. `wrap` container (flex-wrap).** `flow(children*)` lays children by natural width and wraps lines — tags, badges,
button bars. Needs B4's width measurement. Effort M · Value M · CW for natural widths.

**B6. Container queries.** `responsive.local { size => … }` receives the node's area. Must resolve before the focus
pass (`dsl/Responsive.scala:44-54`), so it needs a layout pre-pass computing areas — doable once A1/B4 give a
"layout without paint" path. Effort L · Value M.

**B7. Explicit z-order.** `layers` already orders by child order; add `.zIndex(n)` only on `layers` children and portal
content, sorted stably. Low value given paint-order hit testing already works (`dsl/Focus.scala:21-29`). Effort S ·
Value L.

**B8. Record layout bounds for every node (debug + hit areas).** A cheap `NodeBounds` recorder during paint (opt-in)
fixes the dialog-border click-outside gap (`dsl/Chrome.scala:122-133`) and feeds the inspector (H1). Effort M ·
Value M.

### (c) Styling & theming

**C1. Design tokens + component roles.** Keep the 10 roles, add a typed token map `Theme.component[Button]` with
`ComponentStyle(base, focused, hovered, pressed, disabled, selected)`. Lookups via a given `ThemeKey[C]` per component
type — compile-time, NR. Widgets stay theme-agnostic (TIER): the DSL node resolves and passes plain `Style`s.
```scala
final case class StateStyles(base: Style, focused: Style, hovered: Style, disabled: Style, selected: Style)
theme.withComponent(ButtonRole.Primary, StateStyles(...))
button("Save").variant(ButtonRole.Primary)
```
Effort M · Value H · Theme is a `final class` with `with*` builders (`dsl/Theme.scala:45-52`) — add fields there.

**C2. Interaction-state styling in props.** Extend framework-written `FocusState` with `hovered`, `pressed`,
`disabled` (`dsl/ElementProps.scala:17-21`); `focusStyled` (`dsl/ElementBuiltins.scala:40-41`) becomes
`stateStyled(props, theme)`. Hover requires all-motion mouse mode (documented `website/docs/mouse.md:332-342`).
Effort M · Value H.

**C3. `.disabled` builder.** Removes from tab order, blocks built-ins, applies disabled style, announces "(disabled)"
in accessible forms. Effort S · Value H.

**C4. Lazy cascade.** Replace eager `withStyle` rewrite (`dsl/Grammar.scala:214-219`) with an inherited-style context
pushed during paint (like `FrameCoordinates`), so cascades cost nothing per node. Effort M · Value M.

**C5. Late theme binding.** Factories store a role (`StyleRef.Border`) not a resolved `Style`; resolution happens at
paint with the current theme, eliminating the "built outside view ⇒ default theme" trap (`dsl/Theme.scala:359-368`).
Effort M · Value M.

**C6. Stylesheet/selectors** (Textual-like CSS). Tempting, but selector matching needs class names or tags on nodes and
per-frame matching cost; without reflection it would key on explicit `.classes("danger")`. Recommend **against** a full
CSS engine; C1+C2 cover 90%. Effort L · Value L.

### (d) State & reactivity

**D1. Fine-grained invalidation per subtree (`memo`).** `memo(deps*)(subtree)` caches the built element (and,
optionally, its painted cell block keyed by area) across frames when deps are equal; with a per-memo
`ReactiveScope` child, a signal change re-evaluates only the subtree that read it. Effort L · Value H · single render
thread rule unchanged.

**D2. Store helper.** `Store[S](initial)` with `select(f): Computed[A]` and `dispatch(action)` — sugar over `Signal` +
`Computed` (`runtime/Reactive.scala:111`), with optional history for undo/redo (`store.history(limit)`). Effort S ·
Value M.

**D3. Persistence.** `Persisted.signal(path, initial)(using Codec[A])` writing on change off-thread via `Async`; codec
via `Mirror` derivation in `macros` (NR). Effort M · Value M.

**D4. Undo coalescing in editors.** Group consecutive same-kind edits within N ms into one undo entry; store diffs, not
full snapshots (`widgets/TextArea.scala:136-140`). Effort S · Value M.

### (e) Interaction

**E1. Focus scopes & roving focus.** `focusGroup(children*)` = one tab stop; arrows move within; `.trapFocus` for
non-modal popovers. Implement in `FocusPass.focusKeys` by collapsing a group to one index and storing an inner cursor
in the tracker (`dsl/Focus.scala:334-338`). Effort M · Value H.

**E2. Spatial navigation.** Optional `Alt+arrows` moves to the nearest focusable in that direction using recorded hit
areas (`dsl/Focus.scala:104-110`). Effort S · Value M.

**E3. Keymaps: chords, modes, remapping.** `KeySequence` parser (`"g g"`, `"ctrl+k ctrl+s"`), a pending-prefix state
with timeout on the tick, and named modes (`Keymap.mode("normal")`) for vim-style apps; `KeyBindings.handle` today is
single-event (`dsl/KeyBindings.scala:79-83`). Status bar shows pending prefix. Effort M · Value H.

**E4. Pointer capture + local coordinates.** A built-in that consumes `Down` captures the pointer until `Up`; routing
sends `Drag`/`Up` to the captor regardless of hit (`dsl/ElementHost.scala:96-108`). Mouse helpers get
`MouseContext(local: Position, area: Rect)` (fix 2.3#17). Effort M · Value H.

**E5. Click count / double-click.** Synthesise `clickCount` in `ElementHost.dispatchMouse` from timestamp + position;
`onDoubleClick`. Effort S · Value M.

**E6. Drag and drop.** `draggable(payload: A)` / `dropTarget[A](accept)(onDrop)` built on E4, with a portal-drawn ghost
and hover-highlighted target (C2). Typed payload via a `DragKey[A]` (no reflection). Effort L · Value M.

**E7. Text selection & copy in read-only content.** Mouse-drag selection over `text`/`markdown`/`log` paint, copying
the selected `Buffer` cells via `copyToClipboard` (`dsl/AppServices.scala:53-54`). Needs per-node bounds (B8).
Effort M · Value M.

### (f) Text

**F1. Editor core upgrade.** Shared `EditBuffer` (rope or piece table of grapheme clusters) for `TextInput`/`TextArea`
with selection (anchor+head), word motion (Unicode word boundaries via `java.text.BreakIterator`), select-all, cut/
copy/paste through app clipboard, soft wrap. Effort L · Value H · CW for column math; GraphemeEdit stays the splice
owner (`widgets/GraphemeEdit.scala`).

**F2. Drive the hardware caret.** The focused text node reports its caret cell; the host calls
`frame.setCursorPosition` after paint (`runtime/Frame.scala:52`) through `FrameCoordinates.translate` so scroll views
map correctly. Fixes screen readers and IME popups (2.4#25). Effort S–M · Value H.

**F3. Highlighter extensibility.** Turn `Language` enum (`widgets/SyntaxHighlighter.scala:22-23`) into a
`trait Lexer` with registered instances (Scala, JSON, Bash + YAML, SQL, Markdown, diff); `TextArea(highlighter = …)`.
Effort M · Value M.

**F4. Bidi.** Visual reordering per line via `java.text.Bidi` at render time only; cursor movement in logical order.
Needs CharWidth-aware run widths. Effort L · Value L–M (niche, but only correct way to show Hebrew/Arabic).

**F5. Line-number gutter, search, and find-next** for TextArea/Log/Markdown. Effort M · Value M.

### (g) Performance

**G1. Element-level virtualization: `lazyList(count, itemHeight)(i => Element)`.** Builds only visible items using a
fixed or measured height and caches measurement by key; integrates with focus by exposing virtual focus indices.
Effort L · Value H.

**G2. Make `ListView` render O(viewport).** Cache "all items one row" and total height by `items eq` identity in
`ListState` (pattern already used by `TreeState`, `widgets/Tree.scala:52-67`, and `DataTableState`,
`widgets/DataTable.scala:139-142`). Effort S · Value M.

**G3. Fuse the per-frame passes.** One traversal doing responsive resolution, focus key collection, autofocus search
and decoration (today 4 walks, `dsl/ElementHost.scala:80-81`, `dsl/ViewSession.scala:12`); retain the
path-to-focused computed during decoration for key routing (`dsl/EventRouter.scala:167-168`). Measure first with
`RenderLoopBench` per the project's performance docs. Effort M · Value M.

**G4. Spatial index for hits.** Replace `areas.filter` (`dsl/Focus.scala:113-115`) with a sorted-by-sequence array and
early exit, or a coarse grid bucket. Effort S · Value L (only matters at thousands of targets).

**G5. Dirty-region painting.** Diffing already limits terminal output (CLAUDE.md "Rendering is diff-based"), so paint
cost is CPU only; pair with D1 so memoized subtrees blit cached cells. Effort L · Value M.

### (h) Developer experience

**H1. Inspector overlay.** `F12` toggles an overlay drawing node bounds (B8), node type, constraint, claim, focus index,
key, and measured height for the node under the pointer; tree panel on the side. Pure DSL feature, no reflection —
node names via a `def debugName: String` each node declares. Effort M · Value H.

**H2. Debug borders.** `RunnerConfig.debugLayout = true` or env `GLYPHORA_DEBUG_LAYOUT=1` outlines every container with
a dim box and shows `0-size` children in red. Effort S · Value H.

**H3. Widget gallery app.** `examples/gallery` listing every exported factory with live props editing; doubles as a
visual regression harness (each page = golden). CI auto-discovers examples (CLAUDE.md). Effort M · Value M.

**H4. Error overlay.** Catch exceptions thrown by a subtree's `widget.render` into an "error boundary" node that paints
the message in the area instead of killing the frame: `errorBoundary(fallback)(subtree)`. Effort S–M · Value M.

**H5. Live reload.** JVM hot-swap is out of scope for native; a `Mill -w` + `TuiApp` state snapshot/restore
(`Persisted` from D3) gives "restart preserving state". Effort M · Value L.

**H6. Scaffolding.** `mill init`-style template or a `g8`/`giter8` project; small. Effort S · Value L.

### (i) Testing

**I1. SVG/HTML frame export.** `BufferExport.svg(buffer, theme)` with styles and wide glyph spans; used for docs
screenshots and style-aware goldens alongside the text plane (keeps text goldens readable, addressing the rejection
reason in `GoldenFrames`'s Scaladoc). Lives in `test-support` (TIER ok). Effort S–M · Value H.

**I2. Property-based layout tests.** Generators of constraint lists/areas asserting invariants: segments within area,
sum ≤ axis, monotone in weights, `sizeIn` == single-element `solve` (`core/Constraint.scala:16-35`). ScalaCheck as
test-only dep via `extraTestDeps`. Effort S · Value M.

**I3. A11y lint in Pilot.** `pilot.a11yReport`: focusables without visible focus change, colour-only status
(contrast via `Color.contrastRatio`, `core/Color.scala:306-319`), missing caret position for focused inputs (F2),
modals without Esc dismissal. Effort M · Value M.

**I4. Element-tree assertions.** `Snapshot` of the decorated tree (types, keys, focus indices) — complements buffer
goldens for "why didn't it get focus". Effort S · Value M.

### (j) Widgets missing vs. user expectations
| Widget | Notes | Effort | Value |
|---|---|---|---|
| Grid (B1) | dashboards | M | H |
| Virtual element list (G1) | feeds, cards | L | H |
| Tag/chip input | `flow` (B5) + `TextInputState` | M | M |
| Date/time picker | wrap `Calendar` (display-only now) with keys | M | M |
| Tree-table | `Tree` rows + `DataTable` columns | M | M |
| Breadcrumb | from `screenLabels`, clickable | S | M |
| Stepper / wizard | ordered screens + progress | S | M |
| Diff viewer | side-by-side/unified, uses F3 lexers | M | M |
| Menubar + context menu helper | `Menu` + `portal` + E1 | M | H |
| Toolbar / button group | `focusGroup` (E1) | S | M |
| Hex viewer | fixed-width grid, CW trivial | S | L |
| Embedded terminal/pty | big, needs VT parser | L | L |
| Accordion | multiple `collapsible` with single-open policy | S | L |

---

## 4. Top 15 ranked recommendations

Ranking = value / effort, weighted toward correctness and the most common app shapes.

1. **F2 Drive the hardware caret from focused text nodes** — real a11y/IME defect, small, documented intent
   (`website/docs/unicode-and-accessibility.md:83-87`) vs. no call site under `dsl/`. S–M / H.
2. **A4 `each` also scopes hooks** — removes a two-identity footgun (`dsl/ElementFactories.scala:103-110` vs
   `dsl/ViewState.scala:273`). S / H.
3. **C3 `.disabled` + C2 interaction-state flags** in `FocusState` (`dsl/ElementProps.scala:17-21`). S–M / H.
4. **B1 `grid` layout** in core with `Layout`-style builders. M / H.
5. **B2 cross-axis alignment** (`core/Layout.scala:155-158`). M / H.
6. **E4 pointer capture + local mouse coordinates** (`dsl/InteractiveWidgetElement.scala:9-10`,
   `dsl/Grammar.scala:129-130`). M / H.
7. **A3 `useEffect`/`useMemo`** on existing `ViewState` slots. M / H.
8. **H1+H2+B8 inspector overlay and debug borders** with per-node bounds recording. M / H.
9. **E3 chorded keymaps and modes** (`dsl/KeyBindings.scala:79-83`). M / H.
10. **C1 component tokens with state styles** in `Theme`, replacing hard-coded widget defaults. M / H.
11. **A5 reactive widget states** (version counter in core) to end "background write doesn't redraw". M / H.
12. **B4 `Constraint.Auto` via `Measured`** — layout finally consults measurement; deletes most `claim` overrides. M / H.
13. **E1 focus groups / roving focus** (toolbars, radio rows, menubars). M / H.
14. **I1 SVG/HTML export** for docs and style-aware goldens. S–M / H.
15. **A1 public custom container node** — the long-term composition primitive; do after B4 so placement has
    measurement. M / H.

Honourable mentions: G2 (ListView O(viewport), S), D4 (undo coalescing, S), F1 (editor core with selection, L/H — the
biggest single feature gap for text-heavy apps, ranked lower only by effort), G1 (virtual element list, L/H),
"Dropdown via portal" fix (`dsl/ChoiceElements.scala:76-79`, S/M).

Explicitly not recommended now: full CSS selector engine (C6), z-index (B7), live reload (H5).
