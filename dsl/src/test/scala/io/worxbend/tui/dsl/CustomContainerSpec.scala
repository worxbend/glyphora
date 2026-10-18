package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Rect, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.{BufferAssertions, Pilot}

import org.scalatest.funsuite.AnyFunSuite

/** `container(children*)(place, chrome)`: a layout written by the application, with focus, hit-testing and bounds still
  * the framework's.
  */
final class CustomContainerSpec extends AnyFunSuite with PilotFixture:

  /** Each child one row lower and seven columns further right than the one before, as wide as it measures (seven when
    * it cannot say).
    */
  private val diagonal: (Rect, Seq[ChildInfo]) => Seq[Rect] =
    (area, children) =>
      children.map(child =>
        Rect(area.x + 7 * child.index, area.y + child.index, child.intrinsicWidth(1).getOrElse(7), 1)
      )

  private final class DiagonalApp extends TuiApp:
    var pressed: Vector[String] = Vector.empty

    def view(using ReactiveScope, Theme): Element =
      container(
        button("one")(pressed :+= "one").key("one"),
        button("two")(pressed :+= "two").key("two"),
        button("three")(pressed :+= "three").key("three"),
      )(diagonal)

  private def start(): (DiagonalApp, Pilot) =
    val backend = HeadlessBackend(Size(30, 5))
    val app     = DiagonalApp()
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    (app, pilot)

  private def quit(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("children land where place put them, on a diagonal"):
    val (_, pilot) = start()
    val lines      = pilot.screenLines
    assert(lines(0).indexOf("one") >= 0 && lines(0).indexOf("one") < 7)
    assert(lines(1).indexOf("two") >= 7)
    assert(lines(2).indexOf("three") >= 14)
    quit(pilot)

  test("Tab walks the children in order and Enter activates the focused one"):
    val (app, pilot) = start()
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(app.pressed.size == 2)
    assert(app.pressed.distinct.size == 2)
    quit(pilot)

  test("a click reaches the child painted under it, and boundsOf answers where each child landed"):
    val (app, pilot) = start()
    val two          = app.boundsOf("two")
    assert(two.exists(rect => rect.x == 7 && rect.y == 1))
    pilot.click(two.get).waitForIdle()
    assert(app.pressed == Vector("two"))
    val three        = app.boundsOf("three")
    pilot.click(three.get).waitForIdle()
    assert(app.pressed == Vector("two", "three"))
    quit(pilot)

  test("ChildInfo carries each child's key, constraint and measurements"):
    var seen: Seq[ChildInfo] = Seq.empty
    val node                 = container(text("abc").key("k"), text("漢字").length(3))((area, children) =>
      seen = children
      children.map(_ => area)
    )
    val _                    = BufferAssertions.rendered(node.widget, 10, 3)
    assert(seen.map(_.index) == Seq(0, 1))
    assert(seen.map(_.key) == Seq(Some("k"), None))
    assert(seen(1).constraint.contains(Constraint.Length(3)))
    assert(seen(0).intrinsicWidth(1).contains(3))
    // the CJK label is four display columns; its `.length(3)` is a size along the container's axis, not a measurement
    assert(seen(1).intrinsicWidth(1).contains(4))
    assert(seen(0).intrinsicHeight(10).contains(1))

  test("place may skip a child or hand it a rect outside the area: the first is not painted, the second is clipped"):
    val node  = container(text("AAAA"), text("BBBB"), text("CCCC"))((area, _) =>
      Seq(Rect(area.x, area.y, 4, 1), Rect(area.x + 2, area.y + 1, 10, 1))
    )
    val lines = BufferAssertions.lines(BufferAssertions.rendered(node.widget, 6, 2))
    assert(lines == Seq("AAAA  ", "  BBBB"))

  test("the chrome widget paints under the children, across the whole area"):
    val chrome: Widget = (area, buffer) => buffer.fill(area, Cell(".", Style.Default))
    val node           = container(text("hi"))((area, _) => Seq(Rect(area.x + 1, area.y, 2, 1)), Some(chrome))
    val lines          = BufferAssertions.lines(BufferAssertions.rendered(node.widget, 4, 2))
    assert(lines == Seq(".hi.", "...."))
