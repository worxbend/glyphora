package io.worxbend.tui.examples.airsensor

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import scala.concurrent.duration.DurationInt

/** Renders airsensor headlessly against the scripted sensor and prints the frame as plain text — the source for the
  * documentation "screenshot". Run with
  * `./mill examples.airsensor.test.runMain io.worxbend.tui.examples.airsensor.ScreenshotMain [w] [h] [readings]`.
  */
object ScreenshotMain:

  def main(args: Array[String]): Unit =
    val width    = args.headOption.flatMap(_.toIntOption).getOrElse(96)
    val height   = args.lift(1).flatMap(_.toIntOption).getOrElse(24)
    val readings = args.lift(2).flatMap(_.toIntOption).getOrElse(6)

    val backend = HeadlessBackend(Size(width, height))
    // a fast cadence so the history pane has something in it by the time the frame is captured
    val app     = AirSensorApp(FakeSensor(), 60.millis)
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()

      val deadline = System.nanoTime() + 10.seconds.toNanos
      while app.history.peek.sizeIs < readings && System.nanoTime() < deadline do Thread.sleep(20)

      println(pilot.screenLines.mkString("\n"))
      pilot.pressKey(KeyCode.Char('q'))
      val _ = pilot.awaitTermination()
    }
