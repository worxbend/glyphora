package io.worxbend.tui.examples.dashboard

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

final class DashboardAppSpec extends AnyFunSuite:

  test("ticks drive redraws without any input"):
    val backend = HeadlessBackend(Size(60, 16))
    val app     = DashboardApp()
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      val ticksBefore = app.tick.peek
      val drawsBefore = backend.drawCount
      // poll instead of a fixed sleep: under parallel test load the tick thread may be starved for a while
      pilot.waitUntil("ticks to drive a redraw", 10.seconds)(
        app.tick.peek > ticksBefore && backend.drawCount > drawsBefore
      )
      assert(app.tick.peek > ticksBefore)
      assert(backend.drawCount > drawsBefore)
      assert(pilot.screenText.contains("Load"))
      assert(pilot.screenText.contains("Throughput"))
      assert(pilot.screenText.contains("Signal"))
      pilot.pressKey(KeyCode.Char('q'))
      assert(pilot.awaitTermination(2.seconds))
    }
