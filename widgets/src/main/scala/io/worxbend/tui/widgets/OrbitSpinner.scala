package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, Size, Style, Widget}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** The closed loop an [[OrbitSpinner]]'s arc travels.
  *
  * A path rather than two widgets because the animation is identical — a bright window sliding along a loop — and only
  * the loop differs. Both are rasterised by [[RingWalk]] into an ordered dot sequence, so `sweep` is a fraction of the
  * lap on either of them and `thickness` falls out as the same lap at a smaller extent.
  */
enum OrbitPath:

  /** A circle. The default: no corners to catch the eye, so it reads as continuous motion at any speed. */
  case Circle

  /** A square, travelled clockwise from the midpoint of its top edge. Sits flush against box-drawing chrome, and stays
    * legible at a radius of one or two dots where a circle collapses to a blob.
    */
  case Square

  /** The loop at `halfColumns` by `halfRows` dots either side of the centre.
    *
    * Half-extents rather than a radius because the two axes differ whenever `columnAspect` is not 1, and because a
    * future area-filling border path is this same walk with the extents taken from the grid instead of the radius.
    */
  private[widgets] def walk(halfColumns: Int, halfRows: Int): RingWalk =
    RingWalk(this, halfColumns, halfRows)

/** How the travelling arc is shaded along its length. */
enum OrbitTrail:

  /** A uniform bright window. The quietest, and the only one that survives on a terminal with no colour, because it
    * moves brightness rather than hue; also the right choice below about radius 3, where the ring touches too few cells
    * to show a gradient at all.
    */
  case Solid

  /** A bright head decaying into the ring behind it. Directional, so the head — the thing the eye actually tracks — is
    * unambiguous. `ramp` colours the decay; without one it steps `arcStyle` → `arcStyle.dim` → `style`, buying the one
    * extra perceptual step a modifier can offer on a colourless terminal.
    */
  case Comet(ramp: Option[ColorRamp] = None)

/** Which way round an orbit travels. An enum rather than a signed rate or a `reversed: Boolean`, so a call site reads
  * as a direction and a reversed orbit is greppable rather than a minus sign away from a typo.
  */
enum SpinDirection:
  case Clockwise, CounterClockwise

/** Which dots of a lap are lit, and how brightly, at one moment.
  *
  * Split out of [[OrbitSpinner]] so the time→arc mapping can be tested without a [[Buffer]], and stated in *indices*
  * rather than fractions: quantising the head to the ring's own dot grid once, up front, is what keeps the arc's ends
  * from wobbling by a dot when a fraction lands on a boundary.
  */
private[widgets] object OrbitArc:

  /** Which dot of `samples` the head is on after `elapsed`.
    *
    * [[Animation.step]] rather than a bespoke helper because a revolution *is* a cycle, so `period` is the honest unit;
    * it also brings the family's shared handling of a negative elapsed time and a zero period, which parks the figure
    * with its head at twelve rather than dividing by zero.
    */
  def headIndex(elapsed: FiniteDuration, period: FiniteDuration, samples: Int): Int =
    Animation.step(elapsed, period, samples)

  /** How many dots are lit for a `sweep` fraction of the lap.
    *
    * At least one, so the arc never vanishes, and at most the whole lap: `sweep = 1.0` lights everything, which stops
    * the motion and is the family's static "queued" state. `NaN` reads as no sweep rather than as a full lap, because
    * the sweep goes through [[Fraction.clamped]] like every other fraction in this module.
    */
  def litCount(sweep: Double, samples: Int): Int =
    if samples <= 0 then 0
    else
      val clamped = Fraction.clamped(sweep)
      math.max(1, math.min(samples, math.round(clamped * samples).toInt))

  /** How many dots *behind* the head `index` sits, travelling `direction`.
    *
    * Counter-clockwise negates the index rather than reversing the walk, which is the same thing one allocation cheaper
    * and keeps both directions starting at twelve o'clock: at `head == 0` either sense lights dot zero. It follows that
    * a counter-clockwise frame is the exact vertical mirror of the clockwise frame at the *same* elapsed time.
    */
  def distanceBehind(index: Int, head: Int, samples: Int, direction: SpinDirection): Int =
    if samples <= 0 then 0
    else
      direction match
        case SpinDirection.Clockwise        => math.floorMod(head - index, samples)
        case SpinDirection.CounterClockwise => math.floorMod(head + index, samples)

  /** How brightly the dot at `index` burns, in `[0, 1]`; exactly `0.0` off the arc, so a caller can tell the resting
    * path from the tail's dimmest step without an epsilon.
    */
  def intensityAt(
      index: Int,
      head: Int,
      lit: Int,
      samples: Int,
      direction: SpinDirection,
      trail: OrbitTrail,
  ): Double =
    val behind = distanceBehind(index, head, samples, direction)
    if lit <= 0 || behind >= lit then 0.0
    else
      trail match
        case OrbitTrail.Solid    => 1.0
        case OrbitTrail.Comet(_) => 1.0 - behind.toDouble / lit.toDouble

