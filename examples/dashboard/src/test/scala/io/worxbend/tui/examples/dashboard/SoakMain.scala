package io.worxbend.tui.examples.dashboard

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot

import scala.concurrent.duration.DurationInt

/** Scripted soak check: runs the dashboard headless for 60 seconds under its tick rate and reports draw count and heap
  * growth. Run with `./mill examples.dashboard.test.runMain io.worxbend.tui.examples.dashboard.SoakMain`.
  */
object SoakMain:

  def main(args: Array[String]): Unit =
    val seconds = args.headOption.flatMap(_.toIntOption).getOrElse(60)
    val backend = HeadlessBackend(Size(120, 40))
    val app     = DashboardApp()
    Pilot.using(backend) { app.runWith(backend) } { pilot =>
      pilot.waitForIdle()
      val heapBefore  = usedHeap()
      val drawsBefore = backend.drawCount
      Thread.sleep(seconds * 1000L)
      val draws       = backend.drawCount - drawsBefore
      val heapAfter   = usedHeap()
      pilot.pressKey(KeyCode.Char('q'))
      val stopped     = pilot.awaitTermination(2.seconds)
      println(
        f"soak: $seconds s, $draws draws (${draws.toDouble / seconds}%.1f fps), " +
          f"heap ${heapBefore / 1024 / 1024} MB -> ${heapAfter / 1024 / 1024} MB, clean shutdown: $stopped"
      )
    }

  private def usedHeap(): Long =
    System.gc()
    Thread.sleep(200)
    Runtime.getRuntime.totalMemory() - Runtime.getRuntime.freeMemory()
