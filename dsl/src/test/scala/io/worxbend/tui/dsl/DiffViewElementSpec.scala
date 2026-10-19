package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import org.scalatest.funsuite.AnyFunSuite

/** `diffView(patch, state)`: a focusable diff pane whose `n`/`]` and `p`/`[` keys step through hunks. */
final class DiffViewElementSpec extends AnyFunSuite with PilotFixture:

  private given Theme = Theme.Dark

  private val patchText =
    """--- a/app.txt
      |+++ b/app.txt
      |@@ -1,2 +1,2 @@
      | one
      |-two
      |+TWO
      |@@ -10,2 +10,2 @@
      | ten
      |-eleven
      |+ELEVEN
      |""".stripMargin

  private val patch = Patch.parse(patchText).toOption.get

  test("diffView renders unified by default, measures its rows, and is focusable"):
    val element = diffView(patch, DiffViewState())
    assert(
      trimmedLines(rendered(element.widget, 30, 9)) == Seq(
        "app.txt",
        "@@ -1,2 +1,2 @@",
        " 1  1  one",
        " 2    -two",
        "    2 +TWO",
        "@@ -10,2 +10,2 @@",
        "10 10  ten",
        "11    -eleven",
        "   11 +ELEVEN",
      )
    )
    assert(element.intrinsicHeight(30).contains(9))
    assert(element.debugName == "diffView")
    assert(element.props.focusable)

  test(".split, .contextLines and .lexer reach the widget's options"):
    val element = diffView(patch, DiffViewState()).split.contextLines(1).lexer(Lexer.Scala)
    assert(element.options.layout == DiffLayout.Split)
    assert(element.options.contextLines.contains(1))
    assert(element.options.lexer.contains(Lexer.Scala))
    assert(element.unified.options.layout == DiffLayout.Unified)

  test("n and ] jump to the next hunk, p and [ back, Down scrolls by a row"):
    val backend = HeadlessBackend(Size(30, 3))
    val state   = DiffViewState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = diffView(patch, state)
    val pilot   = startPilot(backend) { app.runWith(backend) }

    def top: String = pilot.screenLines.head.trim

    pilot.waitForIdle()
    assert(top == "app.txt")
    pilot.press("n").waitForIdle()
    assert(top == "@@ -1,2 +1,2 @@")
    pilot.press("]").waitForIdle()
    assert(top == "@@ -10,2 +10,2 @@")
    pilot.press("p").waitForIdle()
    assert(top == "@@ -1,2 +1,2 @@")
    pilot.press("]").press("[").waitForIdle()
    assert(top == "@@ -1,2 +1,2 @@")
    pilot.pressKey(KeyCode.Down).waitForIdle()
    assert(top == "1  1  one")
    pilot.press("q")
    assert(pilot.awaitTermination())

  private val folding =
    Patch.of((1 to 10).mkString("\n"), (Seq("one") ++ (2 to 9).map(_.toString) ++ Seq("ten")).mkString("\n"), 10)

  test("Enter expands the folded run on screen"):
    val backend = HeadlessBackend(Size(24, 14))
    val state   = DiffViewState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        diffView(folding, state).withOptions(DiffViewOptions(lineNumbers = false, contextLines = Some(1)))
    val pilot   = startPilot(backend) { app.runWith(backend) }

    pilot.waitForIdle()
    assert(pilot.screenLines(4).trim == "… 6 unchanged lines")
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(
      pilot.screenLines.take(13).map(_.trim) == Seq("@@ -1,10 +1,10 @@", "-1", "+one") ++ (2 to 9).map(
        _.toString
      ) ++ Seq("-10", "+ten")
    )
    pilot.press("q")
    assert(pilot.awaitTermination())

  test("a click on a folded row expands it, and Right scrolls the line text"):
    val backend = HeadlessBackend(Size(24, 14))
    val state   = DiffViewState()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        diffView(folding, state).withOptions(DiffViewOptions(lineNumbers = false, contextLines = Some(1)))
    val pilot   = startPilot(backend) { app.runWith(backend) }

    pilot.waitForIdle()
    pilot.click(Rect(2, 4, 1, 1)).waitForIdle()
    assert(pilot.screenLines(4).trim == "3")
    pilot.pressKey(KeyCode.Right).waitForIdle()
    assert(pilot.readOnRenderThread(state.column) == 2) // "one" is the widest text, three columns: clamped at 2
    assert(pilot.screenLines(2).trim == "+e")
    pilot.pressKey(KeyCode.Left).waitForIdle()
    assert(pilot.screenLines(2).trim == "+one")
    pilot.press("q")
    assert(pilot.awaitTermination())
