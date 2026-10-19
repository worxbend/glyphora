package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, Size}
import io.worxbend.tui.terminal.{HeadlessBackend, MouseCaptureMode}

import org.scalatest.funsuite.AnyFunSuite

/** `RunnerConfig.mouse` picks the capture mode setup asks for; a handle can upgrade it mid-run. */
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

  test("mouse = AllMotion asks for all-motion tracking, which is mouse capture"):
    val config = RunnerConfig.Default.withMouse(MouseCaptureMode.AllMotion)
    assert(config.mouse == MouseCaptureMode.AllMotion && config.mouseCapture)
    assert(modeDuringRun(config).contains(MouseCaptureMode.AllMotion))

  test("mouse capture is off by default, and withMouseCapture is button-only"):
    assert(RunnerConfig.Default.mouse == MouseCaptureMode.Off && !RunnerConfig.Default.mouseCapture)
    assert(modeDuringRun(RunnerConfig.Default).isEmpty)
    assert(RunnerConfig.Default.withMouseCapture.mouse == MouseCaptureMode.Buttons)
    assert(modeDuringRun(RunnerConfig.Default.withMouseCapture).contains(MouseCaptureMode.Buttons))
    assert(RunnerConfig.Default.withMouse(MouseCaptureMode.AllMotion).withoutMouseCapture == RunnerConfig.Default)

  test("the config prints and compares its one mouse setting"):
    assert(RunnerConfig.Default.withMouse(MouseCaptureMode.AllMotion).toString == "RunnerConfig(mouse=AllMotion)")
    assert(RunnerConfig.Default.withMouse(MouseCaptureMode.AllMotion) != RunnerConfig.Default.withMouseCapture)
    assert(RunnerConfig(mouse = MouseCaptureMode.Buttons) == RunnerConfig.Default.withMouseCapture)

  test("the kept positional arities still take a Boolean, true meaning buttons"):
    val config = RunnerConfig(None, true, None, None, Viewport.Fullscreen, false, None)
    assert(config.mouse == MouseCaptureMode.Buttons)
    assert(RunnerConfig(None, false, None, None, Viewport.Fullscreen, false, None).mouse == MouseCaptureMode.Off)

  test("a handle switches a running app's capture mode"):
    val mode = modeDuringRun(RunnerConfig.Default.withMouseCapture, _.setMouseCaptureMode(MouseCaptureMode.AllMotion))
    assert(mode.contains(MouseCaptureMode.AllMotion))
