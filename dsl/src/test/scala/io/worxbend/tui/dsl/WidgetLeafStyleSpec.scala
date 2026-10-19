package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, Size, Style, Widget}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions

import org.scalatest.funsuite.AnyFunSuite

/** `widget(...)` leaves honour the element style: it is painted under whatever the wrapped widget draws. */
final class WidgetLeafStyleSpec extends AnyFunSuite with PilotFixture:

  /** Writes `a` unstyled and `b` in its own green, leaving the rest of the area alone. */
  private val drawsTwo: Widget = (area, buffer) =>
    buffer.setString(area.x, area.y, "a", Style.Default)
    buffer.setString(area.x + 1, area.y, "b", Style.Default.withFg(Color.Green))

  test("a style set on a widget leaf fills what the widget left unset and never overrides what it drew"):
    val out = BufferAssertions.rendered(widget(drawsTwo).fg(Color.Red).bg(Color.Blue).widget, 4, 1)
    assert(out.get(0, 0).style == Style.Default.withFg(Color.Red).withBg(Color.Blue))
    assert(out.get(1, 0).style.fg.contains(Color.Green), "the widget's own colour wins")
    assert(out.get(1, 0).style.bg.contains(Color.Blue))
    assert(out.get(3, 0).style.bg.contains(Color.Blue), "cells the widget did not touch take the style too")

  test("an unstyled widget leaf draws exactly what the widget draws"):
    val out = BufferAssertions.rendered(widget(drawsTwo).widget, 4, 1)
    assert(out.get(0, 0).style == Style.Default)
    assert(out.get(3, 0).style == Style.Default)

  test("withStyle's cascade reaches a widget leaf in a running app"):
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element = withStyle(_.withBg(Color.Magenta))(widget(drawsTwo))
    val backend = HeadlessBackend(Size(4, 1))
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    assert(pilot.cellAt(0, 0).style.bg.contains(Color.Magenta), pilot.cellAt(0, 0).toString)
    assert(pilot.cellAt(1, 0).style.fg.contains(Color.Green))
