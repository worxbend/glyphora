package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.TextInputState

import org.scalatest.funsuite.AnyFunSuite

/** What focus does when a layer — a pushed screen, or the command palette — covers the view and then goes away.
  *
  * Everything here is asserted by typing a character and seeing which field received it, because that is the thing a
  * user actually experiences. The interesting case is a *deep* base focus over a *shallow* layer: the old behaviour
  * clamped the base index into the layer's much shorter range, so a dialog opened while the fifth control was focused
  * started on the dialog's last field.
  */
final class LayerFocusSpec extends AnyFunSuite with PilotFixture:

  private final class DialogApp extends TuiApp:
    val one                                       = TextInputState()
    val two                                       = TextInputState()
    val three                                     = TextInputState()
    val dialogFirst                               = TextInputState()
    val dialogSecond                              = TextInputState()
    val otherFirst                                = TextInputState()
    override def bindings: KeyBindings            = KeyBindings(
      binding("ctrl+o", "open the dialog")(pushScreen(Screen(dialog))),
      binding("ctrl+w", "swap the dialog for another")(replaceScreen(Screen(otherDialog))),
      binding("ctrl+z", "close the dialog")(popScreen()),
      binding("ctrl+q", "quit")(quit()),
    )
    def view(using ReactiveScope, Theme): Element =
      column(input(one), input(two), input(three))

    private def dialog: View      = panel("Dialog")(input(dialogFirst), input(dialogSecond))
    private def otherDialog: View = panel("Other")(input(otherFirst))

  private def start(app: DialogApp): Pilot =
    val backend = HeadlessBackend(Size(30, 12))
    startPilot(backend) { app.runWith(backend) }.waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a pushed modal starts on its first control, whatever was focused underneath"):
    val app   = DialogApp()
    val pilot = start(app)
    pilot.pressKey(KeyCode.Tab).pressKey(KeyCode.Tab).waitForIdle() // base focus on the third field
    pilot.typeText("base").waitForIdle()
    assert(app.three.value == "base")
    pilot.pressKey(KeyCode.Char('o'), KeyModifiers.Ctrl).waitForIdle()
    pilot.typeText("d").waitForIdle()
    assert(app.dialogFirst.value == "d") // not dialogSecond, which is where a clamped index would have landed
    assert(app.dialogSecond.value == "")
    quitApp(pilot)

  test("popping the modal puts focus back where the modal found it"):
    val app   = DialogApp()
    val pilot = start(app)
    pilot.pressKey(KeyCode.Tab).pressKey(KeyCode.Tab).waitForIdle()
    pilot.pressKey(KeyCode.Char('o'), KeyModifiers.Ctrl).waitForIdle()
    pilot.pressKey(KeyCode.Tab).typeText("second").waitForIdle() // move around inside the dialog
    assert(app.dialogSecond.value == "second")
    pilot.pressKey(KeyCode.Char('z'), KeyModifiers.Ctrl).waitForIdle()
    pilot.typeText("!").waitForIdle()
    assert(app.three.value == "!")                               // back on the base field, not on the base's first one
    assert(app.one.value == "")
    quitApp(pilot)

  /** `replaceScreen` swaps a layer without changing how many there are, so the count comparison alone would not notice.
    * The screen on top is compared as well, which is what this pins.
    */
  test("replacing the top screen starts the incoming one on its first control"):
    val app   = DialogApp()
    val pilot = start(app)
    pilot.pressKey(KeyCode.Char('o'), KeyModifiers.Ctrl).waitForIdle()
    pilot.pressKey(KeyCode.Tab).waitForIdle() // focus the dialog's *second* field
    pilot.pressKey(KeyCode.Char('w'), KeyModifiers.Ctrl).waitForIdle()
    pilot.typeText("x").waitForIdle()
    assert(app.otherFirst.value == "x")
    quitApp(pilot)

  /** The palette is a layer too, and it closes itself from inside its own key handler rather than through `TuiApp`.
    * Deriving the transitions from a count rather than from announcements at the call sites is what keeps that case
    * balanced — the assertion is that focus comes back to the base field the palette covered.
    */
  test("opening and closing the command palette leaves base focus where it was"):
    val app   = DialogApp()
    val pilot = start(app)
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    pilot.typeText("mid").waitForIdle()
    assert(app.two.value == "mid")
    pilot.pressKey(KeyCode.Char('p'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("Commands"))
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(!pilot.screenText.contains("Commands"))
    pilot.typeText("!").waitForIdle()
    assert(app.two.value == "mid!")
    quitApp(pilot)

  /** Two layers deep, unwound one at a time: each pop has to restore the focus its own push covered, not the outermost
    * one, which is why the saved frames are a stack rather than a single slot.
    */
  test("nested layers restore focus one level at a time"):
    val app   = DialogApp()
    val pilot = start(app)
    pilot.pressKey(KeyCode.Tab).pressKey(KeyCode.Tab).waitForIdle() // base: third field
    pilot.pressKey(KeyCode.Char('o'), KeyModifiers.Ctrl).waitForIdle()
    pilot.pressKey(KeyCode.Tab).waitForIdle()                       // dialog: second field
    pilot.pressKey(KeyCode.Char('p'), KeyModifiers.Ctrl).waitForIdle()
    pilot.pressKey(KeyCode.Escape).waitForIdle()                    // palette closes
    pilot.typeText("still").waitForIdle()
    assert(app.dialogSecond.value == "still") // back inside the dialog, where the palette found it
    pilot.pressKey(KeyCode.Char('z'), KeyModifiers.Ctrl).waitForIdle()
    pilot.typeText("home").waitForIdle()
    assert(app.three.value == "home")
    quitApp(pilot)
