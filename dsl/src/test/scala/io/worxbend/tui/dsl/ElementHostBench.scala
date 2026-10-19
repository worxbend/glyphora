package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.testsupport.Bench

/** Times one `ElementHost` frame — resolve, focus pass, paint — of a dashboard-like view with per-node bounds recording
  * off, narrow (keyed nodes, containers, input targets — what `boundsOf` and click-outside read) and full (every node —
  * the inspector, the element-tree snapshot), so the cost of the recorder is a number rather than a guess.
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

  /** How much a configuration records. */
  private enum Recording:
    case Off, Narrow, Full

  /** One configuration under test: a host recording nothing, the narrow set or every node, painting one size. */
  private final class Configuration(
      label: String,
      area: Rect,
      recording: Recording,
      wide: Boolean = false,
      virtual: Boolean = false,
  ):
    private val host   = ElementHost()
    private val buffer = Buffer(area)
    private val state  = ScrollViewState()
    private val rows   = LazyListState()
    recording match
      case Recording.Off    => ()
      case Recording.Narrow => host.enableBoundsRecording()
      case Recording.Full   => host.enableFullBoundsRecording()

    def name: String =
      val shape = if virtual then " lazy" else if wide then " wide" else ""
      s"$label$shape recording ${recording.toString.toLowerCase(java.util.Locale.ROOT)}"

    def measure(iterations: Int): Bench.Result =
      Bench.measure(name, iterations) { () =>
        buffer.reset()
        val view = if virtual then lazyTree(rows) else if wide then wideTree(state) else dashboard(state)
        host.render(area, buffer, Theme.Dark, view)
        Bench.consume(buffer.area.width.toLong)
      }

  def main(args: Array[String]): Unit =
    val _              = args
    val configurations =
      sizes.flatMap((label, area) => Recording.values.toSeq.map(recording => Configuration(label, area, recording))) ++
        Recording.values.toSeq.map(recording => Configuration("80x24", Rect(0, 0, 80, 24), recording, wide = true)) ++
        Recording.values.toSeq.map(recording => Configuration("80x24", Rect(0, 0, 80, 24), recording, virtual = true))
    // every configuration shares the same resolve, focus-pass and paint code, so the first one measured would
    // otherwise pay for compiling it: warm all of them, several rounds over, before timing any of them — and time
    // them in reverse, so whatever warmth is left favours "recording off" and the recorder's cost reads high, not low
    (1 to 3).foreach(_ => configurations.foreach(_.measure(iterations = 100)))
    val results        = configurations.reverse.map(_.measure(iterations = 100)).reverse
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

  /** A tree whose per-frame walks, not its paint, dominate: two thousand keyed rows of a label and a button in a scroll
    * view, of which a viewport's worth is painted — the shape the fused frame pass exists for.
    */
  private def wideTree(scrolling: ScrollViewState): View =
    scrollView(
      column((0 until 2000).map(i => row(text(s"item $i").length(12), button("open")(()).key(s"b$i")))*),
      2000,
      scrolling,
    )

  /** [[wideTree]]'s two thousand rows as a `lazyList`, which builds, decorates and paints only the visible ones. */
  private def lazyTree(rows: LazyListState): View =
    lazyList(2000, 1, rows)(i => row(text(s"item $i").length(12), button("open")(()).key(s"b$i")))
