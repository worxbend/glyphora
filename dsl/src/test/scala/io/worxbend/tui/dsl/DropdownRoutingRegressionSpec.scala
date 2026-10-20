package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

final class DropdownRoutingRegressionSpec extends AnyFunSuite with PilotFixture:
  private val options = Seq("FIRST", "SECOND", "THIRD")

  test("BUGS-7 pass-through dropdown outside press also dismisses its click-outside modal"):
    final class App extends TuiApp:
      val state              = DropdownState()
      private def body: View = centered(20, 5)(panel("MODAL")(dropdown(options, 0, state)(_ => ()).passThroughOutside))
      private val modal      = Screen(body, dismissal = Dismissal.ClickOutside)
      override def bindings: KeyBindings            = KeyBindings(binding("o", "open")(pushScreen(modal)))
      def view(using ReactiveScope, Theme): Element = text("PAGE")
    val backend = HeadlessBackend(Size(40, 12))
    val app   = App()
    val pilot = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle().press("o").waitForIdle().press("enter").waitForIdle()
    assert(app.state.open)
    pilot.click(1, 1).waitForIdle()
    assert(!app.state.open)
    assert(!pilot.screenLines.exists(_.contains("MODAL")))

  for pass <- Seq(false, true) do
    test(s"BUGS-8 dropdown scrim forwards wheel to a covered sibling, passThrough=$pass"):
      final class App extends TuiApp:
        val state                                     = DropdownState()
        val selected                                  = ListState(selected = Some(0))
        def view(using ReactiveScope, Theme): Element =
          val picker = dropdown(options, 0, state)(_ => ())
          row(
            (if pass then picker.passThroughOutside else picker).length(15),
            list((0 until 10).map(i => s"ROW$i"), selected),
          )
      val backend = HeadlessBackend(Size(40, 12))
      val app   = App()
      val pilot = startPilot(backend)(app.runWith(backend))
      pilot.waitForIdle().press("enter").waitForIdle()
      pilot.scrollDown(25, 3).waitForIdle().scrollDown(25, 3).waitForIdle()
      assert(app.selected.selected.contains(2))
      assert(app.state.highlighted.contains(0))
      assert(app.state.open)
      pilot.scrollDown(3, 2).waitForIdle()
      assert(app.state.highlighted.contains(1))

  test("BUGS-9 dropdown popup and scrim disappear when its anchor scrolls out of the viewport"):
    final class App extends TuiApp:
      val state                                     = DropdownState()
      val scroll                                    = ScrollViewState()
      var presses                                   = 0
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+b", "bottom")(scroll.last()),
        binding("ctrl+t", "top")(scroll.first()),
      )
      def view(using ReactiveScope, Theme): Element =
        column(
          scrollView(
            column(text("r0"), text("r1"), text("r2"), dropdown(options, 0, state)(_ => ()), text("r4"), text("r5")),
            contentHeight = 6,
            scroll,
          ).followFocus(false).length(3),
          button("FOOTER")(presses += 1),
        )
    val backend = HeadlessBackend(Size(30, 10))
    val app   = App()
    app.state.openAt(0)
    val pilot = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    assert(!pilot.screenLines.exists(_.contains("SECOND")))
    pilot.click(2, 3).waitForIdle()
    assert(app.presses == 1, "an invisible anchor left a frame-sized scrim active")
    pilot.press("ctrl+b").waitForIdle()
    assert(pilot.screenLines.exists(_.contains("SECOND")))
    pilot.press("ctrl+t").waitForIdle()
    assert(!pilot.screenLines.exists(_.contains("SECOND")))
    pilot.click(2, 3).waitForIdle()
    assert(app.presses == 2)
