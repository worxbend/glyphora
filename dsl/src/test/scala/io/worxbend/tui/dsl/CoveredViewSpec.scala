package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.atomic.AtomicInteger

/** Full-screen navigation should not rebuild view trees that cannot contribute to the frame. */
final class CoveredViewSpec extends AnyFunSuite with PilotFixture:

  test("a modal above a full screen does not rebuild lower pages or lower modals"):
    val baseBuilds  = AtomicInteger(0)
    val pageBuilds  = AtomicInteger(0)
    val lowerBuilds = AtomicInteger(0)
    val app         = new TuiApp:
      private val data                              = Signal("old")
      private val page                              = Screen.full {
        val _ = pageBuilds.incrementAndGet()
        text(s"page=${data.get}")
      }
      private val lower                             = Screen {
        val _ = lowerBuilds.incrementAndGet()
        positioned(0, 1, 10, 1)(text("lower"))
      }
      private val cover                             = Screen.full(text("cover"))
      private val upper                             = Screen(positioned(0, 2, 10, 1)(text("upper")))
      override def bindings: KeyBindings            = KeyBindings(
        binding("p", "page")(pushScreen(page)),
        binding("m", "lower modal")(pushScreen(lower)),
        binding("c", "cover")(pushScreen(cover)),
        binding("n", "upper modal")(pushScreen(upper)),
        binding("u", "update covered data")(data.set("new")),
        binding("b", "back")(popScreen()),
      )
      def view(using ReactiveScope, Theme): Element =
        val _ = baseBuilds.incrementAndGet()
        text("base")
    val backend     = HeadlessBackend(Size(20, 3))
    val pilot       = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val baseBefore  = baseBuilds.get()
    pilot.press("p").waitForIdle()
    pilot.press("m").waitForIdle()
    val pageBefore  = pageBuilds.get()
    val lowerBefore = lowerBuilds.get()
    pilot.press("c").waitForIdle()
    pilot.press("n").waitForIdle()
    pilot.press("u").waitForIdle()
    assert(pilot.screenLines.map(_.trim) == Seq("cover", "", "upper"))
    assert(baseBuilds.get() == baseBefore)
    assert(pageBuilds.get() == pageBefore)
    assert(lowerBuilds.get() == lowerBefore)
    pilot.press("b").waitForIdle()
    pilot.press("b").waitForIdle()
    assert(pilot.screenLines.map(_.trim) == Seq("page=new", "lower", ""))
    assert(pageBuilds.get() > pageBefore)
    assert(lowerBuilds.get() > lowerBefore)
    assert(baseBuilds.get() == baseBefore)
    pilot.interrupt()
    assert(pilot.awaitTermination())

  test("a full screen skips the base and earlier screen builders on redraw"):
    val baseBuilds = AtomicInteger(0)
    val pageBuilds = AtomicInteger(0)
    val app        = new TuiApp:
      private val page                              = Screen.full {
        val _ = pageBuilds.incrementAndGet()
        text("page")
      }
      private val cover                             = Screen.full(text("cover"))
      override def bindings: KeyBindings            = KeyBindings(
        binding("p", "page")(pushScreen(page)),
        binding("c", "cover")(pushScreen(cover)),
        binding("r", "redraw")(requestRedraw()),
        binding("b", "back")(popScreen()),
      )
      def view(using ReactiveScope, Theme): Element =
        val _ = baseBuilds.incrementAndGet()
        text("base")
    val backend    = HeadlessBackend(Size(20, 3))
    val pilot      = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val before     = baseBuilds.get()
    pilot.press("p").waitForIdle()
    assert(pilot.screenLines.head.trim == "page")
    assert(baseBuilds.get() == before)
    val pageBefore = pageBuilds.get()
    pilot.press("c").waitForIdle()
    pilot.press("r").waitForIdle()
    assert(pilot.screenLines.head.trim == "cover")
    assert(baseBuilds.get() == before)
    assert(pageBuilds.get() == pageBefore)
    pilot.press("b").waitForIdle()
    assert(pilot.screenLines.head.trim == "page")
    assert(pageBuilds.get() > pageBefore)
    pilot.interrupt()
    assert(pilot.awaitTermination())

  test("eager hook state survives full-screen coverage without rebuilding its owner"):
    val app     = new TuiApp:
      private val cover                             = Screen.full(text("cover"))
      override def bindings: KeyBindings            = KeyBindings(
        binding("c", "cover")(pushScreen(cover)),
        binding("b", "back")(popScreen()),
      )
      def view(using ReactiveScope, Theme): Element =
        val count = useSignal(0)
        text(s"count=${count.get}").onKeyEvent {
          case KeyEvent(KeyCode.Char('+'), _) =>
            count.update(_ + 1)
            true
          case _                              => false
        }
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.press("+").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=1")
    pilot.press("c").waitForIdle()
    pilot.press("b").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=1")
    pilot.interrupt()
    assert(pilot.awaitTermination())

  test("responsive hook state in a discarded full-screen subtree still starts fresh"):
    val app     = new TuiApp:
      private val cover                             = Screen.full(text("cover"))
      override def bindings: KeyBindings            = KeyBindings(
        binding("c", "cover")(pushScreen(cover)),
        binding("b", "back")(popScreen()),
      )
      def view(using ReactiveScope, Theme): Element = responsive { _ =>
        val count = useSignal(0)
        text(s"count=${count.get}").onKeyEvent {
          case KeyEvent(KeyCode.Char('+'), _) =>
            count.update(_ + 1)
            true
          case _                              => false
        }
      }
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.press("+").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=1")
    pilot.press("c").waitForIdle()
    pilot.press("b").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=0")
    pilot.interrupt()
    assert(pilot.awaitTermination())

  test("a modal still evaluates and paints the page below it"):
    val baseBuilds = AtomicInteger(0)
    val app        = new TuiApp:
      private val modal                             = Screen(positioned(0, 1, 10, 1)(text("modal")))
      override def bindings: KeyBindings            = KeyBindings(binding("m", "modal")(pushScreen(modal)))
      def view(using ReactiveScope, Theme): Element =
        val _ = baseBuilds.incrementAndGet()
        text("base")
    val backend    = HeadlessBackend(Size(20, 3))
    val pilot      = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    val before     = baseBuilds.get()
    pilot.press("m").waitForIdle()
    assert(baseBuilds.get() > before)
    assert(pilot.screenLines.head.trim == "base")
    assert(pilot.screenLines(1).trim == "modal")
    pilot.interrupt()
    assert(pilot.awaitTermination())

  test("replacing a restored navigation entry does not retain the outgoing entry's hooks"):
    val app     = new TuiApp:
      private val page                              = Screen.full {
        val count = useSignal(0)
        text(s"count=${count.get}").onKeyEvent {
          case KeyEvent(KeyCode.Char('+'), _) =>
            count.update(_ + 1)
            true
          case _                              => false
        }
      }
      private val cover                             = Screen.full(text("cover"))
      override def bindings: KeyBindings            = KeyBindings(
        binding("p", "page")(pushScreen(page)),
        binding("c", "cover")(pushScreen(cover)),
        binding("r", "replace")(replaceScreen(page)),
        binding("b", "back")(popScreen()),
      )
      def view(using ReactiveScope, Theme): Element = text("base")
    val backend = HeadlessBackend(Size(20, 3))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.press("p").waitForIdle()
    pilot.press("+").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=1")
    pilot.press("c").waitForIdle()
    pilot.press("b").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=1")
    pilot.press("r").waitForIdle()
    assert(pilot.screenLines.head.trim == "count=0")
    pilot.interrupt()
    assert(pilot.awaitTermination())
