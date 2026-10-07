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

A screen can carry a short human name. Nothing in the library draws it — it is there so
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
dialog's own controls goes to that control and the dialog stays open. "Outside" here means
"on no control of the dialog", which is not quite the same as "outside its frame": a press
on a plain border or on a caption lands on no control, so it counts as outside. A dialog
that wants its whole frame to be inert says so in one line, by consuming presses at its
root:

```scala
panel("Really?")(body).onMouseEvent(_ => true)
```

A geometric test is deliberately not attempted, because nothing in the tree records where
a dialog was *placed* — placement is spacers around a sized node — so the rectangle would
have to be guessed, and a wrong guess closes a dialog the user is still using.

The layer underneath a modal stays inert throughout, as it always has: it receives no
key and no mouse event, so nothing down there reacts to the click that dismissed the
dialog on top of it. `dismissibleOverlay(content)(onOutsidePress)` is the same backdrop as
a plain element builder, for a dialog an application layers itself rather than pushing as
a screen.

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

### Every service outside the app class

Toasts are not the only capability locked inside the app's body. `pushScreen`,
`popScreen`, `openPalette`, `closePalette`, `runEffect`, `requestRedraw`,
`copyToClipboard` and `quit` are all `protected` methods for the same reason, and a
helper that needs one of them faces the same plumbing.

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

```scala
final case class Theme(
  name: String,
  primary: Style,
  accent: Style,
  muted: Style,
  error: Style,
  warning: Style,
  success: Style,
  surface: Style,
  border: Style,          // the frame `panel` and `rule` draw with
  focus: Style,           // the focus cue, and every element's selection highlight
  loading: LoadingTheme,  // spinners and progress bars
  markdown: MarkdownTheme,// headings, bullets, quotes, inline code in `markdown(…)`
  syntax: SyntaxTheme,    // token colours for the standalone `SyntaxHighlighter`
)
```

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

### How a themed helper gets the theme

`view` takes it: `def view(using ReactiveScope, Theme): Element`. Everything it calls
that needs a theme — `statusBar(bindings)`, `topBar(...)`, `panel(...)` — resolves
against the app's own `theme` because the compiler passes it down that `using`
parameter. There is no `given` to declare.

That signature is not decoration. A framework that installed `given Theme = theme`
*around* the call to `view` would leave it out of scope *inside* the body, so an app
that overrode `theme` and wrote `statusBar(bindings)` in its own view would silently
get `Theme.Dark`. Carrying it in the type is what makes the override reach.

For live switching, keep a theme index in a `Signal`, return the selected value from
`theme`, and read the index in `view` so the tree is invalidated. A complete snippet
lives in [State & signals](./state-and-signals#runtime-theme-switching).

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
- `printAbove` writes durable lines into scrollback above the live interface.

These are no-ops or unavailable before a runner is active, so invoke them from event
handlers or from `onStart()` — never from a constructor.

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
(and suppressed on the primary throwable when it has one). With no earlier failure,
a non-fatal cleanup exception is returned as `RunnerError.Handler`; an escaping
throwable is rethrown only after cleanup, with later failures suppressed on it.

### Ask for a frame

A `Signal` write schedules its own redraw, and an event handler that returns `true`
already asks for one. Neither covers state the reactive layer cannot see: the
caller-owned widget states (`ListState`, `TextInputState`, `DataTableState`, `LogState`)
are plain mutable objects, so

```scala
Async.run(fetchRows())(rows => tableState.rows = rows)
```

updates the table correctly and still leaves the previous frame on screen until the
user happens to press a key. `requestRedraw()` asks for the frame that mutation earned:

```scala
Async.run(fetchRows()) { rows =>
  tableState.rows = rows
  requestRedraw()
}
```

The full shell in action is
[`examples/showcase`](https://github.com/oleksandr-balyshyn/glyphora/tree/main/examples/showcase).

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

`TuiApp` itself is built on this class, so an app and an embedded panel can never disagree
about where focus is.
