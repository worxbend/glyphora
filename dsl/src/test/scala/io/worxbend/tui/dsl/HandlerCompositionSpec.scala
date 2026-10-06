package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, MouseEventKind, Position, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import scala.collection.mutable

import org.scalatest.funsuite.AnyFunSuite

/** `onKeyEvent` and `onMouseEvent` each wrote into a single slot, so a second call silently threw the first handler
  * away, and every mouse handler had to open by matching on the event kind. These tests pin the composition order and
  * the per-kind builders.
  */
final class HandlerCompositionSpec extends AnyFunSuite with PilotFixture:

  private def startApp(size: Size)(body: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(size)
    val testApp = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = body
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  private def mouseEvent(kind: MouseEventKind): MouseEvent =
    MouseEvent(Position(0, 0), kind, KeyModifiers.None)

  test("two key handlers on one element both survive, newest first"):
    val seen    = mutable.ListBuffer.empty[String]
    val element = text("x")
      .onKeyEvent { _ =>
        seen += "first"; false
      }
      .onKeyEvent { _ =>
        seen += "second"; false
      }
    element.props.onKey.foreach(handler => assert(!handler(Key.Enter)))
    assert(seen.toList == List("second", "first"))

  test("a consuming handler stops the chain"):
    val seen    = mutable.ListBuffer.empty[String]
    val element = text("x")
      .onKeyEvent { _ =>
        seen += "first"; false
      }
      .onKeyEvent { _ =>
        seen += "second"; true
      }
    element.props.onKey.foreach(handler => assert(handler(Key.Enter)))
    assert(seen.toList == List("second"))

  test("two mouse handlers on one element both survive too"):
    val seen    = mutable.ListBuffer.empty[String]
    val element = text("x")
      .onMouseEvent { _ =>
        seen += "first"; false
      }
      .onMouseEvent { _ =>
        seen += "second"; false
      }
    element.props.onMouse.foreach(handler => assert(!handler(mouseEvent(MouseEventKind.Down))))
    assert(seen.toList == List("second", "first"))

  test("key handlers and mouse handlers stay independent"):
    val element = text("x").onKeyEvent(_ => true).onMouseEvent(_ => true)
    assert(element.props.onKey.exists(_(Key.Enter)))
    assert(element.props.onMouse.exists(_(mouseEvent(MouseEventKind.Down))))

  test("onClick fires on a press and declines everything else"):
    var clicks  = 0
    val element = text("x").onClickAt(_ => clicks += 1)
    Seq(MouseEventKind.Up, MouseEventKind.Drag, MouseEventKind.ScrollUp, MouseEventKind.Moved).foreach { kind =>
      assert(!element.props.onMouse.exists(_(mouseEvent(kind))), s"$kind should not read as a click")
    }
    assert(clicks == 0)
    assert(element.props.onMouse.exists(_(mouseEvent(MouseEventKind.Down))))
    assert(clicks == 1)

  test("onScroll fires for both wheel directions and sees which one it was"):
    val seen    = mutable.ListBuffer.empty[MouseEventKind]
    val element = text("x").onScroll(up = seen += MouseEventKind.ScrollUp, down = seen += MouseEventKind.ScrollDown)
    element.props.onMouse.foreach { handler =>
      assert(handler(mouseEvent(MouseEventKind.ScrollUp)))
      assert(handler(mouseEvent(MouseEventKind.ScrollDown)))
      assert(!handler(mouseEvent(MouseEventKind.Down)))
    }
    assert(seen.toList == List(MouseEventKind.ScrollUp, MouseEventKind.ScrollDown))

  test("onDrag and onDragEnd split motion from release"):
    val seen    = mutable.ListBuffer.empty[String]
    val element = text("x").onDrag(_ => seen += "drag").onDragEnd(_ => seen += "end")
    element.props.onMouse.foreach { handler =>
      assert(handler(mouseEvent(MouseEventKind.Drag)))
      assert(handler(mouseEvent(MouseEventKind.Up)))
      assert(!handler(mouseEvent(MouseEventKind.Down)))
    }
    assert(seen.toList == List("drag", "end"))

  test("onHover listens for motion with no button held"):
    var hovers  = 0
    val element = text("x").onHover(_ => hovers += 1)
    element.props.onMouse.foreach(handler => assert(handler(mouseEvent(MouseEventKind.Moved))))
    assert(hovers == 1)

  test("the per-kind builders keep the element's own type and compose with each other"):
    val bordered: PanelElement = panel("p")(text("x")).onClickAt(_ => ()).onScroll((), ()).rounded
    assert(bordered.borderType == io.worxbend.tui.widgets.BorderType.Rounded)

  test("a click reaches a plain element through the running app, and a wheel over it does not"):
    var clicks = 0
    val pilot  = startApp(Size(20, 3)) {
      text("click me").onClickAt(_ => clicks += 1)
    }
    pilot.click(2, 0).waitForIdle()
    assert(clicks == 1, "an element carrying only onClick must still be hit-tested")
    pilot.scrollUp(2, 0).waitForIdle()
    assert(clicks == 1)
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a declined kind keeps bubbling to the ancestor that wants it"):
    var childClicks   = 0
    var ancestorWheel = 0
    val pilot         = startApp(Size(20, 3)) {
      panel(text("row").onClickAt(_ => childClicks += 1)).onScroll(up = ancestorWheel += 1, down = ancestorWheel += 1)
    }
    pilot.click(2, 1).waitForIdle()
    pilot.scrollDown(2, 1).waitForIdle()
    assert(childClicks == 1)
    assert(ancestorWheel == 1, "the child declined the wheel, so it should have reached the panel")
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
