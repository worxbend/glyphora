---
title: Component-local state
description: Write a reusable piece of view that owns its own state with useSignal, useState and keyed, instead of asking every caller to declare it.
---

# Reusable components with their own state

A `Signal` declared as a field on the app belongs to the app. A `ListState` or a
`TextInputState` belongs to whoever created it. Both have to be declared next to the
application object and handed into the factory call that needs them.

That is fine for state the whole application cares about, and awkward for a small,
reusable piece of view. A collapsible section needs a boolean nobody else will ever
read; a search box needs a `TextInputState` that is nobody else's business. Written
with app-level state, a helper has to take them as parameters:

```scala
// before: the caller declares state it does not care about, once per instance
private val detailsOpen = Signal(false)
private val notesOpen   = Signal(false)

def section(title: String, open: Signal[Boolean])(body: Element)(using ReactiveScope, Theme): Element =
  collapsible(title, open)(body)
```

## `useSignal` and `useState`

`useSignal` hands back a `Signal` that belongs to the **place in the view tree** where
the call was made. It is created on the first frame that reaches the call and handed
back, the same instance, on every later frame that reaches it again:

```scala
// after: the helper owns its state; the caller declares nothing
def section(title: String)(body: Element)(using ReactiveScope, Theme): Element =
  collapsible(title, useSignal(false))(body)

def view(using ReactiveScope, Theme): Element =
  column(
    section("Details")(detailsBody),   // two independent instances, two independent
    section("Notes")(notesBody),       // booleans, and no fields on the app at all
  )
```

`useState` is the same idea for a value that is not reactive — the caller-owned widget
states:

```scala
def searchBox(using ReactiveScope, Theme): Element =
  input(useState(TextInputState()))
```

Both kinds of state schedule their own frames. Writing a `Signal` invalidates the view
that read it. A widget state is `Versioned` — every mutation raises its `version` — and
the app watches the states it painted, so a `TextInputState` written on the render
thread outside any event handler is drawn on the next loop iteration too.

Both take their starting value by name, and evaluate it only on the frame that creates
the slot — an expensive initial value costs nothing on later frames.

These are not a second reactivity system. `useSignal` hands you the same `Signal` type
documented in [State & signals](./state-and-signals), with the same `get` / `peek` /
`set` / `update` and the same render-thread rules. All that changes is who owns it.

## The one rule: same calls, same order, every frame

A call site is identified by the order its hook call is reached in, so **a view must
reach the same hook calls in the same order on every frame.** A hook behind a condition
that flips renumbers everything after it, and state shifts from one call site to its
neighbour. Lift the call above the condition:

```scala
// wrong: the second call site changes number when `expanded` flips
if expanded.get then row(useSignal(0)) else spacer

// right: the hook is reached either way, and the branch only decides what is drawn
val rows = useSignal(0)
if expanded.get then row(rows) else spacer
```

## `each` for anything built in a loop

Elements built by a `map` all share one call site, so they would all share one set of
slots. `each` gives each iteration an identity of its own — the same key it stamps on
the row for focus is the scope its hooks live in:

```scala
column(each(projects.get)(_.id)(projectCard)*)
```

Key by the item's own identity, never by its index. An index renumbers the survivors
when an item is removed, which hands each of them their former neighbour's state — the
classic symptom being a list where deleting the middle row makes the row below it
inherit the deleted one's expanded/collapsed setting. Reorder the items and both the
highlight and every row's `useSignal`/`useState` travel with the item.

`keyed(key)(body)` is the same scope on its own, for a loop `each` does not fit — a
`map` that produces something other than one element per item, say — and for giving a
single reusable component two separately-stateful instances by hand:

```scala
column(projects.get.map(project => keyed(project.id)(projectCard(project)))*)
```

Keys only have to be unique among siblings. A key is one step in a path, so the same
key under two different parents is two different places. Before 0.20.0, `each` set only
the focus key and rows needed `keyed` as well — see the migration notes in
[Versioning](./versioning).

## `memo` for a subtree that is expensive to compose

`memo(deps*)(subtree)` builds the subtree once and hands the same `Element` back on
every later frame whose deps are `==` to the ones it was built with. It is a hook slot
like the others — it belongs to its call site and is swept one frame after the call
stops being reached — and it is for the part of a view that costs real time to
*compose* (a table over thousands of rows, a rendered markdown document) while the
frames around it change for other reasons:

```scala
def view(using ReactiveScope, Theme): Element =
  column(
    text(s"tick ${clock.get}"),
    memo(rows.get, sort.get)(dataTable(columns, rows.peek, widths, sortedBy(sort.peek))).fill,
  )
```

The deps are the whole contract. A signal read *inside* the memoised subtree is not
read on the frames that reuse the cache, so it cannot subscribe them: read every signal
the subtree depends on in the deps list, and `peek` it inside. The subtree must not own
hooks either — a `useSignal` inside it is only reached on frames that rebuild, and a
slot not reached is swept — so keep that state beside the `memo` call and pass it in
as a dep. Mutable widget states compare by identity, so a `ListState` as a dep never
triggers a rebuild by itself; put the value that changed in the list instead. In a loop,
`each` (or `keyed`) gives each row its own cache.

## State the framework keeps for you

Some state is not yours to hold at all. Whether an element is focused, hovered, pressed or
disabled is written by the framework on every frame, so a component never needs a
`useSignal` for "is the mouse over me". Ask for the look instead and let the framework
decide when it applies:

