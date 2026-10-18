package io.worxbend.tui.dsl

import io.worxbend.tui.core.{MouseButton, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.{GoldenFrames, Pilot}

import org.scalatest.funsuite.AnyFunSuite

/** The application-chrome nodes in `ChromeElements`, driven through a running app. */
final class ChromeElementsPilotSpec extends AnyFunSuite with PilotFixture:

  private def startApp(size: Size)(view0: ReactiveScope ?=> Element): Pilot =
    val backend = HeadlessBackend(size)
    val testApp = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0
    startPilot(backend) { testApp.runWith(backend) }.waitForIdle()

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("breadcrumb: a click on a visible item selects it; the fold and the separators select nothing"):
    var chosen = Vector.empty[Int]
    val trail  = Seq("Home", "Projects", "glyphora", "widgets", "Breadcrumb")
    val pilot  = startApp(Size(30, 3))(breadcrumb(trail, index => chosen :+= index))
    assert(pilot.screenLines.head.trim == "Home › … › Breadcrumb")
    pilot.click(1, 0).click(7, 0).click(12, 0).waitForIdle()
    assert(chosen == Vector(0, 4))
    quitApp(pilot)

  test("accordion: opening one section closes the other — a single-open group of collapsibles"):
    val state = AccordionState()
    val pilot = startApp(Size(30, 8)) {
      accordion(state)("General" -> text("name, email"), "Network" -> text("proxy"), "About" -> text("v1"))
    }
    assert(pilot.screenLines.take(3).map(_.trim) == Seq("▸ General", "▸ Network", "▸ About"))
    pilot.press("enter").waitForIdle()
    assert(state.open.contains(0))
    assert(pilot.screenText.contains("name, email"))
    pilot.press("tab", "enter").waitForIdle()
    assert(state.open.contains(1))
    assert(!pilot.screenText.contains("name, email"))
    assert(pilot.screenText.contains("proxy"))
    pilot.press("enter").waitForIdle()
    assert(state.open.isEmpty)
    quitApp(pilot)

  test("wizard: Next is blocked while the step fails validation, then walks on and Finish runs onFinish"):
    val state    = WizardState()
    var accepted = false
    var finished = 0
    val pilot    = startApp(Size(40, 8)) {
      wizard(state)(
        WizardStep("Account", text("account form"), () => Option.when(!accepted)("email is required")),
        WizardStep("Confirm", text("all good")),
      )(finished += 1)
    }
    assert(pilot.screenLines.head.stripTrailing == "● Account ─ ○ Confirm")
    pilot.press("enter").waitForIdle()
    assert(state.current == 0)
    assert(pilot.screenText.contains("email is required"))
    pilot.readOnRenderThread { accepted = true }
    pilot.press("enter").waitForIdle()
    assert(state.current == 1)
    assert(pilot.screenText.contains("all good") && !pilot.screenText.contains("email is required"))
    assert(pilot.screenText.contains("[ Finish ]"))
    pilot.press("enter").waitForIdle()
    assert(finished == 1)
    quitApp(pilot)

  test("toolbar: one tab stop whose arrows move between its buttons, sliding the strip to keep focus visible"):
    var pressed                      = Vector.empty[String]
    def tool(label: String): Element = button(label)(pressed :+= label).key(label)
    val pilot                        = startApp(Size(20, 3)) {
      column(toolbar(tool("New"), tool("Open"), tool("Save"), tool("Quit")).length(1), button("after")(()).key("after"))
    }
    assert(pilot.screenLines.head == "[ New ] [ Open ]   »")
    pilot.press("right", "right", "right").waitForIdle()
    assert(pilot.screenLines.head.stripTrailing == "« [ Save ] [ Quit ]")
    pilot.press("enter", "left", "enter").waitForIdle()
    assert(pressed == Vector("Quit", "Save"))
    pilot.press("tab", "enter").waitForIdle()
    assert(pressed == Vector("Quit", "Save"))
    // focus left the strip, so it slides back to its start; the arrows belong to the group, not to `after`
    pilot.press("right").waitForIdle()
    assert(pilot.screenLines.head == "[ New ] [ Open ]   »")
    quitApp(pilot)

  test("buttonGroup joins its buttons with dividers"):
    val pilot = startApp(Size(30, 2))(buttonGroup(button("B")(()), button("I")(()), button("U")(())))
    assert(pilot.screenLines.head.stripTrailing == "[ B ]│[ I ]│[ U ]")
    quitApp(pilot)

  test("tagInput: comma and Enter commit chips that wrap onto a second row; Backspace removes the last"):
    val state   = TagInputState(suggestions = Seq("native"))
    var changes = Vector.empty[Seq[String]]
    val pilot   = startApp(Size(20, 4))(column(tagInput(state, tags => changes :+= tags), text("below")))
    pilot.typeText("scala,jvm").press("enter").typeText("nat").press("tab", "enter").typeText("tui,")
    pilot.waitForIdle()
    assert(state.tags == Vector("scala", "jvm", "native", "tui"))
    assert(pilot.screenLines.take(3).map(_.stripTrailing) == Seq(" scala   jvm", " native   tui", "below"))
    pilot.press("backspace").waitForIdle()
    assert(state.tags == Vector("scala", "jvm", "native"))
    assert(changes.last == Seq("scala", "jvm", "native"))
    pilot.typeText("x").press("backspace").waitForIdle() // erases the typed x, not a tag
    assert(state.tags.size == 3)
    quitApp(pilot)

  private val fileEdit = Seq(
    MenuBarMenu(
      "File",
      Seq(MenuEntry.Item("New", Some("^N")), MenuEntry.Item("Open"), MenuEntry.Separator, MenuEntry.Item("Quit")),
    ),
    MenuBarMenu("Edit", Seq(MenuEntry.Item("Undo"), MenuEntry.Item("Redo"))),
  )

  test("menuBar: Alt+F opens File over the content, Right moves to Edit, Enter runs the item"):
    val bar   = MenuBarState()
    var ran   = Vector.empty[(Int, Int)]
    val pilot = startApp(Size(30, 8)) {
      column(
        menuBar(fileEdit, bar)((menu, item) => ran :+= (menu -> item)),
        text("the quick brown fox jumps"),
        text("over the lazy dog, twice"),
        input(TextInputState("typing here")),
      )
    }
    pilot.press("alt+f").waitForIdle()
    assert(bar.open.contains(0))
    GoldenFrames.assertMatches("menu-bar-file-open", pilot.lastFrame)
    pilot.press("right").waitForIdle()
    assert(bar.open.contains(1))
    assert(pilot.screenText.contains("Redo") && !pilot.screenText.contains("Open"))
    pilot.press("down", "enter").waitForIdle()
    assert(ran == Vector(1 -> 1))
    assert(bar.open.isEmpty && !pilot.screenText.contains("Redo"))
    quitApp(pilot)

  test("menuBar: F10 opens the first menu, a click on a title switches menus, Esc and a click away close"):
    val bar   = MenuBarState()
    val pilot = startApp(Size(30, 8))(column(menuBar(fileEdit, bar)((_, _) => ()), text("content")))
    pilot.press("f10").waitForIdle()
    assert(bar.open.contains(0))
    pilot.click(7, 0).waitForIdle()
    assert(bar.open.contains(1))
    pilot.press("escape").waitForIdle()
    assert(bar.open.isEmpty)
    pilot.click(1, 0).waitForIdle()
    assert(bar.open.contains(0))
    pilot.click(25, 6).waitForIdle()
    assert(bar.open.isEmpty)
    quitApp(pilot)

  test("contextMenu: a right click opens the menu at the pointer, flipped near the right and bottom edges"):
    val menuState = ContextMenuState()
    var chosen    = Vector.empty[Int]
    val items     = Seq(MenuEntry.Item("Copy"), MenuEntry.Item("Paste"), MenuEntry.Item("Delete"))
    val pilot     = startApp(Size(30, 8)) {
      contextMenu(items, menuState)(index => chosen :+= index)(column(text("right-click anywhere"), button("ok")(())))
    }
    pilot.clickWith(2, 1, MouseButton.Right).waitForIdle()
    assert(menuState.at.isDefined)
    assert(pilot.screenLines(1).startsWith("  ╭"))
    pilot.press("down", "enter").waitForIdle()
    assert(chosen == Vector(1))
    assert(!menuState.isOpen)
    // near the bottom-right corner the menu opens up and to the left of the pointer, ending on the clicked cell
    pilot.clickWith(28, 7, MouseButton.Right).waitForIdle()
    val lines     = pilot.screenLines
    assert(lines(7).stripTrailing.length == 29 && lines(7).stripTrailing.endsWith("╯") && lines(3).contains("╭"))
    pilot.press("escape").waitForIdle()
    assert(!menuState.isOpen)
    quitApp(pilot)
