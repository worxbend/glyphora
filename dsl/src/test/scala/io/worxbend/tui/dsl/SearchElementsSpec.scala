package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import scala.jdk.CollectionConverters.*

/** `.searchable(state)` on `log`, `scrollView` and `markdown`, and the `searchBar(state)` that edits the query. */
final class SearchElementsSpec extends AnyFunSuite with PilotFixture:

  private given Theme = Theme.Dark

  private def hundredLines: LogState =
    val state = LogState()
    (1 to 100).foreach(n => state.append(if n % 10 == 0 then s"$n warn disk" else s"$n ok"))
    state

  test("searchBar typing updates the SearchState live, Enter steps, Esc clears; the log follows the match"):
    val backend = HeadlessBackend(Size(24, 6))
    val lines   = hundredLines
    val search  = SearchState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("f10", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        column(log(lines).searchable(search).fill, searchBar(search).autofocus)
    val pilot   = startPilot(backend) { app.runWith(backend) }

    pilot.waitForIdle()
    pilot.typeText("wa").waitForIdle()
    assert(pilot.readOnRenderThread(search.query) == "wa")
    pilot.typeText("rn").waitForIdle()
    assert(pilot.readOnRenderThread(search.matches.size) == 10)
    assert(pilot.screenLines(4).startsWith("100 warn disk"))
    pilot.press("enter").waitForIdle()
    assert(pilot.readOnRenderThread(search.currentMatch.map(_.row)).contains(9))
    assert(pilot.screenLines.exists(_.startsWith("10 warn disk")))
    pilot.press("escape").waitForIdle()
    assert(pilot.readOnRenderThread(search.query).isEmpty)
    assert(pilot.readOnRenderThread(search.matches).isEmpty)
    pilot.press("f10")
    assert(pilot.awaitTermination())

  test("n and N on a focused searchable log step through the matches"):
    val backend = HeadlessBackend(Size(24, 5))
    val lines   = hundredLines
    val search  = SearchState()
    search.query = "warn"
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("f10", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = log(lines).searchable(search).autofocus
    val pilot   = startPilot(backend) { app.runWith(backend) }

    pilot.waitForIdle()
    pilot.press("n").waitForIdle()
    assert(pilot.screenLines.exists(_.startsWith("10 warn disk")))
    pilot.press("n").waitForIdle()
    assert(pilot.screenLines.exists(_.startsWith("20 warn disk")))
    pilot.press("N").waitForIdle()
    assert(pilot.readOnRenderThread(search.currentMatch.map(_.row)).contains(9))
    pilot.press("f10")
    assert(pilot.awaitTermination())

  test("a searchable scrollView finds a match below the fold and n scrolls to it"):
    val backend = HeadlessBackend(Size(20, 4))
    val scroll  = ScrollViewState()
    val search  = SearchState()
    search.query = "needle"
    val content = column((0 until 30).map(i => text(if i == 25 then "a needle here" else s"hay $i").length(1))*)
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("f10", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = scrollView(content, scroll).searchable(search).autofocus
    val pilot   = startPilot(backend) { app.runWith(backend) }

    pilot.waitForIdle()
    assert(pilot.screenLines.exists(_.startsWith("a needle here")))
    assert(pilot.readOnRenderThread(search.matches.map(_.row)) == Vector(25))
    pilot.press("home").waitForIdle()
    assert(pilot.screenLines.head.startsWith("hay 0"))
    pilot.press("n").waitForIdle()
    assert(pilot.screenLines.exists(_.startsWith("a needle here")))
    pilot.press("f10")
    assert(pilot.awaitTermination())

  test("a searchable markdown inside a scrollView reveals its current match, a table cell's included"):
    val backend  = HeadlessBackend(Size(30, 4))
    val scroll   = ScrollViewState()
    val search   = SearchState()
    search.query = "Rome"
    val document = (1 to 20).map(i => s"paragraph $i").mkString("\n\n") +
      "\n\n| Name | City |\n|---|---|\n| Ann | Rome |\n"
    val app      = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("f10", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = scrollView(markdown(document).searchable(search), scroll)
    val pilot    = startPilot(backend) { app.runWith(backend) }

    pilot.waitForIdle()
    assert(pilot.readOnRenderThread(search.matches.size) == 1)
    assert(pilot.screenLines.exists(line => line.contains("Ann") && line.contains("Rome")))
    pilot.press("f10")
    assert(pilot.awaitTermination())

  test("a searchable scrollView searches its whole content once per query, then paints only its window"):
    val backend = HeadlessBackend(Size(20, 4))
    val scroll  = ScrollViewState()
    val search  = SearchState()
    search.query = "needle"
    val heights = java.util.concurrent.ConcurrentLinkedQueue[Int]()
    val counted = widget((area, buffer) =>
      heights.add(buffer.area.height)
      buffer.setString(area.x, area.y, "counted", Style.Default)
    )
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("f10", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        val rows = (0 until 40).map(i => text(if i == 30 then "a needle here" else s"hay $i").length(1))
        scrollView(column((rows :+ counted.length(1))*), scroll).searchable(search).autofocus
    val pilot   = startPilot(backend) { app.runWith(backend) }

    pilot.waitForIdle()
    assert(pilot.readOnRenderThread(search.matches.map(_.row)) == Vector(30))
    heights.clear()
    pilot.press("home").waitForIdle()
    pilot.press("down").waitForIdle()
    assert(pilot.screenLines.head.startsWith("hay 1"))
    assert(!heights.isEmpty)
    // the content is 41 rows; a frame that searched it whole would hand every widget a 41-row buffer
    assert(heights.iterator.asScala.forall(_ == 4), heights)
    pilot.press("n").waitForIdle()
    assert(pilot.screenLines.exists(_.startsWith("a needle here")))
    pilot.press("f10")
    assert(pilot.awaitTermination())

  test("a searchable scrollView searches again when a state its content paints changes"):
    val backend = HeadlessBackend(Size(20, 4))
    val scroll  = ScrollViewState()
    val search  = SearchState()
    val field   = TextInputState()
    search.query = "needle"
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("f10", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        scrollView(column(input(field).length(1), text("hay").length(1)), scroll).searchable(search)
    val pilot   = startPilot(backend) { app.runWith(backend) }

    pilot.waitForIdle()
    assert(pilot.readOnRenderThread(search.matches).isEmpty)
    pilot.readOnRenderThread(field.setValue("needle"))
    pilot.waitForIdle()
    assert(pilot.readOnRenderThread(search.matches.map(_.row)) == Vector(0))
    pilot.press("f10")
    assert(pilot.awaitTermination())

  test("searchable and searchBar construct the nodes the docs name"):
    val search = SearchState()
    assert(searchBar(search).debugName == "searchBar")
    assert(searchBar(search).props.focusable)
    assert(log(LogState()).searchable(search).search.contains(search))
    assert(scrollView(text("x"), ScrollViewState()).searchable(search).search.contains(search))
    assert(markdown("x").searchable(search).search.contains(search))

  test("a searchable textArea follows a searchBar's query and F3 steps the caret through the matches"):
    val backend = HeadlessBackend(Size(24, 6))
    val editor  = TextAreaState("one beta\ntwo\nbeta three\nfour beta")
    editor.moveToStart()
    val search  = SearchState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("f10", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        column(textArea(editor).searchable(search).fill, searchBar(search).autofocus)
    val pilot   = startPilot(backend) { app.runWith(backend) }

    pilot.waitForIdle()
    pilot.typeText("beta").waitForIdle()
    assert(pilot.readOnRenderThread(search.matches.map(m => (m.row, m.column))) == Vector((0, 4), (2, 0), (3, 5)))
    assert(pilot.readOnRenderThread(editor.cursor) == (0, 4))
    pilot.press("enter").waitForIdle()
    assert(pilot.readOnRenderThread(editor.cursor) == (2, 0))
    pilot.press("tab").waitForIdle() // focus the editor: F3 there steps the shared search too
    pilot.press("f3").waitForIdle()
    assert(pilot.readOnRenderThread(editor.cursor) == (3, 5))
    pilot.press("shift+f3").waitForIdle()
    assert(pilot.readOnRenderThread(editor.cursor) == (2, 0))
    pilot.press("f10")
    assert(pilot.awaitTermination())
