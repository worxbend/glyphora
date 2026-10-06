package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.Signal
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets.TextInputState

import org.scalatest.funsuite.AnyFunSuite

/** `TuiApp.focusTo(key)` / `clearFocus()` / `focusedKey`, and the no-focus state they put the tracker into.
  *
  * Where the keystroke lands is the assertion throughout: each field is a separate `TextInputState`, so typing a
  * character says which element focus was actually on, without reading any framework internals.
  */
final class ImperativeFocusSpec extends AnyFunSuite with PilotFixture:

  /** Three keyed inputs and bindings that move focus between them from application code. */
  private final class FormApp extends TuiApp:
    val name                                       = TextInputState()
    val email                                      = TextInputState()
    val notes                                      = TextInputState()
    var lastMoveAccepted: Boolean                  = false
    var keyUnderFocus: Option[String]              = None
    val showEmail                                  = Signal(true)
    override def bindings: KeyBindings             = KeyBindings(
      binding("ctrl+e", "focus email") { lastMoveAccepted = focusTo("email") },
      binding("ctrl+n", "focus notes") { lastMoveAccepted = focusTo("notes") },
      binding("ctrl+x", "focus a key that is not there") { lastMoveAccepted = focusTo("nonexistent") },
      binding("ctrl+b", "drop focus")(clearFocus()),
      binding("ctrl+r", "read the focused key") { keyUnderFocus = focusedKey },
      binding("ctrl+g", "hide the email field")(showEmail.set(false)),
      binding("ctrl+u", "show the email field again")(showEmail.set(true)),
      binding("ctrl+q", "quit")(quit()),
    )
    // the three services are `protected` on TuiApp, so the "not running" test reaches them through the app itself
    def moveFocusOutsideARun(key: String): Boolean = focusTo(key)
    def dropFocusOutsideARun(): Unit               = clearFocus()
    def readFocusedKeyOutsideARun: Option[String]  = focusedKey

    def view(using ReactiveScope, Theme): Element =
      val fields = Seq(input(name).key("name")) ++
        (if showEmail.get then Seq(input(email).key("email")) else Seq.empty) ++
        Seq(input(notes).key("notes"))
      column(fields*)

  private def start(app: FormApp): Pilot =
    val backend = HeadlessBackend(Size(30, 8))
    startPilot(backend) { app.runWith(backend) }.waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("focusTo moves the cursor to the element carrying that key"):
    val app   = FormApp()
    val pilot = start(app)
    pilot.typeText("a").waitForIdle()
    assert(app.name.value == "a") // the first focusable holds focus to begin with
    pilot.pressKey(KeyCode.Char('e'), KeyModifiers.Ctrl).waitForIdle()
    assert(app.lastMoveAccepted)
    pilot.typeText("b").waitForIdle()
    assert(app.email.value == "b")
    assert(app.name.value == "a")
    quitApp(pilot)

  test("focusTo answers false and changes nothing for a key no rendered focusable carries"):
    val app   = FormApp()
    val pilot = start(app)
    pilot.pressKey(KeyCode.Char('n'), KeyModifiers.Ctrl).waitForIdle()
    pilot.pressKey(KeyCode.Char('x'), KeyModifiers.Ctrl).waitForIdle()
    assert(!app.lastMoveAccepted)
    pilot.typeText("z").waitForIdle()
    assert(app.notes.value == "z") // focus is still where the previous, accepted move left it
    quitApp(pilot)

  /** The same "not there" answer, but for the case that actually happens in an app: the element exists in the code and
    * carries the key, and the view simply did not render that branch this frame.
    */
  test("focusTo declines a key whose element is in a branch the view did not render"):
    val app   = FormApp()
    val pilot = start(app)
    pilot.pressKey(KeyCode.Char('g'), KeyModifiers.Ctrl).waitForIdle() // the email field disappears
    pilot.pressKey(KeyCode.Char('e'), KeyModifiers.Ctrl).waitForIdle()
    assert(!app.lastMoveAccepted)
    quitApp(pilot)

  /** The point of moving focus *by key* rather than by position: the move outlives a re-render that changes the tab
    * order underneath it.
    */
  test("focus follows the keyed element when the tree changes shape afterwards"):
    val app   = FormApp()
    val pilot = start(app)
    pilot.pressKey(KeyCode.Char('n'), KeyModifiers.Ctrl).waitForIdle() // notes: index 2 of 3
    pilot.pressKey(KeyCode.Char('g'), KeyModifiers.Ctrl).waitForIdle() // email goes away; notes is now index 1 of 2
    pilot.typeText("kept").waitForIdle()
    assert(app.notes.value == "kept")
    assert(app.email.value == "")
    quitApp(pilot)

  test("clearFocus leaves nothing focused, so keys reach the app bindings instead of an element"):
    val app   = FormApp()
    val pilot = start(app)
    pilot.pressKey(KeyCode.Char('b'), KeyModifiers.Ctrl).waitForIdle()
    pilot.typeText("ignored").waitForIdle()
    assert(app.name.value == "")
    assert(app.email.value == "")
    assert(app.notes.value == "")
    // and the app's own bindings still work from the no-focus state
    pilot.pressKey(KeyCode.Char('e'), KeyModifiers.Ctrl).waitForIdle()
    pilot.typeText("back").waitForIdle()
    assert(app.email.value == "back")
    quitApp(pilot)

  test("Tab from the no-focus state lands on the first focusable, Shift+Tab on the last"):
    val forward = FormApp()
    val pilot   = start(forward)
    pilot.pressKey(KeyCode.Char('b'), KeyModifiers.Ctrl).waitForIdle()
    pilot.pressKey(KeyCode.Tab).typeText("first").waitForIdle()
    assert(forward.name.value == "first")
    quitApp(pilot)

    val backward  = FormApp()
    val pilotBack = start(backward)
    pilotBack.pressKey(KeyCode.Char('b'), KeyModifiers.Ctrl).waitForIdle()
    pilotBack.pressKey(KeyCode.Tab, KeyModifiers.Shift).typeText("last").waitForIdle()
    assert(backward.notes.value == "last")
    assert(backward.name.value == "")
    quitApp(pilotBack)

  test("focusedKey reports where focus is, and None once it has been cleared"):
    val app   = FormApp()
    val pilot = start(app)
    pilot.pressKey(KeyCode.Char('r'), KeyModifiers.Ctrl).waitForIdle()
    assert(app.keyUnderFocus.contains("name"))
    pilot.pressKey(KeyCode.Char('e'), KeyModifiers.Ctrl).waitForIdle()
    pilot.pressKey(KeyCode.Char('r'), KeyModifiers.Ctrl).waitForIdle()
    assert(app.keyUnderFocus.contains("email"))
    pilot.pressKey(KeyCode.Char('b'), KeyModifiers.Ctrl).waitForIdle()
    pilot.pressKey(KeyCode.Char('r'), KeyModifiers.Ctrl).waitForIdle()
    assert(app.keyUnderFocus.isEmpty)
    quitApp(pilot)

  /** Outside a run there is no tracker to move, so both calls answer for that rather than throwing — an app that
    * mistakenly moves focus from a constructor gets a `false`, not a crash.
    */
  test("focusTo and clearFocus are inert when the app is not running"):
    val app = FormApp()
    assert(!app.moveFocusOutsideARun("email"))
    app.dropFocusOutsideARun()
    assert(app.readFocusedKeyOutsideARun.isEmpty)

  /** The memory a keyed move installs has to survive the element being absent for a frame.
    *
    * A collapsed section or an `if` in the view removes the element from the tree without anybody asking focus to move,
    * and the frame after it comes back is the one where the promise "focus follows that element" is either kept or
    * quietly broken. It used to be broken: the frame without the element re-derived the remembered key from whatever
    * happened to sit at the old index, so the field could never reclaim focus when it reappeared.
    */
  test("a keyed focus survives a frame in which its element is not rendered"):
    val app   = FormApp()
    val pilot = start(app)
    pilot.pressKey(KeyCode.Char('e'), KeyModifiers.Ctrl).waitForIdle() // focus the email field by key
    pilot.pressKey(KeyCode.Char('g'), KeyModifiers.Ctrl).waitForIdle() // it disappears for a frame
    pilot.pressKey(KeyCode.Char('u'), KeyModifiers.Ctrl).waitForIdle() // and comes back
    pilot.pressKey(KeyCode.Char('r'), KeyModifiers.Ctrl).waitForIdle()
    assert(app.keyUnderFocus.contains("email"))
    pilot.typeText("back").waitForIdle()
    assert(app.email.value == "back")
    assert(app.notes.value == "")
    quitApp(pilot)
