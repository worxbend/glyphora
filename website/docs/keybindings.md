---
title: Key sequences, modes and keymaps
description: Bind multi-key sequences, group bindings into vim-style modes, show a which-key popup, and let users remap every key from a keymap file.
---

# Key sequences, modes and keymaps

A `binding` names one key by default — `binding("ctrl+s", "save")(save())`. This page
covers what an editor-shaped app needs beyond that: keys pressed one after another,
modes in which the same key means different things, a popup that tells the user what
can follow, and keymaps users write themselves. All of it is the same `KeyBindings`
value the status bar, the help overlay and the `Ctrl+P` palette already read, so
nothing here has a second registry to keep in step.

## Key sequences

Write the keys of a sequence separated by spaces:

```scala
override def bindings = KeyBindings(
  binding("g g", "go to top")(scrollToTop()),
  binding("ctrl+k ctrl+s", "save all")(saveAll()),
  binding("<leader> f", "find file")(openFinder()),
  binding("q", "quit")(quit()),
)
```

Each step is an ordinary key spec, so `space` spells the space bar inside a sequence.
`KeySequence.parse` reads the same strings at run time and answers a `Left` instead
of throwing; `keyseq"g g"` checks one while compiling, the way `key"ctrl+s"` does for a
single key, and `binding(keyseq"g g", "go to top")(…)` takes it directly.

How a sequence is dispatched:

- The first key of a sequence that nothing else consumed becomes the **pending
  prefix**. From then on the next key goes to the sequence first, before the focused
  element, because the user is part-way through typing a binding.
- A key that completes a sequence fires it. A key that continues none drops the prefix
  and is routed as if nothing had been typed — `g` then `x` runs the `x` binding.
  `Esc` drops the prefix and is consumed.
- A prefix left waiting longer than `sequenceTimeout` (one second; override it on the
  `TuiApp`) is dropped before the next key is routed, even if no tick has occurred.
  Ticks also dismiss the popup while idle; an app with no `config.tickRate` borrows
  ticks for as long as a prefix is pending, exactly as it does for a toast.

A key that starts a sequence cannot also be a binding of its own: `binding("g", …)`
next to `binding("g g", …)` throws from `KeyBindings(…)`, naming both, because the
shorter one could only ever fire by waiting out the longer one. Public `++` composition
checks the combined bindings in every mode too. A screen's
`Screen.bindings` combined over the app's are not checked against each other — the
screen's keys shadow the app's while it is up.

### The leader key

`<leader>` stands for the space bar unless a `given KeyLeader` says otherwise. Declare
it before the bindings that use it:

```scala
given KeyLeader = KeyLeader(",")

override def bindings = KeyBindings(
  binding("<leader> f", "find file")(openFinder()),   // , then f
)
```

The leader is resolved when `KeyBindings(…)` is built, so a collision it creates — a
plain `binding("space", …)` next to `<leader> f` with the default leader — is caught
there too.

## The which-key popup

While a prefix is pending, `TuiApp` lays a small box over the bottom-right corner of
the view, titled with the prefix and listing every key that may follow it with what it
does. It needs no code: it appears in any app whose bindings declare sequences, and
`override def whichKey = false` switches it off. The box is placed with `portal`, so it
is never clipped by a pane; `whichKeyPopup(activeBindings, size)` builds the same box
for a view that wants to place it itself.

`statusBar(activeBindings)` follows the prefix too: while one is pending the hints give
way to `ctrl+k …` and the keys that may come next, so the bar answers "what now?" even
with the popup off.

## Modes

Vim-style apps give the same key different meanings in different modes. Group bindings
with `KeyBindings.modes`, and switch modes by setting `keyMode`:

```scala
override def bindings =
  KeyBindings.modes(
    Mode("normal",
      binding("i", "insert mode")(keyMode.set(ModeId("insert"))),
      binding("d d", "delete line")(deleteLine()),
    ),
    Mode("insert",
      binding("esc", "normal mode")(keyMode.set(ModeId("normal"))),
    ),
  ) ++ KeyBindings(binding("ctrl+q", "quit")(quit()))
```

- The first mode is the one the app starts in. A binding in a mode fires only while
  `keyMode` holds that mode; the mode-less bindings joined with `++` fire in every mode.
- `keyMode` is a `Signal[ModeId]`, on the app and on `AppServices` for a helper in
  another file. An app without modes sees `ModeId.Default`.
- `statusBar(activeBindings)` leads with the mode as `[normal]` and advertises only the
  live mode's keys; the palette offers only those too.
- `helpOverlay(activeBindings)` lists every mode, each under a `normal:` heading, after
  the mode-less bindings — the overlay is where a user learns what the other modes do.

Collisions are checked per mode: `"d"` in insert mode does not collide with `"d d"` in
normal mode.

## User keymaps

Every binding has a `BindingId`. Unless it names one with `.withId("…")`, the id is its
description slugged — `binding("ctrl+s", "save")` is `save`, `"go to top"` is
`go-to-top`. A keymap is a text file of `id = keys` lines:

```text
# ~/.config/myapp/keys
save      = ctrl+w   # muscle memory from the old editor
go-to-top = g t
quit      = ctrl+q
mark      = \#       # the # key itself
```

