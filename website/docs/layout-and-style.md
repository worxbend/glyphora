---
title: Layout & style
description: Compose rows, columns, constraints, alignment, borders, and reusable visual styles in glyphora.
---

# Layout & style

Terminal layout is geometry, not pixels. glyphora divides the available cell grid
with constraints, then renders each child inside the rectangle it receives. Once
that rule clicks, layouts stay predictable through resizes and nested panels.

> **Core idea:** a `row` divides width, a `column` divides height, and a constraint
> on each child says how much of that axis it claims.

## Start with rows and columns

```scala
column(
  topBar("deployctl").length(1),
  row(
    panel("Services")(serviceList).length(28),
    panel("Details")(details).fill,
  ).fill,
  statusBar(bindings).length(1),
)
```

Read it from the outside in:

- the outer `column` reserves one row for the top bar and one for the status bar;
- its middle row uses `.fill`, so it receives all remaining height;
- inside that row, the service panel receives 28 columns and details fills the rest.

`panel`, `text`, inputs, and other elements have sensible preferred sizes. Add an
explicit constraint only when the surrounding composition needs one.

## Choose a constraint

| Extension | Meaning | Typical use |
|---|---|---|
| `.length(12)` | exactly 12 cells | toolbars, sidebars, single-line regions |
| `.percent(40)` | 40% of available axis | balanced master/detail layouts |
| `.ratio(1, 3)` | exactly a third of the axis | splits a whole percentage cannot say |
| `.fill` | share all space left after fixed constraints | main content |
| `.fill(2)` | take twice the remaining share of `.fill(1)` | weighted columns |
| `.minSize(8)` | at least 8 cells when solving | important compact content |
| `.maxSize(30)` | no more than 30 cells | readable text or narrow controls |
| `.auto` | exactly what the content measures | labels, badges, a pane as tall as its text |

A percentage takes its share and leaves the rest as free space, which `flex` then
positions — `row(a.percent(20), b.percent(60))` is 20/60 with a fifth of the axis
unused, not 30/70. Percentages that *do* claim the whole axis absorb the rounding
remainder between them, so `.percent(33)` three times fills the container exactly at
every width rather than leaving a stray column at the right edge.

Weighted fills make proportions clear without hardcoding terminal width:

```scala
row(
  panel("Queue")(queue).fill(1),
  panel("Timeline")(timeline).fill(2),
  panel("Health")(health).fill(1),
).gap(1)
```

### Size to the content with `.auto`

`.auto` asks the child itself: in a `column` it gets the rows its content measures, in a
`row` the columns. Text is measured in display columns, so a CJK label or an emoji is
counted at its real width. A child that cannot measure itself — a chart that fills
whatever it is given — is laid out as a `.fill` instead, never as zero cells.

```scala
row(
  text("Status:").auto,          // exactly 7 columns
  text(status).fill,
)
```

### Bound a size: `.minSize(…).maxSize(…).fill`

On their own, `.minSize(n)` and `.maxSize(n)` are a floor and a cap. Combined with a
size — in either order — they *bound* that size instead:

```scala
row(
  panel("Sidebar")(nav).minSize(20).maxSize(60).fill, // a fill, but 20..60 columns
  panel("Main")(body).fill,
)
```

A bounded child that hits its ceiling stops there and its neighbours share what it
refused; one pushed below its floor is held at the floor. In core terms this is
`Constraint.Bounded(min, max, base)`, and `Constraint.Auto` is the measured size;
`Constraint` gains both cases in 0.20.0, so a `match` over it needs a wildcard.

### Align children across the axis

Every child of a `row` or `column` spans the container's whole cross axis by default —
a button in a column is as wide as the column. `.alignItems(…)` on the container and
`.alignSelf(…)` on a child place it by its measured size instead:

```scala
column(
  text("Welcome"),
  button("Continue")(next()),
  text("v1.4").alignSelf(CrossAlign.End),
).alignItems(CrossAlign.Center)
```

The modes are `CrossAlign.Stretch` (the default), `Start`, `Center` and `End`. A child
that cannot say how wide (or, in a row, how tall) it is keeps stretching.

## Lay out a dashboard on a grid

`grid(cols, rows, gap)(children*)` solves both axes with the same constraints rows and
columns use. Children say where they go with `.at(row, col)` and how many tracks they
cover with `.span(rows, cols)`; the rest flow into the next free cells, in reading order.

```scala
grid(cols = Seq(Constraint.Fill(1), Constraint.Length(30)), rows = Seq(Constraint.Length(3), Constraint.Fill(1)), gap = 1)(
  topBar.span(cols = 2),
  main.at(1, 0),
  sidebar.at(1, 1),
)
```

The same layout can be written as a template of named areas — `.areas(…)` on the
grid, `.area(name)` on each child — where a repeated name spans cells and `.` leaves one
empty:

```scala
grid(cols, rows, gap = 1)(
  topBar.area("top"),
  main.area("main"),
  sidebar.area("side"),
).areas("top top", "main side")
```

A template whose rows differ in length, or where a name does not cover one filled
rectangle, throws where it is written. A `Constraint.Auto` track is as big as the
children sitting in that track alone; `.gaps(columnGap, rowGap)` sets the two gaps
separately.

## Wrap chips and tags with `flow`

`flow(gap, rowGap)(children*)` lays children left to right at their natural widths and
wraps when the next one does not fit — tags, badges, a bar of buttons:

```scala
flow(gap = 1)(tags.map(tag => badge(tag))*)
```

Widths are display widths, so a CJK tag takes two columns per character. A child that
cannot say how wide it is takes a line to itself; `.length(n)` on a child fixes its width.

## Dock panes to the edges

`dock(top = …, bottom = …, left = …, right = …)(center)` is sugar for the usual
`column(top, row(left, center, right), bottom)`: top and bottom span the width, left and
right sit beside the centre, and the centre takes what is left. Docked panes are `.auto`
unless they carry a constraint of their own, and any edge can be left out.

```scala
dock(top = topBar("deployctl"), bottom = statusBar(bindings), left = nav.length(24))(details)
```

## Position content deliberately

`centered(width, height)` is convenient for dialogs and focused empty states:

```scala
centered(42, 9) {
  panel("No deployments")(
    text("Connect a cluster to begin.").bold,
    text("Press c to configure one.").dim,
  ).rounded
}
```

### Align one row of text on its own

`Alignment` also travels *inside* a block of text: a `line(...)` can say where that single
row sits, and it wins over whatever the widget drawing it was told to do. That is what a
label-on-the-left, total-on-the-right row wants, with no hand-counted padding between them:

```scala
column(
  line(span("Deployments")).leftAligned,
  line(span("42 healthy"), span("  ")).rightAligned,
  line(span("updated 12s ago")).centered,
)
```

The builders are `leftAligned`, `centered`, `rightAligned`, and `aligned(Alignment.Right)`
when the choice is computed rather than written out. A line that sets none of them keeps
the old behaviour: it is placed the way the surrounding widget was told to place it. The
offsets are measured in terminal columns via `CharWidth`, so a CJK or emoji line lands
where it looks like it should rather than where its character count would put it.

