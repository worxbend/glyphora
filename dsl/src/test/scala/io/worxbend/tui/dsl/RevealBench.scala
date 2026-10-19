package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.testsupport.Bench

/** Times a frame on which a `scrollView` has to scroll to keep the focused child on screen — every frame here moves
  * focus to the next of 200 buttons, so every frame reveals.
  *
  * Run it with:
  * {{{
  * ./mill dsl.test.runMain io.worxbend.tui.dsl.RevealBench
  * }}}
  *
  * Before 0.20.0's follow-up a revealing frame painted the view's content twice, once at the old offset to find the
  * child and again at the new one; it is now resolved before the paint, from where the last frame painted the child.
  * Asserts nothing; see [[io.worxbend.tui.testsupport.Bench]].
  */
object RevealBench:

  private given ReactiveScope = ReactiveScope.untracked

  def main(args: Array[String]): Unit =
    val _       = args
    // one untimed round of both first, so neither configuration pays for compiling the shared code
    val _       = Seq(false, true).map(recording => frame(recording))
    val results = Seq(false, true).map(recording => frame(recording))
    Bench.report("scrollView over 200 buttons, focus moving every frame", results)
    println(s"checksum ${Bench.consumed}")

  private def frame(recording: Boolean): Bench.Result =
    val host    = ElementHost()
    host.recordBounds = recording
    val area    = Rect(0, 0, 80, 24)
    val buffer  = Buffer(area)
    val state   = ScrollViewState()
    val content = (0 until 200).map(n => button(s"button $n")(()).key(s"b$n"))
    val view    = (scope: ReactiveScope, theme: Theme) ?=> scrollView(column(content*), state)
    host.render(area, buffer, Theme.Dark, view)
    Bench.measure(s"80x24 recording ${if recording then "on" else "off"}", iterations = 100) { () =>
      // wraps round to the top after the last button, so each frame scrolls a row or jumps back to the start
      if !host.focusNext() then { val _ = host.focusNext() }
      buffer.reset()
      host.render(area, buffer, Theme.Dark, view)
      Bench.consume(state.offset.toLong)
    }