`Keymap.parse(text)` reads it into a `Map[BindingId, KeySequence]`, or a
`Left(LineError(line, message))` naming the first bad line — the same error type a theme
file's `Theme.parse` answers. Blank lines and lines starting with `#` are skipped, and a
`#` that starts a word of the keys — at their start or after a space — begins a comment
that runs to the end of the line. The `#` key itself is spelled `\#` there; inside a step,
as in `ctrl+#`, it is the key already. `none` unbinds an application or framework
binding. Framework ids use the reserved `framework.` namespace: `quit = ctrl+q`
changes only an application command, while `framework.quit = ctrl+q` changes the
framework's Ctrl+C action. Explicit app ids may not start with `framework.`.

For an end-user file, validate against the declarations and use the non-throwing
`rebindEither` path, retaining the defaults on failure:

```scala
private lazy val defaults = KeyBindings(
  binding("ctrl+s", "save")(save()),
  binding("g g", "go to top")(scrollToTop()),
)
private lazy val userKeys: Map[BindingId, KeySequence] =
  Keymap.validate(readKeymapFile(), defaults, FrameworkKeys.default).fold(
    error => { System.err.println(error.toString); Map.empty },
    identity,
  )

override def bindings = defaults.rebindEither(userKeys).getOrElse(defaults)

override def frameworkKeys =
  FrameworkKeys.default.rebindEither(userKeys).getOrElse(FrameworkKeys.default)
```

`rebind` replaces a binding's keys with the user's and makes that spelling its label, so
the status bar, `helpOverlay` and the palette all show the key the user actually has.
The result is checked for collisions again; ids nothing declares are ignored, which is
what lets one file feed both `KeyBindings.rebindEither` and `FrameworkKeys.rebindEither`.
Those methods return `Either[RebindError, ...]`; `Keymap.validate` also maps such errors
back to file line numbers. Plain `parse` checks syntax and single-key framework restrictions,
not app-dependent prefix collisions. The throwing `rebind` remains for static declarations.
Both application and framework rebinding resolve `<leader>` against the `KeyLeader` in scope.

### Framework keys

The keys the framework handles once nothing else claimed them are ids as well:

| Id | Default | What it does |
|---|---|---|
| `framework.focus-next` | `tab` | move focus to the next control |
| `framework.focus-previous` | `shift+tab` | move focus to the previous control |
| `framework.command-palette` | `ctrl+p` | open the command palette |
| `framework.quit` | `ctrl+c` | quit |
| `framework.close-modal` | `esc` | close a modal that asked to be closed by Esc |
| `framework.menu-bar` | `f10` | open the first menu of a `menuBar` |
| `framework.window-move` | `alt+m` | arrange mode: the arrows move the focused `window` |
| `framework.window-resize` | `alt+r` | arrange mode: the arrows resize the focused `window` |
| `framework.inspector` | `f12` | cycle the inspector overlay: node card, card and element tree, off — see [Seeing the layout](./troubleshooting#seeing-the-layout) |
| `framework.copy-selection` | `ctrl+shift+c` | copy the mouse selection again; consumed even with no selection |

Override `frameworkKeys` to move them, and set `pageStep` — how many rows
PageUp/PageDown move every list, table and scroll view, 10 by default — on the same
value: `FrameworkKeys.default.copy(pageStep = 20)`. A framework key fires when its key is
pressed with at least its modifiers held. Moving `framework.quit` moves the Ctrl+C *key event*; a
terminal that delivers Ctrl+C as an interrupt still quits through `onInterrupt`.

The keys `TuiApp` handles itself — `framework.focus-next`, `framework.focus-previous`, `framework.command-palette`,
`framework.quit`, `framework.close-modal`, `framework.inspector` and `framework.copy-selection` — may be sequences. They wait under
the same pending prefix as the app's own sequences, and are tried after them, so an app
binding that shares a prefix wins:

```scala
override def frameworkKeys =
  FrameworkKeys.default.rebind(Map(FrameworkKeys.Quit -> keyseq"ctrl+x ctrl+c"))
```

`framework.menu-bar`, `framework.window-move` and `framework.window-resize` are read by a `menuBar` or `window` while it
handles a single key, so each stays one key; `rebind` throws `IllegalArgumentException`
when given a sequence for one of them; `parse` rejects it with a line error and
`rebindEither` returns a `RebindError`. `unbind(ids*)` takes keys away altogether —
`FrameworkKeys.default.unbind(FrameworkKeys.Inspector)` leaves F12 to nothing — by binding
them to `KeySequence.unbound`, which never fires. The file spelling is `none`, including
for app bindings (`save = none`).

Copy-on-release is the primary selection-copy path on legacy terminals. Many emulators
reserve Ctrl+Shift+C for their own clipboard, and without enhanced keyboard reporting
that chord may arrive as plain Ctrl+C, indistinguishable from quit. Rebind
`framework.copy-selection` to a deliverable key such as `f6` when keyboard recopy is needed.
A genuine Ctrl+C key event still quits; it is not guessed to be a copy chord.

### Exporting the keymap

`Keymap.markdown(bindings)` writes the bindings as a Markdown table — key, action, id,
and the mode when there are modes — for a README or a help page;
`Keymap.markdown(bindings, frameworkKeys)` appends the framework's keys. It reads the
rebound bindings, so it documents the keys the user ends up with.

## Testing

`Pilot.press` takes one spec per key, so a sequence is pressed key by key:
`pilot.press("g", "g")`. Drive the timeout with a `ManualClock` handed to
`runWith(backend, clock.reading)` and `pilot.advanceClock(clock, 1100.millis)`.
