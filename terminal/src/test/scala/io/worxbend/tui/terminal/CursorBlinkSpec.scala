package io.worxbend.tui.terminal

import io.worxbend.tui.core.{Buffer, Event, Size}

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.Duration

/** The caret-blink contract: the escape sequences, the defaulted [[Backend]] member, and what [[HeadlessBackend]]
  * records so a test above this module can assert on it.
  *
  * [[JLineContractSpec]] exercises normal JLine teardown and handover through a real writer; this suite pins the
  * sequences and the deliberate *absence* of the re-enable from the unconditional `RestoreAll` string.
  */
final class CursorBlinkSpec extends AnyFunSuite:

  private val Esc = ""

  /** The smallest thing that is a `Backend`: it overrides only the abstract members, so `setCursorBlink` here is the
    * trait's own default.
    */
  private final class BareBackend extends Backend:
    def size: Either[BackendError, Size]                                  = Right(Size(10, 3))
    def draw(buffer: Buffer): Either[BackendError, Unit]                  =
      val _ = buffer
      Right(())
    def enableRawMode(): Either[BackendError, Unit]                       = Right(())
    def disableRawMode(): Either[BackendError, Unit]                      = Right(())
    def enterAlternateScreen(): Either[BackendError, Unit]                = Right(())
    def leaveAlternateScreen(): Either[BackendError, Unit]                = Right(())
    def enableMouseCapture(): Either[BackendError, Unit]                  = Right(())
    def disableMouseCapture(): Either[BackendError, Unit]                 = Right(())
    def hideCursor(): Either[BackendError, Unit]                          = Right(())
    def showCursor(): Either[BackendError, Unit]                          = Right(())
    def readEvent(timeout: Duration): Either[BackendError, Option[Event]] =
      val _ = timeout
      Right(None)
    def close(): Either[BackendError, Unit]                               = Right(())

  test("the sequences are the DECSET and DECRST forms of mode 12"):
    assert(AnsiSequences.EnableCursorBlink == s"$Esc[?12h")
    assert(AnsiSequences.DisableCursorBlink == s"$Esc[?12l")

  test("re-enabling blink is deliberately not part of the unconditional restore string"):
    // RestoreAll is written blind by a shutdown hook that cannot know what the app enabled, so it holds mode *resets*
    // only — those are idempotent. Turning blink back on is not in that class: it would overwrite the preference of a
    // user whose emulator runs a steady caret, for every glyphora app that exits, including ones that never asked.
    assert(!AnsiSequences.RestoreAll.contains(AnsiSequences.EnableCursorBlink))
    // ShowCursor by contrast *is* there, which is the pair worth reading together: hiding the cursor is something only
    // this library does, so putting it back is repairing our own change rather than overriding the user's.
    assert(AnsiSequences.RestoreAll.contains(AnsiSequences.ShowCursor))

  test("setCursorBlink defaults to a successful no-op for a backend with no real caret"):
    // the default is what keeps this addition source-compatible: a Backend written against 0.12.0 still compiles
    val backend: Backend = BareBackend()
    assert(backend.setCursorBlink(false) == Right(()))
    assert(backend.setCursorBlink(true) == Right(()))

  test("a headless backend starts blinking, because that is the terminal's own default"):
    // a test asserting `false` is therefore asserting the app really did ask, not merely that nothing has happened yet
    assert(HeadlessBackend(Size(20, 5)).isCursorBlinking)

  test("the headless backend records what the app asked for, in both directions"):
    val backend = HeadlessBackend(Size(20, 5))
    assert(backend.setCursorBlink(false) == Right(()))
    assert(!backend.isCursorBlinking)
    assert(backend.setCursorBlink(true) == Right(()))
    assert(backend.isCursorBlinking)

  test("closing the backend puts the blink back, the same way it puts cursor visibility back"):
    // `close()` models a terminal handed back to the shell: an app that left the caret steady must not leave the user's
    // own prompt steady too, and a test that reuses the backend afterwards should see a clean terminal
    val backend = HeadlessBackend(Size(20, 5))
    val _       = backend.setCursorBlink(false)
    val _       = backend.hideCursor()
    assert(backend.close() == Right(()))
    assert(backend.isCursorBlinking)
    assert(backend.isCursorVisible)

  test("blink is independent of visibility — a hidden caret keeps whatever blink it was given"):
    // the two are separate modes (DECSET 25 and DECSET 12), and conflating them would make an app that hides the caret
    // between frames silently undo a blink setting it asked for once at start-up
    val backend = HeadlessBackend(Size(20, 5))
    val _       = backend.setCursorBlink(false)
    val _       = backend.hideCursor()
    assert(!backend.isCursorBlinking)
    val _       = backend.showCursor()
    assert(!backend.isCursorBlinking)
