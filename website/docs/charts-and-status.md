---
title: Charts, gauges & status
description: Put live series, histograms, gauges, percentiles, and threshold bands on a glyphora screen at a scale and colour the reader can act on.
---

# Make a number readable at a glance

A number on screen is not information until it carries a scale and a judgement. `812`
means nothing; `812 ppm — Moderate ▲` means something to everyone. This page is about
the second form: choosing the visualisation, fixing the scale so two frames are
comparable, and attaching a band a reader can act on.

The code here is taken from `examples/airsensor` and `examples/loadtest`. Both run
with no network — read them beside this page.

## Pick the visualisation

| You have | Start with | Add when needed |
|---|---|---|
| one bounded value | `progressBar(ratio)` | `.ramp(...)` to colour it by value |
| one value, whole row | `gauge(ratio)` | a centred `ProgressLabel` inside the bar |
| a series over time | `sparkline(samples)` | a pinned `max` so frames compare |
| a distribution | a column of `progressBar(...).bare` rows | bucket labels beside each row |
| x/y data | `chart(datasets, xBounds, yBounds)` | braille resolution for density |
| a categorical comparison | `barChart(data)` | `stackedBarChart` for parts of a whole |

Reach for a sparkline before a chart. A chart needs axes, bounds and roughly twenty
rows to earn its space; a sparkline says "rising" in one row and is legible at eight
columns.

## Fix a sparkline's ceiling

By default a live series rescales to its own maximum on every frame. A flat trace and
a spiky one then look identical, and a value that halves can make the line go *up*.
`.max(n)` pins the ceiling:

```scala
sparkline(samples).max(math.round(metric.gaugeMax * 10.0))
  .styled(_.patch(metric.bandOf(latest).style))
  .fill
```

Pin `max` to the metric's own scale, not to the data's. The trap is that autoscaling
looks fine while you are watching one series — it only misleads once the reader starts
comparing two frames, or two rows.

`barChart` and `stackedBarChart` take the same `max`, and for the same reason: a stacked
chart that autoscales to its tallest stack makes an unchanged column appear to shrink the
moment a taller one arrives beside it. Pin both charts to one ceiling and their heights
mean the same thing:

```scala
stackedBarChart(regions, max = Some(capacity))
```

## Pin the newest sample to the right edge

A sparkline draws one column per data point. When the series is longer than the pane
is wide, the default keeps the *oldest* points and clips the newest off the right —
which is the wrong end for a live metric: the reading you care about is the one that
disappears. `.rightToLeft` anchors the series to the other edge, so the latest sample
always sits in the last column and history scrolls off the left:

```scala
sparkline(window).rightToLeft.max(peak)
```

Before this existed, a live pane had to trim the window by hand on every frame to keep
the newest readings visible. It no longer does — pass the whole history and let the
widget choose the window. The ceiling is still taken from the whole series, not from
the visible columns, so the trace does not rescale itself the moment a peak scrolls
off the left. `dualSparkline(upper, lower, SparkDirection.RightToLeft)` anchors both
halves the same way.

## Carry doubles into a Long series

`Sparkline` takes `Seq[Long]`, so a metric with decimals loses them on the way in.
Scale before rounding, and scale the ceiling by the same factor:

```scala
val samples = readings.map(entry => math.round(metric.read(entry) * 10.0))
```

A factor of ten keeps one decimal, which is all a one-row trace can show anyway. The
trap is scaling the samples and forgetting the ceiling: the line then sits at a tenth
of its true height and never moves.

## Draw a horizontal histogram

`barChart` drops any bar that does not fully fit, so a narrow terminal silently loses
buckets off the right-hand edge. For a latency distribution the horizontal form is
better regardless, because a bucket *range* needs about twelve characters of label and
an upright bar has nowhere to put them — under a three-column bar, "100-250ms" is drawn
as "100".

`horizontalBarChart(data)` is that layout: the bars grow rightwards, one row each by
default (`horizontalBarChart(data, barHeight = 2)` makes them thicker), and the labels
are right-aligned in a gutter down the left edge. The gutter is as wide as the longest
name and never more than half the area, so the bars always keep half the width:

```scala
horizontalBarChart(buckets.map(bucket => bucket.label -> bucket.count))
```

