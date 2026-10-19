package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Effect, MotionDirection, Pattern, Position, Rect, Style}
import io.worxbend.tui.testsupport.Bench

import scala.concurrent.duration.*

/** Times a whole one-second run of a `patterned` effect at 60 frames a second over an unchanging 120 × 40 frame.
  *
  * Run it with:
  * {{{
  * ./mill widgets.test.runMain io.worxbend.tui.widgets.EffectPatternBench
  * }}}
  *
  * One op is the whole run — 60 frames, each re-rendering the content and then playing the effect over it — which is
  * what an app pays for one reveal. A radial and a sweep pattern spread the area over many progress steps per frame; a
  * plain `fadeIn` row is the unpatterned floor to compare against. Asserts nothing; see
  * [[io.worxbend.tui.testsupport.Bench]].
  */
object EffectPatternBench:

  private val area    = Rect(0, 0, 120, 40)
  private val content = Buffer(area)
  (0 until area.height).foreach(row =>
    content.setString(0, row, s"$row " + "lorem ipsum dolor sit amet " * 5, Style.Default)
  )

  private val frames = 60

  def main(args: Array[String]): Unit =
    val _       = args
    val results = Seq(
      run("fadeIn, unpatterned", Effect.fadeIn(1.second)),
      run("patterned radial fadeIn", Effect.patterned(Pattern.radial(Position(60, 20)))(Effect.fadeIn(1.second))),
      run(
        "patterned sweep hslShift",
        Effect.patterned(Pattern.sweep(MotionDirection.Right))(Effect.hslShift(120, 0, 0, 1.second)),
      ),
    )
    Bench.report("Effect runs (60 frames, 120 x 40)", results)
    println(s"checksum ${Bench.consumed}")

  private def run(name: String, effect: Effect): Bench.Result =
    val buffer = Buffer(area)
    Bench.measure(name, iterations = 3) { () =>
      (0 to frames).foreach { frame =>
        buffer.copyFrom(content)
        effect.process((1000L * frame / frames).millis, buffer, area)
      }
      Bench.consume(buffer.get(60, 20).symbol.length.toLong)
    }
