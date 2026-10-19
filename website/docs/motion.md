---
title: Motion
description: Add composable post-render effects, eased value animation, springs, and skippable splash screens to glyphora apps.
---

# Motion

glyphora separates content from presentation in time. Widgets first render a normal,
deterministic frame; active `Effect`s then transform its cells using elapsed time.
That keeps motion out of widget state and makes effects composable and replayable.

Use motion to explain change, preserve context, or add a brief sense of arrival—not
to delay every interaction.

## Animation ticks

Motion needs the runtime to produce frames while time passes, and an animated widget
asks for those frames itself. This app animates with no configuration at all:

```scala
def view(using ReactiveScope, Theme): Element = spinner("deploying")
```

Reading the ambient `AnimationClock` — which every animated widget does — records how
often that widget needs a frame. After each render the app turns one repeating tick on
at the shortest interval anything on the frame asked for, and off again when a frame
asks for nothing. So an app showing a spinner ticks, an app showing a static screen does
not, and neither one had to say so.

Ticks delivered this way arrive at the input loop's own cadence, which is about ten a
second when no tick rate is configured. That is the difference between "the spinner
spins" and "the spinner is perfectly smooth". For motion you want to look deliberate,
set the rate explicitly:

```scala
import scala.concurrent.duration.*

override def config = RunnerConfig(tickRate = Some(50.millis))
```

Twenty frames per second is usually enough for terminal animation. Faster ticks
increase CPU and terminal traffic without guaranteeing a smoother emulator. A
configured `tickRate` also drives `onTick`, which the negotiated ticks deliberately do
not — an app that never asked for ticks does not start receiving that callback.

