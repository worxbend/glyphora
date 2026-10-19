package io.worxbend.tui.dsl

import io.worxbend.tui.core.{MouseButton, Rect, Size}
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
      accordion(state)(
        AccordionSection("General", text("name, email")),
        AccordionSection("Network", text("proxy")),
        AccordionSection("About", text("v1")),
      )
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

  test("toolbar with a state: » opens the hidden controls in a popup; clicking one presses it and closes the menu"):
    var pressed                      = Vector.empty[String]
    def tool(label: String): Element = button(label)(pressed :+= label).key(label)
    val state                        = ToolbarState()
    val pilot                        = startApp(Size(20, 5)) {
      column(toolbar(state)(tool("New"), tool("Open"), tool("Save"), tool("Quit")).length(1), text("below"))
    }
    assert(pilot.screenLines.head == "[ New ] [ Open ]   »")
    assert(state.hidden == Seq(2, 3))
    pilot.click(19, 0).waitForIdle()
    assert(state.open)
    GoldenFrames.assertMatches("toolbar-overflow-open", pilot.lastFrame)
    pilot.click(15, 3).waitForIdle()    // the popup's second control, under its top border: Quit
    assert(pressed == Vector("Quit"))
    assert(!state.open)
    assert(pilot.screenLines(1).stripTrailing == "below")
    // Quit now has focus, so the strip slid to it and the hidden controls are the ones before it, behind «
    assert(pilot.screenLines.head.stripTrailing == "« [ Save ] [ Quit ]")
    pilot.click(19, 0).waitForIdle()    // no » is drawn, so the empty end of the strip opens nothing
    assert(!state.open)
    pilot.click(0, 0).waitForIdle()
    assert(state.open)
    assert(pilot.screenLines(2).contains("[ New ]"))
    pilot.click(2, 4).waitForIdle()     // a press outside the popup closes it and presses nothing
    assert(!state.open)
    assert(pressed == Vector("Quit"))
    pilot.click(0, 0).waitForIdle()
    assert(state.open)
    pilot.press("escape").waitForIdle() // Esc from the focused control bubbles to the toolbar and closes it
    assert(!state.open)
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

  test("menuBar: F10 opens File over the content, Right moves to Edit, Enter runs the item"):
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
    pilot.press("f10").waitForIdle()
    assert(bar.open.contains(0))
    GoldenFrames.assertMatches("menu-bar-file-open", pilot.lastFrame)
    pilot.press("right").waitForIdle()
    assert(bar.open.contains(1))
    assert(pilot.screenText.contains("Redo") && !pilot.screenText.contains("Open"))
    pilot.press("down", "enter").waitForIdle()
    assert(ran == Vector(1 -> 1))
    assert(bar.open.isEmpty && !pilot.screenText.contains("Redo"))
    quitApp(pilot)

  test("menuBar: with nothing that edits text focused, Alt+F opens File"):
    val bar   = MenuBarState()
    val pilot = startApp(Size(30, 8))(column(menuBar(fileEdit, bar)((_, _) => ()), button("ok")(())))
    pilot.press("alt+f").waitForIdle()
    assert(bar.open.contains(0))
    pilot.press("escape").waitForIdle()
    assert(bar.open.isEmpty)
    quitApp(pilot)

  test("menuBar: a focused text field keeps Alt+B/Alt+F word motion; other mnemonics and F10 still reach the bar"):
    val bar   = MenuBarState()
    val field = TextInputState("one two three")
    val pilot = startApp(Size(30, 8))(column(menuBar(fileEdit, bar)((_, _) => ()), input(field)))
    val end   = field.cursor
    pilot.press("alt+b").waitForIdle()
    assert(bar.open.isEmpty, "Alt+B is the field's word-left, not a mnemonic")
    assert(field.cursor < end, "the field moved back a word")
    pilot.press("alt+f").waitForIdle()
    assert(bar.open.isEmpty, "Alt+F is the field's word-right while the field has focus")
    assert(field.cursor == end, "the field moved forward a word")
    pilot.press("alt+e").waitForIdle()
    assert(bar.open.contains(1), "a mnemonic the field does not use opens its menu")
    pilot.press("escape", "f10").waitForIdle()
    assert(bar.open.contains(0), "F10 opens the bar whatever holds focus")
    pilot.press("e").waitForIdle()
    assert(bar.open.contains(1), "with the bar open, a mnemonic letter moves to its menu")
    pilot.press("alt+f").waitForIdle()
    assert(bar.open.contains(0), "with the bar open, Alt+F is the File mnemonic again")
    pilot.press("escape").waitForIdle()
    assert(bar.open.isEmpty && field.value == "one two three")
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

  private def desk(windows: Element*): ReactiveScope ?=> Element =
    layers(column(text("desktop " * 5), text("base layer " * 3)), windowStack(windows*))

  test("window: dragging the title moves it 5 cells, dragging the grip resizes it"):
    val notes = WindowStack().window(2, 1, 16, 5)
    val pilot = startApp(Size(40, 12))(desk(window("Notes", notes)(text("alpha"))))
    assert(pilot.screenLines(1).drop(2).startsWith("┌ Notes "))
    pilot.drag(5, 1, 10, 1).waitForIdle()
    assert(notes.rect == Rect(7, 1, 16, 5))
    assert(pilot.screenLines(1).drop(7).startsWith("┌ Notes "))
    pilot.drag(22, 5, 25, 7).waitForIdle()
    assert(notes.rect == Rect(7, 1, 19, 7))
    quitApp(pilot)

  test("window: two windows overlap; a click on the one beneath brings it to the front"):
    val back  = WindowStack().window(2, 1, 18, 6)
    val front = WindowStack().window(10, 3, 18, 6)
    val pilot = startApp(Size(40, 12)) {
      desk(window("Back", back)(text("back window")), window("Front", front)(text("front window")))
    }
    assert(pilot.screenLines(3).contains("┌ Front "))
    pilot.click(4, 4).waitForIdle()
    assert(back.z > front.z)
    GoldenFrames.assertMatches("window-z-order", pilot.lastFrame)
    quitApp(pilot)

  test("window: Alt+M then the arrows move it, Alt+R then the arrows resize it, Enter and Esc leave the mode"):
    val panel = WindowStack().window(2, 1, 16, 5)
    val pilot = startApp(Size(40, 12))(desk(window("Tools", panel)(button("ok")(()))))
    pilot.press("alt+m").waitForIdle()
    assert(panel.mode == WindowMode.Moving)
    assert(pilot.screenText.contains("Tools · move"))
    pilot.press("right", "right", "right", "down", "enter").waitForIdle()
    assert(panel.rect == Rect(5, 2, 16, 5) && panel.mode == WindowMode.Normal)
    pilot.press("alt+r", "right", "down", "down", "escape").waitForIdle()
    assert(panel.rect == Rect(5, 2, 17, 7) && panel.mode == WindowMode.Normal)
    quitApp(pilot)
