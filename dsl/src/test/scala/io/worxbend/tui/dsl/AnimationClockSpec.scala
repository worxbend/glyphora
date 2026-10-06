package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.{RenderThread, RunnerConfig}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.Pilot
import io.worxbend.tui.widgets as w

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** The ambient clock exists to delete four steps of ceremony — a `tickRate`, a `Signal[Int]`, an `onTick` override, and
  * threading the counter through every call site. These pin what it replaced them with.
  */
final class AnimationClockSpec extends AnyFunSuite with PilotFixture:

  private def app(rate: Option[FiniteDuration])(view0: ReactiveScope ?=> Element): (HeadlessBackend, Pilot) =
    val backend = HeadlessBackend(Size(24, 4))
    val started = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(tickRate = rate)
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = view0
    (backend, startPilot(backend) { started.runWith(backend) }.waitForIdle())

  private def close(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  /** The headline: a spinner with no counter, no `onTick`, and no argument still animates. */
  test("a spinner animates with no tick plumbing at the call site"):
    val (_, pilot) = app(Some(10.millis))(spinner("working"))
    val first      = pilot.screenLines.head
    val deadline   = System.nanoTime() + 2_000_000_000L
    var moved      = false
    while !moved && System.nanoTime() < deadline do
      pilot.waitForIdle()
      moved = pilot.screenLines.head != first
    assert(moved, s"the spinner never advanced past '$first'")
    close(pilot)

  /** The reason the clock is a tracked read rather than a plain value: a view that renders no animation must not be
    * repainted by the ticks. A hand-rolled `ticks.get` in the view repaints the whole app forever instead.
    */
  test("ticks do not repaint a view that renders no animation"):
    val (backend, pilot) = app(Some(10.millis))(text("static"))
    pilot.waitForIdle()
    val drawsBefore      = backend.drawCount
    val deadline         = System.nanoTime() + 300_000_000L
    while System.nanoTime() < deadline do pilot.waitForIdle()
    assert(backend.drawCount == drawsBefore, "a static view was repainted by animation ticks")
    close(pilot)

  test("a view that renders an animation is repainted by the ticks"):
    val (backend, pilot) = app(Some(10.millis))(spinner("working"))
    pilot.waitForIdle()
    val drawsBefore      = backend.drawCount
    val deadline         = System.nanoTime() + 300_000_000L
    while System.nanoTime() < deadline do pilot.waitForIdle()
    assert(backend.drawCount > drawsBefore, "an animated view was never repainted")
    close(pilot)

  /** Without a tick rate there is nothing to advance the clock, so the animation is simply static — it must not throw
    * or render blank, because a spinner in a tick-less app is a plausible mistake rather than a crash.
    */
  test("with no tick rate the animation renders its first frame and stays there"):
    val (_, pilot) = app(None)(spinner("working"))
    assert(pilot.screenLines.head.endsWith("working"))
    close(pilot)

  /** An animation is a pure function of the clock, so pinning it makes any frame reproducible without waiting. */
  test("freezing the clock makes a frame reproducible"):
    AnimationClockLock.frozenAt(0.millis):
      val (_, first) = app(None)(spinner())
      val atZero     = first.screenLines.head
      close(first)

      AnimationClock.freezeAt(w.SpinnerPreset.Dots.frameDuration)
      val (_, second) = app(None)(spinner())
      assert(second.screenLines.head != atZero, "a different frozen moment should render a different frame")
      close(second)

      AnimationClock.freezeAt(0.millis)
      val (_, third) = app(None)(spinner())
      assert(third.screenLines.head == atZero, "the same frozen moment must render the same frame")
      close(third)

  /** The clock is one signal *per render loop*, and this is why.
    *
    * A `Signal`'s subscriber set is a plain mutable set with no lock, confined to a render thread. One clock for the
    * whole process meant two runners' render threads subscribing to and notifying that one set at the same time — which
    * `RenderThread.checkRenderThread` cannot catch, because both threads *are* render threads. What it cost was silent:
    * subscriptions were lost to races on the set, so an app whose only reactive dependency is the ambient clock — any
    * `spinner` — simply froze mid-animation. Two loops, 2000 tracked reads each: every one must survive.
    */
  test("two render loops keep every clock subscription their own views made"):
    val reads    = 2000
    val advanced = CyclicBarrier(2)

    def renderLoop(seen: AtomicInteger): Thread =
      val thread = Thread { () =>
        val _ = RenderThread.register(Thread.currentThread())
        try
          val loop = AnimationClock.attachToCurrentLoop()
          try
            (1 to reads).foreach { _ =>
              given ReactiveScope = ReactiveScope.onInvalidation(() => { val _ = seen.incrementAndGet() })
              val _               = AnimationClock.elapsed
            }
            val _ = advanced.await() // both loops finish subscribing before either publishes
            AnimationClock.advance()
          finally AnimationClock.releaseLoop(loop)
        finally RenderThread.unregister()
      }
      thread.setDaemon(true)
      thread

    val first   = AtomicInteger(0)
    val second  = AtomicInteger(0)
    val threads = Seq(renderLoop(first), renderLoop(second))
    threads.foreach(_.start())
    threads.foreach(_.join(30_000L))

    assert(first.get() == reads, s"${reads - first.get()} of one loop's subscriptions were lost")
    assert(second.get() == reads, s"${reads - second.get()} of the other loop's subscriptions were lost")

  /** A run hands its clock back on the way out, so a JVM that starts and stops apps does not accumulate one signal per
    * run — and a later run gets a clock of its own rather than the dead run's.
    */
  test("an app takes a clock of its own for the run and hands it back on the way out"):
    val before     = AnimationClock.attachedLoops
    val (_, pilot) = app(Some(10.millis))(spinner("working"))
    pilot.waitForIdle()
    assert(AnimationClock.attachedLoops == before + 1, "the run did not take a clock of its own")
    close(pilot)
    assert(AnimationClock.attachedLoops == before, "the finished run left its clock behind")

  /** The explicit-clock factories stay available for animations tied to something other than wall time. */
  test("the explicit-clock factories drive from a caller's own value"):
    val (_, pilot) = app(None)(
      column(
        spinnerAt(0.millis, "a"),
        spinnerAt(w.SpinnerPreset.Dots.frameDuration, "b"),
      )
    )
    assert(pilot.screenLines.head != pilot.screenLines(1), "two different moments should render different frames")
    close(pilot)