Either way there is no counter to declare, advance, or thread through a view — see [the
animation clock](#the-animation-clock) below.

## Three kinds of motion

They are not interchangeable, and reaching for the wrong one is the usual source of
motion that feels wrong:

| Kind | Use | Driven by |
|---|---|---|
| **Animated widgets** — `spinner`, `progressBar`, `orbitSpinner`, `animatedText`, … | ongoing status: something is working, something is loading | the ambient `AnimationClock` |
| **Frame effects** — `Effect.fadeIn`, `sweepIn`, … | a transition: arriving, leaving, drawing attention once | `runEffect`, elapsed since it started |
| **Tween / Spring** | a *value* your view consumes — a ratio, an offset, a scroll position | elapsed time you supply |

An `Effect` transforms the cells of a finished frame. An animated widget draws
different cells. A `Tween` changes a number your widgets are built from. Use an effect
for a transition, a widget for a state, and a tween for a value.

## The animation clock

Every animated widget reads elapsed time from the ambient `AnimationClock`, so this
is a complete animated app:

```scala
override def config = RunnerConfig(tickRate = Some(50.millis))

def view(using ReactiveScope, Theme): Element = spinner("deploying")
```

The read is **tracked**, which is what makes this more than a shorthand: a view only
subscribes to the clock when it actually renders an animation, so a screen with no
animation on it is not repainted by the ticks. Hand-rolling a `Signal[Int]` counter
and reading it in `view` repaints the whole app on every tick forever, whether
anything is moving or not.

Speeds are wall-clock, not tick counts. A preset that holds each frame for 80 ms
looks the same in an app ticking every 50 ms and one ticking every 200 ms; expressing
speed in ticks would make the same animation read as sluggish in one and frantic in
the other.

Every animated widget is a pure function of elapsed time, so each has a
`…At(elapsed)` twin — `spinnerAt`, `orbitSpinnerAt`, `animatedTextAt`,
`indeterminateBarAt`, `skeletonAt`, `marqueeAt`, `linearSpinnerAt`, `spinnerGridAt` —
for driving from a clock of your own. See [Widgets](./widgets#feedback-and-motion) for
the catalogue.

## Run a frame effect

From a key, mouse, or other render-thread handler:

```scala
runEffect(Effect.sweepIn(350.millis, Easing.CubicOut))
```

The effect applies to the full frame after normal rendering. When a finite effect
finishes, the runtime removes it and paints one final unmodified frame.

### Keyed effects

A binding that starts an effect on every press stacks one copy per press. Start it
under a key instead, and a second start replaces the first, timed from the new press:

```scala
binding("ctrl+s", "save") {
  save()
  runEffect("flash", Effect.pingPong(Effect.paint(Color.Yellow, 120.millis)))
}
```

`runEffect(key, fx)` is on `TuiApp` and on `AppServices`. Unkeyed effects and effects
under other keys keep running.

### Scope an effect to one element

A frame effect covers the whole frame. To flash one panel, dissolve one toast or sweep in
one dialog, confine it to an element instead. Give the element a `.key(...)` and name it
as the effect's `scope`:

```scala
binding("ctrl+s", "save") {
  save()
  runEffect("flash", Effect.paint(Color.Yellow, 200.millis), scope = "editor")
}

def view(using ReactiveScope, Theme): Element =
  column(toolbar, textArea(state).key("editor").fill)
```

The effect covers only the rect the `editor` node was painted into, looked up on every
frame from the recorded node bounds — it follows the node when the layout moves, and a
frame on which the node is not on screen paints nothing of it. `runEffect(key, fx, scope)`
is on `TuiApp` and `AppServices`; starting one switches bounds recording on.

`element.withEffect(fx)` does the same from inside the view, without a key. It is
timed from the first frame that reaches the call — a hook, so it follows the
[hook ordering rule](./component-state) — and paints the effect over exactly the
element's own cells, straight after the element paints:

```scala
toastCard(message).withEffect(Effect.fadeIn(250.millis))
```

`withEffect(fx, elapsed)` takes the elapsed time explicitly, which is what makes a frame
of it reproducible in a test: `text("saved").withEffect(flash, 500.millis)` is that
text halfway through a one-second flash, with every sibling exactly as painted.

## Pick an effect

| Effect | Visual behavior | Good for |
|---|---|---|
| `fadeIn` / `fadeOut` | interpolates foreground colors from/to black | gentle screen transitions |
| `coalesce` / `dissolve` | reveals/hides cells in deterministic random order | splash and success moments |
| `sweepIn` | reveals columns left to right | page or panel arrival |
| `slideIn(direction, …)` | slides content into place travelling `MotionDirection.Up`, `Down`, `Left` or `Right` | directional navigation |
| `slideInFromRight` | `slideIn(MotionDirection.Left, …)` under its older name | directional navigation |
| `typewriter` | reveals cells in row-major reading order | short messages and intros |
| `pulse` | endless brightness oscillation | a single live/attention state |
| `hslShift(hue, saturation, lightness, …)` | turns text colours around the HSL wheel | mood changes, warnings warming up |
| `paint(color, …)` | mixes text colours towards one colour | highlighting a change |
| `glitch(intensity, …)` | scrambles a share of the text into block noise, seeded, clean at the end | errors, retro flourishes |
| `evolve(symbols, …)` | runs text through a symbol ramp such as `Effect.ShadeSymbols`, then settles on the real glyph | materialising content |

Most finite effects take a duration and optional `Easing`. Scatter effects also take
a seed; equal seeds produce equal cell order, which makes testing repeatable.

```scala
Effect.coalesce(
  duration = 600.millis,
  easing = Easing.QuadOut,
  seed = 42,
)
```

## Compose timelines

```scala
val reveal = Effect.sequence(
  Effect.coalesce(450.millis),
  Effect.delay(150.millis, Effect.fadeIn(250.millis)),
)

val energetic = Effect.parallel(
  Effect.sweepIn(350.millis, Easing.CubicOut),
  Effect.fadeIn(240.millis),
)

val threePulses = Effect.repeat(Effect.fadeIn(180.millis), times = 3)
```

- `sequence` runs effects one after another;
- `parallel` runs all effects and completes with the longest;
- `delay` holds an effect at zero progress before it begins;
- `repeat` loops a finite effect a fixed number of times;
- `reversed` plays a finite effect backwards;
- `pingPong` plays it forwards then backwards, ending on the frame it began with.

## Limit an effect to some cells

`Effect.filtered(filter)(fx)`, or `fx.filtered(filter)`, puts back every cell the
`CellFilter` rejects once `fx` has run. Filters see each cell as it was rendered,
before the effect touched it:

```scala
val textOnly  = Effect.fadeIn(300.millis).filtered(CellFilter.text)
val redAlerts = Effect.glitch(0.4, 600.millis).filtered(CellFilter.fg(Color.Red))
val rim       = Effect.paint(Color.Cyan, 200.millis).filtered(CellFilter.outer(1))
val custom    = Effect.dissolve(400.millis).filtered(
  CellFilter.inner(2).and(CellFilter.where(_.symbol != "│")).and(CellFilter.bg(Color.Blue).not),
)
```

`text` accepts non-blank cells; `fg(color)` and `bg(color)` match the rendered
colour exactly; `inner(margin)` and `outer(margin)` split the effect's area at a
margin; `where(pred)` takes any `Cell => Boolean`; `and`, `or` and `not` combine
them, and `CellFilter.all` accepts everything.

## Give an effect a spatial shape

`Effect.patterned(pattern)(fx)` gives each cell its own progress through `fx`, so a
reveal can bloom from a point or wipe across the screen:

```scala
val bloom   = Effect.patterned(Pattern.radial(Position(40, 12)))(Effect.fadeIn(500.millis))
val wipe    = Effect.patterned(Pattern.sweep(MotionDirection.Right))(Effect.dissolve(400.millis))
val corner  = Effect.patterned(Pattern.diagonal(softness = 0.1))(Effect.coalesce(600.millis))
val tiles   = Effect.patterned(Pattern.checker(4))(Effect.fadeOut(300.millis))
val ripple  = Effect.patterned(Pattern.wave(frequency = 2.0))(Effect.sweepIn(500.millis))
```

| Pattern | Leads | Trails |
|---|---|---|
| `Pattern.radial(center)` | the centre (rows count double, so rings look round) | the farthest corner |
| `Pattern.diagonal()` | the top-left corner | the bottom-right corner |
| `Pattern.sweep(direction)` | the edge opposite `direction` | the edge it travels towards |
| `Pattern.checker(size)` | one colour of `size`-cell squares, in the first half | the other colour, in the second half |
| `Pattern.wave(freq)` | the left column, with `freq` ripples down the rows | the right column |

`softness` (default `Pattern.DefaultSoftness`, 0.3) is how much of the run each
cell's own transition takes: small is a hard front, close to 1 a broad gradient.
Every pattern starts every cell at 0 and finishes every cell at 1. Per-cell progress
is resolved to `Effect.PatternLevels` (64) steps, so the cost per frame is bounded by
that, not by the size of the screen. An endless effect such as `pulse` has no run to
spread out and plays unpatterned.

An infinite effect such as `pulse` never prunes itself. Use it sparingly, and avoid
placing one inside a sequence that needs to continue.

## Choose an easing curve

`Easing.Linear` moves at constant speed. The `Quad`, `Cubic`, `Quart`, `Quint`,
`Sine`, `Expo`, and `Circ` families provide `In`, `Out`, and `InOut` variants.
`Back`, `Elastic`, and `Bounce` intentionally overshoot or oscillate.

A practical default set:

- `QuadOut` — quick start, gentle finish; most entrances;
- `QuadIn` — accelerates away; exits;
- `CubicInOut` — symmetric state transition;
- `Linear` — progress tied directly to time;
- `BackOut` — playful overshoot for a small, isolated value.

## Animate a value with Tween

`Tween` is for numbers that your view consumes—gauge ratios, offsets, or a chart
range—rather than whole-frame cell transforms:

```scala
import scala.concurrent.duration.*

private val progress = Tween(0.0, 1.0, 800.millis, Easing.CubicOut)

def view(using ReactiveScope, Theme): Element =
  gauge(progress.at(AnimationClock.elapsed))
```

The clock is shared with the animated widgets, so a tween and a spinner in the same
view stay in step, and reading it here subscribes this view to the ticks for exactly
as long as it renders the tween. Supply your own elapsed value instead when the
animation is tied to something other than wall time — progress through a download,
say, rather than seconds.

`Tween.at` clamps through the easing function, so values remain at their completed
endpoint after the duration.

## Use a spring for physical motion

A `Spring` has no fixed duration. Step it each tick until it settles:

```scala
private val spring = Spring(frequency = 6.0, damping = 0.75, deltaTime = 0.05)
private var position = 0.0
private var velocity = 0.0
private val target = Signal(100.0)

override def onTick(): Unit =
  val next = spring.step(Spring.State(position, velocity), target.peek)
  position = next.position
  velocity = next.velocity
```

Lower damping bounces; `1.0` is approximately critically damped; higher values feel
slower. Springs suit scroll or selection motion where a destination can change
mid-flight.

## Build a skippable splash

```scala
override def splash = Some(
  SplashScreen(
    content = centered(40, 7) {
      column(
        bigText("GLYPHORA").fg(Color.Cyan),
        text("terminal UI, written like Scala").dim,
      )
    },
    effect = Effect.coalesce(650.millis),
    minimumDuration = 1.second,
  )
)
```

The splash runs before the first normal view. Any key skips it, and glyphora supplies
a default tick when the app config has none.

## Motion checklist

- Keep most transitions under 500 ms.
- Never animate away an error or required action.
- Prefer one dominant effect over several competing ones.
- Offer a reduced-motion setting for persistent animation. The quietest built-ins are
  `IndeterminateMotion.Pulse` (brightens in place, no travel), `spinnerGrid().uniform`
  (every slot in lockstep), and `SpinnerPreset.Ellipsis`.
- Test finite effects at zero, halfway, completion, and after completion.
- Use stable scatter seeds in tests.

Effects process the same buffers used by headless tests, and `AnimationClock.freezeAt`
pins the clock so an animated widget renders a known frame; see
[Testing](./testing#assert-an-animated-frame) for both.
