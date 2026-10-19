package io.worxbend.tui.core

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{Duration, DurationInt, DurationLong, FiniteDuration}

final class PatternSpec extends AnyFunSuite:

  private val area = Rect(0, 0, 9, 5)

  test("radial: the centre has finished while the corner has not started at t=0.5"):
    val radial = Pattern.radial(Position(4, 2))
    assert(radial(0.5, 4, 2, area) == 1.0)
    assert(radial(0.5, 0, 0, area) == 0.0)
    assert(radial(0.0, 4, 2, area) == 0.0)
    assert(radial(1.0, 0, 0, area) == 1.0)

  test("diagonal runs from the top-left corner to the bottom-right one"):
    val diagonal = Pattern.diagonal()
    assert(diagonal(0.5, 0, 0, area) == 1.0)
    assert(diagonal(0.5, 8, 4, area) == 0.0)
    assert(diagonal(1.0, 8, 4, area) == 1.0)

  test("sweep leads from the edge opposite the direction it travels"):
    assert(Pattern.sweep(MotionDirection.Right)(0.5, 0, 2, area) == 1.0)
    assert(Pattern.sweep(MotionDirection.Right)(0.5, 8, 2, area) == 0.0)
    assert(Pattern.sweep(MotionDirection.Left)(0.5, 8, 2, area) == 1.0)
    assert(Pattern.sweep(MotionDirection.Down)(0.5, 3, 0, area) == 1.0)
    assert(Pattern.sweep(MotionDirection.Up)(0.5, 3, 0, area) == 0.0)
    assert(Pattern.sweep(MotionDirection.Up)(0.5, 3, 4, area) == 1.0)

  test("checker alternates squares: one colour plays the first half, the other the second"):
    val checker = Pattern.checker(2)
    assert(checker(0.25, 0, 0, area) == 0.5)
    assert(checker(0.25, 2, 0, area) == 0.0) // next square along the row
    assert(checker(0.25, 0, 2, area) == 0.0) // next square down the column
    assert(checker(0.25, 2, 2, area) == 0.5) // diagonal neighbour shares the colour
    assert(checker(0.5, 0, 0, area) == 1.0)
    assert(checker(0.75, 1, 3, area) == 0.5)

  test("wave staggers columns by a sinusoid of the row"):
    val wave = Pattern.wave(1.0)
    assert(wave(0.0, 0, 0, area) == 0.0)
    assert(wave(1.0, 8, 4, area) == 1.0)
    val rowA = wave(0.5, 4, 1, area)
    val rowB = wave(0.5, 4, 3, area)
    assert(rowA != rowB, "rows a half wave apart reach the same column at different times")

  test("every pattern stays in [0, 1] and is finished at t=1, even on a one-cell area"):
    val tiny     = Rect(3, 3, 1, 1)
    val patterns = Seq(
      Pattern.radial(Position(0, 0)),
      Pattern.diagonal(),
      Pattern.sweep(MotionDirection.Left),
      Pattern.checker(0),
      Pattern.wave(3.0),
    )
    for p <- patterns; a <- Seq(area, tiny); t <- Seq(-0.5, 0.0, 0.3, 0.7, 1.0, 1.5) do
      a.foreachPosition: (x, y) =>
        val v = p(t, x, y, a)
        assert(v >= 0.0 && v <= 1.0, s"$p at $t ($x,$y) = $v")
        if t >= 1.0 then assert(v == 1.0)

  test("Progress.staggered: a cell with no delay leads, a cell with full delay trails"):
    assert(Progress.staggered(0.0, delay = 0.0, softness = 0.5) == 0.0)
    assert(Progress.staggered(1.0, delay = 1.0, softness = 0.5) == 1.0)
    assert(math.abs(Progress.staggered(0.5, delay = 0.0, softness = 0.5) - 1.0) < 1e-9)
    assert(Progress.staggered(0.5, delay = 1.0, softness = 0.5) == 0.0)

  test("patterned gives each cell its own progress through the inner effect"):
    val buffer = Buffer(Rect(0, 0, 6, 1))
    buffer.setString(0, 0, "abcdef", Style.Default)
    Effect
      .patterned(Pattern.sweep(MotionDirection.Right, softness = 0.0001))(Effect.dissolve(1.second))
      .process(
        500.millis,
        buffer,
        buffer.area,
      )
    // the leading half has finished dissolving, the trailing half has not started
    assert((0 until 3).forall(x => buffer.get(x, 0).isBlank))
    assert((3 until 6).forall(x => !buffer.get(x, 0).isBlank))

  /** An effect that counts how often it is evaluated, painting each cell with the progress step it was asked for. */
  private final class Counting(total: FiniteDuration) extends Effect:
    var calls: Int                                                         = 0
    def duration: Duration                                                 = total
    def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit =
      calls += 1
      area.foreachPosition((x, y) =>
        buffer.set(x, y, buffer.get(x, y).copy(symbol = (elapsed.toMillis / 100).toString))
      )

  private def frame(text: String): Buffer =
    val buffer = Buffer(Rect(0, 0, 9, 1))
    buffer.setString(0, 0, text, Style.Default)
    buffer

  test("patterned evaluates its inner effect once per progress step over a whole run of unchanged frames"):
    val inner = Counting(1.second)
    val fx    = Effect.patterned(Pattern.sweep(MotionDirection.Right))(inner)
    (0 to 60).foreach(f => fx.process((1000L * f / 60).millis, frame("abcdefghi"), Rect(0, 0, 9, 1)))
    // 65 steps exist (0..64); 61 frames each spreading nine cells over several steps used to cost far more
    assert(inner.calls <= Effect.PatternLevels + 1, s"${inner.calls} evaluations")

  test("patterned re-evaluates when the rendered content changes under it"):
    val fx     = Effect.patterned(Pattern.sweep(MotionDirection.Right, softness = 0.0001))(Effect.dissolve(1.second))
    val first  = frame("abcdefghi")
    fx.process(500.millis, first, first.area)
    val second = frame("ABCDEFGHI")
    fx.process(500.millis, second, second.area)
    // the trailing cells have not started dissolving, so they must show the *new* content, not a cached copy
    assert(second.get(8, 0).symbol == "I")
    assert(second.get(0, 0).isBlank)
