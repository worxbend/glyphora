package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Size, Text}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.widgets.{Paragraph, ScrollViewState}

import org.scalatest.funsuite.AnyFunSuite

final class MeasurementSpec extends AnyFunSuite with PilotFixture:

  /** Eight rows of content behind a raw widget, which is what the escape hatch has to be able to measure. */
  private def eightRows: Paragraph = Paragraph(Text.raw((0 until 8).map(n => s"row $n").mkString("\n")))

  test("fixed-size elements report their intrinsic height"):
    assert(text("a\nb\nc").intrinsicHeight(20).contains(3))
    assert(gauge(0.5).intrinsicHeight(20).contains(1))
    assert(spacer.intrinsicHeight(20).isEmpty) // fill: unmeasurable

  test("columns sum measurable children; rows take the max; panels add their borders"):
    assert(column(text("a"), text("b\nc")).intrinsicHeight(20).contains(3))
    assert(row(text("a"), text("b\nc")).intrinsicHeight(20).contains(2))
    assert(panel("t")(text("a"), text("b")).intrinsicHeight(20).contains(4))
    assert(column(text("a"), spacer).intrinsicHeight(20).isEmpty) // a fill child poisons the sum
    assert(row().intrinsicHeight(20).contains(0)) // an empty row is measurable emptiness, like an empty column
    assert(column().intrinsicHeight(20).contains(0))

  test("a panel's gaps are charged to its measured height, the way a column's are"):
    // two children, one gap: 2 content rows + 1 gap + 2 border rows. Left out, a spaced panel inside a scrollView
    // measures one row short per gap and the last line of its content is unreachable.
    assert(column(text("a"), text("b")).gap(1).intrinsicHeight(20).contains(3))
    assert(panel("t")(text("a"), text("b")).gap(1).intrinsicHeight(20).contains(5))
    assert(panel("t")(text("a"), text("b"), text("c")).gap(2).intrinsicHeight(20).contains(9))

  test("markdown measures its wrapped height for the width"):
    val element = markdown("1234567890 1234567890") // 21 columns of prose
    assert(element.intrinsicHeight(30).contains(1))
    assert(element.intrinsicHeight(10).exists(_ >= 2)) // must wrap at narrow widths

  test("a scrollView without an explicit height measures its content end to end"):
    val backend = HeadlessBackend(Size(12, 3))
    val state   = ScrollViewState()
    val content = column((0 until 8).map(n => text(s"row $n"))*)
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = scrollView(content, state)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenLines.head.startsWith("row 0"))
    pilot.pressKey(KeyCode.PageDown).waitForIdle()
    assert(state.offset == 5) // 8 measured rows - 3 viewport: measurement found the real content height
    assert(pilot.screenLines.head.startsWith("row 5"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a raw widget that measures itself needs no measurement wiring at the element"):
    // `widget(...)` has no measurement code of its own any more: it asks the widget, which implements `Measured`.
    assert(widget(eightRows).intrinsicHeight(20).contains(8))
    assert(
      widget(Paragraph(Text.raw("1234567890 1234567890"), overflow = Overflow.Wrap)).intrinsicHeight(10).exists(_ >= 2)
    )

  test("an explicit length beats what the widget says about itself"):
    // the caller measured the whole node on purpose; the widget's own answer must not silently overrule it
    assert(widget(eightRows).length(2).intrinsicHeight(20).contains(2))
    assert(widget(eightRows).fill.intrinsicHeight(20).isEmpty) // a fill is the container's decision, so: unmeasurable

  test("a scrollView over a self-measuring raw widget scrolls its whole content"):
    val backend = HeadlessBackend(Size(12, 3))
    val state   = ScrollViewState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = scrollView(widget(eightRows), state)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenLines.head.startsWith("row 0"))
    pilot.pressKey(KeyCode.PageDown).waitForIdle()
    assert(state.offset == 5, "8 measured rows - 3 viewport: the widget's own measurement reached the scroll view")
    assert(pilot.screenLines.head.startsWith("row 5"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a scrollView scrolls to the last row of a spaced panel"):
    val backend = HeadlessBackend(Size(12, 4))
    val state   = ScrollViewState()
    // 3 content rows + 2 gap rows + 2 border rows = 7; a viewport of 4 leaves 3 rows below the fold
    val content = panel(text("row 0"), text("row 1"), text("row 2")).gap(1)
    assert(content.intrinsicHeight(11).contains(7))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = scrollView(content, state)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.PageDown).waitForIdle()
    assert(state.offset == 3, "the gaps have to be in the measured height or the bottom border is unreachable")
    assert(pilot.screenLines.last.contains("└"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a scrollView reaches the last row of a titled borderless panel"):
    val backend = HeadlessBackend(Size(12, 3))
    val state   = ScrollViewState()
    // 3 content rows + 1 row for the caption the borderless panel reserves above them = 4; a viewport of 3 leaves one
    // row below the fold
    val content = panel("t")(text("row 0"), text("row 1"), text("row 2")).borderless
    assert(content.intrinsicHeight(11).contains(4))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = scrollView(content, state)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.PageDown).waitForIdle()
    assert(state.offset == 1, "the caption's row has to be in the measured height or the last content row is lost")
    assert(pilot.screenLines.last.startsWith("row 2"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** The known consequence of the fallback in [[ScrollViewElement]]: content that cannot report a height measures as
    * exactly one viewport, so it never scrolls and whatever is below the fold is unreachable. There is no better answer
    * at render time, so this test pins the behaviour rather than pretending it is fine — the fix for an app that hits
    * it is an explicit `.length(n)`, the `contentHeight` argument, or a `Measured` widget.
    */
  test("a scrollView over content that cannot measure itself does not scroll"):
    val backend = HeadlessBackend(Size(12, 3))
    val state   = ScrollViewState()
    val content = column(text("row 0"), text("row 1"), spacer) // the fill child makes the column unmeasurable
    assert(content.intrinsicHeight(11).isEmpty)
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = scrollView(content, state)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.PageDown).waitForIdle()
    assert(state.offset == 0)
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
