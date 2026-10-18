package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.widgets.{Lexer, TextAreaState}

import org.scalatest.funsuite.AnyFunSuite

/** Incremental search and the widget options on the `textArea` node. */
final class TextAreaSearchSpec extends AnyFunSuite with PilotFixture:

  private def appWith(element: => Element): TuiApp = new TuiApp:
    override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
    def view(using ReactiveScope, Theme): Element = column(element)

  test("in a read-only textArea, /foo then n moves the caret to the second match"):
    val backend = HeadlessBackend(Size(30, 6))
    val state   = TextAreaState("foo bar\nfoo baz\nqux")
    val pilot   = startPilot(backend) { appWith(textArea(state).readOnly).runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("/foo").waitForIdle()
    assert(pilot.screenLines.exists(_.startsWith("/foo")))
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(state.cursor == (0, 0))
    pilot.typeText("n").waitForIdle()
    assert(state.cursor == (1, 0))
    assert(state.value == "foo bar\nfoo baz\nqux") // nothing typed into a read-only area
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("Ctrl+F searches an editable textArea, where / still types, and Esc cancels"):
    val backend = HeadlessBackend(Size(30, 6))
    val state   = TextAreaState("a/b foo")
    val pilot   = startPilot(backend) { appWith(textArea(state)).runWith(backend) }
    pilot.waitForIdle()
    pilot.typeText("/").waitForIdle()
    assert(state.value == "a/b foo/")
    pilot.pressKey(KeyCode.Char('f'), KeyModifiers.Ctrl).typeText("foo").waitForIdle()
    assert(state.cursor == (0, 4))
    pilot.pressKey(KeyCode.Escape).waitForIdle()
    assert(state.cursor == (0, 8))
    assert(state.search.isEmpty)
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("the lexer, wrap and lineNumbers builders reach the widget"):
    val state   = TextAreaState("select 1")
    val element = textArea(state).lexer(Lexer.Sql).wrap.lineNumbers
    assert(element.options.lexer.contains(Lexer.Sql))
    assert(element.options.wrap && element.options.lineNumbers)
