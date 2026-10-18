package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.{CaptureStdio, LogLevel, LogRing, RunnerConfig}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.concurrent.duration.DurationInt

/** A `TuiApp` captures `println` into `printAbove` unless its `config` says otherwise — the host default
  * `RunnerConfig.captureStdio = None` leaves to it.
  */
final class StdioCaptureAppSpec extends AnyFunSuite with PilotFixture:

  /** The run happens on the pilot's thread, so the swap is observed there; the test thread only asserts afterwards. */
  private def runPrinting(app: TuiApp, backend: HeadlessBackend): Unit =
    val pilot = startPilot(backend) { app.runWith(backend) }.waitForIdle()
    pilot.typeText("p").waitForIdle()
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  private def printingApp(configured: RunnerConfig): TuiApp = new TuiApp:
    override def config: RunnerConfig             = configured
    override def bindings: KeyBindings            = KeyBindings(
      binding("p", "print")(println("hello from println")),
      binding("ctrl+q", "quit")(quit()),
    )
    def view(using ReactiveScope, Theme): Element = text("app")

  test("by default a println from a handler lands above the UI, not on the frame"):
    val backend = HeadlessBackend(Size(20, 3))
    runPrinting(printingApp(RunnerConfig()), backend)
    assert(backend.printedAbove == Seq("hello from println"))
    assert(backend.lastDrawn.exists(frame => (0 until 3).map(x => frame.get(x, 0).symbol).mkString == "app"))

  test("a config that sets other fields keeps the app default"):
    val backend = HeadlessBackend(Size(20, 3))
    runPrinting(printingApp(RunnerConfig(tickRate = Some(1.second))), backend)
    assert(backend.printedAbove == Seq("hello from println"))

  test("CaptureStdio.Off overrules the app default"):
    val realOut  = new ByteArrayOutputStream
    val priorOut = System.out
    System.setOut(PrintStream(realOut, true, "UTF-8"))
    try
      val backend = HeadlessBackend(Size(20, 3))
      runPrinting(printingApp(RunnerConfig().withCaptureStdio(CaptureStdio.Off)), backend)
      assert(backend.printedAbove.isEmpty)
    finally System.setOut(priorOut)

  test("CaptureStdio.Console records the line in the ring as stdout at Info"):
    val ring    = LogRing(10)
    val backend = HeadlessBackend(Size(20, 3))
    runPrinting(printingApp(RunnerConfig().withCaptureStdio(CaptureStdio.Console(ring))), backend)
    assert(backend.printedAbove.isEmpty)
    val records = ring.snapshot
    assert(
      records.map(record => (record.level, record.logger, record.message)) ==
        Vector((LogLevel.Info, "stdout", "hello from println"))
    )
