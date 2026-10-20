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
    fallback: (String, Boolean) => Unit,
):

  private val lock = Object()
  private final case class CapturedLine(text: String, stderr: Boolean)

  private var pending: Vector[CapturedLine] = Vector.empty // guarded by lock
  private var flushScheduled: Boolean       = false        // guarded by lock

  /** Adds one completed line to the batch, scheduling the batch's flush when it is the first line of it. */
  def offer(line: String, stderr: Boolean = false): Unit =
    val schedule = lock.synchronized {
      pending = pending :+ CapturedLine(line, stderr)
      val first = !flushScheduled
      flushScheduled = true
      first
    }
    if schedule then
      val accepted =
        try loop.later(() => flush())
        catch case _: RejectedExecutionException => false
      if !accepted then drainToFallback()

  /** Prints every line collected so far in one call. Render thread. */
  def flush(): Unit =
    val lines = take()
    if lines.nonEmpty then print(lines.map(_.text))

  /** Flushes waiting lines to their original channels, in order, after the loop retires or refuses admission. */
  def drainToFallback(): Unit = take().foreach(line => fallback(line.text, line.stderr))

  private def take(): Vector[CapturedLine] =
    lock.synchronized {
      val lines = pending
      pending = Vector.empty
      flushScheduled = false
      lines
    }
