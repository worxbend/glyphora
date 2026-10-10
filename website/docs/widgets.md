---
title: Widget catalog
description: Choose, compose, configure, and test glyphora's layout, content, input, data, visualization, and feedback widgets.
---

# Widget catalog

glyphora ships more than fifty backend-agnostic widgets. The high-level DSL wraps
each widget in an `Element`, adds focus and input behavior where appropriate, and
keeps mutable interaction state owned by your application.

Use this page to choose a widget and understand its state model. The
[Scaladoc API](pathname:///api/widgets/) remains the exact-signature reference.

## Choose by job

| Job | Start with | Add when needed |
|---|---|---|
| Arrange a screen | `row`, `column`, `panel`, `spacer`, `rule` | `scrollView`, `splitPane`, `layers` |
| Show prose or records | `text`, `list`, `table`, `tabs` | `markdown`, `SyntaxHighlighter`, `log`, `dataTable`, `directoryTree` |
| Collect input | `input`, `checkbox`, `toggle`, `select`, `button` | `radioGroup`, `slider`, `textArea`, `autocomplete`, `filePicker`, `selectionList`, `Form` |
| Show a metric | `gauge`, `sparkline` | `lineGauge`, `dualSparkline`, `barChart`, `horizontalBarChart` |
| Plot data | `chart`, `pieChart`, `heatmap` | `stackedBarChart`, `canvas`, `calendar` |
| Communicate progress | `spinner`, `progressBar`, `orbitSpinner`, `skeleton`, `indeterminateBar` | `marquee`, `animatedText`, effects, toasts |
| Report an outcome | `notice`, `badge` | toasts, dialogs |
| Structure an app | `scaffold`, `topBar`, `sidebar`, `statusBar` | screens, menus, command palette, dialogs, `paginator` |

## The state ownership rule

Interactive widgets do not hide application state. You create the state once,
retain it across renders, and pass it into the element factory:

```scala
import io.worxbend.tui.dsl.*

private val name = TextInputState()
private val notifications = Signal(true)
private val environment = Signal(0)

def settings(using ReactiveScope): Element =
  column(
    text("Project name").dim,
    input(name, placeholder = "glyphora-app"),
    toggle("Notifications", notifications),
    select(Seq("development", "staging", "production"), environment),
    button("Save") { save(name.value, notifications.peek, environment.peek) },
  ).gap(1)
```

The focused widget's built-in key handler mutates its state and requests a redraw.
Signals add tracked reactivity where another part of the tree also needs the value.

> Construct widget state **outside** `view`. Creating `TextInputState()`,
> `ListState()`, or `DataTableState()` inside `view` would reset editing, selection,
> and scrolling on every redraw.

## Choosing one option out of many

`select(options, chosen)` is a one-row cycler: Left and Right step through the options,
and only the current one is ever visible. That is right for three options and wrong for
forty — choosing the thirtieth takes thirty keystrokes, and the user cannot see what they
are choosing between.

`dropdown(...)` shows the list. It draws one row while closed and, when opened, the whole
option list beneath that row as a bordered, scrolling popup:

```scala
import io.worxbend.tui.dsl.*

private val regionState = DropdownState()   // caller-owned, outside `view`
private val region      = Signal(0)

def view(using ReactiveScope, Theme): Element =
  column(
    text("Region").dim,
    dropdown(regions, region.get, regionState)(index => region.set(index)),
  )
```

Enter, Space or Down opens the list, and so does a click on the row. While it is open,
Up and Down move the highlight, the wheel moves it too, and Enter or a click on an option
commits that option through the callback. Escape closes the list and changes nothing — the
highlight is not the chosen value until it is committed, which is what makes "open it,
look around, back out" safe.

The state carries the navigation those keys drive, for an app that wants its own
bindings: `regionState.openAt(selected)` opens the list with the highlight parked on the
current value, `highlighted` reads where the highlight sits, `selectNext(options)` and
`selectPrevious(options)` move it with wraparound, and `highlightAt(visibleRow,
optionCount)` turns a clicked popup row into the option it shows.

Two things are worth knowing before reaching for it. An open dropdown **consumes Escape**,
so an app that binds Escape globally will not see it while a list is showing. And the popup
is drawn inside the node's own area rather than floated over the screen, so while the list
is open the node claims `1 + maxVisibleRows + 2` rows and whatever sits below it moves down.
That is what lets it work inside any panel or column with no overlay machinery; the cost is
that a dropdown near the bottom of a short area gets a clipped list rather than one that
opens upwards. `maxVisibleRows` (8 by default) caps the popup, and a longer list scrolls
inside it.

## One name for the chosen thing

Every widget that draws one of its items differently because it is the chosen one
calls that parameter `highlightStyle` — `ListView`, `Tree`, `Menu`, `DataTable`,
`DirectoryTree`, `Tabs`, `Calendar`, `Dialog`, `RadioGroup` and `Paginator` alike.
Learn it once and it transfers.

The DSL fills it in for you from the app theme's `focus` style, so a `list` and a
`menu` in the same app highlight identically and switching to `Theme.HighContrast`
moves every one of them at once. You only pass it by hand when you build the widget
value yourself — `DataTable` is the common case, because its sort, filter and paging
options mean you construct it rather than let a factory do it. Everything past the
columns, rows and widths bundles into one `DataTableOptions` value, and plain text
rows build through `DataTable.fromStrings`:

```scala
DataTable.fromStrings(columns, rows, widths, DataTableOptions(highlightStyle = theme.focus))
```

## Layout and chrome

Core structural elements:

- `panel(title)(children*)` / `panel(children*)` — bordered vertical container;
- `row(children*)` / `column(children*)` — constrained layout containers;
- `spacer` / `spacer(cells)` — flexible or fixed blank space;
- `line(spans*)` — one row carrying several styles (see [Text, documents, and logs](#text-documents-and-logs));
- `rule(label, borderType)` — horizontal divider, whose weight comes from the same
  `BorderType` set panels frame themselves with: `Plain`, `Rounded` (which draws the
  plain run, since rounding only affects corners), `Double`, or `Thick`. Giving the
  divider the panel's own border type is what makes the two read as one frame;
- `scrollView(content, state)` — measured vertical viewport with wheel/key scrolling;
- `tabbedContent("Name" -> page, ...)(selected)` — tabs plus the selected page;
- `collapsible(title, expanded)(body)` — toggleable disclosure region;
- `splitPane(first, second, splitPercent)` — keyboard/mouse-resizable panes;
- `layers(base, overlays*)` — paint later elements over earlier ones;
- `positioned(dx, dy, width, height)(content)` — place content at an exact offset
  inside the area, clipped to it (see
  [Overlay at an exact offset](./layout-and-style#overlay-at-an-exact-offset));
- `menu`, `tooltip`, and `dialog` — transient interaction surfaces. A `tooltip` sizes
  itself to its text and has no anchor of its own, so it is `positioned` over a
  `layers` base at the cell it should point at;
- `clear(style)` — blanks its area so a popup drawn after it starts from a clean
  background.

### Blanking the area under a popup

A dropdown, toast or autocomplete list drawn over an already-composed frame shows the
page underneath wherever the popup itself does not paint: to the right of a menu row
narrower than the popup, or in the gap between two of them. `clear()` blanks the region
first, and the popup then paints onto empty cells.

```scala
layers(
  page,
  positioned(10, 4, 24, 6)(
    layers(
      clear(),
      menu(Seq(MenuEntry.Item("Open"), MenuEntry.Separator, MenuEntry.Item("Quit")), menuState)(onPick),
    ),
  ),
)
```

With no argument the cells are erased to the terminal's own background. Passing a style
that carries a background colour — `clear(theme.surface)` — paints an opaque panel
instead. `dialog` already does this for its own box, so it needs no `clear()` of its own.

While a `scrollView` is focused, Up/Down scroll a row, PageUp/PageDown ten rows, and
Home/End jump to the top and bottom of the content. `ScrollViewState` exposes the same
moves for an app that wants its own bindings — `first()`, `last()`, `scrollBy(delta)`
(negative moves toward the top), `scrollTo(row)`, and `pageUp()`/`pageDown()`, which
move by whatever the viewport measured on the last frame rather than a fixed ten. All of
them clamp to the content, and all of them are no-ops before the first render, because
that is when the state first learns how tall the content and the viewport are.

`scrollView` draws its own scrollbar. For the rarer case of a scroll indicator beside
something the DSL is not scrolling — a custom render loop, a pane whose offset your own
code owns — use `scrollbar(contentLength, position)`. It owns no state: where the thumb
sits is a pure function of the two numbers you pass, so the offset stays wherever you
already keep it.

```scala
// 200 rows of content, currently showing from row 40
row(
  panel("Log")(logLines).fill,
  scrollbar(contentLength = 200, position = 40),
)

// along the bottom edge instead
Scrollbar(200, 40, ScrollbarOptions(orientation = Direction.Horizontal))

// a left-hand gutter instead of the right edge
Scrollbar(200, 40, ScrollbarOptions(side = ScrollbarSide.Near))

// the same bar as a DSL element
scrollbar(200).at(40).horizontal
```

Everything past `position` — orientation, side, symbols, styles, the viewport length —
bundles into one `ScrollbarOptions` value, so the two numbers that move every frame stay
the widget's only bare arguments.

`side` picks which of the axis's two edges the strip lands on. `Far`, the default, is the
right edge for a vertical bar and the bottom edge for a horizontal one; `Near` is the left
edge and the top edge. The thumb sits in the same place along the strip either way — only
the lane it is drawn in moves.

The element claims one cell across the short axis, so give it a slice next to the content
rather than laying it over the content. `.at(offset)` moves the thumb, `.thumbStyle(...)`
recolours the moving part on its own, and `.symbols(track, thumb)` replaces the two glyphs
(both must be one column wide).

The thumb's length is proportional to how much of the content the viewport covers, and a
`position` past the end pins it to the end rather than drawing it off the track. When the
content fits, only the track is drawn — unless you ask for a thumb anyway with
`thumbWhenFits = true`, which fills the whole track. That is the conventional "you are
seeing all of it" affordance, and it stops the strip flipping between a bare track and a
thumb as the content grows past the viewport by a single row.

By default the bar assumes the viewport is exactly as long as the bar itself, which is
right whenever the strip runs the full height of the thing it describes. When it does not
— a bar beside a bordered pane sits next to two rows of border, so it is two cells longer
than the rows the reader can see — say so with `viewportLength`, otherwise the thumb comes
out the wrong length and stops short of the end of the track:

```scala
// the strip is 10 cells tall, but the pane behind it shows only 8 of the 200 rows
Scrollbar(contentLength = 200, position = 40, options = ScrollbarOptions(viewportLength = Some(8)))
```

Arrow caps mark the two ends of the strip, which is how a reader tells a scrollbar from a
plain border line in a screenshot. Each cap takes one cell away from the track, so a 10-row
bar with both caps places its thumb in the 8 rows between them. On a *horizontal* bar a
double-width glyph takes two cells instead, because there the strip's cells are columns and
a wide glyph covers two of them; on a vertical bar the cells are rows, and no glyph is two
rows tall. `ScrollbarSymbols` collects the conventional glyph sets and `Scrollbar.withSymbols`
builds a bar from one:

| Set | Track | Thumb | Caps |
|---|---|---|---|
| `ScrollbarSymbols.Plain` | `│` | `█` | none — the default |
| `ScrollbarSymbols.Vertical` | `│` | `█` | `↑` `↓` |
| `ScrollbarSymbols.Horizontal` | `─` | `█` | `←` `→` |
| `ScrollbarSymbols.DoubleVertical` | `║` | `█` | `▲` `▼` |
| `ScrollbarSymbols.DoubleHorizontal` | `═` | `█` | `◄` `►` |
| `ScrollbarSymbols.Ascii` | `\|` | `#` | `^` `v` |

```scala
import io.worxbend.tui.widgets.{Scrollbar, ScrollbarSymbols}

Scrollbar.withSymbols(200, 40, ScrollbarSymbols.DoubleVertical)

// nothing outside printable ASCII, for a terminal whose font has no box drawing
Scrollbar.withSymbols(200, 40, ScrollbarSymbols.Ascii)
```

The caps are also available one at a time as `beginSymbol` and `endSymbol`, with
`capStyle` colouring both; leaving either as `None` gives that end plain track.

### Merging touching borders

Two panels that share a column both draw on it. By default the one drawn second wins,
which gives a single wall but leaves the corners unjoined:

```text
┌──┐──┐        ┌──┬──┐
│  │  │   →    │  │  │
└──┘──┘        └──┴──┘
```

`.mergeBorders(MergeStrategy.Exact)` reads the box-drawing glyph already in the buffer,
combines its four arms with the arms of the glyph about to be written, and writes the
single glyph that shows both — so `┌` landing on `─` becomes `┬`, and `┘` landing on
`┌` becomes `┼`. Anything that is not a box-drawing glyph (a title character, a space,
content) is left to overwrite normally.

```scala
layers(
  panel(left).length(20),
  positioned(19, 0, 20, 10)(panel(right).mergeBorders(MergeStrategy.Exact)),
)
```

`MergeStrategy.Fuzzy` behaves the same, except that when Unicode has no combined glyph
it retries with double lines weakened to single ones — there is no double-meets-heavy
junction in Unicode at all, so a double-walled panel touching a thick-walled one joins
one weight lighter instead of not joining. `MergeStrategy.Replace` is the default and
overwrites, exactly as before.

Merging only ever looks at what is already in the buffer, so it depends on draw order:
the panel drawn second is the one that joins.

### Panel padding and captions

A panel reserves blank cells between its border and its children with `.padding(cells)`
or `.padded(Padding(...))`, and can carry a caption on each border:

```scala
panel("Errors")(errorList)
  .padding(1)                       // 1 row top and bottom, 2 columns each side
  .titleBottom(s"${errors.size} total")
  .titleStyle(_.withFg(Color.Red))  // the caption reddens; the frame keeps its own style
```

`.padding(1)` is *not* one cell on all four sides. A terminal cell is roughly twice as
tall as it is wide, so one blank row costs about twice as much of the screen as one blank
column; `.padding(n)` therefore reserves `n` rows above and below and `2 * n` columns
either side, which is what reads as an even margin. When you want the exact counts,
`.padded(Padding(left = 4, right = 1, top = 0, bottom = 0))` sets each side on its own,
and `Padding.uniform`, `Padding.horizontal`, `Padding.vertical` and `Padding.symmetric`
name the common shapes. `Padding.left`, `Padding.right`, `Padding.top` and
`Padding.bottom` pad a single side.

`Block`'s `borders` is a bitset of the sides that draw a line. `Borders.All`,
`Borders.None` and the four single sides combine with `|`; `Borders.Horizontal` is the
top and bottom edges and `Borders.Vertical` the left and right ones. To take a side away
rather than list the ones that stay, use `without`:

```scala
Block(borders = Borders.All.without(Borders.Top))   // same as Right | Bottom | Left
```

`&` intersects two sets, `hasAny` asks whether any named side is drawn, `hasAll` whether
every one of them is, and `show` prints the set as `"Top|Left"` instead of a raw number.

`titleBottom` writes into the bottom border at the right, `title` into the top border at
the left. Neither costs a content row: they overwrite border cells that were being drawn
anyway. Below the DSL, `Block` takes a `Seq[BlockTitle]` and any number of them can share
a border — `BlockTitle.top(line, Alignment.Center)`, `BlockTitle.bottom(line)`, and so on,
each a shorthand for setting `TitlePosition.Top` or `TitlePosition.Bottom` on the caption.

`Block` has two styles and they do different jobs. `borderStyle` colours the frame glyphs
and the titles. `style` paints the *whole* area — frame and interior together — before
anything is drawn, which is how a panel gets a background colour distinct from the screen
behind it:

```scala
Block(
  Seq(BlockTitle.top(Line.raw("Errors"))),
  style = Style.Default.withBg(Color.Blue),        // the panel's own background
  borderStyle = Style.Default.withFg(Color.Red),   // layered on top, so the frame keeps that background
)
```

The fill changes the style of each cell and never its glyph, so content already drawn in
that area keeps its text and its foreground colour — you can render the block before or
after the content and get the same frame. `borderStyle` is layered on `style` rather than
replacing it, so it only has to say what is *different* about the frame; a block left at
the default `style` paints no fill at all.

A third style, `titleStyle`, applies to the captions alone and layers over `borderStyle` in
turn — bold panel names against a plain frame, say. It saves restyling each `BlockTitle`'s
line one at a time and keeping them in step by hand, and a span inside a title still layers
over it, so one word of a caption can differ from the rest.

### Border sets

`borderType` picks the glyphs the frame is drawn from. Every built-in set is one terminal
column wide in every position, so switching between them never moves `inner` — the frame
changes, the content area does not.

| `BorderType` | Looks like | Use it for |
|---|---|---|
| `Plain`, `Rounded`, `Double`, `Thick` | `┌─┐` `╭─╮` `╔═╗` `┏━┓` | the four classic box-drawing weights |
| `Ascii` | `+--+` | terminals and fonts with no box-drawing glyphs, and output that gets pasted somewhere that is not a terminal |
| `LightDoubleDashed`, `LightTripleDashed`, `LightQuadrupleDashed` | `┌╌╌┐` `┌┄┄┐` `┌┈┈┐` | a frame that reads as provisional or inactive next to a solid one |
| `HeavyDoubleDashed`, `HeavyTripleDashed`, `HeavyQuadrupleDashed` | `┏╍╍┓` `┏┅┅┓` `┏┉┉┓` | the same, at the thick weight |
| `QuadrantOutside`, `QuadrantInside` | `▛▀▜` / `▗▄▖` | half-cell frames — one sits half a cell outside the area, the other half a cell inside, so two nested blocks meet with no gap |
| `OneEighthWide`, `OneEighthTall` | `▁▁▁` / `▕▔▏` | the "McGugan box": one-eighth blocks pressed against the cell edge, which reads as a hairline |
| `ProportionalWide`, `ProportionalTall` | `▄▄▄` / `█▀█` | a frame that looks the same thickness all round, compensating for a cell being about twice as tall as it is wide |
| `Full` | `███` | a solid slab |
| `Blank` | spaces | a border-styled margin, or keeping a title's border row with no frame under it |

The dashed sets keep the solid corners of their weight, because a dashed corner glyph does
not exist and the eye only reads a broken run of dashes as a line when its ends are pinned
down.

You do not have to pick `Ascii` by hand for a terminal that needs it. `BorderType.Ascii`
is what every other set degrades to under a theme whose glyph ceiling has been lowered —
see [Degrading to ASCII](./unicode-and-accessibility#degrading-to-ascii).

When none of those is the frame you want, `borderSet` takes the eight glyphs directly and
wins over `borderType`:

```scala
Block(borderSet = Some(BorderGlyphs.symmetric("─", "│", "┌", "┐", "└", "┘")))
Block(borderSet = Some(BorderGlyphs.uniform("*")))
```

`BorderGlyphs` keeps the two sides of each axis apart — `verticalLeft`/`verticalRight` and
`horizontalTop`/`horizontalBottom` — because the half-cell sets are not symmetric: the left
edge of `QuadrantOutside` is `▌` and its right edge is `▐`. `BorderGlyphs.symmetric` is the
short spelling when both sides of an axis share a glyph, and `BorderGlyphs.uniform` when all
eight do.

### Drop shadows

A `shadow` makes a dialog or a popup read as floating above the screen rather than cut into
it:

```scala
Block(shadow = Some(Shadow.Default))                       // one cell down and right, dimming what is behind
Block(shadow = Some(Shadow.shade(ShadowFill.MediumShade))) // painted with ▒ instead
Block(shadow = Some(Shadow.dim(2, 1)))                     // a longer shadow to the right than below
```

Unlike a CSS box shadow, which spills outside its element, this one is paid for *inside* the
block's own area — a widget in this toolkit never draws outside the rectangle it is handed.
A block with the default one-cell shadow frames itself one column narrower and one row
shorter, the freed strip becomes the shadow, and `inner` shrinks to match, so a layout never
gets a surprise. A negative offset casts up and to the left instead, and the frame moves down
and right to make the room there. On an area too small to leave a usable frame behind, the
shadow is dropped and the block renders as if it had none.

There are two ways to paint the band, and which one you want depends on the terminal.
`ShadowFill.Dim` keeps the glyphs that were already there and only dims them, so text behind
the panel stays legible while visibly receding — but a good many terminals render the dim
attribute as nothing at all. `ShadowFill.LightShade`, `MediumShade`, `DarkShade` and `Solid`
overwrite the band with `░`, `▒`, `▓` and `█`, which works everywhere; `ShadowFill.Symbol`
takes a single-column glyph of your own.

See [Layout & style](./layout-and-style) for constraints and [The app shell](./app-shell)
for application-level composition.

## Text, documents, and logs

```scala
column(
  text("Deployment complete").bold.fg(Color.Green),
  text("8 services updated · 0 failed").dim,
  rule("release notes"),
  markdown(releaseNotes),
)
```

- `text` uses a grapheme-aware paragraph renderer, in one style for the whole block.
- `line(spans*)` is the mixed-style row: each part carries its own style.
- `markdown` supports headings, lists, quotes, fenced code, inline styles, and OSC 8
  links. Its DSL element reports width-dependent height to scroll containers.
- `log(LogState)` supports append-heavy output and follow-tail behavior.
- `link(label, url)` emits a clickable OSC 8 hyperlink when the terminal supports it.
- `bigText` renders banners; `image` renders raster data with half-block cells.

### How wrapping breaks a line

`Overflow.Wrap` — the mode `Paragraph`, `markdown` and `notice(...).wrapped` use to grow
onto further rows instead of cutting text off — breaks between words:

| Input, at width 8 | Rows drawn |
| --- | --- |
| `hello world` | `hello` / `world` — the word moves whole, it is not cut after `hello wo` |
| `aa    bb` (four blanks) | `aa` / `bb` — the blanks sat at the break, so they are dropped |
| `    indented text` | `    indented` / `text` — blanks at the *start* of a line are content, not a break point |
| `supercalifragilistic` | `supercal` / `ifragili` / `stic` — a word longer than the whole width has nowhere to go |

Two Unicode characters exist purely to control this, and both are honoured:

- `U+00A0` NO-BREAK SPACE draws a blank column but forbids a break, so `10 kg` never ends
  up with the number alone at the end of a row.
- `U+200B` ZERO WIDTH SPACE draws nothing but permits one, which is how Thai, Japanese and
  long URLs mark a place a wrapper may break.

### Which blanks a wrapping mode keeps

The three wrapping modes break lines identically and differ in one decision only: what
happens to the blanks at the head of a row. That decision has no universal answer, because
it depends on whether the caller's indentation carries meaning. Wrapping `"  * a long
bullet"` at width 10 shows all three:

| Mode | Rows drawn | Keeps |
| --- | --- | --- |
| `Overflow.Wrap` | `  * a long` / `bullet` | the line's own indent; drops the blank the break landed on |
| `Overflow.WrapTrimmed` | `* a long` / `bullet` | nothing — every row starts flush against the left edge |
| `Overflow.WrapPreserved` | `  * a long` / ` bullet` | everything, so the break's blank re-indents the next row |

Pick `Overflow.Wrap` for a document whose indentation is structure and whose breaks are
incidental, which is nearly always. Pick `Overflow.WrapTrimmed` for prose that arrived with
an indent the layout, not the text, should decide. Pick `Overflow.WrapPreserved` when a
blank is data rather than spacing and dropping one would misrepresent the source. A run of
blanks that would leave the word after it no room on the row is dropped even under
`Overflow.WrapPreserved`, because keeping it would cost a row that shows nothing.

`Paragraph.heightAt(width)` counts the rows the chosen mode actually draws, so a wrapping
paragraph and the layout that sized it never disagree.

The classification lives in `io.worxbend.tui.core.LineBreaks` (`isBreakingSpace`,
`isZeroWidthBreak`, `endsWithZeroWidthBreak`) next to `CharWidth`, so any code that wraps
text agrees with the built-in widgets about where a break is allowed.

`heightAt(width)` counts exactly the rows this produces, so a scroll container or a
layout pass sizing a wrapped paragraph never disagrees with what is drawn.

### Which end of a too-wide line survives

A line that still does not fit — because the paragraph clips rather than wraps, or
because it is one unbreakable word — loses the side *away* from its alignment:

| Alignment | `"/var/log/app.log"` in seven columns |
| --- | --- |
| `Left` | `/var/lo` — the beginning, as before |
| `Right` | `app.log` — the end, the part the alignment was chosen to show |
| `Center` | `og/app.` — as much goes from each side |

A cut that lands inside a wide character drops the whole character rather than half
of it, so the row can start one column in from the edge instead of showing a broken
glyph. `CharWidth.dropByWidth(text, columns)` is the counterpart of
`substringByWidth` this is built on, for custom widgets that need the same thing.

### One row, several styles

`text(...)` paints its whole block in a single style, so it cannot say `Status:` in the
default colour and `OK` in green. `line(...)` can:

```scala
line("Status: ", "OK".styled(_.withFg(Color.Green)))
```

Each part is either a plain `String` or a `Span`, and the two mix freely in one call.
`"...".styled(transform)` builds a `Span` — a run of text with its own style. A part
written as a bare `String` carries no style of its own, so it is drawn in the element's
style; that is why the label above needs no `.styled(identity)` to sit beside a coloured
span. The element's own style is the base each span layers onto, so `line(...).dim` dims
the whole row and a span that set its own colour keeps it.

A `line` can also place itself: `line(...).rightAligned` (and `leftAligned`, `centered`,
`aligned(Alignment.Center)`) sets the alignment on the underlying `Line`, which wins over
the alignment the paragraph drawing it was given. Stack them in a `column` to get a
left-aligned heading above right-aligned figures without a second widget. See
[Layout and style](./layout-and-style#align-one-row-of-text-on-its-own).

The alternative before `line` existed was a `row` of `text` elements with hand-counted
widths: `row(text("Status: ").length(8), text("OK").fg(Color.Green))`. Do not do that.
The `8` is a display width written by hand, and it is wrong the moment the label is
translated, and wrong today for any CJK or emoji text, where one character occupies two
terminal columns. `line` measures its parts through `CharWidth`, so the row stays correct.

`SyntaxHighlighter` turns source code into styled `Text`, which any text-taking widget
or element can then render. It is a pure function, not a widget, so it owns no state:

```scala
import io.worxbend.tui.widgets.{Language, SyntaxHighlighter, SyntaxTheme}

text(SyntaxHighlighter.highlight(snippet, Language.Scala))

// `Language.of` resolves a Markdown fence's info-string; unknown names fall back to Generic
text(SyntaxHighlighter.highlight(snippet, Language.of("sh"), SyntaxTheme(comment = Style.Default.dim)))
```

Highlighting is line-oriented and dependency-free: it recognises line comments,
single-line strings, numbers, per-language keywords, `name(` call sites and shell
`$variables`. Block comments and triple-quoted strings that span lines fall back to plain
text — the right trade-off for snippets in help screens, READMEs and Markdown fences
rather than for a full editor. `markdown` already highlights its fenced code this way.

## Lists and navigation

```scala
import io.worxbend.tui.widgets.ListState

private val selected = ListState()
private val services = Signal(Vector("api", "worker", "scheduler"))

def serviceList(using ReactiveScope, Theme): Element =
  list(services.get, selected).onKey(Key.char('d')) {
    selected.selected.foreach { index =>
      services.update(_.patch(index, Nil, 1))
      selected.selected = None
    }
  }
```

While a list is focused, Up and Down move the highlight one row, Home and End jump to
the first and last item, and PageUp/PageDown move it ten rows at a time. Those four
jumps are also available on the state object as `selectFirst`, `selectLast` and
`selectBy` (which takes a signed number of rows), so an app can bind its own keys to
them. None of them touches the scroll offset: the list re-derives that during the next
render, which is what scrolls the chosen row into view. `clearSelection()` drops the
selection *and* scrolls back to the top — setting `selected = None` on its own would
leave the list parked on a page with nothing highlighted on it. A `dataTable` gains the
same four moves, except that a table with paging turned on keeps PageUp/PageDown for
turning pages.

By default the highlight is allowed to come to rest on the very first or very last
visible row, with more items sitting just out of sight below it — the reader cannot see
what is coming next. `ListState(scrollPadding = 2)` keeps two further items visible on
each side of the highlight whenever the list is long enough to show them, so pressing
Down two rows short of the bottom scrolls the list underneath a highlight that stays put.
Near the two ends of the list the padding gives way, because there is nothing left to
reveal: the first and last items still reach the edge rows.

The `> ` marker in front of the selected row needs two columns, and by default a list
reserves them on every row so that the text never shifts sideways when the selection
moves. In a narrow pane that is two columns of text given up permanently, so
`highlightGutter` offers two other policies. `HighlightSpacing.WhenSelected` reserves
them only while something is selected — a list nobody has picked a row in yet uses its
full width. `HighlightSpacing.Never` reserves nothing and draws no marker at all,
leaving the highlight style as the only cue:

```scala
list(services.get, selected).highlightGutter(HighlightSpacing.Never)
```

`highlightSymbol` replaces the marker itself. Whatever you pass keeps its display width
reserved on every row, counted in terminal columns rather than in characters, so a
two-column marker such as `"▶ "` gives up two columns of text and nothing shifts sideways
when the selection moves.

An item in a list is a plain `String`, a styled `Line`, or a whole `Text`, and the three
may be mixed in the same call. A plain string is drawn in the element's own style; a `Line`
carries its own — that is how one row goes red while the rest stay as they were, without
splitting the list in two:

```scala
list(
  Seq[String | Line](
    "api",
    Line(Seq(Span("worker", Style.Default.withFg(Color.Red)))),
    "scheduler",
  ),
  selected,
).highlightSymbol("▶ ")
```

A `Text` item is how one entry takes several rows — a title with a dimmed subtitle under
it being the usual reason. Everything the list counts still counts *items*, not rows: one
press of Down moves past the whole block, and the scroll offset is an item index.

```scala
list(
  Seq[String | Line | Text](
    Text(Seq(Line.raw("deploy"), Line(Seq(Span("finished 4m ago", Style.Default.dim))))),
    "rollback",
  ),
  selected,
)
```

An entry that occupies more rows than the pane has left is drawn from its top and cut off
at the bottom edge, so it is never hidden entirely. The `> ` marker points at the item, so
it is drawn on the entry's first row only; pass `repeatHighlightSymbol = true` to
`ListView` for a marker on every row of the selection instead, which suits an entry whose
rows are a list of their own. An empty `Text` still takes one blank row, because an item
that drew nothing could still be selected and an invisible selection is worse than a blank.

A list normally starts at the top row of its area and grows downward, so a list with
three entries in a ten-row pane leaves seven blank rows underneath it. A chat transcript
or a log tail wants the opposite: the newest entry welded to the bottom edge, with the
blank rows above it. Call `bottomToTop` for that. It anchors the first item of the
sequence to the *last* row of the area and grows upward, so feed the items newest-first.
The underlying `ListView` takes the same choice as a `ListDirection` value
(`TopToBottom`, the default, or `BottomToTop`); nothing else changes — the selection
still clamps and scrolls exactly as it does the other way up.

```scala
private val transcript = ListState()

def chatPane(using ReactiveScope, Theme): Element =
  list(messages.get.reverse, transcript).bottomToTop
```

Use `tree(nodes, TreeState)` for in-memory hierarchy and
`directoryTree(state)` for filesystem snapshots. `DirectoryTreeState(root)` starts
**unloaded**. Construction, navigation and painting never touch the filesystem;
`childrenOf` and `visiblePaths` read only installed snapshots. Create and retain the
state outside `view`.

For a small local tree, load explicitly in `onStart` and in an Enter handler after
expanding. This convenience blocks the owner thread, but never hides IO in painting:

```scala
import io.worxbend.tui.dsl.*
import java.nio.file.Paths

object LocalBrowser extends TuiApp:
  private val files = DirectoryTreeState(Paths.get("."))

  override def onStart(): Unit = files.loadVisible()

  def view(using ReactiveScope, Theme): Element =
    panel("Files")(
      directoryTree(files).onKeyEvent {
        case KeyEvent(KeyCode.Enter, _) =>
          files.toggle()
          files.loadVisible()
          true // handled here; do not toggle again in the built-in handler
        case _ => false
      }
    ).rounded
```

For slow disks, network mounts or large directories, acquire immutable
`DirectoryListing` values on workers instead. The state and its requests stay owned
by the render thread. This helper is called on that owner, with a `TaskScope` made
by `Async.scope()` in `onStart` and a redraw callback such as `() => requestRedraw()`:

```scala
def loadBranches(state: DirectoryTreeState, tasks: TaskScope, redraw: () => Unit): Unit =
  state.directoriesToLoad.foreach { directory =>
    val request: DirectoryLoadRequest = state.beginLoad(directory) // owner
    tasks.runCatching(DirectoryListing.load(request.directory)) { acquired => // worker -> owner
      if state.install(request, acquired.flatMap(identity)) then
        redraw()
        loadBranches(state, tasks, redraw) // expanded descendants may now be visible
    }
  }
```

Call it initially and after toggling a branch in an event handler; do not schedule
work from `view` or render. Close the scope when the screen leaves (or in `onStop`),
and invalidate outstanding tree requests when abandoning that load session. A new
run needs a new scope. Cancellation suppresses callbacks but does not interrupt
blocking filesystem IO.

`DirectoryEntry` contains a path, directory flag and resolved identity.
`loadState(path)` reports `DirectoryLoadState.Unloaded`, `Loading`, `Loaded` or
`Failed(error)`. Failed branches are not automatically retried: invalidate that
branch and call the loader, or explicitly `beginLoad(path)` again. `invalidate()`
drops all snapshots and retires outstanding requests; `invalidate(Some(path))`
only drops that branch. Neither starts IO. An `install` with a stale, foreign,
already-consumed or mismatched request returns `false`, so an old completion cannot
replace a newer snapshot. Always request a redraw after successful installation.

Directory links are followed, even outside the root; this is a browser, not a
sandbox. Acquired identities prevent ancestor cycles while leaving sibling aliases
browsable. All snapshot types and `TaskScope` are available from the DSL import.

### Tab bars

`tabs(titles, selected)` draws a one-row bar of titles with the chosen one
highlighted. The underlying `Tabs` widget lets you decide where the blank columns
between titles come from, and that choice is visible: padding belongs to the tab, so
`highlightStyle` covers it, while a divider sits *between* tabs and is never
highlighted. The default `divider` (`" │ "`) carries its own blanks, which means the
first and last titles sit flush against the edges of the bar. `Tabs.padded(titles)`
is the other arrangement — a bare `"│"` divider plus one blank column of
`paddingLeft` and `paddingRight` inside every tab — so a reversed highlight paints a
solid block a column wider than the title on each side:

```scala
import io.worxbend.tui.core.Line
import io.worxbend.tui.widgets.Tabs

widget(Tabs.padded(Seq("Overview", "Detail").map(Line.raw), selected = 1))
```

A tab bar can also show nothing as chosen. `selected` is an index into the titles,
and any index outside their range highlights no tab — `Tabs.NoSelection` is the name
for one, so a call site can say that it means it rather than leaving the next reader
to work out whether a `-1` was deliberate:

```scala
widget(Tabs(titles, Tabs.NoSelection))  // nothing opened yet
```

`Tabs` also answers `widthAt(height)` from `core.Measured`, reporting the exact
columns it needs — every title, its padding, and one divider per gap. A `row` can
therefore size a tab bar from its own content instead of you maintaining a
`Constraint.Length` that has to be edited every time a tab is renamed.

## Tables: simple and interactive

Use `table` for static rows:

```scala
table(
  Seq(
    Seq("api", "ready", "3"),
    Seq("worker", "scaling", "8"),
  ),
  Constraint.Length(18),
  Constraint.Fill(1),
  Constraint.Length(6),
)
```

Passing no widths at all is allowed and means "equal columns" — `table(rows)` divides
the area between as many columns as the widest visible row has, which is the quickest
way to get a grid on screen before deciding what each column deserves.

`.header(...)` adds a bold caption row above the data — one label per column, in the
same order as the widths. It costs one row of the area:

```scala
table(rows, Constraint.Length(18), Constraint.Fill(1), Constraint.Length(6))
  .header("Service", "Status", "Replicas")
```

One step below the DSL, a `Table`'s rows accept either a bare `Seq[Line]` — one cell
per column, one terminal line tall, which is all a row could be until now — or a
`TableRow`, and the two can be mixed in the same table:

```scala
import io.worxbend.tui.widgets.{Table, TableRow}

Table(
  rows = Seq(
    TableRow(Seq(Line.raw("api"), Line.raw("ready")), bottomMargin = 1),
    Seq(Line.raw("worker"), Line.raw("scaling")),
  ),
  widths = Seq(Constraint.Length(10), Constraint.Fill(1)),
)
```

A cell is likewise either a bare `Line`, covering one column, or a `TableCell`, which
covers several. That is how a grouped header is drawn — two captions, each centred over
its own pair of data columns:

```scala
Table(
  rows = data,
  widths = Seq.fill(4)(Constraint.Length(8)),
  header = Some(Seq(TableCell(Line.raw("inbound"), 2), TableCell(Line.raw("egress"), 2))),
)
```

A span covers the `columnSpacing` gaps between the columns it merges, so it is one
continuous run rather than several cells with holes, and it pushes the cells after it
along instead of overwriting them. It is clamped to at least one column and to no more
than the columns left in the row, so a span that ran off the end clips rather than
drawing outside the table. Spans are a `Table` feature; `DataTable`'s cells are plain
strings and stay one column each.

`TableRow` carries a `height`, a `topMargin`, a `bottomMargin`, and an optional per-row
`style` that layers over the table's own. The height and margins reserve vertical room —
a blank line under a group, breathing space in a sparse table — they do not wrap text,
because a cell is a single `Line`; the cells are drawn on the first line of the height
and the rest is left blank. A height below one clamps to one, and negative margins clamp
to zero, so a row always occupies at least the line it is drawn on.

`.footer(...)` adds a bold summary row — totals, a record count — pinned to the
*bottom* of the table's area rather than appended after the last data row:

```scala
table(rows, Constraint.Length(18), Constraint.Length(9))
  .header("Service", "Replicas")
  .footer("Total", replicas.sum.toString)
```

Pinning is the point. A totals row that simply followed the last row would float in the
middle of any pane the rows do not fill. It is laid out on the same solved column widths
as the body, which a second `table` stacked underneath could not promise — that one had
to repeat the width constraints and drifted the moment they changed. Like the header, it
costs one row of the area; `DataTable` takes the same `footer` and `footerStyle`, and
gives up one row of its scrollable body for it.

A cell that says where it wants to sit gets its way. A `Line` carries its own optional
alignment — `Line.raw("42").rightAligned`, and likewise `.centered` and `.leftAligned` —
and a `Table` places that cell inside its own column accordingly instead of writing it
flush against the column's left edge. That is how a column of numbers lines up on its
last digit:

```scala
Table(
  rows = amounts.map(amount => Seq(Line.raw(amount.name), Line.raw(amount.total).rightAligned)),
  widths = Seq(Constraint.Fill(1), Constraint.Length(9)),
  header = Some(Seq(Line.raw("Service"), Line.raw("Total").rightAligned)),
)
```

A cell that says nothing about alignment is left-aligned, which is what every cell did
before. The placement is per cell rather than per column so a header caption can be
centred over figures that are right-aligned. Content wider than its column is still
clipped from the right whatever the alignment says, and the arithmetic is in terminal
columns, so a CJK or emoji cell lines up on the width it actually occupies rather than
on its character count.

`DataTable` cells are plain `String`s — they have to be, because the widget sorts and
filters on the text — so there is no `Line` to carry a placement and the widget takes an
`alignments` sequence on its `DataTableOptions` instead, one entry per column by position:

```scala
DataTable.fromStrings(
  columns = Seq("Service", "Replicas"),
  rows = rows,
  widths = Seq(Constraint.Fill(1), Constraint.Length(8)),
  options = DataTableOptions(alignments = Seq(Alignment.Left, Alignment.Right)),
)
```

It places the header caption, the body cells and the footer of that column alike, so a
right-aligned column's title — sort indicator included — stays over its figures. The
sequence may be shorter than the column list, or left empty, and every column it does
not reach stays left-aligned; entries past the last column are ignored. A short sequence
is allowed on purpose, because a table gains a column far more often than it changes an
alignment.

A `table` is a flex container, like `row` and `column`: `.gap(n)` sets the blank
columns between neighbouring cells, and `.center` / `.flexEnd` / `.spaceBetween` place
the block of columns inside the area when fixed widths leave space over. Without one of
those the leftover always trails off the right-hand side.

```scala
table(rows, Constraint.Length(18), Constraint.Length(9)).center.gap(2)
```

The same knob exists one level down on the widgets, as `Table(…, flex = Flex.Center)`
and `DataTable(…, options = DataTableOptions(flex = Flex.Center))`. It changes nothing when a `Fill` or `Min` column
is already absorbing the slack, because then there is no leftover to place.

Use `DataTable` when users need sorting, filtering, selection, scrolling, or paging:

```scala
import io.worxbend.tui.widgets.{DataTable, DataTableState}

private val tableState = DataTableState()
private val deployments = DataTable.fromStrings(
  columns = Seq("Service", "Status", "Replicas"),
  rows = Seq(
    Seq("api", "ready", "3"),
    Seq("worker", "scaling", "8"),
  ),
  widths = Seq(Constraint.Fill(2), Constraint.Fill(1), Constraint.Length(8)),
)

def tableView: Element = dataTable(deployments, tableState)
```

`fromStrings` keys each row by its original position, which is all the identity a plain
text row has. Rows with a real identity of their own — a pid, a path, an id — build as
`KeyedRow(key, cells)` instead and go to the `DataTable[K]` constructor directly, which
is what lets the selection follow a record across a re-sort; see
[Tables & selection](./tables-and-selection#pin-the-selection-to-an-identity).

A `DataTable` cell can carry a style of its own through `cellStyle`, which is asked
about the row's cells and the column index and returns a patch:

```scala
DataTable.fromStrings(
  columns = Seq("Service", "Status"),
  rows = rows,
  widths = Seq(Constraint.Fill(1), Constraint.Length(8)),
  options = DataTableOptions(cellStyle = (row, column) =>
    if column == 1 && row(1) == "FAILED" then Style.Default.withFg(Color.Red) else Style.Default,
  ),
)
```

The patch goes on last, over the table style, the selection highlight and the column and
cell cursors, so a red `FAILED` cell stays red under the selection bar. It is asked about
the row's *contents* rather than its position because filtering, sorting and paging move
rows around between frames — an index names a different record after every sort. The
header and footer never consult it; `headerStyle` and `footerStyle` own those rows.

`tableState.selected` indexes `deployments.visibleRows(tableState)`, not the original
unsorted data. Use that method when opening the selected record — or skip the indexing
entirely with `deployments.selectedKey(tableState)`, which answers the selected row's key
directly.

`DataTableState` also carries a `selectedColumn`, the horizontal half of a
spreadsheet-style cursor. It is independent of `selected`: a column on its own
highlights a whole column, a row on its own highlights a whole row, and the two
together identify one cell. `selectNextColumn(n)` and `selectPreviousColumn(n)` walk it
and clamp at the ends the same way row selection does, and `selectCell(row, column)`
moves both halves at once.

Nothing is drawn for a column cursor unless the widget is given a style for it, so a
table that only selects rows behaves exactly as before:

```scala
DataTable.fromStrings(
  columns, rows, widths,
  DataTableOptions(
    columnHighlightStyle = Some(theme.focus),
    cellHighlightStyle = Some(theme.focus.bold),
  ),
)
```

The three styles are layered in that order — row, then column, then the cell where the
two cross — which is what lets the crossing cell look like neither of them.

By default the selected row is marked only by `highlightStyle`, which reverses its
foreground and background. Two kinds of terminal do not show that: one that ignores
reverse video, and one where the row already carries a background colour the reversal
blends into. `highlightSymbol` adds a text marker instead, the same way `ListView` does:

```scala
DataTable.fromStrings(columns, rows, widths, DataTableOptions(highlightSymbol = "> "))
```

The symbol's display width is reserved as a gutter on *every* row, header included, so
the columns do not shift sideways as the selection moves. The default is `""`, which
reserves nothing and draws exactly the grid a table written before this option existed
drew. The width is measured in terminal columns, not characters, so a two-column CJK
glyph reserves two.

## Text editing

| Widget | State | Notes |
|---|---|---|
| `input` | `TextInputState` | one line, horizontal scrolling, paste folds newlines |
| `textArea` | `TextAreaState` | multiple lines, 2D cursor, scrolling, bounded undo/redo |
| `numberInput` | `TextInputState` | whole numbers only; add `.decimal` to accept one decimal point |
| `templateInput` | `TextInputState` | template field: `#` digit, `A` letter, other characters are literals |
| `autocomplete` | `AutocompleteState` | input plus selectable suggestions and accept callback; `.maxSuggestions(n)` caps the list |
| `filePicker` | `FilePickerState` | navigable file selection; explicitly load its root before showing |

### Local file picker

`FilePickerState(root)` starts unloaded, just like `DirectoryTreeState`. Load its
root once outside `view`, normally in `onStart`:

```scala
import io.worxbend.tui.dsl.*
import java.nio.file.Paths

object ChooseFile extends TuiApp:
  private val picker = FilePickerState(Paths.get("."))

  override def onStart(): Unit = picker.tree.loadVisible()

  def view(using ReactiveScope, Theme): Element = filePicker(picker)
```

Up/Down and the wheel move the selection without accepting it. Enter toggles a
known directory and synchronously loads newly visible branches in the **event
handler**, or writes an accepted file to `picker.chosen`. The footer tracks that
signal. Rendering never loads directories or probes paths, and classification uses
the installed snapshot rather than a fresh filesystem check. Failed loads remain
observable through `picker.tree.loadState(path)`; invalidate the failed branch,
load it explicitly and request a redraw to retry.

This convenience picker is for small local directories: loading a new branch can
block its event loop. For asynchronous acquisition, compose `directoryTree` with
the worker/owner snapshot protocol above and your own acceptance handler. Do not
call `loadVisible()` on a worker or from `view`.

### Editing behavior

`numberInput` accepts shifted digits and bracketed paste at the cursor. It checks the
resulting text: the minus must remain first, and decimal mode permits at most one dot.
Empty text, `-`, `.`, and `-.` are editable partial values (the dot forms require
`.decimal`); the form parser still rejects incomplete numbers on submit. A paste that
would violate these rules is rejected whole, never partly inserted or treated as commands.

`autocomplete` folds pasted newlines to spaces, like `input`, and resets its suggestion
highlight. `templateInput` applies its typing rules to pasted code points, filling slots
and inserting literals; shifted letters and digits are accepted too.

All editing and cursor movement is grapheme-cluster-aware. A Backspace removes one
visible cluster instead of one UTF-16 code unit. Internally both fields measure, scroll,
and draw a row through one shared rule (`ClusterRow`, package-private to `tui-widgets`):
every cluster occupies at least one cell, a cluster that would only half-fit at the right
edge is dropped rather than split, and a zero-width cluster is drawn as a blank. That is
why a wide (CJK) or emoji cluster never drifts the cursor a column further off-screen with
each keystroke — the arithmetic the scroll solver counts in is the same arithmetic the
draw loop advances by, because it is the same code.

## Choices, values, and paging

Three small controls that all keep their value where you already keep it — in a `Signal`
you own — rather than in a state object of their own:

```scala
import io.worxbend.tui.dsl.*

private val environment = Signal(0)
private val volume      = Signal(40)
private val page        = Signal(0)

def controls(using ReactiveScope): Element =
  column(
    radioGroup(Seq("development", "staging", "production"), environment),
    slider(volume, SliderRange.of(min = 0, max = 100, step = 5)),
    paginator(page, total = 12),
  ).gap(1)
```

| Element | Draws | Keys |
|---|---|---|
| `radioGroup(options, selected)` | one row per option, `(•)` beside the chosen one and `( )` beside the rest | Up/Down move the selection, which *is* the choice — there is no separate confirm step |
| `slider(value, range)` | `├───●──────┤` sized to its area | Left/Right move by `range.step`, Home/End jump to the ends; clicking or dragging the track jumps to that position |
| `paginator(current, total)` | dots (`● ○ ○`) while they fit, `page/total` otherwise | Left/Right change page |

Each element writes the `Signal` back when the user changes it, so anything else reading
that signal repaints. Values outside the range are clamped rather than drawn off the
control: a slider at `500` on a `0..100` range sits at the right-hand end, and a paginator
asked for page 99 of 3 marks the last dot. A slider's bounds and its step travel together
in a `SliderRange` — `SliderRange.Percent` is the default, and `SliderRange.of(min, max,
step)` builds any other. `of` orders the bounds either way round and rejects a step below
1, because a slider consumes Left/Right whether or not it can move: a zero step would
swallow both arrows, do nothing, and stop them reaching your own bindings. `paginator` displays pages 1-based while
`current` counts from 0, so page 1 of 12 renders as `1/12`.

A slider needs at least three columns (two brackets and one track cell) and draws nothing
below that; at exactly three, every value sits on the single track column. `paginator`
falls back to the `page/total` counter as soon as the dots would not fit, which for
`total` pages needs `total * 2 - 1` columns, and always uses the counter above ten pages.

Underneath, the raw `w.Slider`, `w.RadioGroup` and `w.Paginator` widgets take plain
values rather than signals, for use outside the DSL.

## Data visualization

A tick-driven dashboard can remain compact:

```scala
import scala.concurrent.duration.*

override def config = RunnerConfig(tickRate = Some(100.millis))
private val tick = Signal(0)
override def onTick(): Unit = tick.update(_ + 1)

def dashboard(using ReactiveScope, Theme): Element =
  val t = tick.get
  val load = (math.sin(t * 0.1) + 1) / 2
  val samples = Vector.tabulate(40)(i =>
    (math.sin((t + i) * 0.25) * 40 + 50).toLong
  )
  val wave = Vector.tabulate(80)(i =>
    (i.toDouble, math.sin((t + i) * 0.1) * 40 + 50)
  )

  column(
    row(
      panel("Load")(gauge(load)).percent(40),
      panel("Throughput")(sparkline(samples)).fill,
    ).length(4),
    panel("Signal")(
      chart(
        Seq(Dataset("wave", wave, graphType = GraphType.Line)),
        xBounds = Bounds(0.0, 80.0),
        yBounds = Bounds(0.0, 100.0),
      )
    ).fill,
  )
```

A `sparkline` scales to the largest value it was handed, so it always uses the full row
height — which also means two of them side by side are *not* comparable. `.max(n)` pins
the top of the scale, and two sparklines pinned to the same ceiling can be read against
each other:

```scala
row(
  panel("Ingress")(sparkline(inbound).max(1000L)).fill,
  panel("Egress")(sparkline(outbound).max(1000L)).fill,
)
```

When the series is longer than the pane is wide the extra points have to go somewhere.
By default the oldest points are kept and the newest are clipped off the right;
`.rightToLeft` reverses that, pinning the latest reading to the last column so the
history scrolls off the left — the behaviour a live metric wants, because the column the
reader is watching never moves. `dualSparkline(upper, lower, SparkDirection.RightToLeft)`
does the same for both halves at once.

A sample that was never taken is not a zero. Plotting a missing reading as `0` says the
metric *was* measured and came out empty, which is a different claim; the raw widget
`Sparkline.ofReadings` takes `Seq[Option[Long]]` and draws the `None`s as gaps instead:

```scala
import io.worxbend.tui.widgets.Sparkline

val trace = Sparkline.ofReadings(readings, max = Some(1000L), absentSymbol = Some("·"))
val element = widget(trace).fill
```

An absent column is left untouched by default, which shows the gap as a hole in the trace
and lets the sparkline be drawn over an existing background; `absentSymbol` marks it
explicitly instead, which reads better when the bars have a visible track behind them.
Absent points are also left out of the scale, so a placeholder value can never pull the
whole trace out of shape.

### Highlighting one sample

A chart has one style for all of its bars, so marking the reading that broke a threshold
used to mean splitting the series across two widgets and lining them up by hand.
`.styleFor` asks a question about each column instead — given its index in the data and
its value, return `Some(style)` for a column that should stand out and `None` for one
that should not:

```scala
sparkline(latencies).max(500L).styleFor { (_, value) =>
  Option.when(value > 250L)(Style.Default.withFg(Color.Red))
}
```

The style is *patched over* the sparkline's own rather than replacing it, so an override
that names only a foreground colour keeps the background and the text attributes the
element already had. The index is the point's position in the data you passed, which is
not the same as its screen column once a `.rightToLeft` trace has started scrolling.

`barChart` takes the same function as a third argument, because it has no fluent builders
of its own to hang it on:

```scala
barChart(load, 3, (_, value) => Option.when(value > limit)(Style.Default.withFg(Color.Red)), false)
```

Only the bars change colour. The label under a bar and the number beside it keep the
chart's label and value styles, so they stay readable whatever the bar is doing.

Each `Dataset` picks how it is drawn with `graphType`. `GraphType.Line` joins the points,
`GraphType.Scatter` leaves them separate, `GraphType.Bar` drops an upright bar from each
point to a baseline, and `GraphType.Area` fills everything between the line and that
baseline — reach for the last one when the *size* of a series matters as much as its
shape. The baseline is the dataset's own `fillToY`, in the data's units; it defaults to
zero, which is what a count or a rate is measured against, and you set it when the
meaningful floor is somewhere else:

```scala
Dataset("above ambient", readings, graphType = GraphType.Area, fillToY = 20.0)
```

`ChartOptions(showLabels = true)` prints the two y bounds beside the vertical axis. The
numbers get a *gutter* of their own — a strip of columns reserved to the left of the
axis, as wide as the widest of the two labels — and the axis and the plot both move
right by that much. Earlier versions wrote the labels at the first plot column, where a
four-digit bound painted over the leftmost points of every series. `labelAlignment`
decides where a shorter number sits inside that strip: `Alignment.Right` (the default)
presses it against the axis line, `Alignment.Left` against the frame, `Alignment.Center`
between the two.

```scala
chart(
  Seq(Dataset("wave", wave)),
  xBounds = Bounds(0.0, 80.0),
  yBounds = Bounds(0.0, 100.0),
  options = ChartOptions(showLabels = true, labelAlignment = Alignment.Left),
)
```

A pane too narrow to spare the gutter drops the labels rather than the plot, so a chart
squeezed into a small pane stays a chart instead of becoming a column of numbers.

`xLabels` and `yLabels` name positions *along* an axis rather than the axis itself —
timestamps, dates, category names. They are spread across the axis rather than placed at
data coordinates: the first sits at the axis origin, the last at the far end, and any in
between fall on their own even share of the extent, so three labels read as start, middle
and end of the range.

```scala
chart(
  Seq(Dataset("throughput", points)),
  xBounds = Bounds(0.0, 60.0),
  yBounds = Bounds(0.0, 100.0),
  options = ChartOptions(xLabels = Seq("0s", "30s", "60s"), yLabels = Seq("0", "50", "100")),
)
```

The x labels take one row from the plot, under the axis and above the x title if there is
one. The y labels are right-aligned in the same gutter `showLabels` uses and *replace* its
two bound numbers, because an axis labelled twice is an axis labelled neither way. A label
with no room for it is left out rather than cut down — half of `2026-09-01T12:00` is not a
shortened timestamp, it is a different one, and nothing on screen would say so.

The numbers say how much; `xTitle` and `yTitle` say of what. Each takes a row of its own
— the y title above the plot, the x title below the axis — rather than being written over
the data, so a title can never hide a point. An area with no rows to spare for them draws
nothing at all rather than a chart with no plot in it:

```scala
chart(
  Seq(Dataset("latency", points)),
  xBounds = Bounds(0.0, 60.0),
  yBounds = Bounds(0.0, 250.0),
  options = ChartOptions(xTitle = Some("seconds"), yTitle = Some("ms")),
)
```

Every `Dataset` carries a `name`. With `showLegend = true` the named ones are listed as
a key in the top-right of the plot, one per row, each entry drawn in that dataset's own
style — which is what tells three series apart when all you had before was three
colours. A dataset with an empty name is left out of the key:

```scala
chart(
  Seq(
    Dataset("cpu", cpuPoints, style = Style.Default.withFg(Color.Red)),
    Dataset("mem", memPoints, style = Style.Default.withFg(Color.Blue)),
  ),
  xBounds = Bounds(0.0, 80.0),
  yBounds = Bounds(0.0, 100.0),
  options = ChartOptions(showLegend = true),
)
```

A `Dataset` can also pick its own drawing surface. `resolution` and `marker` on a dataset
override the chart-wide pair for that one series, so a braille line and a cell-resolution
scatter can share one plot — a difference that survives a monochrome terminal, which colour
alone does not:

```scala
chart(
  Seq(
    Dataset("signal", signal, resolution = Some(CanvasResolution.Braille)),
    Dataset("samples", samples, graphType = GraphType.Scatter, marker = Some("*")),
  ),
  xBounds = Bounds(0.0, 80.0),
  yBounds = Bounds(0.0, 100.0),
)
```

Series that end up on the same surface are drawn together in one pass; series on different
surfaces are drawn in separate passes, in the order those surfaces first appear in the
sequence, so where two of them claim the same cell the later one wins. That order is fixed
rather than left to a hash, because two runs of the same chart disagreeing about which
series is on top would be a frame no test could pin down. A chart whose datasets override
nothing is still exactly one pass, drawing exactly what it always drew.

The key is painted over the plot, so it costs the data no space — but only while it
stays small. The widget-level `Chart` carries `hiddenLegendConstraints`, a pair of
`Constraint`s for `(width, height)`, on its `ChartOptions`, and the key is dropped
entirely unless it satisfies both. The default lets it claim up to a quarter of the plot
in either direction, so a pane that shrinks loses its key rather than its data. Dropping
it is all-or-nothing on purpose: half a key says less than none, because a reader cannot
tell which series the missing rows belonged to. Widen the allowance when you would rather
keep the names:

```scala
Chart(
  datasets,
  xBounds = Bounds(0.0, 80.0),
  yBounds = Bounds(0.0, 100.0),
  options = ChartOptions(
    showLegend = true,
    hiddenLegendConstraints = LegendConstraints(Constraint.Percentage(50), Constraint.Percentage(50)),
  ),
)
```

Down at the widget, and at the `chart(...)` element factory alike, the two world ranges
are `Bounds` values rather than bare `(min, max)` tuples, and everything else — labels,
legend, titles, marker, resolution — bundles into one `ChartOptions`.

### Comparing several series side by side

`barChart` shows one series across a set of categories. When there are several series to
compare in each category — this quarter against last, two runs of the same benchmark — the
raw widget `GroupedBarChart` clusters the bars instead:

```scala
import io.worxbend.tui.widgets.{BarGroup, GroupedBar, GroupedBarChart}

val chart = GroupedBarChart(
  Seq(
    BarGroup("q1", Seq(GroupedBar("plan", 40, plan), GroupedBar("actual", 34, actual))),
    BarGroup("q2", Seq(GroupedBar("plan", 55, plan), GroupedBar("actual", 61, actual))),
  ),
  groupGap = 2,
)
val element = widget(chart).fill
```

`barGap` separates the bars inside one group and `groupGap` separates one group from the
next. The colour is per bar, because in a grouped chart the colour is what says *which
series* a bar belongs to, and the same series style repeats in every group —
`SeriesPalette` gives one style per series to use that way. A bar left at `Style.Default`
takes the chart's own `barStyle`, and `BarGroup.of("q1", "plan" -> 40, "actual" -> 34)` is
the shorthand for a group whose bars carry no styles at all.

Every bar in the chart is measured against one shared scale — `max`, or the largest value
anywhere in the data — because a bar's height only means anything next to the bars beside
it. The group label goes centred under the whole group rather than under one bar, so it has
the group's width to fit in; a group that does not fit whole in the area is dropped rather
than half-drawn, the same rule `barChart` follows for a single bar.

### Bar glyphs

A terminal has no pixels, so a bar two and a half cells tall is drawn as two full cells and
one half-filled one. The glyphs that make that possible are the eight Unicode block
elements `▁▂▃▄▅▆▇█`, and `BarSet` is the record that holds them — one glyph per eighth of a
cell, plus an optional glyph for the cells the bar does not reach. `Sparkline` and
`BarChart` both take one as `barSet`:

```scala
Sparkline(samples, barSet = BarSet.Ascii)   // '#', for a terminal with no block elements
BarChart(data, barSet = BarSet.Halves)      // three levels: empty, half a cell, a whole cell
BarChart(data, barSet = BarSet.Solid)       // whole cells only, no sub-cell precision
Sparkline(samples, barSet = BarSet.uniform("*", empty = Some("·")))
```

The default, `BarSet.Eighths`, has no empty glyph, so the cells above a bar are left exactly
as they were — that is what lets a chart be drawn over an existing background. The other
built-in sets do have one, so each bar gets a visible track behind it, painted in the bar's
own style. Every glyph in a set must be a single terminal column wide, because a bar is
measured in whole columns and a two-column glyph would spill into the bar next door.

For custom plots, `canvas(xBounds, yBounds)(shapes*)` provides points, segments,
polylines, rectangles, and circles.

### Naming positions along a chart's x axis

`Chart` prints its two y bounds in a gutter left of the vertical axis. The horizontal axis
has no such pair of numbers, because the interesting labels there are usually not numbers
at all — timestamps, dates, weekdays. `xLabels` supplies them:

```scala
Chart(
  datasets,
  xBounds = Bounds(0.0, 24.0),
  yBounds = Bounds(0.0, 100.0),
  options = ChartOptions(
    showLabels = true,
    xLabels = Seq("00:00", "12:00", "24:00"),
  ),
)
```

The labels are spread across the plot's columns rather than placed at data coordinates:
the first at the left end of the axis, the last at the right end, any in between centred
on their own even share of the width. Three of them therefore read as the start, middle and
end of the range. They take a row from the plot, just under the axis and above the x title
if there is one, so a label can never be drawn over a point. A label that does not fit, or
that would touch the label before it, is left out — half a timestamp reads as a different
time, and two labels running together read as neither.

### Labelling a plot

`CanvasLabel(x, y, line)` pins text to a **world** coordinate — the same coordinate
system the shapes use, with y pointing up. That is what makes it worth having over
writing the text yourself beside the canvas: when the bounds change, the label moves with
the thing it names instead of staying where you put it.

```scala
import io.worxbend.tui.widgets as w

widget(
  w.Canvas(
    xBounds = w.Bounds(-180.0, 180.0),
    yBounds = w.Bounds(-90.0, 90.0),
    shapes = coastline,
    labels = Seq(w.CanvasLabel(13.4, 52.5, Line("Berlin"))),
  )
)
```

`w.CanvasLabel` is spelled through the `widgets` alias rather than re-exported from
`io.worxbend.tui.dsl`, because you are already down at the widget tier to reach `labels`
at all — `canvas(...)` at the element layer does not expose them.

Labels are drawn after every shape, so a dot can never punch a hole through the text that
names it, whichever order you listed them in. Text is placed at whole-*cell* granularity
whatever the resolution — there is no half of a cell for a character to sit in — and it
is clipped at the canvas's own right edge, cut between grapheme clusters so a label
ending in a wide character never half-prints.

### Filled shapes

Three shapes fill an area rather than trace an outline.

`Shape.FilledLine(x1, y1, x2, y2, baselineY)` draws a segment and everything between it
and the horizontal line `baselineY`. `Shape.FilledPolyline(points, baselineY)` does the
same for a whole series — that is an area chart:

```scala
canvas(Bounds(0.0, 24.0), Bounds(0.0, 100.0))(
  Shape.FilledPolyline(readings, baselineY = 0.0, style = Style.Default.fg(Color.Cyan)),
)
```

The baseline does not have to be inside the canvas bounds. A baseline below the visible
range fills to the bottom edge rather than drawing nothing, because "down to zero" still
means "down" when zero is off-screen.

`Shape.FilledRectangle(x, y, width, height)` is a solid box — a highlighted band, a
selection region, a heat cell. It is a separate shape rather than a flag on
`Shape.RectangleShape`, which still draws only the four edges.

All three fill by scanline on the canvas's own dot grid, so the fill is solid at every
resolution and costs one paint per dot rather than one per guessed step.

`Shape.WorldMap()` paints the world's coastlines. Its coordinates are geographic —
longitude from −180 to 180 on x, latitude from −90 to 90 on y — so the canvas has to be
given those same bounds, or the coastlines land somewhere no map has them:

```scala
canvas(Bounds(-180.0, 180.0), Bounds(-90.0, 90.0))(
  Shape.WorldMap(MapResolution.High),
  Shape.Points(Seq((-0.13, 51.51), (139.69, 35.69)), Style.Default.fg(Color.Yellow)),
).braille
```

The projection is plate carrée — longitude straight onto x, latitude straight onto y —
which is what the raw coordinates give and all a terminal, whose cells are already twice
as tall as they are wide, can honestly claim. A narrower window works the way it does for
every other shape: bounds of `(-11.0, 32.0)` and `(35.0, 72.0)` draw Europe filling the
pane and clip everything else away.

`MapResolution` picks how densely the outline is sampled: `Low` (the default) is about a
thousand points, `High` about five thousand. They are the same coastlines at two
densities, so the map does not change shape between them. Pair `High` with
`CanvasResolution.Braille` on a large pane, where each cell carries eight dots and the
lower-detail outline starts to look polygonal; leave the default anywhere smaller, where
the extra points cost four times the drawing to produce the same picture.

The coordinates themselves live in a generated file, `WorldTable.scala`, built by
`tools/generate-world-table.py` from the public-domain dataset behind every terminal world
map. Do not edit it by hand.

### Canvas resolution

A canvas draws sub-pixels — several drawable dots inside one terminal cell — by picking a
glyph that has the right dots filled in. `CanvasResolution` chooses how finely it does
that, and the choice is a trade between resolution and how likely the terminal's font is
to have the glyphs at all. A missing glyph draws as a replacement box, so pick the finest
one you are willing to see fail.

| Resolution | Dots per cell | Looks like | Font needed |
| --- | --- | --- | --- |
| `Cell` | 1 × 1 | your `marker` glyph | anything |
| `HalfBlock` | 1 × 2 | `▀ ▄ █` | anything |
| `Quadrant` | 2 × 2 | `▘ ▚ ▟ █` | anything |
| `Sextant` | 2 × 3 | `🬀 🬞 🬺` | Unicode 13 (2020) |
| `Braille` | 2 × 4 | `⠁ ⡇ ⣿` | anything |
| `Octant` | 2 × 4 | `𜺨 𜵱 █` | Unicode 16 (2024) |

Resolution is not the only difference. Braille draws *sparse dots* with visible gaps
between them, which reads well as a line. The block-drawing resolutions fill their whole
sub-pixel, which reads as a solid area — so a filled chart looks better under `Octant`
than under `Braille` even though the two pack the identical 2 × 4 grid.

`Quadrant` is the useful middle: four times the area of a single cell, solid rather than
dotted, and in every font that already has the half blocks.

`HalfBlock` has one property none of the finer resolutions has: it carries **two colours
per cell**. A terminal cell has one foreground and one background colour, and `▀` fills
its top half with the foreground while its bottom half shows the background — so the two
halves of a half-block cell can be coloured independently. A braille cell packs eight
dots, but all eight must share a single colour. If your points carry colour and two of
them can land in the same cell, `HalfBlock` keeps both where `Braille` keeps only the
last one drawn.

Segments — and therefore polylines and rectangle outlines — are clipped to the canvas
bounds and then drawn one dot at a time on the sub-cell grid. Two consequences worth
knowing. A line that starts far outside the visible world range still draws a solid run
up to the edge, rather than the few of its samples that happened to land inside. And how
finely a line is sampled follows the canvas resolution, not the size of your world units:
bounds of `0.0` to `1.0` and bounds of `0.0` to `1000000.0` draw the same line, and the
second costs no more than the first.

Coordinates must be finite. A `NaN` or infinite point is dropped rather than clamped, and
a segment with a non-finite endpoint is skipped entirely — its direction is undefined, so
drawing from its finite end would put the line somewhere it does not go.

### Writing your own shape

A `Shape` is one method, `draw(painter: Painter): Unit`. The painter speaks two
coordinate systems and hands you both.

*World* coordinates are the numbers your data is already in — whatever `xBounds` and
`yBounds` the canvas was given, with y pointing **up** the way a graph's y axis does.
`painter.paint(x, y, style)` marks the sub-pixel a world point falls in.

*Dot* coordinates are integer positions on the sub-cell grid the canvas actually lights.
Column 0 is the left edge and row 0 is the **top** edge (the y flip has already
happened), and how many dots a cell holds depends on the resolution — one at cell
resolution, two stacked at half-block, eight (2 across, 4 down) at braille.

- `painter.bounds` — the world rectangle, as `((xMin, xMax), (yMin, yMax))`.
- `painter.dotSize` — the grid extent, as `(columns, rows)`.
- `painter.getPoint(x, y)` — the dot a world point falls in, or `None` when it is
  outside the bounds or not a finite number.
- `painter.paintDot(column, row, style)` — mark one dot. Dots off the grid are dropped,
  not clamped.

Working in dot space is what lets a shape fill or trace an area exactly once, instead of
guessing a sample count in world units and hoping it matches the surface:

```scala
final case class FilledBox(x1: Double, y1: Double, x2: Double, y2: Double, style: Style) extends Shape:
  def draw(painter: Painter): Unit =
    val corners =
      for
        (c1, r1) <- painter.getPoint(x1, y1)
        (c2, r2) <- painter.getPoint(x2, y2)
      yield (c1, r1, c2, r2)
    corners.foreach { (c1, r1, c2, r2) =>
      for
        column <- math.min(c1, c2) to math.max(c1, c2)
        row    <- math.min(r1, r2) to math.max(r1, r2)
      do painter.paintDot(column, row, style)
    }
```

### Marking days on a calendar

`calendar(year, month, selected)` draws a month grid. The widget behind it,
`Calendar`, takes a `dayStyles: Map[LocalDate, Style]` so that any number of
individual dates can be drawn differently — appointments, public holidays, today,
the days of a streak — instead of only the one day the cursor is on:

```scala
import io.worxbend.tui.core.Style
import io.worxbend.tui.widgets.Calendar
import java.time.LocalDate

private val busy = Map(
  LocalDate.of(2026, 7, 2)  -> Style.Default.bold,
  LocalDate.of(2026, 7, 14) -> Style.Default.underline,
)

widget(Calendar(2026, 7, selected = Some(14), dayStyles = busy))
```

The map is keyed by `LocalDate`, not by day-of-month, so one map can be handed to
several months' grids and each takes only the dates that belong to it. Styles layer
outermost-last: the calendar's own `style`, then the date's entry, then
`highlightStyle` for the selected day — so the cursor is still visible when it lands
on a marked date.

`Calendar` also decides how the grid is framed and which language it speaks:

| Parameter | Default | Effect |
|---|---|---|
| `showTitle` | `true` | the `July 2026` row; switching it off gives its row back to the grid |
| `showWeekdays` | `true` | the `Mo Tu We ...` row, likewise |
| `firstDayOfWeek` | `DayOfWeek.MONDAY` | which weekday the leftmost column is |
| `locale` | `Locale.ENGLISH` | language of the month name and weekday abbreviations |
| `showSurroundingDays` | `false` | fill the empty leading/trailing cells with the neighbouring months' days |

With `showSurroundingDays = true` the grid has no blank corners: the cells before
the 1st and after the last day of the month are filled with the days of the months
either side, drawn in `surroundingStyle` (dimmed by default) so they read as context.
They are never selectable — `selected` names a day of the month being shown — but
`dayStyles` does reach them, so a marked date keeps its appearance in whichever
month's grid it appears.

Turning a header off does not blank its row, it removes it: a grid with neither
header starts its first week on the very first row of the area and needs two rows
fewer, which is what makes a bare month grid fit a narrow sidebar. `locale` defaults
to English rather than to `Locale.getDefault` on purpose — a widget whose output
depends on the machine it runs on cannot be checked by comparing frames — so pass
`Locale.getDefault` when you do want the machine's language.

## Feedback and motion

Animations need one thing from you — a tick rate — and nothing else:

```scala
override def config: RunnerConfig = RunnerConfig(tickRate = Some(80.millis))

def view(using ReactiveScope, Theme): Element = spinner("deploying")
```

There is no counter to declare, advance, or thread through. The animated elements
read the ambient `AnimationClock`, and because that read is *tracked*, only a view
actually rendering an animation is repainted by the ticks — a screen with no spinner
on it is left alone. Hand-rolling `ticks.get` in the view repaints the whole app
forever instead, whether anything is moving or not.

- `spinner(label)` and `animatedText(content)` animate on the ambient clock;
- `skeleton()`, `indeterminateBar()`, and `marquee(content)` show work without known
  progress — a ticker sets its reading rate with `marquee(headline).speed(6.0)` in cells
  per second, the blanks between repetitions with `.gap(8)`, and `.period(10.seconds)`
  says the rate as a lap time instead, so two tickers of different lengths can be put in
  step;
- `progressBar(ratio)`, `progressBar(done, total)` and `gauge(ratio)` show bounded
  progress;
- toasts, splash screens, and post-render effects live in the app/runtime layer.

Every animation is a pure function of elapsed time, so each of these has a
`…At(elapsed)` twin — `spinnerAt`, `skeletonAt`, `indeterminateBarAt`, `marqueeAt`,
`animatedTextAt` — for driving from a clock of your own. `AnimationClock.freezeAt(...)`
pins the clock so a test can assert an exact frame without waiting for wall time.

Speeds are wall-clock, not tick counts. A preset that holds each frame for 80ms looks
the same in an app ticking every 50ms and one ticking every 200ms; expressing speed in
ticks would make the same spinner read as sluggish in one and frantic in the other.

Use a real percentage when you know one; use an indeterminate widget only when you
do not. Respect reduced-motion needs by offering a config option or static fallback —
`IndeterminateMotion.Pulse` and `SpinnerPreset.Ellipsis` are the quietest built-ins.

### Spinner presets

`spinner` defaults to `SpinnerPreset.Dots`; `.preset(...)` swaps the animation.

```scala
spinner("deploying").preset(SpinnerPreset.Arc)
```

Presets are grouped by the glyph repertoire they need, which is what decides whether
one is safe on a given terminal:

| Catalogue | Presets | Needs |
|---|---|---|
| `AsciiPresets` | `line`, `ellipsis`, `bouncing-bar` | nothing — safe anywhere, including a log |
| `BraillePresets` | `dots`, `dots-orbit`, `dots-pulse`, `dots-ring`, `dots-wave` | a font with the braille block |
| `BlockPresets` | `grow-vertical`, `grow-horizontal`, `quadrant`, `square`, `circle-halves`, `circle-quarters`, `arc`, `triangle`, `pipe`, `star`, `toggle`, `points`, `arrow`, `bouncing-ball`, `layers`, `balloon` | block, box-drawing and geometric-shape glyphs |
| `EmojiPresets` | `moon`, `earth`, `clock`, `hearts` | color emoji; **two columns per frame** |

A preset carries its own speed as `frameDuration`. `.slowedBy(2)`, `.reversed`,
`.atFps(8)` and `.withFrameDuration(...)` derive variants — `.slowedBy` and `.atFps`
are on the element too, so `spinner("x").atFps(4)` needs no preset value. 
`SpinnerPreset.byName("arc")` resolves one from a config file or a `--spinner` flag.

Frames are padded to the widest frame in the set, so a preset with ragged frames
(`ellipsis`, `bouncing-bar`) never shoves its label back and forth.

### Progress-bar presets

`.preset(...)` picks which glyphs a bar draws with. The nine built-ins:

| Preset | Look | Sub-cell |
|---|---|---|
| `Line` (default) | `━━━━────` | no |
| `Blocks` | `████▌` | yes — eighth blocks |
| `BlocksShaded` | `████▌░░░` | yes |
| `Shaded` | `███▓░░░` | yes |
| `Ascii` | `####----` | no |
| `Arrow` | `===>----` | no |
| `Dots` | `⣿⣿⣷⢀⢀` | yes |
| `Baseline` | `▄▄▄▁▁▁` | no |
| `Minimal` | `▪▪▪···` | no |

A preset with `partials` draws progress finer than its own cell grid — eight partial
blocks turn a 20-column bar into 160 distinguishable positions. That also changes the
rounding, deliberately: sub-cell presets **floor** the fill and render the remainder as
the boundary glyph, so the bar never claims progress that has not happened, while
whole-cell presets round to nearest because that is the closest a cell can get.

The block gauge draws from the same vocabulary. By default `Gauge` fills whole cells
with a reversed blank, which rounds to the nearest column — a 20-column gauge has 21
distinguishable states. Hand it a preset and it draws that preset's glyphs instead,
so the same bar gets the eighth-block boundary cell (161 states) or an ASCII `#`
bar for a terminal with no Block Elements font:

```scala
import io.worxbend.tui.widgets.{Gauge, ProgressPreset}

widget(Gauge(0.37, preset = Some(ProgressPreset.Blocks)))  // ███▋
widget(Gauge(0.37, preset = Some(ProgressPreset.Ascii)))   // ####------
```

With a preset the glyph carries the colour rather than the cell background, so
`fillRamp` tints the foreground and `Reverse` is dropped from `filledStyle` — a
reversed full block paints the cell's background colour over the whole cell, which
would be an invisible bar.

`indeterminateBar` draws from the same vocabulary and adds `.motion(...)`:

| Motion | Behaviour |
|---|---|
| `Bounce` (default) | a segment sliding to the far edge and back |
| `Sweep` | a segment leaving one edge and reappearing at the other |
| `Comet` | a bright head with a tail fading behind it |
| `Pulse` | the whole track brightening in place — no travel, the quietest option |

`.period(duration)` sets how long one full traverse takes, so several bars can be made
to move in step regardless of their widths.

### Captions

A bar's caption is a `ProgressLabel`, not a nullable string:

| Element call | Renders |
|---|---|
| `progressBar(r)` | `42%` — the default |
| `progressBar(r).label("syncing")` | `syncing` |
| `progressBar(r).labelled("syncing")` | `syncing 42%` |
| `progressBar(r).bare` | nothing; the bar takes the whole row |

`gauge` carries the same trio — `gauge(r)`, `gauge(r).label("syncing")`,
`gauge(r).labelled("syncing")`, `gauge(r).bare` — so the two progress meters are
captioned the same way whichever one a view reaches for. They also draw the same two
colours: both read `track` and `fill` from the theme's `loading` palette, so a gauge
next to a progress bar cannot come out in a different scheme.

`Gauge`'s caption sits *on* the bar, so its colours have to be derived from the bar
rather than fixed. Where the caption overlaps the fill it is drawn in the fill's own
two colours swapped over; where it overhangs the track it keeps the widget's `style`.
That is what stops a caption from disappearing into a reversed bar or into a bright
`fillRamp` colour. Pass `labelStyle = Some(...)` to override both cases with one
style of your own.

### Theming the animations

Colors come from the ambient `Theme`'s `loading` palette, resolved where the element
is written, so re-theming an app re-themes its spinners, gauges and bars with no
call-site change:

```scala
final case class LoadingTheme(
    spinner: Style,   // the moving glyph
    label: Style,     // the caption beside it
    track: Style,     // the unfilled part of a bar
    fill: Style,      // the filled part
    band: Style,      // a skeleton's sweeping highlight
    fillRamp: Option[ColorRamp] = None,
)
```

A custom theme gets a coherent palette for free from its own semantic styles:

```scala
val mine = Theme.Dark.copy(
  name = "mine",
  loading = LoadingTheme.from(accent = myAccent, muted = myMuted, surface = mySurface),
)
```

Anything set at the call site layers on top of the theme, and the glyph and the label
style independently:

```scala
spinner("syncing").fg(Color.Magenta)              // recolors the glyph, not the label
progressBar(used, total).ramp(ColorRamp.Traffic)     // green → amber → red as it fills
```

`ColorRamp` takes any number of stops, which matters: interpolating straight from green
to red passes through a muddy brown, so the common progress ramp needs amber in the
middle. Built-ins are `Traffic` (green→amber→red), `Recovery` (its reverse), and `Heat`.

A ramp is opt-in rather than a theme default because its meaning is call-site specific:
`Traffic` reads as "filling up towards trouble", which is right for disk usage and
wrong for a download.

The showcase's **Loading** tab renders every preset at once — run
`./mill examples.showcase.run` to pick one by eye.

### Shape spinners

`spinner` says "working" inside one glyph. When there is a pane to fill rather than a
status line, three widgets say it in an area — all on the same ambient clock, all pure
functions of elapsed time.

```scala
orbitSpinner()                              // a circle with a comet arc, fitted to its area
orbitSpinner().path(OrbitPath.Square).radius(4)
linearSpinner().bouncing                    // a head travelling a one-cell track
spinnerGrid().preset(SpinnerPreset.DotsRing)
```

**`orbitSpinner`** draws a figure at sub-cell resolution with a bright arc chasing round
it. `radius` is in **dots**, not cells, because that is the resolution the shape is drawn
at — a radius in cells could not express the difference between the two smallest legible
rings. `sweep` is the fraction of the lap that is lit, not a dot count, so the arc
subtends the same angle at every radius. Down at the widget the constructor takes just
`elapsed`; every knob the fluent builders below set bundles into one
`OrbitSpinnerOptions`, so `OrbitSpinner(elapsed, OrbitSpinnerOptions())` is the figure
the no-argument element draws.

| Method | Effect |
|---|---|
| `.path(OrbitPath.Circle)` or `.path(OrbitPath.Square)` | the loop the arc travels |
| `.radius(dots)` | pins the size; unset, it fills its area |
| `.sweep(fraction)` | how much of the lap is lit — `.sweep(1.0).solid` is a static "queued" ring |
| `.solid` or `.ramp(ColorRamp.Heat)` | a uniform window, or a graded comet tail |
| `.trail(OrbitTrail.Solid)` or `.trail(OrbitTrail.Comet(ramp))` | the shading enum the two presets above set |
| `.thickness(dots)` | thickens arc and path inwards; worth it above ~radius 8 |
| `.reversed`, `.period(d)` | direction and revolution time |
| `.markers("*")` or `.halfBlocks` | the ASCII and no-braille-font floors |

**One style per cell — the constraint this widget is shaped around.** A braille cell packs
eight dots but carries one `Style`, so dot resolution is 2×4 per cell while *colour*
resolution is 1 per cell. Two rules follow, both chosen rather than fallen into: masks are
OR-ed, so a cell holding both arc and path keeps every dot and the resting ring never
erodes; styles take the brightest dot, so such a cell reads as arc and the head is never
overwritten by its own tail. The budget, concretely: a radius-4 braille ring is 24 dots but
only 11 cells, so a ramped comet shows 11 shades, not 24. The alternative — one dot per
cell, so colour and geometry agree — throws away the resolution braille was chosen for.

A consequence worth knowing: because the mask never erodes, the **glyphs are static** and
the entire animation lives in the per-cell style. On a terminal rendering neither colour
nor dim, an orbit spinner is a still ring. Reach for `spinnerGrid` there instead.

**`linearSpinner`** runs a head along a one-cell track, clipping itself to a single row or
column so it cannot smear. Its three knobs are deliberately orthogonal — `LinearPath` is
the walk, `LinearTrail` is the shading, `trailSlots` is the length — so a bouncing head
with a comet tail and a wrapping window of solid slots are the same widget with different
arguments, rather than two modes in which half the options are silently ignored. A bounce
has no dwell frame at the turn; the extreme slots are visited once per cycle and the
interior twice.

| Method | Effect |
|---|---|
| `.vertical` | runs the track down a column (`LinearAxis.Vertical`) instead of along a row |
| `.bouncing` | the head turns back at each end (`LinearPath.Bounce`) rather than wrapping |
| `.reversed` | runs the head the other way (`LinearFlow.Backward`) |
| `.solid` | a uniform lit window behind the head (`LinearTrail.Solid`) instead of a fading tail |
| `.period(d)` | one full cycle — a traverse when wrapping, a round trip when bouncing |

**`spinnerGrid`** turns any `SpinnerPreset` into an area-filling block by giving each slot
a time offset. It *consumes* the preset catalogue rather than extending it, which is the
point of it existing: a preset is a function of time alone and can never carry a spatial
offset, yet every preset already in the catalogue becomes an area animation the moment it
is put here. `.phase(...)` chooses `GridPhase.Uniform` (lockstep — the reduced-motion
member), `GridPhase.Diagonal(framesPerCell)`, or `GridPhase.Radial(framesPerCell)`. It
is also the one place in this family where per-slot colour is
free, because every slot holds exactly one frame — `.ramp(...)` shades the block by phase
with none of the compromise above.

The showcase's **Loading** tab renders all of them animating; the gallery scrolls.

## Animated text

`animatedText(content)` applies a time-based effect to a string, on the same ambient
clock as the spinners. `.effect(...)` chooses which:

| Effect | What it does | Notes |
|---|---|---|
| `Wave(crestWidth, cellsPerSecond)` | a bright crest travelling through the text | the default; survives on a terminal with no color, because it moves emphasis rather than hue |
| `Typewriter(charactersPerSecond, cursor)` | reveals one grapheme at a time behind a blinking cursor | the cursor stops blinking once the text is complete, so a finished line reads as finished |
| `Gradient(ramp, cellsPerSecond)` | colors each grapheme from a `ColorRamp`, scrolling the ramp | the text stays put; the ramp moves through it |
| `Shimmer(sparkle, period)` | a highlight sweeping across with a sparkle at its head | rests between sweeps, which is what stops it reading as a strobe |
| `Bounce(trail, cyclesPerSecond)` | a vertical wave through the text with a dimmed after-image | the only effect that needs more than one row; at one row it degrades to a flat line |

```scala
animatedText("Deploying…").effect(TextEffect.Typewriter())
animatedText("glyphora").effect(TextEffect.Gradient(ColorRamp.Heat)).length(1)
animatedText("LOADING").effect(TextEffect.Bounce(trail = 2)).length(4)
```

Each effect carries its own knobs rather than `AnimatedText` carrying the union of all
of them, so you cannot set a typewriter's cursor on a shimmer and have it quietly
ignored. `heightAt` reports the rows an effect needs — only `Bounce` asks for more than
one.

## Notices and badges

`NoticeLevel` — `Success`, `Info`, `Warning`, `Error` — is the single severity
vocabulary, shared by notices, badges, and `TuiApp.notify`'s toasts. Its `icon` is one
column wide for every level so a stacked column of notices stays aligned, and its `tag`
(`OK`, `INFO`, `WARN`, `FAIL`) is the short form for a badge.

```scala
notice("8 services updated", NoticeLevel.Success).at(LocalTime.now())
notice("could not reach the registry", NoticeLevel.Error).wrapped
```

Renders as `[12:04:31] ✔ 8 services updated`. The timestamp is **passed in**, never read
by the widget: a widget that called `LocalTime.now()` would render a different frame on
every repaint, so a redraw triggered by something else entirely would silently change
the displayed time. Stamp the message when the event happens, which is the moment the
reader cares about. `.wrapped` lets a long message grow instead of clipping.

`badge(label)` is a short inline label, in three variants that read at different
volumes:

| Variant | Draws | Use when |
|---|---|---|
| `Solid` (default) | reversed text, padded — ` NEW ` | the status must not be missed |
| `.outline` | bracketed, no block of color — `[NEW]` | it sits inside a line of prose |
| `.dot` | a colored dot then plain text — `● ready` | every row in a list has one and none should shout |

```scala
row(text("api"), badge(NoticeLevel.Error).outline)   // api [FAIL]
row(text("worker"), badge("3 pending").dot)
badge("BETA").fg(Color.Magenta)
```

`badge(level)` uses the level's own tag and color; `badge(label)` takes the theme's
accent and accepts any `.fg(...)`. Both report a `widthAt`, so a caller can size a
column for them rather than guessing.

## Drop to a raw Widget

The DSL is not a separate renderer. Any core `Widget` can become a leaf:

```scala
import io.worxbend.tui.core.*
import io.worxbend.tui.widgets.Paragraph

val raw = Paragraph(Text.raw("Rendered directly"), overflow = Overflow.Wrap)
val element = widget(raw).fill
```

`Paragraph` can show a window into a longer document rather than the whole of it:

```scala
val page = Paragraph(Text.raw(longDocument), overflow = Overflow.Wrap, scrollY = offset)
```

`scrollY` skips that many rows *after* wrapping, so scrolling a reflowed document by one
moves the view by exactly one screen row whatever the width — something `scrollView`
cannot express, because it scrolls a rendered widget and has to be told its content
height. `scrollX` does the same for columns and only has anything to skip under
`Overflow.Clip`, since a wrapped row is never wider than the area. Both are clamped at
zero, and scrolling past the end leaves the area blank instead of failing. The number to
hand a `scrollbar` beside it is `heightAt(width)`: the height of the whole text, which
the scroll offset deliberately does not reduce.

A `Paragraph`'s `style` is painted across its whole area, so a background color covers the
blank columns after a short line and the rows below the last line, not only the cells that
have a character in them.

A widget that knows how much room its content needs says so by implementing
`io.worxbend.tui.core.Measured` — `heightAt(width)` for content whose height depends on
the width it wraps at, `widthAt(height)` for content sized the other way. Returning
`None` means "I cannot say", and callers must treat that as unmeasurable rather than as
a size. `widget(...)` needs no measurement wiring of its own: a wrapped `Paragraph`,
`Markdown`, `Notice`, `Badge`, `Spinner`, `BigText`, `AnimatedText` or `Tooltip` already
answers, so a `scrollView` over one scrolls the full content. `Paragraph` answers
`widthAt` too: the longest of its lines, measured in terminal columns, which is the
narrowest area in which nothing is clipped and nothing has to wrap.

Measuring is not the same as claiming, though, and only the measurement pass consults
`Measured`. In a `row` or a `column` a wrapped widget claims everything its siblings
leave over, because the layout solver has not picked a width yet and so cannot ask
`heightAt`. Say the height with `.rows(n)`:

```scala
column(
  text("above"),
  widget(myOneRowWidget).rows(1), // without this it takes every leftover row
  text("below"),
)
```

Prefer `.rows(n)` over `.length(n)` here. `.rows(n)` says "n rows tall, whatever width
the container has", which is a fact about the widget and holds wherever you put it.
`.length(n)` sets a single constraint that the container applies along **whichever axis
it runs** — so `widget(divider).length(1)` is one row tall inside a `column` and one
*column wide* inside a `row`. Reach for `.length(n)` only when the number really is the
container's decision.

Or use widgets without the DSL at all:

```scala
val buffer = Buffer(Rect(0, 0, 40, 5))
raw.render(buffer.area, buffer)
```

That escape hatch is useful for embedding, custom render loops, and widget library
tests. See [Architecture](./architecture) and [Testing](./testing).

## Catalog by tier

The internal tiers document dependency order, not quality:

1. **Foundation** — block, row/column, spacer, rule, clear, paragraph, list view, table,
   tabs, gauge, line gauge, sparkline, scrollbar, button, checkbox, toggle, select,
   radio group, slider, text input, tree.
2. **Visualization** — canvas, chart, bar chart, column chart, stacked bar chart,
   pie chart, heatmap, dual sparkline, calendar.
3. **Rich content** — spinner and its presets, spinner grid, orbit spinner, linear
   spinner, animated text, marquee, skeleton, indeterminate bar, big text, notice,
   badge, tooltip, dialog, markdown, syntax highlighter.
4. **Application-scale state** — data table, directory tree, text area, log, menu,
   paginator, scroll view, image, and links.

Every built-in has render-to-`Buffer` tests. Interactive DSL wrappers additionally
have focus and event-routing tests.
