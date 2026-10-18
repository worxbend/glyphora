package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Modifiers, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.{ScrollViewState, TextAreaState, TextInputState}

import org.scalatest.funsuite.AnyFunSuite

/** The terminal's own caret follows the focused text node — the thing a screen reader announces and an input method
  * anchors to, which the painted reverse-video cell is invisible to.
  *
  * Everything here is observed through `Pilot.cursorPosition`, which reads the hardware caret the backend was handed
  * after the frame, not the styled cell in the frame. The painted cell is pinned separately where the caret style
  * decides whether it shows.
  */
final class HardwareCaretSpec extends AnyFunSuite with PilotFixture:

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  private def run(app: TuiApp, size: Size = Size(24, 6)): Pilot =
    val backend = HeadlessBackend(size)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot

  private final class FieldAndButton extends TuiApp:
    val name: TextInputState                      = TextInputState()
    def view(using ReactiveScope, Theme): Element =
      column(input(name), button("GO")(()))

  test("a focused input parks the caret after the text it holds, counted in display columns"):
    val pilot = run(FieldAndButton())
    pilot.typeText("héllo").waitForIdle()
    // "héllo" is five clusters and five columns wide, so the caret sits on column 5 of the input's row
    pilot.assertCursorAt(5, 0)
    quitApp(pilot)

  test("the caret is hidden while a button has focus, and shown again on Tab back"):
    val pilot = run(FieldAndButton())
    pilot.typeText("ab").waitForIdle()
    pilot.assertCursorAt(2, 0)
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    pilot.assertNoCursor()
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    pilot.assertCursorAt(2, 0)
    quitApp(pilot)

  test("a caret inside a scrolled scrollView maps through the scroll offset onto the screen"):
    final class Scrolled extends TuiApp:
      val name: TextInputState                      = TextInputState("héllo")
      val scroll: ScrollViewState                   = ScrollViewState()
      def view(using ReactiveScope, Theme): Element =
        column(
          text("HEADER"),
          scrollView(
            column(text("r0"), text("r1"), text("r2"), text("r3"), input(name).autofocus),
            contentHeight = 5,
            scroll,
          ).length(2),
        )

    val app   = Scrolled()
    val pilot = run(app)
    // the viewport shows content rows 3 and 4 once scrolled to the bottom; the input is content row 4
    app.scroll.last()
    pilot.pressKey(KeyCode.Right).waitForIdle() // any consumed key repaints with the new offset
    assert(pilot.screenLines(2).trim == "héllo" || pilot.screenLines(2).startsWith("héllo"))
    pilot.assertCursorAt(5, 2)
    quitApp(pilot)

  test("a caret scrolled out of a scrollView's window is hidden rather than parked on a stale cell"):
    final class Scrolled extends TuiApp:
      val name: TextInputState                      = TextInputState("ab")
      val scroll: ScrollViewState                   = ScrollViewState()
      def view(using ReactiveScope, Theme): Element =
        column(
          scrollView(
            column(input(name).autofocus, text("r1"), text("r2"), text("r3"), text("r4")),
            contentHeight = 5,
            scroll,
          ).length(2),
          text("FOOTER"),
        )

    val app   = Scrolled()
    val pilot = run(app)
    pilot.assertCursorAt(2, 0)
    app.scroll.last()
    pilot.pressKey(KeyCode.Right).waitForIdle()
    pilot.assertNoCursor()
    quitApp(pilot)

  test("a textArea caret on line 3, column 2 maps to that frame cell"):
    final class Editor extends TuiApp:
      val body: TextAreaState                       = TextAreaState("one\ntwo\nthree")
      def view(using ReactiveScope, Theme): Element =
        column(text("TITLE"), textArea(body))

    val app   = Editor()
    app.body.moveHome()
    app.body.moveRight()
    app.body.moveRight()
    val pilot = run(app)
    // the editor starts on row 1 under the title; line 3 is its third row and column 2 its third cell
    pilot.assertCursorAt(2, 3)
    quitApp(pilot)

  test("the command palette's filter box owns the caret while it is open"):
    final class WithBindings extends TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding(key"ctrl+s", "ctrl+s", "save")(()))
      def view(using ReactiveScope, Theme): Element = text("BODY")

    val pilot = run(WithBindings(), Size(60, 12))
    pilot.assertNoCursor()
    pilot.pressKey(KeyCode.Char('p'), KeyModifiers.Ctrl).waitForIdle()
    pilot.typeText("sa").waitForIdle()
    // the palette is 46 wide, centred: its left border is at column 7, the filter box one column in on row 1 below
    // the panel's top border, which sits on row... read it off the frame rather than hard-code the centring arithmetic
    val row   = pilot.screenLines.indexWhere(_.contains("sa"))
    val col   = pilot.screenLines(row).indexOf("sa") + 2
    pilot.assertCursorAt(col, row)
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    pilot.assertNoCursor()
    quitApp(pilot)

  private final class Styled(style: CaretStyle) extends TuiApp:
    val name: TextInputState                      = TextInputState("ab")
    def view(using ReactiveScope, Theme): Element = input(name).caretStyle(style)

  private def paintedCursorAt(pilot: Pilot, x: Int, y: Int): Boolean =
    pilot.cellAt(x, y).style.modifiers.hasAny(Modifiers.Reverse)

  test("by default a focused field shows both carets: the painted cell and the terminal cursor"):
    val pilot = run(Styled(CaretStyle.Both))
    pilot.assertCursorAt(2, 0)
    assert(paintedCursorAt(pilot, 2, 0))
    quitApp(pilot)

  test("caretStyle(Painted) keeps the terminal cursor hidden, as every release before 0.20.0 did"):
    val pilot = run(Styled(CaretStyle.Painted))
    pilot.assertNoCursor()
    assert(paintedCursorAt(pilot, 2, 0))
    quitApp(pilot)

  test("caretStyle(Hardware) moves the terminal cursor and paints no cursor cell"):
    val pilot = run(Styled(CaretStyle.Hardware))
    pilot.assertCursorAt(2, 0)
    assert(!paintedCursorAt(pilot, 2, 0))
    quitApp(pilot)
