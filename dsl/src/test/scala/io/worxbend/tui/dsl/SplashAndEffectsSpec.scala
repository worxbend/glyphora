package io.worxbend.tui.dsl

import io.worxbend.tui.core.{KeyCode, Size}
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.ManualClock

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

final class SplashAndEffectsSpec extends AnyFunSuite with PilotFixture:

  private final class SplashApp extends TuiApp:
    override def splash: Option[SplashScreen]     = Some(
      SplashScreen(
        centered(20, 1)(text("LOADING")),
        effect = Effect.fadeIn(100.millis),
        minimumDuration = 300.millis,
      )
    )
    override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
    def view(using ReactiveScope, Theme): Element = text("main view")

  test("a player with no splash declared is never active"):
    assert(!SplashPlayer(None, () => 0L).isActive)

  test("skip ends the intro without any time passing"):
    val player = SplashPlayer(Some(SplashScreen(text("INTRO"), Effect.fadeIn(50.millis))), () => 0L)
    assert(player.isActive)
    player.skip()
    assert(!player.isActive)

  /** The stack times effects off the clock it is handed, so this pins the drop boundary exactly rather than sleeping
    * past it and hoping.
    */
  test("the effect stack drops an effect the moment its own clock says it is done"):
    var nanos = 0L
    val stack = EffectStack(() => nanos)
    assert(stack.isEmpty)
    stack.start(Effect.dissolve(150.millis))
    assert(!stack.isEmpty)
    assert(!stack.prune(), "an effect that has not started running yet is not done")
    nanos = 149.millis.toNanos
    assert(!stack.prune())
    nanos = 150.millis.toNanos
    assert(stack.prune(), "the tick that crosses the duration reports the drop")
    assert(stack.isEmpty)
    assert(!stack.prune(), "an empty stack has nothing left to report")

  test("the splash shows first, then transitions to the main view"):
    val backend = HeadlessBackend(Size(30, 5))
    val clock   = ManualClock()
    val app     = SplashApp()
    val pilot   = startPilot(backend) { app.runWith(backend, clock.reading) }
    pilot.waitForIdle()
    assert(pilot.screenText.contains("LOADING"))
    assert(!pilot.screenText.contains("main view"))
    // the intro's minimumDuration is 300 ms; stepping the clock past it has to end the intro on the next tick
    pilot.advanceClock(clock, 500.millis, draws = 0)
    pilot.waitUntil("the main view once the intro has elapsed")(pilot.screenText.contains("main view"))
    pilot.pressKey(KeyCode.Char('q'))
    assert(pilot.awaitTermination())

  test("any key skips the splash"):
    val backend = HeadlessBackend(Size(30, 5))
    val app     = new TuiApp:
      override def splash: Option[SplashScreen]     = Some(
        SplashScreen(text("INTRO"), Effect.fadeIn(50.millis), minimumDuration = 60.seconds)
      )
      override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = text("main view")
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    assert(pilot.screenText.contains("INTRO"))
    pilot.pressKey(KeyCode.Enter).waitForIdle()
    assert(pilot.screenText.contains("main view"))
    pilot.pressKey(KeyCode.Char('q'))
    assert(pilot.awaitTermination())

  test("runEffect animates over the rendered view and completes"):
    val backend = HeadlessBackend(Size(20, 3))
    val clock   = ManualClock()
    val app     = new TuiApp:
      override def config                           = io.worxbend.tui.runtime.RunnerConfig(tickRate = Some(20.millis))
      override def bindings: KeyBindings            = KeyBindings(
        binding("e", "run effect")(runEffect(Effect.dissolve(150.millis))),
        binding("q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("solid content here")
    val pilot   = startPilot(backend) { app.runWith(backend, clock.reading) }
    pilot.waitForIdle()
    assert(pilot.screenText.contains("solid content here"))
    pilot.pressKey(KeyCode.Char('e')).waitForIdle()
    // an effect is a pure function of the clock: stepping into the middle of the dissolve has to erase content, and
    // stepping past its duration has to drop it and put the frame back — no wall-clock waiting either way
    pilot.advanceClock(clock, 75.millis)
    pilot.waitUntil("the effect to visibly alter the frame")(!pilot.screenText.contains("solid content here"))
    pilot.advanceClock(clock, 100.millis)
    pilot.waitUntil("the content to be fully restored")(pilot.screenText.contains("solid content here"))
    pilot.pressKey(KeyCode.Char('q'))
    assert(pilot.awaitTermination())

  /** A splash needs a clock, and an app that declared no `tickRate` gets one lent to it for the intro. The loan has to
    * end with the intro: `onTick` is documented as requiring a `config.tickRate`, so an app that never asked for ticks
    * must not keep receiving them for the rest of the process.
    */
  test("an app that configured no tickRate stops receiving ticks once the splash is over"):
    val backend = HeadlessBackend(Size(30, 5))
    val clock   = ManualClock()
    val ticks   = new java.util.concurrent.atomic.AtomicInteger(0)
    val app     = new TuiApp:
      override def splash: Option[SplashScreen]     = Some(
        SplashScreen(text("INTRO"), Effect.fadeIn(20.millis), minimumDuration = 20.millis)
      )
      override def onTick(): Unit                   = { val _ = ticks.incrementAndGet() }
      override def bindings: KeyBindings            = KeyBindings(binding("q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = text("main view")
    val pilot   = startPilot(backend) { app.runWith(backend, clock.reading) }
    pilot.waitForIdle()
    assert(pilot.screenText.contains("INTRO"))
    // the intro lasts 20 ms; stepping well past it has to end the intro on the next tick
    pilot.advanceClock(clock, 1.second, draws = 0)
    pilot.waitUntil("the main view once the intro has elapsed")(pilot.screenText.contains("main view"))
    // the tick loan ends with the intro: a couple of ambient intervals of honest wall-clock time lets any tick queued
    // before the stand-down land, and after it nothing may deliver ticks — there is no configured rate to deliver them
    Thread.sleep(100)
    assert(ticks.get() == 0, s"onTick fired in an app that configured no tickRate: ${ticks.get()}")
    pilot.pressKey(KeyCode.Char('q'))
    assert(pilot.awaitTermination())
