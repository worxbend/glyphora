package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.testsupport.Bench

/** Times one `ElementHost` frame — resolve, focus pass, paint — of a dashboard-like view with per-node bounds recording
  * off and on, so the cost of the recorder is a number rather than a guess.
  *
  * Run it with:
  * {{{
  * ./mill dsl.test.runMain io.worxbend.tui.dsl.ElementHostBench
  * }}}
  *
  * It asserts nothing and is not part of any suite; see [[io.worxbend.tui.testsupport.Bench]] for why a wall-clock
  * number is not a CI gate. Its widget-level companion is `RenderLoopBench` in `widgets`, which times the composition
  * alone with no element tree over it.
  */
object ElementHostBench:

  private given ReactiveScope = ReactiveScope.untracked

  private val sizes: Seq[(String, Rect)] =
    Seq("80x24" -> Rect(0, 0, 80, 24), "200x50" -> Rect(0, 0, 200, 50))

  def main(args: Array[String]): Unit =
    val _       = args
    val results = sizes.flatMap { (label, area) =>
      Seq(false, true).map { recording =>
        val host   = ElementHost()
        host.recordBounds = recording
        val buffer = Buffer(area)
        val state  = ScrollViewState()
        Bench.measure(s"$label recording ${if recording then "on" else "off"}", iterations = 100) { () =>
          buffer.reset()
          host.render(area, buffer, Theme.Dark, dashboard(state))
          Bench.consume(buffer.area.width.toLong)
        }
      }
    }
    Bench.report("ElementHost frame (resolve, focus pass, paint)", results)
    println(s"checksum ${Bench.consumed}")

  /** Roughly a hundred nodes with the shapes a real screen has: chrome bars, a keyed form, meters, and a scroll view of
    * forty rows, of which only a viewport's worth is painted.
    */
  private def dashboard(scrolling: ScrollViewState): View =
    column(
      topBar("bench", tabs = Seq("overview", "detail", "logs"), right = "v0"),
      row(
        panel("Form")(
          (0 until 8).map(i => row(text(s"field $i").length(10), input(TextInputState(s"value $i")).key(s"f$i")))*
        ).percent(40),
        column(
          panel("Meters")((0 until 6).map(i => gauge((i + 1) / 7.0))*),
          panel("Rows")(scrollView(column((0 until 40).map(i => text(s"row $i").key(s"r$i"))*), 40, scrolling)),
        ).fill,
      ).fill,
      row(button("Save")(()).key("save"), button("Cancel")(()).key("cancel"), spacer).length(1),
      statusBar(Seq("ctrl+s" -> "save", "q" -> "quit")),
    )
