package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.GoldenFrames

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable
import scala.concurrent.duration.DurationInt

/** The `promptComposer` node driven through a whole app: history, reverse search, ghost suggestions, async trigger
  * completion and large-paste compaction.
  */
final class PromptComposerSpec extends AnyFunSuite with PilotFixture:

  private val Ctrl = KeyModifiers.Ctrl

  private final class Harness(state: PromptComposerState):
    val submitted: mutable.Buffer[String] = mutable.Buffer.empty
    val backend                           = HeadlessBackend(Size(40, 8))
    private val app                       = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = column(promptComposer(state)(text => submitted += text))
    val pilot                             = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()

    def finish(): Unit =
      pilot.pressKey(KeyCode.Char('q'), Ctrl)
      assert(pilot.awaitTermination())

  test("Enter submits, Shift+Enter and Alt+Enter insert a newline, and a submission joins the history"):
    val state   = PromptComposerState()
    val harness = Harness(state)
    import harness.pilot
    pilot.typeText("one").pressKey(KeyCode.Enter, KeyModifiers.Shift).typeText("two")
    pilot.pressKey(KeyCode.Enter, KeyModifiers.Alt).typeText("three").waitForIdle()
    assert(state.value == "one\ntwo\nthree")
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(harness.submitted.toSeq == Seq("one\ntwo\nthree"))
    assert(state.value == "")
    assert(state.history == Seq("one\ntwo\nthree"))
    harness.finish()

  test("Up and Down at the edges recall history, and Down past the newest restores the draft"):
    val state   = PromptComposerState(initialHistory = Seq("first", "second"))
    val harness = Harness(state)
    import harness.pilot
    pilot.typeText("draft").pressKey(KeyCode.Up).waitForIdle()
    assert(state.value == "second")
    pilot.pressKey(KeyCode.Up).waitForIdle()
    assert(state.value == "first")
    pilot.pressKey(KeyCode.Up).waitForIdle()
    assert(state.value == "first") // the oldest entry stays put
    pilot.pressKey(KeyCode.Down).pressKey(KeyCode.Down).waitForIdle()
    assert(state.value == "draft")
    harness.finish()

  test("Ctrl-R finds \"deploy\" by substring and Enter loads it for editing"):
    val state   = PromptComposerState(initialHistory = Seq("deploy prod", "ls -la", "git status"))
    val harness = Harness(state)
    import harness.pilot
    pilot.pressKey(KeyCode.Char('r'), Ctrl).typeText("dep").waitForIdle()
    assert(pilot.screenLines.exists(_.contains("(reverse-i-search)`dep': deploy prod")))
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(state.value == "deploy prod")
    assert(harness.submitted.isEmpty) // Enter accepted the match, it did not submit
    harness.finish()

  test("a ghost suggestion from history shows dim after the caret and Tab accepts it"):
    val state   = PromptComposerState(initialHistory = Seq("git status", "git stash"))
    val harness = Harness(state)
    import harness.pilot
    pilot.typeText("git stat").waitForIdle()
    assert(pilot.screenLines.head == "git status")
    assert(pilot.lastFrame.get(9, 0).style.modifiers.hasAny(Modifiers.Dim))
    pilot.pressKey(KeyCode.Tab).waitForIdle()
    assert(state.value == "git status")
    harness.finish()

  test("@ opens an async completion popup that filters as you type and Enter accepts"):
    val people  = Seq("alice", "albert", "bob")
    val state   = PromptComposerState(
      // the final query's answer is held back, so the answers to the earlier queries ("", "a") always show first — the
      // order a loaded machine produced by chance, pinned here so the wait below has to tell them apart
      completions = (trigger, query) =>
        if query == "al" then Thread.sleep(200)
        if trigger == '@' then people.filter(_.startsWith(query)) else Nil
      ,
      debounce = 0.millis,
    )
    val harness = Harness(state)
    import harness.pilot
    pilot.typeText("hi @al")
    // waitForIdle covers the event queue, not the completion worker: wait on the answer to the *last* query — "albert"
    // alone is on screen for the answers to "" and "a" as well, and a frame taken then still lists "bob"
    pilot
      .waitUntil("the popup lists the matches for \"al\"")(
        pilot.screenLines.exists(_.contains("albert")) && !pilot.screenLines.exists(_.contains("bob"))
      )
      .waitForIdle()
    GoldenFrames.assertMatches("composer-mention-popup", pilot.lastFrame)
    pilot.pressKey(KeyCode.Down).pressKey(KeyCode.Enter).waitForIdle()
    assert(state.value == "hi @albert ")
    assert(harness.submitted.isEmpty)
    harness.finish()

  test("/ at the start of the prompt triggers command completion; Esc closes the popup"):
    val state   = PromptComposerState(
      completions =
        (trigger, query) => if trigger == '/' then Seq("help", "history").filter(_.startsWith(query)) else Nil,
      debounce = 0.millis,
    )
    val harness = Harness(state)
    import harness.pilot
    pilot.typeText("/h")
    pilot.waitUntil("the popup lists commands")(pilot.screenLines.exists(_.contains("history"))).waitForIdle()
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(!pilot.screenLines.exists(_.contains("history")))
    assert(state.value == "/h")
    harness.finish()

  test("a 50-line paste collapses to a placeholder and is expanded on submit"):
    val state   = PromptComposerState()
    val harness = Harness(state)
    import harness.pilot
    val pasted  = (1 to 50).map(n => s"line $n").mkString("\n")
    pilot.typeText("see: ").paste(pasted).waitForIdle()
    assert(state.value == "see: [Pasted 50 lines]")
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(harness.submitted.toSeq == Seq(s"see: $pasted"))
    harness.finish()

  test("a prompt glyph prefixes the first row, wrapped rows hang under it, and the composer grows up to maxRows"):
    val state   = PromptComposerState(maxRows = 2)
    val backend = HeadlessBackend(Size(12, 5))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        column(promptComposer(state)(_ => ()).prompt("> ").autofocus, text("below"))
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    assert(pilot.screenLines.take(2).map(_.stripTrailing) == Seq(">", "below"))
    // ten columns are left for the text: "aaaa bbbb " fills the first row and the rest wraps onto a second
    pilot.typeText("aaaa bbbb cccc dddd").waitForIdle()
    assert(pilot.screenLines.take(3).map(_.stripTrailing) == Seq("> aaaa bbbb", "  cccc dddd", "below"))
    // a third wrapped row would pass maxRows = 2: the composer stays two rows tall and scrolls inside them
    pilot.typeText(" eeee").waitForIdle()
    assert(pilot.screenLines(2).stripTrailing == "below")
    assert(pilot.screenLines(1).stripTrailing == "  eeee")
    pilot.pressKey(KeyCode.Char('q'), Ctrl)
    assert(pilot.awaitTermination())
