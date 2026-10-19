package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Color, KeyCode, KeyModifiers, Size}
import io.worxbend.tui.widgets.NoticeLevel
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** Chrome built by top-level definitions, outside any view: the only theme these see is the ambient default. */
private def helperStatusBar: Element = statusBar(Seq("q" -> "quit"))

private def helperTopBar: Element = topBar("App", right = "v1")

private def helperHelp(bindings: KeyBindings): Element = helpOverlay(bindings)

private def helperWhichKey(bindings: KeyBindings): Option[Element] = whichKeyPopup(bindings, Size(30, 8))

private def helperBordered: Element = text("hi").border()

/** Late theme binding for the composite chrome builders and the `.border()` decorator. */
final class ChromeLateBindingSpec extends AnyFunSuite with PilotFixture:

  private val LightSurface = Color.Indexed(253)

  private def driving(appTheme: Theme, size: Size = Size(20, 4))(content: => Element)(body: Pilot => Unit): Unit =
    val app     = new TuiApp:
      override def theme: Theme                     = appTheme
      def view(using ReactiveScope, Theme): Element = content
    val backend = HeadlessBackend(size)
    body(startPilot(backend)(app.runWith(backend)).waitForIdle())

  test("a statusBar built in a top-level def paints Theme.Light's surface inside a Light app"):
    driving(Theme.Light)(column(helperStatusBar, spacer)) { pilot =>
      assert(pilot.cellAt(0, 0).style.bg.contains(LightSurface), pilot.cellAt(0, 0).toString)
      assert(pilot.cellAt(2, 0).style.bg.contains(LightSurface), pilot.cellAt(2, 0).toString)
    }

  test("a topBar built in a top-level def paints Theme.Light's surface"):
    driving(Theme.Light)(column(helperTopBar, spacer)) { pilot =>
      assert(pilot.cellAt(1, 0).symbol == "A")
      assert(pilot.cellAt(1, 0).style.bg.contains(LightSurface), pilot.cellAt(1, 0).toString)
    }

  test("a helpOverlay built in a top-level def draws in Theme.Light's primary style"):
    val bindings = KeyBindings(binding("q", "quit")(()))
    driving(Theme.Light, Size(30, 8))(helperHelp(bindings)) { pilot =>
      val corner = (0 until 30).flatMap(x => (0 until 8).map(y => pilot.cellAt(x, y))).find(_.symbol == "q")
      assert(corner.exists(_.style.fg.contains(Color.Black)), corner.toString)
    }

  test("a whichKeyPopup built in a top-level def paints Theme.Light's surface"):
    final class PopupApp extends TuiApp:
      override def theme: Theme                     = Theme.Light
      override def whichKey: Boolean                = false
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+k ctrl+s", "save all")(()),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element =
        helperWhichKey(activeBindings).fold(text("idle"))(popup => layers(text("idle"), popup))
    val backend = HeadlessBackend(Size(30, 8))
    val pilot = startPilot(backend)(PopupApp().runWith(backend)).waitForIdle()
    pilot.pressKey(KeyCode.Char('k'), KeyModifiers.Ctrl).waitForIdle()
    // the right edge of the row that lists `ctrl+s`: the blank cell just inside it shows the box's surface
    val row   = (0 until 8).find(y => (0 until 30).map(x => pilot.cellAt(x, y).symbol).mkString.contains("save all"))
    val edge  = row.flatMap(y => (29 to 0 by -1).find(x => pilot.cellAt(x, y).symbol == "│").map((_, y)))
    assert(edge.isDefined, pilot.screenText)
    edge.foreach((x, y) => assert(pilot.cellAt(x - 1, y).style.bg.contains(LightSurface), pilot.cellAt(x - 1, y)))
    pilot.pressKey(KeyCode.Escape, KeyModifiers.None).waitForIdle()
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a .border() built in a top-level def draws in Theme.Light's border style"):
    driving(Theme.Light)(helperBordered) { pilot =>
      assert(pilot.cellAt(0, 0).style.fg.contains(Color.Indexed(248)), pilot.cellAt(0, 0).toString)
    }

  test("toasts paint in the app's theme"):
    final class Toasting extends TuiApp:
      override def theme: Theme                     = Theme.Light
      override def bindings: KeyBindings            = KeyBindings(
        binding("n", "notify")(notify("saved", NoticeLevel.Info)),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("idle")
    val backend = HeadlessBackend(Size(30, 4))
    val pilot = startPilot(backend)(Toasting().runWith(backend)).waitForIdle()
    pilot.press("n").waitForIdle()
    val toast = (0 until 30).map(x => pilot.cellAt(x, 1)).find(_.symbol == "s")
    assert(toast.exists(_.style.fg.contains(Color.Blue)), toast.toString)
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