Bars show a comparison; they do not show a magnitude. `showValues = true` — on
`barChart` and `horizontalBarChart` alike — writes each bar's number beside its bar:
above an upright bar, in the empty part of the track past the end of a sideways one. It
goes *beside* the bar rather than inside it because a terminal cell carries one style,
so a number drawn over a filled bar takes the bar's colours and can vanish into them. A
number with nowhere to go — a bar already at the top of the area, or a number wider than
its own bar — is left out rather than truncated, since half a number reads as a
different number. The widget-level `BarChart` takes a `valueFormat: Long => String` in
its `BarChartOptions`, which is where a unit or a thousands separator belongs:

```scala
horizontalBarChart(rows, showValues = true)

BarChart(rows, BarChartOptions(showValues = true, valueFormat = count => s"$count req"))
```

Build the rows by hand instead when you want a number printed beside each bar, which
the widget does not draw:

```scala
private def histogramRows(buckets: Vector[LatencyBucket]): Seq[Element] =
  val peak = buckets.map(_.count).maxOption.getOrElse(0L)
  buckets.map: bucket =>
    row(
      text(bucket.label).dim.length(14),
      progressBar(if peak <= 0 then 0.0 else bucket.count.toDouble / peak).bare.fill,
      text(bucket.count.toString).length(6),
    ).length(1)
```

Scale every bar against the *peak bucket*, not against the total: dividing by the total
makes every bar tiny as soon as the distribution has a long tail.

## Size a bar chart to the width you were handed

A bar chart cannot reflow, so decide the bucket count from the area rather than fixing
it. Ask for buckets that fit and let the data fill them:

```scala
val buckets = math.max(4, math.min(12, available / 18))
LatencyBucket.of(latencies, buckets)
```

The trap is a fixed bucket count that looks right on your terminal and loses half its
bars on a smaller one — silently, because the widget clips rather than complaining.

## Show axis numbers on a chart

`Element.chart` hides `showLabels`, `marker` and `resolution` behind its own parameter
list. When you need any of them, drop to the widget and wrap it:

```scala
import io.worxbend.tui.widgets as w

widget(
  w.Chart(
    datasets = Seq(w.Dataset("latency", points, graphType = w.GraphType.Line)),
    xBounds = w.Bounds(0.0, 60.0),
    yBounds = w.Bounds(0.0, ceiling),
    options = w.ChartOptions(showLabels = true),
  )
)
```

At this tier the two world ranges are `Bounds` values rather than `(min, max)` tuples,
and the knobs the element factory spells out as its own parameters live on one
`ChartOptions` — `showLabels`, `marker`, `resolution` among them.

`widget(...)` is not an escape hatch you should feel bad about — it is the documented
way down a tier, and the element layer is a convenience over exactly this.

`marker` is the glyph a single dot is drawn with at `CanvasResolution.Cell`. The library
names the ones it uses itself, so you can say which default you are replacing rather than
retyping a literal: `Marker.Dot` (`•`, the chart and canvas default), `Marker.Circle`
(`●`, heavier, the spinner default), `Marker.Block` (`█`, for a solid trail) and
`Marker.Ascii` (`*`, for a terminal whose font has none of the others). Any glyph exactly
one column wide works; anything wider is refused and drawn as `Marker.Dot` instead, so a
two-column emoji cannot smear into the cell next door.

## Colour a bar by value

`progressBar(...).ramp(...)` and `gauge(...).ramp(...)` both plumb a `ColorRamp`
through to the fill, so either meter can move its colour with its value:

```scala
progressBar(metric.ratio(reading)).bare.ramp(ColorRamp.Traffic).length(1)
```

`ColorRamp.Traffic` runs green through amber to red — "filling up towards trouble",
which is right for CO₂, disk and memory and wrong for a download. `ColorRamp.Recovery`
is its reverse, for completion and health.

## Compute percentiles yourself

The toolkit ships no statistics helpers, deliberately — a percentile is four lines and
every project wants a slightly different one. Nearest-rank over a sorted vector:

```scala
private def percentile(sorted: Vector[Long], quantile: Double): Long =
  if sorted.isEmpty then 0L
  else
    val rank = math.ceil(quantile * sorted.size).toInt
    sorted(math.max(0, math.min(sorted.size - 1, rank - 1)))
```

