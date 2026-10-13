package io.worxbend.tui.runtime

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/** A caller-owned, serial off-thread frame observer with one replaceable pending frame.
  *
  * Use `RunnerConfig(onFrame = Some(observer))`. Construction starts one daemon worker, not a task per frame. Admission
  * does not run user code: at most one frame is admitted to the callback and one is pending. A newer pending frame
  * replaces the previous one (explicit latest-frame coalescing), so this is sampling, not a lossless recording API.
  * Delivered frames follow admission order, with gaps for replaced frames. The callback and error handler run serially
  * on that worker, never on the render thread; they must not mutate render-confined state or perform terminal I/O.
  *
  * `onError` is required. A non-fatal callback failure is passed to it on the worker; if it returns, delivery
  * continues. A throwing error handler, or a fatal callback failure, escapes to the thread's uncaught-exception handler
  * and retires the observer, dropping pending delivery. It is not swallowed by an executor Future and does not become a
  * runner error. Choose reporting explicitly (and marshal any UI reaction through a previously captured owner
  * dispatcher).
  *
  * The caller must [[close]] this observer, for example from `TerminalRunner`'s `onStop` hook or a `finally` block.
  * Close rejects future admission, drops pending delivery and wakes the worker without waiting or interrupting user
  * code. A callback admitted before close may still start or finish afterward, together with its error reporting. An
  * indefinitely blocked callback can therefore retain its worker and frame; close cannot forcibly end user code.
  *
  * Frames supplied by the runner already contain independent buffer snapshots. No second copy is made here: treat the
  * frame's publicly mutable buffer as read-only after admission, including from other threads holding that frame.
  */
final class LatestFrameObserver(observe: CompletedFrame => Unit, onError: Throwable => Unit)
    extends (CompletedFrame => Unit)
    with AutoCloseable:

  private val monitor = new Object

  private var pending: Option[CompletedFrame] = None
  private var closed                          = false
  private val worker                          = new Thread(() => run(), "glyphora-frame-observer")
  worker.setDaemon(true)
  worker.start()

  /** Offers one already-snapshotted frame, replacing any pending frame. Offers after close are ignored. */
  def apply(frame: CompletedFrame): Unit = monitor.synchronized {
    if !closed then
      pending = Some(frame)
      monitor.notifyAll()
  }

  /** Idempotently rejects admission and drops pending delivery; never joins or interrupts the worker. */
  def close(): Unit = monitor.synchronized {
    closed = true
    pending = None
    monitor.notifyAll()
  }

  /** Waits at most `timeout` for the worker (including uncaught error handling) to exit. Does not close it.
    *
    * A non-positive timeout only checks termination. Returns false if still running; interruption propagates to the
    * caller. Use off the UI thread, and not from the observer's own callback. Unlike close, this deliberately waits.
    */
  def awaitTermination(timeout: FiniteDuration): Boolean =
    worker.join(java.time.Duration.ofNanos(math.max(0L, timeout.toNanos)))

  private def take(): Option[CompletedFrame] = monitor.synchronized {
    while pending.isEmpty && !closed do monitor.wait()
    val next = pending
    pending = None
    next
  }

  private def run(): Unit =
    try
      var next = take()
      while next.nonEmpty do
        try next.foreach(observe)
        catch case NonFatal(error) => onError(error)
        next = take()
    finally close()
