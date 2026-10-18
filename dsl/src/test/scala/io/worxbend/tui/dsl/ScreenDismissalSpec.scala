package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** `Screen.dismissal`: closing a modal with `Esc` or with a click in the area around it.
  *
  * The whole point of the feature is that it is wired once by `TuiApp` rather than per dialog, so the tests drive a
  * real app through `Pilot` and read the frame. "The dialog is gone" is asserted as "its text is no longer drawn",
  * which is the same thing a user sees.
  */
final class ScreenDismissalSpec extends AnyFunSuite with PilotFixture:

  /** 40x12, with the dialog placed at a fixed 20x5 in the middle: columns 10-29, rows 4-8 (the seven spare rows split
    * four above and three below). So (1, 1) is provably outside it, (10, 4) is its top-left corner, and (15, 5) is
    * inside its body.
    */
  private val terminalSize = Size(40, 12)

  private final class DialogApp(how: Dismissal, presentation: Presentation = Presentation.Modal) extends TuiApp:
    var buttonPresses: Int = 0

    private def body: View =
      centered(20, 5)(panel("Really?")(button("DELETE")(buttonPresses += 1)))

    private val dialog: Screen =
      if presentation == Presentation.Modal then Screen(body, dismissal = how)
      else Screen.full(body, dismissal = how)

    override def bindings: KeyBindings = KeyBindings(
      binding("o", "open the dialog")(pushScreen(dialog)),
      binding("ctrl+q", "quit")(quit()),
    )

    def view(using ReactiveScope, Theme): Element = text("the page underneath")

  private def start(how: Dismissal, presentation: Presentation = Presentation.Modal): (DialogApp, Pilot) =
    val backend = HeadlessBackend(terminalSize)
    val app     = DialogApp(how, presentation)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    val _       = pilot.pressKey(KeyCode.Char('o')).waitForIdle()
    assert(pilot.screenLines.mkString("\n").contains("DELETE"))
    (app, pilot)

  private def dialogShowing(pilot: Pilot): Boolean =
    pilot.screenLines.mkString("\n").contains("DELETE")

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a click in the area around a ClickOutside dialog closes it"):
    val (_, pilot) = start(Dismissal.ClickOutside)
    pilot.click(1, 1).waitForIdle()
    assert(!dialogShowing(pilot))
    assert(pilot.screenLines.mkString("\n").contains("the page underneath"))
    quitApp(pilot)

  test("a click on the dialog's own border leaves it open; one cell outside the border closes it"):
    // The dialog is a bordered panel at columns 10-29, rows 4-8. Its border carries no control, so before the painted
    // bounds of every node were recorded a press there resolved to nothing inside the dialog and fell through to the
    // backdrop — the frame of the thing being used counted as "outside" it.
    val (_, pilot) = start(Dismissal.ClickOutside)
    assert(pilot.cellAt(10, 4).symbol == "┌", "the test's corner coordinate is not the dialog's corner")
    assert(pilot.cellAt(10, 6).symbol == "│", "the test's border coordinate is not on the border")
    pilot.click(10, 6).waitForIdle() // the left border, halfway down
    assert(dialogShowing(pilot), "a press on the dialog's border closed it")
    pilot.click(10, 4).waitForIdle() // the top-left corner
    assert(dialogShowing(pilot), "a press on the dialog's corner closed it")
    pilot.click(9, 6).waitForIdle()  // the cell immediately left of the border
    assert(!dialogShowing(pilot), "a press one cell outside the dialog left it open")
    quitApp(pilot)

  test("a dialog that consumes presses at its root is not closed by a click on its own frame"):
    // A press on the dialog's border already stays inside it (the test above). Consuming presses at the root is the
    // stronger statement — every press anywhere in the panel's area resolves to the panel and never reaches the
    // backdrop — and this is that line still working.
    final class ShieldedApp extends TuiApp:
      private val dialog: Screen                    = Screen(
        centered(20, 5)(panel("Really?")(text("BODY")).onMouseEvent(_ => true)),
        dismissal = Dismissal.ClickOutside,
      )
      override def bindings: KeyBindings            = KeyBindings(
        binding("o", "open")(pushScreen(dialog)),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("the page underneath")

    val backend = HeadlessBackend(terminalSize)
    val pilot   = startPilot(backend) { ShieldedApp().runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('o')).waitForIdle()
    assert(pilot.screenLines.mkString("\n").contains("BODY"))
    pilot.click(11, 5).waitForIdle() // a cell of the dialog that carries no control of its own
    assert(pilot.screenLines.mkString("\n").contains("BODY"))
    pilot.click(1, 1).waitForIdle()  // genuinely outside it
    assert(!pilot.screenLines.mkString("\n").contains("BODY"))
    quitApp(pilot)

  test("a click on a control inside the dialog presses it and leaves the dialog open"):
    // The press resolves inside the dialog's own subtree, so the backdrop underneath never hears about it. Without
    // that ordering the click would both press the button and close the dialog it was on.
    val (app, pilot) = start(Dismissal.ClickOutside)
    pilot.click(12, 5).waitForIdle()
    assert(app.buttonPresses == 1)
    assert(dialogShowing(pilot))
    quitApp(pilot)

  test("Esc closes an Escape dialog"):
    val (_, pilot) = start(Dismissal.Escape)
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(!dialogShowing(pilot))
    quitApp(pilot)

  test("Esc does not close an Escape dialog when the tree claimed the key first"):
    // `Esc` is handled at the very last stage of key routing, after the tree and after the bindings, so an element
    // that wants `Esc` for something of its own still gets it and the dialog stays open.
    final class EditorApp extends TuiApp:
      var escapes: Int                              = 0
      private val dialog: Screen                    = Screen(
        centered(24, 5)(panel("Rename")(text("BODY")).onKeyEvent {
          case KeyEvent(KeyCode.Escape, _) =>
            escapes += 1
            true
          case _                           => false
        }),
        dismissal = Dismissal.Escape,
      )
      override def bindings: KeyBindings            = KeyBindings(
        binding("o", "open")(pushScreen(dialog)),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("page")

    val backend = HeadlessBackend(terminalSize)
    val app     = EditorApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('o')).waitForIdle()
    assert(pilot.screenLines.mkString("\n").contains("Rename"))
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(app.escapes == 1)
    assert(pilot.screenLines.mkString("\n").contains("Rename"))
    quitApp(pilot)

  test("the default dismissal closes on neither, which is what every screen did before"):
    val (_, pilot) = start(Dismissal.Never)
    pilot.click(1, 1).waitForIdle()
    assert(dialogShowing(pilot))
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(dialogShowing(pilot))
    quitApp(pilot)

  test("EscapeOrClickOutside answers to both"):
    val (_, pilotOne) = start(Dismissal.EscapeOrClickOutside)
    pilotOne.pressKey(KeyCode.Escape).waitForIdle()
    assert(!dialogShowing(pilotOne))
    quitApp(pilotOne)

    val (_, pilotTwo) = start(Dismissal.EscapeOrClickOutside)
    pilotTwo.click(1, 1).waitForIdle()
    assert(!dialogShowing(pilotTwo))
    quitApp(pilotTwo)

  test("a full screen ignores its dismissal, because it has no outside and nothing to fall back to"):
    val (_, pilot) = start(Dismissal.EscapeOrClickOutside, Presentation.Full)
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(dialogShowing(pilot))
    pilot.click(1, 1).waitForIdle()
    assert(dialogShowing(pilot))
    quitApp(pilot)

  test("dismissibleOverlay wraps the content in the node that owns the outside-press behaviour, unwrapped"):
    // The tree is plain data, so the construction can be checked without rendering anything. The behaviour is the
    // node's own built-in rather than a sibling backdrop's, because it has to read where *its* subtree was painted.
    var outside = 0
    val dialog  = text("dialog")
    val overlay = dismissibleOverlay(dialog)(() => outside += 1)
    assert(overlay.children == Seq(dialog)) // the dialog is passed through unwrapped
    assert(overlay.builtinMouseHandler.isDefined)
    assert(outside == 0) // building the overlay must not run the callback

  test("the two dismissal predicates agree with the case names"):
    assert(!Dismissal.Never.byEscape && !Dismissal.Never.byClickOutside)
    assert(Dismissal.Escape.byEscape && !Dismissal.Escape.byClickOutside)
    assert(!Dismissal.ClickOutside.byEscape && Dismissal.ClickOutside.byClickOutside)
    assert(Dismissal.EscapeOrClickOutside.byEscape && Dismissal.EscapeOrClickOutside.byClickOutside)
