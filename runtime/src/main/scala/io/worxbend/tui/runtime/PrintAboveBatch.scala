package io.worxbend.tui.runtime

import java.util.concurrent.RejectedExecutionException

/** The `CaptureStdio.PrintAbove` sink: lines captured from any thread collect here, and one queued render-thread task
  * hands everything collected so far to `print` in a single call — one scrollback trip per drain of the work queue,
  * which is once per frame, rather than one per line.
  *
  * A line never waits on another thread's frame: the first line of a batch schedules the flush, every later line until
  * it runs just joins it. A line the loop cannot take (closed, or its queue full) goes to `fallback` together with
  * whatever was already waiting, so order is kept and nothing is lost; [[drainToFallback]] does the same at teardown
  * for a batch whose flush the closing loop discarded.
  *
  * Threads: [[offer]] is safe from any thread; the scheduled flush runs on the render thread, which is the only thread
  * `print` is called on.
  */
private[runtime] final class PrintAboveBatch(
    loop: RenderThread.RenderLoop,
    print: Seq[String] => Unit,
    fallback: String => Unit,
):

  private val lock                    = Object()
  private var pending: Vector[String] = Vector.empty // guarded by lock
  private var flushScheduled: Boolean = false        // guarded by lock

  /** Adds one completed line to the batch, scheduling the batch's flush when it is the first line of it. */
  def offer(line: String): Unit =
    val schedule = lock.synchronized {
      pending = pending :+ line
      val first = !flushScheduled
      flushScheduled = true
      first
    }
    if schedule then
      val accepted =
        try loop.later(() => flush())
        catch case _: RejectedExecutionException => false
      if !accepted then drainTo(fallback)

  /** Prints every line collected so far in one call. Render thread. */
  private def flush(): Unit =
    val lines = take()
    if lines.nonEmpty then print(lines)

  /** Hands every line still waiting to `sink`, one at a time, in order — the teardown path, and the path for a batch
    * the loop would not take.
    */
  def drainTo(sink: String => Unit): Unit = take().foreach(sink)

  /** [[drainTo]] the fallback — the teardown path. */
  def drainToFallback(): Unit = drainTo(fallback)

  private def take(): Vector[String] =
    lock.synchronized {
      val lines = pending
      pending = Vector.empty
      flushScheduled = false
      lines
    }
