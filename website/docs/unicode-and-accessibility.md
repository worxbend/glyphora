---
title: Unicode & accessibility
description: Build terminal interfaces that measure Unicode correctly and communicate focus, status, and errors without relying on color.
---

# Unicode & accessibility

A terminal cell is not a Java `Char`, a Unicode code point, or always one visible
character. glyphora treats display width and grapheme clusters as infrastructure so
layout, wrapping, editing, and cursor movement agree.

Accessibility also starts in the model: predictable focus, explicit labels, and
status text that does not depend on color alone.

## Measure what the terminal displays

```scala
import io.worxbend.tui.core.CharWidth

CharWidth.of("hello")       // 5
CharWidth.of("界")          // usually 2 terminal cells
CharWidth.of("e\u0301")    // 1: e + combining acute accent
CharWidth.of("👨‍👩‍👧‍👦")   // one grapheme cluster, terminal-dependent width
CharWidth.withoutControls("a\tb") // "ab": C0, DEL and C1 clusters removed
```

Use `CharWidth` anywhere custom code clips, aligns, wraps, pads, or positions a
cursor. `String.length` counts UTF-16 code units and will eventually misalign a UI.

`CharWidth.withoutControls(text)` strips C0, DEL and C1 control clusters while
keeping combining marks, and is what a custom editable-text model must call before
storing caller-supplied text: a control is zero columns wide but still fills a whole
cell, so storing one desynchronises the backend's cursor model from the terminal's.
`TextInputState` and `TextAreaState` already apply it to both their constructor seed
and every `insert` — a tab pasted into a field is dropped, not expanded.

Built-in widgets already route their width calculations through generated Unicode
Character Database tables. `TextInput` and `TextArea` edit by grapheme cluster, so
Backspace does not split combining sequences or emoji families.

Styled fragments do not introduce character boundaries. A `Line` containing separate
spans for `"e"` and `"\u0301"` still draws one accented grapheme; splitting a ZWJ emoji
across spans does not change its width. Measurement, clipping and paragraph wrapping
share the same line-wide traversal. When a grapheme crosses a span boundary, it keeps
the style of its base character rather than trying to style part of one terminal cell.

## Characters Unicode gives no single width

A few hundred characters — the box-drawing set, Greek and Cyrillic letters, the arrow
block, `±`, `×` — carry the East Asian Width property *Ambiguous*. A terminal configured
for a Western locale draws them one column wide; one configured for a Chinese, Japanese
or Korean locale draws the same characters two columns wide, because its font has
full-width glyphs for them. Nothing your program sends chooses between the two, and
nothing it receives reports which was chosen: the decision lives in the user's terminal
settings.

glyphora measures and lays out everything as one column, the Western answer, and that
does not change — a widget and the buffer it clips against have to agree on every column,
so there is exactly one number the renderer uses. What you can now ask for is the *other*
answer, so your application can notice when the two will disagree:

```scala
import io.worxbend.tui.core.{CharWidth, WidthMode}

CharWidth.of("─α")                          // 2 — what glyphora lays out with
CharWidth.of("─α", WidthMode.Wide)          // 4 — what a CJK-locale terminal will show
CharWidth.isAmbiguousCodePoint('─'.toInt)   // true

span.widthIn(WidthMode.Wide)                // the same question for a Span, Line or Text
line.widthIn(WidthMode.Wide)
text.widthIn(WidthMode.Wide)
```

`widthIn(WidthMode.Narrow)` is exactly `width`, so the two never drift apart. The reason
to care is that glyphora's own borders are made of ambiguous characters: under a CJK
locale a bordered box is drawn wider than it was measured, and the mismatch shows as a
border that runs past its pane. If your users work in those locales, compare the two
numbers for the text you are about to draw and, where they differ, either use the ASCII
border set or leave the extra columns free.

## The terminal's own caret

A widget's caret is a styled cell: visible to someone looking at the screen, and
invisible to everything else. A screen reader announces the insertion point from the
*terminal's* cursor, and an input method editor (the software that turns a run of
keystrokes into a Chinese, Japanese or Korean character) anchors its candidate popup
to the same place. Those two follow the terminal, not the paint.

`Backend.setCursorPosition(position)` moves that terminal cursor, and
`showCursor()` / `hideCursor()` decide whether it is drawn. They are separate calls
on purpose: a background repaint can move the caret without flashing one at a user
who is not typing. A backend that has no real terminal — `HeadlessBackend`, or a
custom one — inherits a no-op default and records the request instead, which is what
a test asserts against.

