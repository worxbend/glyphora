package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Event, Size}
import io.worxbend.tui.runtime.RenderThread
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

/** The bracket around one run: [[TuiApp.onStart]] before the first frame, [[TuiApp.onStop]] on every exit path, and
  * `requestRedraw` for the state in between that the reactive layer cannot see.
  */
final class AppLifecycleSpec extends AnyFunSuite with PilotFixture:

  /** An app that records its own lifecycle and renders a plain `var`.
    *
    * `label` is deliberately not a `Signal`: it stands in for the caller-owned widget state (`ListState`,
    * `TextInputState`, a table's rows) that a background result mutates, which nothing subscribes to and which
    * therefore schedules no redraw of its own.
    *
    * The counters are written on the render thread and read by the test thread only after `awaitTermination`, whose
    * join publishes them; they are `@volatile` so the mid-run assertions read them too.
    */
  private final class LifecycleApp(backend: HeadlessBackend) extends TuiApp:
    @volatile var started               = 0
    @volatile var stopped               = 0
    @volatile var drawsWhenStarted      = -1L
    @volatile var startedOnRenderThread = false
    @volatile var label                 = "before"

    override def onStart(): Unit =
      started += 1
      drawsWhenStarted = backend.drawCount
      startedOnRenderThread = RenderThread.isRenderThread

    override def onStop(): Unit = stopped += 1

    override def bindings: KeyBindings = KeyBindings(binding("q", "quit")(quit()))

    /** The shape of an `Async` continuation: mutate caller-owned state on the render thread, then ask for the frame
      * that mutation earned. Public so the test can post it as queued render-thread work.
      */
    def deliver(value: String): Unit =
      label = value
      requestRedraw()

    def view(using ReactiveScope, Theme): Element = text(label)

  test("onStart runs once, on the render thread, before the first frame"):
    val backend = HeadlessBackend(Size(20, 3))
    val app     = LifecycleApp(backend)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(app.started == 1)
    assert(app.startedOnRenderThread, "onStart must run on the render thread, or Async.every captures the wrong loop")
    assert(app.drawsWhenStarted == 0L, "onStart ran after a frame had already been drawn")
    pilot.pressKey(KeyCode.Char('q'))
    assert(pilot.awaitTermination())
    assert(app.started == 1, "onStart fired more than once for a single run")

  test("onStop runs once when the app quits itself"):
    val backend = HeadlessBackend(Size(20, 3))
    val app     = LifecycleApp(backend)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(app.stopped == 0, "onStop fired while the app was still running")
    pilot.pressKey(KeyCode.Char('q'))
    assert(pilot.awaitTermination())
    assert(app.stopped == 1)

  test("onStop runs on the framework's Ctrl+C path, which no app code reaches"):
    // `Ctrl+C` as a key event: the framework quits the runner directly, so a teardown hung off a binding or off
    // `onInterrupt` would never run
    val backend = HeadlessBackend(Size(20, 3))
    val app     = LifecycleApp(backend)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('c'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
    assert(app.stopped == 1)

  test("onStop runs when an unconsumed interrupt ends the run"):
    // `Ctrl+C` as a signal: the runner quits without the app declining anything
    val backend = HeadlessBackend(Size(20, 3))
    val app     = LifecycleApp(backend)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    backend.postEvent(Event.Interrupt)
    assert(pilot.awaitTermination())
    assert(app.stopped == 1)

  test("requestRedraw repaints state that no signal watches"):
    val backend = HeadlessBackend(Size(20, 3))
    val app     = LifecycleApp(backend)
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenText.startsWith("before"))
    // queued render-thread work, the way `Async.run` delivers a result: no event, no signal, nothing else to notice
    RenderThread.runLater(app.deliver("after"))
    pilot.waitUntil("the mutated label to reach the screen")(pilot.screenText.startsWith("after"))
    pilot.pressKey(KeyCode.Char('q'))
    assert(pilot.awaitTermination())
