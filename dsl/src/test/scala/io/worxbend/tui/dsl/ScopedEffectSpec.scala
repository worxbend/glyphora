package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Color, Effect, Modifiers, Rect, Size}
import io.worxbend.tui.runtime.Frame
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}

/** Element-scoped effects: an effect confined to the rect one node was painted into. */
final class ScopedEffectSpec extends AnyFunSuite with PilotFixture:
  private given ReactiveScope = ReactiveScope.untracked

  /** Emboldens every cell of the area it is handed, whatever the elapsed time — a probe for "which cells did it see".
    */
  private val embolden: Effect = new Effect:
    def process(elapsed: FiniteDuration, buffer: Buffer, area: Rect): Unit = buffer.mapStyle(area)(_.bold)
    def duration: Duration                                                 = 10.seconds

  private def bold(buffer: Buffer, x: Int, y: Int): Boolean = buffer.get(x, y).style.modifiers.hasAny(Modifiers.Bold)

  private def paint(view: Element): Buffer =
    val buffer = Buffer(Rect(0, 0, 4, 2))
    ElementHost().render(buffer.area, buffer, Theme.Dark, view)
    buffer

  test("withEffect(flash) changes only the element's cells at t = 0.5"):
    val flash     = Effect.paint(Color.Red, 1.second)
    val plain     = paint(column(text("aaaa").length(1), text("bbbb").length(1)))
    val flashed   = paint(column(text("aaaa").length(1), text("bbbb").withEffect(flash, 500.millis).length(1)))
    val untouched = (0 until 4).forall(x => flashed.get(x, 0) == plain.get(x, 0))
    val changed   = (0 until 4).forall(x => flashed.get(x, 1).style.fg != plain.get(x, 1).style.fg)
    assert(untouched, "the sibling row must be left as painted")
    assert(changed, "every cell of the effected element must be repainted")
    assert(flashed.get(0, 1).symbol == "b")

  test("a scoped effect on the stack covers only the rect its scope resolves to"):
    val stack  = EffectStack(() => 0L)
    stack.start("flash", embolden, scope = "panel")
    val buffer = Buffer(Rect(0, 0, 6, 3))
    stack.applyTo(Frame(buffer.area, buffer), key => Option.when(key == "panel")(Rect(1, 1, 2, 1)))
    assert(bold(buffer, 1, 1) && bold(buffer, 2, 1))
    assert(!bold(buffer, 0, 1) && !bold(buffer, 3, 1) && !bold(buffer, 1, 0) && !bold(buffer, 1, 2))

  test("a scoped effect whose node is not on screen paints nothing that frame, and restarting the key rescopes it"):
    val stack  = EffectStack(() => 0L)
    stack.start("flash", embolden, scope = "gone")
    val buffer = Buffer(Rect(0, 0, 3, 1))
    stack.applyTo(Frame(buffer.area, buffer), _ => None)
    assert(!(0 until 3).exists(bold(buffer, _, 0)))
    stack.start("flash", embolden)
    stack.applyTo(Frame(buffer.area, buffer), _ => None)
    assert((0 until 3).forall(bold(buffer, _, 0)))
    assert(stack.activeCount == 1)

  test("runEffect(key, fx, scope) in a running app affects only the keyed element"):
    val app     = new TuiApp:
      override def bindings: KeyBindings            =
        KeyBindings(binding("f", "flash")(runEffect("flash", embolden, scope = "panel")))
      def view(using ReactiveScope, Theme): Element =
        column(text("top").length(1), text("panel").key("panel").length(1), text("bottom").length(1))
    val backend = HeadlessBackend(Size(8, 3))
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    pilot.press("f").waitForIdle()
    val frame   = pilot.lastFrame
    assert((0 until 5).forall(bold(frame, _, 1)))
    assert(!(0 until 8).exists(bold(frame, _, 0)) && !(0 until 8).exists(bold(frame, _, 2)))
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("withEffect(fx) timed from the element's first frame paints over that element only, inside a running app"):
    val app     = new TuiApp:
      def view(using ReactiveScope, Theme): Element =
        column(text("plain").length(1), text("flash").withEffect(embolden).length(1))
    val backend = HeadlessBackend(Size(8, 2))
    val pilot   = startPilot(backend)(app.runWith(backend)).waitForIdle()
    val frame   = pilot.lastFrame
    assert((0 until 8).forall(bold(frame, _, 1)))
    assert(!(0 until 8).exists(bold(frame, _, 0)))
    pilot.interrupt()
    val _       = pilot.awaitTermination()

  test("services without a running app accept a scoped effect and do nothing"):
    AppServices.NoOp.runEffect("flash", embolden, scope = "panel")
