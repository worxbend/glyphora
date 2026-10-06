package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.{ReactiveScope, RunnerError}
import io.worxbend.tui.terminal.HeadlessBackend
import org.scalatest.funsuite.AnyFunSuite

final class LifecycleClockReleaseSpec extends AnyFunSuite:
  test("throwing stop callbacks cannot retain animation clock registrations"):
    val baseline = AnimationClock.attachedLoops
    val failure  = IllegalStateException("stop")
    val app      = new TuiApp:
      def view(using ReactiveScope, Theme): Element = text("unused")
      override def onStart(): Unit                  =
        pushScreen(new Screen:
          def view(using ReactiveScope, Theme): Element = text("unused")
          override def onLeave(): Unit                  = throw failure)
        quit()
      override def onStop(): Unit                   = throw IllegalStateException("onStop")
    assert(app.runWith(HeadlessBackend(Size(10, 2))) == Left(RunnerError.Handler(failure)))
    assert(AnimationClock.attachedLoops == baseline)
