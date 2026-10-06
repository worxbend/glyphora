package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, MouseEvent, MouseEventKind, Position, Rect, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.ScrollViewState

import org.scalatest.funsuite.AnyFunSuite

final class PaintedInputSpec extends AnyFunSuite:

  private val area = Rect(0, 0, 20, 4)
  private val down = MouseEvent(Position(3, 0), MouseEventKind.Down, KeyModifiers.None)

  private def paint(host: ElementHost, root: Element): Unit =
    val buffer = Buffer(area)
    host.renderTree(root, Style.Default, tree => tree.widget.render(area, buffer))

  test("a pointer-only overlay wins over a focusable sibling without changing focus"):
    val host   = ElementHost()
    var bottom = 0
    var top    = 0
    val root   = layers(
      button("BOTTOM") { bottom += 1 }.key("bottom"),
      text("TOP").onMouseEvent { _ => top += 1; true },
    )
    paint(host, root)
    host.clearFocus()
    assert(host.dispatchMouse(down))
    assert(top == 1)
    assert(bottom == 0)
    assert(host.tracker.focusedIndex == -1)

  test("a declining pointer overlay bubbles only through its own ancestors"):
    val host  = ElementHost()
    var calls = Vector.empty[String]
    val root  = layers(
      button("BOTTOM") { calls :+= "bottom" },
      column(text("TOP").onMouseEvent { _ => calls :+= "top"; false })
        .onMouseEvent { _ => calls :+= "parent"; false },
    ).onMouseEvent { _ => calls :+= "root"; true }
    paint(host, root)
    assert(host.dispatchMouse(down))
    assert(calls == Vector("top", "parent", "root"))

  test("a focusable overlay wins over a pointer-only sibling and user handling precedes its builtin"):
    val host  = ElementHost()
    var calls = Vector.empty[String]
    val root  = layers(
      text("BOTTOM").onMouseEvent { _ => calls :+= "bottom"; true },
      button("TOP") { calls :+= "builtin" }.onMouseEvent { _ => calls :+= "user"; false }.key("top"),
    )
    paint(host, root)
    host.clearFocus()
    assert(host.dispatchMouse(down))
    assert(calls == Vector("user", "builtin"))
    assert(host.tracker.focusedIndex == 0)

  test("a pointer-only portal wins by deferred paint order rather than its tree position"):
    val host   = ElementHost()
    var bottom = 0
    var top    = 0
    val root   = layers(
      portal(0, 0, 10, 1)(text("TOP").onMouseEvent { _ => top += 1; true }),
      button("BOTTOM") { bottom += 1 },
    )
    PortalQueue.begin()
    try
      val buffer = Buffer(area)
      host.renderTree(root, Style.Default, tree => tree.widget.render(area, buffer))
      PortalQueue.drain().foreach((target, content) => content.widget.render(target, buffer))
      host.clearFocus()
      assert(host.dispatchMouse(down))
      assert(top == 1)
      assert(bottom == 0)
      assert(host.tracker.focusedIndex == -1)
    finally PortalQueue.end()

  test("an inert deferred portal cannot steal a modal button's click or focus"):
    val backend         = HeadlessBackend(Size(40, 12))
    var firstClicks     = 0
    var targetClicks    = 0
    var focusedKeys     = 0
    var suppressedCalls = 0
    val app             = new TuiApp:
      private def dialog: View                      = positioned(10, 3, 20, 5)(
        layers(
          positioned(0, 0, 20, 1)(button("FIRST") { firstClicks += 1 }.key("first")),
          positioned(0, 2, 20, 1)(
            button("TARGET") { targetClicks += 1 }.key("target").onKeyEvent {
              case KeyEvent(KeyCode.Char('x'), _) => focusedKeys += 1; true
              case _                              => false
            }
          ),
        )
      )
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+o", "open")(pushScreen(Screen(dialog))))
      def view(using ReactiveScope, Theme): Element =
        portal(12, 5, 8, 1)(
          column(text("INERT").onMouseEvent { _ => suppressedCalls += 1; true })
            .onMouseEvent { _ => suppressedCalls += 1; true }
        )
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitForIdle().press("ctrl+o").waitForIdle()
      // The covered portal still paints after the modal; suppressing input must not suppress its rendering.
      assert(pilot.screenLines(5).contains("INERT"))
      pilot.click(12, 5).waitForIdle()
      assert(targetClicks == 1)
      assert(firstClicks == 0)
      assert(suppressedCalls == 0)
      pilot.press("x").waitForIdle()
      assert(focusedKeys == 1)
      assert(targetClicks == 1)
    }

  test("an inert deferred portal cannot hide a modal's click-outside backdrop"):
    val backend         = HeadlessBackend(Size(40, 12))
    var suppressedCalls = 0
    var leaves          = 0
    val app             = new TuiApp:
      private def dialog: View                      = centered(20, 5)(panel("DIALOG")(text("BODY")))
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+o", "open")(
          pushScreen(Screen(dialog, onLeave = () => leaves += 1, dismissal = Dismissal.ClickOutside))
        )
      )
      def view(using ReactiveScope, Theme): Element =
        portal(1, 1, 8, 1)(text("INERT").onMouseEvent { event =>
          // The release after dismissal belongs to the now-active base; only the closing press was suppressed.
          if event.kind == MouseEventKind.Down then suppressedCalls += 1
          true
        })
    Pilot.using(backend)(app.runWith(backend)) { pilot =>
      pilot.waitForIdle().press("ctrl+o").waitForIdle()
      assert(pilot.screenText.contains("DIALOG"))
      assert(pilot.screenLines(1).contains("INERT"))
      pilot.click(1, 1).waitForIdle()
      assert(!pilot.screenText.contains("DIALOG"))
      assert(leaves == 1)
      assert(suppressedCalls == 0)
    }

  test("suppressed scroll and portal nodes paint at their screen coordinates without supplying mouse hits"):
    val state  = ScrollViewState()
    state.offset = 2
    var calls  = 0
    val raw    = positioned(5, 4, 20, 4)(
      scrollView(
        portal(1, 7, 6, 1)(
          layers(button("INERT") { calls += 1 }, text("INERT").onMouseEvent { _ => calls += 1; true })
        ),
        contentHeight = 12,
        state,
      )
    )
    val host   = ElementHost()
    val buffer = Buffer(Rect(0, 0, 40, 16))
    PortalQueue.begin()
    try
      host.renderTree(FocusPass.suppressFocus(raw), Style.Default, tree => tree.widget.render(buffer.area, buffer))
      val queued = PortalQueue.drain()
      assert(queued.map(_._1) == Seq(Rect(6, 9, 6, 1)))
      queued.foreach((target, content) => content.widget.render(target, buffer))
      assert(buffer.get(6, 9).symbol == "I")
      assert(host.tracker.focusableCount == 0)
      assert(host.tracker.mouseHit(Position(6, 9)).isEmpty)
      assert(!host.dispatchMouse(MouseEvent(Position(6, 9), MouseEventKind.Down, KeyModifiers.None)))
      assert(calls == 0)
    finally PortalQueue.end()

  test("an inert ancestor excludes its descendants from focus and pointer registration"):
    val covered = column(
      button("COVERED") {}.key("covered").autofocus,
      text("POINTER").onMouseEvent(_ => true),
    )
    val inert   = covered.withProps(covered.props.copy(focusState = covered.props.focusState.copy(inert = true)))
    val root    = layers(button("ACTIVE") {}.key("active"), inert)
    val host    = ElementHost()
    assert(FocusPass.focusKeys(root) == Vector(Some("active")))
    assert(FocusPass.autofocusRequest(root).isEmpty)
    paint(host, root)
    assert(host.tracker.focusableCount == 1)
    assert(host.tracker.mouseHit(Position(3, 0)).exists(_.target == InputTarget.Focus(0)))
    assert(host.tracker.pointerAreaOf(0).isEmpty)
    assert(host.tracker.areaOf(1).isEmpty)

  test("a removed overlay cannot retain its hit record in a later frame"):
    val host   = ElementHost()
    var bottom = 0
    paint(host, layers(button("BOTTOM") { bottom += 1 }, text("TOP").onMouseEvent(_ => true)))
    paint(host, button("BOTTOM") { bottom += 1 })
    assert(host.dispatchMouse(down))
    assert(bottom == 1)
