package io.worxbend.tui.dsl

import io.worxbend.tui.core.Size
import io.worxbend.tui.runtime.Signal
import io.worxbend.tui.terminal.HeadlessBackend
import io.worxbend.tui.testsupport.{ManualClock, Pilot}

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

/** An app that renders an animation gets the ticks that animation needs, without configuring a `tickRate`.
  *
  * The assertion throughout is that the *drawn glyph* changes over time, because that is what a frozen spinner looks
  * like from the outside and it is what the negotiation exists to prevent. Where a test's wait bounds a genuinely
  * concurrent claim, the smallest honest window of wall-clock time is kept; everything else is asserted through
  * `pilot.waitUntil`, so a failure is an assertion rather than a hung suite.
  */
final class AmbientTickSpec extends AnyFunSuite with PilotFixture:

  private def quitApp(pilot: Pilot): Unit =
    pilot.pressKey(KeyCode.Char('q'), KeyModifiers.Ctrl)
    assert(pilot.awaitTermination())

  test("a spinner animates in an app that configured no tick rate"):
    val backend = HeadlessBackend(Size(20, 3))
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = spinner()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    val first   = pilot.screenText
    pilot.waitUntil("the spinner frame to change", 3.seconds)(pilot.screenText != first)
    quitApp(pilot)

  /** The other half of the bargain: a frame with nothing animated on it must not leave a ticker running. The app is
    * driven into an animated state and back out of it, and the assertion is on the backend's draw count: the "idle"
    * text reads the same whether or not anything repaints it, so only a counter catches a ticker that kept asking for
    * frames.
    */
  test("an app whose frame stops animating stops ticking"):
    val backend = HeadlessBackend(Size(20, 3))
    val busy    = Signal(true)
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("s", "stop animating")(busy.set(false)),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element =
        if busy.get then spinner() else text("idle")
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('s')).waitForIdle()
    assert(pilot.screenText.contains("idle"))
    // a ticker still running would keep asking for frames; several of its own intervals of silence is the honest
    // proof that it stood down
    val settled = backend.drawCount
    Thread.sleep(300)
    assert(
      backend.drawCount == settled,
      s"redraws kept coming after the frame stopped animating: $settled -> ${backend.drawCount}",
    )
    quitApp(pilot)

  /** An app that *did* configure a tick rate must be driven by the runner exactly as before — the ambient ticker stays
    * out of the way entirely, and `onTick` keeps being the thing a configured tick rate delivers.
    */
  test("a configured tick rate still drives onTick, and the ambient path does not double up"):
    val backend = HeadlessBackend(Size(20, 3))
    var ticks   = 0
    val app     = new TuiApp:
      override def config: RunnerConfig             = RunnerConfig(tickRate = Some(10.millis))
      override def onTick(): Unit                   = ticks += 1
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = spinner()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    pilot.waitUntil("onTick to be driven by the configured rate", 3.seconds)(ticks > 3)
    // at a 10 ms rate, 100 ms of wall-clock time is due exactly ten ticks; a second stream from the ambient path
    // would roughly double that, so the growth over the window is bounded well below double
    val before  = ticks
    Thread.sleep(100)
    assert(
      ticks - before < 20,
      s"the ambient path double-ticked: $before -> $ticks over 100 ms at a 10 ms rate",
    )
    quitApp(pilot)

  /** `onTick` is documented as needing a `config.tickRate`. The ambient ticker deliberately does not call it, so an app
    * that never asked for ticks does not silently start receiving them.
    */
  test("the ambient ticker does not call onTick"):
    val backend = HeadlessBackend(Size(20, 3))
    var ticks   = 0
    val app     = new TuiApp:
      override def onTick(): Unit                   = ticks += 1
      override def bindings: KeyBindings            = KeyBindings(binding("ctrl+q", "quit")(quit()))
      def view(using ReactiveScope, Theme): Element = spinner()
    val pilot   = startPilot(backend) { app.runWith(backend) }
    pilot.waitForIdle()
    val first   = pilot.screenText
    pilot.waitUntil("the spinner to animate", 3.seconds)(pilot.screenText != first) // it really is animating
    assert(ticks == 0)
    quitApp(pilot)

  /** Toasts age in clock time and are not part of the tree, so they ask for ticks on their own account — an app with no
    * animation and no configured tick rate must still see a toast disappear. The injected clock makes the expiry exact:
    * advancing past the toast's own duration has to age it out.
    */
  test("a toast ages out in an app that configured no tick rate"):
    val backend = HeadlessBackend(Size(40, 6))
    val clock   = ManualClock()
    val app     = new TuiApp:
      override def bindings: KeyBindings            = KeyBindings(
        binding("n", "notify")(notify("saved ok", duration = 300.millis)),
        binding("ctrl+q", "quit")(quit()),
      )
      def view(using ReactiveScope, Theme): Element = text("content")
    val pilot   = startPilot(backend) { app.runWith(backend, clock.reading) }
    pilot.waitForIdle()
    pilot.pressKey(KeyCode.Char('n')).waitForIdle()
    assert(pilot.screenText.contains("saved ok"))
    pilot.advanceClock(clock, 1.second, draws = 0)
    pilot.waitUntil("the toast to age out")(!pilot.screenText.contains("saved ok"))
    quitApp(pilot)