/** Everything about an [[OrbitSpinner]] that is not the time: the figure, the trail, and how both are drawn.
  *
  * Bundled rather than listed on [[OrbitSpinner]] itself because the list had grown to eleven optional knobs. Every
  * field keeps the default it had as an `OrbitSpinner` constructor parameter, so `OrbitSpinnerOptions()` is the figure
  * 0.14.0 drew by default.
  *
  * @param style
  *   the style of the resting path — the dots no part of the arc is currently on
  * @param arcStyle
  *   the style of the lit arc; under [[OrbitTrail.Comet]] without a ramp it is dimmed toward the tail
  * @param path
  *   the closed loop the arc travels
  * @param trail
  *   how the arc is shaded along its length
  * @param sweep
  *   the fraction of the lap that is lit, in `[0, 1]`; `1.0` lights everything and stops the motion — the family's
  *   static "queued" state
  * @param radius
  *   the figure's radius in DOTS, not cells, because that is the resolution the shape is drawn at. `None` — the default
  *   — fits the figure to its area, staying round
  * @param thickness
  *   how many concentric bands the figure is drawn with, inset a dot at a time
  * @param resolution
  *   the sub-cell resolution the figure is drawn at; [[CanvasResolution.Cell]] with an ASCII `marker` is the floor
  * @param marker
  *   read at [[CanvasResolution.Cell]] only — the glyph each hit cell is drawn with there
  * @param direction
  *   which way round the orbit travels
  * @param period
  *   how long one lap takes
  */
final case class OrbitSpinnerOptions(
    style: Style = Style.Default.dim,
    arcStyle: Style = Style.Default,
    path: OrbitPath = OrbitPath.Circle,
    trail: OrbitTrail = OrbitTrail.Comet(),
    sweep: Double = 0.25,
    radius: Option[Int] = None,
    thickness: Int = 1,
    resolution: CanvasResolution = CanvasResolution.Braille,
    marker: String = Marker.Circle,
    direction: SpinDirection = SpinDirection.Clockwise,
    period: FiniteDuration = 1600.millis,
)

