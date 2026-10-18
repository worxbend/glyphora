package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, Size}
import io.worxbend.tui.terminal.{HeadlessBackend, MouseCaptureMode}

import org.scalatest.funsuite.AnyFunSuite

/** `RunnerConfig.mouseMotion` picks the capture mode setup asks for; a handle can upgrade it mid-run. */
final class MouseMotionConfigSpec extends AnyFunSuite:

  private def modeDuringRun(config: RunnerConfig, onFirst: RunnerHandle => Unit = _ => ()): Option[MouseCaptureMode] =
    val backend = HeadlessBackend(Size(10, 2))
    var seen    = Option.empty[MouseCaptureMode]
    backend.postEvent(Event.Tick)
    val _       = TerminalRunner(backend, config).run(
      _ => (),
      (_, handle) =>
        onFirst(handle)
        seen = backend.mouseCaptureMode
        handle.quit()
        EventOutcome.Ignored
      ,
      _ => (),
    )
    seen

  test("withMouseMotion asks for all-motion tracking and implies mouse capture"):
    val config = RunnerConfig.Default.withMouseMotion
    assert(config.mouseCapture && config.mouseMotion)
    assert(modeDuringRun(config).contains(MouseCaptureMode.AllMotion))

  test("plain mouse capture stays button-only, and motion is off by default"):
    assert(!RunnerConfig.Default.mouseMotion)
    assert(modeDuringRun(RunnerConfig.Default.withMouseCapture).contains(MouseCaptureMode.Buttons))
    assert(!RunnerConfig.Default.withMouseMotion.withoutMouseMotion.mouseMotion)

  test("the config prints and compares its motion flag"):
    assert(RunnerConfig.Default.withMouseMotion.toString.contains("mouseMotion=true"))
    assert(RunnerConfig.Default.withMouseMotion != RunnerConfig.Default.withMouseCapture)

  test("a handle switches a running app's capture mode"):
    val mode = modeDuringRun(RunnerConfig.Default.withMouseCapture, _.setMouseCaptureMode(MouseCaptureMode.AllMotion))
    assert(mode.contains(MouseCaptureMode.AllMotion))
