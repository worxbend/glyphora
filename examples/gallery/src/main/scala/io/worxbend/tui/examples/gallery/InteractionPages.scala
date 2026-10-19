package io.worxbend.tui.examples.gallery

import io.worxbend.tui.dsl.*

/** Everything the user types into, picks from or navigates with: text entry, choice controls, navigation chrome and the
  * dialog and window family. Each page owns its state, so whatever the user does on a page is still there when they
  * come back to it.
  */
object InteractionPages:

  private def page(factory: String, family: String, summary: String)(view: View): GalleryPage =
    GalleryPage(factory, family, summary, view)

  private val Fruits = Seq("apple", "apricot", "banana", "blueberry", "cherry")

  def textEntry(fixtures: GalleryFixtures): Vector[GalleryPage] =
    val family   = "Text entry"
    val name     = TextInputState("Ada Lovelace")
    val amount   = TextInputState("42")
    val expiry   = TextInputState("1027")
    val notes    = TextAreaState("Dear team,\n\nthe release is on Friday.\n— A.")
    val complete = AutocompleteState()
    complete.input.insert("ap")
    val tags     = TagInputState(Seq("scala", "tui"), suggestions = Seq("terminal", "native"))
    val search   = SearchState()
    val composer = PromptComposerState(Seq("earlier prompt"))
    composer.editor.insert("summarise the diff")
    val files    = FilePickerState(fixtures.directory)
    files.tree.loadVisible()
    Vector(
      page("input", family, "a one-line text field")(column(text("name"), input(name, placeholder = "your name"))),
      page("numberInput", family, "a field that accepts numbers only")(column(text("amount"), numberInput(amount))),
      page("templateInput", family, "a field typed into a fixed mask")(
        column(text("card expiry"), templateInput(expiry, "##/##"))
      ),
      page("textArea", family, "a multi-line editor with selection, undo and wrapping")(textArea(notes)),
      page("autocomplete", family, "a field with fuzzy suggestions")(autocomplete(Fruits, complete)),
      page("tagInput", family, "a field that turns entries into removable tags")(tagInput(tags)),
      page("searchBar", family, "the query field for a searchable view")(searchBar(search)),
      page("promptComposer", family, "a chat-style prompt with history and completions")(
        promptComposer(composer)(_ => ())
      ),
      page("filePicker", family, "a directory tree that answers a chosen file")(filePicker(files)),
    )

  def choice(fixtures: GalleryFixtures): Vector[GalleryPage] =
    val family  = "Choice"
    val clicks  = Signal(0)
    val agreed  = Signal(true)
    val dark    = Signal(false)
    val size    = Signal(1)
    val country = Signal(0)
    val opener  = DropdownState()
    val volume  = Signal(60)
    val range   = RangeSliderState(SliderRange.of(0, 100), 20, 70)
    val colour  = ColorPickerState()
    val date    = DatePickerState(fixtures.today, () => fixtures.today)
    val time    = TimePickerState(java.time.LocalTime.of(9, 30, 0))
    Vector(
      page("button", family, "a focusable, clickable action")(
        column(button("Click me")(clicks.update(_ + 1)).length(1), text(s"clicked ${clicks.get} times"))
      ),
      page("buttonGroup", family, "buttons that share one focus stop")(
        buttonGroup(button("Left")(()), button("Centre")(()), button("Right")(()))
      ),
      page("toolbar", family, "a row of actions")(toolbar(button("New")(()), button("Open")(()), button("Save")(()))),
      page("checkbox", family, "a boolean with a label")(checkbox("I agree to the terms", agreed)),
      page("toggle", family, "an on/off switch")(toggle("Dark mode", dark)),
      page("radioGroup", family, "one choice out of several, all visible")(
        radioGroup(Seq("small", "medium", "large"), size)
      ),
      page("select", family, "one choice cycled in place")(select(Seq("Poland", "Portugal", "Peru"), country)),
      page("dropdown", family, "one choice from a list that opens on demand")(
        dropdown(Seq("Poland", "Portugal", "Peru"), country.get, opener)(country.set)
      ),
      page("slider", family, "a value along a range")(column(slider(volume).length(1), text(s"volume ${volume.get}"))),
      page("rangeSlider", family, "a low and high value along a range")(rangeSlider(range)),
      page("colorPicker", family, "a palette to pick a colour from")(colorPicker(colour)),
      page("datePicker", family, "a calendar to pick a date from")(datePicker(date)),
      page("timePicker", family, "hours, minutes and seconds")(timePicker(time)),
    )

  def navigation(): Vector[GalleryPage] =
    val family    = "Navigation"
    val tab       = Signal(0)
    val pageNo    = Signal(2)
    val menuState = MenuState(selected = Some(0))
    val entries   = Seq(
      MenuEntry.Item("Open", Some("^O")),
      MenuEntry.Item("Save", Some("^S")),
      MenuEntry.Separator,
      MenuEntry.Item("Quit"),
    )
    val bar       = MenuBarState()
    val menus     =
      Seq(MenuBarMenu("File", entries), MenuBarMenu("Edit", Seq(MenuEntry.Item("Copy"), MenuEntry.Item("Paste"))))
    val context   = ContextMenuState()
    val sections  = AccordionState(Some(0))
    val open      = Signal(true)
    val steps     = WizardState()
    Vector(
      page("tabs", family, "a row of titles with one selected")(tabs(Seq("Overview", "Logs", "Settings"), 1)),
      page("tabbedContent", family, "tabs that switch the content below them")(
        tabbedContent("Overview" -> text("all systems normal"), "Logs" -> text("no new entries"))(tab)
      ),
      page("paginator", family, "which page of several is showing")(paginator(pageNo, 5)),
      page("breadcrumb", family, "the path to where the user is")(breadcrumb(Seq("home", "projects", "glyphora"))),
      page("menu", family, "a vertical list of actions with shortcuts")(menu(entries, menuState)(_ => ())),
      page("menuBar", family, "a one-row bar of drop-down menus")(
        column(menuBar(menus, bar)((_, _) => ()), text("content"))
      ),
      page("contextMenu", family, "a right-click menu on any subtree")(
        contextMenu(entries, context)(_ => ())(text("right-click anywhere on this page"))
      ),
      page("accordion", family, "sections where one is open at a time")(
        accordion(sections)("General" -> text("name, language"), "Advanced" -> text("cache, logging"))
      ),
      page("collapsible", family, "one section that opens and closes")(
        collapsible("Details", open)(text("shown while expanded"))
      ),
      page("wizard", family, "an ordered flow of steps with Back and Next")(
        wizard(steps)(
          WizardStep("Account", text("choose a user name")),
          WizardStep("Profile", text("tell us about yourself")),
          WizardStep("Confirm", text("all set")),
        )(())
      ),
    )

  def windows(): Vector[GalleryPage] =
    val family = "Dialogs & windows"
    val choice = Signal(0)
    val notes  = WindowState(2, 1, 26, 6)
    val tools  = WindowState(16, 4, 24, 6)
    val single = WindowState(4, 1, 30, 7)
    Vector(
      page("dialog", family, "a centred box with a message and buttons")(dialog("Saved", "Your changes were written.")),
      page("confirmDialog", family, "a dialog that answers its own keys")(
        confirmDialog("Quit", "Discard unsaved changes?", Seq("Discard", "Keep editing"), choice.get)(
          choice.set,
          _ => (),
          () => (),
        )
      ),
      page("window", family, "a movable, resizable window")(window("Notes", single)(text("drag the title bar"))),
      page("windowStack", family, "overlapping windows with a front-to-back order")(
        windowStack(window("Notes", notes)(text("back window")), window("Tools", tools)(text("front window")))
      ),
    )
