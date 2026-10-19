package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Rect, Size}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** `ElementTreeSnapshot.render`: the decorated tree as text — node type, key, focus index, constraint and painted
  * bounds — for the "why did that not get focus" question a frame dump cannot answer.
  *
  * The expected dumps are written by hand from the layout rules (a `text` claims one row, a `spacer` fills what is
  * left, a `row` splits its width between a fill and a `length`), not copied from a run, so the assertion can disagree
  * with the code.
  */
final class ElementTreeSnapshotSpec extends AnyFunSuite with PilotFixture:

  private given ReactiveScope = ReactiveScope.untracked

  private def settings: View =
    column(
      text("Settings"),
      row(spacer, button("Save")(()).key("save").length(10)).length(1),
      spacer,
    )

  private val expectedSettings: String =
    """column @ 0,0 20x4
      |  text @ 0,0 20x1
      |  row Length(1) @ 0,1 20x1
      |    spacer @ 0,1 10x1
      |    button key=save focus=0 focused Length(10) @ 10,1 10x1
      |  spacer @ 0,2 20x2""".stripMargin

  test("a small app's tree shows type, key, focus index, focus mark, constraint and painted bounds"):
    val host = ElementHost()
    host.enableFullBoundsRecording()
    host.render(Rect(0, 0, 20, 4), Buffer(Rect(0, 0, 20, 4)), Theme.Dark, settings)
    assert(ElementTreeSnapshot.render(host) == expectedSettings)

  test("the snapshot is stable across two frames of the same view"):
    val host  = ElementHost()
    host.enableFullBoundsRecording()
    host.render(Rect(0, 0, 20, 4), Buffer(Rect(0, 0, 20, 4)), Theme.Dark, settings)
    val first = ElementTreeSnapshot.render(host)
    host.render(Rect(0, 0, 20, 4), Buffer(Rect(0, 0, 20, 4)), Theme.Dark, settings)
    assert(ElementTreeSnapshot.render(host) == first)

  test("without bounds recording the bounds column reads '-' and the rest of the dump is unchanged"):
    val host     = ElementHost()
    host.render(Rect(0, 0, 20, 4), Buffer(Rect(0, 0, 20, 4)), Theme.Dark, settings)
    val expected = expectedSettings.linesIterator.map(_.replaceAll(" @ .*$", " @ -")).mkString("\n")
    assert(ElementTreeSnapshot.render(host) == expected)

  test("a layer a modal covers is marked inert, and a scroll view's content keeps its frame coordinates"):
    val state      = ScrollViewState()
    val view: View =
      layers(
        FocusPass.suppressFocus(text("below")),
        positioned(2, 1, 10, 4)(scrollView(column((0 until 6).map(i => text(s"row $i"))*), 6, state)),
      )
    val host       = ElementHost()
    host.enableFullBoundsRecording()
    host.render(Rect(0, 0, 20, 7), Buffer(Rect(0, 0, 20, 7)), Theme.Dark, view)
    state.scrollDown(2)
    host.render(Rect(0, 0, 20, 7), Buffer(Rect(0, 0, 20, 7)), Theme.Dark, view)
    // the viewport shows content rows 2-5 at frame rows 1-4; the column itself starts one row above the frame, and the
    // two rows scrolled wholly out of the viewport were never painted, so they have no bounds at all
    val expected   =
      """layers @ 0,0 20x7
        |  text inert @ 0,0 20x7
        |  positioned @ 0,0 20x7
        |    scrollView focus=0 focused @ 2,1 10x4
        |      column @ 2,-1 9x6
        |        text @ -
        |        text @ -
        |        text @ 2,1 9x1
        |        text @ 2,2 9x1
        |        text @ 2,3 9x1
        |        text @ 2,4 9x1""".stripMargin
    assert(ElementTreeSnapshot.render(host) == expected)

  test("rendered through a running app, the snapshot is read on the render thread"):
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element = settings
    val backend = HeadlessBackend(Size(20, 4))
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.readOnRenderThread(ElementTreeSnapshot.render(app)) == expectedSettings)
    assert(ElementTreeSnapshot.render(ElementHost()) == "<no frame rendered>")
    pilot.press("ctrl+c")
    assert(pilot.awaitTermination())
    assert(ElementTreeSnapshot.render(app) == "<not running>")

  test("every node type in the dsl sources declares its own debugName"):
    DocumentationSources.repoRoot match
      case None       => cancel("not running from a checkout")
      case Some(root) =>
        val dir     = root.resolve("dsl/src/main/scala/io/worxbend/tui/dsl")
        val sources = Using.resource(Files.list(dir))(_.iterator.asScala.filter(_.toString.endsWith(".scala")).toVector)
        val Node    = """^(?:private\[dsl\] )?final (?:case )?class (\w+Element)\b""".r
        // a top-level declaration and everything under it, up to the next line that opens with a letter, `/` or `@` at
        // column zero — a multi-line constructor closes with `) extends …:` at column zero and must stay in its chunk
        val chunks  = sources.flatMap(path => Files.readString(path).split("(?m)^(?=[A-Za-z/@])").toVector)
        val nodes   = chunks.flatMap(chunk => Node.findFirstMatchIn(chunk).map(found => found.group(1) -> chunk))
        assert(nodes.size >= 50, s"found only ${nodes.size} node types — the scan would pass vacuously")
        val missing = nodes.collect {
          // a decorator forwards `debugName` to what it wraps, so it is exempt by construction
          case (name, chunk) if !chunk.contains("override def debugName") && !chunk.contains("DecoratingElement") =>
            name
        }
        assert(missing.isEmpty, s"node types without a debugName: ${missing.mkString(", ")}")