/** A figure drawn at sub-cell resolution with a bright arc chasing round it, from how long the animation has been
  * running.
  *
  * The block-shaped counterpart to [[Spinner]]: where a one-cell spinner has to say "working" inside a single glyph,
  * this has an area to say it in, so it is what a full-screen "connecting…" pane wants and what a status line does not.
  * Like every animation here the frame is a pure function of `elapsed` — no state between renders, no clock read inside
  * `render`, so a test renders any moment directly and an app with no `tickRate` shows a legible first frame.
  *
  * `options.radius` is in DOTS, not cells, because that is the resolution the shape is drawn at — a radius in cells
  * could not express the difference between the two smallest legible rings. Left unset the figure fills its area,
  * staying round: an extreme aspect ratio yields a small centred ring rather than a squashed one, because a squashed
  * circle reads as a wobble rather than as a rotation.
  *
  * `options.sweep` is the fraction of the lap that is lit, not a dot count. A dot count makes the arc's apparent angle
  * change with radius, which keeps `radius` and arc length from being independent knobs.
  *
  * `options.resolution` and `options.marker` are the pair [[Canvas]] already ships, and reusing them is what gives this
  * widget an ASCII floor: `CanvasResolution.Cell` with `marker = "*"` draws the same figure at a quarter of the
  * vertical resolution on a terminal with no braille block. `options.marker` is read at that resolution only.
  *
  * ONE STYLE PER CELL — the constraint this widget is shaped around. A braille cell packs eight dots and carries one
  * [[io.worxbend.tui.core.Style Style]], so dot resolution is 2x4 per cell while *colour* resolution is 1 per cell. Two
  * rules follow, both chosen rather than fallen into. Masks are OR-ed, so a cell holding both arc and path dots keeps
  * every dot and the resting path never erodes. Styles take the brightest dot in the cell, so such a cell reads as arc:
  * the head is never overwritten by its own tail, and the quantisation error runs in one predictable direction — a cell
  * can read brighter than it should, never dimmer, so the arc never appears to break. The budget, measured rather than
  * estimated: the default radius-4 braille ring is 24 dots but only 11 cells, so a ramped comet shows 11 shades, not 24
  * (5 at the default sweep), and `thickness` buys geometry rather than colour. The alternative — one dot per cell, so
  * colour and geometry agree — throws away the resolution braille was chosen for, and is rejected.
  *
  * The consequence worth knowing before reaching for this on a monochrome terminal: because the mask never erodes, the
  * *glyphs* are a pure function of the geometry and the whole animation lives in the per-cell style. A golden frame
  * must therefore assert styles, not glyphs, and a terminal rendering neither colour nor dim shows a static ring.
  */
final case class OrbitSpinner(
    elapsed: FiniteDuration,
    options: OrbitSpinnerOptions = OrbitSpinnerOptions(),
) extends Widget:

  import options.*

  def render(area: Rect, buffer: Buffer): Unit =
    if !area.isEmpty then
      val grid   = DotGrid(area, resolution, marker)
      val dots   = OrbitSpinner.safeRadius(grid, radius)
      val aspect = grid.columnAspect
      val bands  = bandCount(dots, aspect)
      val outer  = path.walk(dots * aspect, dots)
      val head   = OrbitArc.headIndex(elapsed, period, outer.length)
      val lit    = OrbitArc.litCount(sweep, outer.length)
      var band   = 0
      while band < bands do
        renderBand(grid, band, dots, aspect, outer, head, lit)
        band += 1
      grid.flush(buffer, styleFor)

  /** How many concentric bands `thickness` asks for at a figure of `dots` dot rows.
    *
    * One band per dot of thickness, inset a dot at a time so the rings touch rather than leaving a gap, and never past
    * the centre dot: an inset that ran through the centre would fold the figure inside out. Always at least one, so a
    * zero or negative `thickness` still draws the figure.
    */
  private def bandCount(dots: Int, aspect: Int): Int =
    math.max(1, math.min(thickness, math.min(dots * aspect, dots) + 1))

  /** Lights one concentric band of the figure in `grid`.
    *
    * `band` is the inset in dots from the outer loop, so band zero *is* `outer` and reuses it rather than rasterising
    * the same loop twice. `head` and `lit` are the arc's state measured on the outer lap.
    *
    * This owns the rescaling of the arc onto a shorter lap: an inner band has fewer dots, so the head position and the
    * lit count are mapped onto its own sample count rather than shared as raw indices. Sharing indices would make the
    * tail of a thick arc lag its own head by more the deeper into the figure it went.
    *
    * Writes to `grid` and to nothing else — no buffer is touched until [[render]] flushes — so it carries `render`'s
    * thread constraint and adds none of its own.
    */
  private def renderBand(grid: DotGrid, band: Int, dots: Int, aspect: Int, outer: RingWalk, head: Int, lit: Int): Unit =
    val lap      = outer.length
    val walk     = if band == 0 then outer else path.walk(dots * aspect - band, dots - band)
    val samples  = walk.length
    val bandHead = if band == 0 then head else (head.toLong * samples / lap).toInt
    val bandLit  =
      if band == 0 then lit else math.max(1, math.min(samples, (lit.toLong * samples / lap).toInt))
    var index    = 0
    while index < samples do
      val dot = walk.dotAt(index)
      grid.light(
        grid.centreColumn + RingWalk.columnOf(dot),
        grid.centreRow + RingWalk.rowOf(dot),
        OrbitArc.intensityAt(index, bandHead, bandLit, samples, direction, trail),
      )
      index += 1

  /** The cells an explicit `radius` occupies — exact, not a bound. `None` when the figure is fitted to its area, which
    * is what stops the DSL element claiming a size it will not honour.
    *
    * Deliberately *not* the [[io.worxbend.tui.core.Measured]] mixin every other self-sizing widget uses, and the one
    * exception to it: `Measured` splits the question into two independently-answerable halves (`widthAt`, `heightAt`),
    * and here the two axes are one answer. A round figure's width and height are both derived from the same `radius` at
    * the same `resolution`, so a caller that got one without the other would be holding half of a box — and
    * `SizeClaim.box`, the only caller, needs both together or neither.
    */
  def preferredSize: Option[Size] = radius.map(OrbitSpinner.sizeFor(_, resolution))

  /** One [[io.worxbend.tui.core.Style Style]] per cell, from the brightest dot that cell holds.
    *
    * `1.0` is the head, so a ramp reads the way a progress ramp does — its far stop is the "most" end.
    */
  private def styleFor(intensity: Double): Style =
    if intensity <= 0.0 then style
    else
      trail match
        case OrbitTrail.Solid             => arcStyle
        case OrbitTrail.Comet(Some(ramp)) => arcStyle.withFg(ramp.at(intensity))
        case OrbitTrail.Comet(None)       => if intensity >= 0.5 then arcStyle else arcStyle.dim

