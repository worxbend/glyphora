package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Buffer, Event, Position, Size}
import io.worxbend.tui.terminal.{Backend, BackendError, HeadlessBackend}

import org.scalatest.funsuite.AnyFunSuite

final class FrameTimingSpec extends AnyFunSuite:

  private final class TimedBackend(cursorFailure: Option[BackendError] = None) extends Backend:
    val delegate                                         = HeadlessBackend(Size(4, 1))
    var now: Long                                        = 0L
    val phases                                           = scala.collection.mutable.ArrayBuffer.empty[String]
    export delegate.{
      close,
      disableMouseCapture,
      disableRawMode,
      enableMouseCapture,
      enableRawMode,
      enterAlternateScreen,
      hideCursor,
      leaveAlternateScreen,
      readEvent,
      showCursor,
    }
    def size: Either[BackendError, Size]                 =
      now += 3L
      delegate.size
    def draw(buffer: Buffer): Either[BackendError, Unit] =
      phases += "draw"
      now += 11L
      delegate.draw(buffer)
    override def setCursorPosition(position: Position): Either[BackendError, Unit] =
      phases += "cursor"
      now += 17L
      cursorFailure match
        case Some(error) => Left(error)
        case None        => delegate.setCursorPosition(position)

  private def dispatch(event: Event, handle: RunnerHandle): EventOutcome =
    if event == Event.EndOfInput then
      handle.quit()
      EventOutcome.Ignored
    else EventOutcome.Redraw

  test("opt-in timing measures elapsed composition, synchronous draw, and snapshot observer phases"):
    val backend = TimedBackend()
    val timings = scala.collection.mutable.ArrayBuffer.empty[FrameTiming]
    val owner   = Thread.currentThread()
    backend.delegate.postEvent(Event.Tick)
    backend.delegate.postEvent(Event.EndOfInput)
    val result  = TerminalRunner(
      backend,
      RunnerConfig(
        onFrame = Some(frame => {
          assert(Thread.currentThread() eq owner)
          assert(backend.delegate.drawCount == frame.count + 1L)
          backend.phases += "observe"
          backend.now += 13L
        }),
        onFrameTiming = Some(timing => {
          assert(Thread.currentThread() eq owner)
          backend.phases += "timing"
          val _ = timings += timing
        }),
      ),
      nanoTime = () => backend.now,
    ).run(
      _ => (),
      dispatch,
      frame => {
        backend.phases += "compose"
        backend.now += 5L
        frame.setCursorPosition(Position(0, 0))
      },
    )
    assert(result == Right(()))
    assert(timings.toVector == Vector(FrameTiming(0L, 8L, 11L, 13L), FrameTiming(1L, 8L, 11L, 13L)))
    assert(backend.phases.toVector == Vector.fill(2)(Vector("compose", "draw", "observe", "cursor", "timing")).flatten)

  test("disabled timing adds no clock reads even with synchronous frame observation"):
    val backend    = HeadlessBackend(Size(4, 1))
    var clockReads = 0
    var observed   = 0
    backend.postEvent(Event.Tick)
    backend.postEvent(Event.EndOfInput)
    val result     = TerminalRunner(
      backend,
      RunnerConfig(onFrame = Some(_ => observed += 1)),
      nanoTime = () => { clockReads += 1; 0L },
    ).run(_ => (), dispatch, _ => ())
    assert(result == Right(()))
    assert(observed == 2)
    // TickSchedule already takes one initial reading; with no ticks, every other read would be profiling overhead.
    assert(clockReads == 1)

  test("timing without a frame observer reports zero observer time"):
    val backend                     = TimedBackend()
    var timing: Option[FrameTiming] = None
    backend.delegate.postEvent(Event.EndOfInput)
    val result                      = TerminalRunner(
      backend,
      RunnerConfig(onFrameTiming = Some(value => timing = Some(value))),
      nanoTime = () => backend.now,
    ).run(_ => (), dispatch, _ => backend.now += 5L)
    assert(result == Right(()))
    assert(timing.contains(FrameTiming(0L, 8L, 11L, 0L)))

  test("queued work, event handling and the previous timing callback are excluded from the next frame"):
    val backend = TimedBackend()
    val timings = scala.collection.mutable.ArrayBuffer.empty[FrameTiming]
    backend.delegate.postEvent(Event.Tick)
    backend.delegate.postEvent(Event.EndOfInput)
    val result  = TerminalRunner(
      backend,
      RunnerConfig(onFrameTiming = Some(value => {
        val _ = timings += value
        backend.now += 1000L
      })),
      nanoTime = () => backend.now,
    ).run(
      _ => RenderThread.runLater { backend.now += 2000L },
      (event, handle) => {
        backend.now += 3000L
        dispatch(event, handle)
      },
      _ => backend.now += 5L,
    )
    assert(result == Right(()))
    assert(timings.toVector == Vector(FrameTiming(0L, 8L, 11L, 0L), FrameTiming(1L, 8L, 11L, 0L)))

  test("failed composition delivers neither a draw, a snapshot nor a timing"):
    val backend  = HeadlessBackend(Size(4, 1))
    val failure  = IllegalStateException("compose failed")
    var observed = false
    var timed    = false
    val result   = TerminalRunner(
      backend,
      RunnerConfig(onFrame = Some(_ => observed = true), onFrameTiming = Some(_ => timed = true)),
    ).run(_ => (), dispatch, _ => throw failure)
    assert(result == Left(RunnerError.Handler(failure)))
    assert(backend.drawCount == 0L)
    assert(!observed)
    assert(!timed)
    assert(!backend.isRawMode)
    assert(!backend.isAlternateScreen)

  test("a throwing timing callback remains a synchronous runner handler failure"):
    val backend = HeadlessBackend(Size(4, 1))
    val failure = IllegalStateException("timing failed")
    val result  = TerminalRunner(
      backend,
      RunnerConfig(onFrameTiming = Some(_ => throw failure)),
    ).run(_ => (), dispatch, _ => ())
    assert(result == Left(RunnerError.Handler(failure)))
    assert(backend.drawCount == 1L)
    assert(!backend.isRawMode)
    assert(!backend.isAlternateScreen)

  test("failed draws do not deliver timings or frame observers"):
    val backend  = HeadlessBackend(Size(4, 1))
    val failure  = BackendError.Io(java.io.IOException("draw failed"))
    var observed = false
    var timed    = false
    backend.failNext(HeadlessBackend.Op.Draw, failure)
    val result   = TerminalRunner(
      backend,
      RunnerConfig(onFrame = Some(_ => observed = true), onFrameTiming = Some(_ => timed = true)),
    ).run(_ => (), dispatch, _ => ())
    assert(result == Left(RunnerError.Backend(failure)))
    assert(!observed)
    assert(!timed)

  test("failed cursor placement preserves the completed-frame observer but prevents timing delivery"):
    val failure  = BackendError.Io(java.io.IOException("cursor failed"))
    val backend  = TimedBackend(Some(failure))
    var observed = false
    var timed    = false
    val result   = TerminalRunner(
      backend,
      RunnerConfig(
        onFrame = Some(_ => {
          observed = true
          backend.phases += "observe"
        }),
        onFrameTiming = Some(_ => timed = true),
      ),
      nanoTime = () => backend.now,
    ).run(_ => (), dispatch, _.setCursorPosition(Position(0, 0)))
    assert(result == Left(RunnerError.Backend(failure)))
    assert(backend.delegate.drawCount == 1L)
    assert(observed)
    assert(!timed)
    assert(backend.phases.toVector == Vector("draw", "observe", "cursor"))
    assert(!backend.delegate.isRawMode)
    assert(!backend.delegate.isAlternateScreen)

  test("a throwing frame observer prevents timing delivery and keeps its existing failure semantics"):
    val backend = HeadlessBackend(Size(4, 1))
    val failure = IllegalStateException("frame failed")
    var timed   = false
    val result  = TerminalRunner(
      backend,
      RunnerConfig(onFrame = Some(_ => throw failure), onFrameTiming = Some(_ => timed = true)),
    ).run(_ => (), dispatch, _ => ())
    assert(result == Left(RunnerError.Handler(failure)))
    assert(!timed)