Sort once and take every percentile from the same vector. The trap is sorting inside
the view: that runs on the render thread on every frame, and a load test with a hundred
thousand samples will drop frames doing it.

## Classify a reading into a band

A band is a small closed enum, not a colour. It has to be said twice — in colour for
the glance, in a word for everyone who cannot rely on colour — and only an enum can
carry both:

```scala
enum Band:
  case Good, Moderate, Elevated, Unhealthy, VeryUnhealthy

  def label: String = this match
    case Good          => "Good"
    case Moderate      => "Moderate"
    // …

  /** The severity the toolkit already understands, so badges and toasts need no
    * parallel vocabulary. */
  def level: NoticeLevel = this match
    case Good                      => NoticeLevel.Success
    case Moderate | Elevated       => NoticeLevel.Warning
    case Unhealthy | VeryUnhealthy => NoticeLevel.Error

  def style(using theme: Theme): Style = this match
    case Good                => theme.success
    case Moderate | Elevated => theme.warning
    case Unhealthy           => theme.error
    case VeryUnhealthy       => theme.error.bold
```

Most pollutant thresholds are the same shape — four upper-inclusive cut-offs, anything
above the last is the worst band — so write the cascade once and let each metric differ
only in its numbers:

```scala
def cascade(good: Double, moderate: Double, elevated: Double, unhealthy: Double)(value: Double): Band =
  if value <= good then Good
  else if value <= moderate then Moderate
  else if value <= elevated then Elevated
  else if value <= unhealthy then Unhealthy
  else VeryUnhealthy
```

Not every metric cascades. Temperature and humidity are *band-shaped*: comfortable in
the middle, worse in both directions. Classify those with an explicit match rather than
bending the cascade around them.

Take the colours from the theme, not from hard-coded ANSI values, so a re-theme moves
the whole app at once. See
[Never use color alone](./unicode-and-accessibility#never-use-color-alone) for why the
word is not optional.

## Show a trend arrow

An arrow needs a dead band or it flickers. A metric wobbling by a fraction of a unit is
steady, and without one the arrow flips on nearly every refresh:

```scala
def between(samples: Seq[Double], deadband: Double): Trend =
  if samples.sizeIs < 2 then Steady
  else
    val latest  = samples.last
    val earlier = samples(math.max(0, samples.size - 1 - Window))
    if latest - earlier > deadband then Rising
    else if earlier - latest > deadband then Falling
    else Steady
```

Compare against a sample *several* back, not the previous one — three samples at a
five-second cadence describes the room rather than the sensor's own jitter. A dead band
of one percent of the metric's scale is a reasonable default.

Give every arrow the same display width, or a column of cards will not line up:

```scala
def arrow: String = this match
  case Rising  => "▲"
  case Falling => "▼"
  case Steady  => "·"
```

"Steady" and "unknown" must not share a glyph. A reader who cannot tell "not moving"
from "no data yet" will trust the wrong one.

## Keep a metric grid stable when data goes missing

A grid that reflows when one metric drops out is worse than a grid with a gap in it:
the reader's eye loses the position it had learned. Give every cell a fixed constraint
and render a placeholder rather than omitting the cell:

```scala
row(Metric.All.map(card(_, reading))*).length(6)
```

Every card the same `.length`, every row the same height, and a missing reading becomes
`—` inside its card. The trap is `.fill` on a variable number of children: the layout
then changes shape every time a sensor drops a sample.

## Track several tasks at once

`multiProgress(tasks, columns)` draws one row per caller-owned `ProgressTask` — label,
bar, percentage and ETA by default, with elapsed time, rate, bytes, a remaining count and
a spinner as further `ProgressColumn`s. The tasks are watched, so posting
`task.advance(n)` back to the render thread repaints the rows without a signal. The
column catalogue and the clock that makes an ETA testable are in
[Prompts and progress](./prompts#multi-task-progress).

## Where to go next

- [Live data & background work](./live-data) — where the numbers come from, and how to
  keep a rolling history without unbounded growth.
- [Tables & selection](./tables-and-selection) — the percentile *table*'s layout, and
  everything about rows.
- [Build a sensor dashboard](./build-a-sensor-dashboard) — bands, cards and trends
  assembled into a whole app.
- [Build a load generator](./build-a-load-generator) — histograms and percentiles over
  live results.
- [Widget catalog](./widgets) — the full parameter list for every widget named here.