For independent horizontal and vertical alignment, use `place`:

```scala
place(
  width = 36,
  height = 5,
  horizontal = Alignment.Right,     // right edge of the area
  vertical = VerticalAlignment.Top, // top of the area
)(toastPreview)
```

Each axis is named in its own vocabulary. `horizontal` takes `Alignment` — the same
`Left`/`Center`/`Right` enum that positions a `Block` title and a `Paragraph`'s text — and
`vertical` takes `VerticalAlignment`, whose cases are `Top`, `Middle`, and `Bottom`.
`Flex` — `Start`, `End`, `Center`, `SpaceBetween`, … — is a different question and stays:
it says how leftover space is shared out among *many* children, not where *one* block
sits.

App-oriented presets cover frequent shapes:

```scala
sidebarLayout(navigation, content, paneWidth = 26)
masterDetail(projectList, projectDetails, masterWidth = 32)
```

## Overlay at an exact offset

`centered` and `place` both *align* content inside the area they are handed. When you
already know the exact cell an overlay belongs at — beside the row the pointer is over,
under the control that has focus — use `positioned` instead and paint it over the base
with `layers`:

```scala
val hoveredRow = Signal(0) // whichever row your app already tracks

def view(using ReactiveScope, Theme): Element =
  layers(
    panel("Deployments")(deploymentTable),
    positioned(dx = 34, dy = hoveredRow.get + 2, width = 30, height = 3)(
      tooltip("Rolled back 4 minutes ago by ana."),
    ),
  )
```

Three things are worth knowing before you reach for it:

- `dx` and `dy` are offsets from the **top-left corner of the area this element is
  handed**, not absolute screen coordinates. A `positioned` nested inside a panel is
  placed relative to that panel, so it keeps working when the panel moves.
- `width` and `height` size the box the content renders into, and the box is **clipped**
  to the surrounding area. An offset that runs off the right edge draws the part that
  fits and nothing more: it never spills onto a neighbouring pane and it never throws.
- It only paints. It claims no space from the row or column around it, which is exactly
  why it belongs inside `layers` over a base element rather than beside one.

This is the mechanism the toolkit's own overlays are built from — a toast is a `notice`
inside a `positioned` box, which is how the stack can sit against the right edge with
every row a different width.

## Escaping a pane: `portal(…)`

Clipping is the right behaviour for an overlay that belongs to its pane, and the wrong
behaviour for a dropdown. A context menu opened from a table row inside a bordered
`panel` is taller than the space left below that row, so a `positioned` menu is cut off
at the panel's border and the user sees half a menu.

`portal(…)` takes the same four numbers as `positioned` and the same offset origin, but
instead of painting inside the pane it hands the box to the frame root, which draws it
after the entire tree. The result is clipped only by the terminal, so it paints over the
border, over a neighbouring pane, and over anything else the frame already drew:

```scala
val menuState = MenuState()
val menuOpen  = Signal(false)

def view(using ReactiveScope, Theme): Element =
  panel("Deployments")(
    column(
      deploymentTable,
      if menuOpen.get then
        portal(dx = 12, dy = 4, width = 22, height = 6)(
          layers(clear(), menu(menuEntries, menuState)(runAction)),
        )
      else spacer.length(0),
    ),
  )
```

Which of the three overlay primitives to reach for:

- `positioned` — the overlay belongs to its pane and should be trimmed at its edge.
- `portal` — the overlay is anchored *inside* a pane but must spill past it.
- `layers` — the overlay covers the whole screen anyway (a modal dialog, a splash).

Two details are worth knowing. Portals paint in the order they were rendered, all of them
above the tree, and a portal inside portal content is drawn above the portal containing
it — so a submenu lands over its menu. And the content is still an ordinary child for
event routing, focus order and measurement: a `menu` inside a portal is clicked and
tabbed to exactly where it is drawn, with no extra wiring.

`portal` only escapes its pane inside a running `TuiApp`, because the frame root is what
draws the queued content. Rendering the element by hand in a construction test falls back
to `positioned`'s clipped behaviour rather than drawing nothing.

## Pad inside a border

A `panel` reserves blank cells between its border and its children:

```scala
panel("Summary")(summaryLines).padding(1)                        // the usual case
panel("Summary")(summaryLines).padded(Padding.horizontal(2))     // columns only
panel("Summary")(summaryLines).padded(Padding.left(2))           // one side only
panel("Summary")(summaryLines)
  .padded(Padding(left = 4, right = 1, top = 0, bottom = 0))     // each side named
```

`.padding(n)` is deliberately *not* `n` cells on all four sides. A terminal cell is about
twice as tall as it is wide, so one blank row eats about twice the screen one blank
column does, and a 24-row terminal cannot spare a row top and bottom as cheaply as an
80-column one can spare a column. `.padding(n)` therefore reserves `n` rows above and
below and `2 * n` columns either side — `Padding.proportional(n)` — which is what reads
as an even margin. `Padding.zero`, `.uniform`, `.horizontal`, `.vertical` and
`.symmetric(x, y)` name the other shapes, and `.left`, `.right`, `.top` and `.bottom`
pad a single side — worth using, because the case class takes `(left, right, top,
bottom)` rather than the CSS order, so a positional `Padding(2, 0, 0, 0)` is easy to
write for the wrong side.

Padding is charged to the panel's measured height too, so a padded panel inside a
`scrollView` still reports the rows it really occupies.

## Decorate any element

Five decorators work on every element, not only on `panel`:

```scala
text("ready").border()                                   // a frame in the theme's border style
text("ready").border(BorderType.Rounded, Style.fg(Color.Green)) // glyphs and frame style
column(fields*).padding(1)                               // 1 row above/below, 2 columns either side
column(fields*).padding(Padding.symmetric(2, 1))         // per side, as on a panel
column(fields*).padding(1, 2)                            // rows, then columns — the CSS shorthand
statusLine.background(Style.bg(Color.Blue))              // a solid surface under the content
dialogBody.border().shadow                               // a dimmed drop shadow, down and right
widget(handDrawn).clip                                   // nothing it paints leaves its area
```

Each wraps the element in one decorator node whose child is the element itself, so the
element keeps its focus, its key, its click handling and its place in the Tab order. The
decorator's cells are added to the measurement (`text("hi").border()` is four columns by
three rows), and a `.shadow` is paid for *inside* the area, like `Block`'s — the element
gives up one column and one row to it. `.shadow(Shadow.shade(ShadowFill.LightShade))`
picks another fill for terminals that ignore dim.

Layout extensions written before a decorator move onto it, because they were about where
the element goes and the decorated box now goes there: `text("ab").length(4).border()`
is a four-column box. The fluent style builders follow the 0.20.0 split — `.fg(...)`,
`.bg(...)`, `.styled(...)` after a decorator are the *content* style, layered under what
the element drew, and a border's own colour is the style passed to `.border(...)`.
Decorators compose in the order written, innermost first:
`text("x").padding(0, 1).border().shadow` pads, then frames, then casts.

