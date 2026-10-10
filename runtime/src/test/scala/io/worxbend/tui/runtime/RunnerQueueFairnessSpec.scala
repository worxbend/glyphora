package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Event, Size}
import io.worxbend.tui.terminal.{Backend, BackendError, HeadlessBackend}

import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{Duration, DurationInt}

final class RunnerQueueFairnessSpec extends AnyFunSuite:

  private final class PollSpy extends Backend:
    val terminal                                                          = HeadlessBackend(Size(10, 2))
    val timeouts                                                          = ArrayBuffer.empty[Duration]
    export terminal.{
      size,
      draw,
      enableRawMode,
      disableRawMode,
      enterAlternateScreen,
      leaveAlternateScreen,
      enableMouseCapture,
      disableMouseCapture,
      hideCursor,
      showCursor,
      close,
    }
    def readEvent(timeout: Duration): Either[BackendError, Option[Event]] =
      timeouts.append(timeout)
      Right(None)

  test("an existing backlog keeps turns runnable without waiting for unrelated input"):
    val backend = PollSpy()
    val seen    = ArrayBuffer.empty[Int]
    val result  = TerminalRunner(backend).run(
      handle =>
        (1 to 1024).foreach { i =>
          RenderThread.runLater {
            seen.append(i)
            if i == 1024 then handle.quit()
          }
        },
      (_, _) => EventOutcome.Ignored,
      _ => (),
    )
    assert(result == Right(()))
    assert(seen.toList == (1 to 1024).toList)
    assert(backend.timeouts.nonEmpty)
    assert(backend.timeouts.forall(t => t > Duration.Zero && t <= 1.millis), backend.timeouts.toString)

  test("self-requeueing work yields to repaint, pending input and due ticks"):
    val backend   = HeadlessBackend(Size(10, 2))
    backend.postEvent(Event.Resize(Size(10, 2)))
    var callbacks = 0
    var atInput   = 0
    var atTick    = 0
    var now       = 0L
    val painted   = ArrayBuffer.empty[Int]
    val result    = TerminalRunner(backend, RunnerConfig(tickRate = Some(1.millis)), () => now).run(
      handle =>
        def work(): Unit =
          callbacks += 1
          now += 1000000L
          handle.requestRedraw()
          if callbacks < 10000 then RenderThread.runLater(work())
        RenderThread.runLater(work())
      ,
      (event, handle) =>
        event match
          case _: Event.Resize => atInput = callbacks
          case Event.Tick      =>
            atTick = callbacks
            handle.quit()
          case _               => ()
        EventOutcome.Ignored
      ,
      _ => { val _ = painted.append(callbacks) },
    )
    assert(result == Right(()))
    assert(atInput > 0 && atInput <= 256)
    assert(atTick == atInput)
    assert(painted.exists(n => n > 0 && n <= 256))
    assert(callbacks <= 512, "the final drain must also be bounded")

  test("stop requested inside a callback reaches teardown with bounded final work"):
    val backend                                = HeadlessBackend(Size(10, 2))
    var callbacks                              = 0
    var stoppedAt                              = 0
    var owner: Option[RenderThread.RenderLoop] = None
    val result                                 = TerminalRunner(
      backend,
      onStop = () => {
        stoppedAt = callbacks
        assert(RenderThread.capture() eq owner.get)
        assert(backend.isRawMode)
      },
    ).run(
      _ =>
        val loop         = RenderThread.capture()
        owner = Some(loop)
        def work(): Unit =
          callbacks += 1
          loop.requestStop()
          loop.enqueue(() => work())
        loop.enqueue(() => work())
      ,
      (_, _) => fail("stopped runners must not dispatch input"),
      _ => (),
    )
    assert(result == Right(()))
    assert(stoppedAt > 0 && stoppedAt <= 512)
    assert(!backend.isRawMode)
    assert(owner.exists(loop => !loop.execute(())))

  test("startup quit gives the final drain one finite batch and aggregates its errors"):
    val backend   = HeadlessBackend(Size(10, 2))
    val failure   = IllegalStateException("queued")
    val cleanup   = IllegalStateException("cleanup")
    var callbacks = 0
    val result    = TerminalRunner(backend, onStop = () => throw cleanup).run(
      handle =>
        def work(): Unit =
          callbacks += 1
          RenderThread.runLater(work())
          throw failure
        RenderThread.runLater(work())
        handle.quit()
      ,
      (_, _) => EventOutcome.Ignored,
      _ => fail("startup quit must not paint"),
    )
    assert(callbacks == 256)
    result match
      case Left(error @ RunnerError.QueuedTask(failures)) =>
        assert(failures.first eq failure)
        assert(failures.count == callbacks)
        assert(error.cleanupFailures == Vector(cleanup))
      case other                                          => fail(s"expected aggregated queue failures, got $other")
    assert(!backend.isRawMode)
