package io.worxbend.tui.runtime

import java.util.concurrent.{Future, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/** Optional runner-owned task group using Async's shared daemon executors, never a pool per screen.
  *
  * Create through [[Async.scope]] on the owner. Submission, cancellation and close are thread-safe; callbacks always
  * return to that captured owner. Closing is idempotent and rejects subsequent submissions. Owner retirement closes the
  * group automatically; close it earlier when a screen leaves. A new app run needs a new scope.
  *
  * Cancellation suppresses callbacks not yet admitted by their delivery guard and skips worker work not yet started. It
  * does NOT interrupt or join blocking work, undo side effects, or stop an already admitted callback. Supply IO
  * timeouts/cooperative cancellation yourself. This is best-effort cancellation, not structured blocking concurrency.
  */
final class TaskScope private[runtime] (owner: RenderThread.RenderLoop) extends AutoCloseable:
  private val closed = AtomicBoolean(false)
  private val tasks  = mutable.Set.empty[Task]

  /** Delivers success or non-fatal failure on this scope's owner; cancellation suppresses either outcome. */
  def runCatching[A](work: => A)(onDone: Either[Throwable, A] => Unit): Cancelable =
    val task = register()
    Async.onWorker {
      if task.active then
        val result =
          try Right(work)
          catch case NonFatal(error) => Left(error)
        owner.enqueue(() => task.deliver(onDone(result), repeat = false))
    }
    task

  /** One owner-thread callback after the delay, guarded even if already queued when cancelled. */
  def after(delay: FiniteDuration)(body: => Unit): Cancelable =
    schedule(body, repeat = false)(callback =>
      Async.scheduler.schedule(callback, delay.toMillis, TimeUnit.MILLISECONDS)
    )

  /** Repeated owner-thread callbacks. Intervals below one millisecond are rounded up. */
  def every(interval: FiniteDuration)(body: => Unit): Cancelable =
    val millis = math.max(1L, interval.toMillis)
    schedule(body, repeat = true)(callback =>
      Async.scheduler.scheduleAtFixedRate(callback, millis, millis, TimeUnit.MILLISECONDS)
    )

  private def schedule(body: => Unit, repeat: Boolean)(submit: Runnable => Future[?]): Cancelable =
    val task = register()
    try task.install(submit(() => owner.enqueue(() => task.deliver(body, repeat))))
    catch
      case NonFatal(error) =>
        task.cancel()
        throw error
    task

  private def register(): Task = synchronized {
    if closed.get() || owner.isClosed then throw IllegalStateException("task scope is closed")
    val task = new Task
    tasks += task
    task
  }

  private def remove(task: Task): Unit = synchronized {
    val _ = tasks.remove(task)
  }

  override def close(): Unit =
    if closed.compareAndSet(false, true) then
      val pending = synchronized {
        val snapshot = tasks.toList
        tasks.clear()
        snapshot
      }
      pending.foreach(_.cancel())
      owner.detach(this)

  private final class Task extends Cancelable:
    private val cancelled                    = AtomicBoolean(false)
    private var scheduled: Option[Future[?]] = None

    def active: Boolean = !cancelled.get() && !closed.get() && !owner.isClosed

    def install(future: Future[?]): Unit = synchronized {
      if active then scheduled = Some(future)
      else
        val _ = future.cancel(false)
    }

    def deliver(body: => Unit, repeat: Boolean): Unit =
      try if active then body
      finally if !repeat then cancel()

    def cancel(): Unit =
      cancelled.set(true)
      synchronized {
        scheduled.foreach(future => { val _ = future.cancel(false) })
        scheduled = None
      }
      remove(this)
