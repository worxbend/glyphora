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
  *
  * Each task reserves one owner callback slot before worker/timer submission. Overload of that owner or the shared
  * worker backlog throws `RejectedExecutionException` synchronously (not a `Left`); rejected tasks are unregistered and
  * reserve no capacity. Timers coalesce to one pending/running delivery and hold their slot until cancellation or
  * one-shot completion. Cancellation removes queued deliveries and releases the reservation, but a cancelled worker
  * entry still waits in the bounded worker queue until dequeued, where its work is skipped.
  */
final class TaskScope private[runtime] (owner: RenderThread.RenderLoop) extends AutoCloseable:
  private val closed      = AtomicBoolean(false)
  private val tasks       = mutable.Set.empty[Task]
  // the newest task started under each `latest` key; guarded by `monitor` like `tasks`
  private val latestByKey = mutable.Map.empty[Any, Task]
  // guards `tasks`; a private monitor rather than `this`, which is public through `Async.scope()`
  private val monitor     = new Object

  /** Delivers success or non-fatal failure on this scope's owner; cancellation suppresses either outcome. Dispatch
    * failures escape to the worker's uncaught handler without revoking a result already accepted by the owner queue.
    */
  def runCatching[A](work: => A)(onDone: Either[Throwable, A] => Unit): Cancelable =
    submit(register(None), work)(onDone)

  /** [[runCatching]] under a key, latest-wins: starting a task whose key already has one in flight cancels that earlier
    * task, and its result is discarded even if its work was already running or its callback already queued — only the
    * task that is still the newest under `key` when its result reaches the owner is delivered. Each key therefore
    * delivers at most one result per start, in start order, and never a result for a start that has been superseded.
    *
    * This is what a search box, a "reload on every keystroke" or a reset-and-restart needs instead of a generation
    * counter checked by hand in the continuation: the scope does the stamp-and-compare. Keys are compared with `==`; a
    * completed, failed or cancelled task releases its key, so the next start under it runs normally.
    *
    * Same cancellation contract as the rest of the scope — best effort, no interruption: the superseded work keeps
    * running to completion on its worker, it just reaches nobody. Give long work a cooperative check of its own if
    * finishing it is itself expensive.
    */
  def latest[A](key: Any)(work: => A)(onDone: Either[Throwable, A] => Unit): Cancelable =
    val task     = register(Some(key))
    val previous = monitor.synchronized(latestByKey.put(key, task))
    previous.foreach(_.cancel())
    submit(task, work)(onDone)

  private def submit[A](task: Task, work: => A)(onDone: Either[Throwable, A] => Unit): Cancelable =
    try
      val delivery = owner.reserve()
      task.installDelivery(delivery)
      Async.onWorker {
        if task.active then
          val result =
            try Right(work)
            catch
              case NonFatal(error)  => Left(error)
              case error: Throwable =>
                task.cancel()
                throw error
          // enqueue accepts before wakeup: a throwing wake must not revoke the queued result.
          delivery.enqueue(() => task.deliver(onDone(result), repeat = false))
      }
    catch
      case NonFatal(error) =>
        task.cancel()
        throw error
    task

  /** One owner-thread callback after the delay, guarded even if already queued when cancelled. */
  def after(delay: FiniteDuration)(body: => Unit): Cancelable =
    schedule(body, repeat = false)(callback =>
      Async.scheduler.schedule(callback, delay.toMillis, TimeUnit.MILLISECONDS)
    )

  /** Repeated owner-thread callbacks. Intervals below one millisecond are rounded up; ticks while delivery is pending
    * or running coalesce, rather than accumulating for replay.
    */
  def every(interval: FiniteDuration)(body: => Unit): Cancelable =
    val millis = math.max(1L, interval.toMillis)
    schedule(body, repeat = true)(callback =>
      Async.scheduler.scheduleAtFixedRate(callback, millis, millis, TimeUnit.MILLISECONDS)
    )

  private def schedule(body: => Unit, repeat: Boolean)(submitTimer: Runnable => Future[?]): Cancelable =
    val task = register(None)
    try
      val delivery = owner.reserve()
      task.installDelivery(delivery)
      task.install(
        submitTimer(() =>
          // a firing that finds the task inactive (owner retired, scope closed, or cancelled under a racing close)
          // retires it rather than firing on; a throwing wake is reported instead of ending the periodic task inside
          // its own future — see `Async.scheduleCancelable`, whose runnable makes the same two moves
          if !task.active then task.cancel()
          else
            try
              if repeat then delivery.enqueueRepeating(() => task.deliver(body, repeat))
              else delivery.enqueue(() => task.deliver(body, repeat))
            catch case NonFatal(error) => Async.reportDispatchFailure(error)
        )
      )
    catch
      case NonFatal(error) =>
        task.cancel()
        throw error
    task

  private def register(key: Option[Any]): Task = monitor.synchronized {
    if closed.get() || owner.isClosed then throw IllegalStateException("task scope is closed")
    val task = new Task(key)
    tasks += task
    task
  }

  /** Forgets a finished or cancelled task — and releases its `latest` key, but only if that key still points at *this*
    * task: a superseded task being removed must not take the newer task's registration with it.
    */
  private def remove(task: Task): Unit = monitor.synchronized {
    val _ = tasks.remove(task)
    task.key.foreach { key =>
      if latestByKey.get(key).exists(_ eq task) then
        val _ = latestByKey.remove(key)
    }
  }

  override def close(): Unit =
    if closed.compareAndSet(false, true) then
      val pending = monitor.synchronized {
        val snapshot = tasks.toList
        tasks.clear()
        latestByKey.clear()
        snapshot
      }
      pending.foreach(_.cancel())
      owner.detach(this)

  private final class Task(val key: Option[Any]) extends Cancelable:
    private val cancelled                        = AtomicBoolean(false)
    // the task's own monitor, for its own two slots; never held while taking the scope's
    private val monitor                          = new Object
    private var scheduled: Option[Future[?]]     = None
    private var delivery: Option[owner.Delivery] = None

    def active: Boolean = !cancelled.get() && !closed.get() && !owner.isClosed

    def installDelivery(reservation: owner.Delivery): Unit = monitor.synchronized {
      if active then delivery = Some(reservation)
      else reservation.cancel()
    }

    def install(future: Future[?]): Unit = monitor.synchronized {
      if active then scheduled = Some(future)
      else
        val _ = future.cancel(false)
    }

    def deliver(body: => Unit, repeat: Boolean): Unit =
      try if active then body
      finally if !repeat then cancel()

    def cancel(): Unit =
      cancelled.set(true)
      monitor.synchronized {
        scheduled.foreach(future => { val _ = future.cancel(false) })
        scheduled = None
        delivery.foreach(_.cancel())
        delivery = None
      }
      remove(this)
