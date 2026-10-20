package io.worxbend.tui.runtime

import io.worxbend.tui.core.{Buffer, Event, KeyCode, KeyEvent, Size}
import io.worxbend.tui.terminal.{Backend, BackendError, HeadlessBackend}
import io.worxbend.tui.terminal.HeadlessBackend.Op

import org.scalatest.funsuite.AnyFunSuite

import java.io.IOException
import scala.concurrent.duration.Duration

/** The lifecycle calls [[BackendFailureSpec]]'s recording fake reports on, in the order it saw them.
  *
  * Top level rather than nested in the suite so it is a plain sealed sum a test can list in an expected `List`, which
  * is the assertion this file is written around: the ordering of these calls *is* the contract, and an ordering
  * assertion made against strings would pass against a typo.
  */
private enum BackendStep:
  case RawMode, AlternateScreen, HideCursor, Draw, ReadEvent, Close, EmergencyRestore

/** What a [[Runner]] does when the terminal underneath it fails.
  *
  * [[io.worxbend.tui.terminal.Backend]]'s central design claim is that failures are values — `Either[BackendError, A]`
  * rather than exceptions — because a caller can meaningfully degrade. Above `terminal` that claim had no end-to-end
  * coverage at all, because `HeadlessBackend` could not fail and `emergencyRestore` was named by no test in the
  * repository. The two halves covered here are the ones a user actually feels: a setup that dies half-way must still
  * hand the shell back, and a failure mid-run must end the run as a value rather than by unwinding past the restore.
  */