`Backend.setCursorShape(shape)` picks what that terminal cursor looks like — DECSCUSR,
`CSI n SP q`. The `CursorShape` cases are `Default`, `BlinkingBlock`, `SteadyBlock`,
`BlinkingUnderline`, `SteadyUnderline`, `BlinkingBar` and `SteadyBar`. The affordance it
exists for is a modal editor: a block cursor in command mode and a bar in insert mode is
how a user knows which mode they are in without reading a status line.

The shape is process-global terminal state rather than per-frame state, so set it when the
mode changes rather than on every draw. `JLine3Backend` hands it back to the user's own
configured shape when the app closes or is suspended with `Ctrl+Z`, and the shutdown-hook
restore does the same, so a process killed mid-edit leaves no bar cursor behind in the
shell it came from. A terminal that does not implement DECSCUSR ignores the request, so
the call reports success whether or not anything visibly changed.

## Test the hard strings

Include a small width corpus in custom widget tests:

```scala
val labels = Seq(
  "Kyiv",
  "東京",
  "naïve",
  "e\u0301",
  "🇺🇦",
  "👩🏽‍💻",
)

labels.foreach { label =>
  assert(CharWidth.of(label) >= 1)
}
```

For exact rendering expectations, use `BufferAssertions`; its `lines`,
`trimmedLines`, and `text` helpers skip wide-character continuation cells.

## Make focus predictable

Interactive elements enter a tree-derived tab order. `Tab` moves forward and
`Shift+Tab` moves backward. Mouse clicks focus the hit element before built-in
interaction runs.

When the tree can change shape, give important controls stable identity:

```scala
column(
  input(query, placeholder = "search").key("search"),
  if advanced.get then input(pattern).key("pattern") else spacer(1),
  button("Apply", applyFilter).key("apply"),
)
```

Without keys, focus is positional and can jump when an element appears above the
current control.

## Never use color alone

Pair tone with a symbol and clear language:

```scala
def statusLine(state: State): Element = state match
  case State.Ready  => text("✓ Ready").fg(Color.Green)
  case State.Syncing => text("… Synchronizing").fg(Color.Yellow)
  case State.Failed(message) => text(s"Error: $message").fg(Color.Red)
```

`notice` and `badge` do this for you: `NoticeLevel` pairs each severity with a glyph
(`✔ • ▲ ✖`) and a word (`OK INFO WARN FAIL`), so a message survives a monochrome
terminal. The glyphs are geometric rather than emoji deliberately — emoji are two
columns wide and inconsistently rendered, which makes a stacked column of notices
ragged.

This remains understandable in monochrome terminals and high-contrast modes.
`Theme.HighContrast` is available when the application needs stronger separation.

