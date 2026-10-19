package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.ManualClock

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

final class MultiProgressElementSpec extends AnyFunSuite with PilotFixture:

  test("two tasks render as aligned rows, with the ETA read off a ManualClock"):
    val clock   = ManualClock()
    val fetch   = ProgressTask("fetch sources", Some(100L), () => clock.elapsed)
    val build   = ProgressTask("build", Some(8L), () => clock.elapsed)
    fetch.start()
    build.start()
    clock.advance(12.seconds)
    fetch.advance(30L)
    build.advance(6L)
    val backend = HeadlessBackend(Size(48, 3))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element =
        multiProgress(Seq(fetch, build), ProgressColumn.Default :+ ProgressColumn.Elapsed)
    val pilot   = startPilot(backend)(app.runWith(backend, clock.reading))
    pilot.waitForIdle().assertGolden("multi-progress")
    assert(pilot.screenLines.head.contains("00:28 00:12"))
    pilot.readOnRenderThread(fetch.finish())
    pilot.waitUntil("the finished row to read 100%")(pilot.screenLines.head.contains("100% 00:00 00:12"))
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())
