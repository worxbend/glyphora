package io.worxbend.tui.dsl

import io.worxbend.tui.core.{CharWidth, Constraint, Style, Versioned, Widget}
import io.worxbend.tui.widgets as w

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** A spinner whose colors were resolved from the ambient [[Theme]] at construction.
  *
  * The theme supplies the glyph and label styles; anything the call site sets with `.fg`/`.bold`/`.styled` layers on
  * top of the glyph style, so `spinner(tick).fg(Color.Red)` recolors the moving part and leaves the label alone.
  */
final case class SpinnerElement(
    elapsed: FiniteDuration,
    label: String,
    preset: w.SpinnerPreset,
    glyphStyle: Style,
    labelStyle: Style,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = SpinnerElement
  override def debugName: String = "spinner"
  def widget: Widget             =
    w.Spinner(elapsed, label, preset.degraded(props.glyphs), glyphStyle.patch(props.style), Some(labelStyle))

  /** Swaps the animation — see [[io.worxbend.tui.widgets.SpinnerPreset]] for the catalogue. */
  def preset(chosen: w.SpinnerPreset): SpinnerElement = copy(preset = chosen)

  /** Sets the caption shown after the glyph. */
  def label(text: String): SpinnerElement = copy(label = text)

  /** Styles the caption independently of the glyph, deriving from whatever the [[Theme]] resolved at construction.
    *
    * `.labelStyle(_.dim)` dims the themed caption; `.labelStyle(_ => someStyle)` is the escape hatch for replacing it
    * outright. Taking a transform rather than a `Style` is what keeps `Theme.Light`'s grey caption grey — a replacing
    * builder silently reverted it to the terminal default.
    */
  def labelStyle(transform: Style => Style): SpinnerElement = copy(labelStyle = transform(labelStyle))

  /** Runs the animation `factor` times slower than the preset's own speed. */
  def slowedBy(factor: Double): SpinnerElement = copy(preset = preset.slowedBy(factor))

  /** Runs the animation at an explicit frame rate. */
  def atFps(fps: Double): SpinnerElement = copy(preset = preset.atFps(fps))

  private[dsl] def withProps(props: ElementProps): SpinnerElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                      = SizeClaim.OneRow

/** A one-row determinate progress bar, themed at construction. The caption and fill-ramp builders come from
  * [[ProgressMeterBuilders]], shared with [[GaugeElement]].
  */
final case class ProgressBarElement(
    ratio: Double,
    label: w.ProgressLabel,
    preset: w.ProgressPreset,
    trackStyle: Style,
    fillStyle: Style,
    ramp: Option[w.ColorRamp],
    props: ElementProps = ElementProps(),
) extends Element,
      ProgressMeterBuilders[ProgressBarElement]:
  type Self = ProgressBarElement
  override def debugName: String = "progressBar"
  def widget: Widget             =
    w.LineGauge(
      ratio,
      label,
      trackStyle.patch(props.style),
      fillStyle.patch(props.style),
      preset.degraded(props.glyphs),
      ramp,
    )

  /** Swaps the glyph vocabulary — see [[io.worxbend.tui.widgets.ProgressPreset]] for the catalogue. */
  def preset(chosen: w.ProgressPreset): ProgressBarElement = copy(preset = chosen)

  protected def progressLabel: w.ProgressLabel                                                      = label
  protected def progressRamp: Option[w.ColorRamp]                                                   = ramp
  protected def withProgress(label: w.ProgressLabel, ramp: Option[w.ColorRamp]): ProgressBarElement =
    copy(label = label, ramp = ramp)

  private[dsl] def withProps(props: ElementProps): ProgressBarElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                          = SizeClaim.OneRow

/** A one-row indeterminate progress bar, themed at construction. */
final case class IndeterminateElement(
    elapsed: FiniteDuration,
    motion: w.IndeterminateMotion,
    preset: w.ProgressPreset,
    trackStyle: Style,
    fillStyle: Style,
    period: FiniteDuration = 1600.millis,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = IndeterminateElement
  override def debugName: String = "indeterminateBar"
  def widget: Widget             =
    w.IndeterminateBar(
      elapsed,
      trackStyle.patch(props.style),
      fillStyle.patch(props.style),
      preset.degraded(props.glyphs),
      motion,
      period = period,
    )

  /** Sets how long one full traverse takes. */
  def period(duration: FiniteDuration): IndeterminateElement = copy(period = duration)

  /** Swaps how the segment travels — bounce, sweep, comet, or pulse. */
  def motion(chosen: w.IndeterminateMotion): IndeterminateElement = copy(motion = chosen)

  /** Swaps the glyph vocabulary. */
  def preset(chosen: w.ProgressPreset): IndeterminateElement = copy(preset = chosen)

  private[dsl] def withProps(props: ElementProps): IndeterminateElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                            = SizeClaim.OneRow

/** Text carrying a time-based effect, themed at construction. */
final case class AnimatedTextElement(
    content: String,
    elapsed: FiniteDuration,
    effect: w.TextEffect,
    baseStyle: Style,
    highlightStyle: Style,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = AnimatedTextElement
  override def debugName: String = "animatedText"
  def widget: Widget = w.AnimatedText(content, elapsed, effect, baseStyle.patch(props.style), highlightStyle)

  /** Swaps the effect — see [[io.worxbend.tui.widgets.TextEffect]] for the catalogue. */
  def effect(chosen: w.TextEffect): AnimatedTextElement = copy(effect = chosen)

  /** Styles the emphasised part — the crest, the cursor, the shimmer head — independently of the resting text.
    *
    * Derives from the style the [[Theme]] resolved at construction, the way [[PanelElement.titleStyle]] and
    * [[SpinnerElement.labelStyle]] do; `.highlightStyle(_ => someStyle)` replaces it outright.
    */
  def highlightStyle(transform: Style => Style): AnimatedTextElement =
    copy(highlightStyle = transform(highlightStyle))

  private[dsl] def withProps(props: ElementProps): AnimatedTextElement = copy(props = props)

/** Scrolling ticker text: one row, travelling leftwards, wrapping round with a run of blanks between repetitions.
  *
  * The speed is a *reading rate* — cells per second — not a step per tick, so the same ticker reads at the same pace
  * whatever the app's `tickRate` happens to be. Around eight cells per second is comfortable. [[period]] says the same
  * thing from the other end, as the time one full lap takes, which is the vocabulary the rest of this file's animated
  * nodes ([[SkeletonElement]], [[LinearSpinnerElement]], [[IndeterminateElement]]) already use.
  */
final case class MarqueeElement(
    content: String,
    elapsed: FiniteDuration,
    cellsPerSecond: Double = 8.0,
    gap: Int = 4,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = MarqueeElement
  override def debugName: String = "marquee"
  def widget: Widget             = w.Marquee(content, elapsed, props.style, gap, cellsPerSecond)

  /** Sets the reading rate in cells per second. */
  def speed(cells: Double): MarqueeElement = copy(cellsPerSecond = cells)

  /** Sets how many blank cells separate the end of the text from the start of its next repetition. */
  def gap(cells: Int): MarqueeElement = copy(gap = cells)

  /** Sets how long one full lap takes, converted into the reading rate the widget actually animates on.
    *
    * A lap is the text plus the gap, so this is the knob for putting two tickers of different lengths in step — say one
    * lap every ten seconds each, whatever they happen to say. A non-positive duration, or empty content with no gap,
    * leaves the rate alone rather than dividing by zero.
    */
  def period(duration: FiniteDuration): MarqueeElement =
    val lap     = CharWidth.clusterCount(content) + gap
    val seconds = duration.toNanos / 1e9
    if lap <= 0 || seconds <= 0.0 then this else copy(cellsPerSecond = lap / seconds)

  private[dsl] def withProps(props: ElementProps): MarqueeElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                      = SizeClaim.OneRow

/** An orbit spinner whose colors were resolved from the ambient [[Theme]] at construction.
  *
  * The [[LoadingTheme]] supplies both: the resting path takes `track`, the arc takes `spinner`. Anything the call site
  * sets with `.fg`/`.bold`/`.styled` layers onto the arc only, so `orbitSpinner().fg(Color.Red)` recolors the moving
  * part and leaves the path themed — the rule [[SpinnerElement]] follows for its glyph and label.
  *
  * Everything about the figure itself — path, trail, sweep, radius, resolution, period — rides in the
  * [[io.worxbend.tui.widgets.OrbitSpinnerOptions]] the node forwards to the widget, and the builders below each rewrite
  * one field of it, so a knob added to the widget arrives here as one `copy` rather than as an eleventh constructor
  * parameter.
  */
final case class OrbitSpinnerElement(
    elapsed: FiniteDuration,
    options: w.OrbitSpinnerOptions,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = OrbitSpinnerElement
  override def debugName: String = "orbitSpinner"

  def widget: Widget =
    w.OrbitSpinner(elapsed, options.copy(arcStyle = options.arcStyle.patch(props.style)))

  /** Swaps the loop the arc travels — a circle or a square. */
  def path(chosen: w.OrbitPath): OrbitSpinnerElement = copy(options = options.copy(path = chosen))

  /** Fixes the figure's radius in dots; unset, it fills whatever area it is given. */
  def radius(dots: Int): OrbitSpinnerElement = copy(options = options.copy(radius = Some(dots)))

  /** Sets how much of the lap is lit, as a fraction — `.sweep(1.0)` with a solid trail lights the whole path and stops
    * the motion, which is the family's static "queued" state.
    */
  def sweep(fraction: Double): OrbitSpinnerElement = copy(options = options.copy(sweep = fraction))

  /** Thickens the arc and the path inwards, in dots — worth it above about radius 8, where one dot reads as faint. */
  def thickness(dots: Int): OrbitSpinnerElement = copy(options = options.copy(thickness = dots))

  /** Swaps how the arc is shaded — see [[io.worxbend.tui.widgets.OrbitTrail]]. */
  def trail(chosen: w.OrbitTrail): OrbitSpinnerElement = copy(options = options.copy(trail = chosen))

  /** A uniform bright window instead of a fading tail — the colourless-terminal choice, and the legible one on a small
    * figure.
    */
  def solid: OrbitSpinnerElement = copy(options = options.copy(trail = w.OrbitTrail.Solid))

  /** Colors the comet's decay along a ramp — the only way to get more than two steps of fade out of one style per cell,
    * and even then only about one step per cell the arc crosses.
    */
  def ramp(chosen: w.ColorRamp): OrbitSpinnerElement =
    copy(options = options.copy(trail = w.OrbitTrail.Comet(Some(chosen))))

  /** Runs the arc the other way round. */
  def reversed: OrbitSpinnerElement =
    copy(options = options.copy(direction = w.SpinDirection.CounterClockwise))

  /** Sets how long one full revolution takes. */
  def period(duration: FiniteDuration): OrbitSpinnerElement = copy(options = options.copy(period = duration))

  /** Draws the figure from one `glyph` per cell instead of sub-cell dots — for a terminal with no braille block, at a
    * quarter of the vertical resolution.
    */
  def markers(glyph: String): OrbitSpinnerElement =
    copy(options = options.copy(resolution = w.CanvasResolution.Cell, marker = glyph))

  /** Half-block dots: twice the vertical resolution of `.markers`, and no braille font needed. */
  def halfBlocks: OrbitSpinnerElement = copy(options = options.copy(resolution = w.CanvasResolution.HalfBlock))

  private[dsl] def withProps(props: ElementProps): OrbitSpinnerElement = copy(props = props)

  /** A fixed radius claims its exact box; a fitted one claims the space it fills. Deriving both axes from the widget's
    * own `preferredSize` is what keeps the measurement from drifting away from what is painted.
    */
  private[dsl] override def claim: SizeClaim =
    w.OrbitSpinner(elapsed, options)
      .preferredSize
      .fold(SizeClaim.Fill)(size => SizeClaim.box(size.width, size.height))

/** A head travelling a one-cell track, themed at construction. */
final case class LinearSpinnerElement(
    elapsed: FiniteDuration,
    railStyle: Style,
    headStyle: Style,
    axis: w.LinearAxis = w.LinearAxis.Horizontal,
    path: w.LinearPath = w.LinearPath.Wrap,
    flow: w.LinearFlow = w.LinearFlow.Forward,
    trail: w.LinearTrail = w.LinearTrail.Comet,
    period: FiniteDuration = 1200.millis,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = LinearSpinnerElement
  override def debugName: String = "linearSpinner"

  def widget: Widget =
    w.LinearSpinner(
      elapsed,
      w.LinearSpinnerOptions(
        axis = axis,
        path = path,
        flow = flow,
        trail = trail,
        period = period,
        style = railStyle,
        headStyle = headStyle.patch(props.style),
      ),
    )

  /** Runs the track down a column instead of along a row. */
  def vertical: LinearSpinnerElement = copy(axis = w.LinearAxis.Vertical)

  /** The head turns back at each end rather than wrapping round. */
  def bouncing: LinearSpinnerElement = copy(path = w.LinearPath.Bounce)

  /** Runs the head the other way. */
  def reversed: LinearSpinnerElement = copy(flow = w.LinearFlow.Backward)

  /** A uniform lit window instead of a fading tail. */
  def solid: LinearSpinnerElement = copy(trail = w.LinearTrail.Solid)

  /** Sets how long one full cycle takes — a traverse when wrapping, a round trip when bouncing. */
  def period(duration: FiniteDuration): LinearSpinnerElement = copy(period = duration)

  private[dsl] def withProps(props: ElementProps): LinearSpinnerElement = copy(props = props)
  private[dsl] override def claim: SizeClaim                            =
    axis match
      case w.LinearAxis.Horizontal => SizeClaim.OneRow
      case w.LinearAxis.Vertical   => SizeClaim(Constraint.Fill(1), Constraint.Length(1))

/** A themed block of phase-offset spinners. */
final case class SpinnerGridElement(
    elapsed: FiniteDuration,
    preset: w.SpinnerPreset,
    phase: w.GridPhase,
    glyphStyle: Style,
    ramp: Option[w.ColorRamp] = None,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = SpinnerGridElement
  override def debugName: String = "spinnerGrid"

  def widget: Widget =
    w.SpinnerGrid(elapsed, preset.degraded(props.glyphs), phase, glyphStyle.patch(props.style), ramp)

  /** Swaps the animation each slot runs — see [[io.worxbend.tui.widgets.SpinnerPreset]] for the catalogue. */
  def preset(chosen: w.SpinnerPreset): SpinnerGridElement = copy(preset = chosen)

  /** Swaps how neighbouring slots are offset — lockstep, diagonal, or radial. */
  def phase(chosen: w.GridPhase): SpinnerGridElement = copy(phase = chosen)

  /** Every slot in lockstep: the reduced-motion member of this family. */
  def uniform: SpinnerGridElement = copy(phase = w.GridPhase.Uniform)

  /** Colors the block by phase — free here, because every slot holds exactly one frame. */
  def ramp(chosen: w.ColorRamp): SpinnerGridElement = copy(ramp = Some(chosen))

  /** Runs the animation `factor` times slower than the preset's own speed. */
  def slowedBy(factor: Double): SpinnerGridElement = copy(preset = preset.slowedBy(factor))

  /** Runs the animation at an explicit frame rate. */
  def atFps(fps: Double): SpinnerGridElement = copy(preset = preset.atFps(fps))

  private[dsl] def withProps(props: ElementProps): SpinnerGridElement = copy(props = props)

/** A skeleton placeholder, themed at construction. */
final case class SkeletonElement(
    elapsed: FiniteDuration,
    baseStyle: Style,
    bandStyle: Style,
    bandWidth: Option[Int] = None,
    period: FiniteDuration = 1200.millis,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = SkeletonElement
  override def debugName: String = "skeleton"
  def widget: Widget             =
    w.Skeleton(elapsed, baseStyle.patch(props.style), bandStyle, bandWidth = bandWidth, period = period)

  /** Pins the sweeping band's width, so skeletons of different sizes pulse in step. */
  def band(cells: Int): SkeletonElement = copy(bandWidth = Some(cells))

  /** Sets how long one full sweep takes. */
  def period(duration: FiniteDuration): SkeletonElement = copy(period = duration)

  private[dsl] def withProps(props: ElementProps): SkeletonElement = copy(props = props)

/** Multi-task progress: one row per caller-owned [[io.worxbend.tui.widgets.ProgressTask w.ProgressTask]], each row the
  * same [[io.worxbend.tui.widgets.ProgressColumn ProgressColumn]]s, themed at construction from the [[LoadingTheme]].
  * The tasks are watched, so a background `task.advance(n)` posted back to the render thread repaints without a signal;
  * while any task is still running the node also asks for animation ticks so a spinner column turns and the elapsed and
  * ETA columns count.
  */
final case class MultiProgressElement(
    tasks: Seq[w.ProgressTask],
    columns: Seq[w.ProgressColumn],
    options: w.MultiProgressOptions,
    props: ElementProps = ElementProps(),
) extends Element:
  type Self = MultiProgressElement
  override def debugName: String                                        = "multiProgress"
  override private[dsl] def watchedStates: Seq[Versioned]               = tasks
  def widget: Widget                                                    =
    w.MultiProgress(tasks, columns, options.copy(style = options.style.patch(props.style)))
  private[dsl] def withProps(props: ElementProps): MultiProgressElement = copy(props = props)
