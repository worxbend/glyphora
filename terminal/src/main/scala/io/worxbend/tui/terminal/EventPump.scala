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

  private val pendingResize    = AtomicReference[Option[Size]](None)
  private val pendingInterrupt = AtomicBoolean(false)
  private val woken            = AtomicBoolean(false)
  // the thread currently parked in `blockingRead`, if any, so `wake` knows whom to interrupt
  private val pollingThread    = AtomicReference[Option[Thread]](None)

  /** Queues the coalesced resize the next [[poll]] reports, and wakes an in-flight poll so it is seen promptly. Safe
    * from any thread.
    */
  def postResize(size: Size): Unit =
    pendingResize.set(Some(size))
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
    if pendingInterrupt.getAndSet(false) then Right(Some(Event.Interrupt))
    else
      pendingResize.getAndSet(None) match
        case Some(resized) => Right(Some(Event.Resize(resized)))
        case None          =>
          // something queued render-thread work while we were away: go round the loop instead of blocking again
          if woken.getAndSet(false) then Right(None) else blockingRead(timeout)

  private def blockingRead(timeout: Duration): Either[BackendError, Option[Event]] =
    pollingThread.set(Some(Thread.currentThread()))
    // Re-checked *after* registering, closing the window this registration opens: a wake that landed between `poll`'s
    // check and this registration found nobody to interrupt, so without this second look the render thread parks for
    // the whole timeout — up to a tick interval, or 100 ms with no tick rate — before draining the work that wake was
    // announcing. That is exactly the latency `wake()` exists to remove.
    try if woken.getAndSet(false) then Right(None) else Right(decoder.decode(JLine3Backend.readTimeoutMillis(timeout)))
    catch
      // A paste retains its progress and yields None; a CSI scan tears its sequence. Other interrupted reads reach
      // this boundary, where a wake is also "nothing arrived", never an I/O failure.
      case _: InterruptedIOException => Right(None)
      case NonFatal(error)           => Left(BackendError.Io(error))
    finally
      pollingThread.set(None) // no read is in flight any more: `wake` has nobody to interrupt
      val _ = Thread.interrupted() // drop an interrupt that landed after the read completed

  /** Cuts short an in-flight [[poll]].
    *
    * JLine's reader waits on a monitor and converts an interrupt into an `InterruptedIOException` that it throws *and
    * clears* (`NonBlockingReaderImpl.read`), so the reader stays usable and no buffered input is lost.
    */
  def wake(): Unit =
    woken.set(true)
    // a thread never interrupts its own read: the `woken` flag above already sends it back round the loop
    pollingThread.get().filter(_ ne Thread.currentThread()).foreach(_.interrupt())
