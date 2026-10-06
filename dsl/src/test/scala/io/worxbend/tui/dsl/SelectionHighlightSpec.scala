package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Size, Style}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets as w

import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.Files

/** Every "pick a row" element draws its selected row in the app [[Theme]]'s `focus` style, and — except for the
  * autocomplete dropdown, which only shows a highlight while it is being typed into — does so whether or not it
  * currently holds focus.
  *
  * That second half is what these tests pin down. The focus pass stamps the theme's cue onto every node, not only the
  * focused one, so two lists side by side highlight identically and an app that switches to [[Theme.HighContrast]] — an
  * accessibility theme — gets its selection cue everywhere rather than in whichever element happened to be focused.
  * Each test therefore parks focus on a button and asserts on a collection element that does *not* have it.
  */
final class SelectionHighlightSpec extends AnyFunSuite with PilotFixture:

  private val Width  = 40
  private val Height = 12

  /** `HighContrast`'s cue is `reverse.bold`, which is distinguishable from the widget-level `reverse` default — so a
    * frame that still shows the default is a frame the theme never reached.
    */
  private val Themed: Style = Style.Default.patch(Theme.HighContrast.focus)

  private def startApp(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(Size(Width, Height))
    val testApp = new TuiApp:
      override def theme: Theme                     = Theme.HighContrast
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** Focus parked on a button above the element under test, so the element renders unfocused. */
  private def below(element: Element): Element =
    column(button("go")(()).length(1), element)

  /** The styles present on the screen row that contains `needle` — the highlighted row, addressed by its text so the
    * assertion does not have to know where each widget puts its border, marker or checkbox.
    */
  private def stylesOfRowWith(pilot: Pilot, needle: String): Set[Style] =
    val y = pilot.screenLines.indexWhere(_.contains(needle))
    assert(y >= 0, s"'$needle' is not on screen:\n${pilot.screenText}")
    (0 until Width).map(x => pilot.cellAt(x, y).style).toSet

  private def assertThemed(pilot: Pilot, needle: String): Unit =
    val styles = stylesOfRowWith(pilot, needle)
    assert(styles.contains(Themed), s"the '$needle' row does not carry the theme's focus cue: $styles")
    assert(!styles.contains(Style.Default.reverse), s"the '$needle' row still carries the widget default: $styles")

  test("an unfocused list highlights with the theme's focus style"):
    val state = w.ListState(selected = Some(1))
    val pilot = startApp(below(list(Seq("alpha", "beta"), state)))
    assertThemed(pilot, "beta")
    quitApp(pilot)

  test("an unfocused tree highlights with the theme's focus style"):
    val nodes = Seq(w.TreeNode("root", Seq(w.TreeNode("leaf"))))
    val state = w.TreeState()
    state.selected = Some(Seq(0))
    val pilot = startApp(below(tree(nodes, state)))
    assertThemed(pilot, "root")
    quitApp(pilot)

  test("an unfocused menu highlights with the theme's focus style"):
    val items = Seq(w.MenuEntry.Item("Open"), w.MenuEntry.Item("Save"))
    val state = w.MenuState(selected = Some(0))
    val pilot = startApp(below(menu(items, state)(_ => ())))
    assertThemed(pilot, "Open")
    quitApp(pilot)

  test("an unfocused selection list highlights its cursor row with the theme's focus style"):
    val state = w.ListState(selected = Some(1))
    val pilot = startApp {
      val chosen = Signal(Set(0))
      below(selectionList(Seq("alpha", "beta"), chosen, state))
    }
    assertThemed(pilot, "beta")
    quitApp(pilot)

  test("an unfocused directory tree highlights with the theme's focus style"):
    val root  = Files.createTempDirectory("glyphora-highlight")
    Files.writeString(root.resolve("build.txt"), "")
    root.toFile.deleteOnExit()
    val state = w.DirectoryTreeState(root)
    state.selected = Some(root.resolve("build.txt"))
    val pilot = startApp(below(directoryTree(state)))
    assertThemed(pilot, "build.txt")
    quitApp(pilot)

  test("an unfocused file picker highlights with the theme's focus style"):
    val root  = Files.createTempDirectory("glyphora-highlight-picker")
    Files.writeString(root.resolve("notes.txt"), "")
    root.toFile.deleteOnExit()
    val state = FilePickerState(root)
    state.tree.selected = Some(root.resolve("notes.txt"))
    val pilot = startApp(below(filePicker(state)))
    assertThemed(pilot, "notes.txt")
    quitApp(pilot)

  /** The autocomplete's dropdown is the one element in this family that draws its own rows rather than handing them to
    * a `tui-widgets` list, and it only shows a highlight while focused. It still has to use the app's cue: it used to
    * hard-code `style.reverse`, so under an accessibility theme the suggestion under the cursor was the one row on
    * screen that ignored the theme.
    */
  test("the autocomplete's highlighted suggestion uses the theme's focus style"):
    val state = AutocompleteState()
    state.input.insert("al")
    val pilot = startApp(column(autocomplete(Seq("alpha", "alpine"), state), spacer))
    assertThemed(pilot, "alpha")
    quitApp(pilot)
