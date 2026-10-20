package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import org.scalatest.funsuite.AnyFunSuite

final class EditingBoundarySpec extends AnyFunSuite with PilotFixture:
  private val ctrl                                      = KeyModifiers.Ctrl
  private def focused[E <: Element](node: E): node.Self =
    node.withProps(node.props.copy(focusState = node.props.focusState.copy(focused = true)))

  test("default password prompts keep source text and caret length out of frames and exports"):
    val backend = HeadlessBackend(Size(40, 4))
    val answer  = new java.util.concurrent.atomic.AtomicReference[Option[String]](None)
    val pilot   = startPilot(backend) {
      answer.set(Prompt.password("Token")(using PromptSession.on(backend)))
      Right(())
    }.waitForIdle()
    pilot.typeText("fictitious-secret").waitForIdle()
    assert(!pilot.screenText.contains("fictitious-secret"))
    assert(!io.worxbend.tui.core.BufferExport.ansi(pilot.lastFrame).contains("fictitious-secret"))
    assert(!io.worxbend.tui.core.BufferExport.html(pilot.lastFrame).contains("fictitious-secret"))
    pilot.assertNoCursor()
    pilot.pressKey(KeyCode.Enter)
    assert(pilot.awaitTermination())
    assert(answer.get().contains("fictitious-secret"))

  test("CR and CRLF paste preserve multiline boundaries and single-line words"):
    for newline <- Seq("\r", "\r\n", "\n") do
      val inputState = TextInputState()
      val inputNode  = focused(input(inputState))
      assert(inputNode.builtinPasteHandler.get(s"one${newline}two"))
      assert(inputState.value == "one two")
      val areaState  = TextAreaState()
      val areaNode   = focused(textArea(areaState))
      assert(areaNode.builtinPasteHandler.get(s"one${newline}two"))
      assert(areaState.value == "one\ntwo")

  test("paste in an open search edits the query, including in a read-only area"):
    for editable <- Seq(true, false) do
      val state = TextAreaState("alpha beta")
      state.openSearch()
      val node  = focused(TextAreaElement(state, editable = editable))
      assert(node.builtinPasteHandler.get("beta\rignored"))
      assert(state.value == "alpha beta")
      assert(state.search.contains("beta"))

  test("masked and hidden input never copy or kill secret text outside the field"):
    for hidden <- Seq(false, true) do
      val ring    = KillRing()
      val state   = TextInputState("fictitious secret", ring)
      val backend = HeadlessBackend(Size(30, 3))
      val app     = new TuiApp:
        def view(using ReactiveScope, Theme): Element =
          val field = input(state)
          (if hidden then field.hidden else field.masked()).autofocus
      val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
      pilot.pressKey(KeyCode.Char('a'), ctrl).pressKey(KeyCode.Char('c'), ctrl).waitForIdle()
      assert(backend.clipboardContents.isEmpty)
      assert(!io.worxbend.tui.core.BufferExport.ansi(pilot.lastFrame).contains("fictitious secret"))
      assert(!io.worxbend.tui.core.BufferExport.html(pilot.lastFrame).contains("fictitious secret"))
      if hidden then pilot.assertNoCursor()
      pilot.pressKey(KeyCode.Char('x'), ctrl).waitForIdle()
      assert(backend.clipboardContents.isEmpty)
      pilot.pressKey(KeyCode.End).pressKey(KeyCode.Char('w'), ctrl).waitForIdle()
      assert(ring.size == 0)
      assert(state.value.isEmpty)
      pilot.close()

  test("masked word motion treats the whole field as one word"):
    val state = TextInputState("correct horse")
    val node  = focused(input(state).masked())
    val key   = node.builtinKeyHandler.get
    state.moveHome()
    assert(key(KeyEvent(KeyCode.Right, ctrl)))
    assert(state.cursor == 13)
    assert(key(KeyEvent(KeyCode.Char('b'), KeyModifiers.Alt)))
    assert(state.cursor == 0)
    assert(key(KeyEvent(KeyCode.Char('d'), KeyModifiers.Alt)))
    assert(state.value.isEmpty)
    assert(state.kills.size == 0)

  test("autocomplete shares selection, word kills, undo and cursor keys"):
    val state = AutocompleteState()
    state.input.insert("alpha beta")
    val node  = focused(autocomplete(Seq("alpha"), state))
    val key   = node.builtinKeyHandler.get
    assert(node.editsText)
    assert(key(KeyEvent(KeyCode.Char('w'), ctrl)))
    assert(state.input.value == "alpha ")
    assert(key(KeyEvent(KeyCode.Char('z'), ctrl)))
    assert(state.input.value == "alpha beta")
    assert(key(KeyEvent(KeyCode.Home, KeyModifiers.None)))
    assert(key(KeyEvent(KeyCode.Right, KeyModifiers.Shift)))
    assert(state.input.selectedText == "a")
    assert(key(KeyEvent(KeyCode.Delete, KeyModifiers.None)))
    assert(state.input.value == "lpha beta")