To check a pairing rather than assume it, `Color.contrastRatio(fg, bg)` returns the WCAG
ratio — 4.5 is the AA threshold for normal text, 3 for large text and interface elements
— and `Color.readableOn(bg)` picks whichever of black and white reads better on a
computed background. See [Check that text will be
readable](./layout-and-style#check-that-text-will-be-readable) for the two caveats that
come with it.

## Offer a way to turn motion down

Persistent animation is the accessibility hazard most terminal toolkits ignore. When
an app animates while it waits, give the user a setting, and route it to the quieter
member of each family rather than to nothing at all:

| Instead of | Reach for | Why |
|---|---|---|
| `indeterminateBar()` | `.motion(IndeterminateMotion.Pulse)` | brightens in place; no travel to track across the row |
| `spinnerGrid()` | `.uniform` | every slot in lockstep, so the block pulses rather than waves |
| `spinner()` | `.preset(SpinnerPreset.Ellipsis)` | four slow frames of `...`, closer to punctuation than motion |
| any animation | a static caption | when there is nothing useful to say about progress, say it once |

Two more properties worth knowing before choosing an animation:

- An `orbitSpinner` animates entirely through per-cell **style**; its glyphs are the
  same at every moment. On a terminal that renders neither colour nor dim it is a
  still ring, so prefer `spinnerGrid` or `spinner` there.
- `SpinnerPreset.AsciiPresets` and `ProgressPreset.Ascii`/`Arrow` need nothing beyond
  ASCII, and `orbitSpinner().markers("*")` draws the same figure with one glyph per
  cell. Those are the floors for an unknown terminal, a CI log, or a screen reader.

## Prefer explicit forms and commands

- Use `Form.accessible(state)` when field position, checkbox state, and errors should
  be spelled out; see [Forms & validation](./forms-and-validation).
- Give every global key binding a short, action-oriented description. The same label
  appears in the status bar, help overlay, and command palette.
- Do not require mouse interaction. Every built-in control has a keyboard path.
- Keep status messages visible long enough to read, and allow important output to be
  revisited outside a short-lived toast.
- Use familiar keys (`Tab`, arrows, `Enter`, `Esc`) before inventing chord-heavy
  navigation.

## Terminal capability reality

Terminals vary in color depth, glyph fallback, hyperlink support, and the width they
assign to newer emoji. glyphora can make its calculations internally consistent,
but it cannot install fonts or change an emulator's width policy.

When a glyph is decorative, include a text fallback. When exact alignment is
mission-critical, prefer stable box-drawing and text symbols over very new emoji.

## Degrading to ASCII

Not every terminal can draw every glyph glyphora knows how to draw. A Linux virtual
console has box-drawing characters but no braille and no emoji; a session whose locale
is `C` cannot carry a non-ASCII byte at all. Rather than have you find out from a
screenful of replacement boxes, the toolkit carries a *glyph ceiling* and every themed
factory stays under it.

The ceiling is `GlyphSupport`, three rungs running floor to ceiling:

| Rung | What it permits |
|---|---|
| `GlyphSupport.Ascii` | Nothing above `U+007E`. Survives a `C` locale, a serial console, or a log file. |
| `GlyphSupport.BoxDrawing` | The `U+2500` box-drawing block on top of that — present in every fixed-width font, including the Linux console's built-in one. |
| `GlyphSupport.Full` | Everything, braille spinners and emoji included. The default. |

It is a ceiling, not a measurement. Nothing can interrogate the font your terminal is
drawing with, so a rung says "do not go above this", never "this is definitely
renderable".

### Let the environment choose it

```scala
object Main extends TuiApp:
  override val theme: Theme = Theme.detected()
```

`Theme.detected(base, env)` keeps every color in `base` — `Theme.Dark` unless you say
otherwise — and lowers only its `glyphs` field to whatever `TerminalGlyphs.detect()`
justifies. Call it once at start-up and hold the result: it reads the process
environment, which does not change under a running application.

`TerminalGlyphs.detect()` decides in this order:

1. **`GLYPHORA_ASCII`** set to anything other than empty or `0` forces
   `GlyphSupport.Ascii`. This is the escape hatch for a terminal that claims more than
   it can draw — a font with no braille coverage, where the spinner comes out as a row
   of boxes. There is deliberately no variable that forces the ceiling *up*: `Full` is
   already what any UTF-8 environment resolves to.
2. **The locale's encoding**, read from `LC_ALL`, then `LC_CTYPE`, then `LANG` — the
   ordering POSIX defines. Anything that is not UTF-8, and an environment with none of
   the three set, resolves to `GlyphSupport.Ascii`: the bytes could not survive the
   trip anyway.
3. **`TERM`** naming a built-in console font — `linux`, the `vt` family, `dumb`, or an
   empty value — resolves to `GlyphSupport.BoxDrawing`.
4. Everything else resolves to `GlyphSupport.Full`.

### What follows the ceiling

Setting it on the theme is the whole call site. Every element built by a themed factory
carries the ceiling and degrades on the way to the screen:

- **Borders.** Any `BorderType` degrades to `BorderType.Ascii` — `+`, `-`, `|` — below
  the box-drawing rung. `BorderType.Ascii` and `BorderType.Blank` need nothing and are
  never swapped out. Every glyph in every set is one column wide, so a degraded frame
  leaves the interior in exactly the same place.
- **Spinners.** Any `SpinnerPreset` whose frames are not already ASCII degrades to
  `SpinnerPreset.Line`, the `|/-\` spinner, below `Full`.
- **Progress bars.** Any `ProgressPreset` whose glyphs are not already ASCII degrades
  to `ProgressPreset.Ascii`, `#` over `-`, below `Full`.

The ceiling belongs to the terminal, not to the border you picked, so an explicit
choice degrades too:

```scala
panel(text("x")).thick   // ┏━┓ under Full, +-+ under Ascii
```

Whether a preset is kept is decided by looking at its own glyphs, not by a list of safe
names, so a preset added later is covered without anyone remembering to add it.

### Test it without a terminal

`TerminalGlyphs.detect` takes the environment as an argument, so a test names one
rather than mutating the process's:

```scala
assert(TerminalGlyphs.detect(Map("TERM" -> "xterm", "LANG" -> "C")) == GlyphSupport.Ascii)
```

And a rendering test pins the frame the way any other one does — render the tree under
`Theme.Dark.copy(glyphs = GlyphSupport.Ascii)` and assert no character above `~`
survives.

## Color depth: what is detected, and how to test it

You never pick colors twice. Write the color you mean — including 24-bit
`Color.Rgb(...)` — and the backend reduces it to whatever the terminal can show.

### What is detected

`ColorDepth.detect()` reads the environment, in this order:

1. **`NO_COLOR` and `CLICOLOR=0`** — either one turns color off entirely
   ([no-color.org](https://no-color.org) and the
   [CLICOLOR convention](https://bixense.com/clicolors/)); `NO_COLOR` counts when it is
   set to any non-empty value, `CLICOLOR` only when it is exactly `0`. Text attributes
   still work: bold, dim, italic, underline and reverse are emitted, only the color
   codes are dropped.
2. **`CLICOLOR_FORCE`** — set to anything other than empty or `0`, color is forced
   back on, overriding the two above and overriding "output is not a TTY". If the rest
   of the environment would have said "no color at all", forcing gives you the classic
   sixteen.
3. **`TERM=dumb`** — a terminal that by convention understands no escape sequences, so
   this resolves to `NoColor` before `COLORTERM` is even looked at. A `COLORTERM`
   inherited from an outer terminal must not resurrect color here. The match is exact,
   so a terminfo name that merely contains `dumb` is unaffected.
4. **`COLORTERM`** — containing `truecolor` or `24bit` means the full 24-bit palette,
   with two corrections for terminals that advertise color they cannot render:
   - macOS `Terminal.app` (`TERM_PROGRAM=Apple_Terminal`) only gained 24-bit output in
     build 465, so an older `TERM_PROGRAM_VERSION` — or one that cannot be read — is
     capped at the 256 palette.
   - Inside `screen` or `tmux` the multiplexer passes the outer terminal's `COLORTERM`
     through whether or not it can honor it, so such a session is capped at 256 unless
     its own `TERM` advertises direct color (`TERM=tmux-direct`,
     `TERM=screen-truecolor`). Setting `COLORTERM=` empty forces the fallback instead.
5. **`TERM`** — containing `256` means the xterm-256 palette. An unset or empty `TERM`
   with no `COLORTERM` signal means `NoColor`: that is what output redirected into a
   file looks like from inside the process. Anything else falls back to the classic
   sixteen named colors.

The resolved value is one of `ColorDepth.TrueColor`, `Ansi256`, `Ansi16`, `Monochrome`
or `NoColor`. Nothing in the environment ever resolves to `Monochrome`; it is opt-in,
described below.

### How a color degrades

| Target | What happens to `Color.Rgb(r, g, b)` |
|---|---|
| `TrueColor` | emitted as-is |
| `Ansi256` | nearest entry of the xterm-256 palette — the 24-step grayscale ramp for near-gray values, otherwise the 6×6×6 color cube |
| `Ansi16` | nearest of the sixteen named colors (and a `Color.Indexed(n)` with `n >= 16` is approximated to RGB first, then reduced the same way) |
| `Monochrome` | thresholded by Rec.709 relative luminance to `Color.Black` or `Color.White`; a foreground that lands on the same tone as its background is flipped to the opposite one, and `Modifiers.Reverse` remains the way to express a highlight |
| `NoColor` | dropped; the cell keeps its attributes |

Two RGB shades that are close together can therefore land on the *same* cell color at
`Ansi16`. That is the mechanical reason for [Never use color
alone](#never-use-color-alone): a state distinguished only by hue can disappear
entirely, while one distinguished by a glyph, a label or `bold` survives every step
of this table.

### Running your app in sixteen colors

You do not need to find a sixteen-color terminal. Pin the depth on the app:

```scala
class MyApp extends TuiApp:
  override protected def colorDepth: ColorDepth = ColorDepth.Ansi16
```

`TuiApp.colorDepth` is what `createBackend()` hands to the JLine backend, read once
per `run()`. Pinning it to `Ansi16` makes every RGB style in the app go through the
reduction above, so the run you are looking at is the run a user on a plain `xterm`
gets. `ColorDepth.NoColor` does the same for the `NO_COLOR` audience.

`ColorDepth.Monochrome` is the rung between those two. It still emits color codes, but
every color becomes black or white depending on how bright it is, so a selection drawn
only as a background color stays visible instead of vanishing the way it does under
`NoColor`. Pin it for a two-tone terminal, or to check that a black-and-white screen
capture of a colorful app is still readable.

From a shell, the same two checks without touching the code:

```bash
NO_COLOR=1 ./mill examples.showcase.run       # attributes only, no color at all
TERM=xterm COLORTERM= ./mill examples.showcase.run   # sixteen colors
TERM=dumb ./mill examples.showcase.run        # no color codes at all
TERM_PROGRAM=Apple_Terminal TERM_PROGRAM_VERSION=440 COLORTERM=truecolor \
  ./mill examples.showcase.run                # 256 colors, not 24-bit
```

Test at least one sixteen-color environment and one true-color terminal before
release.

Continue with [Mouse & focus](./mouse) for event routing or [Testing](./testing) for
headless interaction checks.
