package io.worxbend.tui.runtime

import io.worxbend.tui.core.Size
import io.worxbend.tui.terminal.{BackendError, HeadlessBackend}

import org.scalatest.funsuite.AnyFunSuite

/** [[RunnerError.message]] and the identity-bound [[RunnerError.cleanupFailures]]: the one line an app prints has to
  * admit that teardown failed too, and the record it reads that from lives on the instance the runner handed back, not
  * in the enum case's value.
  */
final class RunnerErrorSpec extends AnyFunSuite:

  test("message reports how many cleanup failures followed the primary failure"):
    val first   = IllegalStateException("first stop failure")
    val second  = IllegalStateException("second stop failure")
    val closing = java.io.IOException("device gone")
    val backend = HeadlessBackend(Size(20, 3))
    backend.failNext(HeadlessBackend.Op.Close, BackendError.Io(closing))
    val result  = TerminalRunner(backend, onStop = () => Cleanup.all(() => throw first, () => throw second))
      .run(_.quit(), (_, _) => EventOutcome.Ignored, _ => ())
    result match
      case Left(error) =>
        assert(error.cleanupFailures == Vector(second, closing))
        assert(error.message == "an app callback threw on the render thread: first stop failure (+2 cleanup failures)")
      case Right(())   => fail("the stop failure was not reported")

  test("a single cleanup failure is reported in the singular, on every error case"):
    val handler = RunnerError.Handler(IllegalStateException("boom"))
    handler.addCleanupFailure(IllegalStateException("close failed"))
    assert(handler.message == "an app callback threw on the render thread: boom (+1 cleanup failure)")
    val backend = RunnerError.Backend(BackendError.UnsupportedTerminal("no tty"))
    backend.addCleanupFailure(IllegalStateException("close failed"))
    assert(backend.message == "terminal not supported: no tty (+1 cleanup failure)")
    val queued  = RunnerError.QueuedTask(QueuedTaskFailures(IllegalStateException("task"), 2))
    queued.addCleanupFailure(IllegalStateException("close failed"))
    assert(queued.message.endsWith("the first was: task (+1 cleanup failure)"))

  test("a message with no cleanup failures is unchanged"):
    val error = RunnerError.Handler(IllegalStateException("boom"))
    assert(error.message == "an app callback threw on the render thread: boom")

  test("cleanupFailures are identity-bound: equality, hashCode and toString see only the case's own fields"):
    val primary = IllegalStateException("boom")
    val bare    = RunnerError.Handler(primary)
    val loaded  = RunnerError.Handler(primary)
    loaded.addCleanupFailure(IllegalStateException("close failed"))
    assert(loaded.cleanupFailures.size == 1)
    assert(bare.cleanupFailures.isEmpty)
    assert(bare == loaded)
    assert(bare.hashCode == loaded.hashCode)
    assert(bare.toString == loaded.toString)
    assert(bare.message != loaded.message, "message is the one derived view that does report them")
