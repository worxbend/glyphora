package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.RunnerConfig
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.widgets.TextInputState

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** End-to-end coverage for the 0.4.0 app services: screen stack, toasts, and the command palette. */
final class AppServicesSpec extends AnyFunSuite with PilotFixture:

  private final class NavApp extends TuiApp:
    val baseField                                 = TextInputState()
    val modalField                                = TextInputState()
    override def bindings: KeyBindings            = KeyBindings(
      binding("ctrl+o", "open modal")(openModal()),
      binding("ctrl+q", "quit")(quit()),
    )
    def view(using ReactiveScope, Theme): Element =
      column(text("base screen"), input(baseField))

    private def openModal(): Unit = pushScreen(Screen {
      centered(20, 3) {
        panel("Modal")(input(modalField)).onKeyEvent {
          case KeyEvent(KeyCode.Escape, _) =>
            popScreen()
            true
          case _                           => false
        }
      }
    })

  test("a modal screen renders over the base and traps focus; pop restores"):
    val backend = HeadlessBackend(Size(30, 8))
    val app     = NavApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("a").waitForIdle()
    assert(app.baseField.value == "a")
    pilot.pressKey(KeyCode.Char('o'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("Modal"))
    assert(pilot.screenText.contains("base screen")) // still visible beneath
    pilot.typeText("m").waitForIdle()
    assert(app.modalField.value == "m")
    assert(app.baseField.value == "a")               // base input no longer focused
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(!pilot.screenText.contains("Modal"))
    pilot.typeText("b").waitForIdle()
    assert(app.baseField.value == "ab")
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a full screen replaces the base view and pop restores it"):
    val backend = HeadlessBackend(Size(30, 5))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+f", "forward")(pushScreen(Screen.full(text("second screen")))),
        binding("ctrl+b", "back")(popScreen()),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("base screen")
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenText.contains("base screen"))
    pilot.pressKey(KeyCode.Char('f'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("second screen"))
    assert(!pilot.screenText.contains("base screen"))
    pilot.pressKey(KeyCode.Char('b'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("base screen"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** `replaceScreen` has to be distinguishable from a pop-then-push, which is why the test pops afterwards: if the
    * replace had really been a push, the stack would be two deep and the pop would land on "one" instead of the base.
    */
  test("replaceScreen swaps the top screen in place, leaving the depth alone"):
    val backend = HeadlessBackend(Size(30, 5))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("1", "push one")(pushScreen(Screen.full(text("screen one")))),
        binding("2", "replace with two")(replaceScreen(Screen.full(text("screen two")))),
        binding("b", "back")(popScreen()),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("base screen")
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('1')).waitForIdle()
    assert(pilot.screenText.contains("screen one"))
    pilot.pressKey(KeyCode.Char('2')).waitForIdle()
    assert(pilot.screenText.contains("screen two"))
    assert(!pilot.screenText.contains("screen one"))
    pilot.pressKey(KeyCode.Char('b')).waitForIdle()
    assert(pilot.screenText.contains("base screen"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("replaceScreen on an empty stack behaves as a push"):
    val backend = HeadlessBackend(Size(30, 5))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("r", "replace")(replaceScreen(Screen.full(text("only screen")))),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("base screen")
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('r')).waitForIdle()
    assert(pilot.screenText.contains("only screen"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** Three pushes, one reset. The depth readings are the other half of the assertion: `screenDepth` is a reactive read
    * taken inside `view`, so it also proves that a push repaints anything that consulted it.
    */
  test("resetScreens unwinds the whole stack, and the depth reads report where navigation stands"):
    val backend            = HeadlessBackend(Size(40, 5))
    var depthSeenByHandler = -1
    val app                = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("p", "push") {
          val level = screenDepthNow + 1
          // `currentScreen` is read inside the screen's own view, where a ReactiveScope exists; from here, in a
          // handler, there is none — which is exactly the split the two spellings are for
          pushScreen(Screen.full(text(s"level $level top=${if currentScreen.isDefined then "yes" else "no"}")))
        },
        binding("h", "home")(resetScreens()),
        binding("d", "read depth") { depthSeenByHandler = screenDepthNow },
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text(s"base depth=$screenDepth")
    val pilot              = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenText.contains("base depth=0"))
    pilot.pressKey(KeyCode.Char('p')).waitForIdle()
    pilot.pressKey(KeyCode.Char('p')).waitForIdle()
    pilot.pressKey(KeyCode.Char('p')).waitForIdle()
    assert(pilot.screenText.contains("level 3 top=yes"))
    pilot.pressKey(KeyCode.Char('d')).waitForIdle()
    assert(depthSeenByHandler == 3)
    pilot.pressKey(KeyCode.Char('h')).waitForIdle()
    assert(pilot.screenText.contains("base depth=0"))
    pilot.pressKey(KeyCode.Char('d')).waitForIdle()
    assert(depthSeenByHandler == 0)
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** The lifetime is wall-clock time; ticks are only what notices it has passed. A 400ms toast is a 400ms toast whether
    * the app ticks every 10ms or every 200ms, which is the whole point of spelling it as a duration.
    */
  test("toasts appear on notify and age out once their duration has passed"):
    val backend = HeadlessBackend(Size(40, 6))
    val app     = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(tickRate = Some(10.millis))
      override def bindings: KeyBindings            = KeyBindings(
        binding("n", "notify me")(notify("saved ok", NoticeLevel.Success, duration = 400.millis)),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("content")
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('n')).waitForIdle()
    assert(pilot.screenText.contains("saved ok"))
    pilot.waitUntil("the toast to age out")(!pilot.screenText.contains("saved ok"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("ctrl+p opens the palette, typing filters, enter runs the command"):
    val backend  = HeadlessBackend(Size(60, 16))
    var deployed = false
    val app      = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("d", "deploy to production") { deployed = true },
        binding("r", "restart service")(()),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("content")
    val pilot    = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('p'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("Commands"))
    assert(pilot.screenText.contains("restart service"))
    pilot.typeText("deploy").waitForIdle()
    assert(!pilot.screenText.contains("restart service"))
    assert(pilot.screenText.contains("deploy to production"))
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(deployed)
    assert(!pilot.screenText.contains("Commands"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("copyToClipboard reaches the backend through the runner"):
    val backend = HeadlessBackend(Size(30, 5))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("c", "copy")(copyToClipboard("clipboard payload")),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("content")
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(backend.clipboardContents.isEmpty)
    pilot.pressKey(KeyCode.Char('c')).waitForIdle()
    assert(backend.clipboardContents.contains("clipboard payload"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("escape closes the palette without running anything"):
    val backend = HeadlessBackend(Size(60, 16))
    var fired   = false
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("x", "dangerous action") { fired = true },
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("content")
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('p'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("Commands"))
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(!pilot.screenText.contains("Commands"))
    assert(!fired)
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
