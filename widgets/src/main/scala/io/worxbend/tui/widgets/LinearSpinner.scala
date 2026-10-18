package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect, Style, Widget}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** Everything about a [[LinearSpinner]] that is not the time: the track, the walk, the shading, and how both are drawn.
  *
  * Bundled rather than listed on [[LinearSpinner]] itself because the list had grown to eleven optional knobs. Every
  * field keeps the default it had as a `LinearSpinner` constructor parameter, so `LinearSpinnerOptions()` is exactly
  * the track the bare constructor drew. The vocabulary mirrors [[OrbitSpinnerOptions]].
  *
  * @param axis
  *   whether the track runs along a row or down a column
  * @param path
  *   the walk — wrapping round at the end or bouncing back
  * @param flow
  *   which way the head travels
  * @param trail
  *   how the slots behind the head are shaded
  * @param trailSlots
  *   the length of the trail in sub-cell slots, not cells, because that is the resolution the motion happens at — at
  *   braille a horizontal track has two slots per cell, so the default four slots is two cells of tail
  * @param rail
  *   the resting brightness of the whole track on the same `0..1` scale as the trail. It is what turns an empty row
  *   into a visible track, and the rule at the meeting point is chosen rather than accidental: a slot is drawn in
  *   `headStyle` only where it is *brighter* than the rail, so a comet's tail dissolves into the track instead of
  *   ending on a hard edge. `0.0` leaves the track empty and the head appears to float
  * @param resolution
  *   the sub-cell resolution the track is drawn at; [[CanvasResolution.Cell]] with an ASCII `marker` is the floor
  * @param period
  *   how long one cycle takes — a traverse when wrapping, a round trip when bouncing
  * @param style
  *   the style of the resting track
  * @param headStyle
  *   the style of the head and the part of the trail brighter than the rail
  * @param marker
  *   read at [[CanvasResolution.Cell]] only — the glyph each hit cell is drawn with there
  */
final case class LinearSpinnerOptions(
    axis: LinearAxis = LinearAxis.Horizontal,
    path: LinearPath = LinearPath.Wrap,
    flow: LinearFlow = LinearFlow.Forward,
    trail: LinearTrail = LinearTrail.Comet,
    trailSlots: Int = 4,
    rail: Double = 0.25,
    resolution: CanvasResolution = CanvasResolution.Braille,
    period: FiniteDuration = 1200.millis,
    style: Style = Style.Default.dim,
    headStyle: Style = Style.Default,
    marker: String = Marker.Circle,
)

/** A head travelling a one-cell-thick track at sub-cell resolution, drawn from how long the animation has been running.
  *
  * The row-or-column-shaped member of the animation family: where [[Spinner]] says "working" inside one glyph and
  * [[OrbitSpinner]] says it in a block, this says it along a line — a status row under a log pane, or a column beside
  * one. Like every animation here the frame is a pure function of `elapsed`, so a test renders any moment directly and
  * an app with no `tickRate` still shows a legible first frame.
  *
  * The three motion knobs in `options` are deliberately orthogonal, which is the difference from the obvious design:
  * [[LinearPath]] is the walk, [[LinearTrail]] is the shading, `trailSlots` is the length. A bouncing head with a comet
  * tail and a wrapping window of solid slots are the same widget with different arguments, rather than two modes in
  * which half the options are silently ignored. See [[LinearSpinnerOptions]] for every knob, the rail included.
  */
final case class LinearSpinner(
    elapsed: FiniteDuration,
    options: LinearSpinnerOptions = LinearSpinnerOptions(),
) extends Widget:

  import options.*

  def render(area: Rect, buffer: Buffer): Unit =
    val track = axis match
      case LinearAxis.Horizontal => area.copy(height = math.min(1, area.height))
      case LinearAxis.Vertical   => area.copy(width = math.min(1, area.width))
    if !track.isEmpty then
      val dots       = LinearDots(track, axis, resolution, marker)
      val brightness = LinearMotion.intensities(elapsed, period, dots.slots, path, flow, trail, trailSlots)
      val resting    = Fraction.clamped(rail)
      var index      = 0
      while index < brightness.length do
        brightness(index) = math.max(brightness(index), resting)
        index += 1
      dots.render(brightness, buffer, intensity => if intensity > resting then headStyle else style)

  /** One row for a horizontal track, one column for a vertical one — exact, not a bound, because the track is clipped
    * to a single cell before anything is drawn.
    */
  def preferredThickness: Int = 1
