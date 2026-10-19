---
title: Mouse & focus
description: Understand glyphora's focus order, key bubbling, mouse hit-testing, built-in interactions, and stable focus identity.
---

# Mouse & focus

Keyboard and mouse input meet in the same element tree. Focus determines where key
events begin; rendered rectangles determine where mouse events land. User handlers
run before built-in widget behavior, and an event can bubble toward its parent when
it is not consumed.

## How focus is built

On each render glyphora walks the current `Element` tree depth-first and records
focusable elements. Inputs, buttons, toggles, selects, lists, text areas, and other
interactive elements opt in automatically.

- `Tab` moves to the next focusable element;
- `Shift+Tab` moves to the previous one;
- clicking an interactive element focuses it first;
- the active theme's `focus` style decorates the focused element — and is also the
  selection highlight every list-like element draws with, whether or not it has focus;
- opening a modal removes everything below it from the tab order and from key and mouse routing.

Make any custom element focusable with `.focusable`:

```scala
panel("Custom control")(text("press Enter"))
  .focusable
  .onKey(Key.Enter) { activate() }
```

## Choose where focus starts

Focus starts on the first focusable in the tab order. `.autofocus` moves it to the
element that asks:

```scala
column(
  input(search, placeholder = "search").autofocus.key("search"),
  list(results, resultState),
)
```

It fires **once**, on the frame the element first appears in, and never again while the
element stays in the tree. That is deliberate: an element that re-claimed focus every
render would make `Tab` useless, because the next frame would take the keyboard straight
back. The same rule makes it work for content that appears later — open a dialog whose
default button carries `.autofocus` and the keyboard goes there as the dialog appears,
then stays wherever the user puts it.

`.autofocus` also opts the element into the tab order, the way `.focusable` does, so it
works on a non-interactive element too. Pair it with `.key(...)`: "has this element
appeared?" is answered by the focus key when there is one and by the element's position
in the tab order otherwise, so an unkeyed autofocusing element that *moves* — because
something above it appeared — reads as a new element and claims focus a second time. If
two elements ask at once, the first in the tab order wins.

## Keep focus stable across changing trees

Without an explicit key, focus is positional. Conditional content inserted before a
control can make the same numeric position refer to a different element. Assign a
stable identity when tree shape changes:

```scala
column(
  input(search, placeholder = "search").key("search"),
  if showAdvanced.get then input(pattern).key("advanced-pattern")
  else spacer(1),
  button("Apply") { apply() }.key("apply"),
)
```

During the next focus pass glyphora finds the same key in the new tree and moves the
focus index to it.

## Move focus from application code

The same key is how an app moves focus itself, alongside the `Tab` traversal and
click-to-focus the framework does on its own:

```scala
binding("ctrl+s", "save") {
  if !emailIsValid then
    notify("that email address is not valid", NoticeLevel.Warning)
    focusTo("email")      // put the cursor back on the offending field
  else save()
}
```

`focusTo(key)` answers `false` and changes nothing when no focusable carrying that key
was in the frame the app last rendered — which is what happens when the element sits in
a branch the view did not render, or when the key is misspelt. When it succeeds, the key
is remembered, so focus then *follows* that element across later renders the way a
`.key(...)` normally does.

`clearFocus()` is the other direction: it leaves nothing focused at all, so no element
renders with the focus cue and keys go straight past the tree to the app's bindings.
`Tab` from there lands on the first focusable and `Shift+Tab` on the last. `focusedKey`
reads back where focus currently is (`None` when the focused element is unkeyed, when
focus was cleared, or when the app is not running).

All three are no-ops outside a run, so calling one from a constructor is a `false`
rather than a crash.

## Use concise handlers for exact keys

`.onKey` consumes an event only when one of its keys matches and composes with other
`.onKey` calls:

```scala
panel("Counter")(text(count.get.toString))
  .onKey(Key.char('+'), Key.Up) { count.update(_ + 1) }
  .onKey(Key.char('-'), Key.Down) { count.update(_ - 1) }
  .onKey(Key.Home) { count.set(0) }
```