```scala
def toolButton(label: String, enabled: Boolean)(action: => Unit): Element =
  button(label)(action)
    .hoverStyle(Style.Default.bold)
    .pressedStyle(Style.Default.underline)
    .disabled(!enabled)
```

A component whose own look depends on where the pointer is inside it reads `ctx.local`
in `.onMouse(ctx => …)` — coordinates relative to the component, whatever layout did to it.
See [Mouse & focus](mouse.md#disable-a-control).

## Lifetime

A slot is created on the first frame that reaches its call and released at the end of
the first frame that does not. Hiding a subtree and showing it again therefore starts
it fresh — which is what a collapsed panel wants, and is not what you want for state
that must outlive its own disappearance. Put that in a `Signal` field on the app.

Each run has its own store, so running the same application object a second time starts
every component-local value over.

## Lifetime hooks

Four more hooks attach behaviour, not just values, to a call site. They follow the same
ordering rule and the same lifetime as `useSignal`.

`useEffect(deps*)(start)` runs `start` after the frame that reached it has been painted,
on the render thread. `start` returns a `Cancelable`; when a dep changes (`==`, element
by element) the previous one is cancelled and `start` runs again, and when the call stops
being reached — or the app stops — the last one is cancelled. No deps means "once":

```scala
def clock(using ReactiveScope, Theme): Element =
  val now = useSignal(LocalTime.now())
  useEffect()(Async.every(1.second)(now.set(LocalTime.now()))) // stops when the clock is hidden
  text(now.get.toString)
```

A start with nothing to release returns `() => ()`.

`useMemo(deps*)(compute)` keeps a derived value — a sorted copy, a parsed document —
until its deps change. It is the value-level sibling of `memo`, which caches a whole
subtree.

`useTask(deps*)(work)` runs `work` on a background worker and answers a `Loadable`:
`Loadable.Loading` until the result arrives on the render thread, then
`Loadable.Ready(value)` or `Loadable.Failed(error)`; the arrival schedules its own frame.
A dep change cancels the run in flight and starts a new one, and a node that disappears
before its task finishes never receives the result:

```scala
def details(id: Long)(using ReactiveScope, Theme): Element =
  useTask(id)(api.fetch(id)) match
    case Loadable.Loading       => spinner()
    case Loadable.Ready(record) => text(record.title)
    case Loadable.Failed(error) => text(error.getMessage)
```

The task belongs to a `TaskScope` the call site owns. On a host with no render loop — a
`Console.print`, a `Snapshot.render` — nothing is started and the answer stays `Loading`.

`useArea()` answers where this call's node was painted on the last frame, as
`Option[Rect]` — the same rect `boundsOf(key)` reports. "This node" is the one carrying
the key of the innermost `each` row or `keyed` scope: `each` keys the row for you; inside
`keyed(k)`, give the element `.key(k)`. Outside any keyed scope it answers `None`. It is
`None` on the first frame, and when the node lands somewhere other than what it last
answered, one more frame is drawn so the view settles.

## Where they may be called

Only while a view is being evaluated, on the render thread. An event handler, a timer
body or a plain unit test has no place in the tree for the state to attach to, so
every hook — `useSignal`, `useState`, `useEffect`, `useMemo`, `useTask`, `useArea` — and
`keyed` raise an `IllegalStateException` there rather
than quietly creating a slot nothing would ever reach again. Read state from a handler
through the value the view already captured:

```scala
def counter(using ReactiveScope, Theme): Element =
  val count = useSignal(0)                        // in the view: fine
  button(s"clicked ${count.get}")(count.update(_ + 1)) // in the handler: uses the captured signal
```

## Write your own container

`row`, `column`, `grid`, `flow` and `dock` cover most layouts. When none of them is the
shape you need — a radial menu, a diagonal, a node graph, a masonry wall — write the
placement yourself with `container(…)` and keep everything else the framework's:

```scala
def diagonal(children: Element*): Element =
  container(children*)((area, kids) =>
    kids.map { kid =>
      val width = kid.intrinsicWidth(1).getOrElse(10)
      Rect(area.x + 6 * kid.index, area.y + kid.index, width, 1)
    }
  )

diagonal(button("one")(a()), button("two")(b()), button("three")(c()))
```

Every frame, the placement function is handed the container's area and one `ChildInfo`
per child, and answers one `Rect` per child, in child order. A `ChildInfo` says:

- `index` — where the child sits in the list;
- `key` — the name it was given with `.key(…)`;
- `constraint` — the `.length(n)`, `.fill`, `.auto`, … it carries, for a layout that
  wants to honour one (`alignSelf` likewise);
- `intrinsicWidth(height)` / `intrinsicHeight(width)` — how big its content measures, in
  display columns and rows, `None` when it cannot say. These are the measurements the
  built-in containers lay out by.

Each child is painted into its rect, cut to the container's area; a child the answer
leaves out is not painted. The optional second argument is a `Widget` painted across the
whole area first, under the children — a background, a frame, grid lines:

```scala
container(nodes*)(placeNodes, chrome = Some(edgesWidget))
```

What you do not write is the part that is easy to get wrong. The children are ordinary
children of the tree, so the Tab order is child order, a click lands on whichever child
was painted under it, `boundsOf(key)` answers where each one went, and themes, hover and
disabled states reach them as they reach any other node. The placement function runs on
the render thread during the paint and should be a pure function of its arguments — it
is called again on every frame. A `container` cannot measure itself (its size depends on
a function it cannot invert), so it fills its slot; give it `.length(n)` to fix it.
