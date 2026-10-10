package thirdparty

import io.worxbend.tui.dsl.*
import org.scalatest.funsuite.AnyFunSuite

final class InteractiveWidgetSpec extends AnyFunSuite:
  private given ReactiveScope = ReactiveScope.untracked

  test("Measured leaves and explicit sizing agree with the normal widget adapter"):
    val measured                               = new Widget with Measured:
      def render(area: Rect, buffer: Buffer): Unit   =
        (0 until 8).foreach(y => buffer.setString(area.x, area.y + y, y.toString, Style.Default))
      override def heightAt(width: Int): Option[Int] = Some(8)
      override def widthAt(height: Int): Option[Int] = Some(4)
    val leaf                                   = interactiveWidget(())((_, _) => measured)
    def paint(element: Element): (Int, String) =
      val scroll = ScrollViewState()
      scroll.offset = 100
      val buffer = Buffer(Rect(0, 0, 12, 3))
      ElementHost().render(buffer.area, buffer, Theme.Dark, scrollView(element, scroll))
      (scroll.offset, buffer.get(0, 0).symbol)
    assert(paint(leaf) == paint(widget(measured)))
    assert(paint(leaf) == (5, "5"))
    assert(paint(leaf.length(5)) == (2, "2"))
    assert(leaf.widget eq measured)

  test("paste handlers compose newest first before default behavior"):
    val state  = TextInputState()
    val leaf   = interactiveWidget(
      state,
      InteractiveHandlers[TextInputState](
        onPaste = (s, value) => { s.insert(value); true }
      ),
    )((s, _) => (area, buffer) => buffer.setString(area.x, area.y, s.value, Style.Default))
      .onPaste(_ == "first")
      .onPaste(_ == "second")
    val host   = ElementHost()
    val buffer = Buffer(Rect(0, 0, 10, 1))
    host.render(buffer.area, buffer, Theme.Dark, leaf)
    assert(host.dispatchPaste("first"))
    assert(host.dispatchPaste("second"))
    assert(state.value == "")

  for (focusable, userFallback) <- Seq((true, false), (false, false), (false, true)) do
    test(s"mouse handlers see unclipped translated bounds inside nested scrolling: $focusable/$userFallback"):
      checkNestedMouseBounds(focusable, userFallback)

  private def checkNestedMouseBounds(focusable: Boolean, userFallback: Boolean): Unit =
    val outer      = ScrollViewState()
    val inner      = ScrollViewState()
    outer.offset = 2
    inner.offset = 2
    var hits       = Vector.empty[(Int, Rect)]
    val leaf       = interactiveWidget(
      (),
      InteractiveHandlers[Unit](onMouse = (_, event, area) => {
        hits :+= ((event.position.y - area.y, area))
        true
      }),
    )((_, _) =>
      (area, buffer) => {
        (0 until 8).foreach(y => buffer.setString(area.x, area.y + y, y.toString, Style.Default))
      }
    ).copy(props = ElementProps(focusable = focusable))
    val target     = if userFallback then leaf.onMouseEvent(_ => false) else leaf
    val view: View = positioned(5, 4, 20, 4)(
      scrollView(
        positioned(2, 1, 12, 4)(scrollView(target, 8, inner)),
        12,
        outer,
      )
    )
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 40, 14))
    host.render(buffer.area, buffer, Theme.Dark, view)
    assert(buffer.get(7, 4).symbol == "3")
    assert(host.dispatchMouse(MouseEvent(Position(7, 4), MouseEventKind.Down, KeyModifiers.None)))
    assert(hits == Vector((3, Rect(7, 1, 11, 8))))
    val _          = host.dispatchMouse(MouseEvent(Position(7, 3), MouseEventKind.Down, KeyModifiers.None))
    val _          = host.dispatchMouse(MouseEvent(Position(7, 7), MouseEventKind.Down, KeyModifiers.None))
    assert(hits.size == 1)

  test("pointer-only defaults preserve user-first consumption, paint order and keyboard focus"):
    var calls        = Vector.empty[String]
    var userConsumes = false
    val leaf         = interactiveWidget(
      (),
      InteractiveHandlers[Unit](onMouse = (_, _, _) => { calls :+= "default"; false }),
    )((_, context) =>
      (area, buffer) => {
        assert(!context.focused)
        buffer.setString(area.x, area.y, "pointer", context.style)
      }
    ).copy(props = ElementProps(focusable = false))
      .onMouseEvent { _ => calls :+= "user"; userConsumes }
    val behind       = button("behind") { calls :+= "behind" }.key("keyboard")
    val host         = ElementHost()
    val buffer       = Buffer(Rect(0, 0, 12, 3))
    val click        = MouseEvent(Position(0, 0), MouseEventKind.Down, KeyModifiers.None)
    host.render(buffer.area, buffer, Theme.Dark, layers(behind, leaf))
    assert(host.focusedKey.contains("keyboard"))
    assert(!host.focusNext())
    assert(!host.dispatchMouse(click))
    assert(calls == Vector("user", "default"))
    assert(host.focusedKey.contains("keyboard"))
    userConsumes = true
    assert(host.dispatchMouse(click))
    assert(calls == Vector("user", "default", "user"))
    host.clearFocus()
    assert(host.dispatchMouse(click))
    assert(host.focusedKey.isEmpty)
    host.render(buffer.area, buffer, Theme.Dark, layers(leaf, behind))
    assert(host.dispatchMouse(click))
    assert(calls.last == "behind")

  test("declining pointer-only defaults let wheel events reach their scroll ancestor"):
    var calls  = 0
    val state  = ScrollViewState()
    val leaf   = interactiveWidget(
      (),
      InteractiveHandlers[Unit](onMouse = (_, _, _) => { calls += 1; false }),
    )((_, _) => (_, _) => ()).copy(props = ElementProps(focusable = false))
    val host   = ElementHost()
    val buffer = Buffer(Rect(0, 0, 12, 3))
    host.render(buffer.area, buffer, Theme.Dark, scrollView(leaf, 12, state))
    assert(host.dispatchMouse(MouseEvent(Position(0, 0), MouseEventKind.ScrollDown, KeyModifiers.None)))
    assert(calls == 1)
    assert(state.offset > 0)

  test("one-import interactive leaf receives Unicode keys and paste with user-first consumption"):
    val state    = TextInputState()
    val handlers = InteractiveHandlers[TextInputState](
      onKey = (s, key) =>
        key.code match
          case KeyCode.Char(c) => s.insert(Character.toString(c)); true
          case _               => false,
      onPaste = (s, text) => { s.insert(text); true },
    )
    val leaf     = interactiveWidget(state, handlers)((s, context) =>
      new Widget with Measured:
        def render(area: Rect, buffer: Buffer): Unit   =
          buffer.setString(area.x, area.y, s.value, if context.focused then context.focusStyle else context.style)
        override def heightAt(width: Int): Option[Int] = Some(1)
        override def widthAt(height: Int): Option[Int] = Some(8)
    ).onPaste(text => text == "blocked")
    val host     = ElementHost()
    val buffer   = Buffer(Rect(0, 0, 12, 3))
    host.render(buffer.area, buffer, Theme.Dark, leaf)
    assert(host.dispatchKey(KeyEvent.char('界')))
    assert(host.dispatchPaste("👩‍💻"))
    assert(host.dispatchPaste("blocked"))
    assert(state.value == "界👩‍💻")
    host.render(buffer.area, buffer, Theme.Dark, leaf)
    assert(buffer.get(0, 0).style == Theme.Dark.focus)
    host.clearFocus()
    host.render(buffer.area, buffer, Theme.Dark, leaf)
    assert(!host.dispatchPaste("ignored"))
    assert(buffer.get(0, 0).style == Style.Default)