The `Key` vocabulary includes arrows, `Enter`, `Escape`, `Tab`, paging and editing
keys, `f(1)` through `f(12)`, `ctrl`, `alt`, `shift`, and common constants such as
`CtrlS`, `CtrlP`, and `CtrlQ`.

## Use raw key events for conditional consumption

Drop to `.onKeyEvent` when you need the event's modifiers or want to decline it:

```scala
text("q quits only from this mode").onKeyEvent {
  case KeyEvent(KeyCode.Char('q'), _) if canQuit.peek =>
    quit()
    true
  case _ =>
    false // bubble to the parent, then app bindings
}
```

For the focused path, routing order is:

1. focused element's user handler;
2. focused element's built-in behavior;
3. each ancestor's user handler, inner to outer;
4. the app's `KeyBindings`;
5. built-ins such as `Tab`, `Ctrl+P`, and unconsumed `Ctrl+C`.

This lets an input consume normal text and editing keys while a parent still handles
`Escape` and the app still handles global commands.

## Use concise handlers for one kind of mouse event

Most mouse handling is "run this when the user clicks me", and writing that as a raw
`onMouseEvent` means repeating a `kind` test and a `true`/`false` every time. There is a
named builder for each kind, the mouse-side counterpart of `.onKey`:

```scala
row(text(label), text(count))
  .onClick { select(row) }                          // a press (MouseEventKind.Down)
  .onScroll(up = zoom.update(_ + 1), down = zoom.update(_ - 1))

panel("Canvas")(canvasView)
  .onClickAt(pos => selectedCell.set((pos.x, pos.y)))  // told which cell was pressed
  .onHover(pos => hovered.set(Some(pos)))              // pointer moved, no button held
  .onDrag(pos => stroke.update(pos :: _))              // pointer moved, button held
  .onDragEnd(_ => commitStroke())                      // the button came back up
```

Each one consumes only the kind it names and hands every other kind to a handler already
on the element, so several of them layer instead of the last one replacing the ones
before it — the same rule `.onKey` follows. `onScroll` takes both directions together on
purpose: a wheel that scrolls one way and not the other is a bug, and asking for both
makes forgetting one impossible.

Positions are absolute, zero-based terminal cells, the same coordinate space a `Rect`
uses; an element that wants its own coordinate space subtracts its area's origin.

`onHover` and `onDrag` depend on the terminal reporting motion. On terminals without
any-motion reporting the element simply never hears one, so a hover cue must never be
the only way to reach something.

Reach for the raw `onMouseEvent` below when a handler needs the whole event — its
keyboard modifiers, say — or when whether it consumes the event depends on the state.

## Handle custom mouse behavior

`MouseEvent` carries a `Position` (the absolute terminal cell the pointer was over), a
`MouseEventKind`, keyboard modifiers, and a `MouseButton`:

```scala
panel("Canvas")(canvasView).onMouseEvent {
  case MouseEvent(Position(x, y), MouseEventKind.Down, _, MouseButton.Left) =>
    selectedCell.set((x, y))
    true
  case MouseEvent(_, MouseEventKind.ScrollDown, _, _) =>
    zoom.update(value => math.max(1, value - 1))
    true
  case _ =>
    false
}
```

Kinds are `Down`, `Up`, `Drag`, `Moved`, `ScrollUp`, `ScrollDown`, `ScrollLeft`, and
`ScrollRight`. The last two are the horizontal wheel — what a sideways trackpad swipe
sends. No built-in element consumes them, because no widget in this library scrolls
sideways, so they bubble untouched all the way out and are yours to act on:

```scala
val table: DataTable[Int] = DataTable(columns, rows, widths)

dataTable(table, tableState).onMouseEvent { event =>
  event.kind match {
    case MouseEventKind.ScrollLeft  => columnOffset.update(n => math.max(0, n - 1)); true
    case MouseEventKind.ScrollRight => columnOffset.update(_ + 1); true
    case _                          => false
  }
}
```

