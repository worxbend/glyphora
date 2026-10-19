---
title: The app shell
description: Structure a glyphora application with scaffold, themes, commands, screens, toasts, splash, and terminal services.
---

# The app shell

`TuiApp` is the batteries-included application boundary. You provide a view and
optional lifecycle hooks; it connects state tracking, focus, input routing, screen
navigation, notifications, effects, and a terminal backend.

For a small app, `view` may be the whole story. For a full-screen tool, start with
the shell below and fill in each region.

## A complete shell

```scala
import io.worxbend.tui.dsl.*

final class DeployApp extends TuiApp:
  private val section = Signal(0)
  private val nav = ListState()
  private val search = TextInputState()

  override def bindings = KeyBindings(
    binding("ctrl+r", "refresh deployments")(refresh()),
    binding("ctrl+n", "new deployment")(openCreateScreen()),
    binding("?", "show keyboard help") {
      pushScreen(Screen(centered(48, 12)(helpOverlay(bindings))))
    },
    binding("q", "quit")(quit()),
  )

  def view(using ReactiveScope, Theme): Element =
    scaffold(
      topBar = Some(topBar(
        "deployctl",
        tabs = Seq("Deployments", "Events"),
        selectedTab = section.get,
        right = "production",
      )),
      sidebar = Some(sidebar(navigation, width = 25)),
      statusBar = Some(statusBar(bindings)),
    )(workspace)
```

`scaffold` builds four predictable regions: optional top bar, optional sidebar,
filling content, and optional status bar. Nothing bypasses the normal element/widget
pipeline, so each part can be styled, tested, or replaced.

## Compose the chrome

```scala
scaffold(
  topBar = Some(topBar(
    title = "glyphora",
    tabs = Seq("Widgets", "Log", "About"),
    selectedTab = selectedTab.get,
    right = "connected",
  )),
  sidebar = Some(sidebar(sidebarView, width = 24, side = Side.Left)),
  statusBar = Some(statusBar(bindings)),
)(mainView)
```

- `topBar` renders a title, optional tabs, and optional right-aligned status.
- `sidebar` describes content, width, and `side` (`Side.Left` or `Side.Right`);
  `scaffold` handles placement.
- `statusBar(bindings)` derives readable key hints from the same command registry
  that handles input.

For layouts without the full chrome, use `sidebarLayout`, `masterDetail`, `centered`,
or `place`; see [Layout & style](./layout-and-style).

