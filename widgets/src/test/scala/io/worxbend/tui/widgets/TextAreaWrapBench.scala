package io.worxbend.tui.widgets

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.testsupport.Bench

/** Times one typing frame of a soft-wrapped [[TextArea]] over a 2 000-line document — the cost the per-line wrap cache
  * removes.
  *
  * Run it with:
  * {{{
  * ./mill widgets.test.runMain io.worxbend.tui.widgets.TextAreaWrapBench
  * }}}
  *
  * *cached* types one character and renders, which re-wraps the one edited line. *uncached* alternates the width by a
  * column every frame, which defeats the cache and so costs what every wrapped frame cost before it: every line of the
  * document wrapped again. Asserts nothing; see [[io.worxbend.tui.testsupport.Bench]].
  */
object TextAreaWrapBench:

  def main(args: Array[String]): Unit =
    val _       = args
    val results = Seq(frame(cached = true), frame(cached = false))
    Bench.report("Soft-wrapped TextArea, 2000 lines (one typing frame)", results)
    println(s"checksum ${Bench.consumed}")

  private def frame(cached: Boolean): Bench.Result =
    val state  = TextAreaState(
      Vector.fill(2000)("lorem ipsum dolor sit amet, consectetur adipiscing elit").mkString("\n")
    )
    state.moveTo(TextPosition(1000, 0))
    val editor = TextArea(options = TextAreaOptions(wrap = true))
    var n      = 0
    Bench.measure(s"2000 lines, ${if cached then "cached" else "uncached"}", iterations = 50) { () =>
      n += 1
      val width  = if cached || n % 2 == 0 then 80 else 79
      val buffer = Buffer(Rect(0, 0, width, 24))
      state.insert("x")
      editor.render(buffer.area, buffer, state)
      Bench.consume(state.lineCount.toLong)
    }