A vertical wheel notch over a list still moves its selection; a sideways one deliberately
does not, because a swipe is not a row move. The position is
in absolute screen cells and zero-based, the same coordinate space a `Rect` uses. A
handler that wants coordinates relative to its own area should use `.onMouse` below
rather than subtracting an origin it was never given.

## Local coordinates, double clicks and capture

`.onMouse(ctx => …)` is the mouse handler to reach for first. It sees the same events as
`.onMouseEvent`, wrapped in a `MouseContext`: `ctx.event` is the raw event, `ctx.local`
is the position relative to the element's own top-left cell — `(0, 0)` at the corner,
wherever layout put the element — and `ctx.area` is the element's laid-out rect:

```scala
canvas(40, 10)(draw).onMouse { ctx =>
  if ctx.event.kind == MouseEventKind.Down && ctx.event.clickCount == 2 then
    zoomInAt(ctx.local)
    true
  else false
}
```

`MouseEvent.clickCount` is `2` for the second press of a double click and `3` for a
triple: the host counts presses of the same button on the same cell within 400 ms.
Terminals report no such thing, so the count is synthesised before any handler runs; it is
always `1` on anything but a `Down`.

A press that a handler or a built-in consumes **captures the pointer**. Until the button
comes back up, every drag and the release go to that element, with the area it had when
it was pressed, even once the pointer has left it — which is how a `slider` drag or a
`splitPane` divider keeps following the mouse past the control's edge. `ctx.local` can
then be negative or larger than the area, so clamp it. A press nobody consumed captures
nothing.

`.onMouseEvent` and the absolute-coordinate helpers (`onClickAt`, `onHover`, `onDrag`)
stay, and run before `.onMouse` on the same element.

## Which button

`MouseButton` is `Left`, `Middle`, `Right`, or `Unknown`. Matching on it is how an
application tells a context-menu click apart from an ordinary one:

```scala
panel("Files")(fileList).onMouseEvent { event =>
  event.button match {
    case MouseButton.Right =>
      contextMenuAt.set(Some(event.position))
      true
    case MouseButton.Middle =>
      pasteSelection()
      true
    case _ =>
      false
  }
}
```

`Unknown` is not a failure case — it is what the terminal reports when the event names no
button at all. A wheel notch (`ScrollUp`/`ScrollDown`) presses nothing, and the legacy X10
mouse encoding has one "some button came up" release code that does not say which button it
was. Modern terminals negotiate the SGR encoding, which keeps the button identity on the
release as well as the press, so a right-button drag-and-release reports `Right` throughout.

Built-in click behavior — a `button`, a `checkbox`, a `toggle` — fires on `MouseButton.Left`
only. A right-click over one of those controls is left unconsumed and keeps bubbling, so your
own handler, or an ancestor's, can open a menu instead of the button firing underneath it.
### One kind at a time

Most handlers care about exactly one kind of action, and writing that as a `match` means
remembering to return `false` for everything else — forget it and the element swallows
the wheel and every drag. A set of builders does the matching for you. Each runs on its
kinds and consumes them, and hands everything else to the handler the element already
carried, so it keeps travelling:

```scala
panel("Canvas")(canvasView)
  .onClickAt(position => selectedCell.set((position.x, position.y)))
  .onScroll(up = zoom.update(_ + 1), down = zoom.update(_ - 1))
```

| Builder | Fires on |
|---|---|
| `.onClick` | a button press (`Down`), with no argument |
| `.onClickAt` | the same press, told which cell it landed on |
| `.onScroll` | a wheel turn — both directions are taken together, because a wheel that scrolls one way only is a bug |
| `.onDrag` | motion with a button held (`Drag`) |
| `.onDragEnd` | the release that ends a drag (`Up`) |
| `.onHover` | motion with no button held (`Moved`) |

`.onHover` only ever fires under all-motion tracking, which the backend requests when an
app asks for it; under plain button-event tracking a terminal reports motion only while a
button is down, so a hover cue must never be the only way to reach something.

