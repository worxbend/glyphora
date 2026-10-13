package io.worxbend.tui.dsl

import org.scalatest.funsuite.AnyFunSuite

/** A consumer must be able to name every frame-observation type through the application-facing import. */
final class FrameObservationImportSpec extends AnyFunSuite:
  test("one DSL import exposes frame timing and bounded off-thread observation"):
    val errors = scala.compiletime.testing.typeCheckErrors("""
      import io.worxbend.tui.dsl.*
      def configure(save: CompletedFrame => Unit, report: Throwable => Unit, timing: FrameTiming => Unit): RunnerConfig =
        val observer = LatestFrameObserver(save, report)
        RunnerConfig(onFrame = Some(observer), onFrameTiming = Some(timing))
      def sampledRun(saveFrame: CompletedFrame => Unit, reportFrameError: Throwable => Unit,
                     runApplication: RunnerConfig => Either[RunnerError, Unit]): Either[RunnerError, Unit] =
        val observer = LatestFrameObserver(saveFrame, reportFrameError)
        try runApplication(RunnerConfig(onFrame = Some(observer)))
        finally observer.close()
      def timedConfig(recordSample: (Long, Long, Long) => Unit): RunnerConfig =
        RunnerConfig(onFrameTiming = Some { (timing: FrameTiming) =>
          recordSample(timing.compositionNanos, timing.drawNanos, timing.observerNanos)
        })
    """)
    assert(errors.isEmpty, errors.map(_.message).mkString("\n"))
