package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

/** `Screen.bindings`: keys that exist only while a screen is on top of the stack.
  *
  * The contract has four halves and each gets a test. A screen's key fires while it is pushed and not afterwards; a
  * screen key shadows an app key of the same spec instead of both running; the app's other keys keep working
  * underneath; and every consumer of the key list — dispatch, the status-bar hints, the command palette — reads the
  * same merged list, so what the chrome advertises is what pressing it does.
  */
final class ScreenBindingsSpec extends AnyFunSuite with PilotFixture:

  private final class NavApp extends TuiApp:

    /** How many times each action ran, so shadowing is visible as "one of these two moved". */
    var appSaves: Int    = 0
    var screenSaves: Int = 0
    var appHelps: Int    = 0

    /** A modal screen declaring two keys: one that collides with an app key (`s`) and one the app does not have (`x`).
      */
    private val editor: Screen = Screen(
      text("editor"),
      keys = KeyBindings(
        binding("s", "save the draft")(screenSaves += 1),
        binding("x", "close the editor")(popScreen()),
      ),
    )

    override def bindings: KeyBindings = KeyBindings(
      binding("e", "open the editor")(pushScreen(editor)),
      binding("s", "save the document")(appSaves += 1),
      binding(Seq("h", "f1"), "help")(appHelps += 1),
      binding("ctrl+q", "quit")(quit()),
    )

    def view(using ReactiveScope, Theme): Element =
      column(text("base"), statusBar(activeBindings))

  private def start(): (NavApp, Pilot) =
    val backend = HeadlessBackend(Size(110, 4))
    val app     = NavApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    (app, pilot)

  private def press(pilot: Pilot, key: Char): Unit =
    val _ = pilot.pressKey(KeyCode.Char(key)).waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a screen's key fires while it is pushed and stops firing once it is popped"):
    val (app, pilot) = start()
    press(pilot, 'x') // no screen is up, and the app declares no 'x' at all: nothing happens
    press(pilot, 'e')
    assert(app.screenSaves == 0)
    press(pilot, 's')
    assert(app.screenSaves == 1)
    press(pilot, 'x') // the screen's own key pops it
    press(pilot, 's') // and now the same spec reaches the app's binding instead
    assert(app.screenSaves == 1)
    assert(app.appSaves == 1)
    quitApp(pilot)

  test("a screen key shadows an app key with the same spec rather than both running"):
    val (app, pilot) = start()
    press(pilot, 'e')
    press(pilot, 's')
    assert(app.screenSaves == 1)
    assert(app.appSaves == 0) // the app's own 'save the document' did not also run
    quitApp(pilot)

  test("an app key the screen does not declare still fires while the screen is on top"):
    // Merged, not replaced: pushing a screen must not disarm the app's global keys.
    val (app, pilot) = start()
    press(pilot, 'e')
    press(pilot, 'h')
    assert(app.appHelps == 1)
    quitApp(pilot)

  test("the status bar advertises the merged list, so the hints match what the keys do"):
    val (_, pilot) = start()
    val before     = pilot.screenLines.mkString("\n")
    assert(before.contains("save the document"))
    assert(!before.contains("save the draft"))
    press(pilot, 'e')
    val during     = pilot.screenLines.mkString("\n")
    assert(during.contains("save the draft"))     // the screen's key is advertised
    assert(during.contains("close the editor"))
    assert(!during.contains("save the document")) // and the app key it shadows is not
    assert(during.contains("help"))               // while an app key it does not shadow still is
    press(pilot, 'x')
    assert(!pilot.screenLines.mkString("\n").contains("save the draft"))
    quitApp(pilot)

  /** An app with no keys of its own, so the palette can only be opened by a screen's contribution. */
  private final class PaletteApp extends TuiApp:
    private val screen: Screen                    =
      Screen(text("diagram"), keys = KeyBindings(binding("z", "zoom the diagram")(())))
    def view(using ReactiveScope, Theme): Element = text("base").onKeyEvent {
      case KeyEvent(KeyCode.Char('e'), KeyModifiers.None) =>
        pushScreen(screen)
        true
      case _                                              => false
    }

  test("Ctrl+P opens the palette over a screen's bindings even when the app declares none"):
    // The `Ctrl+P` gate asks whether any binding exists, and the palette lists them; both now read the merged list, so
    // a screen's keys are enough to make the palette worth opening.
    val backend = HeadlessBackend(Size(60, 8))
    val app     = PaletteApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('p'), KeyModifiers.Ctrl).waitForIdle()
    assert(!pilot.screenLines.mkString("\n").contains("zoom the diagram")) // nothing pushed: no bindings, no palette
    pilot.pressKey(KeyCode.Char('e')).waitForIdle()
    pilot.pressKey(KeyCode.Char('p'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenLines.mkString("\n").contains("zoom the diagram"))
    pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("an element handler that consumes the key still wins over a screen binding"):
    // Ordering is unchanged by the merge: the tree is asked first, and only an unconsumed key reaches any binding.
    val consumed = new java.util.concurrent.atomic.AtomicInteger(0)
    val fired    = new java.util.concurrent.atomic.AtomicInteger(0)

    final class GuardedApp extends TuiApp:
      private val screen: Screen                    = Screen(
        text("screen").onKeyEvent {
          case KeyEvent(KeyCode.Char('g'), KeyModifiers.None) =>
            consumed.incrementAndGet()
            true
          case _                                              => false
        },
        keys = KeyBindings(binding("g", "screen action")(fired.incrementAndGet())),
      )
      override def bindings: KeyBindings            = KeyBindings(
        binding("e", "push")(pushScreen(screen)),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("base")

    val backend = HeadlessBackend(Size(40, 4))
    val app     = GuardedApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('e')).waitForIdle()
    pilot.pressKey(KeyCode.Char('g')).waitForIdle()
    assert(consumed.get() == 1)
    assert(fired.get() == 0)
    quitApp(pilot)

  test("Screen.full takes screen-scoped keys the same way a modal one does"):
    // The full-screen factory takes the same `keys` parameter; without this it could be left unwired and unnoticed,
    // because every other test here uses the modal form.
    val screen = Screen.full(text("page"), keys = KeyBindings(binding("r", "refresh")(())))
    assert(screen.presentation == Presentation.Full)
    assert(screen.bindings.hints == Seq(("r", "refresh")))

  test("a screen built without keys declares none, so nothing is merged over the app's"):
    assert(Screen(text("plain")).bindings.hints.isEmpty)
    assert(Screen.full(text("plain")).bindings.hints.isEmpty)

  test("an app binding the screen claims only one of two keys for is still advertised and still fires"):
    // Shadowing is per binding, not per key. The app's help answers to both `h` and `f1`; a screen that declares `h`
    // takes that one key away, but `f1` still reaches the app, so the binding must stay in the merged list.
    final class PartialApp extends TuiApp:
      var helps: Int                                = 0
      private val screen: Screen                    =
        Screen(text("screen"), keys = KeyBindings(binding("h", "screen h")(())))
      override def bindings: KeyBindings            = KeyBindings(
        binding("e", "push")(pushScreen(screen)),
        binding(Seq("f1", "h"), "help")(helps += 1),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = column(text("base"), statusBar(activeBindings))

    val backend = HeadlessBackend(Size(110, 4))
    val app     = PartialApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    press(pilot, 'e')
    assert(pilot.screenLines.mkString("\n").contains("help"))
    val _       = pilot.pressKey(KeyCode.F(1)).waitForIdle()
    assert(app.helps == 1)
    press(pilot, 'h') // the screen took this one key, so the app's help does not run again
    assert(app.helps == 1)
    quitApp(pilot)

  /** An app whose command list holds a palette-only entry: a command with no key at all, which the user reaches by name
    * through `Ctrl+P` rather than by pressing something.
    */
  private final class KeylessApp extends TuiApp:
    var exported: Int = 0

    private val plain: Screen = Screen(text("screen"))

    override def bindings: KeyBindings = KeyBindings(
      binding("e", "open a screen")(pushScreen(plain)),
      // built through the case class rather than through `binding`, which requires at least one key spec: this is a
      // command the user reaches by name in the Ctrl+P palette and by nothing else
      KeyBinding(Seq.empty, "Export CSV", "write the table to a file", () => exported += 1),
      binding("ctrl+q", "quit")(quit()),
    )

    def view(using ReactiveScope, Theme): Element = column(text("base"), statusBar(activeBindings))

  test("a command with no key at all survives a screen being pushed"):
    val backend = HeadlessBackend(Size(110, 4))
    val app     = KeylessApp()
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    assert(pilot.screenText.contains("Export CSV"))
    val _       = pilot.pressKey(KeyCode.Char('e')).waitForIdle()
    // "every trigger of this binding is one the screen claimed" is vacuously true of a binding with no triggers, so
    // the keyless command used to be dropped from the merged list for as long as any screen was on the stack —
    // vanishing from the status bar and from the Ctrl+P palette, and coming back on pop
    assert(pilot.screenText.contains("Export CSV"), "a keyless command must not be shadowed by a screen")
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
