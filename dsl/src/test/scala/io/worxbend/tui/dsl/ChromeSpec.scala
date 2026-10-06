package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

final class ChromeSpec extends AnyFunSuite with PilotFixture:

  test("the default theme is ambient — presets need no explicit given"):
    assert(summon[Theme].name == "dark")

  test("a local given overrides the theme"):
    given Theme = Theme.Light
    assert(summon[Theme].name == "light")

  test("topBar renders title, tabs, and right text over the surface"):
    val bar   = topBar("app", tabs = Seq("one", "two"), right = "v1")
    val lines = trimmedLines(rendered(bar.widget, 30, 1))
    assert(lines.head.startsWith(" app"))
    assert(lines.head.contains("one │ two"))
    assert(lines.head.contains("v1"))

  test("topBar reserves display columns, not code units, for wide text"):
    // each of these characters is one UTF-16 code unit but two terminal columns
    val lines = trimmedLines(rendered(topBar("日本語", tabs = Seq("一", "二"), right = "版本").widget, 40, 1))
    assert(lines.head.startsWith(" 日本語 "))
    assert(lines.head.contains("一 │ 二"))
    assert(lines.head.contains("版本"))

  test("statusBar renders binding hints"):
    val bindings = KeyBindings(binding("q", "quit")(()), binding("tab", "next")(()))
    val lines    = trimmedLines(rendered(statusBar(bindings).widget, 40, 1))
    assert(lines.head == " q quit  │  tab next")

  test("scaffold stacks top bar, sidebar+content, and status bar"):
    val shell = scaffold(
      topBar = Some(topBar("app")),
      sidebar = Some(sidebar(text("side"), width = 6)),
      statusBar = Some(statusBar(Seq("q" -> "quit"))),
    )(text("main content"))
    val lines = trimmedLines(rendered(shell.widget, 30, 5))
    assert(lines.head.startsWith(" app"))
    assert(lines(1).startsWith("side"))
    assert(lines(1).contains("main content"))
    assert(lines.last == " q quit")

  test("a right-hand sidebar renders after the content"):
    val shell = scaffold(sidebar = Some(sidebar(text("side"), width = 6, side = Side.Right)))(text("main"))
    val lines = trimmedLines(rendered(shell.widget, 20, 2))
    assert(lines.head.startsWith("main"))
    assert(lines.head.contains("side"))

  test("helpOverlay lists the hinted bindings in a dialog"):
    val overlay = helpOverlay(KeyBindings(binding("q", "quit the app")(())))
    val lines   = trimmedLines(rendered(overlay.widget, 40, 9))
    assert(lines.exists(_.contains("Help")))
    assert(lines.exists(_.contains("quit the app")))

  test("centered pins content at the requested size in the middle"):
    val layout = centered(4, 1)(text("hi"))
    val buffer = rendered(layout.widget, 10, 5)
    assert(buffer.get(3, 2).symbol == "h")

  test("app-level bindings run for keys no element consumed, and trigger a redraw"):
    val backend = HeadlessBackend(Size(30, 4))
    var saves   = 0
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+s", "save") { saves += 1 },
        binding("q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text(s"saves: $saves")
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.press("ctrl+s").waitForIdle()
    assert(saves == 1)
    assert(pilot.screenLines.head.startsWith("saves: 1"))
    pilot.press("q")
    assert(pilot.awaitTermination())

  test("the surface style actually fills the whole bar row"):
    val bar    = statusBar(Seq("q" -> "quit"))
    val buffer = rendered(bar.widget, 20, 1)
    assert(buffer.get(19, 0).style.bg.nonEmpty)
