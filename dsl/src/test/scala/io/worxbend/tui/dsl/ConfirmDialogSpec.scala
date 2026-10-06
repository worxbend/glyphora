package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.BufferAssertions.{rendered, trimmedLines}

import scala.collection.mutable

import org.scalatest.funsuite.AnyFunSuite

/** `dialog` paints a dialog and answers no keys, so every "really quit?" was three pieces of hand-written wiring. These
  * tests pin the controller node's keys and the ready-made screen that owns the selection for you.
  */
final class ConfirmDialogSpec extends AnyFunSuite with PilotFixture:

  private given Theme = Theme.Dark

  private def confirmElement(selected: Int)(record: String => Unit): ConfirmDialogElement =
    confirmDialog("Quit", "Discard changes?", Seq("OK", "Cancel"), selected)(
      index => record(s"select:$index"),
      index => record(s"press:$index"),
      () => record("cancel"),
    )

  test("the dialog paints its title, message and buttons"):
    val painted = trimmedLines(rendered(confirmElement(0)(_ => ()).widget, 30, 8)).mkString("\n")
    assert(painted.contains("Quit"))
    assert(painted.contains("Discard changes?"))
    assert(painted.contains("[ OK ]") && painted.contains("[ Cancel ]"))

  test("Left and Right move the selection and wrap at both ends"):
    val seen    = mutable.ListBuffer.empty[String]
    val element = confirmElement(0)(seen += _)
    element.builtinKeyHandler.foreach { handle =>
      assert(handle(Key.Right))
      assert(handle(Key.Left))
    }
    // From index 0 of two buttons: Right lands on 1, Left wraps back round to 1.
    assert(seen.toList == List("select:1", "select:1"))

  test("Tab moves to the next button, wrapping like Right does"):
    val seen = mutable.ListBuffer.empty[String]
    confirmElement(1)(seen += _).builtinKeyHandler.foreach(handle => assert(handle(Key.Tab)))
    assert(seen.toList == List("select:0"))

  test("Space and Enter press the selected button"):
    val seen = mutable.ListBuffer.empty[String]
    confirmElement(1)(seen += _).builtinKeyHandler.foreach { handle =>
      assert(handle(Key.Enter))
      assert(handle(Key.Space))
    }
    assert(seen.toList == List("press:1", "press:1"))

  test("Escape cancels without pressing anything"):
    val seen = mutable.ListBuffer.empty[String]
    confirmElement(0)(seen += _).builtinKeyHandler.foreach(handle => assert(handle(Key.Escape)))
    assert(seen.toList == List("cancel"))

  test("an unrelated key is declined so it can keep bubbling"):
    val seen = mutable.ListBuffer.empty[String]
    confirmElement(0)(seen += _).builtinKeyHandler.foreach(handle => assert(!handle(Key.Up)))
    assert(seen.isEmpty)

  test("an area too small for the box paints nothing and does not throw"):
    assert(trimmedLines(rendered(confirmElement(0)(_ => ()).widget, 3, 2)).forall(_.isEmpty))

  test("a message needing display-width arithmetic is not clipped mid-character"):
    // Each ideograph is two columns wide; the widget sizes the box from the measured width.
    val wide    = confirmDialog("設定", "本当に終了しますか", Seq("はい", "いいえ"), 0)(_ => (), _ => (), () => ())
    val painted = trimmedLines(rendered(wide.widget, 40, 8)).mkString("\n")
    assert(painted.contains("本当に終了しますか"))

  test("Screen.confirm owns its selection, so an app pushes it and writes no wiring"):
    val backend   = HeadlessBackend(Size(40, 10))
    var confirmed = 0
    var cancelled = 0
    val app       = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+d", "ask")(
          pushScreen(
            Screen.confirm("Quit", "Discard changes?")({ popScreen(); confirmed += 1 }, { popScreen(); cancelled += 1 })
          )
        ),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("main view")
    val pilot     = startPilot(backend) { app.runWith(backend) }.waitForIdle()

    pilot.pressKey(KeyCode.Char('d'), KeyModifiers.Ctrl).waitForIdle()
    assert(pilot.screenText.contains("Discard changes?"))
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(confirmed == 1 && cancelled == 0)
    assert(!pilot.screenText.contains("Discard changes?"), "the screen should have popped")

    pilot.pressKey(KeyCode.Char('d'), KeyModifiers.Ctrl).waitForIdle()
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(confirmed == 1, "Escape must not confirm")
    assert(cancelled == 1)
    assert(!pilot.screenText.contains("Discard changes?"))

    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("the view beneath a confirmation takes no keys of its own"):
    val backend = HeadlessBackend(Size(40, 10))
    var typed   = 0
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("ctrl+d", "ask")(pushScreen(Screen.confirm("Quit", "Sure?")(popScreen(), popScreen()))),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element =
        // Consumes only unmodified letters, so the app's own Ctrl bindings still reach the binding table.
        text("main view").focusable.onKeyEvent { event =>
          event.code match
            case KeyCode.Char(_) if event.modifiers == KeyModifiers.None =>
              typed += 1
              true
            case _                                                       => false
        }
    val pilot   = startPilot(backend) { app.runWith(backend) }.waitForIdle()
    pilot.typeText("a").waitForIdle()
    assert(typed == 1)
    pilot.pressKey(KeyCode.Char('d'), KeyModifiers.Ctrl).waitForIdle()
    pilot.typeText("b").waitForIdle()
    assert(typed == 1, "the layer under a modal is inert")
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
