---
title: Responsive layouts
description: React to terminal resizes in glyphora — branch the view on terminalSize, swap whole components at breakpoints, and test every band without a PTY.
---

# Responsive layouts

Constraints already handle *reflow*: a `percent(30)` sidebar keeps its share of
whatever width it is given, and every resize repaints the frame against the new
area. That covers stretching. It does not cover the case where a layout stops
making sense — a three-pane dashboard at 140 columns is unreadable at 50, and no
amount of proportional shrinking fixes it. At some width you want *different
components*, not smaller ones.

> **Core idea:** the terminal's size is reactive state. Read it in `view` and the
> view re-evaluates on every resize, exactly as it does for any other signal.

## Branch the whole view on size

`terminalSize` is a reactive read available inside `view`. Reading it subscribes
the view to resizes, so a branch on it re-evaluates automatically:

```scala
def view(using ReactiveScope, Theme): Element =
  if terminalSize.width < 80 then
    column(
      topBar("deployctl").length(1),
      tabbedContent("Services" -> serviceList, "Details" -> details)(activeTab).fill,
    )
  else
    column(
      topBar("deployctl").length(1),
      row(
        panel("Services")(serviceList).percent(30),
        panel("Details")(details).fill,
      ).fill,
    )
```

Wide, that is a sidebar beside a detail pane. Narrow, the same two panes become
tabs. These are different widget trees, not the same tree with different numbers
— which is the point.

Focus survives the swap. The focus pass runs over whatever tree `view` returned,
so focusables that only exist in one branch join the tab order only while that
branch is live, and the tracker clamps the focused index into the new range. Give
elements a `key` if you want focus to *follow* a specific control across a swap:

```scala
input(query, placeholder = "search").key("search")
```

## Name the bands

Raw column counts scattered through a view get hard to keep consistent.
`Breakpoint` buckets width into four bands, and `breakpoint` reads the current
one reactively:

| Band | Width | Typical |
| --- | --- | --- |
| `XSmall` | under 60 | a split pane, a phone-sized SSH session |
| `Small` | 60–79 | a narrow window |
| `Medium` | 80–119 | the classic terminal |
| `Large` | 120 and up | a maximized window |

```scala
def view(using ReactiveScope, Theme): Element =
  breakpoint match
    case Breakpoint.XSmall => column(summary)
    case Breakpoint.Small  => column(summary, recentEvents)
    case _                 => row(summary.percent(35), recentEvents.fill)
```

Bands are cumulative, so `atLeast` and `isBelow` read the way you would say it:

```scala
val showSidebar = breakpoint.atLeast(Breakpoint.Medium)
```

The bands are width-only on purpose. Short-but-wide terminals want a different
decision than narrow ones, and folding both axes into one label would hide that;
when height is what matters, branch on `terminalSize.height` directly.

## Branch deep in the tree

Threading the size down through every builder gets tedious once the decision
belongs to one panel rather than the whole screen. `responsive` puts the branch
where the content is:

```scala
panel("Throughput")(
  responsive {
    case size if size.width < 60 => sparkline(samples)
    case _                       => chart(datasets, xBounds = Bounds(0.0, 60.0), yBounds = Bounds(0.0, 100.0))
  }
)
```

`responsive` takes a `Size => Element`. It is a **media query, not a container
query**: the size it receives is the whole terminal's, the same value
`terminalSize` reports, regardless of how deeply the node is nested or how narrow
its own allotted area is. When the decision depends on the room the node itself
was given, use `responsive.local` — the container query below.

The branch is resolved before the focus pass, so whatever it returns is an
ordinary part of the tree: its focusables take Tab stops, its handlers receive
keys, and clicks hit-test into it.

The node is otherwise transparent. Anything you set on it applies as it would on
a `column` wrapping the same content:

```scala
responsive(build).length(3).onKeyEvent(handler)
```

A constraint set on the node wins; with none set, the node claims whatever the
selected branch claims.

## Branch on the node's own area

A sidebar component should not care how wide the terminal is; it cares how wide
the sidebar is. `responsive.local` is a **container query**: its builder receives
the node's own area, so the same component lays itself out differently in a
28-column sidebar and in a full-width page, at any terminal size:

```scala
def services(using Theme): Element =
  responsive.local {
    case size if size.width < 40 => column(entries.map(e => text(e.name))*)
    case _                       => column(entries.map(e => row(text(e.name), text(e.status), text(e.uptime)))*)
  }

row(panel("Services")(services).length(30), detail.fill)
```

The area is found by a layout pre-pass: the frame is laid out once with the
query unresolved, the node records the rectangle it was given, and the branch is
built for it — still before the focus pass, so the branch's focusables take Tab
stops on the very first frame, exactly as with `responsive`. A query nested
inside another query's branch takes one more pass. The pre-pass paints only the
containers on the way down to each query; every other subtree is laid out but not
painted, so a widget whose render adjusts its own state (a scroll offset clamped to
its content, say) renders once per frame, not twice. A frame with no
`responsive.local` in it pays nothing for the feature.

Because the area must be known before the branch is chosen, the node's size can
never depend on the branch: it takes what its own constraint says (`.length(n)`,
`.percent(p)`, …) or fills its slot, and inside a measuring container (a
`scrollView`, an `.auto` slot) give it a `.length(n)` — it does not measure
itself by its branch the way a `responsive` node does. A query the pre-pass never
reaches, such as one scrolled outside a scroll view's window, builds when it is
drawn instead; its focusables are inert until it is on screen.

The plain `responsive` keeps its media-query meaning, so the two combine: branch
the screen on the terminal and each component on its own slot.

## React to the resize itself

Some work is triggered *by* a resize rather than expressed as a layout: clamping
a scroll offset, re-requesting a differently sized page of rows. Override
`onResize`:

```scala
override def onResize(size: Size): Unit =
  rowsPerPage.set(math.max(1, size.height - 4))
```

It runs on the render thread, before the frame that reflects the new size, and
`terminalSize` already holds the new value by then. You do not need it to make
the view respond to size — that happens on its own.

## Test every band

`Pilot.resize` drives the same path a real `SIGWINCH` does, so each breakpoint is
an ordinary assertion:

```scala
val app = DashboardApp()
Pilot.using(Size(100, 20))(backend => app.runWith(backend)) { pilot =>
  pilot.waitForIdle()
  assert(pilot.screenText.contains("Services"))

  pilot.resize(50, 20).waitForIdle()
  assert(pilot.screenText.contains("Overview")) // tabs replaced the sidebar
}
```

Worth covering in tests, because they are the cases a manual pass misses:

- each band you branch on, plus both sides of every threshold;
- a resize *while* something is focused — the tab order changed shape underneath
  it;
- a resize while a list is scrolled, if `onResize` clamps the offset;
- the degenerate sizes, `1x1` and a one-row terminal, which no widget may crash
  on.

## Related

- [Layout & style](./layout-and-style) — constraints, the reflow half of the story.
- [App shell](./app-shell) — the chrome presets these examples wrap content in.
- [Testing](./testing) — `Pilot`, golden frames, and the rest of the harness.
