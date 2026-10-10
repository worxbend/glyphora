package io.worxbend.tui.dsl

import io.worxbend.tui.core.{
  Buffer,
  KeyCode,
  KeyEvent,
  KeyModifiers,
  Modifiers,
  MouseEvent,
  MouseEventKind,
  Position,
  Rect,
}
import io.worxbend.tui.runtime.ReactiveScope
import io.worxbend.tui.testsupport.BufferAssertions.trimmedLines

import org.scalatest.funsuite.AnyFunSuite

/** The render-and-dispatch engine on its own, with no `TuiApp`, no runner and no terminal.
  *
  * That is the whole point of the class, so these tests drive it the way an embedding host would: build a `Buffer`,
  * render a view into it, deliver events by hand.
  */
final class ElementHostSpec extends AnyFunSuite:

  // nothing here tests reactivity, so the views read their signals — if they had any — without subscribing anything
  private given ReactiveScope = ReactiveScope.untracked

  private def buffer(width: Int, height: Int): Buffer = Buffer(Rect(0, 0, width, height))

  private def press(host: ElementHost, code: KeyCode): Boolean =
    host.dispatchKey(KeyEvent(code, KeyModifiers.None))

  test("Host and App share hooks, responsive portals and paste at offset scroll origins"):
    def component: Element =
      val scrolling = useState(ScrollViewState())
      positioned(3, 2, 10, 3)(
        scrollView(
          responsive(_ => layers(text("body"), portal(1, 0, 7, 1)(input(useState(TextInputState("hook"))).autofocus))),
          6,
          scrolling,
        )
      )
    val host               = ElementHost()
    val paint              = buffer(20, 7)
    host.render(paint.area, paint, Theme.Dark, component)
    val app                = new TuiApp:
      def view(using ReactiveScope, Theme): Element = component
    io.worxbend.tui.testsupport.Pilot.using(Size(20, 7))(backend => app.runWith(backend)) { pilot =>
      pilot.waitForIdle()
      assert(pilot.screenLines == trimmedLines(paint))
      assert(host.dispatchPaste("!"))
      pilot.paste("!").waitForIdle()
      val next = buffer(20, 7)
      host.render(next.area, next, Theme.Dark, component)
      assert(pilot.screenLines == trimmedLines(next))
    }

  test("reset retires hook identity and the last dispatch tree"):
    val host       = ElementHost()
    var states     = Vector.empty[Object]
    val view: View =
      states :+= useState(new Object)
      button("active")(()).key("active")
    host.render(Rect(0, 0, 8, 1), buffer(8, 1), Theme.Dark, view)
    host.reset()
    assert(!host.dispatchKey(Key.Enter))
    assert(host.focusedKey.isEmpty)
    host.render(Rect(0, 0, 8, 1), buffer(8, 1), Theme.Dark, view)
    assert(!(states(0) eq states(1)))

  test("a nested host restores outer painting coordinates and portal collection after failure"):
    val inner     = ElementHost()
    val transform = ViewportTransform(5, 7, Rect(5, 7, 8, 3))
    FrameCoordinates.during(transform) {
      PortalQueue.during {
        PortalQueue.offer(Rect(0, 0, 2, 1), text("outer"))
        intercept[IllegalArgumentException] {
          inner.render(
            Rect(0, 0, 2, 1),
            buffer(2, 1),
            Theme.Dark,
            widget((_, _) => throw new IllegalArgumentException("paint")),
          )
        }
        assert(FrameCoordinates.translate(Rect(0, 0, 2, 1)) == Rect(5, 7, 2, 1))
        assert(PortalQueue.drain().map(_._1) == Seq(Rect(5, 7, 2, 1)))
      }
    }
    assert(!PortalQueue.isCollecting)
    assert(FrameCoordinates.translate(Rect(0, 0, 2, 1)) == Rect(0, 0, 2, 1))

  test("host hooks persist once per frame and nested evaluations restore their owner"):
    val outer       = ElementHost()
    val inner       = ElementHost()
    var evaluations = 0
    var owned       = Vector.empty[Object]
    val view: View  =
      evaluations += 1
      val before = useState(new Object)
      inner.render(Rect(0, 0, 2, 1), buffer(2, 1), Theme.Dark, text(useState("inner")))
      val after  = useState(new Object)
      owned = owned ++ Vector(before, after)
      text("outer")
    outer.render(Rect(0, 0, 10, 1), buffer(10, 1), Theme.Dark, view)
    outer.render(Rect(0, 0, 10, 1), buffer(10, 1), Theme.Dark, view)
    assert(evaluations == 2)
    assert(owned(0) eq owned(2))
    assert(owned(1) eq owned(3))
    assert(!(owned(0) eq owned(1)))

  test("host portals paint last but cannot escape the host boundary"):
    val host       = ElementHost()
    val paint      = buffer(12, 4)
    paint.setString(0, 1, "............", Style.Default)
    val view: View = layers(portal(-2, 0, 10, 1)(text("0123456789")), text("xxxxx"))
    host.render(Rect(3, 1, 5, 1), paint, Theme.Dark, view)
    assert((0 until 12).map(x => paint.get(x, 1).symbol).mkString == "...23456....")

  test("a view renders into a plain buffer with no runner at all"):
    val host  = ElementHost()
    val paint = buffer(10, 3)
    host.render(Rect(0, 0, 10, 3), paint, Theme.Dark, panel(text("hi")))

    assert(trimmedLines(paint).exists(_.contains("hi")))

  test("the responsive pass picks the branch for the area actually being painted"):
    val view: View = responsive(size => if size.width < 40 then text("narrow") else text("wide"))
    val host       = ElementHost()

    val small = buffer(20, 3)
    host.render(Rect(0, 0, 20, 3), small, Theme.Dark, view)
    assert(trimmedLines(small).head.trim == "narrow")

    val large = buffer(80, 3)
    host.render(Rect(0, 0, 80, 3), large, Theme.Dark, view)
    assert(trimmedLines(large).head.trim == "wide")

  test("a key reaches the focused element and an unhandled one comes back unconsumed"):
    var pressed    = 0
    val view: View = column(button("first")(pressed += 1), button("second")(()))
    val host       = ElementHost()
    host.render(Rect(0, 0, 20, 4), buffer(20, 4), Theme.Dark, view)

    assert(press(host, KeyCode.Enter), "the focused button did not take Enter")
    assert(pressed == 1)
    assert(!press(host, KeyCode.F(12)), "an unbound key was reported as consumed")

  test("dispatchKey does not move focus: traversal is the host's policy"):
    val view: View = column(button("first")(()), button("second")(()))
    val host       = ElementHost()
    host.render(Rect(0, 0, 20, 4), buffer(20, 4), Theme.Dark, view)

    val before = host.tracker.focusedIndex
    val _      = host.dispatchKey(KeyEvent(KeyCode.Tab, KeyModifiers.None))
    assert(host.tracker.focusedIndex == before, "the engine moved focus on Tab, which is TuiApp's decision")

  test("focusNext and focusPrevious cycle the focusables"):
    val view: View = column(
      button("first")(()).key("first"),
      button("second")(()).key("second"),
      button("third")(()).key("third"),
    )
    val host       = ElementHost()
    host.render(Rect(0, 0, 20, 4), buffer(20, 4), Theme.Dark, view)
    assert(host.focusedKey.contains("first"))

    assert(host.focusNext())
    host.render(Rect(0, 0, 20, 4), buffer(20, 4), Theme.Dark, view)
    assert(host.focusedKey.contains("second"))

    assert(host.focusPrevious())
    host.render(Rect(0, 0, 20, 4), buffer(20, 4), Theme.Dark, view)
    assert(host.focusedKey.contains("first"))

    assert(host.focusToKey("third"))
    host.render(Rect(0, 0, 20, 4), buffer(20, 4), Theme.Dark, view)
    assert(host.focusedKey.contains("third"))

  test("the focused element is the one drawn with the theme's focus cue"):
    val view: View = column(button("first")(()), button("second")(()))
    val host       = ElementHost()
    val paint      = buffer(20, 4)
    host.render(Rect(0, 0, 20, 4), paint, Theme.Dark, view)

    val rows                    = trimmedLines(paint)
    val focusedRow              = rows.indexWhere(_.contains("first"))
    val unfocusedRow            = rows.indexWhere(_.contains("second"))
    def hasCue(y: Int): Boolean =
      (0 until 20).exists(x => paint.get(x, y).style.modifiers.hasAny(Modifiers.Reverse))

    assert(hasCue(focusedRow), "the focused button carries no cue")
    assert(!hasCue(unfocusedRow), "an unfocused button carries the cue")

  test("a press inside another focusable moves focus, one on empty space does not"):
    val view: View = column(
      button("first")(()).key("first").length(1),
      button("second")(()).key("second").length(1),
      spacer,
    )
    val host       = ElementHost()
    host.render(Rect(0, 0, 20, 6), buffer(20, 6), Theme.Dark, view)

    val onSecond = MouseEvent(Position(2, 1), MouseEventKind.Down, KeyModifiers.None)
    assert(host.dispatchMouse(onSecond), "clicking the second button did nothing")
    // the key of the focused element is read off the last frame, so the move shows up once the next one is painted
    host.render(Rect(0, 0, 20, 6), buffer(20, 6), Theme.Dark, view)
    assert(host.focusedKey.contains("second"))

    val onNothing = MouseEvent(Position(2, 5), MouseEventKind.Down, KeyModifiers.None)
    assert(!host.dispatchMouse(onNothing), "a click on empty space was reported as handled")
    host.render(Rect(0, 0, 20, 6), buffer(20, 6), Theme.Dark, view)
    assert(host.focusedKey.contains("second"), "a click on empty space moved focus")

  test("nothing is dispatched before the first frame"):
    val host = ElementHost()
    assert(!press(host, KeyCode.Enter))
    assert(!host.dispatchPaste("hello"))

  test("clearFocus leaves nothing focused, so keys pass straight through"):
    var pressed    = 0
    val view: View = button("only")(pressed += 1)
    val host       = ElementHost()
    host.render(Rect(0, 0, 20, 3), buffer(20, 3), Theme.Dark, view)

    host.clearFocus()
    // events are routed against the tree the last frame painted, so the cleared focus bites from the next frame on
    host.render(Rect(0, 0, 20, 3), buffer(20, 3), Theme.Dark, view)
    assert(!press(host, KeyCode.Enter), "a key was consumed with nothing focused")
    assert(pressed == 0)

  /** "Nothing is focused" and "nothing is focusable" are two different states.
    *
    * The second one is why [[ElementHost.dispatchKey]] has a depth-first fallback at all: a tree of plain text has no
    * focused element to start the bubble from, so the key is offered to every node instead. After `clearFocus` the tree
    * still has focusables — the user simply took focus off them — and offering the key to every element's own
    * `onKeyEvent` would fire handlers the person at the terminal is not pointing at.
    */
  test("clearFocus stops keys reaching element handlers, even ones on unfocused elements"):
    var seen       = 0
    val view: View = column(button("a")(()), text("plain").onKeyEvent(_ => { seen += 1; true }))
    val host       = ElementHost()
    host.render(Rect(0, 0, 20, 4), buffer(20, 4), Theme.Dark, view)

    host.clearFocus()
    host.render(Rect(0, 0, 20, 4), buffer(20, 4), Theme.Dark, view)
    assert(!press(host, KeyCode.Char('x')), "a key was consumed with nothing focused")
    assert(seen == 0, "an element handler ran with nothing focused")

  /** The fallback itself still has to work: a view with nothing focusable in it is the case it exists for. */
  test("a tree with no focusable at all still offers keys to every element handler"):
    var seen       = 0
    val view: View = column(text("plain").onKeyEvent(_ => { seen += 1; true }))
    val host       = ElementHost()
    host.render(Rect(0, 0, 20, 4), buffer(20, 4), Theme.Dark, view)

    assert(press(host, KeyCode.Char('x')))
    assert(seen == 1)
