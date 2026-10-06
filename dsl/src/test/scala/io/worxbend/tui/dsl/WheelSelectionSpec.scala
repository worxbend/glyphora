package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets as w

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}

/** Every collection element with a moving highlight answers the wheel the same way: one notch moves the selection one
  * entry, the same thing Up/Down do from the keyboard.
  *
  * Six of the seven do. `dataTable` is deliberately out — its selection indexes the visible page, so a wheel notch has
  * no single right meaning — and `log`/`scrollView` move an offset rather than a selection, which is a different
  * behavior tested in [[MouseInteractionSpec]]. These tests exist because four of the six were silently missing the
  * handler while binding the identical keyboard vocabulary, so `tree` and `list` rendered side by side and only one of
  * them answered the wheel.
  */
final class WheelSelectionSpec extends AnyFunSuite with PilotFixture:

  private def startApp(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(Size(40, 10))
    val testApp = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** A directory with two files, deleted when the JVM exits. */
  private def tempTree(prefix: String): Path =
    val root = Files.createTempDirectory(prefix)
    Files.writeString(root.resolve("alpha.txt"), "")
    Files.writeString(root.resolve("beta.txt"), "")
    root.toFile.deleteOnExit()
    root

  test("the wheel moves a list's selection"):
    val state = w.ListState(selected = Some(0))
    val pilot = startApp(list(Seq("alpha", "beta", "gamma"), state))
    pilot.scrollDown(2, 1).waitForIdle()
    assert(state.selected.contains(1))
    pilot.scrollUp(2, 1).waitForIdle()
    assert(state.selected.contains(0))
    quitApp(pilot)

  test("the wheel moves a tree's selection"):
    val nodes = Seq(w.TreeNode("first"), w.TreeNode("second"), w.TreeNode("third"))
    val state = w.TreeState()
    state.selected = Some(Seq(0))
    val pilot = startApp(tree(nodes, state))
    pilot.scrollDown(2, 1).waitForIdle()
    assert(state.selected.contains(Seq(1)))
    pilot.scrollUp(2, 1).waitForIdle()
    assert(state.selected.contains(Seq(0)))
    quitApp(pilot)

  test("the wheel moves a selection list's cursor without toggling membership"):
    val state  = w.ListState(selected = Some(0))
    val chosen = Signal(Set.empty[Int])
    val pilot  = startApp(selectionList(Seq("alpha", "beta", "gamma"), chosen, state))
    pilot.scrollDown(2, 1).waitForIdle()
    assert(state.selected.contains(1))
    assert(chosen.peek.isEmpty, "the wheel moves the cursor; Space is what toggles membership")
    quitApp(pilot)

  test("the wheel moves a directory tree's selection"):
    val root  = tempTree("glyphora-wheel-tree")
    val state = w.DirectoryTreeState(root)
    state.selected = Some(root.resolve("alpha.txt"))
    val pilot = startApp(directoryTree(state))
    pilot.scrollDown(2, 1).waitForIdle()
    assert(state.selected.exists(_.getFileName.toString == "beta.txt"))
    pilot.scrollUp(2, 1).waitForIdle()
    assert(state.selected.exists(_.getFileName.toString == "alpha.txt"))
    quitApp(pilot)

  test("the wheel moves a file picker's selection without accepting a file"):
    val root  = tempTree("glyphora-wheel-picker")
    val state = FilePickerState(root)
    state.tree.selected = Some(root.resolve("alpha.txt"))
    val pilot = startApp(filePicker(state))
    pilot.scrollDown(2, 1).waitForIdle()
    assert(state.tree.selected.exists(_.getFileName.toString == "beta.txt"))
    assert(state.chosen.peek.isEmpty, "the wheel moves the selection; Enter is what accepts it")
    quitApp(pilot)