object OrbitSpinner:

  /** The cells a figure of `radius` dot rows occupies at `resolution`.
    *
    * Exact in both directions: rendered into precisely this area the figure paints every edge cell of it, because the
    * dot span is `2 * radius + 1` — always odd — and the centre is pinned to the middle dot, so the padding a ceiling
    * introduces is split between the two sides and never enough to empty an edge cell.
    */
  def sizeFor(radius: Int, resolution: CanvasResolution): Size =
    val (across, down) = SubCell.dotsPerCell(resolution)
    val aspect         = SubCell.columnAspect(resolution)
    val dots           = math.max(0, radius)
    Size(ceilDiv(2 * dots * aspect + 1, across), ceilDiv(2 * dots + 1, down))

  /** The largest radius that fits, leaving both axes inside the grid. Conservative by up to one dot on an even-sized
    * grid, because the centre is pinned to an integer dot so a golden frame does not shift under a resize. Zero on a
    * grid too small for a loop, which draws the single centre dot rather than a ring clipped into nonsense.
    */
  private[widgets] def fittedRadius(grid: DotGrid): Int =
    math.max(0, math.min((grid.dotWidth - 1) / (2 * grid.columnAspect), (grid.dotHeight - 1) / 2))

  /** The radius in dots to draw at: `requested` if the caller gave one, otherwise whatever fits `grid`.
    *
    * Bounded above by half of [[RingWalk.MaxHalfExtent]] rather than by the whole of it, because the loop is built at
    * half-extents of `radius * columnAspect` by `radius`, and `columnAspect` is at most 2 (one cell is two dots wide at
    * [[CanvasResolution.Cell]]). Half the maximum is therefore the largest radius whose wider axis still lands inside
    * the walk's own clamp, so no figure is silently squashed by being clamped on one axis only. Never negative, so a
    * nonsensical radius draws the single centre dot instead of nothing.
    */
  private[widgets] def safeRadius(grid: DotGrid, requested: Option[Int]): Int =
    math.max(0, math.min(RingWalk.MaxHalfExtent / 2, requested.getOrElse(fittedRadius(grid))))

  private def ceilDiv(value: Int, divisor: Int): Int = (value + divisor - 1) / divisor
