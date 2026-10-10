package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

final class PickersSpec extends AnyFunSuite with PilotFixture:

  private def startApp(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(Size(50, 10))
    val testApp = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("autocomplete filters by subsequence, highlights, and accepts with enter"):
    val state       = AutocompleteState()
    var accepted    = Option.empty[String]
    val suggestions = Seq("deploy-service", "restart-service", "delete-volume")
    val pilot       = startApp(autocomplete(suggestions, state, choice => accepted = Some(choice)))
    pilot.typeText("de").waitForIdle()
    assert(pilot.screenText.contains("deploy-service"))
    assert(pilot.screenText.contains("delete-volume"))
    assert(
      !pilot.screenText.contains("restart-service")
    ) // 'd','e' is not a subsequence prefix match for it? it is: r-e-s... 'd' missing
    pilot.pressKey(KeyCode.Down).pressKey(KeyCode.Enter).waitForIdle()
    assert(accepted.contains("delete-volume"))
    assert(state.input.value == "delete-volume")
    quitApp(pilot)

  test("autocomplete shows nothing for an empty query"):
    val state = AutocompleteState()
    val pilot = startApp(autocomplete(Seq("alpha", "beta"), state))
    assert(!pilot.screenText.contains("alpha"))
    pilot.typeText("x").waitForIdle()
    assert(!pilot.screenText.contains("alpha")) // no match either
    quitApp(pilot)

  test("filePicker navigates directories and accepts a file"):
    val root  = Files.createTempDirectory("glyphora-picker")
    Files.createDirectories(root.resolve("docs"))
    Files.writeString(root.resolve("docs/readme.md"), "")
    Files.writeString(root.resolve("build.txt"), "")
    root.toFile.deleteOnExit()
    val state = FilePickerState(root)
    state.tree.loadVisible() // explicit initial acquisition, outside view/painting
    val pilot = startApp(filePicker(state))
    assert(pilot.screenText.contains("docs/"))
    assert(pilot.screenText.contains("(nothing selected)"))
    pilot.pressKey(KeyCode.Down).pressKey(KeyCode.Enter).waitForIdle() // expand docs/
    assert(pilot.screenText.contains("readme.md"))
    pilot.pressKey(KeyCode.Down).pressKey(KeyCode.Enter).waitForIdle() // accept readme.md
    assert(state.chosen.peek.exists(_.getFileName.toString == "readme.md"))
    assert(pilot.screenText.contains("readme.md"))
    quitApp(pilot)
