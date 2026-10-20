package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Event, Size}

import java.io.InterruptedIOException
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

/** The wake/poll machinery behind [[JLine3Backend.readEvent]] and [[JLine3Backend.wake]]: the pending events the signal
  * handlers post, and the interruptible blocking read they are posted to.
  *
  * Threading: [[poll]] runs on the render thread alone — it is the one reader of the decoder. [[postResize]],
  * [[postInterrupt]] and [[wake]] are safe from any thread, which is what JLine's signal-dispatch thread and the
  * runner's background work call them from.
  */
private[terminal] final class EventPump(decoder: InputDecoder):

  private val pendingResize    = AtomicReference[Option[EventPump.PendingResize]](None)
  private val pendingInterrupt = AtomicBoolean(false)
  private val woken            = AtomicBoolean(false)
  // the thread currently parked in `blockingRead`, if any, so `wake` knows whom to interrupt
  private val pollingThread    = AtomicReference[Option[Thread]](None)

  /** The size of the last `Event.Resize` [[poll]] delivered. Read and written by [[poll]] alone, so render-thread
    * confined.
    *
    * One resize on a mode 2048 terminal arrives twice — as `SIGWINCH` and as the in-band report on the input stream —
    * in either order. A resize to the size the runner was just told about is that second copy: delivering it composes
    * one extra frame for nothing, so it is dropped here, whichever of the two sources it came from.
    */
  private var lastDelivered: Option[Size] = None

  private val readerOwnership  = Object()
  @volatile private var paused = false

  /** Stop admitting reads, interrupt the current decoder read, and wait for that reader to relinquish ownership.
    * Decoder state and posted signals deliberately survive the handover, including an interrupted paste.
    */
  def pause(): Unit =
    paused = true
    wake()
    readerOwnership.synchronized(())

  def resume(): Unit =
    paused = false
    wake()

  /** Queues the coalesced resize the next [[poll]] reports, and wakes an in-flight poll so it is seen promptly. Safe
    * from any thread.
    */
  def postResize(size: Size): Unit =
    pendingResize.set(Some(EventPump.PendingResize(size, forced = false)))
    wake()

  /** Like [[postResize]], but delivered even when the size is the one the runner already has: `SIGCONT` posts this so a
    * terminal handed back after a suspend is repainted although its size never changed. Safe from any thread.
    */
  def postForcedResize(size: Size): Unit =
    pendingResize.set(Some(EventPump.PendingResize(size, forced = true)))
    wake()

  /** Queues the interrupt the next [[poll]] reports, and wakes an in-flight poll so it is seen promptly. Safe from any
    * thread.
    */
  def postInterrupt(): Unit =
    pendingInterrupt.set(true)
    wake()

  /** Blocks up to `timeout` for the next input event, draining anything posted while the caller was away first;
    * `Right(None)` means nothing arrived.
    *
    * `timeout` must be strictly positive, or infinite to block until an event arrives — [[Backend.readEvent]] documents
    * the contract, and the check lives here so it cannot be skipped by reaching for the read directly.
    */
  def poll(timeout: Duration): Either[BackendError, Option[Event]] =
    Backend.requirePositiveTimeout(timeout)
    if paused then Right(None)
    else if pendingInterrupt.getAndSet(false) then Right(Some(Event.Interrupt))
    else
      pendingResize.getAndSet(None) match
        case Some(EventPump.PendingResize(resized, forced)) =>
          if forced || !lastDelivered.contains(resized) then deliverResize(resized) else Right(None)
        case None                                           =>
          // something queued render-thread work while we were away: go round the loop instead of blocking again
          if woken.getAndSet(false) then Right(None)
          else
            blockingRead(timeout).map {
              case Some(Event.Resize(resized)) if lastDelivered.contains(resized) => None
              case Some(Event.Resize(resized)) => deliverResize(resized).toOption.flatten
              case other                       => other
            }

  private def deliverResize(size: Size): Either[BackendError, Option[Event]] =
    lastDelivered = Some(size)
    Right(Some(Event.Resize(size)))

  private def blockingRead(timeout: Duration): Either[BackendError, Option[Event]] = readerOwnership.synchronized {
    pollingThread.set(Some(Thread.currentThread()))
    // Re-checked *after* registering, closing the window this registration opens: a wake that landed between `poll`'s
    // check and this registration found nobody to interrupt, so without this second look the render thread parks for
    // the whole timeout — up to a tick interval, or 100 ms with no tick rate — before draining the work that wake was
    // announcing. That is exactly the latency `wake()` exists to remove.
    try
      if paused || woken.getAndSet(false) then Right(None)
      else Right(decoder.decode(JLine3Backend.readTimeoutMillis(timeout)))
    catch
      // The decoder keeps every interrupted sequence for itself — a paste or a control-string skip retains its
      // progress, a half-read sequence re-queues its prefix — so the only interrupt that reaches this boundary is one
      // on the read of an event's first byte, where nothing was consumed and a wake is also "nothing arrived", never an
      // I/O failure.
      case _: InterruptedIOException => Right(None)
      case NonFatal(error)           => Left(BackendError.Io(error))
    finally
      pollingThread.set(None) // no read is in flight any more: `wake` has nobody to interrupt
      val _ = Thread.interrupted() // drop an interrupt that landed after the read completed
  }

  /** Cuts short an in-flight [[poll]].
    *
    * JLine's reader waits on a monitor and converts an interrupt into an `InterruptedIOException` that it throws *and
    * clears* (`NonBlockingReaderImpl.read`), so the reader stays usable and no buffered input is lost.
    */
  def wake(): Unit =
    woken.set(true)
    // a thread never interrupts its own read: the `woken` flag above already sends it back round the loop
    pollingThread.get().filter(_ ne Thread.currentThread()).foreach(_.interrupt())

private[terminal] object EventPump:

  /** A resize a signal handler posted: the size it read, and whether it must be delivered even unchanged. */
  final case class PendingResize(size: Size, forced: Boolean)
