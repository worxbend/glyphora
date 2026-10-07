package io.worxbend.tui.dsl

import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

final class SliderRangeInteractionSpec extends AnyFunSuite:

  for range <- Seq(
      SliderRange.of(Int.MinValue, Int.MaxValue, Int.MaxValue),
      SliderRange.of(-1500000000, 1500000000, 1000000000),
      SliderRange.of(0, 100, 10),
      SliderRange.of(Int.MinValue, Int.MinValue),
      SliderRange.of(Int.MaxValue, Int.MaxValue),
    )
  do
    test(s"arrow keys clamp widened steps within $range"):
      for
        value        <- Seq(range.min, range.max, 0, Int.MinValue, Int.MaxValue).distinct
        (key, delta) <- Seq(KeyCode.Left -> -BigInt(range.step), KeyCode.Right -> BigInt(range.step))
      do
        var changed  = Option.empty[Int]
        val element  = SliderElement(value, next => changed = Some(next), range)
        assert(element.builtinKeyHandler.exists(_(KeyEvent(key, KeyModifiers.None))))
        val expected = (BigInt(value) + delta).max(BigInt(range.min)).min(BigInt(range.max)).toInt
        assert(changed.contains(expected), s"$value $key must clamp to $expected, got $changed")

  test("Home and End select the declared bounds while unrelated keys bubble without a change"):
    val range   = SliderRange.of(Int.MinValue, Int.MaxValue)
    var changed = Option.empty[Int]
    val element = SliderElement(0, next => changed = Some(next), range)
    assert(element.builtinKeyHandler.exists(_(KeyEvent(KeyCode.Home, KeyModifiers.None))))
    assert(changed.contains(Int.MinValue))
    assert(element.builtinKeyHandler.exists(_(KeyEvent(KeyCode.End, KeyModifiers.None))))
    assert(changed.contains(Int.MaxValue))
    changed = None
    assert(!element.builtinKeyHandler.exists(_(KeyEvent(KeyCode.Down, KeyModifiers.None))))
    assert(changed.isEmpty)

  for range <- Seq(
      SliderRange.of(Int.MinValue, Int.MaxValue),
      SliderRange.of(-1500000000, 1500000000),
      SliderRange.of(0, 100, 10),
      SliderRange.of(Int.MinValue, Int.MinValue),
      SliderRange.of(Int.MaxValue, Int.MaxValue),
    )
  do
    test(s"presses and drags interpolate and clamp $range without narrowing its span"):
      val area = Rect(2, 1, 11, 1)
      for
        kind <- Seq(MouseEventKind.Down, MouseEventKind.Drag)
        x    <- Seq(3, 4, 7, 11, 2, 12, Int.MinValue, Int.MaxValue)
      do
        var changed  = Option.empty[Int]
        val element  = SliderElement(0, next => changed = Some(next), range)
        val event    = MouseEvent(Position(x, 1), kind, KeyModifiers.None)
        assert(element.builtinMouseHandler.exists(_(event, area)))
        val offset   = (BigInt(x) - 3).max(BigInt(0)).min(BigInt(8))
        val span     = BigInt(range.max) - BigInt(range.min)
        val expected = (BigInt(range.min) + (offset * span + 4) / 8).toInt
        assert(changed.contains(expected), s"$kind at $x must map to $expected, got $changed")

  test("a drag position widens before subtracting the slider's origin"):
    val area    = Rect(Int.MinValue, 1, 11, 1)
    var changed = Option.empty[Int]
    val element = SliderElement(50, next => changed = Some(next))
    val event   = MouseEvent(Position(Int.MaxValue, 1), MouseEventKind.Drag, KeyModifiers.None)
    assert(element.builtinMouseHandler.exists(_(event, area)))
    assert(changed.contains(100))

  test("a track without movable columns consumes pointer presses without changing the value"):
    for width <- 0 to 3 do
      var changed = Option.empty[Int]
      val element = SliderElement(50, next => changed = Some(next))
      for kind <- Seq(MouseEventKind.Down, MouseEventKind.Drag) do
        val event = MouseEvent(Position(2, 1), kind, KeyModifiers.None)
        assert(element.builtinMouseHandler.exists(_(event, Rect(2, 1, width, 1))))
      assert(changed.isEmpty)

  test("pointer releases, motion and scroll events bubble without changing the value"):
    var changed = Option.empty[Int]
    val element = SliderElement(50, next => changed = Some(next))
    for kind <- Seq(MouseEventKind.Up, MouseEventKind.Moved, MouseEventKind.ScrollUp) do
      val event = MouseEvent(Position(5, 1), kind, KeyModifiers.None)
      assert(!element.builtinMouseHandler.exists(_(event, Rect(2, 1, 11, 1))))
    assert(changed.isEmpty)

  test("clicking and dragging a full-range slider renders the selected track position"):
    val value = Signal(Int.MinValue)
    val app   = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        positioned(2, 1, 11, 1)(slider(value, SliderRange.of(Int.MinValue, Int.MaxValue)))
    Pilot.using(Size(16, 3))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle().click(7, 1).waitForIdle()
      assert(value.peek == 0)
      assert(pilot.screenLines(1).contains("├────●────┤"))
      pilot.click(11, 1).waitForIdle()
      assert(value.peek == Int.MaxValue)
      assert(pilot.screenLines(1).contains("├────────●┤"))
      pilot.drag(11, 1, 2, 1).waitForIdle()
      assert(value.peek == Int.MinValue)
      assert(pilot.screenLines(1).contains("├●────────┤"))
      pilot.drag(3, 1, 12, 1).waitForIdle()
      assert(value.peek == Int.MaxValue)
    }

  test("a focused full-range slider never wraps when keys reach an Int boundary"):
    val value = Signal(Int.MaxValue)
    val app   = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        slider(value, SliderRange.of(Int.MinValue, Int.MaxValue, Int.MaxValue))
    Pilot.using(Size(11, 2))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle().pressKey(KeyCode.Right).waitForIdle()
      assert(value.peek == Int.MaxValue)
      assert(pilot.screenLines.head == "├────────●┤")
      pilot.pressKey(KeyCode.Left).waitForIdle()
      assert(value.peek == 0)
      assert(pilot.screenLines.head == "├────●────┤")
      pilot.pressKey(KeyCode.Home).pressKey(KeyCode.Left).waitForIdle()
      assert(value.peek == Int.MinValue)
      assert(pilot.screenLines.head == "├●────────┤")
      pilot.pressKey(KeyCode.Right).waitForIdle()
      assert(value.peek == -1)
    }
