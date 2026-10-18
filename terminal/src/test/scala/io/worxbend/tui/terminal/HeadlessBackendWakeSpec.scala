package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Event, KeyEvent, Size}

import org.scalatest.funsuite.AnyFunSuite

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration.{Duration, DurationInt}

/** [[HeadlessBackend.wake]] must honour the [[Backend.wake]] contract the JLine backend honours: an in-flight
  * `readEvent` returns promptly as `Right(None)`, so work queued onto the render thread — and a `Pilot` waiting on it —
  * is not held hostage to the runner's poll timeout, which is the whole tick rate when the app has one.
  */
final class HeadlessBackendWakeSpec extends AnyFunSuite:

  private def key(c: Char): Event = Event.Key(KeyEvent.char(c))

  /** Runs `readEvent(timeout)` on its own thread and answers what it returned, or `None` if it had not returned after
    * `wait`. A read that never returns is the defect under test, so it must fail the test rather than hang it; the
    * reader thread is a daemon and is released with a posted event on the way out.
    */
  private def readOnThread(
      backend: HeadlessBackend,
      timeout: Duration,
      wait: Duration,
  )(during: => Unit): Option[Either[BackendError, Option[Event]]] =
    val started = CountDownLatch(1)
    val done    = CountDownLatch(1)
    val result  = AtomicReference[Option[Either[BackendError, Option[Event]]]](None)
    val reader  = Thread(
      () =>
        started.countDown()
        result.set(Some(backend.readEvent(timeout)))
        done.countDown()
      ,
      "headless-wake-reader",
    )
    reader.setDaemon(true)
    reader.start()
    assert(started.await(2, TimeUnit.SECONDS))
    Thread.sleep(50) // let the reader park inside the queue poll before acting on it
    during
    val finished = done.await(wait.toMillis, TimeUnit.MILLISECONDS)
    if !finished then backend.postEvent(key('!')) // release a reader that ignored the wake, so the JVM can exit
    if finished then result.get() else None

  test("wake() from another thread returns an in-flight readEvent(Duration.Inf) as Right(None)"):
    // the infinite path has no poll slice to fall back on: only the wake can end it
    val backend = HeadlessBackend(Size(10, 2))
    val got     = readOnThread(backend, Duration.Inf, 2.seconds)(backend.wake())
    assert(got == Some(Right(None)), s"readEvent did not return within 2 s after wake(): $got")
    assert(backend.wakeCount == 1)

  test("wake() from another thread returns an in-flight readEvent(3.seconds) within a second as Right(None)"):
    val backend = HeadlessBackend(Size(10, 2))
    val begun   = System.nanoTime()
    val got     = readOnThread(backend, 3.seconds, 2.seconds)(backend.wake())
    val elapsed = (System.nanoTime() - begun) / 1_000_000L
    assert(got == Some(Right(None)), s"readEvent answered $got")
    // 50 ms of that is the park above; the bound excludes the 3 s poll a counter-only wake left in place, with headroom
    // for a loaded CI runner
    assert(elapsed < 1000L, s"readEvent took $elapsed ms after the wake")

  test("an idle readEvent(3.seconds) advances idleReads before the timeout expires"):
    // `Pilot.waitForIdle` needs an idle read within its 2 s deadline whatever the tick rate; the poll is sliced so
    // one arrives, and the early `Right(None)` is what `Backend.readEvent`'s contract already permits
    val backend = HeadlessBackend(Size(10, 2))
    val begun   = System.nanoTime()
    assert(backend.readEvent(3.seconds) == Right(None))
    val elapsed = (System.nanoTime() - begun) / 1_000_000L
    assert(elapsed < 1000L, s"the idle read took $elapsed ms")
    assert(backend.idleReads == 1)

  test("pendingEvents counts posted events, not wake tokens"):
    val backend = HeadlessBackend(Size(10, 2))
    backend.wake()
    backend.wake()
    assert(backend.pendingEvents == 0)
    backend.postEvent(key('a'))
    backend.postEvent(key('b'))
    assert(backend.pendingEvents == 2)
    val _       = backend.readEvent(10.millis) // the token
    assert(backend.readEvent(10.millis) == Right(Some(key('a'))))
    assert(backend.pendingEvents == 1)

  test("postEvent after wake is still delivered in order"):
    val backend = HeadlessBackend(Size(10, 2))
    backend.wake()
    backend.postEvent(key('a'))
    backend.postEvent(key('b'))
    assert(backend.readEvent(10.millis) == Right(None)) // the wake, answered first because it was asked first
    assert(backend.readEvent(10.millis) == Right(Some(key('a'))))
    assert(backend.readEvent(10.millis) == Right(Some(key('b'))))

  test("a wake that finds the queue empty counts as an idle read; one that finds a posted event does not"):
    val backend = HeadlessBackend(Size(10, 2))
    backend.wake()
    assert(backend.readEvent(10.millis) == Right(None))
    assert(backend.idleReads == 1, "a wake with nothing posted is an idle look at the queue")
    backend.wake()
    backend.postEvent(key('x'))
    assert(backend.readEvent(10.millis) == Right(None)) // the token, with `x` queued behind it
    assert(backend.idleReads == 1, "a wake with an event waiting is not an idle read")
    assert(backend.readEvent(10.millis) == Right(Some(key('x'))))
    assert(backend.idleReads == 1)

  test("ten wakes with no reader cost one token"):
    // one spurious loop iteration for a burst of wakes, not ten: the event posted after them is the *second* read
    val backend = HeadlessBackend(Size(10, 2))
    for _ <- 1 to 10 do backend.wake()
    backend.postEvent(key('k'))
    assert(backend.wakeCount == 10)
    assert(backend.readEvent(10.millis) == Right(None))
    assert(backend.readEvent(10.millis) == Right(Some(key('k'))))
