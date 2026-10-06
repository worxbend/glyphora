package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Position, Rect, Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.{ListState, ScrollViewState}

import org.scalatest.funsuite.AnyFunSuite

final class MouseInteractionSpec extends AnyFunSuite with PilotFixture:

  private def startApp(view0: ReactiveScope ?=> Element): Pilot = startAppOn(Size(40, 8))(view0)

  private def startAppOn(size: Size)(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(size)
    val testApp = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  /** Twenty single-row content rows inside a scroll view that is deliberately *not* at the frame origin, so a
    * content-space rect and a screen rect can never coincide.
    *
    * On a 40x12 frame the scroll view lands at `Rect(4, 1, 36, 11)`; the content overflows, so one column goes to the
    * scrollbar and the offscreen content buffer is 35 wide — the content viewport on screen is `Rect(4, 1, 35, 11)`.
    * Focus indices in depth-first order: 0 the scroll view itself, 1 button `TOP` (content row 0), 2 button `A`
    * (content row 8), 3 the slider (content row 12).
    */
  private final class ScrollFixture:
    // the fixture tree is built once, outside any view, so its signal reads subscribe nothing
    private given ReactiveScope = ReactiveScope.untracked
    var topPresses              = 0
    var aPresses                = 0
    val value                   = Signal(0)
    val state                   = ScrollViewState()
    private val contentColumn   = column(
      (0 until 20).map[Element] {
        case 0  => button("TOP") { topPresses += 1 }
        case 8  => button("A") { aPresses += 1 }
        case 12 => slider(value)
        case n  => text(s"row $n")
      }*
    )
    val root: Element           = column(
      text("header").length(1),
      row(text("|").length(4), scrollView(contentColumn, contentHeight = 20, state).fill).fill,
    )

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** One press, not a click: `Pilot.click` posts a Down *and* an Up, which would double every counter below. */
  private def press(pilot: Pilot, x: Int, y: Int): Unit =
    pilot.backend.postEvent(
      io.worxbend.tui.core.Event.Mouse(
        io.worxbend.tui.core.MouseEvent(Position(x, y), io.worxbend.tui.core.MouseEventKind.Down, KeyModifiers.None)
      )
    )
    val _ = pilot.waitForIdle()

  private def wheel(pilot: Pilot, x: Int, y: Int, kind: io.worxbend.tui.core.MouseEventKind): Unit =
    pilot.backend.postEvent(
      io.worxbend.tui.core.Event.Mouse(io.worxbend.tui.core.MouseEvent(Position(x, y), kind, KeyModifiers.None))
    )
    val _ = pilot.waitForIdle()

  test("clicking a button activates it"):
    var pressed = 0
    val pilot   = startApp(column(button("OK") { pressed += 1 }, text("below")))
    pilot.click(5, 0).waitForIdle()
    assert(pressed == 1)
    quitApp(pilot)

  test("a right click over a button does not activate it and stays available to a handler"):
    // a context menu is only possible if the built-in click behaviour declines every button but the left one
    var pressed     = 0
    var rightClicks = 0
    val pilot       = startApp(
      column(button("OK") { pressed += 1 }, text("below")).onMouseEvent { event =>
        if event.button == MouseButton.Right && event.kind == MouseEventKind.Down then
          rightClicks += 1
          true
        else false
      }
    )
    val before      = pilot.screenLines
    pilot.clickWith(5, 0, MouseButton.Right).waitForIdle()
    assert(pressed == 0)
    assert(rightClicks == 1)
    assert(pilot.screenLines == before)
    pilot.clickWith(5, 0, MouseButton.Left).waitForIdle()
    assert(pressed == 1)
    quitApp(pilot)

  test("a middle click over a button does not activate it either"):
    var pressed = 0
    val pilot   = startApp(column(button("OK") { pressed += 1 }, text("below")))
    pilot.clickWith(5, 0, MouseButton.Middle).waitForIdle()
    assert(pressed == 0)
    quitApp(pilot)

  test("clicking a checkbox toggles its signal"):
    val checked = Signal(false)
    val pilot   = startApp(column(checkbox("opt in", checked), text("x")))
    pilot.click(2, 0).waitForIdle()
    assert(checked.peek)
    pilot.click(2, 0).waitForIdle()
    assert(!checked.peek)
    quitApp(pilot)

  test("the scroll wheel scrolls a scrollView under the pointer"):
    val state   = ScrollViewState()
    val content = column((0 until 9).map(n => text(s"row $n").fill)*)
    val pilot   = startApp(scrollView(content, contentHeight = 9, state))
    assert(pilot.screenLines.head.startsWith("row 0"))
    pilot.backend.postEvent(
      io.worxbend.tui.core.Event.Mouse(
        io.worxbend.tui.core
          .MouseEvent(Position(3, 3), io.worxbend.tui.core.MouseEventKind.ScrollDown, KeyModifiers.None)
      )
    )
    pilot.waitForIdle()
    assert(pilot.screenLines.head.startsWith("row 1"))
    quitApp(pilot)

  test("a sideways swipe over a list does not move the selection"):
    // the horizontal wheel is not a row move; a list must decline it so it can bubble to whoever does want it
    val state = ListState(selected = Some(0))
    var kinds = List.empty[MouseEventKind]
    val pilot = startApp(
      list(Seq("alpha", "beta", "gamma"), state).onMouseEvent { event =>
        kinds = kinds :+ event.kind
        false
      }
    )
    pilot.scrollLeft(2, 1).scrollRight(2, 1).waitForIdle()
    assert(state.selected == Some(0))
    assert(kinds == List(MouseEventKind.ScrollLeft, MouseEventKind.ScrollRight))
    // the vertical wheel over the same list still does move it, so nothing about scrolling regressed
    pilot.scrollDown(2, 1).waitForIdle()
    assert(state.selected == Some(1))
    quitApp(pilot)

  test("a sideways swipe reaches an application handler and can change the frame"):
    val offset = Signal(0)
    val pilot  = startApp(
      column(text(s"offset")).onMouseEvent { event =>
        event.kind match
          case MouseEventKind.ScrollRight =>
            offset.update(_ + 1)
            true
          case _                          => false
      }
    )
    pilot.scrollRight(2, 0, times = 3).waitForIdle()
    assert(offset.peek == 3)
    quitApp(pilot)

  test("a button inside a scrolled scrollView activates where it is drawn, not at its content coordinates"):
    val fixture = ScrollFixture()
    val pilot   = startAppOn(Size(40, 12))(fixture.root)
    (0 until 5).foreach(_ => pilot.pressKey(KeyCode.Down)) // the scroll view holds focus, so this scrolls it
    pilot.waitForIdle()
    assert(pilot.screenLines(1).contains("row 5"))
    pilot.click(1, 8).waitForIdle()                        // where button A's content-space rect used to sit
    assert(fixture.aPresses == 0)
    pilot.click(6, 4).waitForIdle()                        // where button A is actually drawn
    assert(fixture.aPresses == 1)
    assert(fixture.topPresses == 0)
    quitApp(pilot)

  test("a scrollView records its focusables' areas in screen coordinates and drops the scrolled-out ones"):
    val fixture = ScrollFixture()
    fixture.state.offset = 5
    val tracker = FocusTracker()
    tracker.reconcile(FocusPass.focusKeys(fixture.root), FocusPass.autofocusRequest(fixture.root))
    val tree    = FocusPass.decorate(fixture.root, tracker, Style.Default)
    val buffer  = Buffer(Rect(0, 0, 40, 12))
    tree.widget.render(buffer.area, buffer)
    assert(tracker.areaOf(0).contains(Rect(4, 1, 36, 11))) // the scroll view's own area is already a screen area
    assert(tracker.areaOf(2).contains(Rect(4, 4, 35, 1)))  // button A, translated onto the screen
    assert(tracker.areaOf(1).isEmpty)                      // button TOP is scrolled above the viewport
    assert(tracker.hitTest(Position(6, 4)).contains(2))    // and beats the enclosing scroll view on paint order
    assert(tracker.hitTest(Position(1, 8)).isEmpty) // the old content-space coordinates hit nothing

  test("a slider inside a scrollView positions from its on-screen area"):
    val fixture = ScrollFixture()
    val pilot   = startAppOn(Size(40, 12))(fixture.root)
    (0 until 5).foreach(_ => pilot.pressKey(KeyCode.Down))
    pilot.waitForIdle()
    // the slider is drawn at Rect(4, 8, 35, 1), so (21 - 4 - 1) / (35 - 3) is exactly half the track
    pilot.click(21, 8).waitForIdle()
    assert(fixture.value.peek == 50)
    quitApp(pilot)

  test("clicking positions a slider knob"):
    val value = Signal(0)
    val pilot = startApp(column(slider(value), text("x")))
    pilot.click(38, 0).waitForIdle() // near the right end of a 40-wide track
    assert(value.peek == 100)
    pilot.click(20, 0).waitForIdle()
    assert(value.peek > 30 && value.peek < 70)
    quitApp(pilot)

  test("an unconsumed mouse event reaches the hit element and its ancestor exactly once each"):
    var onList   = 0
    var onColumn = 0
    val pilot    = startApp(
      column(
        list(Seq("a", "b", "c"), ListState())
          .onMouseEvent { _ =>
            onList += 1
            false
          }
          .length(3),
        text("detail"),
      ).onMouseEvent { _ =>
        onColumn += 1
        false
      }
    )
    press(pilot, 2, 0)
    assert(onList == 1)
    assert(onColumn == 1)
    quitApp(pilot)

  test("a handler on an element the pointer is not over receives nothing"):
    var onDetail = 0
    val pilot    = startApp(
      column(
        list(Seq("a", "b", "c"), ListState()).length(3),
        text("detail").onMouseEvent { _ =>
          onDetail += 1
          false
        },
      )
    )
    press(pilot, 2, 0)
    assert(onDetail == 0)
    quitApp(pilot)

  test("with nothing focusable, only the untracked handler under the pointer receives the event"):
    var onTop    = 0
    var onBottom = 0
    val pilot    = startApp(
      column(
        text("top")
          .onMouseEvent { _ =>
            onTop += 1
            false
          }
          .length(1),
        text("bottom")
          .onMouseEvent { _ =>
            onBottom += 1
            false
          }
          .length(1),
      )
    )
    press(pilot, 2, 0)
    assert(onTop == 1)
    assert(onBottom == 0)
    press(pilot, 2, 1)
    assert(onTop == 1)
    assert(onBottom == 1)
    quitApp(pilot)

  test("a handler on a non-focusable element inside a tracked one still receives the event"):
    var onCanvas = 0
    val pilot    = startApp(
      splitPane(
        panel("Canvas")(text("x")).onMouseEvent { _ =>
          onCanvas += 1
          false
        },
        text("R").fill,
        Signal(50),
      )
    )
    press(pilot, 2, 2)
    assert(onCanvas == 1)
    quitApp(pilot)

  test("the user handler runs before the element's built-in behavior and can consume the event"):
    var seen    = 0
    var pressed = 0
    val pilot   = startApp(
      column(
        button("OK") { pressed += 1 }.onMouseEvent { _ =>
          seen += 1
          true
        },
        text("below"),
      )
    )
    press(pilot, 5, 0)
    assert(seen == 1)
    assert(pressed == 0)
    quitApp(pilot)

  test("a click on a layers stack goes to the topmost layer under the pointer, not the base beneath it"):
    var baseClicks    = 0
    var overlayClicks = 0
    val pilot         = startApp(
      layers(
        panel("Canvas")(text("x").fill).onMouseEvent { _ =>
          baseClicks += 1
          false
        },
        centered(20, 3)(
          panel("Hint")(text("hi")).onMouseEvent { _ =>
            overlayClicks += 1
            true
          }
        ),
      )
    )
    press(pilot, 20, 4) // inside the centered overlay, which also sits over the base layer
    assert(overlayClicks == 1)
    assert(baseClicks == 0)
    quitApp(pilot)

  test("the wheel scrolls the enclosing scrollView when the pointer is over a focusable inside it"):
    val fixture = ScrollFixture()
    val pilot   = startAppOn(Size(40, 12))(fixture.root)
    assert(pilot.screenLines(1).contains("TOP"))
    wheel(pilot, 6, 9, io.worxbend.tui.core.MouseEventKind.ScrollDown) // over button A, drawn at content row 8
    assert(fixture.aPresses == 0)
    assert(fixture.state.offset == 1)
    assert(pilot.screenLines(1).contains("row 1"))
    wheel(pilot, 6, 9, io.worxbend.tui.core.MouseEventKind.ScrollUp)
    assert(fixture.state.offset == 0)
    quitApp(pilot)

  test("dragging moves the splitPane divider"):
    val split = Signal(50)
    val pilot = startApp(splitPane(text("L").fill, text("R").fill, split))
    drag(pilot, 10, 2)
    assert(split.peek == 25) // 10 of 40 columns
    quitApp(pilot)

  /** The wheel is the only gesture that reaches an enclosing control's built-in. A drag must not: the splitPane's
    * built-in reads the pointer against its own whole area, so bubbling one would move the divider from anywhere in
    * either pane — and dragging inside a pane's own control is exactly what a user does to select text.
    */
  test("dragging inside a focusable pane does not move the splitPane divider"):
    val split = Signal(50)
    val state = ListState()
    val pilot = startApp(splitPane(list(Seq("a", "b", "c"), state).fill, text("R").fill, split))
    drag(pilot, 5, 3) // over the list, which is a smaller focusable than the splitPane and so wins the hit test
    assert(split.peek == 50)
    // and the divider still moves for a drag the list does not cover, so the built-in itself is intact
    drag(pilot, 30, 3)
    assert(split.peek == 75)
    quitApp(pilot)

  private def drag(pilot: Pilot, x: Int, y: Int): Unit =
    pilot.backend.postEvent(
      io.worxbend.tui.core.Event.Mouse(
        io.worxbend.tui.core.MouseEvent(Position(x, y), io.worxbend.tui.core.MouseEventKind.Drag, KeyModifiers.None)
      )
    )
    val _ = pilot.waitForIdle()