On a `panel`, `.padding(...)` pads *inside* its border, as it always has, and takes the
same three forms.

## Choose which sides have a border, and where the captions sit

A `panel` frames all four sides by default. `.borders(sides)` picks which ones are
actually drawn, and `.borderless` drops the frame while keeping the padding, the flex
layout and the children:

```scala
panel("Filters")(filterList).borders(Borders.Right)      // a column separator
panel(header).borders(Borders.Top | Borders.Bottom)      // a horizontal band
panel("Group")(members).borderless                       // a captioned group, no frame
```

A `.borderless` panel that was only there for its padding is better written as the
padding itself: `column(members*).padding(1)` — see
[Decorate any element](#decorate-any-element).

`Borders.Top`, `.Right`, `.Bottom`, `.Left`, `.All` and `.None` combine with `|`. Corner
glyphs appear only where two drawn sides meet, so a half-framed panel has no dangling
corners, and the measured height charges only the sides that are drawn — a
`Borders.Top` panel reserves one row of chrome, not two.

Captions live in border cells, so they never cost a content row and never widen the box
(an over-long one is clipped). The top caption starts at the left and the bottom one at
the right; `.titleAligned(...)` and `.titleBottomAligned(...)` move them:

```scala
panel("Settings")(body).titleAligned(Alignment.Center)
panel("Queue")(body).titleBottom("3 pending").titleBottomAligned(Alignment.Left)
```

`title` and `titleBottom` take a plain `String` and paint it in one style. For a caption
made of differently-styled runs — or simply a third and fourth caption — use `.titles`,
which takes `BlockTitle` values, and a `BlockTitle` carries a `Line`:

```scala
panel("build")(body).titles(
  BlockTitle.top(Line(Seq("ok ".styled(identity), "2 warnings".styled(_.fg(Color.Yellow))))),
)
```

Captions sharing a border *and* an alignment are drawn as one run separated by a single
space, in the order given, with `title`/`titleBottom` first.

### Put an element in the border: `.header` and `.footer`

A caption is a string. When the thing in the border is an element — a `badge` with a
count, a `spinner` while the pane loads, a `button` — put it in a slot:

```scala
panel("Logs")(logLines).footer(badge("3"))                       // bottom border, right
panel("Jobs")(jobRows).header(spinner("loading"))           // top border, right
panel("Queue")(rows).footer(button("retry")(retry()), Alignment.Left)
```

A slot is written into its border row like a caption: it costs no content row, never
widens the box, and is cut to the run between the corners — by display width, so a CJK
label is cut on a whole character and the corners are never overwritten. It is as wide
as it measures, and placed along the row by its alignment (right unless told otherwise).
A slot is still a child of the panel, so a `button` in the footer takes focus and clicks
like any other control. A slot on a side with no border drawn (`.borders(...)`) has no
row to be written into, so it is not painted and is not a focus stop either.

## Wrap and align text

`text(...)` cuts a line off at the right edge of its area by default, and starts every
line at the left edge. Two families of builders change that:

```scala
column(
  text("A long paragraph of prose that should fill the pane.").wrapped,
  text("Centered heading").centered,
  text("42").rightAligned,
)
```

- `.wrapped` breaks an over-long line onto further rows; `.clipped` restores the
  default. Breaks happen at grapheme-cluster boundaries, so a wide CJK character, an
  emoji, or a letter with a combining accent is never split down the middle.
- `.centered`, `.rightAligned`, and `.aligned(Alignment.Left)` position each line
  inside the area's width.

Wrapping also changes what the element claims from its container. A clipping text
claims exactly the box its longest line measures; a wrapping one claims the full width
it is offered, and its height is then measured from that width — which is what lets a
wrapped paragraph size a `scrollView` or an auto-height container correctly. For text
taller than the space available, put it in a `scrollView`: the element itself paints
what fits and nothing more.

## Distribute leftover space

Rows and columns support flex-like packing when their children do not consume all
available space:

```scala
row(
  button("Cancel", cancel),
  button("Deploy", deploy),
).gap(2).flexEnd
```

Available modes are `.center`, `.spaceBetween`, `.spaceAround`, `.spaceEvenly`, and
`.flexEnd`, plus `.flex(mode)` and `.gap(cells)`. They matter only when space
remains; a `.fill` child intentionally consumes that space first.

These helpers exist only on `row`, `column`, and `panel` — the containers that lay
children out along an axis. (A `panel` stacks its children with the same widget a
`column` does, so `panel("Logs")(a, b).gap(1)` spaces them inside the border without a
`column` in between.) Writing `text("x").center` is a compile error rather than a call
that quietly does nothing. `.rounded` and `.doubleBorder` are typed the same way: they
exist only on `panel`.

## Give list rows a stable identity

Focus is positional by default. The framework remembers "focus is on the third
focusable"; insert a row at the top of a list and the third focusable is now a different
row, so the highlight appears to jump. `each(items)(keyOf)(render)` renders one element
per item and stamps each with a `.key` derived from the item itself:

```scala
// positional — inserting a process at the top moves the highlight to a different process
column(processes.map(processRow)*)

// keyed — the highlight stays on the process it was on
column(each(processes)(_.pid.toString)(processRow)*)
```

It returns a plain `Seq[Element]`, so it splices into whichever container you want:
`column(each(…)*)`, `row(each(…)*)`, `panel("Processes")(each(…)*)`.

Keys must be unique within one frame, and must identify the *item* — a database id, a
process id, a file path — not its index, which is the positional identity this replaces.
Two children sharing a key re-anchor focus to the first of them. When two lists in one
view could derive the same keys, `each(items, "left")(…)(…)` prefixes them.
## Paint the gaps a split leaves

`Layout.split(area)` hands back one rectangle per constraint. `splitWithSpacers(area)`
hands back those same rectangles *and* the empty rectangles between and around them —
one more spacer than there are segments, in the order "space before the first segment,
space between each pair, space after the last":

```scala
import io.worxbend.tui.core.*
import io.worxbend.tui.widgets.{Block, Borders, Rule}

val (panes, gaps) = Layout.horizontal(0.5, 0.5).withSpacing(1).splitWithSpacers(buffer.area)
Block(borders = Borders.All).render(panes(0), buffer)
Block(borders = Borders.All).render(panes(1), buffer)
// gaps(1) is exactly the one-cell channel `spacing` opened between the two panes
Rule(orientation = Direction.Vertical).render(gaps(1), buffer)
```

This is the primitive behind a draggable splitter, a ruled grid, or a drop shadow: the
solver already worked out where the gap is, so a caller no longer redoes that arithmetic
and no longer risks disagreeing with it. The gaps come from the segments *after* they
were placed and clipped, so a layout whose constraints overrun the area reports the room
that is really on screen rather than the room it wished for.

A zero-extent spacer is normal, not an error — it is what adjacent segments with no
spacing produce, and every widget renders an empty rectangle as nothing, so there is
nothing to special-case. A layout with no constraints returns no segments and no
spacers.

## Share a border between two blocks

Two bordered blocks placed next to each other draw two border lines that sit side by
side — a doubled-up seam down the middle. The usual fix in a terminal UI is to let them
*share* one column, so the right block's left border lands exactly on the left block's
right border. That is what `Spacing.Overlap` does:

```scala
import io.worxbend.tui.core.*
import io.worxbend.tui.widgets.{Block, Borders}

val (left, right) = Layout
  .horizontal(Constraint.Length(10), Constraint.Length(10))
  .spaced(Spacing.Overlap(1))
  .split2(buffer.area)

Block(borders = Borders.All).render(left, buffer)
Block(borders = Borders.All).render(right, buffer)
```

Two ten-wide blocks then occupy nineteen columns rather than twenty, and column 9 holds
one vertical line instead of two.

`Spacing` has two cases and no sign convention: `Gap(n)` inserts `n` empty cells between
segments — the same thing `Layout`'s `spacing` field has always done — and `Overlap(n)`
pulls them together by `n`. A negative number in either case is clamped to zero, because
the direction is carried by the case you picked, never by the sign of the number. That
also means `Layout(…, spacing = -2)` still means "no spacing", as it always has; asking
for an overlap is something you have to say out loud.

An overlap can never place a segment outside the area, however deep it is or whichever
`Flex` mode is in play. And `splitWithSpacers` reports every inner spacer as empty under
an overlap: the shared cells belong to both neighbours, so they are not a gap.

## Style elements fluently

Styling calls return a new element, so they chain naturally and never mutate a
shared widget:

```scala
text("production")
  .bold
  .fg(Color.White)
  .bg(Color.Red)

panel("Audit log")(logView).rounded
panel("Danger zone")(dangerView).doubleBorder.frameStyle(_.withFg(Color.Red))
panel("Now playing")(nowPlaying).thick
panel("Preference")(body).borderType(userChoice)
```

### Content style and frame style

`style` means the same thing on every node: the style its *content* is drawn in. On a
`text` that is the text, on a `list` the body, and on a `panel` the children and the
blank cells around them, layered *under* whatever the children set so a child's own
colour still wins. A container's border is a separate style, `frameStyle`:

```scala
panel("Disk")(usage)
  .frameStyle(_.withFg(Color.Red))   // the border and, without a titleStyle, the caption
  .titleStyle(_.bold)                // the caption on its own
  .style(Style.fg(Color.Green))      // the content: children and the inner area
```

`.frameStyle(style)` replaces the theme's `border`; `.frameStyle(_.bold)` edits it.
`.style(s)` replaces an element's own style, and `.fg`, `.bold`, `.styled(...)` edit
it, on a panel exactly as on a text. Before 0.20.0 the style builders on a panel
recoloured its border; a call written that way now recolours the content, so move it to
`.frameStyle(...)`.

Four border glyph sets exist: the square default, `.rounded` (`╭─╮`), `.doubleBorder`
(`╔═╗`), and `.thick` (`┏━┓`), which gives the same emphasis as a double border in one
heavy stroke rather than two thin ones. `.borderType(glyphs)` takes a `BorderType`
value, for code that picks one from a theme setting or a `match` rather than writing it
out. All four sets are one column wide, so the choice never changes how much room is
left inside the box.

The built-in modifiers are `.bold`, `.dim`, `.italic`, `.underline`, `.reverse`,
`.blink`, `.hidden`, `.crossedOut`, `.fg(...)`, and `.bg(...)`. Each has a negative
form — `.notBold`, `.notDim`, `.notItalic`, `.notUnderline`, `.notReverse`, `.notBlink`,
`.notHidden`, `.notCrossedOut`, `.withoutFg`, `.withoutBg` — which is how one element
opts out of something an ancestor set. Use `.styled` when you need a complete `Style`
transformation.

Two of the attributes come with caveats. `.blink` is widely ignored, and switched off
outright by some terminals and some accessibility settings, so never carry meaning in it
alone. `.hidden` paints text in the background colour so it occupies its cells but cannot
be read; it is not a security measure, because the characters are still in the terminal's
buffer and are still copied by a selection.

`Style` itself carries two more text attributes that no element shortcut exposes:
`.blink` and `.rapidBlink`. They are separate escape codes (SGR 5 and SGR 6), not two
names for one thing, which is why both exist — but terminal support is thin, most
emulators render them identically, and several ignore both. Treat blinking as a hint and
never as the thing that carries the meaning.

`.fg`/`.bg` name the same two things `Style.withFg`/`withBg` do, and the same two things
every other terminal toolkit calls them. On a `Style` the builders keep the `with`
prefix, because `Style` is a case class whose fields are already called `fg` and `bg` and
a field and a method cannot share a name.

A `Style` also remembers what it was asked to turn *off*. `Style.Default.notBold` is
not the same value as `Style.Default`: it carries "bold is cleared", so layering it
over a bold base removes the bold instead of being ignored. That is what makes one
element opt out of an inherited style:

```scala
withStyle(_.bold) {
  column(
    text("headline"),                  // bold, inherited
    text("footnote").styled(_.notBold), // opts out
  )
}
```

The `not*` builders and the plain ones are last-call-wins in both directions:
`.notBold.bold` is bold, `.bold.notBold` is not.

`Modifiers.All` is the whole set of text attributes in one value, so
`style.without(Modifiers.All)` clears every attribute without naming the eight of them.
The bitset operators are `a | b` (the flags set in either), `a.without(b)` (the flags in
`a` that are not in `b`), and `a & b` (the flags set in both — for asking what two styles
agree on).

To drop *everything* an inherited style said, patch `Style.Reset` on top of it, or call
`.reset` on the style, which is the same thing:

```scala
val themed = Style.Default.withFg(Color.Cyan).bold
themed.patch(Style.Reset) // default-colored, unbold text
themed.patch(Style.Default) // unchanged: Default is silent about every field
```

`Style.Reset` sets the foreground, background and underline color to `Color.Reset` (the
terminal default) and records every text attribute as cleared, so all of that survives
further layering. The two fields it leaves alone are `underlineStyle` and `link`, because
`patch` reads `UnderlineStyle.None` and an absent link as "silent" rather than "off" —
call `withUnderlineStyle(UnderlineStyle.None)` yourself to remove a curly underline.

## Style factories

For the common case of a style that sets one or two things, the `Style` companion has
short factories. Each is exactly the builder chain beside it:

| Factory | The same value as |
| --- | --- |
| `Style.fg(Color.Green)` | `Style.Default.withFg(Color.Green)` |
| `Style.bg(Color.Black)` | `Style.Default.withBg(Color.Black)` |
| `Style.of(Color.Green, Color.Black)` | `Style.Default.withFg(Color.Green).withBg(Color.Black)` |
| `Style.mods(Modifiers.Bold)` | `Style.Default.bold` |

They are plain methods rather than implicit conversions from `Color`, so a call site that
passes a style always says the word `Style`.

Apply a base style to a whole subtree with `withStyle`:

```scala
withStyle(_.withFg(Color.Cyan)) {
  column(
    text("connected").bold,
    text("latency 12 ms"),
  )
}
```

Descendants can still add or override their own style, and nested `withStyle` calls
layer inner over outer. A raw `widget(...)` leaf takes the style *under* what its widget
draws: once the widget has painted, every cell of its area gets the style's fields the
cell left unset, so `widget(myGauge).bg(Color.Indexed(236))` paints a background behind
the gauge while the gauge's own colours still win. Images still ignore the element style,
because their renderer owns every cell's colour directly.

`withStyle` is a cascade rather than a rewrite: it wraps the subtree in one node and the
style reaches each descendant when the frame is painted, on the pass the host already
makes over every node. Wrapping a thousand-node subtree costs one node, not a thousand
rebuilt ones, so it is fine to call inside `view` on every frame.

## Work with text values directly

Underneath the elements are three plain immutable values from `tui-core`, and one
`import io.worxbend.tui.dsl.*` reaches all of them. A `Span` is a run of characters plus
one `Style`; a `Line` is a sequence of spans making up one terminal row; a `Text` is a
sequence of lines.

### Restyle a value you were handed

All three answer `styled(transform)`, which runs every span's `Style` through the
function and leaves the characters alone, so a value returned by a helper can be
adjusted instead of taken apart and rebuilt:

```scala
val label = "OK".styled(_.withFg(Color.Green)) // a Span
label.styled(_.bold)                           // green and bold
Text.raw(body).styled(_.italic)                // every line italic
```

### Shorthands on strings

Two or three transforms turn up in nearly every view, and spelling them out makes a row
of text harder to read than the row itself. Each has a one-word name on a `String`, which
gives back the same `Span` the long form does:

```scala
line("press ", "q".bold, " to quit")     // instead of "q".styled(_.bold)
line("status: ", "down".fg(Color.Red))   // instead of "down".styled(_.withFg(Color.Red))
```

The shorthands are `bold`, `dim`, `italic`, `underline`, `reverse`, `crossedOut`, and the
two colours `fg(color)` and `bg(color)`. They exist on the resulting `Span` too, so they
chain — `"q".bold.fg(Color.Cyan)` — and each step layers onto the style the span already
has rather than starting over from the default. Anything past this short list still goes
through `styled`, which is the general form and is not going anywhere.

`under(base)` layers the other way round: `base` goes *underneath* what each span already
chose, so a span that set a colour keeps it and `base` fills in only what was left unset.
That is the direction a theme colour travels:

```scala
Text.raw(body).under(theme.muted)
```

`patchStyle(style)` is `under` with the two layers swapped: the argument goes on top and
wins wherever it says something, while everything it stays silent about survives. "Make
this already-styled line italic without disturbing its colours" is one call:

```scala
line.patchStyle(Style.Default.italic)
```

All three methods reach into the *spans*, rewriting each one's `Style`, rather than
setting a field. That is why they compose the way plain `Style` calls do.

### Tint a whole row without touching its spans

A `Line` also carries a base style of its own, which every span in it is drawn on top of.
Setting it is one field replacement, no matter how many spans the line has:

```scala
val row = Line(Seq(Span.raw("cpu "), Span("94%", Style.Default.withFg(Color.Red))))
row.withStyle(Style.Default.dim)   // the whole row is dim; "94%" is still red
row.withStyleOf(_.bold)            // edit the base layer instead of replacing it
Line.styled(spans, theme.muted)    // the same thing at construction time
```

Every character is resolved in three layers, each one laid over the last with
`Style.patch`, so the innermost layer wins wherever it speaks and the outer layers show
through wherever it says nothing:

1. the widget's own style — `Paragraph`'s `style` argument, a table's cell style, and so on,
2. then the `Line`'s `style`,
3. then the `Span`'s `style`.

In the example above the `"cpu "` span chose no colour, so it takes the line's dim; the
`"94%"` span chose red and keeps it, while still picking up the dim the line set, because
dim and a foreground colour are different settings and neither overrules the other. A
line that never sets a base style has `Style.Default`, which sets nothing, so it renders
exactly as it did before the layer existed.

A `Text` carries the same layer one level further out, so a whole block can be tinted in
one call, and it also carries a default alignment for its rows:

```scala
Text.raw(body).withStyle(theme.muted).centered
Text.styled(rows, theme.muted)          // the base style at construction time
```

That makes the full cascade four deep — the widget's style, then the `Text`'s, then the
`Line`'s, then the `Span`'s. A `Paragraph` given a `style` therefore no longer erases a
style its `Text` was built with; it sits underneath it.

Alignment resolves the same way, innermost first: a `Line`'s own alignment wins, failing
that the `Text`'s, failing that the widget's `alignment` argument. So a block can be
right-aligned as a whole while one heading inside it stays `leftAligned`.

The difference from `patchStyle` is what gets changed. `withStyle` leaves the spans as
they are and adds a layer beneath them, which is what you want for "this whole row is
dim". `patchStyle` rewrites every span, which is what you want when the spans themselves
must come out of the call carrying the new style — for example before handing them to
something that reads `span.style` directly.

### Show a secret without showing it

For a secret that is merely displayed — in a paragraph, a table cell, a list row, a log
line — wrap it in `Masked`:

```scala
val token = Masked(apiToken)        // hidden behind •
val pin   = Masked(accountPin, "*") // or behind a mask of your choosing

text(token.value)
line("token: ", pin.toSpan)
```

`Masked` emits one mask glyph per grapheme cluster, so an emoji or an accented letter is
hidden by exactly one character. Writing `"*" * secret.length` by hand does not: that
counts UTF-16 code units, so it produces more mask characters than there are characters to
hide, which both leaks the shape of the secret and misaligns the row. Its `toString` is
the mask too, so a `Masked` that reaches a log line or an assertion message does not print
the secret.

This hides characters on a screen. It is not encryption: the original text is still in
memory and still readable through `content`.

### Walk text cell by cell

A *grapheme cluster* is what a reader thinks of as one character: a base character plus
whatever attaches to it, so `e` followed by a combining acute accent is one cluster, and
so is a multi-code-point emoji. It is also the smallest thing a terminal cell can hold.

`styledGraphemes(base)` on a `Span` or a `Line` steps through the text one cluster at a
time, each one already carrying the style it will be drawn in — the span's own style
layered over the `base` you pass, so nothing downstream has to resolve it again:

```scala
val cells = line.styledGraphemes(theme.body).toList
cells.map(_.width).sum == line.width // always
```

Reach for it when you need per-cell information: working out which character a mouse click
landed on, or writing a reflow rule the built-in wrapping does not cover. The returned
iterator is stateful, single-use and owned by the calling thread — walk it once, and call
the method again rather than storing one.

### Build a value out of mixed pieces in one call

When you already have all the pieces, `Line.of` and `Text.of` take them as arguments and
promote the plain ones for you. `Line.of` accepts a `String` or a `Span` in any order and
in any mixture; `Text.of` accepts a `String` or a `Line`:

```scala
Line.of("Name: ", "Remy".styled(_.bold))          // two spans, only the second styled
Text.of("Summary", Line.of("ok ", failures))      // two rows
```

A plain string becomes an unstyled piece, and anything already built is taken exactly as
it is — its style, and a line's alignment, come through untouched. The saving is the
wrapper around every plain piece: the first line above would otherwise be
`Line(Seq(Span.raw("Name: "), "Remy".styled(_.bold)))`.

One difference to keep in mind: `Text.of` does **not** split on newlines. Each argument is
exactly one row, so a `\n` inside one of the strings would end up inside a row, where it
occupies no column but one cell and pushes the rest of that row a column out of place.
`Text.raw` is the one that splits; pass strings that may contain newlines to that instead.

### Build a value up a piece at a time

These are immutable values, so nothing is pushed into them: each helper returns a copy and
leaves the receiver alone. `Line.appended(span)` adds a span to the right of a row,
`Text.appended(line)` adds a row below a block, and `appendedAll` does the same with a
whole value. `Line.Empty` and `Text.Empty` are the identities to fold from:

```scala
val row = spans.foldLeft(Line.Empty)(_.appended(_))
val doc = rows.foldLeft(Text.Empty)(_.appended(_))
```

`+` and `++` are the same two helpers as operators, for the short inline cases:
`Span.raw("ok ") + count` makes a one-row `Line` out of two spans, `line + span` and
`line ++ otherLine` extend a row to the right, and `text + line` and `text ++ otherText`
add rows below. There is no operator for stacking two `Line`s vertically — that would give
`+` two different axes depending on the type of its right-hand side — so write
`Text(Seq(first, second))` when you mean "one above the other".

`Text.appendedToLast(span)` extends the *last* row rather than starting a new one, and it
owns the case that is easy to get wrong: a text with no rows at all has no last row, so
one is started holding just that span. A text whose last row exists but is empty is not
that case — the span joins that row and the row count does not change.

### Read the characters back out

`plainText` flattens a value back to an ordinary string with every style dropped — the
text a user would copy out of the terminal. Use it for logging, clipboard payloads and
test assertions:

```scala
val line = Line(Seq(Span("ok", Style.Default.withFg(Color.Green)), Span(" done", Style.Default)))
line.plainText            // "ok done"
Text.raw("a\nb").plainText // "a\nb"
```

`Text.plainText` joins the lines with `\n` and adds no trailing newline, so
`Text.raw(s).plainText == s` for any `s` without a carriage return.

Do not measure with it. `plainText.length` counts UTF-16 code units, which is not the
number of terminal columns the text occupies: one CJK character is one code unit and two
columns, and a combining accent is one code unit and no column at all. `width` is the
accessor that answers in columns, and it goes through `CharWidth`.
### Read terminal output back into text

A compiler, a test runner or `git` run as a subprocess colours its output with the same
SGR escape sequences glyphora writes. Shown as a plain string, those sequences are either
stripped or, worse, drawn as stray characters that push the rest of the row out of place.
`Text.fromAnsi` reads them back into the styled `Text` they describe — the inverse of the
terminal module's encoder:

```scala
val out = Text.fromAnsi("\u001b[1;31merror\u001b[0m: missing semicolon")
text(out)   // "error" in bold red, the rest in the element's own style
```

It understands the 16 named colours, the 256-colour and truecolour forms in both their
`;` and `:` spellings, every text attribute and its reset code, the `4:n` styled
underlines with the SGR 58 underline colour, and OSC 8 hyperlinks, which land on
`Style.link`. An SGR reset returns to `Style.Default` — silence, not `Style.Reset` — so
the uncoloured stretches take whatever style the widget drawing the text gives them,
exactly as plain text in that widget would. Every other escape sequence (cursor
movement, screen clearing, other OSC strings) is dropped whole, and so are control
characters other than `\n`, so CRLF output splits into clean rows. Plain text with no
escape in it comes out exactly as `Text.raw` reads it, and widths are measured through
`CharWidth` as they are for every `Text`.

Output that is still arriving goes through `AnsiParser`, which keeps its place between
chunks — a chunk boundary can fall inside an escape sequence or in the middle of a line,
and the style in force at the end of one chunk is the style the next one starts in.
`feed` hands back the lines a chunk *completed*; `finish` returns the unterminated
remainder once the stream has ended:

```scala
val parser = AnsiParser()
parser.feed(chunk).foreach(logState.append)   // on every chunk
logState.append(parser.finish())              // when the process exits
```

The cookbook's [subprocess log recipe](./cookbook#show-a-subprocesss-coloured-output-in-a-log)
shows the whole wiring, including which thread each line runs on.

### Write styled text as a markup literal

Assembling a message from spans is exact but verbose: `Line.of("error", " in ",
"f.scala".styled(_.withLink(url)))` names the style of every piece in Scala. The
`markup"…"` interpolator writes the same thing as one literal with inline tags, and the
compiler checks the tags:

```scala
val message = markup"[bold red]error[/] in [link=$url]$file[/]"
text(message)
text(markup"[green]✓[/] $passed passed, [red]$failed[/] failed")
```

A tag is one or more space-separated words in square brackets; it styles everything up
to the matching `[/]`, and tags nest, each inner one layered onto the outer with
`Style.patch`. The words are the attributes `bold`, `dim`, `italic`, `underline`, `blink`,
`reverse`, `hidden` and `strike` (`underline=curly` and friends for a styled underline);
a colour in any spelling `Color.parse` reads — `red`, `bright_cyan`, `#ff8800` — plus
`ansi(208)` for a palette index, which is the foreground, with `on <colour>` for the
background (`[red on blue]`) or the explicit `fg=`/`bg=`; and `link=<url>`. `[[` is a
literal `[`; a `]` outside a tag is already literal. A `\n` in the literal starts a new
row, as it does in `Text.raw`.

An interpolated `$value` is **text, never markup**. Whatever it contains — `[red]`
included — is rendered as the characters it is, in the style in force where it appears,
so `markup"[bold]$input[/]"` is safe to write over input the program did not author. The
one place a value stands inside a tag is as the whole value of `link=`, `fg=` or `bg=`:
`[link=$url]` takes a `String`, `[fg=$accent]` a `Color`, and the compiler checks those
types too.

A word that is not a style (`[bogus]`), a tag never closed, a `[/]` with nothing to
close, or a `[` with no `]` stops the build with an error naming the problem, instead of
being found the first time that view is drawn. What survives into the compiled code is
the span constructors with every literal style already a constant, so nothing is parsed
when the program runs — the same bargain `key"…"` and `hex"…"` make. `markup"…"` is
forwarded by `import io.worxbend.tui.dsl.*`, like `key"…"`.

## Turn a style back into code

When a cell assertion or a golden-frame comparison fails, the message shows the style it
found — `Style(fg=Cyan, modifiers=Bold)`. That reads well, but you cannot paste it into
the test as the expected value, because it is prose rather than an expression.

`asSource` prints the same value as the code that builds it:

```scala
Style.Default.withFg(Color.Cyan).bold.asSource
// "Style.Default.withFg(Color.Cyan).bold"

Color.Rgb(1, 2, 3).asSource
// "Color.Rgb(1,2,3)"
```

The builder order is fixed — colors, set modifiers, cleared modifiers, underline, link —
so two equal styles always print the same text, whatever order they were built in, and
the text always evaluates back to an equal style. `toString` stays what it was; this is a
second formatter beside it, for pasting rather than reading.

## Derive one color from another

`Color` can compute related colors instead of making you type a second hex literal.
`Color.lighten(c, amount)` and `Color.darken(c, amount)` fade toward white and black,
`Color.mix(a, b, t)` blends two colors, and `Color.gradient(from, to, steps)` returns an
evenly spaced ramp. Each one also reads as a method on the color itself —
`accent.mixedWith(theme.surface, 0.3).darken(0.1)`.

Fading toward white also drains the color out of a hue: a red lightened by 60% is
noticeably pinker and greyer than the red it came from. When you want the *same* color
at a different brightness, work in HSL — Hue (the angle on the color wheel, in degrees),
Saturation and Lightness (both fractions from `0.0` to `1.0`):

```scala
val brand = Color.hex("#c83232").getOrElse(Color.Red)

brand.withLightness(0.8)   // same hue, a pale tint of the brand color
brand.withSaturation(0.2)  // same hue and brightness, nearly grey
brand.rotateHue(180)       // the complementary color
brand.asHsl                // (hue, saturation, lightness), for your own arithmetic
```

Evenly spaced hues at one saturation and lightness make a categorical palette — colors
that are easy to tell apart but visually belong to one set, which is what a multi-series
chart wants:

```scala
val seriesColors = Seq.tabulate(6)(i => Color.hsl(i * 60.0, 0.65, 0.55))
```

`Color.hsl` wraps the hue (`-30` and `330` are the same red) and clamps saturation and
lightness, so generated palettes cannot produce an out-of-range color. `Color.toHsl(c)`
is the inverse, and it answers for named and 256-color values too by going through their
RGB approximation.

The same distinction applies to ramps between two colors. `Color.gradient` interpolates
each channel on its own, which is right for a fade toward a background — but halfway from
red to cyan the channels cancel and you get a dead grey. `Color.gradientHsl` travels
round the color wheel instead, taking the shorter arc, so every stop stays as saturated
as its ends:

```scala
Color.gradient(Color.Rgb(255, 0, 0), Color.Rgb(0, 255, 255), 7)     // sags through grey
Color.gradientHsl(Color.Rgb(255, 0, 0), Color.Rgb(0, 255, 255), 7)  // stays vivid
```

`Color.mixHsl(a, b, t)` is the single-point version, and both read as methods too —
`a.mixedThroughHueWith(b, 0.5)` and `a.hueGradientTo(b, 7)`. When one end is a grey it
has no hue to travel from, so the other end's hue is used for both and only saturation
and lightness move; that is what stops a fade toward grey from swinging through red on
the way.

## Write a hex color the compiler checks

`Color.hex("#ff8800")` returns an `Option`, because the string it is handed might come
from a config file or a command-line flag and might be malformed. For a literal you typed
yourself that `Option` is pure friction — every call site ends in `.get` — and a typo is
still only found when that line happens to run.

The `hex"…"` interpolator moves both to compile time:

```scala
import io.worxbend.tui.core.hex

val brand = hex"#c83232"   // expands to Color.Rgb(200, 50, 50) — no Option, no parsing at runtime
val short = hex"#f80"      // the three-digit form, same as Color.hex accepts
```

`hex"…"` is the one literal the DSL does not forward, so it needs this import where
`key"…"` does not: a colour literal is written in a theme, which is `tui-core` territory,
where a key spec is written in every app's bindings.

A malformed literal — `hex"#ff88"`, `hex"nothex"` — fails the build with an error naming
the string, rather than returning `None` at runtime. Interpolating a value
(`hex"#$computed"`) is rejected, because a value that is not known until the program runs
cannot be checked while it is compiled; use `Color.hex(value)` there and handle the
`None`. This is the one thing an application imports from `io.worxbend.tui.core`
directly: it is a literal notation rather than a name any DSL signature mentions, so it is
not part of the `io.worxbend.tui.dsl.*` re-export.

## Check that text will be readable

Accessibility guidance is written in *contrast ratios* — a number from 1 (two colors you
cannot tell apart) to 21 (black against white). The Web Content Accessibility Guidelines
ask for at least 4.5 for normal text, 3 for large text and interface elements, and 7 for
their strictest level.

`Color.contrastRatio(a, b)` computes it, and `Color.luminance(c)` gives the underlying
perceived brightness on a 0-to-1 scale:

```scala
Color.contrastRatio(Color.White, Color.Black)   // 21.0
Color.Cyan.contrastWith(Color.Black) >= 4.5     // the same check, read left to right
```

When the background is computed rather than chosen — a heat-map cell, a generated series
swatch — `Color.readableOn(background)` returns whichever of black and white reads better
on it:

```scala
val swatch = Color.hsl(hue, 0.65, 0.55)
text(label).fg(Color.readableOn(swatch)).bg(swatch)
```

Two honest caveats. `readableOn` picks the better of two colors; it cannot promise the
result clears a threshold, because a background near the middle of the range leaves
neither black nor white far from it. And for the sixteen *named* colors the RGB a
terminal actually paints is chosen by the terminal emulator, so a ratio involving them
describes the palette this library assumes, not what a given terminal shows. Ratios
between explicit `Color.Rgb` values — the Tailwind palette below, for instance — have no
such caveat.

## Reach for a ready-made palette

The sixteen named colors (`Color.Red`, `Color.BrightBlue`, …) are whatever the user's
terminal theme says they are. That is usually the right choice for chrome, because it
follows the user's taste — but it means you cannot know what a chart series will
actually look like, or whether the label on it will be readable. The alternative used to
be picking hex literals by hand, which is design work.

`tui-core` ships the Tailwind CSS default palette for exactly that gap: 22 hue ramps of
eleven shades each, as fixed 24-bit colors that no terminal theme can change.

```scala
import io.worxbend.tui.core.palette.Tailwind

val ok    = Tailwind.Emerald.c500
val warn  = Tailwind.Amber.c500
val panel = Tailwind.Slate.c800
```

The step numbers — `c50`, `c100`, `c200` … `c900`, `c950`, lightest to darkest — mean
the same visual weight in every ramp, so swapping `Blue.c500` for `Amber.c500` keeps a
design balanced. `shades.all` gives the eleven in order when the step is computed rather
than written down, `shades.shade(500)` looks one up by its number (returning `None` for
a step that is not in the ramp, rather than guessing a neighbour), and
`Tailwind.ramp("emerald")` finds a ramp by its published lower-case name.
`Tailwind.Black` and `Tailwind.White` are true black and white, again independent of the
terminal theme.

### The Material palette

The same design work exists in a second published form, and it is here too: the Material
Design 2014 palette, 16 hue ramps with accent shades, 3 without (`Brown`, `Gray`,
`BlueGray`), plus its own `Black` and `White`.

```scala
import io.worxbend.tui.core.palette.Material

val warn     = Material.Amber.c700
val urgent   = Material.Red.a400 // an accent
val surfaces = Seq(Material.BlueGray.c900, Material.BlueGray.c800, Material.BlueGray.c700)
```

A Material ramp runs `c50` to `c900` — ten steps, where a Tailwind ramp has eleven and
carries a further `c950` — and `all`, `shade(step)` and the step-means-the-same-weight
rule work exactly as they do there. `shade(950)` on a Material ramp is `None`, because
that step is Tailwind's and not Material's.

The four accents (`a100`, `a200`, `a400`, `a700`) are the part with no Tailwind
counterpart. They are not extra steps of the ramp: they are deliberately more vivid than
any ordinary shade, and Material uses them for the one thing on a screen that has to be
noticed — a selection, an alert. As a surface or body colour they are exhausting, which
is why `all` lists the ten ordinary shades and `accents` lists the four separately.

Which palette to use is taste rather than capability. Material's ramps are warmer and its
accents far more saturated, so a Material accent shouts where a Tailwind `c500` speaks.

## Build with themes, not scattered colors

For application chrome and reusable components, take the `Theme` as a context
parameter and draw the color from a semantic role:

```scala
def deploymentStatus(name: String, healthy: Boolean)(using theme: Theme): Element =
  val tone = if healthy then theme.success else theme.error
  text(s"● $name").styled(_ => tone)
```

That `(using theme: Theme)` is not ceremony: `view` receives the app's own theme (its
signature is `def view(using ReactiveScope, Theme)`), and the compiler passes it down
to helpers that ask for it. A helper that does *not* ask falls back to the library
default and silently renders dark-theme colors in a light-theme app.

