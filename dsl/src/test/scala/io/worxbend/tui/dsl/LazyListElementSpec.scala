package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, KeyCode, KeyEvent, KeyModifiers, MouseEvent, MouseEventKind, Position, Rect}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.testsupport.BufferAssertions.trimmedLines

import org.scalatest.funsuite.AnyFunSuite

/** `lazyList` driven through an `ElementHost`, the way a frame actually reaches it: resolve, focus pass, paint. */
final class LazyListElementSpec extends AnyFunSuite:

  private given ReactiveScope = ReactiveScope.untracked

  /** One frame into a cleared buffer, as a runner draws it. */
  private def draw(host: ElementHost, buffer: Buffer, view: View): Unit =
    buffer.reset()
    host.render(buffer.area, buffer, Theme.Dark, view)

  private def press(host: ElementHost, code: KeyCode): Boolean = host.dispatchKey(KeyEvent(code, KeyModifiers.None))

  /** A builder that counts how many items it was asked for, per frame. */
  private final class Counting:
    var built: Int                     = 0
    def item(i: Int): Element          =
      built += 1
      text(s"item $i")
    def frame[A](body: => A): (A, Int) =
      built = 0
      val result = body
      (result, built)

  test("a hundred thousand items build no more than the viewport plus two, every frame"):
    val counting   = Counting()
    val state      = LazyListState()
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 80, 24))
    val view: View = lazyList(100000, 1, state)(counting.item)
    (1 to 3).foreach { _ =>
      val (_, built) = counting.frame(draw(host, buffer, view))
      assert(built <= 24 + 2, s"built $built items")
    }
    assert(trimmedLines(buffer).take(2) == Seq("item 0", "item 1"))

  test("keyboard and wheel move the selection, and the next frame builds around it"):
    val counting   = Counting()
    val state      = LazyListState()
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 20, 5))
    val view: View = lazyList(1000, 1, state)(counting.item)
    draw(host, buffer, view)
    assert(press(host, KeyCode.Down))
    assert(state.selected.contains(0))
    assert(press(host, KeyCode.End))
    assert(state.selected.contains(999))
    val (_, built) = counting.frame(draw(host, buffer, view))
    assert(built <= 5 + 2)
    assert(trimmedLines(buffer).last == "item 999")
    assert(press(host, KeyCode.Home))
    draw(host, buffer, view)
    assert(trimmedLines(buffer).head == "item 0")
    assert(host.dispatchMouse(MouseEvent(Position(1, 1), MouseEventKind.ScrollDown, KeyModifiers.None)))
    assert(state.selected.contains(1))

  test("a click selects the item under the pointer"):
    val state  = LazyListState()
    val host   = ElementHost()
    val buffer = Buffer(Rect(0, 0, 20, 5))
    host.render(buffer.area, buffer, Theme.Dark, lazyList(50, 1, state)(i => text(s"item $i")))
    assert(host.dispatchMouse(MouseEvent(Position(2, 3), MouseEventKind.Down, KeyModifiers.None)))
    assert(state.selected.contains(3))

  test("focusables inside items take tab stops, keys and recorded bounds"):
    val state      = LazyListState()
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 20, 4))
    var opened     = Option.empty[Int]
    val view: View = lazyList(10000, 1, state)(i => button(s"open $i") { opened = Some(i) }.key(s"b$i"))
    draw(host, buffer, view)
    assert(host.focusToKey("b2"))
    draw(host, buffer, view)
    assert(host.focusedKey.contains("b2"))
    assert(press(host, KeyCode.Enter))
    assert(opened.contains(2))
    host.recordBounds = true
    draw(host, buffer, view)
    assert(host.boundsOf("b1").map(_.y).contains(1))
    assert(host.boundsOf("b500").isEmpty)

  test("the caret of a text input inside an item reaches the frame"):
    val state      = LazyListState()
    val field      = TextInputState("abc")
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 20, 4))
    val view: View = lazyList(100, 1, state)(i => if i == 1 then input(field).key("field") else text(s"item $i"))
    draw(host, buffer, view)
    assert(host.focusToKey("field"))
    draw(host, buffer, view)
    assert(host.dispatchKey(KeyEvent(KeyCode.Char('d'), KeyModifiers.None)))
    assert(field.value == "abcd")

  test("a write into the state between frames is noticed by the host's version polling"):
    val state      = LazyListState()
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 20, 4))
    val view: View = lazyList(100, 1, state)(i => text(s"item $i"))
    draw(host, buffer, view)
    assert(!host.pollStates())
    state.selected = Some(50)
    assert(host.pollStates())
    draw(host, buffer, view)
    assert(trimmedLines(buffer).last == "item 50")

  test("inside a scroll view it measures as every item and builds only what the window shows"):
    val counting   = Counting()
    val state      = LazyListState()
    val scrolling  = ScrollViewState()
    val host       = ElementHost()
    val buffer     = Buffer(Rect(0, 0, 20, 5))
    val view: View = scrollView(lazyList(10000, 1, state)(counting.item), scrolling)
    draw(host, buffer, view)
    scrolling.offset = 300
    // the first frame after a jump the window has not caught up: the items it lacks are built while painting, so the
    // frame shows them rather than blanks, and the miss still asks for the frame that builds them properly
    draw(host, buffer, view)
    assert(scrolling.offset == 300, "the scroll view measured the list as every item, or it would have clamped")
    assert(trimmedLines(buffer).head.startsWith("item 300"), "no blank frame after the jump")
    assert(host.pollStates())
    val (_, built) = counting.frame(draw(host, buffer, view))
    assert(built <= 5 + 2)
    assert(trimmedLines(buffer).head.startsWith("item 300"))
    // a one-row scroll of the enclosing view is covered by the overscan, so it needs no second frame
    scrolling.offset = 301
    draw(host, buffer, view)
    assert(trimmedLines(buffer).head.startsWith("item 301"))

  test("drawn without a host it still renders, building at render time"):
    val element = lazyList(5, 1, LazyListState())(i => text(s"item $i"))
    val buffer  = Buffer(Rect(0, 0, 10, 2))
    element.widget.render(buffer.area, buffer)
    assert(trimmedLines(buffer) == Seq("item 0", "item 1"))
    assert(element.intrinsicHeight(10).contains(5))
