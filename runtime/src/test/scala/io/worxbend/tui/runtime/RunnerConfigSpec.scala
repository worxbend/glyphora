package io.worxbend.tui.runtime

import io.worxbend.tui.terminal.MouseCaptureMode

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** Covers the shape [[RunnerConfig]] took when it stopped being a `case class`: a `final class` with a private
  * constructor, a companion `apply` that keeps every named-argument construction compiling, one `with*` builder per
  * field (paired with a `without*` for the Booleans and the Options), and hand-written structural equality over every
  * field — with the three function-valued fields compared by reference, as the case class compared them.
  */
final class RunnerConfigSpec extends AnyFunSuite:

  private val onTaskError: RenderTaskErrorHandler = _ => ()
  private val onFrame: CompletedFrame => Unit     = _ => ()
  private val onFrameTiming: FrameTiming => Unit  = _ => ()
  private val cast                                = java.nio.file.Path.of("demo.cast")

  /** Every field set to something other than its default. */
  private val populated = RunnerConfig(
    tickRate = Some(100.millis),
    mouse = MouseCaptureMode.Buttons,
    onTaskError = Some(onTaskError),
    onFrame = Some(onFrame),
    viewport = Viewport.Inline(3),
    keyEventTypes = true,
    onFrameTiming = Some(onFrameTiming),
    captureStdio = Some(CaptureStdio.PrintAbove),
    recording = Some(cast),
  )

  test("every with* builder round-trips its field from the default configuration"):
    assert(RunnerConfig.Default.withTickRate(100.millis) == RunnerConfig(tickRate = Some(100.millis)))
    assert(RunnerConfig.Default.withMouseCapture == RunnerConfig(mouse = MouseCaptureMode.Buttons))
    assert(RunnerConfig.Default.withOnTaskError(onTaskError) == RunnerConfig(onTaskError = Some(onTaskError)))
    assert(RunnerConfig.Default.withOnFrame(onFrame) == RunnerConfig(onFrame = Some(onFrame)))
    assert(RunnerConfig.Default.withViewport(Viewport.Inline(3)) == RunnerConfig(viewport = Viewport.Inline(3)))
    assert(RunnerConfig.Default.withKeyEventTypes == RunnerConfig(keyEventTypes = true))
    assert(RunnerConfig.Default.withOnFrameTiming(onFrameTiming) == RunnerConfig(onFrameTiming = Some(onFrameTiming)))
    assert(
      RunnerConfig.Default.withCaptureStdio(CaptureStdio.PrintAbove)
        == RunnerConfig(captureStdio = Some(CaptureStdio.PrintAbove))
    )
    assert(RunnerConfig.Default.withRecording(cast) == RunnerConfig(recording = Some(cast)))

  test("each builder leaves every other field as it was"):
    assert(populated.withTickRate(1.second).mouseCapture)
    assert(populated.withTickRate(1.second).onFrame.exists(_ eq onFrame))
    assert(populated.withMouseCapture.tickRate.contains(100.millis))
    assert(populated.withViewport(Viewport.Fullscreen).keyEventTypes)
    assert(populated.withKeyEventTypes.viewport == Viewport.Inline(3))
    assert(populated.withOnFrame(_ => ()).onFrameTiming.exists(_ eq onFrameTiming))
    assert(populated.withOnFrameTiming(_ => ()).onTaskError.exists(_ eq onTaskError))
    assert(populated.withOnTaskError(_ => ()).tickRate.contains(100.millis))
    assert(populated.withCaptureStdio(CaptureStdio.Off).keyEventTypes)
    assert(populated.withTickRate(1.second).captureStdio.contains(CaptureStdio.PrintAbove))

  test("the Boolean and Option fields have a without* pair"):
    assert(populated.withoutTickRate.tickRate.isEmpty)
    assert(populated.withoutTickRate.withTickRate(100.millis) == populated)
    assert(!populated.withoutMouseCapture.mouseCapture)
    assert(populated.withoutMouseCapture.withMouseCapture == populated)
    assert(!populated.withoutKeyEventTypes.keyEventTypes)
    assert(populated.withoutKeyEventTypes.withKeyEventTypes == populated)
    assert(populated.withoutOnTaskError.onTaskError.isEmpty)
    assert(populated.withoutOnTaskError.withOnTaskError(onTaskError) == populated)
    assert(populated.withoutOnFrame.onFrame.isEmpty)
    assert(populated.withoutOnFrame.withOnFrame(onFrame) == populated)
    assert(populated.withoutOnFrameTiming.onFrameTiming.isEmpty)
    assert(populated.withoutOnFrameTiming.withOnFrameTiming(onFrameTiming) == populated)
    assert(populated.withoutCaptureStdio.captureStdio.isEmpty)
    assert(populated.withoutCaptureStdio.withCaptureStdio(CaptureStdio.PrintAbove) == populated)
    assert(populated.withoutRecording.recording.isEmpty)
    assert(populated.withoutRecording.withRecording(cast) == populated)

  test("equals and hashCode agree on all fields"):
    val twin            = RunnerConfig.Default
      .withTickRate(100.millis)
      .withMouseCapture
      .withOnTaskError(onTaskError)
      .withOnFrame(onFrame)
      .withViewport(Viewport.Inline(3))
      .withKeyEventTypes
      .withOnFrameTiming(onFrameTiming)
      .withCaptureStdio(CaptureStdio.PrintAbove)
      .withRecording(cast)
    assert(twin == populated)
    assert(twin.hashCode == populated.hashCode)
    val variants        = Seq(
      populated.withTickRate(1.second),
      populated.withoutMouseCapture,
      populated.withOnTaskError(_ => ()),
      populated.withOnFrame(_ => ()),
      populated.withViewport(Viewport.Fullscreen),
      populated.withoutKeyEventTypes,
      populated.withOnFrameTiming(_ => ()),
      populated.withCaptureStdio(CaptureStdio.Off),
      populated.withoutRecording,
    )
    for variant <- variants do
      assert(variant != populated, variant.toString)
      // one-field changes to a field-by-field hash: a collision among them would mean a field the hash forgot
      assert(variant.hashCode != populated.hashCode, variant.toString)
    val notAConfig: Any = "not a config"
    assert(populated != notAConfig)
    assert(Set(populated, twin, RunnerConfig.Default).size == 2)

  test("the function-valued fields compare by reference, as the case class compared them"):
    assert(RunnerConfig(onFrame = Some(onFrame)) == RunnerConfig(onFrame = Some(onFrame)))
    assert(RunnerConfig(onFrame = Some(_ => ())) != RunnerConfig(onFrame = Some(_ => ())))
    assert(RunnerConfig(onTaskError = Some(_ => ())) != RunnerConfig(onTaskError = Some(_ => ())))
    assert(RunnerConfig(onFrameTiming = Some(_ => ())) != RunnerConfig(onFrameTiming = Some(_ => ())))

  test("the companion apply still accepts the documented named-argument forms"):
    assert(RunnerConfig() == RunnerConfig.Default)
    assert(RunnerConfig(tickRate = None) == RunnerConfig.Default)
    assert(
      RunnerConfig(tickRate = Some(100.millis), mouse = MouseCaptureMode.Buttons)
        == RunnerConfig.Default.withTickRate(100.millis).withMouseCapture
    )
    assert(RunnerConfig(keyEventTypes = true).keyEventTypes)
    assert(RunnerConfig(viewport = Viewport.Inline(3)).viewport == Viewport.Inline(3))
    assert(RunnerConfig(onTaskError = Some(onTaskError)).onTaskError.exists(_ eq onTaskError))
    assert(
      RunnerConfig(
        Some(100.millis),
        true,
        Some(onTaskError),
        Some(onFrame),
        Viewport.Inline(3),
        true,
        Some(onFrameTiming),
        Some(CaptureStdio.PrintAbove),
        Some(cast),
      ) == populated
    )
    // the seven-field arity is kept as a plain overload for callers compiled before `captureStdio`/`recording` existed
    assert(
      RunnerConfig(
        Some(100.millis),
        true,
        Some(onTaskError),
        Some(onFrame),
        Viewport.Inline(3),
        true,
        Some(onFrameTiming),
      )
        == populated.withoutCaptureStdio.withoutRecording
    )

  test("toString lists only the fields that differ from the default"):
    assert(RunnerConfig.Default.toString == "RunnerConfig.Default")
    assert(
      RunnerConfig(tickRate = Some(100.millis), mouse = MouseCaptureMode.Buttons).toString
        == "RunnerConfig(tickRate=Some(100 milliseconds), mouse=Buttons)"
    )
    assert(RunnerConfig(onFrame = Some(onFrame)).toString == "RunnerConfig(onFrame=<installed>)")
    assert(
      RunnerConfig(viewport = Viewport.Inline(3), keyEventTypes = true).toString
        == "RunnerConfig(viewport=Inline(3), keyEventTypes=true)"
    )
    assert(populated.toString.startsWith("RunnerConfig(tickRate=Some(100 milliseconds), mouse=Buttons, "))
    assert(populated.toString.endsWith(", captureStdio=PrintAbove, recording=demo.cast)"))