### Handlers compose

Two `.onKeyEvent` or `.onMouseEvent` calls on one element both run. The most recently
added goes first, and its `false` falls through to the one already there; the first
`true` stops the chain. The per-kind builders follow the same rule, so they compose with
each other and with a raw handler. That is what lets a helper hand back an element with a
binding on it and a call site add a second binding without erasing the first.


## Alt keys: menu bar or text field

A `menuBar` sees keys before the focused control (the capture phase), and a text field
uses four `Alt+<letter>` chords of its own: `Alt+B` / `Alt+F` move by word, `Alt+D`
deletes the word after the caret and `Alt+Y` cycles the kill ring. When both want the
same key, one policy applies:

| Focus | Bar | `Alt+F` with a `File` menu | `Alt+E` with an `Edit` menu | `F10` |
|---|---|---|---|---|
| a text field | closed | word right in the field | opens `Edit` | opens the first menu |
| anything else | closed | opens `File` | opens `Edit` | opens the first menu |
| anything | open | moves to `File` | moves to `Edit` | closes the bar |

So a field never loses its word motion, and every menu stays one key sequence away:
`F10` (the rebindable `menu-bar` key) opens the bar from anywhere, and with the bar open
a mnemonic letter, plain or with `Alt`, moves to its menu — `F10` then `F` opens `File`
from inside a field. The text fields are `input`, `textArea`, `searchBar`, `tagInput`
and `promptComposer`. "Press the mnemonic twice" was considered and rejected: a second
`Alt+F` is also the field's second word-right, so it cannot mean both.

## Built-in mouse behavior

You do not need custom handlers for common interactions:

| Element | Mouse behavior |
|---|---|
| input, list, select, controls | click focuses; click may position/select/activate |
| dataTable | click selects the row under the pointer |
| button, checkbox, toggle | click activates |
| list, menu, tree, directoryTree, selectionList, filePicker | wheel moves the selection one entry |
| log, scrollView | wheel changes the offset |
| slider | click or drag positions the value; a drag keeps going past the edge |
| split pane | drag moves the divider, also past the panes' edges |

`dataTable` is the one selectable collection with no wheel behavior: its selection
indexes the visible page, so a wheel step would have to choose between moving inside
the page and turning it. PageUp/PageDown say which, and `.onMouseEvent` is there if a
particular table wants the wheel to do one of them.

A user `.onMouseEvent` runs first. Return `false` when the widget's built-in behavior
should still run.

## Mouse delivery order

Mouse events are delivered only to elements that actually rendered under the
pointer, innermost first: the deepest handler-carrying element under the pointer,
outward to the element the click resolved to, then that element's ancestors. At each
step the element's own `.onMouseEvent` runs first and its built-in behavior second,
so a **wheel** a built-in declines keeps travelling outward — that is how the wheel
still scrolls a `scrollView` when the pointer sits over a button inside it. Only the
wheel does that: scrolling targets the nearest scrollable ancestor, while a press or a
drag stays with the element it landed on, so dragging inside a `splitPane` pane does
not move the divider. An element's built-in only ever acts on a pointer inside that
element's own rendered area. Each handler sees a given event at most once — returning
`false` bubbles the event outward, it never redelivers it. A handler on an element the
pointer is not over is not called.

Where overlapping elements *carrying handlers* compete, the topmost one wins.
Containers paint children in order and `layers(base, overlays*)` paints later children
over earlier ones, so a click on an overlay goes to the overlay, not to the base drawn
underneath it. Focusable elements are resolved differently — by smallest covering area,
which has no notion of z-order — so give an overlay's controls their own layer rather
than relying on paint order to shadow a focusable beneath them.

### A click outside a dialog

A modal pushed with `Dismissal.ClickOutside` (or a `dismissibleOverlay` layered by hand)
closes on a press that lands outside the dialog. Two rules decide what "outside" is:

1. A press that resolves to one of the dialog's controls — a button, an input, anything
   focusable or carrying a handler — is delivered there, as above, and the dialog stays
   open whatever the control does with it.
