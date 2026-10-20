package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, MouseEvent, MouseEventKind, Position, Rect, Style}

import org.scalatest.funsuite.AnyFunSuite

final class CaptureLifecycleRegressionSpec extends AnyFunSuite:

  private val area = Rect(0, 0, 30, 6)

  private def paint(host: ElementHost, root: Element): Unit =
    host.renderTree(root, Style.Default, _.widget.render(area, Buffer(area)))

  private def send(host: ElementHost, kind: MouseEventKind, x: Int = 2, y: Int = 0): Unit =
    val _ = host.dispatchMouse(MouseEvent(Position(x, y), kind, KeyModifiers.None))

  test("removing a captured keyed pointer target never hands its gesture to the reused ordinal"):
    val host                        = ElementHost()
    var events                      = Vector.empty[(String, MouseEventKind)]
    def node(name: String): Element = text(name).key(name).length(1).onMouseEvent { event =>
      events :+= name -> event.kind
      true
    }
    paint(host, column(node("A"), node("B")))
    send(host, MouseEventKind.Down)
    paint(host, column(node("B")))
    // Both the former capture's tail and the replacement's actual hit must be rejected.
    send(host, MouseEventKind.Drag)
    send(host, MouseEventKind.Up)
    assert(events == Vector("A" -> MouseEventKind.Down))
    send(host, MouseEventKind.Down)
    send(host, MouseEventKind.Up)
    assert(events.takeRight(2) == Vector("B" -> MouseEventKind.Down, "B" -> MouseEventKind.Up))

  Seq(false, true).foreach { focusable =>
    test(s"keyed capture follows insertion and reorder with press-time geometry, focusable=$focusable"):
      val host                        = ElementHost()
      var events                      = Vector.empty[(String, MouseContext)]
      def node(name: String): Element =
        val target = text(name).key(name).length(1).onMouse { context =>
          events :+= name -> context
          true
        }
        target.withProps(target.props.copy(focusable = focusable))
      paint(host, column(node("A"), node("B")))
      send(host, MouseEventKind.Down)
      val pressedArea                 = events.head._2.area
      paint(host, column(node("C"), node("B"), node("A")))
      send(host, MouseEventKind.Drag, 50, 5)
      send(host, MouseEventKind.Up, 50, 5)
      assert(events.map(_._1) == Vector("A", "A", "A"))
      assert(events.map(_._2.area).distinct == Vector(pressedArea))
      assert(events.last._2.local == Position(50 - pressedArea.x, 5 - pressedArea.y))

    test(s"unkeyed removal cancels instead of capturing a replacement, focusable=$focusable"):
      val host                        = ElementHost()
      var events                      = Vector.empty[(String, MouseEventKind)]
      def node(name: String): Element =
        val target = text(name).length(1).onMouseEvent { event =>
          events :+= name -> event.kind
          true
        }
        target.withProps(target.props.copy(focusable = focusable))
      paint(host, column(node("A"), node("B")))
      send(host, MouseEventKind.Down)
      paint(host, column(node("B")))
      send(host, MouseEventKind.Drag)
      send(host, MouseEventKind.Up)
      assert(events == Vector("A" -> MouseEventKind.Down))
  }

  test("keyed slider capture keeps the pressed signal and geometry after real host recomposition"):
    given ReactiveScope   = ReactiveScope.untracked
    val host              = ElementHost()
    val a                 = Signal(0)
    val b                 = Signal(0)
    val buffer            = Buffer(area)
    def aControl: Element = row(slider(a).key("a").length(12), spacer.fill).length(1)
    def bControl: Element = row(slider(b).key("b").length(12), spacer.fill).length(1)
    host.render(area, buffer, Theme.Dark, column(aControl, bControl))
    send(host, MouseEventKind.Down, 1)
    host.render(area, buffer, Theme.Dark, column(text("inserted").length(1), bControl, aControl))
    send(host, MouseEventKind.Drag, 17, 5)
    send(host, MouseEventKind.Up, 17, 5)
    assert(a.peek == 100)
    assert(b.peek == 0)

  test("unkeyed built-in capture cancels when removal changes the input topology"):
    given ReactiveScope = ReactiveScope.untracked
    val host            = ElementHost()
    val a               = Signal(0)
    val b               = Signal(0)
    val buffer          = Buffer(area)
    host.render(area, buffer, Theme.Dark, column(slider(a).length(1), slider(b).length(1)))
    send(host, MouseEventKind.Down, 1)
    host.render(area, buffer, Theme.Dark, column(slider(b).length(1)))
    send(host, MouseEventKind.Drag, 29)
    send(host, MouseEventKind.Up, 29)
    assert(a.peek == 0)
    assert(b.peek == 0)
    send(host, MouseEventKind.Down, 29)
    assert(b.peek == 100)

  Seq(false, true).foreach { pointerOnly =>
    test(s"same-slot unkeyed slider replacement retires both callbacks, pointerOnly=$pointerOnly"):
      given ReactiveScope                       = ReactiveScope.untracked
      val host                                  = ElementHost()
      val a                                     = Signal(0)
      val b                                     = Signal(0)
      val buffer                                = Buffer(area)
      def control(signal: Signal[Int]): Element =
        val node = slider(signal).bold.pressedStyle(Style.Default.underline).length(1)
        node.withProps(node.props.copy(focusable = !pointerOnly)).background(Style.Default)
      host.render(area, buffer, Theme.Dark, column(control(a)))
      send(host, MouseEventKind.Down, 1)
      host.render(area, buffer, Theme.Dark, column(control(b)))
      send(host, MouseEventKind.Drag, 29)
      send(host, MouseEventKind.Up, 29)
      assert(a.peek == 0, "retired press-time signal must not receive the tail")
      assert(b.peek == 0, "replacement signal must not receive the tail")
      host.render(area, buffer, Theme.Dark, column(control(a)))
      send(host, MouseEventKind.Drag, 29)
      assert(a.peek == 0, "returning an owner cannot resurrect a retired capture")
      host.render(area, buffer, Theme.Dark, column(control(b)))
      send(host, MouseEventKind.Down, 29)
      assert(b.peek == 100, "a fresh press recovers")

    test(s"ordinary unkeyed slider survives fluent copies and repeated redraws, pointerOnly=$pointerOnly"):
      given ReactiveScope  = ReactiveScope.untracked
      val host             = ElementHost()
      val value            = Signal(0)
      val buffer           = Buffer(area)
      def control: Element =
        val node = slider(value).bold.pressedStyle(Style.Default.underline).length(1)
        node.withProps(node.props.copy(focusable = !pointerOnly)).background(Style.Default)
      host.render(area, buffer, Theme.Dark, column(control))
      send(host, MouseEventKind.Down, 1)
      host.render(area, buffer, Theme.Dark, column(control))
      send(host, MouseEventKind.Drag, 14)
      assert(value.peek > 0 && value.peek < 100)
      host.render(area, buffer, Theme.Dark, column(control))
      send(host, MouseEventKind.Drag, 40, 5)
      send(host, MouseEventKind.Up, 40, 5)
      assert(value.peek == 100)
  }

  test("same-slot unkeyed range slider replacement invokes neither retired nor replacement callback"):
    given ReactiveScope = ReactiveScope.untracked
    val host            = ElementHost()
    val a               = RangeSliderState(SliderRange.Percent, 0, 100)
    val b               = RangeSliderState(SliderRange.Percent, 0, 100)
    val buffer          = Buffer(area)
    var aCalls          = 0
    var bCalls          = 0
    def first: Element  = rangeSlider(a, (_, _) => aCalls += 1).bold.length(1)
    def second: Element = rangeSlider(b, (_, _) => bCalls += 1).bold.length(1)
    host.render(area, buffer, Theme.Dark, column(first))
    send(host, MouseEventKind.Down, 1)
    val pressed         = (a.low, a.high)
    val calls           = aCalls
    host.render(area, buffer, Theme.Dark, column(second))
    send(host, MouseEventKind.Drag, 20)
    send(host, MouseEventKind.Up, 20)
    assert((a.low, a.high) == pressed)
    assert(aCalls == calls)
    assert(bCalls == 0)
    send(host, MouseEventKind.Down, 20)
    assert(bCalls == 1)

  test("unkeyed range slider keeps its caller-owned state through rebuilt adapters"):
    given ReactiveScope  = ReactiveScope.untracked
    val host             = ElementHost()
    val state            = RangeSliderState(SliderRange.Percent, 0, 100)
    val buffer           = Buffer(area)
    var calls            = 0
    def control: Element = rangeSlider(state, (_, _) => calls += 1).bold.length(1)
    host.render(area, buffer, Theme.Dark, column(control))
    send(host, MouseEventKind.Down, 1)
    host.render(area, buffer, Theme.Dark, column(control))
    send(host, MouseEventKind.Drag, 14)
    send(host, MouseEventKind.Up, 14)
    assert(state.low > 0)
    assert(calls == 2)

  Seq(false, true).foreach { replace =>
    test(s"signal-backed split capture preserves only the same caller-owned lifetime, replace=$replace"):
      given ReactiveScope                       = ReactiveScope.untracked
      val host                                  = ElementHost()
      val a                                     = Signal(50)
      val b                                     = Signal(50)
      val buffer                                = Buffer(area)
      def control(signal: Signal[Int]): Element =
        // The split itself handles Drag; an ancestor admits the initial press on its divider.
        column(splitPane(text("left"), text("right"), signal).bold.background(Style.Default)).onMouseEvent {
          _.kind == MouseEventKind.Down
        }
      host.render(area, buffer, Theme.Dark, control(a))
      send(host, MouseEventKind.Down, 14)
      host.render(area, buffer, Theme.Dark, control(if replace then b else a))
      send(host, MouseEventKind.Drag, 27)
      send(host, MouseEventKind.Up, 27)
      assert(a.peek == (if replace then 50 else 90))
      assert(b.peek == 50)
      if replace then
        send(host, MouseEventKind.Down, 14)
        send(host, MouseEventKind.Drag, 27)
        send(host, MouseEventKind.Up, 27)
        assert(b.peek == 90)

    test(s"window capture preserves only the same caller-owned state, replace=$replace"):
      given ReactiveScope                   = ReactiveScope.untracked
      val host                              = ElementHost()
      val stack                             = WindowStack()
      val a                                 = stack.window(0, 0, 12, 4)
      val b                                 = stack.window(0, 0, 12, 4)
      val buffer                            = Buffer(area)
      def control(state: WindowState): View =
        windowStack(window("owner", state)(text("body")).bold.background(Style.Default))
      host.render(area, buffer, Theme.Dark, control(a))
      send(host, MouseEventKind.Down, 2)
      host.render(area, buffer, Theme.Dark, control(if replace then b else a))
      send(host, MouseEventKind.Drag, 7)
      send(host, MouseEventKind.Up, 7)
      assert(a.rect.x == (if replace then 0 else 5))
      assert(b.rect.x == 0)
      if replace then
        send(host, MouseEventKind.Down, 2)
        send(host, MouseEventKind.Drag, 7)
        send(host, MouseEventKind.Up, 7)
        assert(b.rect.x == 5)
  }

  test("a stable caller-owned signal does not override the unkeyed structural topology guard"):
    given ReactiveScope  = ReactiveScope.untracked
    val host             = ElementHost()
    val value            = Signal(0)
    val buffer           = Buffer(area)
    def control: Element = slider(value).length(1)
    host.render(area, buffer, Theme.Dark, column(control))
    send(host, MouseEventKind.Down, 1)
    host.render(area, buffer, Theme.Dark, column(text("inserted").length(1), control))
    send(host, MouseEventKind.Drag, 29, 1)
    send(host, MouseEventKind.Up, 29, 1)
    assert(value.peek == 0)
    send(host, MouseEventKind.Down, 29, 1)
    assert(value.peek == 100)

  test("unknown unkeyed builtin ownership cancels even when its callback reference is stable"):
    given ReactiveScope       = ReactiveScope.untracked
    val host                  = ElementHost()
    val buffer                = Buffer(area)
    var calls                 = 0
    val onChange: Int => Unit = _ => calls += 1
    def control: Element      = SliderElement(0, onChange).length(1)
    host.render(area, buffer, Theme.Dark, column(control))
    send(host, MouseEventKind.Down, 1)
    host.render(area, buffer, Theme.Dark, column(control))
    send(host, MouseEventKind.Drag, 29)
    send(host, MouseEventKind.Up, 29)
    assert(calls == 1, "a callback is not proof of caller-owned lifetime")

  test("lazy-window replacement retires a captured item before another item inherits its ordinal"):
    given ReactiveScope = ReactiveScope.untracked
    val host            = ElementHost()
    val state           = LazyListState()
    val buffer          = Buffer(Rect(0, 0, 20, 3))
    var events          = Vector.empty[(Int, MouseEventKind)]
    val view: View      = lazyList(100, 1, state) { i =>
      text(s"item $i").key(s"item-$i").onMouseEvent { event =>
        events :+= i -> event.kind
        true
      }
    }
    host.render(buffer.area, buffer, Theme.Dark, view)
    send(host, MouseEventKind.Down)
    state.selected = Some(50)
    host.render(buffer.area, buffer, Theme.Dark, view)
    send(host, MouseEventKind.Drag)
    send(host, MouseEventKind.Up)
    assert(events == Vector(0 -> MouseEventKind.Down))
    send(host, MouseEventKind.Down)
    send(host, MouseEventKind.Up)
    assert(events.size == 3)
    assert(events.last._1 != 0)

  test("omitting a still-declared captured node retires it rather than routing to its unpainted handlers"):
    val host   = ElementHost()
    var events = Vector.empty[MouseEventKind]
    val target = text("A").key("A").onMouseEvent { event => events :+= event.kind; true }
    paint(host, target)
    send(host, MouseEventKind.Down)
    paint(host, positioned(0, 0, 0, 0)(target))
    send(host, MouseEventKind.Drag)
    send(host, MouseEventKind.Up)
    paint(host, target)
    send(host, MouseEventKind.Drag)
    assert(events == Vector(MouseEventKind.Down))

  test("ancestor disabling permanently retires capture even if the control is reenabled before its tail"):
    val host            = ElementHost()
    var events          = Vector.empty[MouseEventKind]
    def target: Element = text("A").key("A").onMouseEvent { event => events :+= event.kind; true }
    paint(host, column(target))
    send(host, MouseEventKind.Down)
    paint(host, column(target).disabled())
    paint(host, column(target))
    send(host, MouseEventKind.Drag)
    send(host, MouseEventKind.Up)
    assert(events == Vector(MouseEventKind.Down))

  test("covering focus layers retire capture even when the modal reuses the same public key"):
    val host                        = ElementHost()
    var events                      = Vector.empty[String]
    def node(name: String): Element = text(name).key("shared").onMouseEvent { event =>
      events :+= s"$name:${event.kind}"
      true
    }
    paint(host, node("base"))
    send(host, MouseEventKind.Down)
    host.tracker.pushLayer()
    paint(host, node("modal"))
    send(host, MouseEventKind.Drag)
    send(host, MouseEventKind.Up)
    host.tracker.popLayer()
    paint(host, node("base"))
    send(host, MouseEventKind.Drag)
    send(host, MouseEventKind.Up)
    assert(events == Vector("base:Down"))
    send(host, MouseEventKind.Down)
    send(host, MouseEventKind.Up)
    assert(events.takeRight(2) == Vector("base:Down", "base:Up"))
