package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, KeyEvent, KeyModifiers, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions

import org.scalatest.funsuite.AnyFunSuite

/** User keymaps: `Keymap.parse` reads `id = spec` lines, `KeyBindings.rebind` applies them by [[BindingId]], and every
  * consumer of the bindings — dispatch, the help overlay, the Markdown export — sees the user's keys.
  */
final class KeymapSpec extends AnyFunSuite with PilotFixture:

  private val ctrlS = KeyEvent(KeyCode.Char('s'), KeyModifiers.Ctrl)

  private def declared(saves: => Unit): KeyBindings = KeyBindings(
    binding("s", "save")(saves),
    binding("g g", "go to top")(()),
  )

  test("parse reads id = spec lines and skips comments and blank lines"):
    val parsed = Keymap.parse("# my keys\n\nsave = ctrl+s\ngo-to-top = g t\n")
    assert(parsed == Right(Map(BindingId("save") -> keyseq"ctrl+s", BindingId("go-to-top") -> keyseq"g t")))

  test("a # that starts a word ends the line as a comment; \\# is a literal # key"):
    val parsed =
      Keymap.parse("save = ctrl+s   # the usual one\nmark = \\#  # a literal hash\nnext = g \\# # two keys\n")
    assert(
      parsed == Right(
        Map(
          BindingId("save") -> keyseq"ctrl+s",
          BindingId("mark") -> KeySequence.of(KeyEvent.of(KeyCode.Char('#'))),
          BindingId("next") -> KeySequence.of(KeyEvent.of(KeyCode.Char('g')), KeyEvent.of(KeyCode.Char('#'))),
        )
      ),
      parsed,
    )
    assert(
      Keymap.parse("mark = ctrl+#\n") == Right(
        Map(BindingId("mark") -> KeySequence.of(KeyEvent(KeyCode.Char('#'), KeyModifiers.Ctrl)))
      )
    )
    assert(Keymap.parse("mark = # nothing left\n").left.exists(_.line == 1))

  test("parse names the line of a malformed entry"):
    assert(Keymap.parse("save = ctrl+s\nnonsense\n").left.exists(_.line == 2))
    assert(Keymap.parse("save = ctlr+s\n").left.exists(_.toString.startsWith("line 1: ")))
    assert(Keymap.parse("save = a\nsave = b\n") == Left(LineError(2, "'save' is bound twice")))

  test("a binding's default id is its description slugged, and withId overrides it"):
    assert(binding("g g", "Go to top!")(()).id == BindingId("go-to-top"))
    assert(binding("q", "quit")(()).withId("exit").id == BindingId("exit"))

  test("rebind moves the binding to the user's key, and help and markdown show the new spec"):
    var saves    = 0
    val rebound  = declared(saves += 1).rebind(Keymap.parse("save = ctrl+s\n").toOption.get)
    assert(!rebound.handle(KeyEvent.char('s')))
    assert(rebound.handle(ctrlS))
    assert(saves == 1)
    val markdown = Keymap.markdown(rebound)
    assert(markdown.linesIterator.exists(line => line.contains("`ctrl+s`") && line.contains("save")))
    assert(!markdown.contains("`s`"))
    given Theme  = Theme.Dark
    val help     = BufferAssertions.text(BufferAssertions.rendered(helpOverlay(rebound).widget, 40, 8))
    assert(help.contains("ctrl+s"))

  test("a rebind that creates a prefix collision is rejected"):
    assertThrows[IllegalArgumentException](declared(()).rebind(Map(BindingId("save") -> keyseq"g")))

  test("framework keys are ids too: a keymap moves focus traversal and quit"):
    val keys = FrameworkKeys.default.rebind(Keymap.parse("focus-next = ctrl+n\nquit = ctrl+q\n").toOption.get)
    assert(keys.focusNext == keyseq"ctrl+n")
    assert(keys.quit == keyseq"ctrl+q")
    assert(keys.focusPrevious == FrameworkKeys.default.focusPrevious)
    assert(Keymap.markdown(KeyBindings.empty, keys).contains("`ctrl+n`"))

  test("a framework key the app handles may be a sequence; one an element reads while handling a key may not"):
    val keys = FrameworkKeys.default.rebind(Map(FrameworkKeys.Quit -> keyseq"ctrl+x ctrl+c"))
    assert(keys.quit == keyseq"ctrl+x ctrl+c")
    assert(Keymap.markdown(KeyBindings.empty, keys).contains("`ctrl+x ctrl+c`"))
    assertThrows[IllegalArgumentException](FrameworkKeys.default.rebind(Map(FrameworkKeys.MenuBar -> keyseq"g g")))

  test("a running app fires a framework key bound to a sequence, and the prefix alone does nothing"):
    final class App extends TuiApp:
      override def frameworkKeys: FrameworkKeys     =
        FrameworkKeys.default.rebind(Map(FrameworkKeys.Quit -> keyseq"ctrl+x ctrl+c"))
      def view(using ReactiveScope, Theme): Element = text("emacs")
    val backend = HeadlessBackend(Size(20, 4))
    val pilot = startPilot(backend)(App().runWith(backend))
    pilot.waitForIdle()
    pilot.press("ctrl+c", "ctrl+x", "x", "ctrl+c").waitForIdle()
    assert(!pilot.awaitTermination(timeout = scala.concurrent.duration.DurationInt(200).millis))
    pilot.press("ctrl+x", "ctrl+c")
    assert(pilot.awaitTermination())

  test("unbind takes a framework key away"):
    val keys = FrameworkKeys.default.unbind(FrameworkKeys.Inspector)
    assert(keys.inspector == KeySequence.unbound)
    assert(!keys.fires(keys.inspector, KeyEvent.of(KeyCode.F(12))))

  test("a running app honours rebound framework keys and its page step"):
    final class App extends TuiApp:
      val rows                                      = ListState()
      override def frameworkKeys: FrameworkKeys     =
        FrameworkKeys.default.rebind(Map(FrameworkKeys.Quit -> keyseq"ctrl+q")).copy(pageStep = 3)
      def view(using ReactiveScope, Theme): Element = list((1 to 20).map(_.toString), rows)
    val backend = HeadlessBackend(Size(20, 10))
    val app   = App()
    val pilot = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.press("tab", "home", "pagedown").waitForIdle()
    assert(pilot.readOnRenderThread(app.rows.selected) == Some(3))
    pilot.press("ctrl+c").waitForIdle()
    assert(!pilot.awaitTermination(timeout = scala.concurrent.duration.DurationInt(200).millis))
    pilot.press("ctrl+q")
    assert(pilot.awaitTermination())
