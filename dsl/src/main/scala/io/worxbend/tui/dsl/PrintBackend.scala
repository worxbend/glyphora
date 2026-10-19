package io.worxbend.tui.dsl

import io.worxbend.tui.core.{Buffer, Event, Size}
import io.worxbend.tui.runtime.RenderThread
import io.worxbend.tui.terminal.{Backend, BackendError, HeadlessBackend}

import scala.concurrent.duration.{Duration, FiniteDuration}

/** The backend a no-TTY run drives: a [[HeadlessBackend]] at the target size, with every flushed frame handed to
  * `onFrame` and the first wait for input turned into the end of the run.
  *
  * No keystroke is ever going to arrive in a process without a terminal, so the moment the loop asks for one — after
  * `onStart`, the first frame and whatever queued work followed — the run is complete: `endRun` (the app's own
  * `quit()`) is called and the read answers "nothing yet", which the loop reads back as "quit, exit cleanly". That is
  * what makes an app that only ever exits on `q` exit `0` under `PrintFinalFrame` instead of hanging a CI job. Ticks
  * and background work are not waited for: a print is the app's initial state, not a recording — unless `idleTimeout`
  * is set (`NonInteractivePolicy.awaitIdle`), in which case a wait for input ends the run only once the loop is idle
  * (nothing queued or reserved on the render loop, and no frame drawn since the previous wait) or the timeout has
  * passed since this backend was created; until then the wait is real, cut short by [[wake]], so ticks fire and `Async`
  * results land and repaint.
  *
  * Owned by `TuiApp.run()` for exactly one run; the runner calls every method on the render thread. Everything but
  * `draw` and `readEvent` is the headless backend's own behaviour, delegated rather than inherited because
  * `HeadlessBackend` is final.
  */
private[dsl] final class PrintBackend(
    frameSize: Size,
    onFrame: Buffer => Unit,
    endRun: () => Unit,
    idleTimeout: Option[FiniteDuration] = None,
) extends Backend:

  private val inner                  = HeadlessBackend(frameSize)
  private val deadline               = idleTimeout.map(limit => System.nanoTime() + limit.toNanos)
  private var drewSinceWait: Boolean = false // render thread only

  // the monitor a waiting `readEvent` parks on and `wake` notifies, from any thread
  private val wakeSignal     = Object()
  private var woken: Boolean = false // guarded by wakeSignal

  def size: Either[BackendError, Size] = inner.size

  /** Records the frame headlessly, then hands a snapshot to the sink — a snapshot, because the runner reuses the buffer
    * for the next frame and a `PrintFinalFrame` sink holds on to it.
    */
  def draw(buffer: Buffer): Either[BackendError, Unit] =
    drewSinceWait = true
    inner.draw(buffer).map(_ => onFrame(buffer.snapshot))

  /** Ends the run — see the class notes, and for `idleTimeout` waits first — and reports no event. */
  def readEvent(timeout: Duration): Either[BackendError, Option[Event]] =
    deadline match
      case None      => endRun()
      case Some(end) =>
        val now     = System.nanoTime()
        val settled = !drewSinceWait && RenderThread.capture().isIdle
        drewSinceWait = false
        if settled || now >= end then endRun()
        else
          val budget = if timeout.isFinite then math.min(timeout.toNanos, end - now) else end - now
          await(budget)
    Right(None)

  /** Cuts a waiting [[readEvent]] short; safe from any thread. */
  override def wake(): Unit = wakeSignal.synchronized {
    woken = true
    wakeSignal.notifyAll()
  }

  private def await(nanos: Long): Unit =
    wakeSignal.synchronized {
      val until = System.nanoTime() + nanos
      var left  = nanos
      try
        while !woken && left > 0 do
          wakeSignal.wait(math.max(1L, left / 1_000_000L))
          left = until - System.nanoTime()
      catch case _: InterruptedException => Thread.currentThread().interrupt()
      woken = false
    }

  def enableRawMode(): Either[BackendError, Unit]        = inner.enableRawMode()
  def disableRawMode(): Either[BackendError, Unit]       = inner.disableRawMode()
  def enterAlternateScreen(): Either[BackendError, Unit] = inner.enterAlternateScreen()
  def leaveAlternateScreen(): Either[BackendError, Unit] = inner.leaveAlternateScreen()
  def enableMouseCapture(): Either[BackendError, Unit]   = inner.enableMouseCapture()
  def disableMouseCapture(): Either[BackendError, Unit]  = inner.disableMouseCapture()
  def hideCursor(): Either[BackendError, Unit]           = inner.hideCursor()
  def showCursor(): Either[BackendError, Unit]           = inner.showCursor()
  def close(): Either[BackendError, Unit]                = inner.close()
