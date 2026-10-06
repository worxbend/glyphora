package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.runtime.{ReactiveScope as Scope, RunnerConfig, Viewport}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** What `terminalSize` reports to an app that does not own the whole terminal.
  *
  * An inline app is painted into the bottom few rows of the primary screen, so "the size of the terminal" and "the size
  * the view is laid out in" are two different rectangles. Every frame publishes the second one; a resize used to
  * publish the first, so an `onResize` override — and any view recomputed from what it wrote — briefly saw a height
  * several times the app's own.
  */
final class InlineViewportResizeSpec extends AnyFunSuite with PilotFixture:

  private final class InlineApp extends TuiApp:
    val seenAtResize: java.util.concurrent.atomic.AtomicReference[Option[Size]] =
      java.util.concurrent.atomic.AtomicReference(None)
    override def config: RunnerConfig                                           =
      RunnerConfig(viewport = Viewport.Inline(10))
    override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
    override def onResize(size: Size): Unit       =
      seenAtResize.set(Some(terminalSize(using Scope.untracked)))
    def view(using ReactiveScope, Theme): Element = text("inline view")

  test("a resize publishes the area the app is painted into, not the whole terminal"):
    val backend = HeadlessBackend(Size(80, 30))
    val app     = InlineApp()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.resize(80, 30).waitForIdle()
    assert(app.seenAtResize.get().contains(Size(80, 10)))
    pilot.pressKey(KeyCode.Char('q'))
    assert(pilot.awaitTermination())
