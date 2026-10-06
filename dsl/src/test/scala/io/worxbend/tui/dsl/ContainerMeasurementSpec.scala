package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.widgets.ScrollViewState

import org.scalatest.funsuite.AnyFunSuite

/** Two invariants the DSL states in prose and could not otherwise prove.
  *
  * The first is `Element.intrinsicHeight`'s three-step fallback, whose first step is `constrainedHeight`: an explicit
  * `.length(n)` is the caller's own answer and always wins, and any other explicit constraint (`.fill`, a percentage)
  * is by definition the container's decision, so the node measures as *unmeasurable*. `Element` calls that step one
  * "which every override below shares" — and `row`, `column` and `panel`, the three nodes every app is built from, used
  * to skip it, so a `.length(n)` on a container was silently overruled by the arithmetic over its children.
  * [[MeasurementSpec]] pins the arithmetic; this pins the constraint that outranks it.
  *
  * The second is [[View]] carrying its `Theme` in its type rather than `TuiApp` installing one as a given around the
  * call to `view`. A given installed around the call is not in scope *inside* the body, which is how an app that
  * overrode `theme` used to get `Theme.Dark` out of a `statusBar(bindings)` written in its own `view`.
  */
final class ContainerMeasurementSpec extends AnyFunSuite with PilotFixture:

  // ---- an explicit constraint on a container outranks the arithmetic over its children ----

  test("an explicit length on a column is the caller's answer, not the sum of its children"):
    val rows = column(text("a"), text("b"), text("c"))
    assert(rows.intrinsicHeight(20).contains(3), "unconstrained, a column still sums its children")
    assert(rows.length(1).intrinsicHeight(20).contains(1))

  test("a panel the container sizes measures as unmeasurable, never as its natural height"):
    val box = panel(text("a"))
    assert(box.intrinsicHeight(20).contains(3), "unconstrained, a panel still adds its two border rows")
    assert(box.fill.intrinsicHeight(20).isEmpty)
    assert(box.length(5).intrinsicHeight(20).contains(5))

  test("a row under a percentage measures as unmeasurable, never as its tallest child"):
    val line = row(text("a"), text("b\nc"))
    assert(line.intrinsicHeight(20).contains(2), "unconstrained, a row still takes the tallest child")
    assert(line.percent(50).intrinsicHeight(20).isEmpty)
    assert(line.length(7).intrinsicHeight(20).contains(7))

  test("a scrollView scrolls the height the caller fixed on its content, not the natural one"):
    // The consequence the arithmetic-only measurement hid: eight natural rows behind an explicit `.length(4)` must
    // scroll as four, so the viewport stops one row down instead of five.
    val backend = HeadlessBackend(Size(12, 3))
    val state   = ScrollViewState()
    val content = column((0 until 8).map(n => text(s"row $n"))*).length(4)
    assert(content.intrinsicHeight(11).contains(4))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = scrollView(content, state)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenLines.head.startsWith("row 0"))
    pilot.press("end").waitForIdle()
    assert(state.offset == 1, "4 constrained rows - 3 viewport: the caller's own length reached the scroll view")
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())

  // ---- the theme an app overrides reaches the helpers its own view calls ----

  /** A sub-view helper of the shape [[View]] exists for: it is handed a body it did not write, and supplies the
    * contexts that body runs under. Because the `Theme` travels *in* the body's type, the palette installed here is the
    * one the body resolves against — not whatever happened to be in scope where the body was written.
    */
  private def themedAs(palette: Theme)(body: View)(using scope: ReactiveScope): Element =
    body(using scope, palette)

  test("a themed helper written in an app's own view resolves the app's theme, not the library default"):
    assert(Theme.Light.surface.bg != Theme.Dark.surface.bg, "the two palettes must differ for this to prove anything")
    val backend = HeadlessBackend(Size(24, 3))
    val app     = new TuiApp:
      override def theme: Theme                     = Theme.Light
      override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = statusBar(Seq("q" -> "quit"))
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.cellAt(0, 0).style.bg == Theme.Light.surface.bg)
    assert(pilot.cellAt(0, 0).style.bg != Theme.Dark.surface.bg, "the default theme leaked past the app's override")
    pilot.press("q")
    assert(pilot.awaitTermination())

  test("a View handed to a sub-view helper is themed where the helper applies it"):
    assert(
      Theme.HighContrast.surface.bg != Theme.Light.surface.bg,
      "the two palettes must differ for this to prove anything",
    )
    val backend = HeadlessBackend(Size(24, 3))
    val app     = new TuiApp:
      override def theme: Theme                     = Theme.Light
      override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
      // the body is written here, under the app's own Light theme, but runs under the palette `themedAs` supplies
      def view(using ReactiveScope, Theme): Element =
        themedAs(Theme.HighContrast)(statusBar(Seq("q" -> "quit")))
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.cellAt(0, 0).style.bg == Theme.HighContrast.surface.bg)
    assert(pilot.cellAt(0, 0).style.bg != Theme.Light.surface.bg, "the body was themed where it was written")
    pilot.press("q")
    assert(pilot.awaitTermination())
