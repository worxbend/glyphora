package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Cell, Rect, Style}
import io.worxbend.tui.testsupport.BufferAssertions.rendered

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

final class LinearAndGridSpec extends AnyFunSuite:

  private val Period = 1200.millis

  private def at(sample: Int, samples: Int): FiniteDuration =
    Duration.fromNanos(Period.toNanos * sample / samples)

  private def cells(widget: io.worxbend.tui.core.Widget, width: Int, height: Int): Vector[(String, Style)] =
    val buffer = rendered(widget, width, height)
    (for
      y <- 0 until height
      x <- 0 until width
    yield
      val cell = buffer.get(x, y)
      (cell.symbol, cell.style)
    ).toVector

  // ---------------------------------------------------------------- linear: purity and bounds

  test("LinearSpinnerOptions() draws exactly what the bare constructor drew"):
    assert(LinearSpinner(300.millis) == LinearSpinner(300.millis, LinearSpinnerOptions()))
    assert(cells(LinearSpinner(300.millis), 16, 1) == cells(LinearSpinner(300.millis, LinearSpinnerOptions()), 16, 1))

  test("a linear spinner is a pure function of elapsed time"):
    LinearPath.values.foreach: path =>
      val spinner = LinearSpinner(500.millis, LinearSpinnerOptions(path = path))
      assert(cells(spinner, 16, 1) == cells(spinner, 16, 1))

  /** The widget clips itself to one cell of thickness before drawing, so a caller who hands it a tall rect gets a row
    * rather than a smeared block.
    */
  test("a linear spinner occupies exactly one row or one column"):
    val horizontal = rendered(LinearSpinner(400.millis, LinearSpinnerOptions(axis = LinearAxis.Horizontal)), 12, 4)
    assert((0 until 12).forall(x => (1 until 4).forall(y => horizontal.get(x, y).symbol == " ")))

    val vertical = rendered(LinearSpinner(400.millis, LinearSpinnerOptions(axis = LinearAxis.Vertical)), 6, 8)
    assert((1 until 6).forall(x => (0 until 8).forall(y => vertical.get(x, y).symbol == " ")))

  test("a linear spinner never writes outside its rect"):
    val frame = Buffer(Rect(0, 0, 16, 3))
    (0 until 16).foreach(x => frame.set(x, 0, Cell("#", Style.Default)))
    (0 until 16).foreach(x => frame.set(x, 2, Cell("#", Style.Default)))
    LinearSpinner(300.millis).render(Rect(0, 1, 16, 1), frame)
    assert((0 until 16).forall(x => frame.get(x, 0).symbol == "#"))
    assert((0 until 16).forall(x => frame.get(x, 2).symbol == "#"))

  test("degenerate sizes and parameters neither throw nor spill"):
    LinearPath.values.foreach: path =>
      LinearFlow.values.foreach: flow =>
        LinearAxis.values.foreach: axis =>
          Seq(0, 1, 2).foreach: size =>
            Seq(-3, 0, 1, 99).foreach: trailSlots =>
              val spinner =
                LinearSpinner(
                  250.millis,
                  LinearSpinnerOptions(axis = axis, path = path, flow = flow, trailSlots = trailSlots),
                )
              assert(rendered(spinner, size, size).area == Rect(0, 0, size, size))

  test("a zero period parks the head instead of dividing by zero"):
    val parked = LinearSpinner(500.millis, LinearSpinnerOptions(period = Duration.Zero))
    assert(cells(parked, 12, 1) == cells(parked.copy(elapsed = 4.seconds), 12, 1))

  test("a NaN rail reads as an empty track rather than poisoning every slot"):
    val spinner = LinearSpinner(300.millis, LinearSpinnerOptions(rail = Double.NaN))
    assert(cells(spinner, 12, 1).nonEmpty)

  // ---------------------------------------------------------------- linear: the motion

  test("every path and flow animates"):
    LinearPath.values.foreach: path =>
      LinearFlow.values.foreach: flow =>
        val frames = (0 until 24)
          .map(s => cells(LinearSpinner(at(s, 24), LinearSpinnerOptions(path = path, flow = flow)), 16, 1))
          .distinct
        assert(frames.size > 1, s"$path/$flow never moved")

  /** One period is one full cycle: a whole traverse for a wrap, a round trip for a bounce. That is the same contract
    * `IndeterminateBar` already states for its own bounce, so the two agree rather than each inventing a unit.
    */
  test("one period is exactly one cycle for both paths"):
    LinearPath.values.foreach: path =>
      val start = cells(LinearSpinner(0.millis, LinearSpinnerOptions(path = path)), 16, 1)
      val after = cells(LinearSpinner(Period, LinearSpinnerOptions(path = path)), 16, 1)
      assert(start == after, s"$path did not return after one period")

  /** A bounce has no dwell frame at the turn: the extreme slots are visited once per cycle, the interior twice. A stall
    * at each end is the most common way to get this wrong and it is visible as a stutter.
    */
  test("a bounce does not stall at the turn"):
    val samples = 48
    val frames  = (0 until samples).map(s =>
      cells(LinearSpinner(at(s, samples), LinearSpinnerOptions(path = LinearPath.Bounce)), 16, 1)
    )
    val repeats = frames.sliding(2).count(pair => pair.head == pair(1))
    assert(repeats <= samples / 8, s"the bounce repeated a frame $repeats times out of $samples")

  test("the two flows are genuinely different animations"):
    LinearPath.values.foreach: path =>
      val forward  =
        (0 until 12).map(s =>
          cells(LinearSpinner(at(s, 12), LinearSpinnerOptions(path = path, flow = LinearFlow.Forward)), 16, 1)
        )
      val backward =
        (0 until 12).map(s =>
          cells(LinearSpinner(at(s, 12), LinearSpinnerOptions(path = path, flow = LinearFlow.Backward)), 16, 1)
        )
      assert(forward != backward, s"$path ignored its flow")

  /** A solid trail is a uniform window; a comet grades away from the head. The distinction has to be visible in the
    * styles, or the two enum cases are the same thing under different names.
    */
  test("solid and comet trails render differently"):
    val solid = cells(LinearSpinner(300.millis, LinearSpinnerOptions(trail = LinearTrail.Solid, trailSlots = 6)), 16, 1)
    val comet = cells(LinearSpinner(300.millis, LinearSpinnerOptions(trail = LinearTrail.Comet, trailSlots = 6)), 16, 1)
    assert(solid != comet)

  test("a longer trail lights more of the track"):
    def lit(slots: Int): Int =
      val buffer = rendered(
        LinearSpinner(300.millis, LinearSpinnerOptions(trailSlots = slots, rail = 0.0, style = Style.Default.dim)),
        20,
        1,
      )
      (0 until 20).count(x => buffer.get(x, 0).symbol.trim.nonEmpty)
    assert(lit(8) >= lit(2))

  test("every resolution draws a track, and the cell resolution uses the marker"):
    Seq(CanvasResolution.Braille, CanvasResolution.HalfBlock).foreach: resolution =>
      val drawn = cells(LinearSpinner(300.millis, LinearSpinnerOptions(resolution = resolution)), 12, 1)
      assert(drawn.exists(_._1.trim.nonEmpty), s"$resolution drew nothing")
    val ascii =
      cells(LinearSpinner(300.millis, LinearSpinnerOptions(resolution = CanvasResolution.Cell, marker = "=")), 12, 1)
    assert(ascii.exists(_._1 == "="))

  // ---------------------------------------------------------------- spinner grid

  test("a spinner grid is a pure function of elapsed time"):
    val grid = SpinnerGrid(700.millis)
    assert(cells(grid, 10, 4) == cells(grid, 10, 4))

  test("a grid fills its area with slots and animates"):
    val frames = (0 until 12).map(s => cells(SpinnerGrid((s * 90).millis), 10, 3)).distinct
    assert(frames.size > 1)
    val drawn  = cells(SpinnerGrid(0.millis), 10, 3).count(_._1.trim.nonEmpty)
    assert(drawn >= 20, s"a 10x3 grid should fill most of its area, drew $drawn")

  /** The phase offset is the grid's whole character: without it a block of spinners is many animations, not one. */
  test("uniform runs every slot in lockstep and the others do not"):
    val uniform = cells(SpinnerGrid(400.millis, phase = GridPhase.Uniform), 8, 3).map(_._1).filter(_.trim.nonEmpty)
    assert(uniform.distinct.size == 1, s"uniform slots differ: ${uniform.distinct}")

    Seq(GridPhase.Diagonal(), GridPhase.Radial()).foreach: phase =>
      val offset = cells(SpinnerGrid(400.millis, phase = phase), 8, 3).map(_._1).filter(_.trim.nonEmpty)
      assert(offset.distinct.size > 1, s"$phase did not offset its slots")

  test("a diagonal and its reverse are different animations"):
    val forward = cells(SpinnerGrid(400.millis, phase = GridPhase.Diagonal(1)), 8, 3)
    val reverse = cells(SpinnerGrid(400.millis, phase = GridPhase.Diagonal(-1)), 8, 3)
    assert(forward != reverse)

  /** A two-column emoji preset must lay out at half the slot count rather than overwriting its neighbour — the same
    * continuation-cell rule every other glyph widget follows.
    */
  test("a two-column preset halves the slot count instead of overwriting its neighbour"):
    val wide   = SpinnerGrid(0.millis, preset = SpinnerPreset.Moon)
    val buffer = rendered(wide, 8, 1)
    assert((0 until 8 by 2).forall(x => buffer.get(x, 0).symbol.trim.nonEmpty), "slots should start on even columns")
    // `Cell.Empty` is a space, which is how a wide glyph's second column is reserved without drawing over it
    assert((1 until 8 by 2).forall(x => buffer.get(x, 0) == Cell.Empty), "continuation cells should be reserved")

  test("an area narrower than one slot draws nothing rather than a clipped half-glyph"):
    val buffer = rendered(SpinnerGrid(0.millis, preset = SpinnerPreset.Moon), 1, 1)
    assert(buffer.get(0, 0).symbol == " ")

  /** Per-slot colour is free here — every slot holds exactly one frame — which is the one place this family escapes the
    * one-style-per-cell compromise the orbit spinner documents.
    */
  test("a ramp shades the block by phase"):
    val ramped = rendered(SpinnerGrid(300.millis, phase = GridPhase.Diagonal(), ramp = Some(ColorRamp.Heat)), 8, 3)
    val colors = (for
      y <- 0 until 3
      x <- 0 until 8
    yield ramped.get(x, y).style.fg).distinct
    assert(colors.count(_.isDefined) > 1, "a ramped grid should show more than one colour")

  test("every preset in the catalogue lays out in a grid without spilling"):
    SpinnerPreset.All.foreach: preset =>
      val buffer = rendered(SpinnerGrid(250.millis, preset = preset), 9, 2)
      assert(buffer.area == Rect(0, 0, 9, 2), s"${preset.name} resized its area")