`Theme.Dark`, `Theme.Light`, `Theme.HighContrast` and six palette presets
(`Theme.Dracula`, `Theme.Catppuccin`, `Theme.Nord`, `Theme.Gruvbox`, `Theme.Solarized`,
`Theme.Ansi16`) are built in; [The app shell](./app-shell#theme-presets) lists them,
and covers component styles and theme files. A custom theme is a
value of semantic styles — `primary`, `accent`, `muted`, `error`, `warning`, `success`,
`surface`, `border`, `focus` — plus the three grouped sub-palettes `loading`,
`markdown`, and `syntax`. Several factories read them for you already: `panel` and
`rule` frame themselves with `border`, `dialog` uses `primary` and `focus`, the whole
progress-and-spinner family reads `loading`, and `markdown` renders through
`markdown`. Every element that highlights a selected row — `list`, `tree`, `menu`,
`selectionList`, `filePicker`, `directoryTree` — draws it in `focus`, focused or not,
because the focus pass stamps the theme's cue onto the whole tree rather than onto the
one element holding the keyboard. See [The app shell](./app-shell) for live switching.

## Styles from text, and back again

`Style.parse` reads a whole style in one line — the spelling theme files use — and
`Style.render` writes it back:

```scala
Style.parse("bold #ff0000 on black")      // Right(bold, fg #ff0000, bg Black)
Style.parse("italic bright cyan on 236")  // two-word colour names keep their space
Style.parse("not-bold curly-underline underline-color red link https://example.com")
Style.parse("bold chartreuse")            // Left("'chartreuse' is not a style word or a colour")
Style.render(Style.Default.dim.withFg(Color.Cyan)) // "dim Cyan"
```

The words come in any order: attributes (`bold`, `dim`, `italic`, `underline`,
`blink`, `rapid-blink`, `reverse`, `hidden`, `crossed-out`) and their `not-` forms,
the underline variants (`double-underline`, `curly-underline`, …), a colour on its own
for the foreground, `on <colour>` for the background, `underline-color <colour>`, and
`link <url>`. `default` or an empty string is `Style.Default`. Every style `render`
writes parses back to an equal style.

## Colors from text, and back again

A color does not have to be written in Scala. `Color.parse` reads the three
spellings a configuration or theme file is likely to contain, and returns
`Either[String, Color]` — a `Left` carries a message naming what it could not
read, so a bad line is something your program reports rather than an exception
it has to catch:

```scala
Color.parse("bright cyan")  // Right(Color.BrightCyan)
Color.parse("#1e88e5")      // Right(Color.Rgb(30, 136, 229))
Color.parse("214")          // Right(Color.Indexed(214))
Color.parse("puce")         // Left("'puce' is not a color name, a hex color like #rrggbb, ...")
```

Names ignore case and the separators a hand-written name may carry, so
`BrightRed`, `bright red`, `bright-red`, `light_red` and `brightred` are all the
same color. `light` is accepted anywhere `bright` is; `grey`, `gray` and
`dark grey` name `BrightBlack`, and `silver` names `White`.

A run of one to three digits is always read as an xterm palette index, never as
a hex color: `120` is `Indexed(120)`. Write the `#` when you mean the color
`#120`.

The `.render` method is the inverse — it writes a color in the spelling `parse`
reads back, which is what you want when saving a theme rather than the
compiler-generated `toString`:

```scala
Color.BrightBlue.render      // "BrightBlue"
Color.rgb(255, 136, 0).render // "#ff8800"
Color.Indexed(214).render     // "214"
```

Every string `.render` writes parses back to an equal color. The other direction
is lossy, because many spellings name one color.

For a table of palette constants written as hex literals, `Color.fromInt` is the
total counterpart of `Color.hex`: it takes a packed `0x00RRGGBB` integer and
always succeeds, so no `Option` has to be unwrapped to initialise a `val`.
`Color.toInt` (or the `.packed` method) goes the other way.

```scala
val Surface = Color.fromInt(0x1e293b)
val Accent  = Color.fromInt(0xf8fafc)
```

## Avoid common layout surprises

- A constraint applies along the **parent container's direction**: `.length(10)` is
  width in a row and height in a column.
- Borders consume cells. A 3-row panel has one inner row after its top and bottom
  border.
- Use `CharWidth`, not `String.length`, when custom code measures visible text.
- Give interactive elements a stable `.key("settings-name")` when conditional
  rendering might otherwise move focus to a different positional index.
- Deep fixed sizes fail on small terminals. Reserve fixed cells for chrome, then let
  primary content fill.
- Constraints reflow, but they cannot decide that a layout has stopped working. When
  a narrow terminal needs *different components* rather than smaller ones, branch on
  size — see [Responsive layouts](./responsive).

Next, browse the [Widget catalog](./widgets) or assemble these pieces into
[The app shell](./app-shell).