A desktop-style shell adds a menu bar above everything, a toolbar under it, and
floating windows over the content — `menuBar`, `toolbar` and `window` in the
[widgets guide](./widgets#application-chrome):

```scala
column(
  menuBar(menus, bar)(runMenuItem),
  toolbar(button("New")(newDoc()), button("Save")(save())),
  layers(mainView, windowStack(inspector, console)).fill,
)
```

`Alt+<letter>` and `F10` reach the menu bar whatever holds focus, and `Alt+M` / `Alt+R`
move and resize the focused window; all three are rebindable framework keys
(`menu-bar`, `window-move`, `window-resize` — see [Key bindings](./keybindings)).

## Declare commands once

```scala
override def bindings = KeyBindings(
  binding("ctrl+s", "save current file")(save()),
  binding("ctrl+o", "open project")(openProject()),
  binding("ctrl+t", "switch theme")(nextTheme()),
  binding("esc", "quit")(quit()),
)
```

One `KeyBinding` supplies:

- event dispatch after the focused element declines a key;
- `(key, description)` hints for `statusBar`;
- rows for `helpOverlay`;
- searchable commands in the built-in `Ctrl+P` palette.

Descriptions should be short verbs: “open project” is easier to scan than “project
opening functionality.” `KeyEvent.parse` — the one parser in `tui-core` that reads
these strings — accepts printable keys, named keys, and modifiers such as `ctrl+s`,
`alt+enter`, and `shift+tab` (which also answers to `backtab`, the name most terminals
give that key). `Pilot.press` in a test takes the same strings, so a
test presses what the app declared rather than a hand-translated `KeyEvent`.

Two characters are both syntax and keys, and the parser resolves each in favour of the
key:

- `+` separates a modifier from the key it modifies, but only when it terminates a
  modifier name — so `"+"` binds the plus key and `"ctrl++"` binds Ctrl+plus.
- Surrounding whitespace is stripped as formatting, but never all of it — so `" "`
  binds the space bar, as does the more readable `"space"`.

Modifier names and named keys are case-insensitive (`"Ctrl+Enter"` works), but a
single-character key keeps its case, because that case is what the terminal reports:
Shift+G arrives as `KeyCode.Char('G')`, so bind `"G"`, not `"shift+g"`. Ctrl is the
exception — a terminal cannot tell Ctrl+S from Ctrl+Shift+S, so `"ctrl+S"` folds to
`"ctrl+s"` rather than declaring a binding that could never fire.

Five Ctrl combinations are rejected outright rather than parsed, because a terminal
without the kitty keyboard protocol has no way to send them — the control code they
would produce is already spoken for:

| Rejected spec | What the terminal actually sends | Bind this instead |
|---|---|---|
| `"ctrl+i"` | Tab | `"tab"` |
| `"ctrl+m"` | Enter | `"enter"` |
| `"ctrl+j"` | Enter | `"enter"` |
| `"ctrl+h"` | Backspace | `"backspace"` |
| `"ctrl+["` | Escape | `"esc"` |

Before, those specs parsed happily and then never fired, which is a bug that looks like
a broken key. Now they fail loudly at declaration time and the message names the
replacement.

Function keys run `"f1"` through `"f35"`, matching the range the input decoder emits.
How far up the range your terminal can actually reach depends on what it sends:
`"f1"`–`"f12"` work everywhere; `"f13"`–`"f20"` work on xterm-family terminals, which
report them as the legacy `CSI 25~`–`CSI 34~` numbers (that is what the keyboard sends
for Shift+F1 through Shift+F8); `"f21"`–`"f35"` need a terminal speaking the kitty
keyboard protocol, because no legacy escape sequence names them.

Six more keys have spec names, and they come with a caveat worth reading before you use
one:

| Spec | Key |
|---|---|
| `"capslock"` | Caps Lock |
| `"scrolllock"` | Scroll Lock |
| `"numlock"` | Num Lock |
| `"printscreen"`, `"prtsc"` | Print Screen |
| `"pause"` | Pause / Break |
| `"menu"` | the context-menu key beside the right-hand Ctrl |

An ordinary terminal never sends any of these. They arrive only from a terminal speaking
the kitty keyboard protocol (`"menu"` also arrives from xterm, which has sent it as
`CSI 29~` for decades). So treat a binding on one as a bonus shortcut for the people
whose terminal supports it, and always give the same command an ordinary key as well —
the `binding(Seq(…), …)` form below is exactly for that.

The three lock keys report the key *being pressed*, not the state it leaves behind.
glyphora has no notion of whether Caps Lock is currently on, and nothing is delivered
when the light changes for any other reason.

A spec that names no key (`"ctrl+"`) or no known key (`"banana"`) is a programmer error
and throws from `binding` at declaration time.

### Checking a key spec while compiling

"Throws at declaration time" still means the program has to run before anyone finds out.
Every spec in an application is a string written by hand, so a typo in one is knowable
much earlier than that. `key"ctrl+s"` is the same spec put through the same parser, but
during compilation: it gives back the `KeyEvent` the spec names, and a spec the parser
rejects fails the build with the parser's own message instead.

```scala
override def bindings = KeyBindings(
  binding(key"ctrl+s", "ctrl+s", "save current file")(save()),
  binding("ctrl+o", "open project")(openProject()),      // the string form still works
)

panel("editor")(editor).onKey(key"ctrl+d") { duplicateLine() }
```

Three kinds of spec are a compile error rather than a value:

- one the parser rejects — `key"ctlr+s"` (misspelt modifier), `key"banana"` (no such
  key), `key"ctrl+"` (modifiers with no key), `key"ctrl+i"` (a combination no terminal
  can deliver);
- one with a `$` hole in it, because a value filled in later is not known while
  compiling;
- anything that is not a literal at all.

For a spec that genuinely is built at run time — read from a config file, say — keep
using `KeyEvent.parse`, which reports the same problem as a `Left` you can show the
person who wrote the config.

The `KeyEvent` form of `binding` takes the label separately, as
`binding(key"ctrl+s", "ctrl+s", "save current file")`. A `KeyEvent` does not remember
the text it was written as, and the label is what the status bar and the help overlay
show, so glyphora asks for it rather than inventing a spelling.

## Modifier keys held on their own

A modifier is normally visible only as part of another key's event: holding Ctrl and
pressing `a` arrives as `KeyCode.Char('a')` with the `Ctrl` bit set in
`event.modifiers`, and the Ctrl key itself produces nothing. That is what almost every
application wants, and it is the only thing a terminal without the kitty keyboard
protocol can report.

Some terminals can also report a modifier press as a key in its own right. When they
do, it arrives as `KeyCode.Modifier(key)`, where `key` is a `ModifierKey` —
`LeftShift`, `RightControl`, `LeftAlt`, and so on, with left and right kept apart
because the protocol distinguishes them. Read it from an element or app key handler if
you want to show a “Ctrl held” hint:

```scala
.onKeyEvent { event =>
  event.code match
    case KeyCode.Modifier(ModifierKey.LeftControl) => showCtrlHint(); true
    case _                                         => false
}
```

There is deliberately no key spec for a bare modifier: `"shift"` is not a valid key
name, so declaring a binding on it fails at declaration time the same way `"banana"`
does. A binding on a bare modifier would fire part-way through every chord that begins
with that modifier, which is never what the author meant.

Note what the terminal has to do for any of this to happen: the modifier press must be
reported, and glyphora currently asks a kitty-capable terminal only to disambiguate
escape codes, not to report every key. So on a terminal glyphora set up itself these
events do not arrive today; a terminal already put into report-all-keys mode by
something else will deliver them, and the decoder now names them correctly instead of
dropping them on the floor.

## Keys coming back up

By default a terminal reports only that a key went *down*. A terminal speaking the
kitty keyboard protocol can also report that it came back up, and glyphora will ask it
to when the application says so:

```scala
override def config: RunnerConfig = RunnerConfig(keyEventTypes = true)
```

That adds the protocol's "report event types" flag to what glyphora pushes. Releases
then reach the element that has focus, and its ancestors, through `.onKeyRelease`:

```scala
panel(content)
  .onKeyEvent   { key => startAiming(key); true }
  .onKeyRelease { key => stopAiming(key); true }
```

Four things are worth knowing before reaching for it.

- **It is best-effort.** A terminal without the protocol reports presses only, so the
  handler is simply never called there. Use it to enrich an interaction — releasing a
  held key to stop something — and never as the only path to an action.
- **Auto-repeat is a press.** Holding a key on a terminal without the protocol already
  produces a stream of ordinary presses, so glyphora reports the protocol's own repeat
  the same way. Held-arrow scrolling then behaves identically wherever the app runs.
  There is no separate repeat event.
- **A release fires no binding and no built-in.** A binding names a press, and every
  built-in behaviour in the library is written against one; running either on the way
  up would do each action twice per keystroke. Only your own `.onKeyRelease` sees a
  release.
- **It costs traffic.** Every keystroke becomes two events, which is why the flag is
  off unless asked for.

In a test, `Pilot.release("a")` posts a release the same way `press("a")` posts a
press, so a release path can be driven without a terminal that supports the protocol.

## One command, several keys

When a command answers to more than one key — a vim-flavoured app where `j` and the
down arrow both mean “next” — declare it once with a sequence of specs instead of
twice:

```scala
override def bindings = KeyBindings(
  binding(Seq("down", "j"), "next item")(selectNext()),
  binding(Seq("up", "k"), "previous item")(selectPrevious()),
)
```

Any of the listed keys fires the action, and the **first** spec is the label, so the
status bar shows one `down next item` hint, the help overlay lists one row, and the
palette offers one command. Two separate `binding(…)` declarations would fire
correctly but advertise the same command twice everywhere it is listed.

### Media and transport keys

The play, pause, next-track and volume buttons have spec names too:

| Spec | Key |
|---|---|
| `"play"`, `"mediapause"`, `"playpause"` | play, pause, and the single play/pause toggle |
| `"stop"`, `"record"`, `"reverse"` | stop, record, reverse |
| `"fastforward"`, `"rewind"` | fast forward, rewind |
| `"tracknext"` (`"next"`), `"trackprevious"` (`"trackprev"`, `"prev"`) | skip forward, skip back |
| `"volumeup"` (`"volup"`), `"volumedown"` (`"voldown"`), `"mute"` | volume |

Note `"mediapause"`, not `"pause"`: `"pause"` names the Pause/Break key above the arrow
cluster, which is a different physical key.

Two things have to go right for one of these to reach your app. The terminal has to
speak the kitty keyboard protocol, and the desktop environment has to not take the key
first — many take the volume and transport keys before any application sees them. So
pair every media binding with an ordinary key, using the several-keys form above:

```scala
override def bindings = KeyBindings(
  binding(Seq("playpause", "space"), "play or pause")(togglePlayback()),
  binding(Seq("tracknext", "n"), "next track")(skipForward()),
)
```

### Sequences, modes and user keymaps

A binding's spec may be a sequence — `binding("g g", "go to top")(…)`,
`binding("ctrl+k ctrl+s", "save all")(…)`, `binding("<leader> f", "find file")(…)` —
with a which-key popup listing the possible continuations while one is half typed.
Bindings can be grouped into vim-style modes with `KeyBindings.modes`, and every binding
— including the framework's own Tab, Ctrl+P and Ctrl+C — has an id a user keymap file
rebinds. All of that is on [Key sequences, modes and keymaps](./keybindings).

Focused/local handlers run before global bindings. Use local `.onKey(...)` for
behavior owned by one element, and app bindings for commands meaningful everywhere.

An element-level handler can be written with the same key-spec strings a `binding`
uses, so a key has one spelling everywhere in an app:

```scala
panel("Editor")(editor)
  .onKey("ctrl+s") { save() }        // the spec string, as in binding("ctrl+s", …)
  .onKey("down", "j") { next() }     // several specs, one action
  .onKey(Key.Escape) { cancel() }    // the typed vocabulary, still available
```

Both forms build the same `KeyEvent` — the string goes through the same parser
`binding(…)` and `Pilot.press(…)` use — so `.onKey("ctrl+s")` and
`.onKey(Key.ctrl('s'))` are interchangeable. A spec the parser rejects (a misspelt
`"ctlr+s"`, a key name that does not exist) throws where the element is built, rather
than turning into a binding that silently never fires.

## Navigate with screens

A `Screen` is a tracked view pushed on the app's stack:

```scala
private def openCreateScreen(): Unit =
  pushScreen(Screen {
    centered(48, 11) {
      panel("New deployment")(
        createForm,
        text("Ctrl+S create · Esc cancel").dim,
      ).rounded.onKey(Key.Escape) {
        popScreen()
      }
    }
  })
```

`Screen(...)` is modal: it paints over the current view and removes the layers below
from tab order and from event routing: nothing below a modal receives a key or a mouse
event. `Screen.full(...)` replaces the current view entirely:

```scala
pushScreen(Screen.full(settingsPage))
```

Call `popScreen()` from the active screen to return. Focus stays inside a modal by
construction, so you do not need a separate focus trap.

Focus also moves *with* the layer. Pushing a screen starts the incoming screen on its
own first control — whatever was focused underneath, and however deep in the tab order
it was — and popping puts focus back on the element the screen covered, exactly where
the user left it. Nested layers unwind one level at a time, so closing a palette opened
over a dialog returns to the dialog rather than to the page beneath it. `replaceScreen`
counts as a new layer for this purpose, so the incoming screen starts at its first
control too.

### Swap, unwind, and read where you are

`pushScreen`/`popScreen` move one level at a time. Three more calls cover the rest of a
navigation stack:

```scala
replaceScreen(Screen.full(detailPage))   // swap the top screen, staying at the same depth
resetScreens()                           // unwind everything, back to the app's own view
```

`replaceScreen` matters because the alternative — `popScreen()` then `pushScreen(…)` —
writes the stack twice, and whatever renders in between briefly shows the layer
underneath. `replaceScreen` writes once, so the swap is a single frame. On an empty
stack it does the same thing as a push. `resetScreens()` is the one-call "home" from a
deep drill-down; on an already-empty stack it schedules no frame at all, because a
`Signal` set to the value it already holds notifies nobody.

Reading where navigation stands comes in two spellings, because a `view` and an event
handler are in different positions:

```scala
def view(using ReactiveScope, Theme): Element =
  // reactive: this view repaints when a screen is pushed or popped
  column(breadcrumb(screenDepth), currentScreen.map(_ => text("(in a screen)")).getOrElse(text("home")))

override def bindings: KeyBindings = KeyBindings(
  // a handler has no ReactiveScope and must not subscribe anything, so it reads the non-tracking form
  binding("esc", "back")(if screenDepthNow > 0 then popScreen() else quit()),
)
```

`currentScreen` and `screenDepth` are reactive reads for `view`; `screenDepthNow` is the
same depth read without subscribing, for a handler.

### Name the screens, and draw a breadcrumb

A screen can carry a short human name. Nothing draws it on its own — it is there so
the application can build its own trail:

```scala
pushScreen(Screen.full(settingsPage, label = "Settings"))
pushScreen(Screen(confirmBody, label = "Confirm delete"))
```

```scala
def view(using ReactiveScope, Theme): Element =
  column(
    text(("Home" +: screenLabels).mkString(" › ")),
    body,
  )
```

`breadcrumb("Home" +: screenLabels, onSelect)` draws the same trail clickable, folding its
middle items into `…` when the row runs short — see the widgets guide.

`screenLabels` lists the stacked screens outermost first, which is the order a breadcrumb
reads in, and skips any screen that has no label — a dialog that never wanted a name adds
a level of depth without opening a blank step in the trail. The app's own `view` is not in
the list: it is the thing everything else is stacked on, and only the application knows
what to call it, which is why `"Home"` above is written by hand.

It is a reactive read like `screenDepth`, so a view that draws the trail repaints on the
next push or pop by itself. A screen written as a class overrides `label` directly:

```scala
val settings = new Screen:
  def view(using ReactiveScope, Theme): Element = settingsPage
  override def label: Option[String] = Some("Settings")
```

There is deliberately no accessor for the screens themselves. Handing out the list would
publish the order the stack happens to be stored in, and everything that has wanted it so
far — a breadcrumb, a title bar — wants the names. That last binding is the usual
reason to want it: one `Esc` that means "go back a level" while anything is pushed and
"quit" at the top, without the app keeping a parallel counter of its own.

### Close a dialog with Esc or a click away from it

By default only the application's own `popScreen()` closes a screen. A modal can ask to
close itself instead:

```scala
pushScreen(Screen(confirmBody, dismissal = Dismissal.EscapeOrClickOutside))
```

`Dismissal` has four cases — `Never` (the default), `Escape`, `ClickOutside` and
`EscapeOrClickOutside` — and all of them apply to a modal only. A full screen replaces
what is beneath it, so it has no surrounding area that counts as "outside", and there is
nothing to fall back to.

`Esc` is handled at the very last stage of key routing, after the element tree and after
the key bindings, so an element that wants `Esc` for itself still gets it and the dialog
stays open.

A click is resolved against the topmost thing under the pointer, so a press on any of the
dialog's own controls goes to that control and the dialog stays open. A press that lands
on no control is then judged by where the frame painted things: the app records the
painted rect of every node while a click-outside modal is up, and a press on any node of
the dialog that *draws* something — its panel border, its title, a label inside it — is
inside the dialog and is absorbed, while a press that lands only on nodes that arrange
space and draw nothing — the `row`, `column` and `spacer`s `centered(...)` places the
dialog with, a dimmed `backdrop` — is outside it and closes it. "Outside" therefore means
what the user sees: outside the dialog's frame, border included.

A dialog made of nodes that own blank cells without drawing them — a borderless `column`
of labels — can still absorb every press that lands on it by consuming presses at its
root, exactly as before:

```scala
column(text("Really?"), button("OK")(ok())).onMouseEvent(_ => true)
```

The layer underneath a modal stays inert throughout, as it always has: it receives no
key and no mouse event, so nothing down there reacts to the click that dismissed the
dialog on top of it. `dismissibleOverlay(content)(onOutsidePress)` is the same backdrop as
a plain element builder, for a dialog an application layers itself rather than pushing as
a screen; its presence in the tree is what switches bounds recording on, so the very first
frame of a hand-layered overlay on a real terminal still judges by "on no control" until
the next frame is painted. See [Mouse](./mouse#a-click-outside-a-dialog) for the delivery
rules this rests on.

### Keys that belong to one screen

A shortcut often belongs to the screen showing it, not to the whole application. Before
`Screen` could declare its own keys there were two ways to write that, and neither was
good: putting the key on a root element handler made it fire from whatever the tree was
showing, including screens it had nothing to do with, and putting it in `TuiApp.bindings`
made it a permanent app key that had to check the navigation depth itself before deciding
whether it meant anything.

A screen declares its keys with the `keys` parameter, in exactly the form `TuiApp.bindings`
takes:

```scala
pushScreen(
  Screen(
    editorBody,
    keys = KeyBindings(
      binding("esc", "close the editor")(popScreen()),
      binding("ctrl+s", "save the draft")(saveDraft()),
    ),
  )
)
```

Those keys exist while that screen is on top of the stack and are gone the moment it is
popped. They are consulted *before* the app's own bindings, so a screen key shadows an app
key that answers to the same spec — the app's other keys keep working underneath. A screen
written as a class overrides `bindings` directly instead:

```scala
val editor = new Screen:
  def view(using ReactiveScope, Theme): Element = editorBody
  override def bindings: KeyBindings = KeyBindings(binding("esc", "close")(popScreen()))
```

Hand `activeBindings` — not `bindings` — to anything that *shows* the keys:

```scala
def view(using ReactiveScope, Theme): Element =
  scaffold(statusBar = Some(statusBar(activeBindings)))(body)
```

`activeBindings` is the merged list: the top screen's keys followed by the app's, with any
app binding the screen has completely taken over left out, because it can no longer fire.
Passing `bindings` instead would advertise keys the screen shadowed and omit the ones it
added, so the hints would disagree with what pressing them does. Dispatch, the status-bar
hints, the help overlay and the command palette all read this same list, so they cannot
drift apart. It is a reactive read, so a `view` that uses it repaints when a screen is
pushed or popped; the event path reads it without subscribing, which is why there is no
handler-facing spelling to remember.

### Start and stop work with a screen

`TuiApp.onStart`/`onStop` cover the whole app. A screen has the same pair of its own, for
work that belongs to a subtree that comes and goes:

```scala
val liveMetrics = new Screen:
  private var poller: Option[Cancelable] = None
  def view(using ReactiveScope, Theme): Element = metricsPage
  override def onEnter(): Unit = poller = Some(Async.every(5.seconds)(refresh()))
  override def onLeave(): Unit = poller.foreach(_.cancel())
```

A push during a run calls `onEnter` immediately on the render thread, after writing
navigation and before the first frame showing the entry. This includes pushes from
`onStart`. Popping, replacing, or resetting an active entry calls `onLeave`; teardown
leaves active entries innermost first, before the app's `onStop`, while app resources
are still available.

Navigation state survives repeated runs of the same app, but resource activation does
not. On the next run, retained inactive entries re-enter outermost first **after
`onStart` returns successfully**, before any frame. `onStart` can remove or replace
retained entries without leaving them again. Entries already entered by a startup
push are not entered a second time, and entries removed by an earlier `onEnter`
callback are skipped. Pushing the same `Screen` value twice creates independent
entries, each with its own enter/leave pair per active lifetime.

Every attempted `onEnter` is matched by one `onLeave`, even if acquisition throws
part-way through. An entry becomes inactive before its leave callback runs, so a
throwing cleanup or navigation from a callback cannot release it twice. Removing an
inactive entry invokes neither hook. Navigation before a run or from `onLeave`/`onStop`
during teardown only changes the retained stack; newly added entries stay inactive
until a later startup.

Teardown attempts every active entry's `onLeave` before the app's `onStop`; one failing
callback does not skip the others or the framework's own cleanup. The render owner
remains registered through these callbacks, so signal writes are still confined to
it. Terminal-facing app services are already inert; use these hooks to release
resources, not to start more work.

Before these existed, a screen that polls had to arm its poller in the app's `onStart`
and cancel it in the app's `onStop` — so it kept polling for a screen the user had
closed long ago.

A screen small enough not to want a class of its own passes the hooks to the factory:

```scala
pushScreen(Screen.full(metricsPage, onEnter = () => startPolling(), onLeave = () => stopPolling()))
```

Which of the two a screen is, is its `presentation`: `Presentation.Modal` (the default)
or `Presentation.Full`. A hand-written `Screen` overrides it directly:

```scala
val settings = new Screen:
  def view(using ReactiveScope, Theme): Element  = settingsPage
  override def presentation: Presentation = Presentation.Full
```

## Ask "are you sure?"

`dialog(...)` draws a dialog and answers nothing — it is a picture, so the selected
button, the arrow keys, `Enter` and `Esc` are all yours to wire. Two pieces remove that
work.

`Screen.confirm` is the whole thing, selection state included:

```scala
private def askBeforeQuitting(): Unit =
  pushScreen(
    Screen.confirm("Quit", "Discard unsaved changes?")(
      { popScreen(); quit() },   // OK
      popScreen(),               // Cancel
    )
  )
```

`Left`/`Right` (and `Tab`) move between the buttons, `Space` or `Enter` presses the
selected one, and `Esc` runs the cancel branch. Neither callback pops the screen for you:
what happens after a confirmation is the application's business, and a screen that popped
itself would take that choice away. Both are by-name, so nothing runs when the screen is
built. `confirmLabel` and `cancelLabel` rename the two buttons; the first is the one
selected when the screen opens.

When the selection has to live somewhere you can see it — a dialog with three buttons, a
choice that drives something else on screen — use the element directly.
`confirmDialog(title, message, buttons, selected)` is the controller, with selection
caller-owned as in every other control here:

```scala
confirmDialog("Deploy", "Deploy to production?", Seq("Deploy", "Dry run", "Cancel"), choice.get)(
  index => choice.set(index),
  index => run(index),
  () => popScreen(),
)
```

`onPress` is handed the index of the button that was pressed. A click presses the
*selected* button rather than the one under the pointer: the widget centres its labels
and publishes no per-button geometry, so there is nothing to hit-test against.

## Translate the built-in labels

The framework draws a few words of its own: the `OK` a bare `dialog(title, message)`
shows, the two buttons of `Screen.confirm`, the command palette's `Commands` title and
`type to filter…` placeholder, the `Help` heading of `helpOverlay(bindings)`, the wizard's
`Back`/`Next`/`Finish`, the inspector's panel titles, the `Search` placeholder of
`searchBar(state)`, and the prompts' `(Y/n)` hints, list hints, `❯`/`◉`/`◯` glyphs and
`✓`/`✗` task markers. Every one of them comes from one `Messages` table, which an app
replaces by overriding `messages`:

```scala
object App extends TuiApp:
  override def messages: Messages =
    Messages.Default
      .withDialogOk("Sí")
      .withDialogCancel("Cancelar")
      .withPaletteTitle("Órdenes")
      .withPalettePlaceholder("escribe para filtrar…")
      .withHelpTitle("Ayuda")

  def view(using ReactiveScope, Theme): Element = dialog("Guardado", "Los cambios están escritos.")
```

`Messages.Default` is the English table every earlier release drew, and each field has a
`with…` builder. The run binds the app's table on the render thread around every frame and
every event, so a factory called in `view`, an overlay painted after it and a screen pushed
from a key binding all read the same one. Labels the app passes itself —
`dialog(title, message, buttons = Seq("Vale"))`, `Screen.confirm(..., confirmLabel = "Sí")`
— are never touched: the table only supplies the defaults. Code that runs outside an app —
a `Snapshot`, `Console.print`, a `Prompt` — reads `Messages.Default` unless it is wrapped
in `Messages.scoped(table) { … }`.

## Notify without interrupting flow

```scala
notify("Deployment queued", NoticeLevel.Success)
notify("Authentication expired", NoticeLevel.Error, duration = 10.seconds)
dismissToasts()
```

Toasts stack in the top-right corner and expire after a wall-clock `duration`
(three seconds when you do not say). Each one renders through the same `Notice` widget
the rest of the toolkit uses, so it carries that severity's icon and theme colour.

Ticks are what *notices* that a toast has expired, so an app that uses them needs a
tick rate — but the tick rate no longer decides how long "three seconds" is:

```scala
import scala.concurrent.duration.*

override def config = RunnerConfig(tickRate = Some(100.millis))
```

Use toasts for confirmation and recoverable status. Keep required decisions in a
screen or dialog where they cannot disappear.

### Notifying from outside the app class

`notify` is a method on the app, so only code written inside the app's own body can
call it. As soon as a view is split across files — a helper that saves a row, an
element factory in a module of its own — that helper has no way to say anything to the
user unless the app threads a callback down to it.

`Notifications` is the same capability as a value. Take it as a `using` parameter:

```scala
// in any other file
def saveRow(row: Row)(using notifications: Notifications): Unit =
  repository.save(row)
  notifications.success(s"saved ${row.name}")
```

and call it from the app with nothing extra written at the call site, because the app
publishes its own `notifications` as a `given`:

```scala
override def bindings = KeyBindings(
  binding("ctrl+s", "save")(saveRow(selected.peek)),  // resolves `notifications` on its own
)
```

`info`, `success`, `warn` and `error` are shorthands for `notify` at that severity and
the default duration; `notify(message, level, duration)` and `dismissToasts()` are
there for anything else. Everything on it runs on the render thread, exactly like the
app methods it delegates to.

### Reach the user who looked away

A toast only tells someone who is watching the terminal. `desktop(title, body)` on the
same `Notifications` value raises the toast *and*, while the terminal window is
unfocused, a desktop notification through whichever protocol the terminal speaks
(OSC 9 for iTerm2, OSC 99 for kitty, OSC 777 everywhere else):

```scala
def announce(result: BuildResult)(using notifications: Notifications): Unit =
  notifications.desktop("build", s"${result.passed} tests passed")
```

"Unfocused" is what the terminal reported through focus events — `FocusLost` with no
`FocusGained` since — so a terminal without focus reporting is always treated as
focused and only ever shows the toast. The desktop half is best-effort in every
direction: a terminal without a notification protocol ignores it, one inside tmux or
screen never sees it (the backend knows and writes nothing), and the operating system
may suppress it. Keep the toast as the thing the user comes back to.

### Every service outside the app class

Toasts are not the only capability locked inside the app's body. `pushScreen`,
`popScreen`, `openPalette`, `closePalette`, `runEffect`, `requestRedraw`,
`copyToClipboard`, `quit`, `editExternally`, `openInEditor`,
`taskbarProgress`/`clearTaskbarProgress` and the reactive `colorScheme` read are all
reachable only from inside the app for the same reason, and a helper that needs one of
them faces the same plumbing.

`AppServices` is all of them as one value, and it extends `Notifications` — so ask for
the narrow type when a helper only notifies, and for this one when it navigates:

```scala
// in any other file
def statusFooter(using ReactiveScope, Theme, services: AppServices): Element =
  row(
    button("Settings")(services.pushScreen(settingsScreen)),
    button("Copy id")(services.copyToClipboard(currentId)),
    button("Quit")(services.quit()),
  )
```

The app publishes its own as a `given`, so calling that helper from `view` needs
nothing at the call site:

```scala
def view(using ReactiveScope, Theme): Element =
  column(body, statusFooter)
```

Each member delegates to the app method of the same name, so there is one
implementation of every behaviour and the render-thread rules are the ones those
methods already document.

For building an element outside a running app — a unit test, a tool that renders one
element and prints it — `AppServices.NoOp` supplies a value whose every method does
nothing, the way `Theme.default` supplies styling with no app around:

```scala
given AppServices = AppServices.NoOp
val element = statusFooter   // builds and renders; its buttons simply do nothing
```

## Run inline instead of taking the screen

By default an app takes the whole terminal. It does that by switching to the
*alternate screen* — the terminal's second screen buffer, which has no scrollback:
your shell's output disappears for the app's lifetime and is back, untouched, the
moment it exits, and the app leaves no trace behind. That is right for a dashboard
and wrong for a progress panel.

The other shape is inline. The app stays on the *primary* screen and owns only the
bottom few rows of it:

```scala
override def config: RunnerConfig = RunnerConfig(viewport = Viewport.Inline(3))
```

At startup the runner scrolls the screen up by three lines to make room, so
everything the shell printed before stays visible above the app, and when the app
exits its last frame stays on screen the way `git`'s output does — the shell's next
prompt appears underneath it, not over it.

Two things to know before choosing it. The frame is composed into those rows and
nothing else, so a layout written for a full screen is clipped to the strip: design
the view for the height you asked for. And a terminal shorter than the strip shrinks
it rather than failing — resize a window down to two rows and a `Viewport.Inline(5)`
app composes two rows, so the view still has to survive a small area.

`printAbove` pairs naturally with an inline app: it writes durable lines into the
scrollback above the strip, which is how an installer logs the steps it has finished
while the strip keeps showing the current one.

## Theme semantically

A `Theme` names every role the built-in chrome and the themed factories draw with:

| Role | Used by |
|---|---|
| `name` | what `Theme.toString` prints and a theme switcher shows |
| `primary`, `accent`, `muted` | body text, highlights, secondary text |
| `error`, `warning`, `success` | notices, badges, toasts |
| `surface` | dialogs and raised panes |
| `border` | the frame `panel` and `rule` draw with (a panel's `.frameStyle(...)` overrides it) |
| `focus` | the focus cue, and every element's selection highlight |
| `loading: LoadingTheme` | spinners and progress bars |
| `markdown: MarkdownTheme` | headings, bullets, quotes, inline code in `markdown(…)` |
| `syntax: SyntaxTheme` | token colours for the standalone `SyntaxHighlighter` |
| `glyphs: GlyphSupport` | the glyph ceiling every themed factory keeps under (`Theme.detected()` lowers it) |

A custom theme starts from a preset and replaces roles with the `with*` builders, or
spells every role out through `Theme(name = …, primary = …, …)`:

```scala
val mine: Theme = Theme.Dark
  .withBorder(Style.fg(Color.Magenta))
  .withFocus(Style.Default.reverse.bold)
```

`Theme` is a `final class`, not a case class: there is no `copy`, and only construction
through a preset plus `with*` is unaffected by a role added in a later release.

`Theme.Dark`, `Theme.Light`, and `Theme.HighContrast` ship with glyphora. Built-in
chrome, palette, focus, and toasts use semantic theme roles rather than hardcoded
colors, and so do the themed element factories: `panel` and `rule` take their frame
from `border`, `dialog` from `primary` and `focus`, every "pick a row" element —
`list`, `tree`, `menu`, `selectionList`, `filePicker`, `directoryTree` — highlights the
selected row with `focus`, and `markdown` renders through `markdown`. That last one is
the reason the field exists: the widget-level `MarkdownTheme()` defaults are tuned for
a dark terminal, so a document rendered under `Theme.Light` used to come out with cyan
headings on white.

The three grouped sub-palettes exist because their fields retheme together.
`LoadingTheme.from(accent, muted, surface)` derives a coherent loading palette for a
custom theme rather than making you spell out all five — see
[Widgets](./widgets#theming-the-animations).

### Theme presets

Nine themes ship with glyphora, all listed in `Theme.presets`:

| Preset | Character |
|---|---|
| `Theme.Dark`, `Theme.Light` | the defaults `Theme.auto` picks between |
| `Theme.HighContrast` | weight and underline instead of hue, for a monochrome or colour-filtered terminal |
| `Theme.Dracula` | purple accent, pink keywords |
| `Theme.Catppuccin` | the Mocha flavour: mauve accent over soft pastels |
| `Theme.Nord` | frost blue over the polar-night greys |
| `Theme.Gruvbox` | the dark variant, warm yellow accent |
| `Theme.Solarized` | Solarized Dark, blue accent |
| `Theme.Ansi16` | only the terminal's own sixteen colours, so it follows the user's terminal palette |

The five palette presets paint foregrounds in their scheme's published colours and leave
the terminal background alone, so they read best on a terminal set to the scheme's own
background. `Ansi16` is the one that is right on any background. Every preset fills
every role, the loading, Markdown and syntax palettes, and the `button.primary` and
`button.danger` components below.

### Component styles and variants

The flat roles say what a colour *means*; a component style says how one kind of
control looks in each interaction state. A `StateStyles` holds five styles — `base`,
`focused`, `hovered`, `pressed`, `disabled` — and a theme stores one per component role:

```scala
val mine: Theme = Theme.Nord.withComponent(
  ButtonRole.Primary,
  StateStyles(
    base = Style.of(Color.Black, Color.Cyan),
    focused = Style.Default.bold.underline,
    hovered = Style.Default.bold,
    disabled = Style.Default.dim,
  ),
)

button("Save")(save()).variant(ButtonRole.Primary)
```

`base` goes under the element's own style (an explicit `.fg(...)` still wins); each
other state, when it says something, replaces the cue that state would otherwise take —
the theme's `focus` and `disabled` styles, or the element's own `.hoverStyle(...)` /
`.pressedStyle(...)`, which stay the per-element override. A state left at
`Style.Default` changes nothing, so a theme with no components draws exactly as before.

The built-in roles are `ButtonRole.Default` / `Primary` / `Secondary` / `Danger`,
`InputRole` (every text field), `ListRole` and `ListRole.Selected` (the scrollable
collections and their selected row) and `TableHeaderRole`; `ComponentRole.named("…")`
adds one of your own. A `button` with no `.variant` resolves `ButtonRole.Default`.

Roles are looked up by a hierarchical `StyleName`: `"button.primary"` falls back to
`"button"`, merging every field the child leaves unset, so a theme can style all
buttons once and a variant only says what differs. `theme.component(role)` and
`theme.style(StyleName("list.selected"))` read the merged result. The host resolves
components while it paints, so widgets themselves never see the theme.

### Theme files

`Theme.parse(text)` reads a theme from a small `key = value` format, and
`Theme.render(theme)` writes one back, so an end user can restyle an app without
recompiling it:

```text
# midnight.theme
name    = midnight
extends = nord
accent  = bold #88c0d0
border  = #4c566a
markdown.heading1 = bold #88c0d0
syntax.keyword    = #81a1c1
component.button.primary         = #2e3440 on #88c0d0
component.button.primary:focused = bold underline #2e3440 on #8fbcbb
```

Every value is a style in [`Style.parse`](./layout-and-style#styles-from-text-and-back-again)'s
spelling. The keys are the roles (`primary` … `focus`), the palettes by dotted path
(`loading.fill`, `markdown.table-header`, `markdown.syntax.string`, `syntax.comment`),
`glyphs` (`full`, `box-drawing`, `ascii`), and components as `component.<name>` for the
base style and `component.<name>:<state>` for a state. A line whose first visible
character is `#` is a comment.

A setting the file leaves out keeps the base theme's value: the `extends` preset, or
`Theme.Dark` *without* components when there is no `extends`. That is why a file
`Theme.render` wrote reads back as exactly the theme it came from. Failures are values:

```scala
private val loaded: Theme =
  Theme.parse(Files.readString(path)) match
    case Right(theme) => theme
    case Left(error)  => // LineError(line, message): "line 4: accent: '#zzzzzz' is not a style word or a colour"
      System.err.println(s"$path: $error")
      Theme.auto

override def theme: Theme = loaded
```

### How a themed helper gets the theme

`view` takes it: `def view(using ReactiveScope, Theme): Element`. Everything it calls
that needs a theme — `statusBar(bindings)`, `topBar(...)`, `panel(...)` — resolves
against the app's own `theme` because the compiler passes it down that `using`
parameter. There is no `given` to declare.

That signature is not decoration. A framework that installed `given Theme = theme`
*around* the call to `view` would leave it out of scope *inside* the body, so an app
that overrode `theme` and wrote `statusBar(bindings)` in its own view would silently
get `Theme.Dark`. Carrying it in the type is what makes the override reach.

An element built *outside* a view — a top-level `def`, a helper object — sees only the
library default, `Theme.default`. It still paints in the app's theme: a themed factory
that was handed the default records how it built the node, and the host re-resolves it
against the app's theme when it paints, keeping any builder you applied afterwards
(`.titleStyle(_.bold)` stays bold, over the app theme's caption colour). A factory
handed an explicit theme — `panel(...)(using Theme.HighContrast)` — keeps that theme.
The chrome builders (`statusBar`, `topBar`, `helpOverlay`) compose several nodes and are
not late-bound; call them from `view`.

For live switching, keep a theme index in a `Signal`, return the selected value from
`theme`, and read the index in `view` so the tree is invalidated. A complete snippet
lives in [State & signals](./state-and-signals#runtime-theme-switching).

### Follow the terminal's light or dark scheme

The default `theme` is `Theme.auto`, and `Theme.auto` is not a palette: it is a
placeholder the app resolves on every frame to `Theme.Light` or `Theme.Dark` according
to what the terminal said about itself. At start-up the capability probe asks the
terminal for its background colour (OSC 11) and, where the terminal can say, for its
own light/dark verdict (`CSI ? 996 n`); afterwards a terminal that implements mode 2031
reports every switch of the user's system theme, and the next frame follows. A terminal
that answers nothing resolves to `Theme.Dark` — exactly what every app got before the
terminal could be asked.

An app that returns any other theme pins it and sees none of this. To pick between two
palettes of your own, read the scheme yourself:

```scala
override def theme: Theme =
  if colorSchemeNow == ColorScheme.Light then corporateLight else corporateDark
```

— or branch on it inside `view` with the reactive `colorScheme` read, and pick a single
colour with `lightDark(light, dark)`, which takes the light colour on a light terminal
and the dark one on a dark or unknown one:

```scala
def view(using ReactiveScope, Theme): Element =
  text("warning").fg(lightDark(Color.Rgb(0x80, 0x40, 0x00), Color.Yellow))
```

`lightDark` resolves through `AppServices`, so inside the app it needs nothing at the
call site and a helper elsewhere takes `(using AppServices)`. Outside any run,
`AppServices.NoOp` reports `ColorScheme.Unknown` and the dark colour wins. The
one-import surface carries `ColorScheme` (`Light | Dark | Unknown`); the terminal tier's
`TerminalCapabilities.colorScheme` and `backgroundColor` are where the answer is kept.

## Add a splash or frame effect

```scala
import scala.concurrent.duration.*

override def splash = Some(
  SplashScreen(
    content = centered(38, 5)(bigText("GLYPHORA").fg(Color.Cyan)),
    effect = Effect.coalesce(700.millis),
    minimumDuration = 1.second,
  )
)
```

The splash appears before the first normal view and any key skips it. `runEffect`
applies a post-render effect to the whole current frame:

```scala
runEffect(Effect.parallel(
  Effect.fadeIn(250.millis),
  Effect.sweepIn(350.millis),
))
```

Both need ticks to animate; while a splash is on screen it supplies a 50 ms tick
automatically to an app that configured none, and the loan ends with the intro. See [Motion](./motion).

## Use terminal services safely

`TuiApp` exposes protected services for operations that need the active backend:

```scala
copyToClipboard(currentUrl)

suspend {
  val editor = sys.env.getOrElse("EDITOR", "vi")
  new ProcessBuilder(editor, selectedPath.toString)
    .inheritIO()
    .start()
    .waitFor()
}

printAbove("deployment finished", s"id: ${deployment.id}")
```

- `copyToClipboard` uses OSC 52 where supported;
- `suspend` restores cooked mode and the normal screen while an external program
  runs, then re-enters the TUI and forces a full repaint;
- `printAbove` writes durable lines into scrollback above the live interface;
- `taskbarProgress(signal)` mirrors a `Reactive[Option[Double]]` on the terminal's tab
  or the taskbar (OSC 9;4 — Windows Terminal, Ghostty, WezTerm and others show it) for
  as long as it is bound, and `clearTaskbarProgress()` unbinds and clears it:

  ```scala
  private val downloaded = Signal(Option.empty[Double])
  override def onStart(): Unit = taskbarProgress(downloaded)
  // later, from an Async continuation: downloaded.set(Some(bytes.toDouble / total))
  ```

  `Some(fraction)` is a determinate bar, `None` clears it, the value is read on every
  frame so a change schedules the frame that pushes it, and the indicator is cleared
  when the app exits or the terminal is handed over. The same binding is available to
  helpers as `AppServices.taskbarProgress`.

These are no-ops or unavailable before a runner is active, so invoke them from event
handlers or from `onStart()` — never from a constructor.

### Where `println` goes

While a `TuiApp` runs, `System.out` and `System.err` — and Scala's `println` with them —
are captured: each completed line is written into the scrollback above the live UI
through `printAbove`, so a stray debug print or a library that logs to `System.err` no
longer paints over the frame. Whatever is still buffered without a newline is flushed to
the real streams when the run ends, under the restored prompt, and `suspend` passes the
streams straight through to the terminal for the duration of its body.

The policy is the `captureStdio` field of `RunnerConfig`, a `CaptureStdio`:

```scala
override def config: RunnerConfig = RunnerConfig()
  .withTickRate(100.millis)
  .withCaptureStdio(CaptureStdio.Off)            // leave the streams alone
  // .withCaptureStdio(CaptureStdio.Console(ring)) // or collect them in a LogRing
```

The field is left unset by `RunnerConfig()` and by `RunnerConfig(tickRate = …)`, which is
how a config that only sets other fields keeps the app's `PrintAbove` default; a bare
`TerminalRunner` reads the same unset field as `Off`. `CaptureStdio.Console(ring)` turns
each line into a `LogRecord` under the `stdout` (Info) or `stderr` (Warning) logger, for
the console below.

### A log console on a key

`logConsole(ring)` draws the tail of a `LogRing`; a `JulBridge.attach(ring)` in the
constructor feeds it everything `java.util.logging` sees. Bind a key to a `Signal[Boolean]`
and let the view decide whether the console is on screen:

```scala
object Monitor extends TuiApp:
  private val ring        = LogRing(2000)
  private val showConsole = Signal(false)
  private val bridge      = JulBridge.attach(ring)

  override def config: RunnerConfig = RunnerConfig().withCaptureStdio(CaptureStdio.Console(ring))

  override def bindings: KeyBindings = KeyBindings(
    binding("f12", "toggle log console")(showConsole.set(!showConsole.peek)),
    binding("ctrl+q", "quit")(quit()),
  )

  override def onStop(): Unit = bridge.cancel()

  def view(using ReactiveScope, Theme): Element =
    val console = Option.when(showConsole.get)(
      panel("Log")(logConsole(ring).minLevel(LogLevel.Debug)).length(8)
    )
    column(body +: console.toSeq*)
```

A record appended from a worker thread is on screen at the next frame; a frame that must
happen *because* a record arrived is the app's to request, with `requestRedraw()` from a
timer or the `Async` continuation that produced it.

### Edit text in `$EDITOR`

`editExternally(initial, suffix)` writes `initial` to a temporary file, runs the user's
editor on it through `suspend`, and answers the new content — `None` when the file came
back unchanged or the editor could not be started. `openInEditor(path, line)` opens an
existing file the same way, at `+line` when one is given. Both are `AppServices` members
rather than `protected` methods, so a helper outside the app class can call them:

```scala
binding("e", "edit description") {
  editExternally(description.peek, suffix = ".md").foreach(description.set)
}
binding("o", "open config")(openInEditor(configPath, line = Some(12)))
```

The editor is `VISUAL`, then `EDITOR`, split on blanks so `code --wait` works, and `vi`
when neither is set; a launch that fails is logged at `WARNING` on `io.worxbend.tui.dsl`
and treated as "nothing edited" rather than ending the app from inside a key handler.

### Styled scrollback with `insertBefore`

`printAbove` takes plain strings, and the backend strips control sequences out of
them before they reach the terminal. That is the right treatment for text of unknown
provenance, and it also means a line written this way can carry no styling at all: no
coloured log level, no bold prefix, no hyperlink.

`insertBefore(height) { ... }` is the same durable output, drawn rather than printed.
The block you are handed is a buffer as wide as the terminal and `height` rows tall,
and whatever you paint into it is what lands in the scrollback:

```scala
insertBefore(1) { (area, buffer) =>
  buffer.setString(area.x, area.y, "ERROR", Style.Default.withFg(Color.Red).bold)
  buffer.setString(area.x + 6, area.y, message, Style.Default)
}
```

Because the block is a plain `Widget`, any widget can render it — a `Paragraph`, a
`Table` of results, a `Gauge` frozen at the moment a step finished.

Two things to know. The block is emitted once, at the moment of the call: it is not
part of any later frame and is not repainted when the window is resized, exactly like
the shell output above it. And a `height` of zero or less inserts nothing and
succeeds, so a caller computing a height from a list of messages needs no guard.

## Choose the terminal to draw on

`run()` opens a terminal through `createBackend()`, which by default is JLine on the
process's controlling terminal. Two overrides change that without giving up `main`:

```scala
object MyApp extends TuiApp:
  // force a palette instead of sniffing NO_COLOR / COLORTERM / TERM — the way to
  // check that a theme still reads on a sixteen-color terminal without finding one
  override protected def colorDepth: ColorDepth = ColorDepth.Ansi16

  def view(using ReactiveScope, Theme): Element = ...
```

Override `createBackend()` itself to substitute a different `Backend` entirely — a
recording backend, a remote one — and everything else about the app is unchanged,
because the loop lives in `runWith(backend)` and runs over whatever it is handed.
That is the same seam headless tests use; see [Testing](./testing).

## Spatial focus and mouse motion

Two `TuiApp` options change how focus and the mouse behave across the whole app:

```scala
object Board extends TuiApp:
  override def spatialNavigation: Boolean = true                                // Alt+arrows move focus by direction
  override def config: RunnerConfig       = RunnerConfig.Default.withMouse(MouseCaptureMode.AllMotion) // hover now
  def view(using ReactiveScope, Theme): Element = tiles
```

`spatialNavigation` binds `Alt+Left/Right/Up/Down` to "nearest focusable in that
direction", after the tree and the bindings have had their chance at the key.
`withMouse(MouseCaptureMode.AllMotion)` asks the terminal for every pointer movement; with plain
`withMouseCapture` the app switches to it by itself the first time a frame draws a
`.hoverStyle`. Both are covered on [Mouse & focus](./mouse#move-focus-by-direction).

## Lifecycle hooks

| Hook | When to use it |
|---|---|
| `config` | tick cadence and backend behavior |
| `colorDepth` | force a palette instead of detecting one from the environment |
| `createBackend()` | draw on something other than JLine's controlling terminal |
| `theme` | active semantic theme |
| `bindings` | app-wide commands |
| `splash` | optional launch composition |
| `onStart()` | the run has begun and this is the render thread — start pollers and timers here |
| `onStop()` | the run is over, whatever ended it — cancel what `onStart` began |
| `onTick()` | advance frame-oriented state; never block |
| `onResize(size)` | react to a new terminal size (clamp a scroll offset, re-fetch a page) |
| `onTerminalFocus(focused)` | pause/resume activity when mode-1004 focus events arrive |
| `onInterrupt()` | intercept `Ctrl+C`; return `true` to keep running |

### Start and stop

`onStart()` runs on the render thread after the terminal is ready and before the first
frame. That matters for background work: `Async` captures the render loop of the
thread that calls it, and your app object is constructed long before any loop
exists — so `Async.every(...)` in a field initialiser attaches to the unattributed
queue instead of this app and can accumulate a backlog before startup. Calling
`quit()` from `onStart()` exits before anything is drawn, which is how a start-up
check declines to run.

`onStop()` runs on the way out of every owned run: `quit()`, an unconsumed `Ctrl+C`, a
backend failure, or a handler that threw. It also runs if evaluating `config` or
`splash` fails before `onStart`, under a temporary registered cleanup owner. In that
case retained screens stay inactive and receive neither hook, and the preparation
exception escapes unchanged with cleanup failures suppressed on it. Cleanup must
tolerate resources never having been opened. If `createBackend()` fails, no backend
was acquired and `onStop` is not called.

Nothing cancels a repeating `Async.every` for you, so `onStop` is where it is cancelled.

```scala
private var poller: Option[Cancelable] = None

override def onStart(): Unit = poller = Some(Async.every(5.seconds)(refresh()))
override def onStop(): Unit  = poller.foreach(_.cancel())
```

At `onStop()` the event loop has ended and app service handles have been detached,
so `quit()`, `suspend`, `printAbove` and `copyToClipboard` are no-ops. The render
owner is still registered, however: thread-confined cleanup and signal writes must
still run on that owner. Terminal restoration happens later, not before this hook.

The order is: detach app service handles and cancel the ambient ticker, screen
`onLeave` callbacks (innermost first), app `onStop`, root-scope disposal and clock
release, then queue closure, owner unregistration and backend closure.
Every release is attempted even if an earlier one throws. A prior run failure stays
primary and later failures are available through `RunnerError.cleanupFailures`
(and suppressed on the primary throwable when it has one). `RunnerError.message` ends
with ` (+N cleanup failure(s))` when teardown failed too, so printing the one line is
enough; `cleanupFailures` is identity-bound — not part of `==`, `hashCode` or `toString`,
lost on re-construction — and the suppressed chain on the primary throwable is the
durable record. With no earlier failure,
a non-fatal cleanup exception is returned as `RunnerError.Handler`; an escaping
throwable is rethrown only after cleanup, with later failures suppressed on it.

### Ask for a frame

A `Signal` write schedules its own redraw, an event handler that returns `true`
already asks for one, and so does a mutation of a caller-owned widget state painted on
the last frame: `ListState`, `TextInputState`, `DataTableState`, `LogState` and the rest
are `Versioned`, and the app compares their versions on every loop iteration, so

```scala
Async.run(fetchLines())(lines => lines.foreach(logState.append))
```

is on screen without anything else. `requestRedraw()` is for what none of these see —
a plain field of your own that the view reads, or a `LogRing` filled by another thread.

The full shell in action is
[`examples/showcase`](https://github.com/worxbend/glyphora/tree/main/examples/showcase).

## Embed the engine without TuiApp

`TuiApp` is two things stacked: an engine that turns a `view` into a painted frame and
routes events back into it, and a set of opinions about what keys mean — Tab and
Shift+Tab move focus, Ctrl+P opens the command palette, Ctrl+C quits, screens stack,
toasts expire, a splash plays first.

Those opinions are right for an application whose whole window is glyphora. They are
wrong for a program that already owns its loop, or that draws a glyphora panel inside a
loop somebody else wrote — Tab may already mean something there, and quitting is not a
key's decision to make. `ElementHost` is the engine on its own:

```scala
val host = ElementHost()

runner.run { frame ?=>
  host.render(frame, Theme.Dark, myView)
}

// per event, on the same thread the frames are painted on
def onEvent(event: Event): Unit = event match
  case Event.Key(key) if !host.dispatchKey(key) =>
    key match                                  // your policy, not the framework's
      case KeyEvent(KeyCode.Tab, mods) if mods.hasAny(KeyModifiers.Shift) => host.focusPrevious()
      case KeyEvent(KeyCode.Tab, _)                                      => host.focusNext()
      case _                                                             => ()
  case Event.Mouse(mouse) => host.dispatchMouse(mouse)
  case Event.Paste(text)  => host.dispatchPaste(text)
  case _                  => ()
```

What the host does for you is the part that is genuinely hard to rewrite: resolving a
`responsive` view against the size actually being painted, reconciling focus between
frames so the highlight follows the *element* rather than the screen position, decorating
the tree with the theme's focus cue, and routing every event to the tree that is on
screen rather than to a freshly evaluated one.

What it deliberately does not do is decide anything. `dispatchKey` returning `false`
means nothing on screen wanted the key and the choice is yours; the host never traverses
focus and never quits. `focusNext`, `focusPrevious`, `focusToKey` and `clearFocus` are
there when you want the usual meanings — call them from your own key handling.

Two rules come with it. One instance belongs to one loop, and every method is called on
that loop's render thread: the focus bookkeeping and the last painted tree live in the
instance and are not synchronised. And a focus change takes effect on the *next* frame,
because events are routed against the tree the last `render` painted — which is the tree
the person at the terminal is looking at. Any loop that redraws after handling an event
never notices.

A widget state written between frames — `state.insert(...)` from a continuation, not an
event — is noticed by `host.pollStates()`: it answers `true` when a state painted on the
last frame has been mutated since, and invalidates the view through its `ReactiveScope`
the way a signal write would. Ask it once per loop iteration, after running queued work
and before deciding whether to draw. Lifetime hooks start their effects after each
`render`'s paint; `host.reset()` cancels them.

`TuiApp` itself uses the same view session: hooks, responsive resolution, focus decoration
and deferred portal painting run exactly once per frame in both hosts. `useState` and
`useSignal` survive successive `host.render` calls; unvisited slots are swept after
responsive resolution. A host owns one mount until it is discarded or `host.reset()` is
called between frames. Reset drops hooks, focus history and the last dispatch tree; it
neither closes caller-owned resources nor disposes the caller's reactive scope. The
embedding loop still owns subscription cleanup, invalidation and scheduling.

A portal escapes enclosing panels and scroll windows, not its host. With the plain-buffer
render overload, all painting and pointer eligibility are clipped to `area` intersected
with `buffer.area`; layout origins are preserved even at the top/left boundary. The
frame overload uses `frame.area`. Nested hosts restore the surrounding hook, portal and
coordinate contexts, including when evaluation or painting throws.

Screen hooks are namespaced by navigation entry, not by the Screen object's identity or
its label. Pushing the same Screen twice creates independent local state; replacing it
with itself starts fresh. Only the last full-screen entry and the modals above it build
their views: earlier screens and the base cannot contribute pixels, so their builders
are skipped. Eager hook state in covered entries is retained without evaluating those
builders; responsive hooks whose subtree is discarded by a full-screen entry remain
unvisited and are swept, as before. Covered builders see current data when uncovered;
put resource acquisition and side effects in lifecycle/event handlers, not in `view`.
State that must outlive disappearance belongs in caller-owned
signals. Focus uses the same ordered entry identities and a fresh identity for each
palette activation. Changing navigation beneath an open palette keeps the palette/query
visible but resets focus for the changed suffix; closing it cannot restore an outgoing
screen's anchor.

### Custom interactive leaves

`widget(...)` remains the render-only adapter. Use `interactiveWidget(...)` for a
focusable custom control without extending Element. Its state belongs to the caller;
its builder receives the current style, focused flag and theme focus cue and returns an
ordinary `Widget` (optionally also `Measured`). Builders must be side-effect-free because
layout may request a widget before painting. Explicit sizing still overrides measurement.
All signature types are available from `import io.worxbend.tui.dsl.*`.

```scala
val state = TextInputState()
val editor = interactiveWidget(state, InteractiveHandlers[TextInputState](
  onKey = (s, event) => event.code match
    case KeyCode.Char(codePoint) =>
      s.insert(Character.toString(codePoint))
      true
    case _ => false,
  onPaste = (s, value) => { s.insert(value); true },
  onMouse = (_, event, layout) =>
    val localRow = event.position.y - layout.y
    localRow == 0,
)) { (s, context) =>
  (area, buffer) => buffer.setString(area.x, area.y, s.value,
    if context.focused then context.focusStyle else context.style)
}
```

Mouse bounds are the original **translated, unclipped layout rectangle**, in the same
coordinate space as the event. Routing checks the clipped visible intersection first,
including every nested scroll viewport and the host boundary. Do not capture a render
rectangle for mouse arithmetic: a scroll view can paint into an offscreen coordinate
space. No pointer capture is provided.

The usual `.onKeyEvent` and `.onMouseEvent` run before the adapter's defaults. `.onPaste`
is public on every element and runs on the focused leaf before its paste default;
handlers compose newest-first and return false to decline. Paste does not bubble into
application key bindings. `.onKeyRelease` uses the existing release route. The adapter
is a leaf, not a custom-container protocol: compose children with normal DSL containers.
