package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Event, MouseEvent, MouseEventKind, Position, Size}
import io.worxbend.tui.terminal.HeadlessBackend

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CyclicBarrier, TimeUnit}

final class DragRaceRegressionSpec extends AnyFunSuite with PilotFixture:

  test("shared built-in DragKeys isolate interleaved payload loads on two live runner threads"):
    val barrier            = CyclicBarrier(2)
    val key                = summon[DragKey[String]]
    def start(tag: String) =
      var received = Vector.empty[String]
      val packed   = key.pack(tag)
      // Gate the real pack/load path after its typed delivery, forcing both runner threads to overlap.
      val gated    = new DragPayload(
        key,
        tag,
        () => {
          packed.load()
          val _ = barrier.await(5, TimeUnit.SECONDS)
        },
      )
      val target   = DropTarget.of(key, _ => true, value => received :+= value)
      val app      = new TuiApp:
        def view(using ReactiveScope, Theme): Element = text(tag).onMouse { ctx =>
          if ctx.event.kind == MouseEventKind.Down then target.drop(gated) else true
        }
      val backend  = HeadlessBackend(Size(10, 2))
      val pilot    = startPilot(backend)(app.runWith(backend)).waitForIdle()
      (pilot, () => received)
    val (a, receivedA)     = start("A")
    val (b, receivedB)     = start("B")
    val down               = Event.Mouse(MouseEvent(Position(0, 0), MouseEventKind.Down, KeyModifiers.None))
    a.backend.postEvent(down)
    b.backend.postEvent(down)
    a.waitForIdle()
    b.waitForIdle()
    assert(a.readOnRenderThread(receivedA()) == Vector("A"))
    assert(b.readOnRenderThread(receivedB()) == Vector("B"))