final class BackendFailureSpec extends AnyFunSuite:

  /** Records every lifecycle call in order and fails whichever one a test names.
    *
    * `HeadlessBackend` is `final` and models state rather than call order, and the pair this file exists to pin —
    * `close()` *followed by* `emergencyRestore()` — is an ordering, not a state. So this is a fake of its own, in the
    * idiom `RunnerLifecycleSpec` already uses for the same reason.
    *
    * `readEvent` answers with `q` on every call, so a run that gets as far as its loop ends at the first event a
    * handler quits on instead of blocking the suite.
    */
  private final class RecordingBackend(
      failAt: Option[BackendStep] = None,
      error: BackendError = BackendError.NotInRawMode,
  ) extends Backend:

    private val recorded = scala.collection.mutable.ArrayBuffer.empty[BackendStep]

    /** Every lifecycle call this backend saw, in the order it saw them. */
    def calls: List[BackendStep] = recorded.synchronized(recorded.toList)

    private def note(step: BackendStep): Unit = recorded.synchronized { val _ = recorded += step }

    private def attempt(step: BackendStep): Either[BackendError, Unit] =
      note(step)
      if failAt.contains(step) then Left(error) else Right(())

    def size: Either[BackendError, Size]                                  = Right(Size(20, 3))
    def draw(buffer: Buffer): Either[BackendError, Unit]                  = attempt(BackendStep.Draw)
    def enableRawMode()                                                   = attempt(BackendStep.RawMode)
    def disableRawMode()                                                  = Right(())
    def enterAlternateScreen()                                            = attempt(BackendStep.AlternateScreen)
    def leaveAlternateScreen()                                            = Right(())
    def enableMouseCapture()                                              = Right(())
    def disableMouseCapture()                                             = Right(())
    def hideCursor()                                                      = attempt(BackendStep.HideCursor)
    def showCursor()                                                      = Right(())
    def readEvent(timeout: Duration): Either[BackendError, Option[Event]] =
      note(BackendStep.ReadEvent)
      Right(Some(Event.Key(KeyEvent.of(KeyCode.Char('q')))))
    def close(): Either[BackendError, Unit]                               = attempt(BackendStep.Close)
    override def emergencyRestore(): Unit                                 = note(BackendStep.EmergencyRestore)

  private def quitOnAnything(event: Event, handle: RunnerHandle): EventOutcome =
    val _ = event
    handle.quit()
    EventOutcome.Ignored

  test("fullscreen setup selects its screen before acquiring raw-mode keyboard flags and hiding the cursor"):
    // Kitty keyboard stacks are per screen: acquire input modes only after choosing the screen they will dress.
    // Hiding remains last, and any partial setup is still closed and emergency-restored.
    val backend = RecordingBackend()
    val result  = TerminalRunner(backend).run(_ => (), quitOnAnything, _ => ())
    assert(result.isRight, s"a backend that fails nothing should run cleanly: $result")
    assert(backend.calls.take(3) == List(BackendStep.AlternateScreen, BackendStep.RawMode, BackendStep.HideCursor))
    assert(backend.calls.last == BackendStep.Close, s"the terminal was not handed back last: ${backend.calls}")
    assert(
      !backend.calls.contains(BackendStep.EmergencyRestore),
      "a run that ended cleanly used the last-resort restore path anyway",
    )

  test("a fullscreen setup that cannot enter raw mode releases its already-selected alternate screen"):
    val boom    = BackendError.Io(IOException("not a tty"))
    val backend = RecordingBackend(failAt = Some(BackendStep.RawMode), error = boom)
    val result  = TerminalRunner(backend).run(_ => (), quitOnAnything, _ => ())
    assert(result == Left(RunnerError.Backend(boom)))
    assert(
      backend.calls == List(
        BackendStep.AlternateScreen,
        BackendStep.RawMode,
        BackendStep.Close,
        BackendStep.EmergencyRestore,
      )
    )

  test("a setup that fails at the alternate screen closes the backend and then restores it in the emergency"):
    // both teardowns, in this order: `close` is the tidy path and may itself fail on a terminal that just did, and
    // `emergencyRestore` is the one that cannot. Dropping either used to hand the shell back in raw mode.
    val boom     = BackendError.UnsupportedTerminal("no alternate screen")
    val backend  = RecordingBackend(failAt = Some(BackendStep.AlternateScreen), error = boom)
    var started  = false
    var composed = 0
    val result   = TerminalRunner(backend).run(
      _ => { started = true },
      quitOnAnything,
      _ => { composed += 1 },
    )
    assert(result == Left(RunnerError.Backend(boom)))
    assert(
      backend.calls == List(
        BackendStep.AlternateScreen,
        BackendStep.Close,
        BackendStep.EmergencyRestore,
      ),
      s"teardown order after a failed setup: ${backend.calls}",
    )
    assert(!started, "onStart ran although the terminal was never dressed")
    assert(composed == 0, "a frame was composed for a terminal setup could not dress")

  test("a setup that fails leaves nothing dressed and asks the backend for an emergency restore"):
    // the same contract against the real `HeadlessBackend`, which answers about *state* where the fake answers about
    // order: raw mode and the alternate screen were both entered before `hideCursor` failed, and both must be undone.
    val boom    = BackendError.UnsupportedTerminal("no cursor control")
    val backend = HeadlessBackend(Size(20, 3))
    backend.failNext(Op.HideCursor, boom)
    val result  = TerminalRunner(backend).run(_ => (), quitOnAnything, _ => ())
    assert(result == Left(RunnerError.Backend(boom)))
    assert(backend.emergencyRestoreCount == 1L, "the last-resort restore was never asked for")
    assert(!backend.isRawMode, "the shell was handed back in raw mode")
    assert(!backend.isAlternateScreen, "the shell was handed back on the alternate screen")
    assert(backend.drawCount == 0L, "a frame was flushed although setup failed")

  test("a draw that fails mid-run ends the run as a backend failure with the terminal handed back"):
    val boom    = BackendError.Io(IOException("broken pipe"))
    val backend = HeadlessBackend(Size(20, 3))
    backend.failNext(Op.Draw, boom)
    val result  = TerminalRunner(backend).run(_ => (), quitOnAnything, _ => ())
    assert(result == Left(RunnerError.Backend(boom)))
    assert(backend.drawCount == 0L, "the failed flush was counted as a frame")
    assert(backend.lastDrawn.isEmpty, "the frame that could not be flushed was retained as if it had been")
    assert(!backend.isRawMode, "raw mode was not restored")
    assert(!backend.isAlternateScreen, "the alternate screen was not left")
    assert(backend.isCursorVisible, "the cursor was left hidden")
    // the tidy path sufficed, so the last-resort one is not owed
    assert(backend.emergencyRestoreCount == 0L)

  test("an input read that fails ends the run rather than spinning on a dead stream"):
    // the first frame is already on screen when the stream dies, so this is the failure an app is most likely to meet
    // in production: a terminal closed underneath a running UI.
    val boom    = BackendError.Io(IOException("stream closed"))
    val backend = HeadlessBackend(Size(20, 3))
    backend.failNext(Op.ReadEvent, boom)
    val result  = TerminalRunner(backend).run(_ => (), quitOnAnything, _ => ())
    assert(result == Left(RunnerError.Backend(boom)))
    assert(backend.drawCount == 1L, "the frame drawn before the stream died is still owed")
    assert(!backend.isRawMode, "raw mode was not restored")
    assert(backend.isCursorVisible, "the cursor was left hidden")

  test("a failed backend error reaches the app as one printable line rather than a constructor call"):
    // what `TuiApp` puts on the user's terminal after the app has exited, so it is the string that has to read well
    val backend = HeadlessBackend(Size(20, 3))
    backend.failNext(Op.EnableRawMode, BackendError.UnsupportedTerminal("stdin is not a terminal"))
    val result  = TerminalRunner(backend).run(_ => (), quitOnAnything, _ => ())
    assert(result.left.exists(_.message == "terminal not supported: stdin is not a terminal"), s"reported: $result")

  test("an armed failure is spent by the operation it names and nothing after it"):
    // the seam's own contract. A failure that stayed armed would silently poison every later assertion in whatever
    // test used it, which is the one way a test double can make a suite lie.
    val backend = HeadlessBackend(Size(20, 3))
    backend.failNext(Op.EnableRawMode, BackendError.NotInRawMode)
    assert(backend.enableRawMode() == Left(BackendError.NotInRawMode))
    assert(!backend.isRawMode, "the armed operation took effect as well as failing")
    assert(backend.enableRawMode() == Right(()))
    assert(backend.isRawMode, "the arming outlived the call it was meant for")

  test("an unarmed backend behaves exactly as it always did"):
    // the compatibility half of the seam: every existing test in the repository drives one of these, so a backend
    // nothing armed has to be indistinguishable from the one that had no injection at all.
    val backend = HeadlessBackend(Size(20, 3))
    backend.postEvent(Event.Key(KeyEvent.of(KeyCode.Char('q'))))
    val result  = TerminalRunner(backend).run(_ => (), quitOnAnything, _ => ())
    assert(result.isRight, s"an unarmed backend failed a run: $result")
    assert(backend.drawCount >= 1L)
    assert(backend.emergencyRestoreCount == 0L)
    assert(!backend.isRawMode)
    assert(!backend.isAlternateScreen)
    assert(backend.isCursorVisible)
