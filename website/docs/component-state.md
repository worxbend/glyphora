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

The difference matches what those two kinds of state already do. Writing a `Signal`
invalidates the view that read it and schedules a redraw. Writing a `TextInputState`
does not, so a change made outside the event path still has to ask for its frame with
`requestRedraw()`.

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

## `keyed` for anything built in a loop

Elements built by a `map` all share one call site, so they would all share one set of
slots. `keyed` gives each iteration an identity of its own:

```scala
column(projects.get.map(project => keyed(project.id)(projectCard(project)))*)
```

Key by the item's own identity, never by its index. An index renumbers the survivors
when an item is removed, which hands each of them their former neighbour's state — the
classic symptom being a list where deleting the middle row makes the row below it
inherit the deleted one's expanded/collapsed setting.

Keys only have to be unique among siblings. A key is one step in a path, so the same
key under two different parents is two different places.

## Lifetime

A slot is created on the first frame that reaches its call and released at the end of
the first frame that does not. Hiding a subtree and showing it again therefore starts
it fresh — which is what a collapsed panel wants, and is not what you want for state
that must outlive its own disappearance. Put that in a `Signal` field on the app.

Each run has its own store, so running the same application object a second time starts
every component-local value over.

## Where they may be called

Only while a view is being evaluated, on the render thread. An event handler, a timer
body or a plain unit test has no place in the tree for the state to attach to, so
`useSignal`, `useState` and `keyed` all raise an `IllegalStateException` there rather
than quietly creating a slot nothing would ever reach again. Read state from a handler
through the value the view already captured:

```scala
def counter(using ReactiveScope, Theme): Element =
  val count = useSignal(0)                        // in the view: fine
  button(s"clicked ${count.get}")(count.update(_ + 1)) // in the handler: uses the captured signal
```