2. A press that resolves to no control is compared against the **painted bounds** of the
   dialog's nodes. If it lies within a node that draws something — a `panel` border, a
   `text`, a `rule`, a `badge` — it is inside the dialog and is absorbed. If it lies only
   within nodes that arrange space and draw nothing — `row`, `column`, `spacer`, `layers`,
   `positioned`, `portal`, `responsive`, and the `backdrop` fill of `place` — it is
   outside, and the dialog closes.

So the border of a dialog is part of the dialog, which it was not before 0.20.0, when a
border carried no control and so counted as outside. The bounds the second rule reads are
the same per-node record that `boundsOf(key)` and the element-tree snapshot use (see
[Testing](./testing#find-elements-by-key)); an app on a real terminal starts recording
them on the frame that first shows a click-outside modal and keeps recording for the rest
of the run, while a headless run records from its first frame.

## Hover and all-motion tracking

`Moved` — the pointer moving with **no** button held — is the one kind you do not get
by default. A terminal reports only the mouse activity it has been asked for, and the
default request is *button-event tracking*: presses, releases, the wheel, and motion
only while a button is down. Under that setting a terminal never sends a hover, so
`Moved` never arrives and `Drag` covers all motion you can see.

Asking for `MouseCaptureMode.AllMotion` adds the terminal mode that reports every
pointer movement over the window, which is what hover highlighting, tooltips and
drop-target previews need:

```scala
backend.enableMouseCapture(MouseCaptureMode.AllMotion)
```

It is opt-in because it is not free: the terminal sends a report for every cell the
pointer crosses, which costs nothing locally and is noticeable over a slow ssh link.
`MouseCaptureMode.Buttons` is what the no-argument `enableMouseCapture()` requests, and
`MouseCaptureMode.Off` through the mode-taking form stops capture. A runner picks one of the
three for its whole run with `RunnerConfig.Default.withMouse(MouseCaptureMode.AllMotion)` —
one setting, so "motion without capture" cannot be written.

A `TuiApp` running with mouse capture does not need to: the first frame that contains a
`.hoverStyle(...)`, a `.pressedStyle(...)` or an explicit `.pointer(shape)` switches the
terminal to all-motion tracking by itself — a pressed cue too, because once the button
comes back up the control returns to hovered or plain, and only motion reports say which. An app with mouse capture off is never switched on — that would take
the terminal's own text selection away.

### Hover and pressed styles

The framework works out which element the pointer is resting over and which one a button
is down on, and a control asks to show either:

```scala
button("Deploy")(deploy())
  .hoverStyle(Style.Default.bold)
  .pressedStyle(Style.Default.underline)
```

Only the innermost target under the pointer is hovered, so a container's hover style
shows where none of its children is. Pressed lasts from the press to the release, wherever
the release lands. A control with a `.key` is remembered by its key, so when the tree
changes shape before the next frame — a row inserted above it — the hovered or pressed
mark stays on that control instead of landing on whichever one took its place; an
unkeyed control is remembered by its position in the frame. A hover cue must never be the only way to find something: a terminal
without motion reporting never sends a hover.

## Disable a control

`.disabled(condition)` disables an element and everything inside it while `condition`
holds:

```scala
button("Save")(save()).disabled(!form.isValid)
```

A disabled control leaves the tab order (`Tab` skips it and `focusTo` refuses it), none of
its handlers or built-ins run — a click lands on it and stops there, neither activating it
nor moving focus — and it draws in the theme's `disabled` style (`muted`, dimmed, unless
the theme sets its own with `theme.withDisabled(style)` or a theme file's `disabled = …`). The
pointer over it is `PointerShape.NotAllowed`. It stays in the tree and keeps its space, so
nothing moves when it is re-enabled.

Colour announces nothing to a screen reader, so the state is also spelled out:
`app.accessibleLabel("save")` answers `Some("button Save, disabled")` for a keyed element
(`button Save, focused` when it holds the keyboard), and the element-tree snapshot marks the
node `disabled`. Put that label in an announcement line or a status bar for users who
cannot see the dimming.

## Focus groups

A toolbar of six buttons should not cost six presses of `Tab`. `focusGroup(children*)`
makes its children one tab stop with the arrow keys moving inside it — a roving tab stop:

```scala
column(
  input(search),
  focusGroup(button("New")(create()), button("Open")(open()), button("Delete")(delete())),
  list(items, state),
)
```

`Tab` from the input lands on `New`; `Right`/`Down` and `Left`/`Up` cycle through the
three, wrapping; the next `Tab` goes to the list; `Shift+Tab` comes back to whichever
member the group was left on. A member that consumes an arrow itself keeps it. Pass a
single `column(...)` for a vertical group.

### Keep Tab inside a popover

`.trapFocus` keeps `Tab` and `Shift+Tab` cycling inside a subtree while focus is in it —
for a popover or an inline editor that is not a modal screen but should hold the keyboard
until it closes. A pushed modal `Screen` needs none of this; the layer below it is already
out of the tab order.

```scala
column(text("Rename"), input(name).autofocus, row(button("OK")(ok()), button("Cancel")(cancel()))).trapFocus
```

## Move focus by direction

On a grid-shaped screen, `Tab` order is a poor fit. Overriding
`TuiApp.spatialNavigation` to `true` makes `Alt+Left/Right/Up/Down` move focus to the
nearest focusable painted in that direction — from the top-left tile of a 2×2 grid,
`Alt+Right` is the top-right tile and `Alt+Down` the bottom-left. Only an Alt+arrow
nothing in the tree and no binding claimed reaches it, and it stays inside a
`.trapFocus` subtree. A host with its own loop calls
`ElementHost.focusInDirection(SpatialDirection.Right)` itself.

## Select and copy text

A mouse-capturing app takes the terminal's own text selection away, which users notice
immediately. A `TuiApp` running with `RunnerConfig(mouse = MouseCaptureMode.Buttons)` gives it back:

- a left drag over `text`, `markdown`, `log`, `logConsole`, `table`, `styledTable` or
  `dataTable` selects the cells under it, highlighted in the theme's focus cue;
- a double click selects the word under the pointer (word boundaries come from
  `java.text.BreakIterator`), a triple click the whole row;
- the release copies the selection through the app clipboard — OSC 52 to the terminal,
  plus the in-app buffer `Ctrl+V` reads — and `Ctrl+Shift+C` (the rebindable
  `copy-selection` framework key) copies it again;
- the next press anywhere clears it.

A selection starts only from a press no element consumed, so a button inside a
selectable panel still clicks. The one exception is `dataTable`, whose press selects
the row under it: there the press still selects the row, and a drag that then covers
two or more cells (`TextSelection.DragThreshold`) turns into a text selection instead,
so a click whose pointer drifts one cell is still only a click. A selection stays
inside the node it started in — dragging
past the edge clamps to that node's first or last cell. Inside a `scrollView`, a drag
past the top or bottom of the viewport scrolls it one row per pointer movement, so the
selection can reach rows that were not on screen when it began. Selection works in whole
graphemes: a wide character is copied once, never half of it.

The keyboard selects too. On a focused selectable node — `text(…).focusable`, a `log`, a
`dataTable` — Shift+Left/Right/Up/Down, when the node itself does not use them, starts a
selection on the node's first visible cell and moves its far end a cell or a row per
press; `Ctrl+Shift+C` copies it.

A selection belongs to its node and to a place in that node's text, not to the screen.
Content added elsewhere in the view leaves it where it is, and inside a `scrollView` the
highlight moves with the content as the view scrolls. Give the node a `.key(…)` and the
selection follows it even when nodes are inserted before it; without one it is held by
the node's position in the tree. Copying takes only the rows on screen, since those are
the rows a scroll view draws.

Opt an element out — with everything inside it — with `.selectable(false)`; opt any other
node in with `.selectable(true)`:

```scala
column(
  text(secretToken).selectable(false),
  widget(myCustomRenderer).selectable(true),
)
```

The selection is an overlay on the frame, drawn over the node's cells as it paints;
the widgets themselves know nothing about it. An app without mouse capture draws no
selection at all — there the terminal selects natively. A host that owns its loop and
captures the mouse turns it on with `host.textSelection = true` and reads
`host.selectedText` / `host.copySelection()`.

## Drag and drop

`.draggable(payload)` makes an element draggable, `.dropTarget[A](accept)(onDrop)` makes
one accept payloads of type `A`:

```scala
final case class Card(rank: Int)
given DragKey[Card] = DragKey("card")

row(
  text("7♠").draggable(Card(7)),
  panel("Pile")(pileView).dropTarget[Card](_.rank > 5)(card => pile.update(_ :+ card)),
)
```

A press on a draggable element arms the drag and the first motion starts it; a press and
release without motion is still an ordinary click. While the drag runs, a dimmed ghost of
the element follows the pointer — drawn through a portal, over everything — and the
innermost drop target under the pointer that takes the payload draws the theme's focus
cue. The release over it calls `onDrop` with the value. `Esc` (the `close-modal`
framework key) while a drag runs cancels it: the ghost goes, and the release drops
nothing.

The type is carried by a `DragKey[A]`: a draggable and a target meet only when they were
built against the same key, which is how a drop hands the target an `A` without a
runtime type test or reflection — it stays native-image friendly. `String`, `Int` and
`Long` have built-in keys; give an app type one with `given DragKey[Card] = DragKey("card")`.
A payload of another type, or one `accept` refuses, passes over a target as if it were
not there. Drags need no all-motion tracking: motion with a button held is reported
under the default button-event tracking.

## Pointer shapes

On a terminal that speaks OSC 22 (kitty, foot, WezTerm, Ghostty, recent xterm) the
mouse pointer itself changes over what it is pointing at, the way it does in a
browser: `button` and `link` show the hand, the text inputs (`input`, `numberInput`,
`templateInput`, `textArea`) the I-beam, and a `splitPane` the resize arrow over its
divider — a horizontal split's one-column gap, or the first row of a vertical split's
second pane. Anything else hands the user's own pointer back.

`.pointer(shape)` asks for one on any element, for a custom clickable or to overrule a
default, and covers the element's children:

```scala
text("open in browser").onClick(open(url)).pointer(PointerShape.Pointer)
panel(busyBody).pointer(PointerShape.Wait)
```

`PointerShape` offers `Default`, `Text`, `Pointer`, `ResizeHorizontal`, `ResizeVertical`,
`Wait` and `NotAllowed`, the CSS cursor keywords the protocol takes.

Two things to know. The app updates the shape on every mouse event it receives, so
under the default button-only capture it changes on a click or a drag and under
all-motion tracking it follows the hover — and the backend is told only when the shape
changes, not once per cell. And it is decoration: a terminal without OSC 22 ignores
the request, one inside tmux or screen is never asked (`capabilities.pointerShapes` is
`Support.No` there), and nothing an app does may depend on it.

## Backend support

`TuiApp` and `JLine3Backend` negotiate mouse capture for you. A custom runner owns
that backend lifecycle itself. Input decoding belongs in the backend—application
code should never parse escape sequences.

The backend asks the terminal for four modes: `1000` (press and release), `1002`
(motion while a button is held), `1015` and `1006`. The last two are *encodings* — the
original one writes each coordinate as a single byte and cannot name a column past
223, so both of the newer forms are requested and a terminal that understands the
better one (`1006`, which names which button was released) settles on it. Pixel
reporting (`1016`) is deliberately not requested: its coordinates are pixels, and
everything above the backend addresses cells. `MouseCaptureMode.AllMotion` adds mode
`1003` on top.

Headless tests can click exact cells and post any mouse kind. See
[Testing](./testing#test-mouse-and-resize) for examples, and
[Unicode & accessibility](./unicode-and-accessibility) for keyboard-equivalent and
focus guidance.
