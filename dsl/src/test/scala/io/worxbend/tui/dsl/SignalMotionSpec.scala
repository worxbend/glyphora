package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.Signal
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.ManualClock

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** `signal.animateTo(target, duration, easing)` moves a `Signal[Double]` or `Signal[Int]` along a `Tween` on the ticks
  * the run already has. Driven by a `ManualClock` through a configured tick rate, so every sample is an exact moment on
  * the curve rather than whatever wall-clock time happened to pass.
  */
final class SignalMotionSpec extends AnyFunSuite with PilotFixture:

  test("a Double signal reaches its target at the duration, through the easing curve, under a manual clock"):
    val backend = HeadlessBackend(Size(20, 3))
    val clock   = ManualClock()
    val value   = Signal(0.0)
    val app     = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(tickRate = Some(100.millis))
      override def bindings: KeyBindings            =
        KeyBindings(binding("a", "animate") { val _ = value.animateTo(100.0, 400.millis, Easing.QuadOut) })
      def view(using ReactiveScope, Theme): Element = text(f"${value.get}%.2f")
    val pilot   = startPilot(backend)(app.runWith(backend, clock.reading))
    pilot.waitForIdle()
    pilot.press("a").waitForIdle()
    assert(pilot.screenLines.head.trim == "0.00")
    // QuadOut: 1 - (1 - t)^2, so a quarter of the way in time is 43.75% of the way in value, half is 75%
    pilot.advanceClock(clock, 100.millis)
    assert(pilot.screenLines.head.trim == "43.75")
    pilot.advanceClock(clock, 100.millis)
    assert(pilot.screenLines.head.trim == "75.00")
    pilot.advanceClock(clock, 200.millis)
    assert(pilot.screenLines.head.trim == "100.00")
    // finished: a further tick paints nothing new, and the value stays put
    pilot.advanceClock(clock, 100.millis, draws = 0)
    pilot.waitForIdle()
    assert(pilot.screenLines.head.trim == "100.00")
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("an Int signal rounds each sample and lands exactly on its target"):
    val backend = HeadlessBackend(Size(20, 3))
    val clock   = ManualClock()
    val count   = Signal(0)
    val app     = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(tickRate = Some(100.millis))
      override def bindings: KeyBindings            =
        KeyBindings(binding("a", "animate") { val _ = count.animateTo(10, 400.millis, Easing.Linear) })
      def view(using ReactiveScope, Theme): Element = text(count.get.toString)
    val pilot   = startPilot(backend)(app.runWith(backend, clock.reading))
    pilot.waitForIdle()
    pilot.press("a").waitForIdle()
    pilot.advanceClock(clock, 100.millis)
    assert(pilot.screenLines.head.trim == "3") // 2.5 rounds half up
    pilot.advanceClock(clock, 100.millis)
    assert(pilot.screenLines.head.trim == "5")
    pilot.advanceClock(clock, 200.millis)
    assert(pilot.screenLines.head.trim == "10")
    pilot.interrupt()
    val _ = pilot.awaitTermination()

  test("with no tick rate configured, the ambient ticker carries the motion to its target and then stands down"):
    val backend = HeadlessBackend(Size(20, 3))
    val value   = Signal(0.0)
    val app     = new TuiApp:
      override def bindings: KeyBindings            =
        KeyBindings(binding("a", "animate") { val _ = value.animateTo(1.0, 200.millis, Easing.Linear) })
      // the unrounded value, not `%.2f`: a late tick under load can sample 0.995 <= v < 1.0, which `%.2f` already shows
      // as "1.00" while the motion still owes its final tick — and that tick's frame then lands in the window below
      def view(using ReactiveScope, Theme): Element = text(value.get.toString)
    val pilot   = startPilot(backend)(app.runWith(backend))
    pilot.waitForIdle()
    pilot.press("a")
    pilot.waitUntil("the motion to reach its target")(pilot.screenLines.head.trim == "1.0")
    pilot.waitForIdle()
    // the ticker that carried it must not keep asking for frames once nothing is moving
    val settled = backend.drawCount
    Thread.sleep(300)
    assert(
      backend.drawCount == settled,
      s"redraws kept coming after the motion finished: $settled -> ${backend.drawCount}",
    )
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("starting a second animation on the same signal replaces the first, and cancel stops one in flight"):
    val backend = HeadlessBackend(Size(20, 3))
    val clock   = ManualClock()
    val value   = Signal(0.0)
    var handle  = Option.empty[Cancelable]
    val app     = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(tickRate = Some(100.millis))
      override def bindings: KeyBindings            = KeyBindings(
        binding("a", "animate up") { handle = Some(value.animateTo(100.0, 400.millis, Easing.Linear)) },
        binding("b", "animate down instead") { handle = Some(value.animateTo(-100.0, 400.millis, Easing.Linear)) },
        binding("c", "cancel")(handle.foreach(_.cancel())),
      )
      def view(using ReactiveScope, Theme): Element = text(f"${value.get}%.1f")
    val pilot   = startPilot(backend)(app.runWith(backend, clock.reading))
    pilot.waitForIdle()
    pilot.press("a").waitForIdle()
    pilot.advanceClock(clock, 100.millis)
    assert(pilot.screenLines.head.trim == "25.0")
    pilot.press("b").waitForIdle() // from 25 towards -100 over 400ms: -31.25 per 100ms
    pilot.advanceClock(clock, 100.millis)
    assert(pilot.screenLines.head.trim == "-6.3")
    pilot.press("c").waitForIdle()
    pilot.advanceClock(clock, 100.millis, draws = 0)
    pilot.waitForIdle()
    assert(pilot.screenLines.head.trim == "-6.3", "a cancelled animation must not move the signal again")
    pilot.interrupt()
    val _ = pilot.awaitTermination()
