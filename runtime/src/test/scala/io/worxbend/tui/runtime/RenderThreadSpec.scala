package io.worxbend.tui.runtime

import org.scalatest.funsuite.AnyFunSuite

final class RenderThreadSpec extends AnyFunSuite:

  test("the guard is a no-op while no render thread is registered"):
    assert(RenderThread.isRenderThread)
    RenderThread.checkRenderThread() // must not throw

  test("Signal.set works without a registered render thread"):
    val signal = Signal(1)
    signal.set(2)
    assert(signal.peek == 2)

  test("checkRenderThread throws from a foreign thread while one is registered"):
    RenderThread.register(Thread.currentThread())
    try
      RenderThread.checkRenderThread() // current thread is the render thread: fine
      var thrown: Option[Throwable] = None
      val foreign                   = Thread { () =>
        try RenderThread.checkRenderThread()
        catch case error: IllegalStateException => thrown = Some(error)
      }
      foreign.start()
      foreign.join()
      assert(thrown.exists { case _: IllegalStateException => true; case _ => false })
    finally RenderThread.unregister()

  test("Signal.set from a foreign thread is a defect while a render thread is registered"):
    RenderThread.register(Thread.currentThread())
    try
      val signal                    = Signal(1)
      var thrown: Option[Throwable] = None
      val foreign                   = Thread { () =>
        try signal.set(2)
        catch case error: IllegalStateException => thrown = Some(error)
      }
      foreign.start()
      foreign.join()
      assert(thrown.nonEmpty)
      assert(signal.peek == 1)
      // the exception message is the only guidance the author of the offending line gets, so it has to name both
      // remedies, not just the fault
      val message                   = thrown.flatMap(error => Option(error.getMessage)).getOrElse("")
      assert(message.contains("not '"), s"the offending thread is not named: $message")
      assert(message.contains("Async.run"), s"the usual remedy is not named: $message")
      assert(message.contains("RenderThread.runOnRenderThread"), s"the explicit hop is not named: $message")
    finally RenderThread.unregister()

  test("runOnRenderThread runs inline when already on the render thread"):
    var ran = false
    RenderThread.runOnRenderThread { ran = true }
    assert(ran)

  test("runLater queues work until the runner drains it"):
    var ran = false
    RenderThread.runLater { ran = true }
    assert(!ran)
    RenderThread.drainPending()
    assert(ran)

  test("runOnRenderThread queues from a foreign thread and drains on the render thread"):
    RenderThread.register(Thread.currentThread())
    try
      var ran     = false
      val foreign = Thread(() => RenderThread.runOnRenderThread { ran = true })
      foreign.start()
      foreign.join()
      assert(!ran)
      RenderThread.drainPending()
      assert(ran)
    finally RenderThread.unregister()

  test("a nested registration unregisters back to the outer runner instead of leaving the guard open"):
    val outer = RenderThread.register(Thread.currentThread())
    try
      val inner = RenderThread.register(Thread.currentThread()) // a runner started from inside another runner's loop
      assert(inner ne outer)
      RenderThread.unregister() // the inner runner exits; the outer one is still running this thread

      var thrown: Option[Throwable] = None
      val foreign                   = Thread { () =>
        try RenderThread.checkRenderThread()
        catch case error: IllegalStateException => thrown = Some(error)
      }
      foreign.start()
      foreign.join()
      assert(thrown.nonEmpty, "the guard must not fail open once the nested runner unregistered")

      var ran = false
      RenderThread.runLater { ran = true }
      outer.drain() // the outer runner's own queue, not the unattributed one
      assert(ran, "work queued after the nested runner exited belongs to the outer runner's queue")
    finally RenderThread.unregister()

  test("with no runner registered runOnRenderThread degrades to running inline on the caller's thread"):
    // documents a known limitation: with nothing to marshal onto, background work updates state where it already is
    var ranOn   = Option.empty[String]
    val foreign = Thread(() => RenderThread.runOnRenderThread { ranOn = Some(Thread.currentThread().getName) })
    foreign.setName("foreign-worker")
    foreign.start()
    foreign.join()
    assert(ranOn.contains("foreign-worker"))

  test("work queued with no runner registered is drained by whichever runner starts next"):
    // documents a known limitation: the unattributed queue is process-wide, so a continuation left over from one app
    // runs on the next app's render thread
    var ran  = false
    RenderThread.runLater { ran = true }
    assert(!ran)
    val loop = RenderThread.register(Thread.currentThread())
    try
      RenderThread.drainPending(loop)
      assert(ran)
    finally RenderThread.unregister()

  test("the unattributed queue is bounded, keeping the newest work and counting what it dropped"):
    // the one queue nobody owns: an `Async.every` armed before any runner starts fills it, and nothing is guaranteed
    // ever to drain it. Unbounded, that is a leak for the life of the JVM and then a flood the moment a runner arrives
    RenderThread.drainPending() // start from an empty queue whatever ran before this test
    val cap         = RenderThread.DetachedQueueLimit
    val queued      = cap * 2
    val dropsBefore = RenderThread.detachedDrops
    val ran         = scala.collection.mutable.ArrayBuffer.empty[Int]
    (1 to queued).foreach(i => RenderThread.runLater { val _ = ran.append(i) })

    assert(RenderThread.detachedDrops - dropsBefore == queued - cap, "the queue grew past its cap")
    RenderThread.drainPending()
    assert(ran.size == cap, s"${ran.size} bodies survived a cap of $cap")
    // the oldest went, not the newest: these are continuations of work that has already happened, so the ones
    // describing the most recent state are the ones worth keeping
    assert(ran.head == queued - cap + 1)
    assert(ran.last == queued)

  test("registered runners cannot mutate a signal bound to another runner"):
    val signal = Signal(1)
    RenderThread.register(Thread.currentThread())
    try
      signal.set(1) // even an unchanged write establishes ownership
      var thrown: Option[Throwable] = None
      val otherRunner               = Thread { () =>
        RenderThread.register(Thread.currentThread())
        try
          try signal.set(2)
          catch case error: IllegalStateException => thrown = Some(error)
        finally RenderThread.unregister()
      }
      otherRunner.start()
      otherRunner.join()
      assert(thrown.nonEmpty)
      assert(signal.peek == 1)
    finally RenderThread.unregister()

  test("a throwing queued body neither stops the drain nor drops the bodies queued behind it"):
    val seen  = scala.collection.mutable.ArrayBuffer.empty[Throwable]
    val order = scala.collection.mutable.ArrayBuffer.empty[String]
    val loop  = RenderThread.register(Thread.currentThread(), () => (), error => { val _ = seen.append(error) })
    try
      RenderThread.runLater(order.append("first"))
      RenderThread.runLater(throw RuntimeException("boom"))
      RenderThread.runLater(order.append("third"))
      RenderThread.drainPending(loop)
      assert(order.toList == List("first", "third"), "the drain stopped at the throwing body")
      assert(seen.map(_.getMessage).toList == List("boom"), "the throwable was dropped instead of reported")

      RenderThread.runLater(order.append("fourth"))
      RenderThread.drainPending(loop)
      assert(order.toList == List("first", "third", "fourth"), "the loop is unusable after a failing body")
    finally RenderThread.unregister()

  test("the installed handler receives the throwable itself, not a wrapper"):
    val seen = scala.collection.mutable.ArrayBuffer.empty[Throwable]
    val loop = RenderThread.register(Thread.currentThread(), () => (), error => { val _ = seen.append(error) })
    try
      RenderThread.runLater(throw IllegalStateException("queued"))
      RenderThread.drainPending(loop)
      assert(seen.size == 1)
      seen.head match
        case error: IllegalStateException => assert(error.getMessage == "queued")
        case other                        => fail(s"expected the original IllegalStateException, got $other")
    finally RenderThread.unregister()

  test("a fatal error from a queued body still propagates past the drain"):
    // NonFatal deliberately excludes LinkageError: absorbing a JVM-level failure would hide a broken process
    val loop = RenderThread.register(Thread.currentThread(), () => (), _ => fail("the handler saw a fatal error"))
    try
      RenderThread.runLater(throw LinkageError("fatal"))
      assertThrows[LinkageError](RenderThread.drainPending(loop))
    finally RenderThread.unregister()

  test("a failure from unattributed work is reported through the draining runner's handler"):
    // the unattributed queue has no owner of its own, so the runner that drains it is the one that would otherwise die
    val seen = scala.collection.mutable.ArrayBuffer.empty[Throwable]
    RenderThread.runLater(throw RuntimeException("detached boom"))
    val loop = RenderThread.register(Thread.currentThread(), () => (), error => { val _ = seen.append(error) })
    try
      RenderThread.drainPending(loop) // must not throw
      assert(seen.map(_.getMessage).toList == List("detached boom"))
    finally RenderThread.unregister()

  test("two render threads can be registered at once without racing each other"):
    RenderThread.register(Thread.currentThread())
    try
      @volatile var otherOk = false
      val other             = Thread { () =>
        RenderThread.register(Thread.currentThread())
        try
          RenderThread.checkRenderThread() // both threads are render threads
          otherOk = true
        finally RenderThread.unregister()
      }
      other.start()
      other.join()
      assert(otherOk)
      RenderThread.checkRenderThread() // still registered here after the other unregistered itself
    finally RenderThread.unregister()
